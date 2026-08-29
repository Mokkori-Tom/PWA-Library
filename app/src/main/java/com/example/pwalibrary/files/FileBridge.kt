package com.example.pwalibrary.files

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import com.example.pwalibrary.data.AppDatabase
import com.example.pwalibrary.data.FolderGrant
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native half of the File System Access shim.
 *
 * Bound to exactly one mini-app: every lookup is filtered by [appUuid], so one
 * zip cannot reach a folder the user granted to another. The tree uri itself is
 * never exposed to JavaScript, only an opaque grant id.
 *
 * Methods run on WebView's JavaScript bridge thread, never the main thread, so
 * blocking file IO here is fine.
 */
class FileBridge(
    private val context: Context,
    private val appUuid: String,
    /** Invoked to start the SAF picker; the activity answers asynchronously. */
    private val onRequestDirectory: (requestId: String) -> Unit
) {

    private val access = FolderAccess(context)
    private val grantDao = AppDatabase.get(context).folderGrantDao()

    // ------------------------------------------------------------ directories

    @JavascriptInterface
    fun requestDirectory(requestId: String) {
        onRequestDirectory(requestId)
    }

    @JavascriptInterface
    fun listGrants(): String = respond {
        val array = JSONArray()
        runBlocking { grantDao.forApp(appUuid) }.forEach { grant ->
            array.put(
                JSONObject()
                    .put("grant", grant.id)
                    .put("name", grant.displayName)
            )
        }
        JSONObject().put("grants", array)
    }

    // ------------------------------------------------------------------ files

    @JavascriptInterface
    fun list(grantId: String, path: String): String = respond {
        val entries = JSONArray()
        access.list(grant(grantId), path).forEach { entries.put(it.toJson()) }
        JSONObject().put("entries", entries)
    }

    @JavascriptInterface
    fun stat(grantId: String, path: String): String = respond {
        val entry = access.info(grant(grantId), path)
        JSONObject().put("entry", entry?.toJson() ?: JSONObject.NULL)
    }

    @JavascriptInterface
    fun read(grantId: String, path: String): String = respond {
        val bytes = access.read(grant(grantId), path)
        val info = access.info(grant(grantId), path)
        JSONObject()
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("type", info?.type ?: "")
            .put("lastModified", info?.lastModified ?: 0L)
    }

    @JavascriptInterface
    fun write(grantId: String, path: String, base64: String, append: Boolean): String = respond {
        val bytes = if (base64.isEmpty()) ByteArray(0) else Base64.decode(base64, Base64.NO_WRAP)
        access.write(grant(grantId), path, bytes, append)
        JSONObject()
    }

    @JavascriptInterface
    fun mkdir(grantId: String, path: String): String = respond {
        access.createDirectory(grant(grantId), path)
        JSONObject()
    }

    @JavascriptInterface
    fun remove(grantId: String, path: String, recursive: Boolean): String = respond {
        access.remove(grant(grantId), path, recursive)
        JSONObject()
    }

    @JavascriptInterface
    fun exists(grantId: String, path: String): String = respond {
        JSONObject().put("exists", access.exists(grant(grantId), path))
    }

    // ----------------------------------------------------------------- shared

    private fun grant(grantId: String): FolderGrant =
        runBlocking { grantDao.find(grantId, appUuid) }
            ?: throw FolderAccess.AccessException("このフォルダへの許可がありません")

    /**
     * Every call answers with JSON rather than throwing: an exception crossing
     * the bridge surfaces in JavaScript as a bare "Java exception was raised",
     * which tells the mini-app nothing.
     */
    private inline fun respond(body: () -> JSONObject): String = try {
        body().put("ok", true).toString()
    } catch (e: FolderAccess.AccessException) {
        JSONObject()
            .put("ok", false)
            .put("name", "NotAllowedError")
            .put("error", e.message ?: "アクセスできません")
            .toString()
    } catch (e: Exception) {
        JSONObject()
            .put("ok", false)
            .put("name", "InvalidStateError")
            .put("error", e.message ?: "エラーが発生しました")
            .toString()
    }

    private fun FolderAccess.Entry.toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("kind", if (isDirectory) "directory" else "file")
        .put("size", size)
        .put("lastModified", lastModified)
        .put("type", type)
}
