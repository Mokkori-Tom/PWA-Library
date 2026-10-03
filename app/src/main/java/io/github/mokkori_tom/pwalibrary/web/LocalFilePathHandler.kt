package io.github.mokkori_tom.pwalibrary.web

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import io.github.mokkori_tom.pwalibrary.files.FileSystemShim
import java.io.File

/**
 * Serves an extracted mini-app straight off internal storage.
 *
 * Written by hand rather than using [WebViewAssetLoader.InternalStoragePathHandler]
 * so the MIME table and the no-cache headers are explicit — a stale cached
 * index.html after an update is exactly the failure this app must not have.
 */
class LocalFilePathHandler(root: File) : WebViewAssetLoader.PathHandler {

    private val canonicalRoot = root.canonicalFile
    private val rootPrefix = canonicalRoot.path + File.separator

    override fun handle(path: String): WebResourceResponse? {
        val decoded = try {
            Uri.decode(path.removePrefix("/"))
        } catch (e: Exception) {
            return notFound()
        }

        var file = File(canonicalRoot, decoded)
        // Same traversal guard as extraction: a request for "../../databases/x"
        // must not escape the app's own directory.
        if (!isInsideRoot(file)) return notFound()

        if (file.isDirectory) file = File(file, "index.html")
        if (!file.isFile) return notFound()
        if (!isInsideRoot(file)) return notFound()

        val mime = mimeTypeOf(file.name)
        return try {
            WebResourceResponse(
                mime,
                "utf-8",
                200,
                "OK",
                noCacheHeaders(),
                if (mime == "text/html") htmlStream(file) else file.inputStream()
            )
        } catch (e: Exception) {
            notFound()
        }
    }

    /**
     * Serves HTML, adding a device-width viewport meta when the page declares
     * none.
     *
     * Done here rather than by injecting script into the loaded page: a viewport
     * meta appended from JavaScript does not change WebView's layout width, so
     * the tag has to be present when the parser first sees it. Without this, a
     * page with no viewport meta is laid out at an assumed 980px.
     */
    private fun htmlStream(file: File): java.io.InputStream {
        if (file.length() > MAX_HTML_REWRITE_BYTES) return file.inputStream()

        val bytes = file.readBytes()
        // ISO-8859-1 maps each byte to one char and back, so the ASCII patterns
        // below can be matched without assuming (or corrupting) the page's real
        // encoding.
        val text = String(bytes, Charsets.ISO_8859_1)

        val injected = StringBuilder()
        if (!VIEWPORT_META.containsMatchIn(text)) injected.append(VIEWPORT_TAG)
        // A parser-blocking script tag, so the shim is installed before any of
        // the page's own scripts run. Kept to a few ASCII bytes: anything longer
        // here would push the page's <meta charset> out of the parser's window.
        injected.append(FileSystemShim.TAG)

        val at = (HEAD_OPEN.find(text) ?: HTML_OPEN.find(text))?.range?.last?.plus(1) ?: 0
        val patched = text.substring(0, at) + injected + text.substring(at)
        return ByteArrayInputStream(patched.toByteArray(Charsets.ISO_8859_1))
    }

    private fun isInsideRoot(file: File): Boolean = try {
        val canonical = file.canonicalPath
        canonical == canonicalRoot.path || canonical.startsWith(rootPrefix)
    } catch (e: Exception) {
        false
    }

    private fun notFound() = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        noCacheHeaders(),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun noCacheHeaders() = mapOf(
        "Cache-Control" to "no-cache, no-store, must-revalidate",
        "Pragma" to "no-cache",
        "Expires" to "0"
    )

    private fun mimeTypeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MIME_TYPES[ext] ?: "application/octet-stream"
    }

    private companion object {
        /** Larger HTML is streamed untouched rather than buffered to rewrite. */
        const val MAX_HTML_REWRITE_BYTES = 4L * 1024 * 1024

        const val VIEWPORT_TAG =
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"

        val VIEWPORT_META = Regex(
            """<meta\s[^>]*name\s*=\s*["']?viewport""",
            RegexOption.IGNORE_CASE
        )
        val HEAD_OPEN = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
        val HTML_OPEN = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)

        /**
         * Deliberately explicit. MimeTypeMap on some devices returns null for
         * .mjs and .webmanifest, and a wrong type on a module script makes the
         * page fail with a bare console error.
         */
        val MIME_TYPES = mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "css" to "text/css",
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "json" to "application/json",
            "webmanifest" to "application/manifest+json",
            "map" to "application/json",
            "wasm" to "application/wasm",
            "txt" to "text/plain",
            "csv" to "text/csv",
            "xml" to "application/xml",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "avif" to "image/avif",
            "bmp" to "image/bmp",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "eot" to "application/vnd.ms-fontobject",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "m4a" to "audio/mp4",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "pdf" to "application/pdf"
        )
    }
}
