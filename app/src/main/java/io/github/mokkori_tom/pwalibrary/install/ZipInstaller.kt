package io.github.mokkori_tom.pwalibrary.install

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

class InstallException(message: String) : Exception(message)

/**
 * A zip that has been copied out of the content provider and validated, but has
 * not touched the apps directory yet. Held so the caller can decide whether this
 * is a fresh install or an update before anything is overwritten.
 */
class StagedZip(
    val tempZip: File,
    /** "" for a flat zip, "myapp/" when the zip contains a wrapping folder. */
    val rootPrefix: String,
    /**
     * "" normally; "app/" when the manifest says the build expects to be served
     * from a subpath the zip does not contain. The files are extracted that far
     * down so the absolute paths the build emitted resolve.
     */
    val installSubpath: String,
    val manifest: WebManifest?,
    /** What the root index.html says about itself. Carries the load when there is no manifest. */
    val html: HtmlHead,
    val fallbackName: String,
    val totalBytes: Long,
    /** Identity of last resort for zips that carry no manifest. */
    val sha256: String
) {
    /**
     * Where this zip's own files sit inside an app directory.
     *
     * Everything that resolves a path the app wrote — its start_url, its icons —
     * has to start here rather than at the app directory, which is one level up
     * whenever [installSubpath] is set.
     */
    fun contentRootIn(appDir: File): File =
        if (installSubpath.isEmpty()) appDir else File(appDir, installSubpath)
}

/**
 * Extracts imported zips. Every zip here comes from an untrusted source (a chat
 * message, a download), so the validation below is load-bearing rather than
 * defensive polish.
 */
class ZipInstaller(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 20_000
        private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        private const val MAX_ZIP_BYTES = 256L * 1024 * 1024
        private const val COPY_BUFFER = 64 * 1024

        /** Long enough that no dialog is still open, short enough to matter. */
        private const val STALE_STAGE_MILLIS = 60L * 60 * 1000

        /**
         * How much of index.html is read looking for its head. Generous next to
         * any real head, and bounded because a single-file app can inline
         * megabytes of base64 into the very same document.
         */
        private const val MAX_HEAD_BYTES = 256 * 1024
    }

    // ---------------------------------------------------------------- staging

    fun stage(uri: Uri): StagedZip {
        val temp = File.createTempFile("import-", ".zip", context.cacheDir)
        try {
            val sha256 = copyUriToFile(uri, temp)
            val inspection = inspect(temp)
            val prefix = inspection.rootPrefix
            // One open for both: the manifest and the page are read from the
            // same central directory. The page is read first because it is what
            // says where a manifest not sitting at a conventional name lives.
            val (manifest, html) = openZip(temp).use { zf ->
                val head = readHtmlHead(zf, prefix)
                readManifest(zf, prefix, head.manifestHref) to head
            }
            return StagedZip(
                tempZip = temp,
                rootPrefix = prefix,
                installSubpath = InstallPaths.installSubpath(
                    manifest?.scope, manifest?.startUrl, inspection.topLevel
                ),
                manifest = manifest,
                html = html,
                fallbackName = displayName(uri).removeSuffix(".zip").ifBlank { "アプリ" },
                totalBytes = inspection.totalBytes,
                sha256 = sha256
            )
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    fun discard(staged: StagedZip) {
        staged.tempZip.delete()
    }

    /**
     * Drops staged zips no dialog can still be waiting on.
     *
     * A staged zip outlives its import while the user is being asked whether it
     * is an update, and that question dies with the process. Without this, every
     * killed prompt would leave a copy of the zip in the cache.
     */
    fun sweepStagedZips() {
        val cutoff = System.currentTimeMillis() - STALE_STAGE_MILLIS
        context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("import-") && it.name.endsWith(".zip") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    /** Copies the zip out of the content provider and returns its SHA-256. */
    private fun copyUriToFile(uri: Uri, dest: File): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw InstallException("ファイルを読み込めませんでした。")
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { src ->
            dest.outputStream().use { out ->
                val buf = ByteArray(COPY_BUFFER)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ZIP_BYTES) {
                        throw InstallException("zip が大きすぎます（上限 ${MAX_ZIP_BYTES / 1024 / 1024}MB）。")
                    }
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: ""
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    // ------------------------------------------------------------- validation

    /** What one pass over the central directory establishes about a zip. */
    private class Inspection(
        val rootPrefix: String,
        val totalBytes: Long,
        /** Names directly under the root, used to tell a declared subpath apart from a real one. */
        val topLevel: Set<String>
    )

    private fun inspect(zip: File): Inspection =
        openZip(zip).use { zf ->
            var count = 0
            var total = 0L
            var bestIndex: String? = null
            var bestDepth = Int.MAX_VALUE
            val names = ArrayList<String>()

            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (isNoise(name)) continue

                count++
                if (count > MAX_ENTRIES) {
                    throw InstallException("zip に含まれるファイルが多すぎます（上限 $MAX_ENTRIES 件）。")
                }

                // Reject absolute paths and traversal segments before anything
                // is written. The canonical-path check at extraction time is the
                // real guard; this just fails early with a clearer message.
                if (name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
                    throw InstallException("zip に不正なパスが含まれています: $name")
                }

                names += name

                if (!entry.isDirectory) {
                    val size = entry.size
                    if (size > 0) {
                        if (size > MAX_ENTRY_BYTES) {
                            throw InstallException("展開後のファイルが大きすぎます: $name")
                        }
                        total += size
                        if (total > MAX_TOTAL_BYTES) {
                            throw InstallException("展開後の合計サイズが大きすぎます（上限 ${MAX_TOTAL_BYTES / 1024 / 1024}MB）。")
                        }
                    }

                    // Zipping a folder produces "myapp/index.html"; zipping its
                    // contents produces "index.html". Anchor on whichever
                    // index.html sits shallowest and treat that as the root.
                    if (name.substringAfterLast('/') == "index.html") {
                        val depth = name.count { it == '/' }
                        if (depth < bestDepth) {
                            bestDepth = depth
                            bestIndex = name
                        }
                    }
                }
            }

            val index = bestIndex
                ?: throw InstallException("index.html が見つかりません。Web アプリの zip か確認してください。")
            val prefix = index.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

            // Collected after the prefix is known, because that is what the
            // names have to be relative to.
            val topLevel = names
                .filter { it.startsWith(prefix) }
                .mapNotNull {
                    it.removePrefix(prefix).substringBefore('/').takeIf { seg -> seg.isNotEmpty() }
                }
                .toSet()

            Inspection(prefix, total, topLevel)
        }

    /** Entries produced by macOS' Archive Utility and Finder that nobody wants extracted. */
    private fun isNoise(name: String): Boolean =
        name.startsWith("__MACOSX/") ||
            name.substringAfterLast('/') == ".DS_Store" ||
            name.substringAfterLast('/') == "Thumbs.db"

    private fun openZip(zip: File): ZipFile = try {
        ZipFile(zip)
    } catch (e: IOException) {
        throw InstallException("zip を開けませんでした。壊れている可能性があります。")
    }

    /**
     * Reads the manifest, wherever the zip keeps it.
     *
     * The two conventional names at the root are tried first, then whatever
     * `<link rel="manifest">` points at. The link is not tried first because it
     * is the page's claim rather than a fact about the zip, and a page that
     * links a manifest it did not ship should not stop the real one being read.
     */
    private fun readManifest(zf: ZipFile, prefix: String, href: String?): WebManifest? {
        val entry = zf.getEntry("${prefix}manifest.json")
            ?: zf.getEntry("${prefix}manifest.webmanifest")
            ?: href?.let { InstallPaths.entryForHref(prefix, it) }?.let { zf.getEntry(it) }
            ?: return null
        if (entry.isDirectory || entry.size > 1024 * 1024) return null
        val text = zf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        return WebManifest.parse(text)
    }

    /**
     * Reads the root index.html's head for a name and icons.
     *
     * This is the entry [inspect] derived the root prefix from, so it exists.
     * A manifest start_url pointing at some other page is not followed: when
     * there is a manifest it already answers both questions, and when there is
     * not, index.html is where the app starts.
     */
    private fun readHtmlHead(zf: ZipFile, prefix: String): HtmlHead {
        val entry = zf.getEntry("${prefix}index.html") ?: return HtmlHead.EMPTY
        return try {
            HtmlHead.parse(zf.getInputStream(entry).use { readAtMost(it, MAX_HEAD_BYTES) })
        } catch (e: Exception) {
            // Nothing here is worth failing an otherwise valid import over.
            HtmlHead.EMPTY
        }
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, COPY_BUFFER))
        val buf = ByteArray(COPY_BUFFER)
        var total = 0
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------- extraction

    /**
     * Extracts into a staging directory and swaps it into place, so a failure
     * part way through leaves the previously installed version untouched.
     */
    fun commit(staged: StagedZip, uuid: String): File {
        val target = Storage.appDir(context, uuid)
        val staging = Storage.stagingDir(context, uuid)
        staging.deleteRecursively()
        // Creates the subpath directories too, when the build asked for them.
        val contentRoot = staged.contentRootIn(staging)
        if (!contentRoot.mkdirs()) throw InstallException("展開先を作成できませんでした。")

        try {
            extract(staged.tempZip, staged.rootPrefix, contentRoot)
            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                throw InstallException("展開したファイルを配置できませんでした。")
            }
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }

        staged.tempZip.copyTo(Storage.originalZip(context, uuid), overwrite = true)
        return target
    }

    private fun extract(zip: File, prefix: String, destRoot: File) {
        val canonicalRoot = destRoot.canonicalPath + File.separator
        var written = 0L

        openZip(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (isNoise(name)) continue
                if (prefix.isNotEmpty() && !name.startsWith(prefix)) continue

                val relative = name.removePrefix(prefix)
                if (relative.isEmpty()) continue

                val out = File(destRoot, relative)

                // The check that actually matters: a crafted entry name such as
                // "../../databases/pwa_library.db" resolves outside destRoot, and
                // extracting it would let an imported zip overwrite app internals.
                if (!out.canonicalPath.startsWith(canonicalRoot)) {
                    throw InstallException("zip に不正なパスが含まれています: $name")
                }

                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }

                out.parentFile?.mkdirs()
                written += writeEntry(zf.getInputStream(entry), out, remaining = MAX_TOTAL_BYTES - written)
            }
        }
    }

    /**
     * Sizes in the central directory can lie, so the cap is enforced against the
     * bytes actually written rather than against ZipEntry.getSize().
     */
    private fun writeEntry(input: InputStream, dest: File, remaining: Long): Long {
        var written = 0L
        input.use { src ->
            dest.outputStream().use { out ->
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    written += n
                    if (written > MAX_ENTRY_BYTES || written > remaining) {
                        throw InstallException("展開後のサイズが上限を超えました。")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        return written
    }
}
