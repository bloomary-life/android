package life.bloomary.app.features.notifications.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms today's reminders after a reboot. iOS local notifications survive
 * reboot on their own; AlarmManager alarms do not, so parity requires an
 * explicit re-sync from persisted state.
 */
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                NotificationReminderService.syncAfterReboot(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
