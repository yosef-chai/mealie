package io.github.yosefchai.mealie

import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import io.github.yosefchai.mealie.auth.Pkce
import io.github.yosefchai.mealie.auth.SessionSync
import io.github.yosefchai.mealie.net.ServerUrl
import io.github.yosefchai.mealie.share.ShareActivity
import io.github.yosefchai.mealie.web.ContentDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Application רגיל: הבדיקות לא צריכות את WorkManager ואת אתחול ה-WebView של MealieApp.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AndroidLogicTest {

    @Test
    fun `first url is extracted from shared text`() {
        assertEquals(
            "https://example.com/recipes/pasta",
            ShareActivity.extractUrl("תראו איזה מתכון! https://example.com/recipes/pasta. מעולה"),
        )
        assertEquals("https://a.com/x", ShareActivity.extractUrl("one https://a.com/x two https://b.com/y"))
        assertEquals("https://example.com/r/1", ShareActivity.extractUrl("look example.com/r/1"))
        assertNull(ShareActivity.extractUrl("no link here"))
    }

    @Test
    fun `content disposition handles RFC 5987 and quotes`() {
        assertEquals("מתכון.json", ContentDisposition.filename("attachment; filename*=UTF-8''%D7%9E%D7%AA%D7%9B%D7%95%D7%9F.json"))
        assertEquals("backup 2026.zip", ContentDisposition.filename("attachment; filename=\"backup 2026.zip\""))
        assertEquals("plain.zip", ContentDisposition.filename("attachment; filename=plain.zip"))
        assertEquals(
            "unicode.zip",
            ContentDisposition.filename("attachment; filename=\"fallback.zip\"; filename*=UTF-8''unicode.zip"),
        )
        assertEquals("evil.txt", ContentDisposition.filename("attachment; filename=\"../../evil.txt\""))
        assertNull(ContentDisposition.filename(null))
    }

    @Test
    fun `pkce challenge is S256 of the verifier`() {
        // ערך צפוי שחושב בנפרד: base64url(sha256(verifier)) בלי padding.
        assertEquals(
            "ngF5GsXcbwljx6u133FFr3Xht9xooA_DuaX_3QwODtc",
            Pkce.challengeFor("dBjftJeZ4CVP-mJ92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val p = Pkce.create()
        assertTrue(p.verifier.length >= 43)
        assertEquals(Pkce.challengeFor(p.verifier), p.challenge)
    }

    @Test
    fun `session cookie is copied to the other address without Secure on http`() {
        val cookies = FakeCookies()
        val sync = SessionSync { cookies }
        val home = ServerUrl.parse("http://192.168.1.10:9000")!!
        val remote = ServerUrl.parse("https://mealie.example.com")!!
        cookies.setCookie(remote.origin + "/", "mealie.access_token=abc; Path=/")

        assertTrue(sync.propagate(remote, listOf(home, remote), 3600))
        assertEquals("abc", sync.tokenAt(home))
        assertTrue(cookies.raw.getValue(home.origin + "/").none { "Secure" in it })

        sync.setForAll(listOf(home, remote), "xyz", 60)
        assertTrue(cookies.raw.getValue(remote.origin + "/").last().contains("Secure"))
        assertEquals("xyz", sync.tokenAt(home))

        sync.clearAll(listOf(home, remote))
        assertNull(sync.tokenAt(home))
        assertNull(sync.tokenAt(remote))
    }

    /** CookieManager מינימלי: שומר name=value לכל origin, ו-Max-Age=0 מוחק. */
    private class FakeCookies : CookieManager() {
        val raw = mutableMapOf<String, MutableList<String>>()
        private val jar = mutableMapOf<String, MutableMap<String, String>>()

        override fun setCookie(url: String, value: String) {
            raw.getOrPut(url) { mutableListOf() } += value
            val (name, v) = value.substringBefore(';').split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val map = jar.getOrPut(url) { mutableMapOf() }
            if ("Max-Age=0" in value || v.isEmpty()) map.remove(name) else map[name] = v
        }

        override fun getCookie(url: String): String? =
            jar[url]?.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

        override fun setCookie(url: String, value: String, callback: ValueCallback<Boolean>?) = setCookie(url, value)
        override fun setAcceptCookie(accept: Boolean) = Unit
        override fun acceptCookie() = true
        override fun setAcceptThirdPartyCookies(webview: WebView, accept: Boolean) = Unit
        override fun acceptThirdPartyCookies(webview: WebView) = false
        override fun removeSessionCookies(callback: ValueCallback<Boolean>?) = Unit
        override fun removeAllCookies(callback: ValueCallback<Boolean>?) = jar.clear()
        override fun hasCookies() = jar.isNotEmpty()
        override fun flush() = Unit
        @Deprecated("") override fun removeSessionCookie() = Unit
        @Deprecated("") override fun removeAllCookie() = Unit
        @Deprecated("") override fun removeExpiredCookie() = Unit
    }
}
