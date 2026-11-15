package io.github.yosefchai.mealie.net

import io.github.yosefchai.mealie.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** נזרק כשבקשה ב-http מכוונת לכתובת ציבורית. */
class CleartextBlockedException(val host: String) : IOException("Cleartext to public host $host blocked")

/** לקוח OkHttp יחיד לכל האפליקציה: הצמדת תעודות, מדיניות http, ועוגיות משותפות עם ה-WebView. */
object Http {

    val userAgent = "MealieAndroid/${BuildConfig.VERSION_NAME} (Android)"

    private val cleartextGuard = Interceptor { chain ->
        val url = chain.request().url
        if (!url.isHttps && !HostPolicy.isCleartextAllowed(url.host)) throw CleartextBlockedException(url.host)
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
    }

    fun build(cookieJar: CookieJar = WebViewCookieJar()): OkHttpClient {
        val trust = PinningTrustManager()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier(PinningHostnameVerifier)
            .cookieJar(cookieJar)
            .addInterceptor(cleartextGuard)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val client: OkHttpClient by lazy { build() }

    /** לבקשות ל-GitHub (עדכונים): בלי עוגיות של ה-WebView. */
    val publicClient: OkHttpClient by lazy { client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build() }

    /** בלי מעקב אחרי הפניות: שער אימות מחזיר 302, ואנחנו רוצים לזהות אותו ולא להגיע לדף ה-HTML. */
    val probeClient: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
}
