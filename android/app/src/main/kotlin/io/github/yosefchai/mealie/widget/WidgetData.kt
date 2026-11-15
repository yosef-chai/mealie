package io.github.yosefchai.mealie.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.yosefchai.mealie.core.AppGraph
import io.github.yosefchai.mealie.net.Images
import io.github.yosefchai.mealie.net.MealieClient
import io.github.yosefchai.mealie.net.MealieClient.Companion.arr
import io.github.yosefchai.mealie.net.MealieClient.Companion.obj
import io.github.yosefchai.mealie.net.MealieClient.Companion.str
import io.github.yosefchai.mealie.net.MealieHttpException
import io.github.yosefchai.mealie.net.MealieJson
import io.github.yosefchai.mealie.net.MealieSession
import io.github.yosefchai.mealie.net.ServerAccess
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** למה הווידג'ט לא מציג נתונים עדכניים. */
@Serializable
enum class WidgetProblem { NO_SERVER, SIGNED_OUT, OFFLINE, NO_LISTS, SYNC_FAILED, ERROR }

@Serializable
data class ShoppingItem(val id: String, val text: String, val checked: Boolean, val position: Int = 0, val updatedAt: String? = null)

@Serializable
data class ShoppingSnapshot(
    val listId: String? = null,
    val listName: String? = null,
    val items: List<ShoppingItem> = emptyList(),
    val updatedAt: Long = 0,
    val problem: WidgetProblem? = null,
    /** הניסיון האחרון לרענן, גם אם נכשל; מונע ניסיונות חוזרים ברצף. */
    val checkedAt: Long = 0,
) {
    val unchecked: List<ShoppingItem> get() = items.filter { !it.checked }.sortedBy { it.position }
    val checked: List<ShoppingItem> get() = items.filter { it.checked }.sortedByDescending { it.updatedAt }
}

@Serializable
data class PlannedMeal(
    val type: String,
    val title: String,
    val note: String? = null,
    val recipePath: String? = null,
    val recipeId: String? = null,
)

@Serializable
data class MealsSnapshot(
    val date: String? = null,
    val meals: List<PlannedMeal> = emptyList(),
    /** קובץ התמונה של המנה הראשית, בתיקיית ה-cache. */
    val heroImage: String? = null,
    val updatedAt: Long = 0,
    val problem: WidgetProblem? = null,
    val checkedAt: Long = 0,
)

/** קריאה וכתיבה של נתוני הווידג'טים מול Mealie, ושמירה במצב (state) של כל ווידג'ט. */
object WidgetData {
    val SHOPPING = stringPreferencesKey("shopping")
    val MEALS = stringPreferencesKey("meals")

    /** רשימה שהמשתמש בחר בהגדרת הווידג'ט; בלי בחירה, הרשימה הראשונה. */
    val LIST_ID = stringPreferencesKey("list_id")

    /** סדר הצגת הארוחות, כמו בלוח התכנון של Mealie. */
    val MEAL_ORDER = listOf("breakfast", "lunch", "dinner", "side", "snack", "drink", "dessert")

    suspend fun shoppingState(context: Context, id: GlanceId): Preferences =
        getAppWidgetState(context, PreferencesGlanceStateDefinition, id)

    fun decodeShopping(prefs: Preferences): ShoppingSnapshot? =
        prefs[SHOPPING]?.let { runCatching { MealieJson.decodeFromString<ShoppingSnapshot>(it) }.getOrNull() }

    fun decodeMeals(prefs: Preferences): MealsSnapshot? =
        prefs[MEALS]?.let { runCatching { MealieJson.decodeFromString<MealsSnapshot>(it) }.getOrNull() }

    /** חיבור לשרת עם טוקן API (נוצר פעם אחת). null + הסבר אם אי אפשר. */
    private suspend fun session(context: Context): Pair<MealieSession?, WidgetProblem?> {
        if (!AppGraph.network.current().connected) return null to WidgetProblem.OFFLINE
        return when (val access = runCatching { ServerAccess.connect(context) }.getOrElse { ServerAccess.Access.Unreachable }) {
            is ServerAccess.Access.Ready -> ServerAccess.ensureApiToken(context, access.session) to null
            ServerAccess.Access.NoServer -> null to WidgetProblem.NO_SERVER
            ServerAccess.Access.SignedOut -> null to WidgetProblem.SIGNED_OUT
            ServerAccess.Access.Unreachable -> null to WidgetProblem.OFFLINE
        }
    }

    private suspend fun problemOf(context: Context, session: MealieSession, t: Throwable): WidgetProblem = when {
        t is MealieHttpException && t.code == 401 -> {
            // הטוקן בוטל (למשל נמחק מהפרופיל): שוכחים אותו, והווידג'ט מבקש להתחבר מחדש
            if (session.isApiToken) ServerAccess.forget(context, session.server)
            WidgetProblem.SIGNED_OUT
        }
        t is IOException && t !is MealieHttpException -> WidgetProblem.OFFLINE
        else -> WidgetProblem.ERROR
    }

    // ---- רשימת קניות ----

    suspend fun loadShopping(context: Context, previous: ShoppingSnapshot?, listId: String?): ShoppingSnapshot {
        val (session, problem) = session(context)
        if (session == null) return (previous ?: ShoppingSnapshot()).copy(problem = problem, checkedAt = System.currentTimeMillis())
        val api = MealieClient(session)
        return try {
            val id = listId ?: previous?.listId ?: firstListId(api) ?: return ShoppingSnapshot(problem = WidgetProblem.NO_LISTS, checkedAt = System.currentTimeMillis())
            val list = try {
                api.get("/api/households/shopping/lists/$id")
            } catch (e: MealieHttpException) {
                // הרשימה נמחקה: עוברים לראשונה שקיימת
                if (e.code != 404) throw e
                val fallback = firstListId(api) ?: return ShoppingSnapshot(problem = WidgetProblem.NO_LISTS, checkedAt = System.currentTimeMillis())
                api.get("/api/households/shopping/lists/$fallback")
            }
            val items = (list.arr("listItems") ?: JsonArray(emptyList())).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ShoppingItem(
                    id = o.str("id") ?: return@mapNotNull null,
                    text = itemText(o),
                    checked = (o["checked"] as? JsonPrimitive)?.booleanOrNull == true,
                    position = (o["position"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
                    updatedAt = o.str("updatedAt") ?: o.str("updateAt"),
                )
            }
            val now = System.currentTimeMillis()
            ShoppingSnapshot(list.str("id") ?: id, list.str("name")?.withTextDirection(), items, now, null, now)
        } catch (t: Throwable) {
            (previous ?: ShoppingSnapshot()).copy(problem = problemOf(context, session, t), checkedAt = System.currentTimeMillis())
        }
    }

    suspend fun shoppingLists(context: Context): List<Pair<String, String>>? {
        val (session, _) = session(context)
        session ?: return null
        return runCatching {
            MealieClient(session).get("/api/households/shopping/lists?perPage=-1&orderBy=name&orderDirection=asc")
                .arr("items")?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    (o.str("id") ?: return@mapNotNull null) to (o.str("name") ?: "")
                }.orEmpty()
        }.getOrNull()
    }

    private suspend fun firstListId(api: MealieClient): String? =
        (api.get("/api/households/shopping/lists?perPage=1&orderBy=createdAt&orderDirection=asc").arr("items")?.firstOrNull() as? JsonObject)?.str("id")

    /** הטקסט שהממשק של Mealie מציג: display (כמות, יחידה, מוצר, הערה), עם נפילה לשדות עצמם. */
    private fun itemText(o: JsonObject): String {
        o.str("display")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.withTextDirection() }
        val food = o.obj("food").str("name")
        val note = o.str("note")
        return listOfNotNull(food, note).joinToString(" ").ifBlank { "—" }.withTextDirection()
    }

    /**
     * סימון מוצר. השרת מקבל את הפריט המלא (PUT /items), ולכן קוראים אותו קודם ומשנים רק את checked.
     * מחזיר false אם השמירה נכשלה.
     */
    suspend fun setChecked(context: Context, itemId: String, checked: Boolean): Boolean {
        val (session, _) = session(context)
        session ?: return false
        val api = MealieClient(session)
        return runCatching {
            val item = api.get("/api/households/shopping/items/$itemId") as? JsonObject ?: return false
            val updated = JsonObject(item + ("checked" to JsonPrimitive(checked)))
            api.put("/api/households/shopping/items", JsonArray(listOf(updated)))
            true
        }.getOrElse { t ->
            if (t is MealieHttpException && t.code == 404) true else false // הפריט נמחק בינתיים: אין מה לשמור
        }
    }

    // ---- ארוחות היום ----

    suspend fun loadMeals(context: Context, previous: MealsSnapshot?): MealsSnapshot {
        val (session, problem) = session(context)
        if (session == null) return (previous ?: MealsSnapshot()).copy(problem = problem, checkedAt = System.currentTimeMillis())
        val api = MealieClient(session)
        return try {
            val group = session.server.groupSlug ?: runCatching { api.get("/api/users/self").str("groupSlug") }.getOrNull()
            val entries = (api.get("/api/households/mealplans/today") as? JsonArray).orEmpty()
            val meals = entries.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val recipe = o.obj("recipe")
                val slug = recipe.str("slug")
                PlannedMeal(
                    type = o.str("entryType") ?: "dinner",
                    title = (recipe.str("name") ?: o.str("title")?.takeIf { it.isNotBlank() } ?: o.str("text") ?: "—").withTextDirection(),
                    note = o.str("text")?.takeIf { it.isNotBlank() && recipe == null },
                    recipePath = if (slug != null && group != null) "/g/${enc(group)}/r/${enc(slug)}" else null,
                    recipeId = recipe.str("id"),
                )
            }.sortedBy { MEAL_ORDER.indexOf(it.type).let { i -> if (i < 0) 99 else i } }
            val hero = meals.firstOrNull { it.type == "dinner" && it.recipeId != null } ?: meals.firstOrNull { it.recipeId != null }
            val heroFile = hero?.recipeId?.let { cacheHero(context, session, it) }
            val now = System.currentTimeMillis()
            MealsSnapshot(java.time.LocalDate.now().toString(), meals, heroFile, now, null, now)
        } catch (t: Throwable) {
            (previous ?: MealsSnapshot()).copy(problem = problemOf(context, session, t), checkedAt = System.currentTimeMillis())
        }
    }

    /** תמונת המנה, מוקטנת לגודל שמתאים ל-RemoteViews (יש מגבלת זיכרון לתמונות בווידג'ט). */
    private suspend fun cacheHero(context: Context, session: MealieSession, recipeId: String): String? {
        val dir = File(context.cacheDir, "widget").apply { mkdirs() }
        val file = File(dir, "meal_$recipeId.webp")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 12 * 3600_000L) return file.absolutePath
        val bmp = Images.load(session.url.resolve("/api/media/recipes/$recipeId/images/min-original.webp"), session.token, 640) ?: return null
        dir.listFiles()?.filter { it.name.startsWith("meal_") && it != file }?.forEach { it.delete() }
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        file.outputStream().use { bmp.compress(format, 85, it) }
        bmp.recycle()
        return file.absolutePath
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---- עדכון כל הווידג'טים ----

    suspend fun refreshAll(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(ShoppingListWidget::class.java).forEach { id -> refreshShopping(context, id) }
        manager.getGlanceIds(MealPlanWidget::class.java).forEach { id -> refreshMeals(context, id) }
    }

    suspend fun refreshShopping(context: Context, id: GlanceId) {
        val prefs = shoppingState(context, id)
        val next = loadShopping(context, decodeShopping(prefs), prefs[LIST_ID])
        updateAppWidgetState(context, id) { it[SHOPPING] = MealieJson.encodeToString(ShoppingSnapshot.serializer(), next) }
        ShoppingListWidget().update(context, id)
    }

    suspend fun refreshMeals(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val next = loadMeals(context, decodeMeals(prefs))
        updateAppWidgetState(context, id) { it[MEALS] = MealieJson.encodeToString(MealsSnapshot.serializer(), next) }
        MealPlanWidget().update(context, id)
    }

    suspend fun hasWidgets(context: Context): Boolean {
        val m = GlanceAppWidgetManager(context)
        return m.getGlanceIds(ShoppingListWidget::class.java).isNotEmpty() || m.getGlanceIds(MealPlanWidget::class.java).isNotEmpty()
    }
}

/**
 * כיוון השורה לפי האות הראשונה (כלל first-strong של Unicode), בלי להתחשב במספרים שבהתחלה.
 * RemoteViews מסדר שורה לפי כיוון הממשק, ולכן "1 חלב 3%" בממשק באנגלית יוצא הפוך בלי הסימון.
 */
internal fun String.withTextDirection(): String {
    for (c in this) {
        when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return "\u200F$this"
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return "\u200E$this"
        }
    }
    return this
}
