package io.github.yosefchai.mealie.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * מצרך שיובא כטקסט ("500 גר' קמח") נשמר ב-Mealie בלי כמות, ולכן שינוי מספר המנות לא משנה אותו.
 * כאן הכמות שבתחילת השורה עוברת לשדה quantity, ושאר הטקסט נשאר ב-note כמו שהוא.
 *
 * בכוונה בלי יחידה ובלי מצרך מקושר: המפענחים של השרת לא מכירים יחידות בעברית, והיו יוצרים במסד
 * יחידות ומצרכים שגויים ("ביצים" כיחידה). הטקסט המקורי נשמר ב-originalText, כך שאפשר עדיין
 * להריץ עליו את המפענח של Mealie.
 *
 * שורה שאין בה כמות אחת וברורה נשארת כמו שהיא: טווח ("1-2 כפות"), שתי כמויות ("2 ביצים + 1 חלמון"),
 * מידה של תבנית או טמפרטורה ("24 ס"מ", "180 מעלות") ומספור ("1. קמח").
 */
object IngredientQuantities {

    data class Parsed(val quantity: Double, val note: String)

    private const val V = "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"
    private val VULGAR = mapOf(
        '½' to 1 / 2.0, '⅓' to 1 / 3.0, '⅔' to 2 / 3.0, '¼' to 1 / 4.0, '¾' to 3 / 4.0,
        '⅕' to 1 / 5.0, '⅖' to 2 / 5.0, '⅗' to 3 / 5.0, '⅘' to 4 / 5.0, '⅙' to 1 / 6.0,
        '⅚' to 5 / 6.0, '⅛' to 1 / 8.0, '⅜' to 3 / 8.0, '⅝' to 5 / 8.0, '⅞' to 7 / 8.0,
    )

    // "1½" | "1 1/2" | "1/2" | "1.5" / "1,5" / "1,000" | "½"
    private val NUMBER = Regex(
        "^(?:(\\d+)\\s*([$V])|(\\d+)\\s+(\\d+)\\s*[/⁄]\\s*(\\d+)|(\\d+)\\s*[/⁄]\\s*(\\d+)|(\\d+(?:[.,]\\d+)?)|([$V]))",
    )

    private val APPROX = Regex(
        "^(?:כ\\s*[-־–]?\\s*|~\\s*|≈\\s*|about\\s+|approx(?:imately|\\.)?\\s*|around\\s+|ca\\.\\s*|circa\\s+|environ\\s+|etwa\\s+|ungefähr\\s+)(?=[\\d$V])",
        RegexOption.IGNORE_CASE,
    )

    // מילים במקום מספר, בתחילת השורה. הארוכות קודם ("שלושת רבעי" לפני "שלוש").
    private val WORDS: List<Pair<String, Double>> = listOf(
        "שני שלישים" to 2 / 3.0, "שני שליש" to 2 / 3.0, "שלושת רבעי" to 0.75, "שלושה רבעים" to 0.75,
        "חצי" to 0.5, "רבע" to 0.25, "שליש" to 1 / 3.0,
        "שתיים" to 2.0, "שתי" to 2.0, "שני" to 2.0, "שלושה" to 3.0, "שלוש" to 3.0, "ארבעה" to 4.0, "ארבע" to 4.0,
        "חמישה" to 5.0, "חמש" to 5.0, "שישה" to 6.0, "שש" to 6.0, "שבעה" to 7.0, "שבע" to 7.0, "שמונה" to 8.0,
        "תשעה" to 9.0, "תשע" to 9.0, "עשרה" to 10.0, "עשר" to 10.0,
        "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0,
    ).sortedByDescending { it.first.length }

    // אחרי המספר: טווח או "או" ← אין כמות אחת ("1-2", "2.5־3", "2 עד 3", "שתיים או שלוש")
    private val RANGE = Regex(
        "^\\s*(?:[-–—־]|(?:עד|או|to|or|bis|oder|à|ou)(?=\\s))\\s*(?:[\\d$V]|(?:" + WORDS.joinToString("|") { Regex.escape(it.first) } + ")(?![\\p{L}]))",
        RegexOption.IGNORE_CASE,
    )

    // כמות חלופית מחוץ לסוגריים: "¼ cup to ½ cup", "1/2 כוס או 100 גרם", "2 כוסות קמח, כ-280 גרם".
    // הכפלה של המספר הראשון בלבד הייתה יוצרת שורה סותרת. כמות בתוך סוגריים ("1 כוס (120 גרם)") היא הערה, ונשארת.
    private val ALTERNATIVE = Regex(
        "(?:^|\\s)(?:to|עד|או|or|oder|ou)\\s+(?:about\\s+|כ-?\\s*)?[\\d$V]|[,،;]\\s*(?:כ\\s*[-־]?\\s*|~\\s*|about\\s+|approx\\.?\\s*)?[\\d$V]",
        RegexOption.IGNORE_CASE,
    )

    // יחידה בלי מספר בתחילת השורה היא אחת: "כוס שעועית לבנה", "כפית תמצית וניל"
    private const val UNITS_ONE =
        "כוס|כף|כפית|קילו|קילוגרם|ק\"ג|ק״ג|ליטר|חבילה|חבילת|קופסה|קופסת|קופסא|שקית|מיכל|צנצנת|פחית|גביע|חופן|צרור|ראש|כיכר|קורט|קמצוץ|שן|מקל|ענף|פרוסה|פרוסת|בקבוק|יחידה|סלסלה|סלסלת"
    private val IMPLICIT_ONE = Regex("^(?:$UNITS_ONE)(?=[\\s(])")
    // עוד יחידה אחרי פסיק: "כפית פפריקה, כפית מלח" הם שני מצרכים בשורה אחת
    private val SECOND_UNIT = Regex("[,،;/]\\s*(?:\\d+\\s*)?(?:$UNITS_ONE|כוסות|כפות|כפיות)(?![\\p{L}])")

    // הכמות בסוף בלי מפריד: "כפות מיץ לימון 4", "ציפורן שום 5", "Piselli surgelati 200 g"
    // היחידה שאחרי המספר קצרה (g, ml, גרם, מ"ל, шт, 個), כדי ש"zest of ½ lemon" לא ייקרא כ"½ lemon"
    private val TRAILING_BARE = Regex("^([^\\d$V]{2,40}?)\\s+(\\d+(?:[.,]\\d+)?|\\d+\\s*[/⁄]\\s*\\d+|[$V])\\s*([\\p{L}\"'׳״]{1,3}\\.?)?$")
    // מידה של תבנית, תנור או טמפרטורה, ולא כמות
    private val NOT_INGREDIENT = Regex("תבנית|תנור|קוטר|מעלות|בחום|oven|pan\\b|tin\\b|°|ס\"מ|ס״מ|\\bcm\\b|inch", RegexOption.IGNORE_CASE)

    // אחרי המספר: מידה, טמפרטורה, אחוז או מספור, ולא כמות של מצרך
    private val NOT_AMOUNT = Regex(
        "^(?:\\s*(?:%|°|ס\"מ|ס״מ|מ\"מ|מ״מ|cm|mm|inch(?:es)?|in\\.|אינץ['׳]?|מעלות|degrees?)(?![\\p{L}])|[.)]\\s|[.):]$|:)",
        RegexOption.IGNORE_CASE,
    )

    // עוד כמות בהמשך השורה ("2 ביצים + 1 חלמון", "1 כוס ועוד 2 כפות")
    private val SECOND_AMOUNT = Regex("(?:^|\\s)(?:\\+|ועוד|plus|and)\\s*[\\d$V]|(?:^|\\s)ו[\\d$V]", RegexOption.IGNORE_CASE)

    // "2 כוסות וחצי קמח", "כוס וחצי קמח", "2 וחצי כוסות", "כף ושליש שמרים"
    private val HALF_AFTER = Regex("^([\\p{L}\"'׳״]{1,10})\\s+(וחצי|ורבע|ושליש)(?=[\\s(]|$)\\s*(.*)$")
    private val HALF_FIRST = Regex("^(וחצי|ורבע|ושליש)(?=\\s|$)\\s*(.*)$")

    // "קמח – 500 גרם", "ביצים: 2" ← "500 גרם קמח", "2 ביצים"
    private val TRAILING = Regex("^([^\\d$V:–—\\-]{2,40}?)\\s*(?::|\\s[-–—]\\s|[–—])\\s*(.{1,25})$")

    private val PARENS = Regex("\\([^()]*\\)|\\[[^\\[\\]]*]")
    private val BIDI = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
    private val BULLET = Regex("^[•·▪●◦*\\-–—]\\s+")
    private val SPACES = Regex("[\\s\\u00A0]+")
    private val HEBREW = Regex("[\\u0590-\\u05FF]")
    private val DIGIT = Regex("[\\d$V]")

    fun parse(text: String): Parsed? {
        val t = BULLET.replace(SPACES.replace(BIDI.replace(text, ""), " ").trim(), "")
        if (t.isEmpty() || t.length > 200) return null
        return leading(t, allowEmptyNote = false) ?: trailing(t) ?: bareTrailing(t)
    }

    private fun leading(line: String, allowEmptyNote: Boolean): Parsed? {
        var rest = line
        val approx = APPROX.find(rest)?.let { rest = rest.substring(it.range.last + 1); true } ?: false

        var qty: Double? = null
        NUMBER.find(rest)?.let { m ->
            qty = number(m) ?: return null
            rest = rest.substring(m.range.last + 1)
        }
        if (qty == null && !approx) {
            WORDS.firstOrNull { (w, _) -> rest.startsWith("$w ", ignoreCase = true) }?.let { (w, v) ->
                qty = v
                rest = rest.substring(w.length)
            }
        }
        if (qty == null) {
            if (approx) return null
            // בלי מספר: "כוס וחצי קמח" היא אחת וחצי, "כוס שעועית" היא אחת
            val half = HALF_AFTER.matchEntire(rest)?.takeIf { HEBREW.containsMatchIn(it.groupValues[1]) }
            return when {
                half != null -> finish(1 + fraction(half.groupValues[2]), "${half.groupValues[1]} ${half.groupValues[3]}", false, line, allowEmptyNote)
                // רק כשאין מספר אחר מחוץ לסוגריים ("קילוגרם תפוח אדמה 1/2" הוא חצי)
                IMPLICIT_ONE.containsMatchIn(rest) && !DIGIT.containsMatchIn(topLevel(rest)) -> finish(1.0, rest, false, line, allowEmptyNote)
                else -> null
            }
        }
        if (RANGE.containsMatchIn(rest) || NOT_AMOUNT.containsMatchIn(rest)) return null
        rest = rest.trimStart()
        val first = HALF_FIRST.matchEntire(rest)
        val after = if (first == null) HALF_AFTER.matchEntire(rest) else null
        when {
            first != null -> { qty = qty!! + fraction(first.groupValues[1]); rest = first.groupValues[2] }
            after != null -> { qty = qty!! + fraction(after.groupValues[2]); rest = "${after.groupValues[1]} ${after.groupValues[3]}" }
        }
        return finish(qty!!, rest.trimStart(' ', '-', '–', '—', ':'), approx, line, allowEmptyNote)
    }

    // "קמח – 500 גרם" ← "500 גרם קמח"
    private fun trailing(t: String): Parsed? {
        val m = TRAILING.matchEntire(t) ?: return null
        val name = m.groupValues[1].trim()
        val amount = leading(m.groupValues[2].trim(), allowEmptyNote = true) ?: return null
        if (amount.note.split(' ').count { it.isNotBlank() } > 1) return null
        return Parsed(amount.quantity, listOf(amount.note, name).filter { it.isNotBlank() }.joinToString(" "))
    }

    // "כפות מיץ לימון 4" ← "4 כפות מיץ לימון"
    private fun bareTrailing(t: String): Parsed? {
        val m = TRAILING_BARE.matchEntire(t) ?: return null
        val name = m.groupValues[1].trim()
        if (':' in name || !name.any { it.isLetter() } || NOT_INGREDIENT.containsMatchIn(t)) return null
        val qty = NUMBER.find(m.groupValues[2])?.let { number(it) } ?: return null
        return finish(qty, listOf(m.groupValues[3], name).filter { it.isNotBlank() }.joinToString(" "), false, t, false)
    }

    private fun finish(qty: Double, note: String, approx: Boolean, original: String, allowEmptyNote: Boolean): Parsed? {
        val n = note.trim()
        if (qty <= 0 || qty > 100_000 || qty.isNaN()) return null
        if (n.isEmpty() && !allowEmptyNote) return null
        if (n.isNotEmpty() && !n.any { it.isLetter() }) return null
        val top = topLevel(n)
        if (SECOND_AMOUNT.containsMatchIn(top) || ALTERNATIVE.containsMatchIn(top) || SECOND_UNIT.containsMatchIn(top)) return null
        val mark = if (!approx) "" else if (HEBREW.containsMatchIn(original)) " (בערך)" else " (approx.)"
        return Parsed(qty, n + mark)
    }

    // הטקסט שמחוץ לסוגריים, גם בסוגריים מקוננים
    private fun topLevel(s: String): String {
        var t = s
        while (true) {
            val next = PARENS.replace(t, " ")
            if (next == t) return t
            t = next
        }
    }

    private fun fraction(word: String) = when (word) {
        "וחצי" -> 0.5
        "ושליש" -> 1 / 3.0
        else -> 0.25
    }

    private fun number(m: MatchResult): Double? {
        val g = m.groupValues
        return when {
            g[1].isNotEmpty() -> g[1].toDouble() + VULGAR.getValue(g[2][0])
            g[3].isNotEmpty() -> g[5].toDouble().takeIf { it > 0 }?.let { g[3].toDouble() + g[4].toDouble() / it }
            g[6].isNotEmpty() -> g[7].toDouble().takeIf { it > 0 }?.let { g[6].toDouble() / it }
            g[8].isNotEmpty() -> decimal(g[8])
            g[9].isNotEmpty() -> VULGAR.getValue(g[9][0])
            else -> null
        }
    }

    // "1,5" הוא אחד וחצי, אבל "1,000" הוא אלף
    private fun decimal(s: String): Double? =
        if (Regex("\\d{1,3},\\d{3}").matches(s)) s.replace(",", "").toDoubleOrNull() else s.replace(',', '.').toDoubleOrNull()

    private val FIRST_WORD = Regex("^([^\\s(]+)(?:\\s+|(?=\\()|$)(.*)$")

    /** המילה הראשונה בטקסט שאחרי הכמות: היחידה, אם יש ("גר'" ב-"גר' קמח"). */
    fun unitToken(note: String): String? = FIRST_WORD.matchEntire(note.trim())?.groupValues?.get(1)

    /**
     * מעדכן את המצרכים שנשמרו כטקסט בלבד. מחזיר null אם אין מה לשנות.
     * מצרך שכבר יש לו כמות, יחידה, מצרך מקושר או מתכון מקושר לא משתנה.
     *
     * unitFor: יחידה מהשרת למילה שאחרי הכמות, או null. כשנמצאה, היא מקושרת למצרך והמילה יוצאת מהטקסט,
     * כי Mealie מציג את היחידה בעצמו.
     */
    fun apply(ingredients: JsonArray, unitFor: (String) -> JsonObject? = { null }): JsonArray? {
        var changed = false
        val out = ingredients.map { el ->
            val o = el as? JsonObject ?: return@map el
            if (!textOnly(o)) return@map el
            val text = (o["note"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@map el
            val p = parse(text) ?: return@map el
            changed = true
            val original = (o["originalText"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: text
            var note = p.note
            var unit: JsonElement = JsonNull
            FIRST_WORD.matchEntire(note)?.let { m ->
                unitFor(m.groupValues[1])?.let { u ->
                    unit = u
                    note = m.groupValues[2].trim()
                }
            }
            JsonObject(
                o + mapOf(
                    "quantity" to JsonPrimitive(p.quantity),
                    "unit" to unit,
                    "note" to JsonPrimitive(note),
                    "originalText" to JsonPrimitive(original),
                    // השרת מחשב את הטקסט המוצג מחדש רק כשהשדה ריק
                    "display" to JsonPrimitive(""),
                ),
            )
        }
        return if (changed) JsonArray(out) else null
    }

    private fun textOnly(o: JsonObject): Boolean {
        fun empty(e: JsonElement?) = e == null || e is JsonNull
        val q = (o["quantity"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        return q == 0.0 && empty(o["unit"]) && empty(o["food"]) && empty(o["referencedRecipe"])
    }
}
