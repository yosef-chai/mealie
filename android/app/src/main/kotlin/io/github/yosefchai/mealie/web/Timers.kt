package io.github.yosefchai.mealie.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.provider.AlarmClock
import io.github.yosefchai.mealie.R
import java.util.Locale

/** בקשה לטיימר מתוך שלב הכנה; בטווח ("20-25 דקות") המשתמש בוחר. */
data class TimerRequest(val minSeconds: Int, val maxSeconds: Int, val recipe: String?, val step: Int?)

/**
 * טיימר בשעון של הטלפון, דרך AlarmClock.ACTION_SET_TIMER (Common Intents).
 * EXTRA_SKIP_UI מתחיל את הטיימר מיד, בלי לעבור לאפליקציית השעון. השליחה דורשת את ההרשאה SET_ALARM.
 */
object Timers {

    fun start(context: Context, seconds: Int, recipe: String?, step: Int?): Boolean {
        val message = when {
            recipe != null && step != null -> context.getString(R.string.timer_label_step, recipe, step)
            recipe != null -> recipe
            else -> context.getString(R.string.timer_label_default)
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_400))
            .putExtra(AlarmClock.EXTRA_MESSAGE, message.take(100))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** "25 דק'", "1 שעה ו-30 דק'" לפי השפה של המכשיר. */
    fun format(seconds: Int, locale: Locale = Locale.getDefault()): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = buildList {
            if (h > 0) add(Measure(h, MeasureUnit.HOUR))
            if (m > 0) add(Measure(m, MeasureUnit.MINUTE))
            if (s > 0 || isEmpty()) add(Measure(s, MeasureUnit.SECOND))
        }
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).formatMeasures(*parts.toTypedArray())
    }
}
