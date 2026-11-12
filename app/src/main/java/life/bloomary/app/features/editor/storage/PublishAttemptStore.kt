package life.bloomary.app.features.editor.storage

import android.content.Context

/**
 * Remembers the fingerprint of the last submitted post per member, so a
 * resubmit after a lost response asks the member to check LinkedIn first.
 * Mirrors the `bloomary.publish-attempt.*` UserDefaults key in EditorViewModel.swift.
 */
interface PublishAttemptStore {
    fun lastFingerprint(memberID: String): String?
    fun setLastFingerprint(memberID: String, fingerprint: String)
    fun clear(memberID: String)
}

class InMemoryPublishAttemptStore : PublishAttemptStore {
    private val values = mutableMapOf<String, String>()
    override fun lastFingerprint(memberID: String) = values[memberID]
    override fun setLastFingerprint(memberID: String, fingerprint: String) {
        values[memberID] = fingerprint
    }
    override fun clear(memberID: String) {
        values.remove(memberID)
    }
}

class SharedPreferencesPublishAttemptStore(context: Context, private val namespace: String) : PublishAttemptStore {
    private val prefs = context.applicationContext.getSharedPreferences("bloomary_publish_attempts", Context.MODE_PRIVATE)
    private fun key(memberID: String) = "bloomary.publish-attempt.$namespace.$memberID"
    override fun lastFingerprint(memberID: String): String? = prefs.getString(key(memberID), null)
    override fun setLastFingerprint(memberID: String, fingerprint: String) {
        prefs.edit().putString(key(memberID), fingerprint).apply()
    }
    override fun clear(memberID: String) {
        prefs.edit().remove(key(memberID)).apply()
    }
}
