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
import java.util.concurrent.ConcurrentHashMap

sealed interface InstallOutcome {
    data class Installed(val app: AppEntity) : InstallOutcome
    data class Updated(val app: AppEntity, val previousVersion: String) : InstallOutcome
    data class Failed(val message: String) : InstallOutcome

    /**
     * The zip resembles an existing app by name and by nothing else, which is
     * not enough to overwrite it and too much to ignore. The staged zip is held
     * under [token] until the caller answers.
     */
    data class NeedsChoice(
        val token: String,
        val candidate: AppEntity,
        val incomingName: String
    ) : InstallOutcome
}

class AppRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).appDao()
    private val grantDao = AppDatabase.get(context).folderGrantDao()
    private val installer = ZipInstaller(context)

    /**
     * Zips staged but not yet committed, waiting on a "update or add?" answer.
     * Emptied by answering or cancelling; anything stranded by a killed process
     * is swept on next launch by [ZipInstaller.sweepStagedZips].
     */
    private val awaitingChoice = ConcurrentHashMap<String, StagedZip>()

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
        complete(staged, forceUuid, mayAsk = forceUuid == null)
    }

    /**
     * Finishes an import the user was asked about. [targetUuid] is the app to
     * overwrite, or null to add the zip as a new app despite the resemblance.
     */
    suspend fun resolveChoice(token: String, targetUuid: String?): InstallOutcome =
        withContext(Dispatchers.IO) {
            val staged = awaitingChoice.remove(token)
                ?: return@withContext InstallOutcome.Failed("取り込みをやり直してください。")
            complete(staged, targetUuid, mayAsk = false)
        }

    fun cancelChoice(token: String) {
        awaitingChoice.remove(token)?.let { installer.discard(it) }
    }

    /**
     * Imports a staged zip, replacing an existing app when something identifies
     * one.
     *
     * [forceUuid] pins the target when the user explicitly picked an app;
     * otherwise identity comes from the manifest, then from the zip's own
     * bytes. When neither answers and [mayAsk] is set, a name collision is
     * referred back to the user rather than guessed at.
     */
    private suspend fun complete(
        staged: StagedZip,
        forceUuid: String?,
        mayAsk: Boolean
    ): InstallOutcome {
        var parked = false
        try {
            val manifest = staged.manifest
            val manifestId = (manifest?.id ?: manifest?.name)?.let { normalizeManifestId(it) }

            // Needed before the lookup, because a relative id has to be paired
            // with a name to be safe to match on. This is the name the zip
            // declares, never the one the user may have chosen since.
            val importName = contentName(staged) ?: staged.fallbackName

            // Falling back to the content hash means re-importing a zip this
            // app exported updates the entry it came from instead of adding a
            // duplicate, even when the zip has no manifest to identify it.
            val existing = when {
                forceUuid != null -> dao.findByUuid(forceUuid)
                else -> manifestId?.let { matchByManifest(it, importName) }
                    ?: dao.findByZipHash(staged.sha256)
            }

            // Sharing a name is a hint, not an identity: two unrelated apps can
            // pick the same one, and merging them would destroy one. It is also
            // the only clue a manifest-less rebuild leaves, so it is worth a
            // question even though it is not worth acting on.
            if (existing == null && mayAsk) {
                val sameName = dao.findByAnyName(importName)
                if (sameName != null) {
                    val token = UUID.randomUUID().toString()
                    awaitingChoice[token] = staged
                    parked = true
                    return InstallOutcome.NeedsChoice(token, sameName, importName)
                }
            }

            // Reusing the uuid keeps the https origin stable, which is what keeps
            // the mini-app's localStorage and IndexedDB alive across an update.
            val uuid = existing?.uuid ?: UUID.randomUUID().toString()
            val appDir = installer.commit(staged, uuid)

            // A name the user chose outranks everything: renaming is a decision
            // about this app, and an update is still the same app. Failing that,
            // what the zip says about itself outranks the stored name, so an
            // update can still rename. The name of the file it arrived in never
            // does — renaming the file must not rename the app.
            val name = if (existing?.nameIsCustom == true) {
                existing.name
            } else {
                contentName(staged) ?: existing?.name ?: staged.fallbackName
            }

            // The derived icon is refreshed even when a custom one is in effect,
            // so that clearing the choice later lands on this zip's icon rather
            // than on whatever shipped when the app was first imported.
            val derived = IconStore.extract(context, uuid, appDir, manifest, staged.html.iconHrefs)
            if (derived == null) Storage.iconFile(context, uuid).delete()
            val custom = Storage.customIconFile(context, uuid)
                .takeIf { existing?.iconIsCustom == true && it.isFile }
            val now = System.currentTimeMillis()

            val entity = AppEntity(
                id = existing?.id ?: 0,
                uuid = uuid,
                manifestId = manifestId ?: existing?.manifestId,
                name = name,
                importName = importName,
                nameIsCustom = existing?.nameIsCustom ?: false,
                shortName = manifest?.shortName ?: existing?.shortName ?: "",
                description = manifest?.description ?: existing?.description ?: "",
                iconPath = (custom ?: derived)?.absolutePath,
                // Self-healing: a flag left set by a file that has since gone
                // missing would otherwise pin the app to a null icon forever.
                iconIsCustom = custom != null,
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

            return if (existing == null) {
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
            return InstallOutcome.Failed(e.message ?: "展開に失敗しました。")
        } catch (e: Exception) {
            return InstallOutcome.Failed("展開に失敗しました。")
        } finally {
            // Not while a dialog still needs it.
            if (!parked) installer.discard(staged)
        }
    }

    /**
     * The name the zip claims for itself, ignoring the file it was delivered in.
     *
     * The page's own title is the last of these because it describes a document
     * rather than an app: a manifest that bothers to name itself is making a
     * deliberate statement, while a title is often left at whatever the first
     * page happened to be called.
     */
    private fun contentName(staged: StagedZip): String? =
        staged.manifest?.name?.takeIf { it.isNotBlank() }
            ?: staged.manifest?.shortName?.takeIf { it.isNotBlank() }
            ?: staged.html.title

    // ------------------------------------------------------------------ edits

    /**
     * Renames an app and records that the name is the user's from now on, so
     * later imports stop overwriting it.
     *
     * [updatedAt] is deliberately left alone: it is shown as the date the app's
     * files last changed, and renaming changes no files.
     */
    suspend fun rename(app: AppEntity, rawName: String): AppEntity? = withContext(Dispatchers.IO) {
        val name = rawName.trim().take(MAX_NAME_CHARS)
        if (name.isEmpty() || name == app.name) return@withContext null
        val updated = app.copy(name = name, nameIsCustom = true)
        dao.update(updated)
        ShortcutHelper.refresh(context, updated)
        updated
    }

    /** Puts back the name the zip declared, and lets imports drive it again. */
    suspend fun restoreName(app: AppEntity): AppEntity = withContext(Dispatchers.IO) {
        val updated = app.copy(
            name = app.importName.ifBlank { app.name },
            nameIsCustom = false
        )
        dao.update(updated)
        ShortcutHelper.refresh(context, updated)
        updated
    }

    suspend fun setCustomIcon(app: AppEntity, source: Uri): AppEntity? = withContext(Dispatchers.IO) {
        val icon = IconStore.importCustom(context, app.uuid, source) ?: return@withContext null
        val updated = app.copy(iconPath = icon.absolutePath, iconIsCustom = true)
        dao.update(updated)
        ShortcutHelper.refresh(context, updated)
        updated
    }

    /** Drops the chosen icon and falls back to the one derived from the zip. */
    suspend fun clearCustomIcon(app: AppEntity): AppEntity = withContext(Dispatchers.IO) {
        Storage.customIconFile(context, app.uuid).delete()
        val derived = Storage.iconFile(context, app.uuid).takeIf { it.isFile }
        val updated = app.copy(iconPath = derived?.absolutePath, iconIsCustom = false)
        dao.update(updated)
        ShortcutHelper.refresh(context, updated)
        updated
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
    private suspend fun matchByManifest(manifestId: String, importName: String): AppEntity? =
        if (isRelativeId(manifestId)) {
            dao.findByManifestIdAndImportName(manifestId, importName)
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

        /** Matches the cap [exportFileName] applies, so a name always survives export. */
        const val MAX_NAME_CHARS = 60

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
