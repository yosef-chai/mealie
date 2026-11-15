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
    val enableOidc: Boolean = false,
    val oidcProviderName: String = "",
    val tokenTime: Int = 48,
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
