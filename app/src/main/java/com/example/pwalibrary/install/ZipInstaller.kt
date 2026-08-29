package com.example.pwalibrary.install

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
    val manifest: WebManifest?,
    val fallbackName: String,
    val totalBytes: Long,
    /** Identity of last resort for zips that carry no manifest. */
    val sha256: String
)

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
    }

    // ---------------------------------------------------------------- staging

    fun stage(uri: Uri): StagedZip {
        val temp = File.createTempFile("import-", ".zip", context.cacheDir)
        try {
            val sha256 = copyUriToFile(uri, temp)
            val (prefix, totalBytes) = inspect(temp)
            val manifest = readManifest(temp, prefix)
            return StagedZip(
                tempZip = temp,
                rootPrefix = prefix,
                manifest = manifest,
                fallbackName = displayName(uri).removeSuffix(".zip").ifBlank { "アプリ" },
                totalBytes = totalBytes,
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

    /** Returns the detected root prefix and the declared uncompressed size. */
    private fun inspect(zip: File): Pair<String, Long> =
        openZip(zip).use { zf ->
            var count = 0
            var total = 0L
            var bestIndex: String? = null
            var bestDepth = Int.MAX_VALUE

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
            prefix to total
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

    private fun readManifest(zip: File, prefix: String): WebManifest? =
        openZip(zip).use { zf ->
            val entry = zf.getEntry("${prefix}manifest.json")
                ?: zf.getEntry("${prefix}manifest.webmanifest")
                ?: return@use null
            if (entry.size > 1024 * 1024) return@use null
            val text = zf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            WebManifest.parse(text)
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
        if (!staging.mkdirs()) throw InstallException("展開先を作成できませんでした。")

        try {
            extract(staged.tempZip, staged.rootPrefix, staging)
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
