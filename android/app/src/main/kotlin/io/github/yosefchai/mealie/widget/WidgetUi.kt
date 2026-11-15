package io.github.yosefchai.mealie.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.FilledButton
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.github.yosefchai.mealie.MainActivity
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.ui.theme.Dark
import io.github.yosefchai.mealie.ui.theme.Light

/** גדלים קנוניים (WL-2 בהנחיות האיכות): 2x1, 2x2, 4x2, 4x4. */
internal object WidgetSizes {
    val SMALL = DpSize(110.dp, 48.dp)
    val SQUARE = DpSize(110.dp, 110.dp)
    val WIDE = DpSize(250.dp, 110.dp)
    val LARGE = DpSize(250.dp, 250.dp)
    val all = setOf(SMALL, SQUARE, WIDE, LARGE)
}

/** צבעי Mealie למכשירים בלי Dynamic Color (לפני Android 12). מ-12 ואילך: צבעי הטפט של המשתמש. */
internal val BrandColors = ColorProviders(light = Light, dark = Dark)

@Composable
internal fun WidgetTheme(content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme(content = content)
    else GlanceTheme(colors = BrandColors, content = content)
}

/**
 * מחרוזות בשפה שהמשתמש בחר באפליקציה. מ-Android 13 המערכת מחילה אותה בעצמה;
 * לפני כן AppCompat שומר אותה רק ל-Activities, ולכן בונים Context מתאים.
 */
internal fun Context.localized(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return this
    val config = Configuration(resources.configuration).apply { setLocale(locales[0]) }
    return createConfigurationContext(config)
}

/** פתיחה של מסך ב-Mealie (נתיב יחסי), עם מעבר הפתיחה של המערכת מהווידג'ט. */
internal fun openPath(context: Context, path: String): Action = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_PATH)
        .putExtra(MainActivity.EXTRA_PATH, path)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
)

internal fun openApp(context: Context): Action = actionStartActivity(
    Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
)

internal fun problemText(context: Context, problem: WidgetProblem): String = context.getString(
    when (problem) {
        WidgetProblem.NO_SERVER -> R.string.widget_no_server
        WidgetProblem.SIGNED_OUT -> R.string.widget_sign_in
        WidgetProblem.OFFLINE -> R.string.widget_offline
        WidgetProblem.NO_LISTS -> R.string.widget_no_lists
        WidgetProblem.SYNC_FAILED -> R.string.widget_sync_failed
        WidgetProblem.ERROR -> R.string.widget_error
    },
)

/** מצב ריק או שגיאה: אייקון, הסבר ופעולה, ממורכזים בכל גודל (WT-1, WS-3). */
@Composable
internal fun WidgetMessage(icon: Int, title: String, body: String?, action: String?, onAction: Action?, compact: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).let { m -> onAction?.let { m.clickable(it) } ?: m },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) {
            Image(
                ImageProvider(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(32.dp),
            )
            Spacer(GlanceModifier.height(8.dp))
        }
        Text(
            title,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            maxLines = 2,
        )
        if (body != null && !compact) {
            Spacer(GlanceModifier.height(2.dp))
            Text(body, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center), maxLines = 3)
        }
        if (action != null && onAction != null && !compact) {
            Spacer(GlanceModifier.height(10.dp))
            FilledButton(text = action, onClick = onAction)
        }
    }
}

/** פינות הווידג'ט לפי המערכת (Android 12+); בגרסאות קודמות המשגר קובע את הצורה. */
fun GlanceModifier.systemCorners(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cornerRadius(android.R.dimen.system_app_widget_background_radius) else this
