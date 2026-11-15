/**
 * נוסחת versionCode. חייבת להיות זהה ל-core/VersionCode.kt באפליקציה (בדיקת יחידה מוודאת).
 * X·1,000,000 + Y·10,000 + Z·100 + appPatch, כך שתיקון אפליקציה לא חופף לגרסת Mealie הבאה.
 */
object MealieVersion {
    private val semver = Regex("""^v?(\d{1,4})\.(\d{1,2})\.(\d{1,2})$""")

    fun versionCode(mealieVersion: String, appPatch: Int): Int {
        require(appPatch in 0..99) { "appPatch must be 0-99, got $appPatch" }
        val m = semver.matchEntire(mealieVersion.trim())
            ?: error("mealieVersion must look like X.Y.Z, got '$mealieVersion'")
        val (x, y, z) = m.destructured
        return x.toInt() * 1_000_000 + y.toInt() * 10_000 + z.toInt() * 100 + appPatch
    }

    fun versionName(mealieVersion: String, appPatch: Int): String {
        val base = mealieVersion.trim().removePrefix("v")
        return if (appPatch == 0) base else "$base.$appPatch"
    }
}
