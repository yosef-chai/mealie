package io.github.yosefchai.mealie.net

import android.content.Context
import android.os.Build
import io.github.yosefchai.mealie.auth.ApiToken
import io.github.yosefchai.mealie.auth.SessionSync
import io.github.yosefchai.mealie.auth.TokenStore
import io.github.yosefchai.mealie.core.AppGraph
import io.github.yosefchai.mealie.core.ServerConfig
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** תשובת שגיאה מהשרת; 401 פירושו שהטוקן לא בתוקף. */
class MealieHttpException(val code: Int, val body: String) : IOException("HTTP $code")

/** חיבור מוכן לשרת: כתובת שנבחרה (פנימית או חיצונית) וטוקן. */
data class MealieSession(val server: ServerConfig, val url: ServerUrl, val token: String, val isApiToken: Boolean)

/**
 * גישה לשרת מחוץ ל-WebView (ווידג'טים, ייבוא מתכון, עבודה ברקע).
 * הטוקן: טוקן API ארוך-טווח אם נוצר, אחרת עוגיית ההתחברות של ה-WebView.
 */
object ServerAccess {

    fun tokens(context: Context) = TokenStore(context.applicationContext)

    sealed interface Access {
        data class Ready(val session: MealieSession) : Access
        data object NoServer : Access
        data object Unreachable : Access
        data object SignedOut : Access
    }

    /** בוחר כתובת לשרת (פנימית קודם) ומצרף טוקן. null אם אין חיבור או שהמשתמש לא מחובר. */
    suspend fun open(context: Context, server: ServerConfig? = null): MealieSession? =
        (connect(context, server) as? Access.Ready)?.session

    suspend fun connect(context: Context, server: ServerConfig? = null): Access = withContext(Dispatchers.IO) {
        val target = server ?: AppGraph.settings.current().activeServer ?: return@withContext Access.NoServer
        val resolver = ServerResolver(probe = { url, timeout -> AppGraph.probe.probe(url, timeout) })
        val url = when (val r = resolver.resolve(target, AppGraph.network.current())) {
            is Resolution.Ready -> r.url
            is Resolution.Offline, is Resolution.Failed -> return@withContext Access.Unreachable
        }
        val api = tokens(context).get(target.id)
        if (api != null) return@withContext Access.Ready(MealieSession(target, url, api.token, isApiToken = true))
        val sessions = SessionSync()
        // CookieManager מאתחל את מנוע ה-WebView; ברקע זה תקין, רק איטי בפעם הראשונה
        val cookie = withContext(Dispatchers.Main) {
            (listOf(url) + target.all).firstNotNullOfOrNull { runCatching { sessions.tokenAt(it) }.getOrNull() }
        } ?: return@withContext Access.SignedOut
        Access.Ready(MealieSession(target, url, cookie, isApiToken = false))
    }

    /**
     * לווידג'טים צריך טוקן שלא פג אחרי 48 שעות: יוצרים טוקן API בשם המכשיר, פעם אחת, בזמן שהמשתמש מחובר.
     * מחזיר חיבור עם הטוקן החדש, או את החיבור המקורי אם היצירה נכשלה.
     */
    suspend fun ensureApiToken(context: Context, session: MealieSession): MealieSession {
        if (session.isApiToken) return session
        return runCatching {
            val name = "Mealie Android · ${Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.ROOT) }} ${Build.MODEL}"
            val res = MealieClient(session).post("/api/users/api-tokens", buildJsonObject { put("name", name) }).jsonObject
            val token = res["token"]?.jsonPrimitive?.contentOrNull ?: return session
            val id = res["id"]?.jsonPrimitive?.intOrNull
            tokens(context).put(session.server.id, ApiToken(token, id))
            session.copy(token = token, isApiToken = true)
        }.getOrDefault(session)
    }

    /** יציאה או הסרת שרת: מבטל את טוקן ה-API בשרת (אם אפשר) ומוחק אותו מהמכשיר. */
    suspend fun revoke(context: Context, server: ServerConfig) {
        val store = tokens(context)
        val api = store.get(server.id) ?: return
        runCatching {
            val session = open(context, server)
            if (session != null && api.id != null) MealieClient(session).delete("/api/users/api-tokens/${api.id}")
        }
        store.remove(server.id)
    }

    /** טוקן API שהשרת דחה (בוטל מהפרופיל): מוחקים כדי לא לנסות שוב. */
    suspend fun forget(context: Context, server: ServerConfig) = tokens(context).remove(server.id)
}

class MealieClient(val session: MealieSession, private val http: OkHttpClient = Http.client) {

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String): Request.Builder = Request.Builder()
        .url(if (path.startsWith("http")) path else session.url.resolve(path))
        .header("Authorization", "Bearer ${session.token}")
        .header("Accept", "application/json")
        .header("Accept-Language", Locale.getDefault().toLanguageTag())

    suspend fun get(path: String): JsonElement = call(request(path).get().build())

    suspend fun post(path: String, body: JsonElement): JsonElement =
        call(request(path).post(body.toString().toRequestBody(json)).build())

    suspend fun put(path: String, body: JsonElement): JsonElement =
        call(request(path).put(body.toString().toRequestBody(json)).build())

    suspend fun delete(path: String): JsonElement = call(request(path).delete().build())

    suspend fun putMultipart(path: String, body: MultipartBody): JsonElement = call(request(path).put(body).build())

    suspend fun postMultipart(path: String, body: MultipartBody): JsonElement = call(request(path).post(body).build())

    /** לבקשות SSE (ייבוא עם התקדמות): מחזיר את התשובה הפתוחה לקריאה. */
    suspend fun open(path: String, body: RequestBody): Response = withContext(Dispatchers.IO) {
        val client = http.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
        val res = client.newCall(request(path).header("Accept", "text/event-stream").post(body).build()).execute()
        if (!res.isSuccessful) {
            val text = res.body.string()
            res.close()
            throw MealieHttpException(res.code, text)
        }
        res
    }

    private suspend fun call(req: Request): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) throw MealieHttpException(res.code, text)
            if (text.isBlank()) JsonNull else runCatching { MealieJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
        }
    }

    companion object {
        fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
        fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
        fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
    }
}
