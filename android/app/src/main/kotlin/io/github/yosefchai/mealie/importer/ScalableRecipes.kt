package io.github.yosefchai.mealie.importer

import android.util.Log
import io.github.yosefchai.mealie.net.MealieClient
import io.github.yosefchai.mealie.net.MealieClient.Companion.arr
import io.github.yosefchai.mealie.net.MealieClient.Companion.str
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * מעדכן מתכונים בשרת כך ששינוי מספר המנות יעבוד:
 * - כמות בכל מצרך שנשמר כטקסט (IngredientQuantities), כדי שהיא תוכפל;
 * - קישור ליחידה (UnitCatalog), כי Mealie מפעיל את כפתור המנות רק במתכון שיש בו מצרך עם יחידה.
 *
 * יחידות שכבר קיימות בשרת משמשות כמו שהן. יחידה מוכרת שחסרה נוצרת פעם אחת; מילה שאינה יחידה מוכרת
 * נשארת בטקסט ולא נוצרת ממנה יחידה. העדכון חלקי (PATCH של recipeIngredient בלבד), כדי לא לדרוס שינויים אחרים.
 */
class ScalableRecipes(private val api: MealieClient) {

    data class Summary(val checked: Int, val updated: Int, val failed: Int)

    private var units: MutableList<JsonObject>? = null
    private var matcher = UnitCatalog.Matcher(emptyList())
    // בלי רשימת היחידות של השרת לא יוצרים יחידות, כדי לא ליצור כפולות
    private var unitsLoaded = false
    private val cannotCreate = HashSet<UnitCatalog.Concept>()

    /** מתכון אחד. מחזיר true אם משהו השתנה. */
    suspend fun fix(slug: String): Boolean {
        val path = "/api/recipes/${encode(slug)}"
        val recipe = api.get(path).jsonObject
        val ingredients = recipe["recipeIngredient"] as? JsonArray ?: return false
        ensureUnits(tokens(ingredients))
        val updated = IngredientQuantities.apply(ingredients) { matcher.find(it) } ?: return false
        api.patch(path, buildJsonObject { put("recipeIngredient", updated) })
        return true
    }

    /**
     * כל המתכונים שהמשתמש רואה. מתכון שנכשל (למשל של משק בית אחר, בלי הרשאת עריכה) מדלגים עליו.
     * onProgress(done, total) נקרא אחרי כל מתכון.
     */
    suspend fun fixAll(onProgress: (Int, Int) -> Unit): Summary {
        val slugs = pages("/api/recipes", "&orderBy=created_at&orderDirection=asc").mapNotNull { it.str("slug") }.distinct()
        var updated = 0
        var failed = 0
        slugs.forEachIndexed { i, slug ->
            try {
                if (fix(slug)) updated++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "Recipe $slug was not updated", e)
            }
            onProgress(i + 1, slugs.size)
        }
        return Summary(slugs.size, updated, failed)
    }

    /** המילים שאחרי הכמות במצרכים שיעודכנו: מועמדות להיות יחידה. */
    private fun tokens(ingredients: JsonArray): Set<String> = ingredients.mapNotNull { el ->
        val note = ((el as? JsonObject)?.get("note") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        IngredientQuantities.parse(note)?.note?.let(IngredientQuantities::unitToken)
    }.toSet()

    private suspend fun ensureUnits(tokens: Set<String>) {
        if (tokens.isEmpty()) return
        val list = units ?: runCatching { pages("/api/units", "").toMutableList() }
            .onSuccess { unitsLoaded = true }
            .onFailure { Log.w(TAG, "Units could not be read", it) }
            .getOrDefault(mutableListOf())
            .also { units = it; matcher = UnitCatalog.Matcher(it) }
        if (!unitsLoaded) return
        tokens.forEach { token ->
            if (matcher.find(token) != null) return@forEach
            val concept = UnitCatalog.conceptOf(token) ?: return@forEach
            if (concept in cannotCreate) return@forEach
            try {
                val created = api.post(
                    "/api/units",
                    buildJsonObject {
                        put("name", concept.name)
                        concept.plural?.let { put("pluralName", it) }
                        put("description", "")
                        put("fraction", concept.fraction)
                        concept.abbreviation?.let { put("abbreviation", it) }
                        put("useAbbreviation", concept.abbreviation != null)
                    },
                ).jsonObject
                list.add(created)
                matcher = UnitCatalog.Matcher(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // למשל משתמש בלי הרשאה לנהל נתונים: המצרך נשאר עם כמות בלבד
                cannotCreate += concept
                Log.w(TAG, "Unit ${concept.name} could not be created", e)
            }
        }
    }

    private suspend fun pages(path: String, query: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var page = 1
        while (true) {
            val res = api.get("$path?page=$page&perPage=100$query").jsonObject
            val items = res.arr("items").orEmpty().mapNotNull { it as? JsonObject }
            out += items
            // השרת מחזיר את שדות העימוד ב-snake_case
            val total = (res["total_pages"] ?: res["totalPages"]).let { (it as? JsonPrimitive)?.intOrNull } ?: page
            if (page >= total || items.isEmpty()) break
            page++
        }
        return out
    }

    companion object {
        private const val TAG = "ScalableRecipes"

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
