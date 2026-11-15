package io.github.yosefchai.mealie.update

import io.github.yosefchai.mealie.BuildConfig
import io.github.yosefchai.mealie.core.VersionCode
import io.github.yosefchai.mealie.net.Http
import io.github.yosefchai.mealie.net.MealieJson
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val checksumsUrl: String,
    val pageUrl: String,
    val notes: String? = null,
)

sealed interface CheckResult {
    data class Available(val info: UpdateInfo) : CheckResult

    data object UpToDate : CheckResult

    data class Error(val error: Throwable) : CheckResult
}

/**
 * בודק את הגרסה האחרונה בפורק.
 * ראשית: הפניית github.com/<repo>/releases/latest (לא נספרת במכסת ה-API, 60 בקשות לשעה ל-IP).
 * גיבוי: ה-REST API. פורק בלי שחרורים מפנה ל-/releases בלי /tag/, וזה "אין עדכון", לא שגיאה.
 */
class UpdateChecker(
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val currentCode: Int = BuildConfig.VERSION_CODE,
    client: OkHttpClient = Http.publicClient,
) {
    private val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val http = client

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        if (!BuildConfig.SELF_UPDATE) return@withContext CheckResult.UpToDate
        val tag = try {
            latestTagFromRedirect() ?: latestTagFromApi()
        } catch (e: IOException) {
            runCatching { latestTagFromApi() }.getOrElse { return@withContext CheckResult.Error(e) }
        }
        val parsed = tag?.let(VersionCode::fromTag) ?: return@withContext CheckResult.UpToDate
        if (parsed.code <= currentCode) return@withContext CheckResult.UpToDate
        CheckResult.Available(
            UpdateInfo(
                tag = tag,
                versionName = if (parsed.appPatch == 0) parsed.mealieVersion else "${parsed.mealieVersion}.${parsed.appPatch}",
                versionCode = parsed.code,
                apkUrl = "https://github.com/$repo/releases/download/$tag/$APK_NAME",
                checksumsUrl = "https://github.com/$repo/releases/download/$tag/$CHECKSUMS_NAME",
                pageUrl = "https://github.com/$repo/releases/tag/$tag",
                notes = runCatching { notes(tag) }.getOrNull(),
            ),
        )
    }

    /** null = אין שחרור (או שהתשובה לא בפורמט הצפוי). */
    private fun latestTagFromRedirect(): String? {
        val req = Request.Builder().url("https://github.com/$repo/releases/latest").head().build()
        noRedirect.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            val location = r.header("Location") ?: return null
            if ("/releases/tag/" !in location) return null
            return location.substringAfter("/releases/tag/").substringBefore('?').substringBefore('#')
                .let { java.net.URLDecoder.decode(it, "UTF-8") }
        }
    }

    private fun latestTagFromApi(): String? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("GitHub API ${r.code}")
            return MealieJson.decodeFromString<Release>(r.body.string()).tagName
        }
    }

    /** רשימת השינויים (best-effort; לא חוסם עדכון אם המכסה נגמרה). */
    private fun notes(tag: String): String? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/tags/$tag")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return MealieJson.decodeFromString<Release>(r.body.string()).body
        }
    }

    @Serializable
    private data class Release(@SerialName("tag_name") val tagName: String, val body: String? = null)

    companion object {
        const val APK_NAME = "mealie-android.apk"
        const val CHECKSUMS_NAME = "checksums.txt"
    }
}
