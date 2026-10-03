package io.github.mokkori_tom.pwalibrary.files

import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Native half of download support: collects a file's bytes from the page.
 *
 * The page sends base64 slices rather than one string, so an export is written
 * straight to a staging file instead of being held in memory twice over.
 * Nothing is shown to the user until the last slice arrives; only then is
 * there a complete file worth asking where to put.
 *
 * Methods run on WebView's JavaScript bridge thread, never the main thread, so
 * the file IO here is fine. [onReady] and [onFail] are posted back by the
 * activity.
 */
class DownloadBridge(
    private val stagingDir: File,
    private val onReady: (id: String, file: File, fileName: String, mime: String) -> Unit,
    private val onFail: (id: String, message: String) -> Unit
) {

    private class Sink(
        val file: File,
        val out: OutputStream,
        val fileName: String,
        val mime: String,
        var written: Long
    )

    private val sinks = ConcurrentHashMap<String, Sink>()

    @JavascriptInterface
    fun open(id: String, fileName: String, mime: String, size: String) {
        discard(id)
        if ((size.toLongOrNull() ?: 0L) > MAX_BYTES) {
            onFail(id, "ファイルが大きすぎます")
            return
        }
        stagingDir.mkdirs()
        val file = File(stagingDir, "dl-$id")
        runCatching { Sink(file, FileOutputStream(file), fileName, mime, 0L) }
            .onSuccess { sinks[id] = it }
            .onFailure { onFail(id, "保存の準備に失敗しました") }
    }

    @JavascriptInterface
    fun chunk(id: String, base64: String) {
        val sink = sinks[id] ?: return
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            ?: return failAndDiscard(id, "データを復号できませんでした")

        sink.written += bytes.size
        // A page could keep sending past what it declared, so the cap is
        // enforced on what actually arrives too.
        if (sink.written > MAX_BYTES) return failAndDiscard(id, "ファイルが大きすぎます")
        runCatching { sink.out.write(bytes) }
            .onFailure { failAndDiscard(id, "書き込めませんでした") }
    }

    @JavascriptInterface
    fun done(id: String) {
        val sink = sinks.remove(id) ?: return
        runCatching { sink.out.close() }
        onReady(id, sink.file, sink.fileName, sink.mime)
    }

    @JavascriptInterface
    fun fail(id: String, message: String) = failAndDiscard(id, message)

    private fun failAndDiscard(id: String, message: String) {
        discard(id)
        onFail(id, message)
    }

    private fun discard(id: String) {
        sinks.remove(id)?.let {
            runCatching { it.out.close() }
            it.file.delete()
        }
    }

    companion object {
        /** Refused rather than risking the staging area on a runaway page. */
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
