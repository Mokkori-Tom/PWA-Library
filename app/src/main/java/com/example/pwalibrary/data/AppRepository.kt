package com.example.pwalibrary.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.example.pwalibrary.install.IconStore
import com.example.pwalibrary.install.InstallException
import com.example.pwalibrary.install.StagedZip
import com.example.pwalibrary.install.Storage
import com.example.pwalibrary.install.WebManifest
import com.example.pwalibrary.install.ZipInstaller
import com.example.pwalibrary.shortcut.ShortcutHelper
import androidx.core.content.FileProvider
import com.example.pwalibrary.web.AppAssetRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface InstallOutcome {
    data class Installed(val app: AppEntity) : InstallOutcome
    data class Updated(val app: AppEntity, val previousVersion: String) : InstallOutcome
    data class Failed(val message: String) : InstallOutcome
}

class AppRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).appDao()
    private val grantDao = AppDatabase.get(context).folderGrantDao()
    private val installer = ZipInstaller(context)

    fun observeApps(): Flow<List<AppEntity>> = dao.observeAll()

    suspend fun find(uuid: String): AppEntity? = dao.findByUuid(uuid)

    /**
     * Imports a zip, replacing an existing app when the manifest identifies one.
     *
     * [forceUuid] pins the target when the user explicitly picked "update" on a
     * specific card; otherwise identity comes from the manifest, and a zip with
     * no manifest always installs as a new app.
     */
    suspend fun install(uri: Uri, forceUuid: String? = null): InstallOutcome = withContext(Dispatchers.IO) {
        val staged: StagedZip = try {
            installer.stage(uri)
        } catch (e: InstallException) {
            return@withContext InstallOutcome.Failed(e.message ?: "取り込みに失敗しました。")
        } catch (e: Exception) {
            return@withContext InstallOutcome.Failed("取り込みに失敗しました。")
        }

        try {
            val manifest = staged.manifest
            val manifestId = (manifest?.id ?: manifest?.name)?.let { normalizeManifestId(it) }

            // Needed before the lookup, because a relative id has to be paired
            // with the name to be safe to match on.
            val incomingName = manifest?.name?.takeIf { it.isNotBlank() }
                ?: manifest?.shortName?.takeIf { it.isNotBlank() }
                ?: staged.fallbackName

            // Falling back to the content hash means re-importing a zip this
            // app exported updates the entry it came from instead of adding a
            // duplicate, even when the zip has no manifest to identify it.
            val existing = when {
                forceUuid != null -> dao.findByUuid(forceUuid)
                else -> manifestId?.let { matchByManifest(it, incomingName) }
                    ?: dao.findByZipHash(staged.sha256)
            }

            // Reusing the uuid keeps the https origin stable, which is what keeps
            // the mini-app's localStorage and IndexedDB alive across an update.
            val uuid = existing?.uuid ?: UUID.randomUUID().toString()
            val appDir = installer.commit(staged, uuid)

            // For a zip with no manifest, an existing name wins over the file
            // name so that renaming the file does not rename the app.
            val name = manifest?.name?.takeIf { it.isNotBlank() }
                ?: manifest?.shortName?.takeIf { it.isNotBlank() }
                ?: existing?.name
                ?: staged.fallbackName

            val icon = IconStore.extract(context, uuid, appDir, manifest)
            val now = System.currentTimeMillis()

            val entity = AppEntity(
                id = existing?.id ?: 0,
                uuid = uuid,
                manifestId = manifestId ?: existing?.manifestId,
                name = name,
                shortName = manifest?.shortName ?: existing?.shortName ?: "",
                description = manifest?.description ?: existing?.description ?: "",
                iconPath = icon?.absolutePath,
                startUrl = resolveStartUrl(manifest, appDir),
                displayMode = manifest?.display ?: existing?.displayMode ?: "standalone",
                themeColor = manifest?.themeColor ?: existing?.themeColor,
                zipFilePath = Storage.originalZip(context, uuid).absolutePath,
                appDirPath = appDir.absolutePath,
                sizeBytes = directorySize(appDir),
                zipSha256 = staged.sha256,
                shortcutId = existing?.shortcutId,
                // Only updates need it: a fresh install has no worker yet.
                needsStorageReset = existing != null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                version = manifest?.version ?: existing?.version ?: "1.0",
                category = existing?.category ?: "",
                tags = existing?.tags ?: "[]"
            )

            if (existing == null) {
                val id = dao.insert(entity)
                InstallOutcome.Installed(entity.copy(id = id))
            } else {
                dao.update(entity)
                // The origin is unchanged, but the directory behind it was swapped.
                AppAssetRegistry.forget(uuid)
                ShortcutHelper.refresh(context, entity)
                InstallOutcome.Updated(entity, existing.version)
            }
        } catch (e: InstallException) {
            InstallOutcome.Failed(e.message ?: "展開に失敗しました。")
        } catch (e: Exception) {
            InstallOutcome.Failed("展開に失敗しました。")
        } finally {
            installer.discard(staged)
        }
    }

    fun observeGrants(appUuid: String): Flow<List<FolderGrant>> = grantDao.observeForApp(appUuid)

    suspend fun revokeGrant(grant: FolderGrant) = withContext(Dispatchers.IO) {
        grantDao.deleteById(grant.id)
        releaseIfUnused(grant.treeUri)
    }

    /**
     * SAF permissions outlive the app entry unless released, and the per-app cap
     * on persisted uris is finite, so they have to be handed back on delete.
     */
    private suspend fun releaseIfUnused(treeUri: String) {
        if (grantDao.countByTree(treeUri) > 0) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    suspend fun delete(app: AppEntity) = withContext(Dispatchers.IO) {
        val trees = grantDao.forApp(app.uuid).map { it.treeUri }
        grantDao.deleteForApp(app.uuid)
        trees.forEach { releaseIfUnused(it) }
        dao.delete(app)
        AppAssetRegistry.forget(app.uuid)
        Storage.deleteAllFor(context, app.uuid)
        if (app.shortcutId != null) {
            ShortcutHelper.disable(context, app.uuid)
        }
    }

    /**
     * Copies the app's original zip to a location the user picked. The zip is
     * the one that was imported, byte for byte, rather than a re-zip of the
     * extracted files, so what they share is exactly what they received.
     */
    suspend fun exportTo(app: AppEntity, dest: Uri): String? = withContext(Dispatchers.IO) {
        val source = File(app.zipFilePath)
        if (!source.isFile) return@withContext null
        try {
            val out = context.contentResolver.openOutputStream(dest) ?: return@withContext null
            out.use { sink -> source.inputStream().use { it.copyTo(sink) } }
            // The picker lets the user rename, so report the name that was
            // actually written rather than the one that was suggested.
            displayNameOf(dest) ?: exportFileName(app)
        } catch (e: Exception) {
            null
        }
    }

    private fun displayNameOf(uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }

    /**
     * Stages the zip under a readable file name and returns a FileProvider uri.
     *
     * The stored copy is named <uuid>.zip, which would show up as the file name
     * in the share sheet and on the recipient's device.
     */
    suspend fun shareUri(app: AppEntity): Uri? = withContext(Dispatchers.IO) {
        val source = File(app.zipFilePath)
        if (!source.isFile) return@withContext null
        try {
            val dir = Storage.shareRoot(context)
            val name = exportFileName(app)
            dir.listFiles()?.forEach { if (it.name != name) it.delete() }
            val staged = File(dir, name)
            source.copyTo(staged, overwrite = true)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun markPinned(app: AppEntity) = withContext(Dispatchers.IO) {
        dao.update(app.copy(shortcutId = ShortcutHelper.shortcutIdFor(app.uuid)))
    }

    /**
     * manifest.json's start_url is relative to the manifest, which lives at the
     * app root. Anything that does not resolve to a real file falls back to
     * index.html, whose presence was already verified at import.
     */
    private fun resolveStartUrl(manifest: WebManifest?, appDir: File): String {
        val raw = manifest?.startUrl?.trim().orEmpty()
        if (raw.isEmpty() || raw == "/" || raw == "./") return "index.html"

        val cleaned = raw.removePrefix("./").removePrefix("/")
        if (Uri.parse(cleaned).scheme != null) return "index.html"

        val path = cleaned.substringBefore('?').substringBefore('#')
        val candidate = File(appDir, path)
        val root = appDir.canonicalPath + File.separator
        val inside = runCatching { candidate.canonicalPath.startsWith(root) }.getOrDefault(false)

        return if (inside && candidate.isFile) cleaned else "index.html"
    }

    /**
     * Looks up the app a manifest identifies.
     *
     * A manifest id is only unique when it is absolute. Generated manifests
     * routinely use a relative one such as "./index.html?v=5", which unrelated
     * apps would share, so those must agree on the name too — merging two
     * different apps would destroy one of them, which is far worse than leaving
     * a duplicate behind.
     */
    private suspend fun matchByManifest(manifestId: String, name: String): AppEntity? =
        if (isRelativeId(manifestId)) {
            dao.findByManifestIdAndName(manifestId, name)
        } else {
            dao.findByManifestId(manifestId)
        }

    private fun isRelativeId(id: String): Boolean {
        if (id.startsWith("./") || id.startsWith("../") || id.startsWith("/")) return true
        // A bare "index.html" is no more distinctive than "./index.html".
        return !id.contains(':') &&
            id.substringAfterLast('.', "").lowercase() in setOf("html", "htm")
    }

    /**
     * Strips the query and fragment. Generated manifests often encode a version
     * there ("./index.html?v=5"), which would make every release look like a
     * different app.
     */
    private fun normalizeManifestId(raw: String): String =
        raw.trim().substringBefore('?').substringBefore('#').ifBlank { raw.trim() }

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        /**
         * Exports usually land on shared storage, so the name has to survive
         * FAT/exFAT rules rather than just Linux ones.
         */
        fun exportFileName(app: AppEntity): String {
            val base = app.name
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trimEnd('.')
                .take(60)
                .ifBlank { "app" }
            return "$base.zip"
        }
    }
}
