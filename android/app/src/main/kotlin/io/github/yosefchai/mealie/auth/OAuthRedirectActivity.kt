package io.github.yosefchai.mealie.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.yosefchai.mealie.MainActivity

/**
 * מקבל את הפניית ה-OAuth כשהדפדפן לא תומך ב-Auth Tab ונפתח Custom Tab רגיל.
 * מעביר את התוצאה, מחזיר את MainActivity לחזית (singleTask, בלי מופע כפול) ונסגר מיד.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.takeIf(OidcRedirects.isOurRedirect)?.let(OidcRedirects::deliver)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
