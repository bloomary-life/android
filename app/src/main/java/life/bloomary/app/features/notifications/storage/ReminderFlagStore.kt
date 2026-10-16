package life.bloomary.app.features.notifications.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.reminderDataStore by preferencesDataStore(name = "bloomary_notification_store")

private val KeyLastSyncMemberID = stringPreferencesKey("last_sync_member_id")
private val KeyLastSyncDate = stringPreferencesKey("last_sync_date")

/**
 * UserDefaults stand-in for the reminder flags the iOS service writes:
 * per-identifier scheduled flags, per-day posted flags
 * (`bloomary.notification.{memberID}.{date}.posted`), plus the last-sync
 * memberID/date the boot receiver re-arms from. Blocking reads mirror the
 * synchronous UserDefaults access, bridged like DataStoreDraftRepository.
 */
class ReminderFlagStore(context: Context) {
    private val dataStore = context.applicationContext.reminderDataStore

    fun isScheduled(identifier: String): Boolean = getBool(booleanPreferencesKey(identifier))

    fun setScheduled(identifier: String, value: Boolean) = setBool(booleanPreferencesKey(identifier), value)

    fun isPosted(postedKey: String): Boolean = getBool(booleanPreferencesKey(postedKey))

    fun setPosted(postedKey: String, value: Boolean) = setBool(booleanPreferencesKey(postedKey), value)

    fun lastSyncMemberID(): String? = runBlocking { dataStore.data.first()[KeyLastSyncMemberID] }

    fun lastSyncDate(): String? = runBlocking { dataStore.data.first()[KeyLastSyncDate] }

    fun setLastSync(memberID: String, localDate: String) {
        runBlocking {
            dataStore.edit { prefs ->
                prefs[KeyLastSyncMemberID] = memberID
                prefs[KeyLastSyncDate] = localDate
            }
        }
    }

    private fun getBool(key: Preferences.Key<Boolean>): Boolean =
        runBlocking { dataStore.data.first()[key] ?: false }

    private fun setBool(key: Preferences.Key<Boolean>, value: Boolean) {
        runBlocking {
            dataStore.edit { prefs -> prefs[key] = value }
        }
    }
}
