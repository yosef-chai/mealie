package io.github.yosefchai.mealie.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.yosefchai.mealie.net.CertPins
import io.github.yosefchai.mealie.net.HostPolicy
import io.github.yosefchai.mealie.net.Http
import io.github.yosefchai.mealie.net.ServerUrl
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class WebUiState(
    /** נתיב יחסי לבסיס, למשל "/g/home/r/pasta". */
    val path: String = "/",
    val progress: Int = 0,
    val loading: Boolean = true,
    val routerReady: Boolean = false,
    val firstPaint: Boolean = false,
    /** הדף הנוכחי לא בשרת (שער אימות / ספק זהות): אין בו את הגשר, ולא יגיע ממנו דיווח theme. */
    val foreignPage: Boolean = false,
    val canGoBack: Boolean = false,
    val dark: Boolean? = null,
    val barColor: Int? = null,
    val backgroundColor: Int? = null,
    val wakeLock: Boolean = false,
    val error: WebError? = null,
    val untrusted: UntrustedCert? = null,
)

data class WebError(val code: Int, val description: String)

data class UntrustedCert(val hostPort: String, val fingerprint: String, val canPin: Boolean)

data class InjectOptions(val themeValue: String?, val raw: Boolean, val timers: Boolean = true)

/** פעולות שדורשות Activity (בורר קבצים, קישורים חיצוניים, OIDC של Google). */
interface WebHost {
    fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean

    fun openExternal(uri: Uri)

    /** Google חוסמת התחברות בתוך WebView; האפליקציה עוברת לזרימת OIDC native. */
    fun onEmbeddedGoogleSignIn()

    /** זמן בישול שזוהה בשלב הכנה: min/max בשניות (טווח כמו "20-25 דקות"); recipe ו-step לתיאור הטיימר. */
    fun onTimerRequest(minSeconds: Int, maxSeconds: Int, recipe: String?, step: Int?)
}

/**
 * מחזיק את ה-WebView ומחבר אותו לאפליקציה. יושב בשכבת התצוגה (לא ב-ViewModel, כדי לא לדלוף Context);
 * ה-Activity מצהיר configChanges, ולכן סיבוב מסך לא יוצר אותו מחדש.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebController(
    private val context: Context,
    private val host: WebHost,
    private val downloads: Downloads,
) {
    private val _state = MutableStateFlow(WebUiState())
    val state: StateFlow<WebUiState> = _state.asStateFlow()

    /**
     * מחזיק את ה-WebView. SwipeRefreshLayout שומר את הילד הראשון שלו (mTarget) ולא מאפס אותו ב-removeView,
     * ולכן WebView שהוחלף (תצוגה מקורית, החלפת שרת, קריסת רינדור) היה נשאר בגודל 0 ומסך לבן.
     * הילד הקבוע הוא FrameLayout, וה-WebView מתחלף בתוכו.
     */
    private val webHost = FrameLayout(context)

    val container: SwipeRefreshLayout = SwipeRefreshLayout(context).apply {
        addView(webHost, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // היעד הוא ה-FrameLayout, אז הגלילה נבדקת מול ה-WebView עצמו.
        setOnChildScrollUpCallback { _, _ -> webView?.canScrollVertically(-1) == true }
        setOnRefreshListener { webView?.reload() ?: run { isRefreshing = false } }
    }

    var webView: WebView? = null
        private set

    private var server: ServerUrl? = null
    private var allServers: List<ServerUrl> = emptyList()
    private var options = InjectOptions(themeValue = null, raw = false)
    private var scriptHandler: ScriptHandler? = null
    private var reply: JavaScriptReplyProxy? = null
    private var navSeq = 0
    private val main = Handler(Looper.getMainLooper())

    /** בזמן התחברות (דף כניסה או שער אימות) ניווט לדומיין אחר נשאר ב-WebView, כדי שהעוגייה תישמר כאן. */
    private var authFlow = false

    var onRoute: (String) -> Unit = {}
    var onRendererRecovered: () -> Unit = {}

    private val script: String by lazy { asset("inject/mobile.js") }
    private val css: String by lazy { asset("inject/mobile.css") }
    private val timers: String by lazy { asset("inject/timers.js") }

    val arrayBufferSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)

    /** הגשר ל-JS (MealieNative) זמין; בלעדיו לא מגיעים דיווחי route / theme מהדף. */
    val bridgeSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    /** מחבר לשרת: יוצר WebView חדש עם כללי origin של כל הכתובות שלו. */
    fun attach(active: ServerUrl, all: List<ServerUrl>, inject: InjectOptions, authGate: Boolean, restore: Bundle? = null) {
        server = active
        allServers = (all + active).distinctBy { it.origin }
        options = inject
        authFlow = authGate
        recreate()
        val restored = restore?.let { webView?.restoreState(it) } != null
        if (!restored) webView?.loadUrl(active.resolve("/"))
    }

    /** מעבר בין כתובת פנימית לחיצונית של אותו שרת, בלי לאבד את המסך הנוכחי. */
    fun switchOrigin(active: ServerUrl) {
        val path = _state.value.path
        server = active
        if (active.origin !in allServers.map { it.origin }) allServers = allServers + active
        webView?.loadUrl(active.resolve(path))
    }

    private fun recreate() {
        scriptHandler?.remove()
        scriptHandler = null
        reply = null
        webView?.let {
            webHost.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        container.isRefreshing = false
        val wv = WebView(context)
        configure(wv)
        webView = wv
        webHost.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        _state.value = WebUiState()
    }

    private fun configure(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = "$userAgentString ${Http.userAgent}"
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // ל-Mealie יש מצב כהה משלו.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(wv.settings, true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)

        val origins = allServers.map { it.origin }.toSet()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, "MealieNative", origins) { _, message, _, isMainFrame, replyProxy ->
                if (isMainFrame) {
                    reply = replyProxy
                    onMessage(message)
                }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, buildScript(), origins)
        }

        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("http")) downloads.fetch(url, contentDisposition, mimeType)
        }
    }

    private fun buildScript(): String {
        val cfg = buildJsonObject {
            put("raw", options.raw)
            put("arrayBuffer", arrayBufferSupported)
            if (!options.raw) {
                options.themeValue?.let { put("theme", it) }
                put("css", css)
                put("timers", options.timers)
            }
        }
        // timers.js מגדיר את MealieTimers (זיהוי זמנים); mobile.js מוסיף את הכפתורים לשלבים
        val timerScript = if (!options.raw && options.timers) timers else ""
        return "window.__mealieApp=$cfg;\n$timerScript\n$script"
    }

    // ---- פקודות ----

    fun load(path: String) {
        val s = server ?: return
        webView?.loadUrl(s.resolve(path))
    }

    /** ניווט פנימי דרך ה-router של Nuxt (בלי טעינה מחדש); נפילה ל-loadUrl אם אין router או שהניווט נכשל. */
    fun navigate(path: String) {
        val proxy = reply
        if (!_state.value.routerReady || proxy == null) {
            load(path)
            return
        }
        val seq = ++navSeq
        send(buildJsonObject { put("cmd", "navigate"); put("path", path); put("seq", seq) })
        // אם אין תשובה בכלל (דף תקוע), טוענים ישירות.
        main.postDelayed({ if (navSeq == seq && pendingNav == seq) load(path) }, 1500)
        pendingNav = seq
    }

    private var pendingNav = 0

    fun scrollToTopOrRoot(rootPath: String) {
        if (reply == null) {
            load(rootPath)
            return
        }
        pendingRoot = rootPath
        send(buildJsonObject { put("cmd", "scrollTop"); put("seq", ++navSeq) })
    }

    private var pendingRoot: String? = null

    fun openDrawer() = send(buildJsonObject { put("cmd", "openDrawer") })

    fun setTheme(value: String) {
        options = options.copy(themeValue = value)
        send(buildJsonObject { put("cmd", "theme"); put("value", value) })
        webView?.reload()
    }

    fun reload() {
        _state.update { it.copy(error = null) }
        webView?.reload()
    }

    fun setOffline(offline: Boolean) {
        webView?.settings?.cacheMode = if (offline) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
    }

    /** אחורה בהיסטוריה, אבל אף פעם לא חזרה לדף הכניסה. מחזיר false אם אין לאן. */
    fun goBack(): Boolean {
        val wv = webView ?: return false
        if (!wv.canGoBack()) return false
        val list = wv.copyBackForwardList()
        val prev = list.getItemAtIndex(list.currentIndex - 1)?.url
        val prevPath = prev?.let { u -> server?.relativePath(u) }
        if (prevPath != null && Tab.isLogin(prevPath)) return false
        wv.goBack()
        return true
    }

    fun clearHistory() = webView?.clearHistory()

    fun saveState(out: Bundle) {
        val wv = webView ?: return
        // מגבלת 1MB לכל התהליך: עד 200KB, ובלי היסטוריה קדימה; אחרת נשמר רק ה-URL.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) {
            WebViewCompat.saveState(wv, out, MAX_STATE_BYTES, false)
        } else {
            wv.saveState(out)
        }
    }

    fun clearUntrusted() = _state.update { it.copy(untrusted = null) }

    fun destroy() {
        scriptHandler?.remove()
        webView?.let {
            webHost.removeView(it)
            it.destroy()
        }
        webView = null
    }

    private fun send(obj: JsonObject) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching { reply?.postMessage(obj.toString()) }
    }

    // ---- הודעות מה-JS ----

    private fun onMessage(message: WebMessageCompat) {
        if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            downloads.binaryChunk(message.arrayBuffer)
            return
        }
        val data = message.data ?: return
        val obj = runCatching { io.github.yosefchai.mealie.net.MealieJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
        fun int(k: String) = (obj[k] as? JsonPrimitive)?.intOrNull
        fun bool(k: String) = (obj[k] as? JsonPrimitive)?.booleanOrNull
        when (str("type")) {
            "route" -> str("path")?.let(::onPath)
            "ready" -> _state.update { it.copy(routerReady = bool("router") == true) }
            "nav" -> {
                pendingNav = 0
                if (bool("ok") != true) str("path")?.let(::load)
            }
            "scrolled" -> {
                val root = pendingRoot
                pendingRoot = null
                if (bool("wasAtTop") == true && root != null) navigate(root)
            }
            "theme" -> _state.update {
                it.copy(dark = bool("dark"), barColor = parseCss(str("bar")), backgroundColor = parseCss(str("background")))
            }
            "wakeLock" -> _state.update { it.copy(wakeLock = bool("on") == true) }
            "timer" -> {
                val min = int("min") ?: return
                val max = int("max") ?: min
                // AlarmClock.EXTRA_LENGTH: 1 עד 86400 שניות
                if (min in 1..MAX_TIMER_SECONDS && max in min..MAX_TIMER_SECONDS) {
                    host.onTimerRequest(min, max, str("recipe")?.trim()?.takeIf { it.isNotEmpty() }?.take(80), int("step"))
                }
            }
            "download-begin" -> downloads.begin(
                int("id") ?: return,
                str("name") ?: "download",
                str("mime") ?: "application/octet-stream",
                int("chunks") ?: 1,
            )
            "download-chunk" -> downloads.base64Chunk(int("id") ?: return, int("index") ?: return, str("data") ?: return)
            "download-error" -> downloads.fail(int("id") ?: return)
            "download-url" -> {
                val url = str("url") ?: return
                downloads.fetch(url, null, null, str("name"))
            }
        }
    }

    private fun onPath(path: String) {
        _state.update { it.copy(path = path, canGoBack = webView?.canGoBack() == true) }
        authFlow = Tab.isLogin(path)
        onRoute(path)
    }

    private fun parseCss(value: String?): Int? {
        val m = Regex("""rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)""").find(value ?: return null) ?: return null
        val (r, g, b) = m.destructured
        val a = m.groupValues[4].toFloatOrNull() ?: 1f
        if (a < 0.5f) return null
        return Color.rgb(r.toInt(), g.toInt(), b.toInt())
    }

    private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

    // ---- WebViewClient ----

    private inner class Client : WebViewClientCompat() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val url = uri.toString()
            val scheme = uri.scheme.orEmpty()
            if (scheme != "http" && scheme != "https") {
                host.openExternal(uri)
                return true
            }
            if (scheme == "http" && !HostPolicy.isCleartextAllowed(uri.host.orEmpty())) {
                _state.update { it.copy(error = WebError(BLOCKED_CLEARTEXT, uri.host.orEmpty())) }
                return true
            }
            if (allServers.any { it.sameOrigin(url) }) return false
            if (uri.host == "accounts.google.com") {
                host.onEmbeddedGoogleSignIn()
                return true
            }
            // ספק זהות או שער אימות: נשאר כאן כדי שהעוגייה תגיע ל-WebView.
            if (authFlow && request.isForMainFrame) return false
            host.openExternal(uri)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            if (uri.scheme == "http" && !HostPolicy.isCleartextAllowed(uri.host.orEmpty())) {
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val foreign = allServers.none { it.sameOrigin(url) }
            _state.update { it.copy(loading = true, error = null, routerReady = false, foreignPage = foreign) }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && allServers.any { it.sameOrigin(url) }) {
                view.evaluateJavascript(buildScript(), null)
            }
            val gateOrigin = allServers.none { it.sameOrigin(url) }
            if (gateOrigin) authFlow = true
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            _state.update { it.copy(firstPaint = true) }
        }

        override fun onPageFinished(view: WebView, url: String) {
            container.isRefreshing = false
            _state.update { it.copy(loading = false, canGoBack = view.canGoBack()) }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            // גיבוי לדיווח מה-JS (למשל ב-WebView בלי WebMessageListener).
            server?.relativePath(url)?.let { if (it != _state.value.path) onPath(it) }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: androidx.webkit.WebResourceErrorCompat) {
            if (!request.isForMainFrame) return
            val code = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) error.errorCode else -1
            val desc = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) error.description.toString() else ""
            container.isRefreshing = false
            _state.update { it.copy(error = WebError(code, desc), loading = false) }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val u = error.url?.toHttpUrlOrNull()
            val cert = error.certificate?.let(CertPins::fromWebView)
            // proceed רק לתעודה שהמשתמש אישר מראש עבור כתובת פרטית זו (טביעת אצבע SHA-256).
            if (u != null && cert != null && CertPins.isPinned(u.host, u.port, cert)) {
                handler.proceed()
                return
            }
            handler.cancel()
            if (u != null && cert != null && allServers.any { it.host == u.host && it.port == u.port }) {
                _state.update {
                    it.copy(
                        untrusted = UntrustedCert("${u.host}:${u.port}", CertPins.fingerprint(cert), HostPolicy.isPrivateHost(u.host)),
                        loading = false,
                    )
                }
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // בלי טיפול האפליקציה כולה קורסת. יוצרים WebView חדש וטוענים את המסך האחרון.
            if (view !== webView) return true
            val path = _state.value.path
            val s = server ?: return true
            recreate()
            webView?.loadUrl(s.resolve(path))
            onRendererRecovered()
            return true
        }
    }

    // ---- WebChromeClient ----

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            _state.update { it.copy(progress = newProgress) }
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = host.showFileChooser(filePathCallback, fileChooserParams)
    }

    companion object {
        const val MAX_STATE_BYTES = 200 * 1024
        const val MAX_TIMER_SECONDS = 24 * 3600
        const val BLOCKED_CLEARTEXT = -1001
    }
}
