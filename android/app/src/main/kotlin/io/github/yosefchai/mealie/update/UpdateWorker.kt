package io.github.yosefchai.mealie.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.yosefchai.mealie.core.AppGraph
import io.github.yosefchai.mealie.core.Notifications
import io.github.yosefchai.mealie.core.UpdateMode
import java.util.concurrent.TimeUnit

/** בדיקת עדכון ברקע כל 12 שעות. במצב אוטומטי מוריד, מאמת ומתקין; אחרת מציג התראה. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = AppGraph.settings.current()
        if (settings.updateMode == UpdateMode.OFF) return Result.success()
        val info = when (val r = UpdateChecker().check()) {
            is CheckResult.Available -> r.info
            CheckResult.UpToDate -> return Result.success()
            is CheckResult.Error -> return Result.retry()
        }
        if (settings.skippedUpdateTag == info.tag) return Result.success()

        val installer = ApkInstaller(applicationContext)
        if (settings.updateMode == UpdateMode.NOTIFY || !installer.canInstall()) {
            Notifications.updateAvailable(applicationContext, info)
            return Result.success()
        }
        return runCatching {
            installer.install(installer.download(info))
            Result.success()
        }.getOrElse {
            installer.cleanup()
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "update-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
