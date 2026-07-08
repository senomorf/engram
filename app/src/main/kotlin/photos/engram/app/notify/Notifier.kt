package photos.engram.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import photos.engram.app.MainActivity
import photos.engram.app.R

class Notifier(
    private val context: Context,
) {
    fun showDigest(waitingCount: Int) {
        ensureChannel()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled().not()) return
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(EXTRA_OPEN_QUEUE, true)
            }
        val pending =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_DIGEST)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.digest_title))
                .setContentText(context.resources.getQuantityString(R.plurals.digest_body, waitingCount, waitingCount))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
        val granted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            runCatching {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_DIGEST, notification)
            }
        }
    }

    private fun ensureChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_DIGEST,
                context.getString(R.string.digest_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_OPEN_QUEUE = "open_queue"
        private const val CHANNEL_DIGEST = "digest"
        private const val NOTIFICATION_DIGEST = 1
    }
}
