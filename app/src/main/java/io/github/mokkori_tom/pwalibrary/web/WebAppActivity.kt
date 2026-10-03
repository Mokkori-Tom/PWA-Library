package io.github.mokkori_tom.pwalibrary.web

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.util.Base64
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
import android.webkit.URLUtil
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import io.github.mokkori_tom.pwalibrary.R
import io.github.mokkori_tom.pwalibrary.data.AppDatabase
import io.github.mokkori_tom.pwalibrary.data.AppEntity
import io.github.mokkori_tom.pwalibrary.data.FolderGrant
import io.github.mokkori_tom.pwalibrary.files.DownloadBridge
import io.github.mokkori_tom.pwalibrary.files.FileBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
        private const val STATE_SAVE_PATH = "pending_save_path"
        private const val STATE_SAVE_NAME = "pending_save_name"
        private const val STATE_SAVE_MIME = "pending_save_mime"
        private const val STATE_SAVE_TEMP = "pending_save_temp"
        private const val STATE_CAPTURE_PATH = "pending_capture_path"

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
    private lateinit var camera: ActivityResultLauncher<Uri>
    /** The file the camera app was asked to write, until the page is handed it. */
    private var pendingCapture: File? = null
    private var chooserDialog: Dialog? = null
    /** False on a fresh launch, true when rebuilt after the system recycled us. */
    private var restored = false
    private lateinit var folderPicker: ActivityResultLauncher<Uri?>

    /** Set while the page holds the screen via requestFullscreen() or <video>. */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** Whether the app itself asked for fullscreen, so custom views restore correctly. */
    private var fullscreenByManifest = false

    /** True for the first page load after an update, to clear stale SW caches. */
    private var pendingStorageReset = false

    private var currentApp: AppEntity? = null

    /** "/" normally, "/app/" for a build extracted under a declared subpath. */
    private var appRootPath: String = "/"

    /** Known from the intent, unlike [currentApp] which waits on a database read. */
    private var appUuid: String? = null
    /** Set while a mini-app's showDirectoryPicker() call is waiting on SAF. */
    private var pendingDirectoryRequest: String? = null

    private lateinit var documentCreator: ActivityResultLauncher<String>
    /** A finished download waiting for the user to say where it goes. */
    private var pendingSave: PendingSave? = null
    private var downloadSeq = 0

    private data class PendingSave(
        val file: File,
        val fileName: String,
        val mime: String,
        /** False when the bytes are the mini-app's own file rather than a copy. */
        val deleteAfter: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)
        setContentView(container)

        folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            onFolderPicked(uri)
        }

        documentCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            onSaveTargetPicked(uri)
        }

        fileChooser = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            // Must always answer, even on cancel, or the file input stays stuck.
            answerFileChooser(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        }

        camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            onPhotoTaken(saved)
        }

        restored = savedInstanceState != null
        pendingCapture = savedInstanceState?.getString(STATE_CAPTURE_PATH)?.let(::File)

        pendingDirectoryRequest = savedInstanceState?.getString(STATE_DIRECTORY_REQUEST)
        // The staged file is in the cache directory, so it outlives the
        // activity that the save dialog displaced.
        savedInstanceState?.getString(STATE_SAVE_PATH)?.let { path ->
            pendingSave = PendingSave(
                File(path),
                savedInstanceState.getString(STATE_SAVE_NAME) ?: "download",
                savedInstanceState.getString(STATE_SAVE_MIME) ?: "application/octet-stream",
                savedInstanceState.getBoolean(STATE_SAVE_TEMP, true)
            )
        }

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
        // Photos from the last session. Kept until now because the page reads a
        // chosen file lazily, long after the chooser has answered.
        if (!restored) capturesDir(app.uuid).deleteRecursively()
        appRootPath = app.startUrl.substringBeforeLast('/', "")
            .let { if (it.isEmpty()) "/" else "/$it/" }
        val loader = AppAssetRegistry.loaderFor(app.uuid, appDir)
        val view = createWebView(loader)
        webView = view

        // Scoped to this one app: FileBridge filters every lookup by uuid, so a
        // folder granted to one zip is unreachable from another.
        view.addJavascriptInterface(
            FileBridge(applicationContext, app.uuid) { requestId ->
                runOnUiThread { startFolderPick(requestId) }
            },
            "__pwalibFs"
        )
        view.addJavascriptInterface(
            DownloadBridge(
                stagingDir(),
                onReady = { _, file, fileName, mime ->
                    runOnUiThread { askWhereToSave(file, fileName, mime, deleteAfter = true) }
                },
                onFail = { _, message -> runOnUiThread { toast(message) } }
            ),
            "__pwalibDl"
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
    private fun createWebView(loader: androidx.webkit.WebViewAssetLoader): WebView {
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
                // Restricted to main-frame navigation, so a missing script or
                // image still fails as a missing script or image.
                if (response != null && response.statusCode == 404 && request.isForMainFrame) {
                    resolveNavigation(loader, request.url)?.let { return it }
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
                val wantsImage = types.any { it.startsWith("image/") }
                return when {
                    // <input capture>: the page asked for the camera outright.
                    wantsImage && params.isCaptureEnabled -> launchCamera() || launchFileChooser(types)
                    wantsImage -> {
                        offerCameraOrFiles(types)
                        true
                    }
                    else -> launchFileChooser(types)
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

        view.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            startDownload(url, contentDisposition, mimeType)
        }

        return view
    }

    /**
     * Answers a navigation that matched no file, in the order a static host
     * would.
     *
     * The two shapes need different answers and both turn up in real builds. A
     * Next export writes `about.html`, so `/about` has to find that file; a
     * Vite SPA writes one index.html and expects every route to land on it.
     * Trying `.html` and a directory index before the shell serves both: Next
     * gets its page, and an SPA falls through to the shell as before.
     */
    private fun resolveNavigation(
        loader: androidx.webkit.WebViewAssetLoader,
        url: Uri
    ): WebResourceResponse? {
        val path = url.path.orEmpty().trimEnd('/')
        val candidates = mutableListOf<String>()
        // Skipped for a path that already names a file: /logo.png is missing,
        // not a route, and /logo.png.html is nobody's file.
        if (path.isNotEmpty() && !path.substringAfterLast('/').contains('.')) {
            candidates += "$path.html"
            candidates += "$path/index.html"
        }
        candidates += "/"
        // A build deployed under a subpath is extracted under one, so its shell
        // is not at "/" — that request finds nothing and falls through to here.
        // Added after "/" so an app living at the root is answered exactly as
        // before, without a directory index further in ever outranking it.
        appRootPath.takeIf { it != "/" }?.let { candidates += it }

        for (candidate in candidates) {
            val target = url.buildUpon().path(candidate).clearQuery().fragment(null).build()
            val response = loader.shouldInterceptRequest(target)
            if (response != null && response.statusCode == 200) return response
        }
        return null
    }

    private fun openExternally(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "リンクを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    // ----------------------------------------------------------- downloads

    /**
     * Answers a download the way a browser does: collect the bytes, then ask
     * where they go.
     *
     * Refusing was worse than it looked. By the time this runs the page's work
     * is over — fieldform reports "Exported 2 rows to CSV" with no file
     * anywhere — so the mini-app cannot tell the user that anything failed.
     */
    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?) {
        when {
            // Only the page can read its own blob, so it does the reading.
            url.startsWith("blob:") -> {
                val id = "d" + (++downloadSeq)
                webView?.evaluateJavascript(
                    "window.__pwalibReadBlob(" +
                        JSONObject.quote(url) + "," + JSONObject.quote(id) + ")",
                    null
                )
            }
            url.startsWith("data:") -> stageDataUrl(url, mimeType)
            else -> stageAppFile(url, contentDisposition, mimeType)
        }
    }

    private fun stageDataUrl(url: String, mimeType: String?) {
        val comma = url.indexOf(',')
        if (comma < 0) return toast("保存できませんでした")

        val header = url.substring("data:".length, comma)
        val payload = url.substring(comma + 1)
        val bytes = runCatching {
            if (header.contains(";base64")) Base64.decode(payload, Base64.DEFAULT)
            else Uri.decode(payload).toByteArray()
        }.getOrNull() ?: return toast("保存できませんでした")

        val mime = mimeType?.takeIf { it.isNotBlank() }
            ?: header.substringBefore(';').ifBlank { "application/octet-stream" }

        askDownloadName(url) { suggested ->
            val staged = File(stagingDir().apply { mkdirs() }, "dl-data")
            runCatching { staged.writeBytes(bytes) }
                .onSuccess { askWhereToSave(staged, suggested, mime, deleteAfter = true) }
                .onFailure { toast("保存できませんでした") }
        }
    }

    /** A link to one of the mini-app's own files; no copy needed to save it. */
    private fun stageAppFile(url: String, contentDisposition: String?, mimeType: String?) {
        val root = currentApp?.appDirPath?.let { File(it) } ?: return toast("保存できませんでした")
        val path = runCatching { Uri.parse(url).path }.getOrNull()?.removePrefix("/")
        if (path.isNullOrBlank()) return toast("保存できませんでした")

        val file = File(root, Uri.decode(path))
        val inside = runCatching {
            file.canonicalPath.startsWith(root.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!inside || !file.isFile) return toast("保存できませんでした")

        askWhereToSave(
            file,
            URLUtil.guessFileName(url, contentDisposition, mimeType),
            mimeType ?: "application/octet-stream",
            deleteAfter = false
        )
    }

    /** The `<a download>` value, which DownloadListener never carries. */
    private fun askDownloadName(url: String, then: (String) -> Unit) {
        val view = webView ?: return then("")
        view.evaluateJavascript("window.__pwalibDownloadName(" + JSONObject.quote(url) + ")") { raw ->
            then(runCatching { JSONArray("[" + raw + "]").optString(0) }.getOrDefault(""))
        }
    }

    private fun askWhereToSave(file: File, fileName: String, mime: String, deleteAfter: Boolean) {
        val save = PendingSave(file, safeFileName(fileName), mime, deleteAfter)
        pendingSave = save
        runCatching { documentCreator.launch(save.fileName) }.onFailure {
            pendingSave = null
            if (deleteAfter) file.delete()
            toast("保存先を選べません")
        }
    }

    private fun onSaveTargetPicked(uri: Uri?) {
        val save = pendingSave ?: return
        pendingSave = null

        if (uri == null) {
            if (save.deleteAfter) save.file.delete()
            toast("保存を取り消しました")
            return
        }

        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = contentResolver.openOutputStream(uri)
                        ?: error("保存先を開けませんでした")
                    stream.use { out -> save.file.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            if (save.deleteAfter) save.file.delete()
            toast(if (saved) save.fileName + " を保存しました" else "保存できませんでした")
        }
    }

    /** A page picks this name, so it must not be able to aim it at a path. */
    private fun safeFileName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "download" }

    private fun stagingDir(): File = File(cacheDir, "downloads")

    // ------------------------------------------------------- file inputs

    private fun answerFileChooser(uris: Array<Uri>?) {
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private fun launchFileChooser(types: Array<String>): Boolean = try {
        fileChooser.launch(types)
        true
    } catch (e: ActivityNotFoundException) {
        answerFileChooser(null)
        false
    }

    private fun offerCameraOrFiles(types: Array<String>) {
        chooserDialog = AlertDialog.Builder(this)
            .setItems(
                arrayOf(getString(R.string.take_photo), getString(R.string.choose_file))
            ) { _, which ->
                if (which != 0 || !launchCamera()) launchFileChooser(types)
            }
            .setOnCancelListener { answerFileChooser(null) }
            .show()
    }

    /** One directory per mini-app, so clearing one app's photos cannot touch another's. */
    private fun capturesDir(uuid: String): File = File(cacheDir, "captures/$uuid")

    /**
     * Hands the shot to the device's camera app rather than opening the camera
     * here. That needs no CAMERA permission, and the page never sees a live
     * feed: it gets the one picture the user chose to take, or nothing.
     */
    private fun launchCamera(): Boolean {
        val uuid = currentApp?.uuid ?: return false
        val file = File(capturesDir(uuid).apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg")
        return try {
            pendingCapture = file
            camera.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
            true
        } catch (e: ActivityNotFoundException) {
            pendingCapture = null
            false
        }
    }

    private fun onPhotoTaken(saved: Boolean) {
        val file = pendingCapture
        pendingCapture = null
        // After being recycled behind the camera app the page has reloaded and
        // the input that asked is gone, so there is nobody left to hand it to.
        if (!saved || file == null || file.length() == 0L || filePathCallback == null) {
            file?.delete()
            answerFileChooser(null)
            return
        }
        answerFileChooser(arrayOf(FileProvider.getUriForFile(this, "$packageName.fileprovider", file)))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        outState.putString(STATE_CAPTURE_PATH, pendingCapture?.absolutePath)
        pendingSave?.let {
            outState.putString(STATE_SAVE_PATH, it.file.absolutePath)
            outState.putString(STATE_SAVE_NAME, it.fileName)
            outState.putString(STATE_SAVE_MIME, it.mime)
            outState.putBoolean(STATE_SAVE_TEMP, it.deleteAfter)
        }
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
        chooserDialog?.dismiss()
        chooserDialog = null
        answerFileChooser(null)
        webView?.let { view ->
            container.removeView(view)
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
