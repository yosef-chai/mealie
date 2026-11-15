package io.github.yosefchai.mealie.core

/**
 * נוסחת versionCode, זהה ל-buildSrc/MealieVersion.kt (בדיקת יחידה מוודאת מול BuildConfig).
 * X·1,000,000 + Y·10,000 + Z·100 + appPatch.
 */
object VersionCode {
    private val tagPattern = Regex("""^android-v(\d{1,4})\.(\d{1,2})\.(\d{1,2})(?:\.(\d{1,2}))?$""")

    data class Parsed(val mealieVersion: String, val appPatch: Int, val code: Int)

    fun of(major: Int, minor: Int, patch: Int, appPatch: Int): Int {
        require(appPatch in 0..99 && minor in 0..99 && patch in 0..99)
        return major * 1_000_000 + minor * 10_000 + patch * 100 + appPatch
    }

    /** "android-v3.1.0" או "android-v3.1.0.2". תגית בלי סיומת היא appPatch=0. */
    fun fromTag(tag: String): Parsed? {
        val m = tagPattern.matchEntire(tag.trim()) ?: return null
        val (x, y, z) = m.destructured
        val n = m.groupValues[4].ifEmpty { "0" }.toInt()
        return Parsed("$x.$y.$z", n, of(x.toInt(), y.toInt(), z.toInt(), n))
    }

    fun tagFor(mealieVersion: String, appPatch: Int): String =
        if (appPatch == 0) "android-v$mealieVersion" else "android-v$mealieVersion.$appPatch"
}
