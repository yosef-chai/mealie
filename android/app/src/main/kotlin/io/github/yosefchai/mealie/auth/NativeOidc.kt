package io.github.yosefchai.mealie.auth

import android.net.Uri
import androidx.core.net.toUri
import io.github.yosefchai.mealie.net.Http
import io.github.yosefchai.mealie.net.MealieJson
import io.github.yosefchai.mealie.net.MealieToken
import io.github.yosefchai.mealie.net.NativeOidcConfig
import io.github.yosefchai.mealie.net.NativeTokenRequest
import io.github.yosefchai.mealie.net.ServerUrl
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * התחברות OIDC של Mealie לאפליקציות נייטיב (docs: authentication/oidc-v2.md, "Native and mobile clients"):
 * האפליקציה בונה בקשת PKCE בעצמה, פותחת אותה בדפדפן המערכת (Auth Tab), ו-Mealie מחליף את הקוד בטוקן.
 */
class NativeOidc(private val client: OkHttpClient = Http.client) {

    data class Pending(val server: ServerUrl, val pkce: Pkce)

    sealed interface Outcome {
        data class Success(val token: MealieToken) : Outcome

        data object Cancelled : Outcome

        data class Failed(val reason: String) : Outcome
    }

    suspend fun config(server: ServerUrl): NativeOidcConfig? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(server.resolve("/api/auth/oauth/native/config")).build())
                .execute().use { r ->
                    if (!r.isSuccessful) null else MealieJson.decodeFromString<NativeOidcConfig>(r.body.string())
                }
        }.getOrNull()
    }

    fun authorizationUri(config: NativeOidcConfig, pkce: Pkce): Uri =
        config.authorizationEndpoint.toUri().buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", config.scope)
            .appendQueryParameter("state", pkce.state)
            .appendQueryParameter("nonce", pkce.nonce)
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

    /** מטפל בהפניה מהספק: בודק state, ואז שולח את הקוד ל-Mealie. השרת גם מחזיר Set-Cookie שנשמר ב-CookieManager. */
    suspend fun complete(pending: Pending, redirect: Uri): Outcome = withContext(Dispatchers.IO) {
        redirect.getQueryParameter("error")?.let {
            return@withContext if (it == "access_denied") Outcome.Cancelled else Outcome.Failed(it)
        }
        if (redirect.getQueryParameter("state") != pending.pkce.state) return@withContext Outcome.Failed("state")
        val code = redirect.getQueryParameter("code") ?: return@withContext Outcome.Failed("code")
        val body = MealieJson.encodeToString(
            NativeTokenRequest.serializer(),
            NativeTokenRequest(code, pending.pkce.verifier, REDIRECT_URI, pending.pkce.nonce),
        ).toRequestBody("application/json".toMediaType())
        try {
            client.newCall(
                Request.Builder().url(pending.server.resolve("/api/auth/oauth/native/token")).post(body).build(),
            ).execute().use { r ->
                if (!r.isSuccessful) Outcome.Failed("HTTP ${r.code}")
                else Outcome.Success(MealieJson.decodeFromString<MealieToken>(r.body.string()))
            }
        } catch (e: IOException) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /** Google מקבלת רק scheme מותאם שמכיל נקודה (לפי תיעוד Mealie). */
        const val SCHEME = "io.github.yosefchai.mealie"
        const val REDIRECT_URI = "$SCHEME:/oauth/callback"
    }
}

/**
 * מחבר בין הנפילה ל-Custom Tab (OAuthRedirectActivity) לבין המסך שהתחיל את ההתחברות.
 * Auth Tab מחזיר תוצאה ישירות, ולכן משתמש בזה רק הדפדפן שלא תומך בו.
 */
object OidcRedirects {
    private val _redirects = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val redirects: SharedFlow<Uri> = _redirects

    fun deliver(uri: Uri) {
        _redirects.tryEmit(uri)
    }

    val isOurRedirect: (Uri) -> Boolean = { it.scheme == NativeOidc.SCHEME && it.path?.startsWith("/oauth") == true }
}
