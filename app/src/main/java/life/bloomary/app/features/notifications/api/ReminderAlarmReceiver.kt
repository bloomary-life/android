package life.bloomary.app.features.notifications.api

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import life.bloomary.app.R

/**
 * Fires the local reminder alarms armed by [NotificationReminderService].
 * Tap opens the app via the `bloomary://editor` deep link; MainActivity
 * deliberately does not navigate on it, mirroring iOS carrying the deeplink
 * in userInfo without ever handling it.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(EXTRA_BODY) ?: return
        val deeplink = intent.getStringExtra(EXTRA_DEEPLINK).orEmpty()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        NotificationReminderService.ensureChannel(context)

        val builder = NotificationCompat.Builder(context, NotificationReminderService.CHANNEL_ID)
            // Full-colour PNG; no monochrome status-bar icon exists in res yet.
            .setSmallIcon(R.drawable.bloomary_app_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (deeplink.isNotEmpty()) {
            val tapIntent = Intent(Intent.ACTION_VIEW, deeplink.toUri())
                .setPackage(context.packageName)
            val tapPendingIntent = PendingIntent.getActivity(
                context,
                0,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(tapPendingIntent)
        }

        val notificationID = intent.action?.hashCode() ?: 0
        try {
            NotificationManagerCompat.from(context).notify(notificationID, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between scheduling and firing.
        }
    }

    companion object {
        const val EXTRA_TITLE = "life.bloomary.app.notifications.extra.TITLE"
        const val EXTRA_BODY = "life.bloomary.app.notifications.extra.BODY"
        const val EXTRA_DEEPLINK = "life.bloomary.app.notifications.extra.DEEPLINK"
    }
}
