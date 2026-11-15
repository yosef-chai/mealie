package io.github.yosefchai.mealie.net

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface ProbeResult {
    val url: ServerUrl

    data class Ok(override val url: ServerUrl, val about: AboutInfo) : ProbeResult

    /** שער אימות (Authelia/Authentik/Cloudflare Access) לפני Mealie: צריך להשלים אותו ב-WebView. */
    data class AuthGate(override val url: ServerUrl, val location: String?) : ProbeResult

    data class NotMealie(override val url: ServerUrl, val status: Int) : ProbeResult

    data class UntrustedCert(override val url: ServerUrl, val fingerprint: String, val canPin: Boolean) : ProbeResult

    data class CleartextBlocked(override val url: ServerUrl) : ProbeResult

    data class Unreachable(override val url: ServerUrl, val error: Throwable) : ProbeResult
}

/** בודק אם כתובת היא שרת Mealie, בלי לעקוב אחרי הפניות ועם בדיקת JSON. */
class ServerProbe(private val client: OkHttpClient = Http.probeClient) {

    suspend fun probe(url: ServerUrl, timeoutMs: Long? = null): ProbeResult = withContext(Dispatchers.IO) {
        val c = timeoutMs?.let {
            client.newBuilder()
                .callTimeout(it, java.util.concurrent.TimeUnit.MILLISECONDS)
                .connectTimeout(it, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
        } ?: client
        val request = Request.Builder()
            .url(url.resolve("/api/app/about"))
            .header("Accept", "application/json")
            .build()
        try {
            c.newCall(request).execute().use { resp ->
                val type = resp.header("Content-Type").orEmpty()
                when {
                    resp.isRedirect -> ProbeResult.AuthGate(url, resp.header("Location"))
                    resp.isSuccessful && type.contains("json") -> {
                        val about = runCatching { MealieJson.decodeFromString<AboutInfo>(resp.body.string()) }.getOrNull()
                        if (about != null && about.version.isNotBlank()) ProbeResult.Ok(url, about)
                        else ProbeResult.NotMealie(url, resp.code)
                    }
                    // HTML במקום JSON, או 401/403 משער: נראה כמו דף התחברות של שכבת הגנה.
                    type.contains("html") || resp.code == 401 || resp.code == 403 ->
                        ProbeResult.AuthGate(url, null)
                    else -> ProbeResult.NotMealie(url, resp.code)
                }
            }
        } catch (e: CleartextBlockedException) {
            ProbeResult.CleartextBlocked(url)
        } catch (e: IOException) {
            val cert = e.findCause<UntrustedCertificateException>()
            if (cert != null) ProbeResult.UntrustedCert(url, cert.fingerprint, HostPolicy.isPrivateHost(url.host))
            else ProbeResult.Unreachable(url, e)
        }
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var t: Throwable? = this
        while (t != null) {
            if (t is T) return t
            t = t.cause
        }
        return null
    }
}
