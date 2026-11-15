package io.github.yosefchai.mealie.importer

import android.content.Context
import io.github.yosefchai.mealie.core.AppGraph
import io.github.yosefchai.mealie.net.Http
import io.github.yosefchai.mealie.net.MealieClient
import io.github.yosefchai.mealie.net.MealieClient.Companion.arr
import io.github.yosefchai.mealie.net.MealieClient.Companion.obj
import io.github.yosefchai.mealie.net.MealieClient.Companion.str
import io.github.yosefchai.mealie.net.MealieHttpException
import io.github.yosefchai.mealie.net.ServerAccess
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** מתכון שנוצר בשרת, לתצוגה בסיום. */
data class ImportedRecipe(
    val slug: String,
    /** נתיב במסך של Mealie: /g/{group}/r/{slug} */
    val path: String,
    val name: String,
    val imageUrl: String?,
    val ingredients: Int,
    val steps: Int,
    /** חלק מהנתונים חסרים (למשל אין מצרכים); כדאי לעבור על המתכון. */
    val partial: Boolean,
)

sealed interface ImportState {
    data object Connecting : ImportState

    data class Reading(val host: String) : ImportState

    data object Saving : ImportState

    data object Finishing : ImportState

    data class Exists(val path: String, val name: String, val url: String) : ImportState

    data class Done(val recipe: ImportedRecipe) : ImportState

    /** אין שרת מוגדר, או שהמשתמש לא מחובר: הייבוא ממשיך בתוך האפליקציה. */
    data object NeedsApp : ImportState

    /** לא נמצא מתכון בדף. blocked: האתר הציג דף אימות. */
    data class Failed(val blocked: Boolean, val message: String?) : ImportState
}

/**
 * ייבוא מתכון מקישור:
 * 1. קריאת הדף במכשיר (PageExtractor) ושליחת schema.org Recipe לשרת (create/html-or-json).
 * 2. אם במכשיר לא נמצא מתכון: השרת קורא את הקישור בעצמו (create/url), כולל AI אם הוגדר בשרת.
 * 3. אם גם זה נכשל ובשרת מופעל AI: שליחת טקסט הדף ל-create/ai.
 * 4. אחרי היצירה: כותרות מקטעים ("לבצק", "למילוי"), ותמונה שהמכשיר מוריד אם השרת לא הצליח.
 */
class RecipeImporter(private val context: Context) {

    fun run(url: String, force: Boolean = false): Flow<ImportState> = flow {
        emit(ImportState.Connecting)
        if (AppGraph.settings.current().servers.isEmpty()) { emit(ImportState.NeedsApp); return@flow }
        val opened = ServerAccess.open(context) ?: run { emit(ImportState.NeedsApp); return@flow }
        val session = ServerAccess.ensureApiToken(context, opened)
        val api = MealieClient(session)

        if (!force) {
            existing(api, url)?.let { (slug, name) -> emit(ImportState.Exists(recipePath(api, slug), name, url)); return@flow }
        }

        emit(ImportState.Reading(hostOf(url)))
        val page = PageExtractor(context).extract(url)
        val settings = AppGraph.settings.current()

        emit(ImportState.Saving)
        var slug: String? = null
        var sections: JsonObject? = null
        var deviceImage: String? = null
        var userAgent: String? = null
        var failure: String? = null

        if (page is PageResult.Found) {
            val recipe = page.data["recipe"] as? JsonObject
            sections = page.data["sections"] as? JsonObject
            deviceImage = (page.data["image"] as? JsonPrimitive)?.contentOrNull
            userAgent = page.userAgent
            if (recipe != null) {
                // בלי "url": אחרת השרת בוחר מפענח ייעודי לאתר, שמחפש HTML מלא ומפספס שדות.
                // הכתובת נשמרת כ-orgURL מהשדה url שבתוך המתכון.
                slug = runCatching {
                    api.post(
                        "/api/recipes/create/html-or-json",
                        buildJsonObject {
                            put("data", recipe.toString())
                            put("includeTags", settings.importTags)
                            put("includeCategories", settings.importCategories)
                        },
                    ).asSlug()
                }.onFailure { failure = errorMessage(it) }.getOrNull()
            }
        }
        if (slug == null) {
            slug = runCatching {
                api.post(
                    "/api/recipes/create/url",
                    buildJsonObject {
                        put("url", url)
                        put("includeTags", settings.importTags)
                        put("includeCategories", settings.importCategories)
                    },
                ).asSlug()
            }.onFailure { failure = errorMessage(it) }.getOrNull()
        }
        if (slug == null && page is PageResult.NoRecipe && !page.text.isNullOrBlank() && aiEnabled(api)) {
            slug = runCatching {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("content", page.text.take(30_000))
                    .addFormDataPart("url", url)
                    .build()
                api.postMultipart("/api/recipes/create/ai", body).asSlug()
            }.onFailure { failure = errorMessage(it) }.getOrNull()
        }
        if (slug == null) {
            emit(ImportState.Failed(blocked = page is PageResult.Blocked, message = failure))
            return@flow
        }

        emit(ImportState.Finishing)
        val recipe = finish(api, slug, sections, deviceImage, userAgent, url)
        emit(ImportState.Done(recipe))
    }

    /** מתכון שכבר יובא מאותה כתובת. */
    private suspend fun existing(api: MealieClient, url: String): Pair<String, String>? = runCatching {
        val filter = URLEncoder.encode("orgURL = \"${url.replace("\"", "")}\"", "UTF-8")
        val res = api.get("/api/recipes?perPage=1&queryFilter=$filter")
        val item = (res.arr("items")?.firstOrNull() as? JsonObject) ?: return@runCatching null
        val slug = item.str("slug") ?: return@runCatching null
        slug to (item.str("name") ?: slug)
    }.getOrNull()

    private var groupSlug: String? = null

    /** קישור פנימי למתכון; ה-groupSlug נשמר כשהמשתמש גולש, ואחרת נלקח מהשרת. */
    private suspend fun recipePath(api: MealieClient, slug: String): String {
        val group = groupSlug ?: api.session.server.groupSlug
            ?: runCatching { api.get("/api/users/self").str("groupSlug") }.getOrNull()
        groupSlug = group
        return if (group != null) "/g/${encodePath(group)}/r/${encodePath(slug)}" else "/"
    }

    private suspend fun aiEnabled(api: MealieClient): Boolean = runCatching {
        val ai = api.get("/api/groups/self").obj("aiProviderSettings")
        (ai?.get("aiEnabled") as? JsonPrimitive)?.booleanOrNull == true
    }.getOrDefault(false)

    private suspend fun finish(
        api: MealieClient,
        slug: String,
        sections: JsonObject?,
        deviceImage: String?,
        userAgent: String?,
        pageUrl: String,
    ): ImportedRecipe {
        val path = "/api/recipes/${encodePath(slug)}"
        var recipe = runCatching { api.get(path).jsonObject }.getOrNull()
            ?: return ImportedRecipe(slug, recipePath(api, slug), slug, null, 0, 0, partial = true)

        // כותרות מקטעים: רק כשמספר הפריטים בשרת זהה למה שנשלח, כדי שהמיקומים יתאימו
        val withTitles = sections?.let { applySections(recipe, it) }
        if (withTitles != null) {
            runCatching { api.put(path, withTitles) }.onSuccess { recipe = withTitles }
        }

        // כמות ויחידה בכל מצרך, כדי ששינוי מספר המנות יעבוד (השרת שומר מצרכים מיובאים כטקסט בלבד)
        runCatching { ScalableRecipes(api).fix(slug) }
            .onSuccess { changed -> if (changed) runCatching { api.get(path).jsonObject }.getOrNull()?.let { recipe = it } }
            .onFailure { android.util.Log.w("RecipeImporter", "Ingredient amounts were not saved", it) }

        // השרת לא תמיד מצליח להוריד את התמונה (חסימת שרתים, hotlink). המכשיר מוריד ומעלה.
        if (recipe.str("image").isNullOrBlank() && !deviceImage.isNullOrBlank()) {
            uploadImage(api, slug, deviceImage, userAgent, pageUrl)
            recipe = runCatching { api.get(path).jsonObject }.getOrDefault(recipe)
        }

        val id = recipe.str("id")
        val image = recipe.str("image")?.takeIf { it.isNotBlank() }
        val imageUrl = if (id != null && image != null) api.session.url.resolve("/api/media/recipes/$id/images/min-original.webp?version=$image") else null
        val ingredients = (recipe["recipeIngredient"] as? JsonArray)?.size ?: 0
        val steps = (recipe["recipeInstructions"] as? JsonArray)?.size ?: 0
        return ImportedRecipe(
            slug = slug,
            path = recipePath(api, slug),
            name = recipe.str("name") ?: slug,
            imageUrl = imageUrl,
            ingredients = ingredients,
            steps = steps,
            partial = ingredients == 0 || steps == 0,
        )
    }

    private fun applySections(recipe: JsonObject, sections: JsonObject): JsonObject? {
        val ing = recipe["recipeIngredient"] as? JsonArray ?: return null
        val steps = recipe["recipeInstructions"] as? JsonArray ?: return null
        val ingSecs = sections["ingredients"] as? JsonArray ?: JsonArray(emptyList())
        val stepSecs = sections["instructions"] as? JsonArray ?: JsonArray(emptyList())
        if (ingSecs.isEmpty() && stepSecs.isEmpty()) return null
        fun titled(list: JsonArray, secs: JsonArray): JsonArray? {
            if (secs.isEmpty()) return list
            val titles = secs.mapNotNull { s ->
                val o = s as? JsonObject ?: return@mapNotNull null
                val i = (o["index"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val t = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                i to t
            }.toMap()
            if (titles.keys.any { it !in list.indices }) return null
            return JsonArray(list.mapIndexed { i, el ->
                val t = titles[i] ?: return@mapIndexed el
                JsonObject((el as JsonObject) + ("title" to JsonPrimitive(t)))
            })
        }
        val newIng = titled(ing, ingSecs) ?: return null
        val newSteps = titled(steps, stepSecs) ?: return null
        return JsonObject(recipe + mapOf("recipeIngredient" to newIng, "recipeInstructions" to newSteps))
    }

    private suspend fun uploadImage(api: MealieClient, slug: String, imageUrl: String, userAgent: String?, referer: String) {
        runCatching {
            val bytes = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(imageUrl)
                    .header("Referer", referer)
                    .apply { userAgent?.let { header("User-Agent", it) } }
                    .build()
                Http.publicClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@withContext null
                    val type = res.body.contentType()?.toString().orEmpty()
                    if (!type.startsWith("image/")) return@withContext null
                    res.body.bytes().takeIf { it.size in 1..(15 * 1024 * 1024) }?.let { it to type }
                }
            } ?: return
            val (data, type) = bytes
            val ext = when {
                "png" in type -> "png"
                "webp" in type -> "webp"
                "gif" in type -> "gif"
                else -> "jpg"
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("image", "image.$ext", data.toRequestBody(type.toMediaTypeOrNull()))
                .addFormDataPart("extension", ext)
                .build()
            api.putMultipart("/api/recipes/${encodePath(slug)}/image", body)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.asSlug(): String =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("no slug")

    private fun errorMessage(t: Throwable): String? = (t as? MealieHttpException)?.let {
        runCatching {
            val detail = io.github.yosefchai.mealie.net.MealieJson.parseToJsonElement(it.body).obj("detail")
            detail.str("message")
        }.getOrNull()
    }

    companion object {
        fun hostOf(url: String): String = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrNull() ?: url

        private fun encodePath(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
