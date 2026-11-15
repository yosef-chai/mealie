package io.github.yosefchai.mealie.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.yosefchai.mealie.core.Notifications
import java.io.File

/**
 * תוצאת ההתקנה. כשנדרש אישור משתמש: בחזית – פותחים את המסך מיד; ברקע – התראה
 * (אנדרואיד חוסם פתיחת Activity מהרקע, ו-full-screen intent שמור לשיחות ושעון מעורר).
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val apk = intent.getStringExtra(EXTRA_APK)?.let(::File)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (foreground) context.startActivity(confirm) else Notifications.installConfirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                apk?.delete()
                Notifications.cancelUpdate(context)
            }
            else -> {
                apk?.delete()
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) Notifications.installFailed(context, message)
            }
        }
    }

    companion object {
        const val EXTRA_APK = "io.github.yosefchai.mealie.extra.APK"
    }
}
