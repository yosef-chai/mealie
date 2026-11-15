package io.github.yosefchai.mealie.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * מקור אמת אחד לעוגיות: ה-CookieManager של ה-WebView. כך בקשות native (בדיקות שרת, הורדות)
 * נושאות את עוגיית ההתחברות של Mealie וגם עוגיות של שער אימות (Authelia, Authentik).
 */
class WebViewCookieJar(private val manager: () -> CookieManager = { CookieManager.getInstance() }) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = manager()
        val target = url.toString()
        cookies.forEach { cm.setCookie(target, it.toString()) }
        cm.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = manager().getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
