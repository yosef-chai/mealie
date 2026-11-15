package io.github.yosefchai.mealie.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * יחידות מידה מוכרות, כדי לקשר מצרך ליחידה שכבר קיימת בשרת גם כשהיא כתובה אחרת
 * ("גר'" ← גרם, "כוסות" ← כוס, "שיני" ← שן, "tbsp" ← tablespoon).
 *
 * Mealie מאפשר לשנות את מספר המנות רק במתכון שיש בו לפחות מצרך אחד עם יחידה או מצרך מקושר,
 * ולכן הקישור הזה הוא מה שמפעיל את הכפתור.
 */
object UnitCatalog {

    /**
     * יחידה: שם, רבים, וכל צורות הכתיבה שלה. fraction: תצוגה בשברים (½ כוס) ולא בעשרוני (0.5 ק"ג).
     * abbreviation: הקיצור המקובל ("g", "tbsp"); יחידה חדשה מוצגת בו, כמו שהוא כתוב ברוב המתכונים.
     */
    data class Concept(val name: String, val plural: String?, val spellings: Set<String>, val fraction: Boolean, val abbreviation: String? = null)

    private fun c(name: String, plural: String?, fraction: Boolean, vararg more: String) =
        Concept(name, plural, (listOfNotNull(name, plural) + more).map(::normalize).toSet(), fraction)

    private fun Concept.short(abbr: String) = copy(abbreviation = abbr)

    val concepts: List<Concept> = listOf(
        c("כוס", "כוסות", true),
        c("כף", "כפות", true),
        c("כפית", "כפיות", true),
        c("גרם", null, false, "גרמים", "גר'", "גר", "ג'"),
        c("ק\"ג", null, false, "קילו", "קילוגרם", "קילוגרמים", "קג"),
        c("מ\"ל", null, false, "מל", "מיליליטר", "מיליליטרים"),
        c("ליטר", "ליטרים", false),
        c("חבילה", "חבילות", true, "חבילת"),
        c("קופסה", "קופסאות", true, "קופסא", "קופסת", "קופסות"),
        c("שקית", "שקיות", true),
        c("פחית", "פחיות", true),
        c("גביע", "גביעים", true, "גביעי"),
        c("שן", "שיני", true, "שיניים"),
        c("צרור", "צרורות", true),
        c("חופן", "חופנים", true, "חופני"),
        c("קורט", null, true),
        c("קמצוץ", "קמצוצים", true),
        c("פרוסה", "פרוסות", true, "פרוסת"),
        c("יחידה", "יחידות", true),
        c("cup", "cups", true, "c"),
        c("tablespoon", "tablespoons", true, "tbsp", "tbs", "tbl").short("tbsp"),
        c("teaspoon", "teaspoons", true, "tsp").short("tsp"),
        c("gram", "grams", false, "g", "gr").short("g"),
        c("kilogram", "kilograms", false, "kg").short("kg"),
        c("milliliter", "milliliters", false, "ml", "millilitre", "millilitres").short("ml"),
        c("liter", "liters", false, "l", "litre", "litres").short("l"),
        c("ounce", "ounces", true, "oz").short("oz"),
        c("pound", "pounds", true, "lb", "lbs").short("lb"),
        c("pinch", "pinches", true),
        c("clove", "cloves", true),
        c("can", "cans", true),
        c("package", "packages", true, "pkg"),
        c("slice", "slices", true),
        c("stick", "sticks", true),
        c("bunch", "bunches", true),
    )

    private val bySpelling: Map<String, Concept> = concepts.flatMap { k -> k.spellings.map { it to k } }.toMap()

    /** צורת השוואה: בלי הבדלי גרש וגרשיים, אותיות גדולות ונקודה בסוף ("Tbsp." = "tbsp", "ק״ג" = "ק\"ג"). */
    fun normalize(s: String): String = s.trim()
        .replace('׳', '\'').replace('’', '\'').replace('`', '\'')
        .replace('״', '"').replace('“', '"').replace('”', '"')
        .trimEnd('.')
        .lowercase()

    fun conceptOf(token: String): Concept? = bySpelling[normalize(token)]

    /** כל צורות הכתיבה של יחידה מהשרת (שם, רבים, קיצורים, כינויים). */
    fun spellingsOf(unit: JsonObject): Set<String> {
        fun s(key: String) = (unit[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val aliases = (unit["aliases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { a -> (a["name"] as? JsonPrimitive)?.contentOrNull } }
        return (listOfNotNull(s("name"), s("pluralName"), s("abbreviation"), s("pluralAbbreviation")) + aliases)
            .map(::normalize).filter { it.isNotEmpty() }.toSet()
    }

    /**
     * מאתר יחידה מהשרת למילה שבתחילת המצרך: קודם כתיב זהה, ואחר כך אותה יחידה בכתיב אחר.
     * יחידה שהשרת לא מכיר מחזירה null.
     */
    class Matcher(units: List<JsonObject>) {
        private val exact = HashMap<String, JsonObject>()
        private val byConcept = HashMap<Concept, JsonObject>()

        init {
            units.forEach { u ->
                val spellings = spellingsOf(u)
                spellings.forEach { exact.putIfAbsent(it, u) }
                spellings.mapNotNull { bySpelling[it] }.distinct().forEach { byConcept.putIfAbsent(it, u) }
            }
        }

        fun find(token: String): JsonObject? {
            val n = normalize(token)
            if (n.isEmpty()) return null
            return exact[n] ?: bySpelling[n]?.let { byConcept[it] }
        }
    }
}
