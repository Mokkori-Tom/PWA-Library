package io.github.mokkori_tom.pwalibrary.web

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps each mini-app to its own https origin.
 *
 * Two things depend on this:
 *  - *.androidplatform.net is treated as https, so mini-apps get a secure
 *    context and Service Workers actually register.
 *  - one subdomain per app means localStorage / IndexedDB are isolated per app
 *    by the browser's own origin rules, with no work on our side.
 *
 * The domain is derived from the app's persistent uuid, never from its rowid,
 * so an update keeps the origin and therefore keeps the app's stored data.
 */
object AppAssetRegistry {

    private val loaders = ConcurrentHashMap<String, WebViewAssetLoader>()

    fun domainFor(uuid: String): String = "app-${uuid.replace("-", "").lowercase()}.androidplatform.net"

    fun baseUrl(uuid: String): String = "https://${domainFor(uuid)}/"

    /**
     * Builds the URL a mini-app is launched at.
     *
     * A trailing `index.html` is dropped so the app opens at its directory URL.
     * The path handler serves the same file either way, but an SPA router reads
     * `location.pathname`: launched at `/index.html` a Vite/Next build matches no
     * route and renders its 404, which is not how the same build behaves when a
     * real static host serves it at `/`. Relative links resolve identically.
     */
    fun urlFor(uuid: String, startUrl: String): String {
        val cleaned = startUrl.removePrefix("./").removePrefix("/")
        val cut = cleaned.indexOfFirst { it == '?' || it == '#' }
        val path = if (cut < 0) cleaned else cleaned.substring(0, cut)
        val suffix = if (cut < 0) "" else cleaned.substring(cut)
        val directory = if (path == "index.html") ""
        else path.removeSuffix("/index.html").let { if (it == path) path else "$it/" }
        return baseUrl(uuid) + directory + suffix
    }

    fun loaderFor(uuid: String, appDir: File): WebViewAssetLoader {
        val domain = domainFor(uuid)
        return loaders.getOrPut(domain) {
            WebViewAssetLoader.Builder()
                .setDomain(domain)
                .setHttpAllowed(false)
                // Registered before "/" so the reserved path wins; the shim
                // handler returns null for anything else and falls through.
                .addPathHandler(ShimPathHandler.PREFIX, ShimPathHandler())
                .addPathHandler("/", LocalFilePathHandler(appDir))
                .build()
        }
    }

    fun isAppHost(host: String?): Boolean = host != null && loaders.containsKey(host)

    /**
     * Entry point for the process-global Service Worker client, which cannot
     * know which app a request belongs to. Resolving by host keeps two mini-apps
     * open at once from serving each other's files.
     */
    fun intercept(url: Uri): WebResourceResponse? {
        val loader = loaders[url.host] ?: return null
        return loader.shouldInterceptRequest(url)
    }

    fun forget(uuid: String) {
        loaders.remove(domainFor(uuid))
    }
}
