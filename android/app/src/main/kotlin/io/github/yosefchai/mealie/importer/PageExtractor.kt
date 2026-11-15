package io.github.yosefchai.mealie.importer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.yosefchai.mealie.net.MealieJson
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** תוצאה של קריאת הדף במכשיר. */
sealed interface PageResult {
    /** נמצא מתכון: data הוא הפלט של extract.js (recipe, sections, counts, image). */
    data class Found(val data: JsonObject, val userAgent: String) : PageResult

    /** הדף נטען אבל אין בו מבנה של מתכון; הטקסט נשמר לייבוא עם AI, אם השרת תומך. */
    data class NoRecipe(val title: String?, val image: String?, val text: String?, val userAgent: String) : PageResult

    /** האתר הציג דף אימות (Cloudflare, Akamai וכו'). */
    data class Blocked(val title: String?) : PageResult

    data object Failed : PageResult
}

/**
 * פותח את הדף ב-WebView נסתר, כמו דפדפן רגיל של המשתמש: עובר אתרים שחוסמים שרתים, ומריץ JavaScript
 * כך שגם מתכון שנבנה בצד הלקוח נקרא. אחרי הטעינה מריץ את assets/import/extract.js.
 */
class PageExtractor(private val context: Context) {

    private val script: String by lazy { context.assets.open("import/extract.js").bufferedReader().use { it.readText() } }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(url: String, timeoutMs: Long = 30_000): PageResult = withContext(Dispatchers.Main) {
        val wv = WebView(context.applicationContext)
        var crashed = false
        try {
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = true
                blockNetworkImage = true // תמונות לא נחוצות לחילוץ; הכתובת שלהן נקראת מה-HTML
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            // גודל של טלפון, כדי שהאתר יבנה את הפריסה ויחשב אלמנטים גלויים
            val w = (412 * context.resources.displayMetrics.density).toInt()
            val h = (915 * context.resources.displayMetrics.density).toInt()
            wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            wv.layout(0, 0, w, h)

            val loaded = kotlinx.coroutines.CompletableDeferred<Boolean>()
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !(request.url.scheme == "https" || request.url.scheme == "http")

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = Unit

                override fun onPageFinished(view: WebView, url: String) {
                    if (!loaded.isCompleted) loaded.complete(true)
                }

                // תהליך הרינדור קרס או נסגר בגלל זיכרון: בלי טיפול כאן כל האפליקציה נסגרת.
                // ה-WebView הזה לא שמיש יותר; הייבוא ממשיך בשרת (create/url).
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    crashed = true
                    if (!loaded.isCompleted) loaded.complete(false)
                    return true
                }
            }
            wv.loadUrl(url)

            withTimeoutOrNull(timeoutMs) {
                if (!loaded.await()) return@withTimeoutOrNull PageResult.Failed
                // אתרים שבונים את המתכון אחרי הטעינה, או שמחזירים קודם דף "רק רגע" של אימות
                var last: JsonObject? = null
                repeat(6) { attempt ->
                    delay(if (attempt == 0) 800 else 2_000)
                    if (crashed) return@withTimeoutOrNull PageResult.Failed
                    val r = withTimeoutOrNull(10_000) { evaluate(wv) } ?: return@repeat
                    last = r
                    val ok = (r["ok"] as? JsonPrimitive)?.booleanOrNull == true
                    if (ok) return@withTimeoutOrNull PageResult.Found(r, wv.settings.userAgentString)
                    val title = (r["title"] as? JsonPrimitive)?.contentOrNull
                    if (!isChallenge(title) && attempt >= 1) {
                        return@withTimeoutOrNull PageResult.NoRecipe(
                            title,
                            (r["image"] as? JsonPrimitive)?.contentOrNull,
                            (r["text"] as? JsonPrimitive)?.contentOrNull,
                            wv.settings.userAgentString,
                        )
                    }
                }
                val title = (last?.get("title") as? JsonPrimitive)?.contentOrNull
                if (isChallenge(title)) PageResult.Blocked(title) else PageResult.Failed
            } ?: PageResult.Failed
        } finally {
            if (!crashed) wv.stopLoading()
            wv.destroy()
        }
    }

    private suspend fun evaluate(wv: WebView): JsonObject? = suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(script) { raw ->
            // evaluateJavascript מחזיר JSON של הערך; הסקריפט מחזיר מחרוזת JSON, כלומר עטיפה כפולה
            val obj = runCatching {
                val inner = MealieJson.parseToJsonElement(raw)
                val text = (inner as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
                MealieJson.parseToJsonElement(text).jsonObject
            }.getOrNull()
            if (cont.isActive) cont.resume(obj)
        }
    }

    companion object {
        private val CHALLENGE = Regex(
            "just a moment|attention required|access denied|checking your browser|radware|incapsula|are you a robot|captcha|רק רגע|הגישה נדחתה",
            RegexOption.IGNORE_CASE,
        )

        fun isChallenge(title: String?) = title != null && CHALLENGE.containsMatchIn(title)
    }
}
