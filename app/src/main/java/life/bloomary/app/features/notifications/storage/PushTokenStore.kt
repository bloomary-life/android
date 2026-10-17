package life.bloomary.app.features.notifications.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.pushTokenDataStore by preferencesDataStore(name = "bloomary_push_token_store")

/**
 * Persistence for the FCM token handshake. Blocking API mirrors
 * DataStore (runBlocking bridge over
 * DataStore, the UserDefaults stand-in).
 */
interface PushTokenStore {
    /** Latest token FCM handed us via `onNewToken`, before a session exists. */
    fun latestToken(): String?
    fun setLatestToken(token: String)

    /** Token already registered with the backend for [memberID]; dedupes repeat PUTs. */
    fun registeredToken(memberID: String): String?
    fun setRegisteredToken(memberID: String, token: String)
}

class DataStorePushTokenStore(
    context: Context,
) : PushTokenStore {
    private val dataStore = context.applicationContext.pushTokenDataStore

    override fun latestToken(): String? = runBlocking { dataStore.data.first()[KeyLatestToken] }

    override fun setLatestToken(token: String) {
        runBlocking {
            dataStore.edit { prefs -> prefs[KeyLatestToken] = token }
        }
    }

    override fun registeredToken(memberID: String): String? =
        runBlocking { dataStore.data.first()[registeredKey(memberID)] }

    override fun setRegisteredToken(memberID: String, token: String) {
        runBlocking {
            dataStore.edit { prefs -> prefs[registeredKey(memberID)] = token }
        }
    }

    private fun registeredKey(memberID: String) = stringPreferencesKey("registered_token.$memberID")

    private companion object {
        val KeyLatestToken = stringPreferencesKey("latest_token")
    }
}
