package io.github.yosefchai.mealie

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.yosefchai.mealie.auth.NativeOidc
import io.github.yosefchai.mealie.auth.OidcRedirects
import io.github.yosefchai.mealie.core.AppSettings
import io.github.yosefchai.mealie.core.Notifications
import io.github.yosefchai.mealie.core.ThemeMode
import io.github.yosefchai.mealie.net.HostPolicy
import io.github.yosefchai.mealie.net.ServerUrl
import io.github.yosefchai.mealie.ui.AppViewModel
import io.github.yosefchai.mealie.ui.Connection
import io.github.yosefchai.mealie.ui.MealieAppRoot
import io.github.yosefchai.mealie.ui.UpdateState
import io.github.yosefchai.mealie.ui.theme.MealieOrange
import io.github.yosefchai.mealie.ui.theme.MealieTheme
import io.github.yosefchai.mealie.web.CameraImage
import io.github.yosefchai.mealie.web.DownloadEvent
import io.github.yosefchai.mealie.web.Downloads
import io.github.yosefchai.mealie.web.InjectOptions
import io.github.yosefchai.mealie.web.Tab
import io.github.yosefchai.mealie.web.WebController
import io.github.yosefchai.mealie.web.WebHost
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.toArgb

class MainActivity : AppCompatActivity(), WebHost {

    private val vm: AppViewModel by viewModels()
    private lateinit var downloads: Downloads
    lateinit var web: WebController
        private set

    /** מה שמחובר כרגע ל-WebView: שרת, כתובת ואפשרויות הזרקה. */
    private var attachedServerId: String? = null
    private var attachedOrigin: String? = null
    private var attachedInject: InjectOptions? = null
    private var restoreBundle: Bundle? = null
    private var pendingPath: String? = null
    private val startedAt = SystemClock.elapsedRealtime()

    /** הודעות קצרות ל-Snackbar של המסך הראשי. */
    val snackbar = MutableSharedFlow<String>(extraBufferCapacity = 4)

    // ---- בורר קבצים ומצלמה ----
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var captureFile: File? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        fileCallback = null
        val capture = captureFile
        captureFile = null
        if (callback == null) return@registerForActivityResult
        if (result.resultCode != RESULT_OK) {
            // חובה לשחרר את ה-callback גם בביטול, אחרת ה-WebView לא יפתח בורר שוב.
            callback.onReceiveValue(null)
            capture?.delete()
            return@registerForActivityResult
        }
        val data = result.data
        val picked = buildList {
            data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
            if (isEmpty()) data?.data?.let(::add)
        }
        if (picked.isNotEmpty()) {
            capture?.delete()
            callback.onReceiveValue(picked.toTypedArray())
            return@registerForActivityResult
        }
        if (capture == null || capture.length() == 0L) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { prepareCapture(capture) }
            callback.onReceiveValue(uri?.let { arrayOf(it) })
        }
    }

    // ---- הרשאות ----
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        localNetworkGranted.value = ok
        vm.retry()
    }

    // מצב Compose: כרטיס ההסבר נעלם מיד כשההרשאה ניתנת (גם דרך הגדרות המערכת)
    private val localNetworkGranted = androidx.compose.runtime.mutableStateOf(false)
    private val legacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ---- OIDC native: Auth Tab מחזיר תוצאה ישירות ----
    private val authTab = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        lifecycleScope.launch {
            val uri = if (result.resultCode == AuthTabIntent.RESULT_OK) result.resultUri else null
            if (vm.completeOidc(uri)) web.load("/")
        }
    }

    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        (vm.update.value as? UpdateState.NeedsPermission)?.let { vm.installUpdate(it.info) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        localNetworkGranted.value = Build.VERSION.SDK_INT < 37 || granted(PERMISSION_LOCAL_NETWORK)
        enableEdgeToEdge()
        restoreBundle = savedInstanceState?.getBundle(STATE_WEB)
        // הפתיחה נשארת עד שהממשק צויר (onPageCommitVisible), עם תקרה קשיחה של 2 שניות.
        splash.setKeepOnScreenCondition {
            val waited = SystemClock.elapsedRealtime() - startedAt
            val c = vm.connection.value
            waited < 2_000 && (c is Connection.Loading || (c is Connection.Ready && !web.state.value.firstPaint))
        }

        downloads = Downloads(applicationContext, lifecycleScope)
        web = WebController(this, this, downloads)
        web.onRoute = vm::onRoute

        setContent {
            val settings by vm.settings.collectAsState()
            val s = settings ?: AppSettings()
            MealieTheme(mode = s.theme, dynamicColor = s.dynamicColor) {
                MealieAppRoot(vm = vm, activity = this)
            }
        }

        observe()
        handleIntent(intent)
        requestStartupPermissions()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch { vm.connection.collect(::onConnection) }
                launch {
                    vm.settings.collect { s -> s?.let(::applySettings) }
                }
                launch {
                    web.state.collect { st ->
                        if (st.wakeLock) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                launch { downloads.events.collect(::onDownload) }
                launch {
                    OidcRedirects.redirects.collect { uri -> if (vm.completeOidc(uri)) web.load("/") }
                }
            }
        }
    }

    private fun onConnection(c: Connection) {
        val settings = vm.settings.value ?: return
        val (server, url, gate) = when (c) {
            is Connection.Ready -> Triple(c.server, c.url, c.gate)
            is Connection.Offline -> Triple(c.server, c.url ?: return, false)
            else -> return
        }
        web.setOffline(c is Connection.Offline)
        val inject = injectOptions(settings)
        when {
            attachedServerId == server.id && attachedInject == inject && attachedOrigin != url.origin -> {
                // אותו שרת, כתובת אחרת (בית ↔ מרחוק): ממשיכים מאותו מסך.
                web.switchOrigin(url)
                attachedOrigin = url.origin
            }
            attachedServerId != server.id || attachedInject != inject -> {
                web.attach(url, server.all, inject, gate, restoreBundle)
                restoreBundle = null
                attachedServerId = server.id
                attachedOrigin = url.origin
                attachedInject = inject
            }
        }
        pendingPath?.let {
            pendingPath = null
            web.load(it)
        }
        if (needsLocalNetworkPermission(server.all)) requestLocalNetworkIfNeeded()
    }

    private fun injectOptions(s: AppSettings) = InjectOptions(
        themeValue = when (s.theme) {
            ThemeMode.SYSTEM -> "auto"
            ThemeMode.LIGHT -> "light"
            ThemeMode.DARK -> "dark"
        },
        raw = s.rawWebView,
        timers = s.stepTimers,
    )

    private fun applySettings(s: AppSettings) {
        val inject = injectOptions(s)
        val current = attachedInject ?: return
        if (current.raw != inject.raw) {
            // מעבר ל"תצוגת ווב מקורית" או ממנה: חיבור מחדש עם הזרקה אחרת.
            attachedServerId = null
            onConnection(vm.connection.value)
        } else if (current.themeValue != inject.themeValue) {
            attachedInject = inject
            inject.themeValue?.let(web::setTheme)
        }
    }

    private fun onDownload(e: DownloadEvent) {
        when (e) {
            is DownloadEvent.Started -> snackbar.tryEmit(getString(R.string.download_started, e.name))
            is DownloadEvent.Finished -> {
                Notifications.downloadFinished(this, e.name, e.uri, e.mime)
                snackbar.tryEmit(getString(R.string.download_saved, e.name))
            }
            is DownloadEvent.Failed -> snackbar.tryEmit(getString(R.string.download_failed, e.name))
        }
    }

    // ---- Intents: שיתוף, קיצורי דרך, התראת עדכון ----

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val path = when (intent.action) {
            ACTION_IMPORT -> {
                val url = intent.getStringExtra(EXTRA_IMPORT_URL)
                if (url == null) {
                    snackbar.tryEmit(getString(R.string.share_no_url))
                    "/r/create/url"
                } else {
                    // ה-middleware של Mealie מוסיף את ה-groupSlug (pwa-share-target-redirect).
                    "/r/create/url?recipe_import_url=" + URLEncoder.encode(url, "UTF-8")
                }
            }
            // ווידג'טים וגיליון הייבוא: נתיב בתוך Mealie (מתכון, רשימת קניות)
            ACTION_OPEN_PATH -> intent.getStringExtra(EXTRA_PATH)?.takeIf { it.startsWith("/") && !it.startsWith("//") }
            ACTION_OPEN -> when (intent.data?.schemeSpecificPart) {
                "shopping" -> Tab.SHOPPING.rootPath(null)
                "mealplan" -> Tab.MEALPLAN.rootPath(null)
                "import" -> "/r/create/url"
                else -> null
            }
            Notifications.ACTION_SHOW_UPDATE -> {
                vm.checkForUpdate(manual = true)
                null
            }
            else -> null
        } ?: return
        if (attachedServerId != null) web.load(path) else pendingPath = path
    }

    // ---- WebHost ----

    override fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val accept = params.acceptTypes.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val wantsImages = accept.isEmpty() || accept.any { it.startsWith("image/") || it == "*/*" }
        val content = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = accept.singleOrNull()?.takeIf { '/' in it } ?: "*/*"
            if (accept.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accept.filter { '/' in it }.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        }
        val camera = if (wantsImages) cameraIntent() else null
        val launch = when {
            params.isCaptureEnabled && camera != null -> camera
            camera != null -> Intent.createChooser(content, null).putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
            else -> Intent.createChooser(content, null)
        }
        return try {
            fileChooser.launch(launch)
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            captureFile?.delete()
            captureFile = null
            callback.onReceiveValue(null)
            true
        }
    }

    private fun cameraIntent(): Intent? {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        captureFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        return intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** צילום: הקטנה ותיקון כיוון לפי ההגדרה, ואז URI של FileProvider להעלאה. */
    private fun prepareCapture(file: File): Uri? {
        val compress = vm.settings.value?.compressCameraImages ?: true
        val out = if (compress) {
            val target = File(file.parentFile, "upload-${file.name}")
            if (CameraImage.shrink(file, target)) {
                file.delete()
                target
            } else {
                file
            }
        } else {
            file
        }
        return FileProvider.getUriForFile(this, "$packageName.files", out)
    }

    override fun openExternal(uri: Uri) {
        if (uri.scheme == "http" || uri.scheme == "https") {
            val dark = web.state.value.dark ?: false
            val color = web.state.value.barColor ?: MealieOrange.toArgb()
            val params = CustomTabColorSchemeParams.Builder().setToolbarColor(color).build()
            runCatching {
                CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(params)
                    .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
                    .setShowTitle(true)
                    .build()
                    .launchUrl(this, uri)
            }.onFailure { startSafely(Intent(Intent.ACTION_VIEW, uri)) }
            return
        }
        val intent = if (uri.scheme == "intent") {
            runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                ?.apply { selector = null; component = null; addCategory(Intent.CATEGORY_BROWSABLE) }
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
        intent?.let(::startSafely)
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            snackbar.tryEmit(getString(R.string.no_app_for_link))
        }
    }

    override fun onEmbeddedGoogleSignIn() = startOidc()

    override fun onTimerRequest(minSeconds: Int, maxSeconds: Int, recipe: String?, step: Int?) {
        val request = io.github.yosefchai.mealie.web.TimerRequest(minSeconds, maxSeconds, recipe, step)
        if (minSeconds == maxSeconds) startTimer(request, minSeconds) else vm.requestTimer(request)
    }

    /** מפעיל טיימר בשעון; אם אין אפליקציית שעון שתומכת בזה, מודיעים. */
    fun startTimer(request: io.github.yosefchai.mealie.web.TimerRequest, seconds: Int) {
        vm.requestTimer(null)
        val ok = io.github.yosefchai.mealie.web.Timers.start(this, seconds, request.recipe, request.step)
        snackbar.tryEmit(
            if (ok) getString(R.string.timer_started, io.github.yosefchai.mealie.web.Timers.format(seconds))
            else getString(R.string.timer_no_clock),
        )
    }

    /** התחברות דרך דפדפן המערכת (Auth Tab, עם נפילה ל-Custom Tab). */
    fun startOidc() {
        lifecycleScope.launch {
            val uri = vm.prepareOidc()
            if (uri == null) {
                snackbar.tryEmit(getString(R.string.oidc_unavailable))
                return@launch
            }
            AuthTabIntent.Builder().build().launch(authTab, uri, NativeOidc.SCHEME)
        }
    }

    fun openInstallPermission() {
        installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    }

    // ---- הרשאות ----

    private fun requestStartupPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private var askedLocalNetwork = false

    fun requestLocalNetworkIfNeeded() {
        if (Build.VERSION.SDK_INT < 37 || granted(PERMISSION_LOCAL_NETWORK)) return
        if (!askedLocalNetwork) {
            askedLocalNetwork = true
            localNetworkPermission.launch(PERMISSION_LOCAL_NETWORK)
            return
        }
        // כבר נשאלה (ואולי נדחתה לצמיתות): הכפתור פותח את הגדרות האפליקציה
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    /** Android 17+: כל כתובת ברשת המקומית (גם אם הוזנה כ"מרוחקת") דורשת את ההרשאה. */
    fun needsLocalNetworkPermission(urls: List<ServerUrl?>): Boolean =
        Build.VERSION.SDK_INT >= 37 && urls.any { it != null && HostPolicy.isPrivateHost(it.host) } && !localNetworkGranted.value

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---- מחזור חיים ----

    override fun onResume() {
        super.onResume()
        val ok = Build.VERSION.SDK_INT < 37 || granted(PERMISSION_LOCAL_NETWORK)
        if (ok != localNetworkGranted.value) {
            localNetworkGranted.value = ok
            if (ok) vm.retry()
        }
    }

    override fun onPause() {
        super.onPause()
        vm.syncSession()
        vm.syncWidgets()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) outState.putBundle(STATE_WEB, Bundle().also(web::saveState))
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION_IMPORT = "io.github.yosefchai.mealie.action.IMPORT"
        const val ACTION_OPEN = "io.github.yosefchai.mealie.action.OPEN"
        const val ACTION_OPEN_PATH = "io.github.yosefchai.mealie.action.OPEN_PATH"
        const val EXTRA_PATH = "io.github.yosefchai.mealie.extra.PATH"
        const val EXTRA_IMPORT_URL = "io.github.yosefchai.mealie.extra.IMPORT_URL"
        const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val STATE_WEB = "web"
    }
}
