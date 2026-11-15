package io.github.yosefchai.mealie.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import io.github.yosefchai.mealie.MainActivity

/**
 * יעד שיתוף: אפליקציות שולחות טקסט חופשי ("תראו איזה מתכון! https://…"), ולכן מחלצים את ה-URL הראשון.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val url = extractUrl(text) ?: extractUrl(subject)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_IMPORT)
                .putExtra(MainActivity.EXTRA_IMPORT_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        fun extractUrl(text: String): String? {
            val m = Patterns.WEB_URL.matcher(text)
            var bare: String? = null
            while (m.find()) {
                val candidate = m.group().trimEnd('.', ',', ')', '!', '?', '"', '\'')
                if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) return candidate
                // כתובת בלי scheme ("example.com/recipe"): רק אם אין כתובת מלאה בטקסט.
                if (bare == null && '/' in candidate) bare = "https://$candidate"
            }
            return bare
        }
    }
}
