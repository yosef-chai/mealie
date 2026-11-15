package io.github.yosefchai.mealie.web

/**
 * לשוניות הסרגל הנייטיב ומיפוי שלהן לנתיבים של Mealie (DefaultLayout.vue).
 * כל הנתיבים יחסיים לכתובת הבסיס (אחרי הסרת SUB_PATH).
 */
enum class Tab(private val match: Regex?) {
    RECIPES(Regex("""^/g/[^/?#]+(?:/(?:recipes(?:/(?:categories|tags|tools|finder|timeline).*)?|r/.*)?)?/?(?:[?#].*)?$""")),
    MEALPLAN(Regex("""^/household/mealplan(?:/.*)?(?:[?#].*)?$""")),
    SHOPPING(Regex("""^/shopping-lists(?:/.*)?(?:[?#].*)?$""")),
    COOKBOOKS(Regex("""^/g/[^/?#]+/cookbooks(?:/.*)?(?:[?#].*)?$""")),
    MORE(null),
    ;

    /** שורש הלשונית. בלי groupSlug, "/" מפנה לבית הקבוצה של המשתמש. */
    fun rootPath(groupSlug: String?): String = when (this) {
        RECIPES -> groupSlug?.let { "/g/$it" } ?: "/"
        MEALPLAN -> "/household/mealplan/planner/view"
        SHOPPING -> "/shopping-lists"
        COOKBOOKS -> groupSlug?.let { "/g/$it/cookbooks" } ?: "/"
        MORE -> "/"
    }

    fun isRoot(path: String): Boolean {
        val clean = path.substringBefore('?').substringBefore('#').trimEnd('/').ifEmpty { "/" }
        return when (this) {
            RECIPES -> clean == "/" || Regex("""^/g/[^/]+$""").matches(clean)
            MEALPLAN -> clean.startsWith("/household/mealplan/planner")
            SHOPPING -> clean == "/shopping-lists"
            COOKBOOKS -> Regex("""^/g/[^/]+/cookbooks$""").matches(clean)
            MORE -> false
        }
    }

    companion object {
        val bar = listOf(RECIPES, MEALPLAN, SHOPPING, COOKBOOKS, MORE)

        /** COOKBOOKS נבדק לפני RECIPES כי שניהם תחת /g/<slug>. */
        fun forPath(path: String): Tab? =
            listOf(COOKBOOKS, MEALPLAN, SHOPPING, RECIPES).firstOrNull { it.match?.matches(path) == true }

        fun groupSlugOf(path: String): String? =
            Regex("""^/g/([^/?#]+)""").find(path)?.groupValues?.get(1)

        private val noTabPaths = Regex("""^/(login|register|forgot-password|reset-password|admin/setup)(?:[/?#].*)?$""")

        /** מסכים שבהם הסרגל מוסתר (כניסה, הרשמה). */
        fun hidesBar(path: String): Boolean = noTabPaths.matches(path)

        fun isLogin(path: String): Boolean = path.startsWith("/login")
    }
}
