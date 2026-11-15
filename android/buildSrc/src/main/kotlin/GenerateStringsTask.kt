import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * מייצר values-<qualifier>/strings.xml לכל השפות של Mealie:
 * - מחרוזות שקיימות כבר ב-Mealie נלקחות מ-frontend/app/lang/messages (mealieKeys: שם משאב → מפתח מקונן).
 * - מחרוזות ייחודיות לאפליקציה נלקחות מ-android/i18n/<locale>.json, עם נפילה לאנגלית.
 */
@CacheableTask
abstract class GenerateStringsTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mealieLangDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appI18nDir: DirectoryProperty

    @get:Input
    abstract val mealieKeys: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val langFiles = mealieLangDir.get().asFile.listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name }
            ?: throw GradleException("No Mealie locale files in ${mealieLangDir.get()}")

        val appDir = appI18nDir.get().asFile
        val appSource = readFlat(File(appDir, "$SOURCE_LOCALE.json"))
            ?: throw GradleException("Missing $SOURCE_LOCALE.json in $appDir")
        val mealieSource = readNested(langFiles.first { it.nameWithoutExtension == SOURCE_LOCALE })
        val keys = mealieKeys.get()

        val problems = mutableListOf<String>()
        val tags = mutableListOf<String>()
        for (file in langFiles) {
            val locale = file.nameWithoutExtension
            val qualifier = qualifierFor(locale) ?: continue
            val isSource = locale == SOURCE_LOCALE
            val mealie = if (isSource) mealieSource else readNested(file)
            val app = if (isSource) appSource else readFlat(File(appDir, "$locale.json")).orEmpty()

            val entries = sortedMapOf<String, String>()
            for ((res, path) in keys) {
                val value = lookup(mealie, path)
                val usable = value != null && isPlainMessage(value)
                when {
                    usable -> entries[res] = value!!
                    isSource -> problems += "Mealie key '$path' missing or not plain in $SOURCE_LOCALE"
                }
            }
            for ((res, source) in appSource) {
                val value = app[res]?.takeIf { it.isNotBlank() } ?: if (isSource) source else continue
                if (placeholders(value) != placeholders(source)) {
                    problems += "$locale: '$res' placeholders ${placeholders(value)} != ${placeholders(source)}"
                    continue
                }
                entries[res] = value
            }
            if (!isSource && entries.isEmpty()) continue

            val dir = File(out, if (qualifier.isEmpty()) "values" else "values-$qualifier")
            dir.mkdirs()
            File(dir, "strings.xml").writeText(toXml(entries), Charsets.UTF_8)
            // עברית: חלק ממנגנוני הזמן-ריצה עדיין מחפשים את הקוד הישן "iw", ולכן כותבים גם אותו.
            legacyAlias[qualifier]?.let { alias ->
                File(out, "values-$alias").apply { mkdirs() }.resolve("strings.xml").writeText(toXml(entries), Charsets.UTF_8)
            }
            tags +=if (qualifier.isEmpty()) SOURCE_LOCALE else qualifier.replace("-r", "-")
        }
        // לבורר השפה בתוך האפליקציה (Android 12 ומטה): אותה רשימה כמו ב-locale config.
        File(out, "values/locales.xml").writeText(
            buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
                append("    <string-array name=\"supported_locales\" translatable=\"false\">\n")
                tags.forEach { append("        <item>").append(it).append("</item>\n") }
                append("    </string-array>\n</resources>\n")
            },
            Charsets.UTF_8,
        )
        if (problems.isNotEmpty()) {
            throw GradleException("String generation failed:\n" + problems.joinToString("\n"))
        }
    }

    private fun readNested(file: File): Map<*, *> =
        JsonSlurper().parse(file, "UTF-8") as? Map<*, *> ?: emptyMap<Any, Any>()

    private fun readFlat(file: File): Map<String, String>? {
        if (!file.exists()) return null
        val parsed = JsonSlurper().parse(file, "UTF-8") as? Map<*, *> ?: return null
        return parsed.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    private fun lookup(root: Map<*, *>, path: String): String? {
        var node: Any? = root
        for (part in path.split('.')) node = (node as? Map<*, *>)?.get(part) ?: return null
        return node as? String
    }

    /** הודעות עם תחביר vue-i18n (קישור, ריבוי, משתנים) לא מתאימות למשאב קבוע. */
    private fun isPlainMessage(value: String) =
        value.isNotBlank() && "@:" !in value && '{' !in value && '|' !in value

    private fun placeholders(value: String): Set<String> =
        Regex("""%(\d+\$)?[sd]""").findAll(value).map { it.value }.toSet()

    private fun toXml(entries: Map<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<!-- Generated by :app:generateMealieStrings. Do not edit. -->\n")
        append("<resources>\n")
        for ((name, value) in entries) {
            append("    <string name=\"").append(name).append('"')
            if ('%' in value && placeholders(value).isEmpty()) append(" formatted=\"false\"")
            append('>').append(escape(value)).append("</string>\n")
        }
        append("</resources>\n")
    }

    private fun escape(value: String): String {
        val sb = StringBuilder()
        value.forEachIndexed { i, c ->
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '@', '?' -> if (i == 0) sb.append('\\').append(c) else sb.append(c)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    companion object {
        const val SOURCE_LOCALE = "en-US"

        /** שפת המקור היא ברירת המחדל; אזורים כפולים מקבלים -r, ושאר השפות לפי קוד שפה בלבד. */
        private val explicit = mapOf(
            "en-US" to "",
            "en-GB" to "en-rGB",
            "fr-FR" to "fr",
            "fr-BE" to "fr-rBE",
            "fr-CA" to "fr-rCA",
            "pt-BR" to "pt",
            "pt-PT" to "pt-rPT",
            "zh-CN" to "zh-rCN",
            "zh-TW" to "zh-rTW",
            "no-NO" to "nb",
            "sr-SP" to "sr",
        )

        private val legacyAlias = mapOf("he" to "iw")

        fun qualifierFor(locale: String): String? {
            explicit[locale]?.let { return it }
            val lang = locale.substringBefore('-').lowercase()
            return lang.takeIf { it.matches(Regex("[a-z]{2,3}")) }
        }
    }
}
