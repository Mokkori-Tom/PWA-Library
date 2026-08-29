package com.example.pwalibrary.files

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.pwalibrary.data.FolderGrant
import java.io.ByteArrayOutputStream

/**
 * Reads and writes inside a folder the user granted through SAF.
 *
 * Every path is relative to the granted tree and is resolved one segment at a
 * time, so a mini-app can never address anything outside the folder its user
 * chose.
 */
class FolderAccess(private val context: Context) {

    companion object {
        /** Everything crosses the JS bridge base64-encoded, so keep it bounded. */
        const val MAX_FILE_BYTES = 32L * 1024 * 1024
    }

    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val type: String
    )

    class AccessException(message: String) : Exception(message)

    private fun root(grant: FolderGrant): DocumentFile =
        DocumentFile.fromTreeUri(context, Uri.parse(grant.treeUri))
            ?: throw AccessException("フォルダにアクセスできません")

    /**
     * Splits a relative path and rejects anything that could escape the tree.
     * An empty path resolves to the tree root.
     */
    private fun segments(path: String): List<String> {
        val parts = path.trim().trim('/').split('/').filter { it.isNotEmpty() }
        parts.forEach { part ->
            if (part == "." || part == "..") throw AccessException("不正なパス: " + path)
        }
        return parts
    }

    /** Walks to [path], optionally creating what is missing along the way. */
    private fun resolve(
        grant: FolderGrant,
        path: String,
        createDirs: Boolean,
        createFile: Boolean
    ): DocumentFile? {
        val parts = segments(path)
        var current = root(grant)
        if (parts.isEmpty()) return current

        for (i in 0 until parts.size - 1) {
            val name = parts[i]
            val next = current.findFile(name)
            current = when {
                next != null && next.isDirectory -> next
                next != null -> throw AccessException(name + " はフォルダではありません")
                createDirs -> current.createDirectory(name)
                    ?: throw AccessException("フォルダを作成できません: " + name)
                else -> return null
            }
        }

        val last = parts.last()
        val found = current.findFile(last)
        if (found != null) return found
        if (createFile) {
            return current.createFile(mimeOf(last), last)
                ?: throw AccessException("ファイルを作成できません: " + last)
        }
        if (createDirs) {
            return current.createDirectory(last)
                ?: throw AccessException("フォルダを作成できません: " + last)
        }
        return null
    }

    fun exists(grant: FolderGrant, path: String): Boolean =
        resolve(grant, path, createDirs = false, createFile = false) != null

    fun info(grant: FolderGrant, path: String): Entry? =
        resolve(grant, path, createDirs = false, createFile = false)?.toEntry()

    fun list(grant: FolderGrant, path: String): List<Entry> {
        val dir = resolve(grant, path, createDirs = false, createFile = false)
            ?: throw AccessException("フォルダが見つかりません: " + path)
        if (!dir.isDirectory) throw AccessException("フォルダではありません: " + path)
        return dir.listFiles().map { it.toEntry() }
    }

    fun createDirectory(grant: FolderGrant, path: String) {
        val existing = resolve(grant, path, createDirs = false, createFile = false)
        if (existing != null) {
            if (!existing.isDirectory) throw AccessException("同名のファイルがあります: " + path)
            return
        }
        resolve(grant, path, createDirs = true, createFile = false)
            ?: throw AccessException("フォルダを作成できません: " + path)
    }

    fun read(grant: FolderGrant, path: String): ByteArray {
        val file = resolve(grant, path, createDirs = false, createFile = false)
            ?: throw AccessException("ファイルが見つかりません: " + path)
        if (file.isDirectory) throw AccessException("フォルダは読み込めません: " + path)
        if (file.length() > MAX_FILE_BYTES) throw AccessException("ファイルが大きすぎます")

        val out = ByteArrayOutputStream()
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw AccessException("ファイルを開けません: " + path)
        input.use { stream ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FILE_BYTES) throw AccessException("ファイルが大きすぎます")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    fun write(grant: FolderGrant, path: String, bytes: ByteArray, append: Boolean) {
        if (bytes.size > MAX_FILE_BYTES) throw AccessException("書き込むデータが大きすぎます")
        val file = resolve(grant, path, createDirs = true, createFile = true)
            ?: throw AccessException("ファイルを作成できません: " + path)
        if (file.isDirectory) throw AccessException("フォルダには書き込めません: " + path)

        // "wt" rather than plain "w": several providers leave the tail of a
        // longer previous version in place otherwise.
        val mode = if (append) "wa" else "wt"
        val out = context.contentResolver.openOutputStream(file.uri, mode)
            ?: throw AccessException("ファイルを開けません: " + path)
        out.use { it.write(bytes) }
    }

    fun remove(grant: FolderGrant, path: String, recursive: Boolean) {
        if (segments(path).isEmpty()) throw AccessException("フォルダ自体は削除できません")
        val target = resolve(grant, path, createDirs = false, createFile = false)
            ?: throw AccessException("見つかりません: " + path)
        if (target.isDirectory && !recursive && target.listFiles().isNotEmpty()) {
            throw AccessException("フォルダが空ではありません: " + path)
        }
        if (!target.delete()) throw AccessException("削除できません: " + path)
    }

    private fun DocumentFile.toEntry() = Entry(
        name = name ?: "",
        isDirectory = isDirectory,
        size = if (isDirectory) 0 else length(),
        lastModified = lastModified(),
        type = type ?: ""
    )

    /**
     * SAF derives the stored file name partly from the mime type, so a type that
     * disagrees with the extension gets a second extension appended.
     */
    private fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
