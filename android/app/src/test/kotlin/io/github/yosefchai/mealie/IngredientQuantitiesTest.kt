package io.github.yosefchai.mealie

import io.github.yosefchai.mealie.importer.IngredientQuantities
import io.github.yosefchai.mealie.importer.IngredientQuantities.Parsed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngredientQuantitiesTest {

    private fun p(text: String) = IngredientQuantities.parse(text)
    private fun assertParsed(text: String, quantity: Double, note: String) {
        val r = p(text) ?: throw AssertionError("no quantity in: $text")
        assertEquals("quantity of: $text", quantity, r.quantity, 0.0001)
        assertEquals("note of: $text", note, r.note)
    }

    @Test
    fun `lines from a real Hebrew recipe page`() {
        assertParsed("500 גר' קמח לחם (אפשרי גם קמח רב תכליתי)", 500.0, "גר' קמח לחם (אפשרי גם קמח רב תכליתי)")
        assertParsed("50 מ\"ל ריצ' או שמנת מתוקה", 50.0, "מ\"ל ריצ' או שמנת מתוקה")
        assertParsed("2 ביצים גודל M", 2.0, "ביצים גודל M")
        assertParsed("1 כף ברנדי / כל סוג אלכוהול 40%", 1.0, "כף ברנדי / כל סוג אלכוהול 40%")
        assertNull(p("ריבה למילוי"))
    }

    @Test
    fun `fractions decimals and unicode`() {
        assertParsed("1/2 כוס סוכר", 0.5, "כוס סוכר")
        assertParsed("1 1/2 כוסות מים", 1.5, "כוסות מים")
        assertParsed("1½ cups milk", 1.5, "cups milk")
        assertParsed("1 ½ כוס", 1.5, "כוס")
        assertParsed("¾ כוס שמן", 0.75, "כוס שמן")
        assertParsed("1.5 ק\"ג עוף", 1.5, "ק\"ג עוף")
        assertParsed("1,5 kg Mehl", 1.5, "kg Mehl")
        assertParsed("1,000 g flour", 1000.0, "g flour")
        assertParsed("500גר' חמאה", 500.0, "גר' חמאה")
        assertParsed("2 x 400 גרם עגבניות מרוסקות", 2.0, "x 400 גרם עגבניות מרוסקות")
        assertParsed("1 קופסת שימורים (400 גרם) חומוס", 1.0, "קופסת שימורים (400 גרם) חומוס")
    }

    @Test
    fun `Hebrew and English number words`() {
        assertParsed("חצי כוס שמן", 0.5, "כוס שמן")
        assertParsed("רבע כפית פלפל", 0.25, "כפית פלפל")
        assertParsed("שלושת רבעי כוס סוכר", 0.75, "כוס סוכר")
        assertParsed("שתי ביצים", 2.0, "ביצים")
        assertParsed("שלוש שיני שום", 3.0, "שיני שום")
        assertParsed("two eggs", 2.0, "eggs")
        assertNull(p("שתיים עד שלוש ביצים"))
    }

    @Test
    fun `and a half after the unit`() {
        assertParsed("כוס וחצי קמח", 1.5, "כוס קמח")
        assertParsed("2 כוסות וחצי קמח", 2.5, "כוסות קמח")
        assertParsed("2 וחצי כוסות מים", 2.5, "כוסות מים")
        assertParsed("1 כף ורבע סוכר", 1.25, "כף סוכר")
    }

    @Test
    fun `approximate amounts keep the word`() {
        assertParsed("כ-200 גרם גבינה", 200.0, "גרם גבינה (בערך)")
        assertParsed("כ200 גרם גבינה", 200.0, "גרם גבינה (בערך)")
        assertParsed("about 2 cups stock", 2.0, "cups stock (approx.)")
        // "כוס" מתחילה ב-כ, אבל אין אחריה מספר
        assertNull(p("כוס"))
    }

    @Test
    fun `amount after the name is moved to the front`() {
        assertParsed("קמח – 500 גרם", 500.0, "גרם קמח")
        assertParsed("ביצים: 2", 2.0, "ביצים")
        assertParsed("סוכר - 1 כוס", 1.0, "כוס סוכר")
        assertNull(p("מלח - לפי הטעם"))
        assertNull(p("לבצק: 2 כוסות קמח"))
    }

    @Test
    fun `lines without one clear amount stay as they are`() {
        assertNull(p("1-2 כפות דבש"))
        assertNull(p("1 – 2 כפות דבש"))
        assertNull(p("2 עד 3 כפות"))
        assertNull(p("3/4-1 כוס חלב"))
        assertNull(p("1 to 2 tbsp honey"))
        assertNull(p("2 ביצים + 1 חלמון"))
        assertNull(p("1 כוס ועוד 2 כפות קמח"))
        assertNull(p("24 ס\"מ תבנית"))
        assertNull(p("180 מעלות"))
        assertNull(p("100% שוקולד מריר"))
        assertNull(p("1. קמח"))
        assertNull(p("1) קמח"))
        assertNull(p("200"))
        assertNull(p("2 - 3"))
        assertNull(p(""))
        assertNull(p("מלח ופלפל"))
    }

    @Test
    fun `a unit without a number is one`() {
        assertParsed("כוס שעועית לבנה", 1.0, "כוס שעועית לבנה")
        assertParsed("כפית תמצית וניל", 1.0, "כפית תמצית וניל")
        assertParsed("כוס (240 מ\"ל) מים חמימים", 1.0, "כוס (240 מ\"ל) מים חמימים")
        assertParsed("כף ושליש (14 ג') שמרים יבשים", 4 / 3.0, "כף (14 ג') שמרים יבשים")
        // שני מצרכים בשורה אחת
        assertNull(p("כפית פפריקה מתוקה, כפית גדושה מלח, פלפל שחור לפי הטעם"))
        // "כוסברה" מתחילה ב"כוס" אבל היא לא יחידה
        assertNull(p("כוסברה קצוצה"))
    }

    @Test
    fun `amount at the end without a separator`() {
        assertParsed("כפות מיץ לימון 4", 4.0, "כפות מיץ לימון")
        assertParsed("ציפורן שום 5", 5.0, "ציפורן שום")
        assertParsed("קילוגרם תפוח אדמה 1/2", 0.5, "קילוגרם תפוח אדמה")
        assertParsed("Piselli surgelati 200 g", 200.0, "g Piselli surgelati")
        assertParsed("Картофель — 200 г", 200.0, "г Картофель")
        assertNull(p("תבנית בקוטר 24"))
        assertNull(p("כפות רוטב צ'ילי מתוק 290 מ\"ל 12"))
        assertNull(p("Vitamin B12"))
        assertNull(p("Finely grated zest of ½ lemon"))
    }

    @Test
    fun `alternative amounts outside parentheses stay as they are`() {
        assertNull(p("1/2 כוס או 100 גרם סוכר"))
        assertNull(p("¼ cup to ½ cup milk, as needed"))
        assertNull(p("2 כוסות קמח לבן, כ-280 גרם"))
        assertNull(p("2.5־3 כוסות מים"))
        assertNull(p("5 עלי בזיליקום או 1 כפית אורגנו יבש"))
        // בתוך סוגריים זו הערה, והשורה עדיין ניתנת להכפלה
        assertParsed("1 cup (120g) all-purpose flour", 1.0, "cup (120g) all-purpose flour")
        assertParsed("1 tablespoon jaggery powder ( or 1 tsp sugar)", 1.0, "tablespoon jaggery powder ( or 1 tsp sugar)")
        assertParsed("1 large onion (white or yellow), diced", 1.0, "large onion (white or yellow), diced")
    }

    @Test
    fun `bidi marks bullets and spaces are cleaned`() {
        assertParsed("‏• 2  כוסות קמח", 2.0, "כוסות קמח")
    }

    @Test
    fun `only text-only ingredients are changed`() {
        val json = Json.parseToJsonElement(
            """
            [
              {"quantity": 0, "unit": null, "food": null, "note": "500 גר' קמח", "display": "500 גר' קמח", "originalText": null, "title": "לבצק"},
              {"quantity": 2, "unit": {"id": "u"}, "food": null, "note": "", "display": "2 cups"},
              {"quantity": 0, "unit": null, "food": {"id": "f", "name": "salt"}, "note": "1 pinch", "display": "salt"},
              {"quantity": null, "unit": null, "food": null, "note": "ריבה למילוי", "display": "ריבה למילוי"},
              {"quantity": 0, "unit": null, "food": null, "referencedRecipe": {"slug": "x"}, "note": "1 בצק", "display": ""}
            ]
            """,
        ).jsonArray
        val out = IngredientQuantities.apply(json)!!
        val first = out[0] as JsonObject
        assertEquals(500.0, first["quantity"]!!.jsonPrimitive.double, 0.0)
        assertEquals("גר' קמח", first["note"]!!.jsonPrimitive.content)
        assertEquals("500 גר' קמח", first["originalText"]!!.jsonPrimitive.content)
        assertEquals("", first["display"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("לבצק"), first["title"])
        for (i in 1..4) assertEquals(json[i], out[i])
        assertNull(IngredientQuantities.apply(JsonArray(json.drop(1))))
    }

    // יחידות כמו בשרת אמיתי (מתוך גיבוי): שם, רבים, קיצור
    private val serverUnits = Json.parseToJsonElement(
        """
        [
          {"id": "cup", "name": "כוס", "pluralName": "כוסות", "abbreviation": "", "aliases": []},
          {"id": "gram", "name": "גרם", "pluralName": null, "abbreviation": "", "aliases": []},
          {"id": "clove", "name": "שן", "pluralName": "שיני", "abbreviation": "", "aliases": []},
          {"id": "kg", "name": "ק\"ג", "pluralName": null, "abbreviation": "", "aliases": []},
          {"id": "kilo", "name": "קילו", "pluralName": "קילו", "abbreviation": "", "aliases": []},
          {"id": "pack", "name": "חבילה", "pluralName": "חבילות", "abbreviation": "חבילת", "aliases": [{"name": "חב'"}]}
        ]
        """,
    ).jsonArray.map { it as JsonObject }

    @Test
    fun `units are matched by any spelling`() {
        val m = io.github.yosefchai.mealie.importer.UnitCatalog.Matcher(serverUnits)
        fun id(t: String) = m.find(t)?.get("id")?.jsonPrimitive?.content
        assertEquals("cup", id("כוסות"))
        assertEquals("cup", id("כוס"))
        assertEquals("gram", id("גר'"))
        assertEquals("gram", id("גר׳"))
        assertEquals("gram", id("גרמים"))
        assertEquals("clove", id("שיני"))
        // כתיב זהה קודם: "קילו" הוא יחידה נפרדת בשרת, ו"קילוגרם" שייך ל-ק"ג
        assertEquals("kilo", id("קילו"))
        assertEquals("kg", id("ק״ג"))
        assertEquals("kg", id("קילוגרם"))
        assertEquals("pack", id("חבילת"))
        assertEquals("pack", id("חב'"))
        assertNull(id("כפית"))
        assertNull(id("ביצים"))
        assertNull(id(""))
    }

    @Test
    fun `known units can be created and unknown words cannot`() {
        val catalog = io.github.yosefchai.mealie.importer.UnitCatalog
        assertEquals("כפית", catalog.conceptOf("כפיות")?.name)
        assertEquals("tablespoon", catalog.conceptOf("Tbsp.")?.name)
        assertEquals("מ\"ל", catalog.conceptOf("מ״ל")?.name)
        assertNull(catalog.conceptOf("ביצים"))
        assertNull(catalog.conceptOf("large"))
        // יחידה חדשה מוצגת בקיצור, כמו שהיא כתובה ("150 g", לא "150 grams")
        assertEquals("g", catalog.conceptOf("grams")?.abbreviation)
        assertEquals("tbsp", catalog.conceptOf("tablespoon")?.abbreviation)
        assertNull(catalog.conceptOf("גרם")?.abbreviation)
    }

    @Test
    fun `the unit word moves from the text to the unit`() {
        val m = io.github.yosefchai.mealie.importer.UnitCatalog.Matcher(serverUnits)
        val json = Json.parseToJsonElement(
            """
            [
              {"quantity": 0, "unit": null, "food": null, "note": "500 גר' קמח לחם"},
              {"quantity": 0, "unit": null, "food": null, "note": "1 כוס (200 גרם) סוכר"},
              {"quantity": 0, "unit": null, "food": null, "note": "2 ביצים גודל M"},
              {"quantity": 0, "unit": null, "food": null, "note": "3 שיני שום"}
            ]
            """,
        ).jsonArray
        val out = IngredientQuantities.apply(json) { m.find(it) }!!.map { it as JsonObject }
        fun unit(i: Int) = (out[i]["unit"] as? JsonObject)?.get("id")?.jsonPrimitive?.content
        fun note(i: Int) = out[i]["note"]!!.jsonPrimitive.content
        assertEquals("gram", unit(0)); assertEquals("קמח לחם", note(0))
        assertEquals("cup", unit(1)); assertEquals("(200 גרם) סוכר", note(1))
        assertNull(unit(2)); assertEquals("ביצים גודל M", note(2))
        assertEquals("clove", unit(3)); assertEquals("שום", note(3))
        assertEquals("500 גר' קמח לחם", out[0]["originalText"]!!.jsonPrimitive.content)
    }

    @Test
    fun `existing original text is kept`() {
        val json = Json.parseToJsonElement(
            """[{"quantity": 0, "unit": null, "food": null, "note": "2 ביצים", "originalText": "2 large eggs"}]""",
        ).jsonArray
        val out = IngredientQuantities.apply(json)!![0] as JsonObject
        assertEquals("2 large eggs", out["originalText"]!!.jsonPrimitive.content)
        assertEquals(Parsed(2.0, "ביצים"), p("2 ביצים"))
    }
}
