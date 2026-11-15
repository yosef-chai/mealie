package io.github.yosefchai.mealie.widget

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.yosefchai.mealie.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * רענון הווידג'טים: כל 30 דקות כשיש רשת, מיד אחרי שהמשתמש יוצא מהאפליקציה (שינויים שעשה בה),
 * ובלחיצה על "רענון". updatePeriodMillis=0 בהגדרת הווידג'ט, כי WorkManager חסכוני יותר.
 */
class WidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!WidgetData.hasWidgets(applicationContext)) {
            // הווידג'ט האחרון הוסר: אין סיבה להמשיך לרענן
            WorkManager.getInstance(applicationContext).cancelUniqueWork(WidgetSync.PERIODIC)
            return Result.success()
        }
        WidgetData.refreshAll(applicationContext)
        return Result.success()
    }
}

object WidgetSync {
    internal const val PERIODIC = "widget-sync"
    private const val NOW = "widget-sync-now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<WidgetSyncWorker>(30, TimeUnit.MINUTES).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** רענון חד-פעמי (ביציאה מהאפליקציה, בהוספת ווידג'ט, בנתונים ישנים). */
    fun refreshSoon(context: Context) {
        val req = OneTimeWorkRequestBuilder<WidgetSyncWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelIfUnused(context: Context) {
        // הבדיקה אם נשארו ווידג'טים נעשית ב-worker (דורשת coroutine); הוא מבטל את הרענון התקופתי
        refreshSoon(context)
    }

    /**
     * תצוגה מקדימה בבורר הווידג'טים (Android 15+): פעם אחת לכל גרסה, כי זה API מוגבל בקצב
     * (בערך שתי קריאות בשעה). לגרסאות קודמות יש previewLayout ו-previewImage ב-XML.
     */
    suspend fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = context.getSharedPreferences("widget_previews", Context.MODE_PRIVATE)
        val key = "v${BuildConfig.VERSION_CODE}_${context.resources.configuration.locales[0].toLanguageTag()}"
        if (prefs.getBoolean(key, false)) return
        val manager = GlanceAppWidgetManager(context)
        // מגבלת קצב: מסמנים רק כשהשתיים הצליחו, ואחרת מנסים שוב בהפעלה הבאה
        val ok = runCatching {
            val success = GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
            manager.setWidgetPreviews(ShoppingListWidgetReceiver::class) == success &&
                manager.setWidgetPreviews(MealPlanWidgetReceiver::class) == success
        }.getOrDefault(false)
        if (ok) prefs.edit { clear().putBoolean(key, true) }
    }
}
