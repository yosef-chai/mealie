package io.github.yosefchai.mealie.auth

import android.webkit.CookieManager
import io.github.yosefchai.mealie.net.ServerUrl

/**
 * עוגיות מבודדות לפי origin, והכתובת הפנימית והחיצונית הן שני origins שונים.
 * כאן משכפלים את mealie.access_token בין הכתובות של אותו שרת, כדי שמעבר רשת לא יחייב כניסה מחדש.
 * המאפיינים תואמים ל-session_cookie_attrs בשרת: Path=/, SameSite=Lax, ו-Secure רק ב-https.
 */
class SessionSync(private val cookies: () -> CookieManager = { CookieManager.getInstance() }) {

    fun tokenAt(url: ServerUrl): String? =
        cookies().getCookie(url.origin + "/")
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$COOKIE=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    /** מעתיק את הטוקן מהכתובת הפעילה לשאר הכתובות. מחזיר true אם היה מה להעתיק. */
    fun propagate(from: ServerUrl, all: List<ServerUrl>, maxAgeSeconds: Long): Boolean {
        val token = tokenAt(from) ?: return false
        all.filter { it.origin != from.origin && tokenAt(it) != token }
            .forEach { set(it, token, maxAgeSeconds) }
        cookies().flush()
        return true
    }

    /** טוקן מ-OIDC native או טוקן API שהודבק: נכנס לכל הכתובות לפני טעינת הדף. */
    fun setForAll(all: List<ServerUrl>, token: String, maxAgeSeconds: Long) {
        all.forEach { set(it, token, maxAgeSeconds) }
        cookies().flush()
    }

    /** יציאה: מוחק בכל הכתובות. */
    fun clearAll(all: List<ServerUrl>) {
        all.forEach { cookies().setCookie(it.origin + "/", "$COOKIE=; Path=/; Max-Age=0") }
        cookies().flush()
    }

    private fun set(url: ServerUrl, token: String, maxAgeSeconds: Long) {
        val secure = if (url.isHttps) "; Secure" else ""
        cookies().setCookie(url.origin + "/", "$COOKIE=$token; Path=/; Max-Age=$maxAgeSeconds; SameSite=Lax$secure")
    }

    companion object {
        const val COOKIE = "mealie.access_token"
    }
}
