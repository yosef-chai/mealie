package io.github.yosefchai.mealie.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val MealieJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** GET /api/app/about (שמות camelCase, לפי MealieOutModel). */
@Serializable
data class AboutInfo(
    val version: String = "",
    val production: Boolean = true,
    val allowSignup: Boolean = false,
    val allowPasswordLogin: Boolean = true,
    val defaultGroupSlug: String? = null,
    val enableOidc: Boolean = false,
    val oidcRedirect: Boolean = false,
    val oidcProviderName: String = "",
    val tokenTime: Int = 48,
    val brandingName: String? = null,
)

/** GET /api/app/about/theme. */
@Serializable
data class AboutTheme(
    val lightPrimary: String? = null,
    val darkPrimary: String? = null,
)

/** GET /api/auth/oauth/native/config (snake_case, BaseModel רגיל). */
@Serializable
data class NativeOidcConfig(
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("client_id") val clientId: String,
    val scope: String = "openid email profile",
)

@Serializable
data class NativeTokenRequest(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("redirect_uri") val redirectUri: String,
    val nonce: String? = null,
)

/** MealieAuthToken. */
@Serializable
data class MealieToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

/** GET /api/users/self – רק השדות שהאפליקציה צריכה. */
@Serializable
data class SelfUser(
    val groupSlug: String? = null,
    val fullName: String? = null,
)
