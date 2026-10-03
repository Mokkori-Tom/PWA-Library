package io.github.mokkori_tom.pwalibrary.web

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import io.github.mokkori_tom.pwalibrary.files.DownloadShim
import io.github.mokkori_tom.pwalibrary.files.FileSystemShim
import java.io.ByteArrayInputStream

/**
 * Serves the injected shims under a reserved path.
 *
 * Both of them ride in this one file: the page gets a single script tag, and
 * the byte budget for the injection stays where it is.
 *
 * Inlining it into the page instead would push the page's own `<meta charset>`
 * past the first 1024 bytes, where the HTML parser stops looking for it, and
 * would force ~8KB of non-ASCII JavaScript through the byte-preserving
 * ISO-8859-1 splice in [LocalFilePathHandler].
 */
class ShimPathHandler : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        // Returning null lets the loader fall through to the file handler.
        if (path.removePrefix("/") != FileSystemShim.PATH) return null

        return WebResourceResponse(
            "text/javascript",
            "utf-8",
            200,
            "OK",
            mapOf(
                "Cache-Control" to "no-cache, no-store, must-revalidate",
                "Pragma" to "no-cache",
                "Expires" to "0"
            ),
            ByteArrayInputStream((FileSystemShim.JS + DownloadShim.JS).toByteArray(Charsets.UTF_8))
        )
    }

    companion object {
        const val PREFIX = "/__pwalib__/"
    }
}
