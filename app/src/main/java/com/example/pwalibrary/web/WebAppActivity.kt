package com.example.pwalibrary.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.example.pwalibrary.data.AppDatabase
import com.example.pwalibrary.data.AppEntity
import com.example.pwalibrary.data.FolderGrant
import com.example.pwalibrary.files.FileBridge
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Hosts one mini-app. Plain views rather than Compose: WebView is the entire
 * screen here, and AndroidView interop would only add a layer to debug through.
 */
class WebAppActivity : ComponentActivity() {

    companion object {
        const val EXTRA_UUID = "app_uuid"
        private const val STATE_DIRECTORY_REQUEST = "pending_directory_request"

        private var serviceWorkerConfigured = false

        /**
         * Drops the app's Service Worker registrations and Cache Storage, then
         * reloads so the freshly extracted files are served.
         *
         * Run through evaluateJavascript on whatever page rendered, rather than
         * by navigating to a reset page of our own: a worker that answers every
         * navigation from its cache would simply serve the stale shell instead,
         * which is exactly what happened with the earlier approach.
         */
        private const val RESET_SCRIPT = """
            (function () {
              var jobs = [], changed = false;
              if (navigator.serviceWorker) {
                jobs.push(navigator.serviceWorker.getRegistrations().then(function (rs) {
                  if (rs.length) changed = true;
                  return Promise.all(rs.map(function (r) { return r.unregister(); }));
                }));
              }
              if (window.caches) {
                jobs.push(caches.keys().then(function (ks) {
                  if (ks.length) changed = true;
                  return Promise.all(ks.map(function (k) { return caches.delete(k); }));
                }));
              }
              var done = function () { if (changed) location.reload(); };
              Promise.all(jobs).then(done, done);
            })();
        """

        fun intentFor(context: Context, uuid: String): Intent =
            Intent(context, WebAppActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                // documentLaunchMode="intoExisting" matches an existing task by
                // component *and* data URI. Without a distinct URI per app every
                // mini-app would reuse the same task and the same recents entry.
                data = Uri.parse("pwalib://app/$uuid")
                putExtra(EXTRA_UUID, uuid)
            }

        /**
         * The Service Worker client is process-global and, unlike WebViewClient,
         * is NOT consulted through shouldInterceptRequest on the WebView. Without
         * this, a mini-app registers its worker successfully and then serves
         * nothing — the classic "works once, blank on reload" symptom.
         */
        private fun configureServiceWorkers() {
            if (serviceWorkerConfigured) return
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
            serviceWorkerConfigured = true

            val controller = ServiceWorkerControllerCompat.getInstance()
            controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    AppAssetRegistry.intercept(request.url)
            })

            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_FILE_ACCESS)) {
                controller.serviceWorkerWebSettings.allowFileAccess = false
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CONTENT_ACCESS)) {
                controller.serviceWorkerWebSettings.allowContentAccess = false
            }
        }
    }

    private lateinit var container: FrameLayout
    private var webView: WebView? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooser: ActivityResultLauncher<Array<String>>
    private lateinit var folderPicker: ActivityResultLauncher<Uri?>

    /** Set while the page holds the screen via requestFullscreen() or <video>. */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** Whether the app itself asked for fullscreen, so custom views restore correctly. */
    private var fullscreenByManifest = false

    /** True for the first page load after an update, to clear stale SW caches. */
    private var pendingStorageReset = false

    private var currentApp: AppEntity? = null
    /** Known from the intent, unlike [currentApp] which waits on a database read. */
    private var appUuid: String? = null
    /** Set while a mini-app's showDirectoryPicker() call is waiting on SAF. */
    private var pendingDirectoryRequest: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)
        setContentView(container)

        folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            onFolderPicked(uri)
        }

        fileChooser = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            // Must always answer, even on cancel, or the file input stays stuck.
            filePathCallback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
            filePathCallback = null
        }

        pendingDirectoryRequest = savedInstanceState?.getString(STATE_DIRECTORY_REQUEST)

        val uuid = intent.getStringExtra(EXTRA_UUID) ?: intent.data?.lastPathSegment
        appUuid = uuid
        if (uuid.isNullOrBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val app = AppDatabase.get(this@WebAppActivity).appDao().findByUuid(uuid)
            val appDir = app?.let { File(it.appDirPath) }
            if (app == null || appDir == null || !appDir.isDirectory) {
                Toast.makeText(this@WebAppActivity, "アプリが見つかりません", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            start(app, appDir)
        }
    }

    private fun start(app: AppEntity, appDir: File) {
        setTitle(app.name)
        applyChrome(app)
        configureServiceWorkers()

        currentApp = app
        val loader = AppAssetRegistry.loaderFor(app.uuid, appDir)
        val view = createWebView(app.uuid, loader)
        webView = view

        // Scoped to this one app: FileBridge filters every lookup by uuid, so a
        // folder granted to one zip is unreachable from another.
        view.addJavascriptInterface(
            FileBridge(applicationContext, app.uuid) { requestId ->
                runOnUiThread { startFolderPick(requestId) }
            },
            "__pwalibFs"
        )
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitCustomView()) return
                val wv = webView
                if (wv != null && wv.canGoBack()) wv.goBack() else finish()
            }
        })

        if (app.needsStorageReset) {
            pendingStorageReset = true
            // The HTTP cache is no-store anyway, but this removes one more thing
            // that could hand back the previous bundle.
            view.clearCache(true)
            // Cleared up front: a flag that survived a failed reset would send
            // every later launch through it as well.
            lifecycleScope.launch {
                AppDatabase.get(this@WebAppActivity).appDao().clearStorageResetFlag(app.uuid)
            }
        }
        view.loadUrl(AppAssetRegistry.urlFor(app.uuid, app.startUrl))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(uuid: String, loader: androidx.webkit.WebViewAssetLoader): WebView {
        val view = WebView(this)

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Everything is served locally, so there is nothing worth caching in
            // the HTTP cache and a stale entry after an update would be a bug.
            cacheMode = WebSettings.LOAD_NO_CACHE
            // The asset loader is the only file source; file:// and content://
            // access would just widen what a hostile zip can reach.
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
        }

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val response = loader.shouldInterceptRequest(request.url)
                // History fallback, the same deal a static host gives an SPA:
                // /about is a client-side route, not a file, so a real reload
                // there would otherwise return an empty 404. Restricted to
                // main-frame navigation, so a missing script or image still
                // fails as a missing script or image.
                if (response != null && response.statusCode == 404 && request.isForMainFrame) {
                    val root = Uri.parse(AppAssetRegistry.baseUrl(uuid))
                    loader.shouldInterceptRequest(root)?.let { if (it.statusCode == 200) return it }
                }
                return response
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                // Navigation inside the mini-app's own origin stays in the WebView.
                if (AppAssetRegistry.isAppHost(url.host)) return false
                openExternally(url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!pendingStorageReset) return
                pendingStorageReset = false
                // Visible confirmation that the native half of the update path
                // ran: without it, "stale page" and "never re-imported" look
                // identical from the app itself.
                Toast.makeText(
                    this@WebAppActivity,
                    "更新を適用しています…",
                    Toast.LENGTH_SHORT
                ).show()
                view.evaluateJavascript(RESET_SCRIPT, null)
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // Without handling this, a mini-app crashing takes the whole
                // library app down with it.
                container.removeView(view)
                view.destroy()
                if (webView === view) webView = null
                Toast.makeText(
                    this@WebAppActivity,
                    "アプリが停止しました",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return true
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val types = params.acceptTypes
                    .filter { it.isNotBlank() }
                    .toTypedArray()
                    .ifEmpty { arrayOf("*/*") }
                return try {
                    fileChooser.launch(types)
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }

            // requestFullscreen() and <video> fullscreen both go through here.
            // Without it the promise simply rejects and nothing happens.
            override fun onShowCustomView(
                view: View,
                callback: WebChromeClient.CustomViewCallback
            ) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webView?.visibility = View.GONE
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                setSystemBarsHidden(true)
            }

            override fun onHideCustomView() {
                exitCustomView()
            }

            // No INTERNET, no runtime permissions declared: camera, microphone and
            // geolocation requests are denied rather than silently hanging.
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                request.deny()
            }
        }

        view.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "このアプリではダウンロードできません", Toast.LENGTH_SHORT).show()
        }

        return view
    }

    private fun openExternally(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "リンクを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------- folder access

    /**
     * Opens the SAF picker.
     *
     * No confirmation of our own: SAF asks for real once a folder is picked
     * ("フォルダへのアクセスを PWA Library に許可しますか?"), and a third dialog
     * on top of that just trains people to tap through. The trade-off is that
     * the system prompt names this app rather than the mini-app that asked, so
     * the per-app grant list in the detail screen is where that can be audited.
     */
    private fun startFolderPick(requestId: String) {
        if (currentApp == null) {
            answerDirectoryRequest(requestId, ok = false, name = "InvalidStateError", error = "アプリが不明です")
            return
        }
        if (pendingDirectoryRequest != null) {
            answerDirectoryRequest(requestId, ok = false, name = "AbortError", error = "処理中です")
            return
        }

        pendingDirectoryRequest = requestId
        runCatching { folderPicker.launch(null) }.onFailure {
            pendingDirectoryRequest = null
            answerDirectoryRequest(requestId, ok = false, name = "NotAllowedError", error = "フォルダを選べません")
        }
    }

    private fun onFolderPicked(uri: Uri?) {
        val requestId = pendingDirectoryRequest
        pendingDirectoryRequest = null
        val uuid = currentApp?.uuid ?: appUuid

        if (uri == null) {
            if (requestId != null) {
                answerDirectoryRequest(requestId, ok = false, name = "AbortError", error = "選択されませんでした")
            }
            return
        }
        if (uuid == null) return

        // Without this the grant dies with the activity, and "keep writing to
        // the same folder" would mean re-picking on every launch.
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.isSuccess
        if (!persisted) {
            if (requestId != null) {
                answerDirectoryRequest(requestId, ok = false, name = "NotAllowedError", error = "フォルダの権限を保持できません")
            }
            return
        }

        val label = DocumentFile.fromTreeUri(this, uri)?.name
            ?: uri.lastPathSegment?.substringAfterLast(':')
            ?: "フォルダ"

        lifecycleScope.launch {
            val dao = AppDatabase.get(this@WebAppActivity).folderGrantDao()
            val grant = dao.findByTree(uuid, uri.toString())
                ?.also { dao.touch(it.id, System.currentTimeMillis()) }
                ?: FolderGrant(
                    id = UUID.randomUUID().toString(),
                    appUuid = uuid,
                    treeUri = uri.toString(),
                    displayName = label,
                    grantedAt = System.currentTimeMillis()
                ).also { dao.insert(it) }

            // A lost request id only costs the answer, not the grant: the page's
            // next reconnect picks the folder up anyway.
            if (requestId != null) {
                answerDirectoryRequest(requestId, ok = true, grantId = grant.id, folderName = grant.displayName)
            }
        }
    }

    private fun answerDirectoryRequest(
        requestId: String,
        ok: Boolean,
        grantId: String? = null,
        folderName: String? = null,
        name: String? = null,
        error: String? = null
    ) {
        val payload = JSONObject().put("ok", ok)
        if (ok) {
            payload.put("grant", grantId).put("name", folderName)
        } else {
            payload.put("name", name ?: "NotAllowedError").put("error", error ?: "失敗しました")
        }
        val js = "window.__pwalibResolve(" +
            JSONObject.quote(requestId) + "," + JSONObject.quote(payload.toString()) + ")"
        webView?.evaluateJavascript(js, null)
    }

    /** Returns true when a fullscreen custom view was actually dismissed. */
    private fun exitCustomView(): Boolean {
        val view = customView ?: return false
        container.removeView(view)
        customView = null
        webView?.visibility = View.VISIBLE
        setSystemBarsHidden(fullscreenByManifest)
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        return true
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !hidden)
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Applies manifest.json `display` and `theme_color` to the system UI. */
    private fun applyChrome(app: AppEntity) {
        fullscreenByManifest = app.displayMode == "fullscreen"
        if (fullscreenByManifest) {
            setSystemBarsHidden(true)
            return
        }

        val themeColor = app.themeColor?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        if (themeColor != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            @Suppress("DEPRECATION")
            window.statusBarColor = themeColor
            controller.isAppearanceLightStatusBars = isLight(themeColor)
        }
    }

    private fun isLight(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return luminance > 0.6
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The picker is another app's activity; this one can be recycled while
        // it is on screen.
        outState.putString(STATE_DIRECTORY_REQUEST, pendingDirectoryRequest)
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        exitCustomView()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView?.let { view ->
            container.removeView(view)
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
