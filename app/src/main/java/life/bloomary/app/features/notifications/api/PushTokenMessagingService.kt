package life.bloomary.app.features.notifications.api

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import life.bloomary.app.R
import life.bloomary.app.application.app.BloomaryApp

/**
 * FCM entry point, the Android counterpart of the APNs callbacks that feed
 * ios/Features/Notifications/API/NotificationTokenService.swift.
 *
 * [onNewToken] mirrors APNs `didRegisterForRemoteNotifications`: the token is
 * stashed and registered with the backend when a LinkedIn session exists
 * (see [NotificationPushRegistrar]).
 *
 * [onMessageReceived] displays server reminders (wiki:
 * daily-posting-workflow-and-notifications.md) on the same channel as local
 * fallback reminders. Full local-vs-server identity-key suppression is a
 * planned follow-up; server reminders are best-effort there too.
 */
class PushTokenMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val container = (application as? BloomaryApp)?.container ?: return
        scope.launch {
            container.notificationPushRegistrar.onNewToken(token, container.linkedInSessionStore.token.value)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: return
        val deeplink = message.data["deeplink"].orEmpty()
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        NotificationReminderService.ensureChannel(this)

        val builder = NotificationCompat.Builder(this, NotificationReminderService.CHANNEL_ID)
            // Full-colour PNG; no monochrome status-bar icon exists in res yet.
            .setSmallIcon(R.drawable.bloomary_app_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (deeplink.isNotEmpty()) {
            val tapIntent = Intent(Intent.ACTION_VIEW, deeplink.toUri())
                .setPackage(packageName)
            val tapPendingIntent = PendingIntent.getActivity(
                this,
                0,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(tapPendingIntent)
        }

        // Server pushes carry the memberID + local_date + action identity key;
        // one notification id per key keeps repeats from stacking.
        val identity = listOfNotNull(message.data["action"], message.data["local_date"])
            .joinToString(".")
            .ifEmpty { message.messageId.orEmpty() }
        try {
            NotificationManagerCompat.from(this).notify(identity.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked.
        }
    }
}
