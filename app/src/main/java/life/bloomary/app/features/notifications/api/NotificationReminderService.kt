package life.bloomary.app.features.notifications.api

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import life.bloomary.app.features.linkedinauth.model.LinkedInToken
import life.bloomary.app.features.linkedinpost.api.LinkedInPostCredential
import life.bloomary.app.features.notifications.storage.ReminderFlagStore
import life.bloomary.app.features.streak.api.StreakService
import life.bloomary.app.features.streak.model.Streak
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent

/**
 * Port of ios/Features/Notifications/API/NotificationReminderService.swift.
 *
 * UNUserNotificationCenter → AlarmManager (`setExactAndAllowWhileIdle`, inexact
 * `set` fallback when `canScheduleExactAlarms()` is false). UserDefaults flags
 * → [ReminderFlagStore]. Local reminders only; server push token registration
 * lives in [NotificationTokenService]/[NotificationPushRegistrar].
 *
 * Sync triggers mirror iOS: root appear + token change call
 * [syncTodayReminder]; a successful post calls [recordTodayPostAndCancelReminders].
 * Reboot parity (iOS notifications survive reboot) lives in [syncAfterReboot],
 * driven by ReminderBootReceiver.
 */
class NotificationReminderService(
    context: Context,
    private val streakService: StreakService,
    private val flagStore: ReminderFlagStore = ReminderFlagStore(context),
) {
    private val appContext = context.applicationContext

    /**
     * Wired by the app layer (MainActivity) to an ActivityResultLauncher so
     * POST_NOTIFICATIONS is requested at schedule time, mirroring iOS
     * `requestAuthorization(options: [.alert, .sound])`. Null in contexts that
     * cannot prompt (boot receiver) — scheduling then follows the current
     * permission state, like a denied iOS authorization.
     */
    var notificationPermissionRequester: (suspend () -> Boolean)? = null

    suspend fun syncTodayReminder(token: LinkedInToken?) {
        if (token == null || token.accessToken.isEmpty() || token.profile.memberID.isEmpty()) return
        val memberID = token.profile.memberID
        val credential = LinkedInPostCredential(accessToken = token.accessToken, memberID = memberID)
        try {
            val streak = withContext(Dispatchers.IO) { streakService.streak(credential) }
            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val today = localDayString(now, zone)
            val reminderSchedules = pendingReminderSchedules(memberID, today, now, zone)
            cancelAlarms(appContext, listOf(legacyReminderIdentifier(memberID, today)))
            flagStore.setLastSync(memberID, today)
            if (hasPostedToday(streak, memberID, today)) {
                cancelAlarms(appContext, reminderIdentifiers(memberID, today))
                return
            }
            val reminder = TodayReminder(
                title = REMINDER_TITLE,
                body = REMINDER_BODY,
                deeplink = REMINDER_DEEPLINK,
            )
            for (reminderSchedule in reminderSchedules) {
                if (flagStore.isScheduled(reminderSchedule.identifier)) continue
                if (schedule(reminder, reminderSchedule.identifier, reminderSchedule.triggerAtMillis)) {
                    flagStore.setScheduled(reminderSchedule.identifier, true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppTelemetry.track(AppTelemetryEvent.NotificationSyncFailed)
        }
    }

    fun recordTodayPostAndCancelReminders(token: LinkedInToken?) {
        if (token == null || token.profile.memberID.isEmpty()) return
        val memberID = token.profile.memberID
        val today = localDayString(Instant.now(), ZoneId.systemDefault())
        flagStore.setPosted(postedKey(memberID, today), true)
        cancelAlarms(appContext, reminderIdentifiers(memberID, today))
    }

    private fun hasPostedToday(streak: Streak, memberID: String, localDate: String): Boolean =
        streak.lastPostedDay == localDate || flagStore.isPosted(postedKey(memberID, localDate))

    private suspend fun schedule(reminder: TodayReminder, identifier: String, triggerAtMillis: Long): Boolean {
        if (reminder.title.isEmpty() || reminder.body.isEmpty()) return false
        if (!requestAuthorization()) return false
        ensureChannel(appContext)
        scheduleAlarm(appContext, identifier, triggerAtMillis, reminder)
        return true
    }

    /** Mirrors `requestAuthorization`: returns whether notifications may be posted, prompting if possible. */
    private suspend fun requestAuthorization(): Boolean {
        if (NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val requester = notificationPermissionRequester ?: return false
            if (!requester()) return false
        }
        return NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    companion object {
        const val CHANNEL_ID = "bloomary_post_reminders"

        private const val REMINDER_TITLE = "Keep your LinkedIn streak"
        private const val REMINDER_BODY = "You have not posted today."
        private const val REMINDER_DEEPLINK = "bloomary://editor"

        private val ReminderTimes = listOf(
            Triple("0900", 9, 0),
            Triple("1630", 16, 30),
        )

        private val DayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

        /**
         * Same-day future reminder slots. Mirrors the Swift static of the same
         * name; `triggerAtMillis` replaces the carried DateComponents.
         */
        fun pendingReminderSchedules(
            memberID: String,
            localDate: String,
            now: Instant,
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<ReminderSchedule> {
            val localNow = now.atZone(zone)
            val day = localNow.toLocalDate()
            return ReminderTimes.mapNotNull { (suffix, hour, minute) ->
                val fire = day.atTime(hour, minute, 0).atZone(zone)
                if (fire.toInstant() <= now) {
                    null
                } else {
                    ReminderSchedule(
                        identifier = "bloomary.notification.$memberID.$localDate.post_reminder.$suffix",
                        triggerAtMillis = fire.toInstant().toEpochMilli(),
                    )
                }
            }
        }

        private fun reminderIdentifiers(memberID: String, localDate: String): List<String> = listOf(
            legacyReminderIdentifier(memberID, localDate),
            "bloomary.notification.$memberID.$localDate.post_reminder.0900",
            "bloomary.notification.$memberID.$localDate.post_reminder.1630",
        )

        private fun legacyReminderIdentifier(memberID: String, localDate: String): String =
            "bloomary.notification.$memberID.$localDate.post_reminder"

        private fun postedKey(memberID: String, localDate: String): String =
            "bloomary.notification.$memberID.$localDate.posted"

        private fun localDayString(now: Instant, zone: ZoneId): String =
            DayFormatter.format(now.atZone(zone))

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Posting reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        /**
         * Reboot parity: iOS local notifications survive a reboot, AlarmManager
         * alarms do not. Re-runs the sync's scheduling half from the persisted
         * last-sync state (no streak fetch — the receiver cannot reach the
         * session/DI graph, and the persisted memberID+date+flags describe
         * exactly what the last sync armed).
         */
        fun syncAfterReboot(context: Context) {
            val appContext = context.applicationContext
            val store = ReminderFlagStore(appContext)
            val memberID = store.lastSyncMemberID() ?: return
            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val today = localDayString(now, zone)
            if (store.lastSyncDate() != today) return
            cancelAlarms(appContext, listOf(legacyReminderIdentifier(memberID, today)))
            if (store.isPosted(postedKey(memberID, today))) {
                cancelAlarms(appContext, reminderIdentifiers(memberID, today))
                return
            }
            if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return
            ensureChannel(appContext)
            val reminder = TodayReminder(REMINDER_TITLE, REMINDER_BODY, REMINDER_DEEPLINK)
            for (reminderSchedule in pendingReminderSchedules(memberID, today, now, zone)) {
                scheduleAlarm(appContext, reminderSchedule.identifier, reminderSchedule.triggerAtMillis, reminder)
            }
        }

        internal fun scheduleAlarm(
            context: Context,
            identifier: String,
            triggerAtMillis: Long,
            reminder: TodayReminder,
        ) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val pendingIntent = alarmPendingIntent(context, identifier, reminder, noCreate = false) ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                // Exact-alarm permission denied/revoked: inexact fallback,
                // reminders stay local and idempotent.
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }

        internal fun cancelAlarms(context: Context, identifiers: List<String>) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            for (identifier in identifiers) {
                val pendingIntent = alarmPendingIntent(context, identifier, reminder = null, noCreate = true)
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                }
            }
        }

        private fun alarmPendingIntent(
            context: Context,
            identifier: String,
            reminder: TodayReminder?,
            noCreate: Boolean,
        ): PendingIntent? {
            val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
                // The action carries the identifier so each reminder maps to a
                // distinct PendingIntent (and notification id).
                action = identifier
                if (reminder != null) {
                    putExtra(ReminderAlarmReceiver.EXTRA_TITLE, reminder.title)
                    putExtra(ReminderAlarmReceiver.EXTRA_BODY, reminder.body)
                    putExtra(ReminderAlarmReceiver.EXTRA_DEEPLINK, reminder.deeplink)
                }
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or
                (if (noCreate) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT)
            return PendingIntent.getBroadcast(context, 0, intent, flags)
        }
    }
}

data class ReminderSchedule(
    val identifier: String,
    val triggerAtMillis: Long,
)

internal data class TodayReminder(
    val title: String,
    val body: String,
    val deeplink: String,
)
