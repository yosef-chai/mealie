package io.github.yosefchai.mealie.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.yosefchai.mealie.MainActivity
import io.github.yosefchai.mealie.R
import io.github.yosefchai.mealie.update.UpdateInfo

object Notifications {
    private const val CHANNEL_UPDATES = "updates"
    private const val CHANNEL_DOWNLOADS = "downloads"
    private const val ID_UPDATE = 1001
    private var downloadId = 2000

    const val ACTION_SHOW_UPDATE = "io.github.yosefchai.mealie.action.SHOW_UPDATE"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun updateAvailable(context: Context, info: UpdateInfo) {
        val open = PendingIntent.getActivity(
            context,
            ID_UPDATE,
            Intent(context, MainActivity::class.java).setAction(ACTION_SHOW_UPDATE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(
            context,
            ID_UPDATE,
            NotificationCompat.Builder(context, CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_stat_mealie)
                .setContentTitle(context.getString(R.string.update_available_title))
                .setContentText(context.getString(R.string.update_available_body, info.versionName))
                .setContentIntent(open)
                .setAutoCancel(true),
        )
    }

    /** אישור התקנה כשהאפליקציה ברקע. */
    fun installConfirm(context: Context, confirm: Intent) {
        val pending = PendingIntent.getActivity(
            context,
            ID_UPDATE,
            confirm,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(
            context,
            ID_UPDATE,
            NotificationCompat.Builder(context, CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_stat_mealie)
                .setContentTitle(context.getString(R.string.update_ready_title))
                .setContentText(context.getString(R.string.update_ready_body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(pending)
                .setAutoCancel(true),
        )
    }

    fun installFailed(context: Context, message: String?) {
        notify(
            context,
            ID_UPDATE,
            NotificationCompat.Builder(context, CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_stat_mealie)
                .setContentTitle(context.getString(R.string.update_failed_title))
                .setContentText(message ?: context.getString(R.string.update_failed_body))
                .setAutoCancel(true),
        )
    }

    fun cancelUpdate(context: Context) = NotificationManagerCompat.from(context).cancel(ID_UPDATE)

    fun downloadFinished(context: Context, name: String, uri: Uri, mime: String) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pending = PendingIntent.getActivity(
            context,
            downloadId,
            Intent.createChooser(view, name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(
            context,
            downloadId++,
            NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_stat_mealie)
                .setContentTitle(name)
                .setContentText(context.getString(R.string.download_finished))
                .setContentIntent(pending)
                .setAutoCancel(true),
        )
    }

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        // לפני Android 13 אין הרשאת התראות בזמן ריצה.
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
