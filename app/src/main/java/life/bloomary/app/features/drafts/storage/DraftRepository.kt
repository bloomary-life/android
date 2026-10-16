package life.bloomary.app.features.drafts.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import life.bloomary.app.features.drafts.domain.DraftSnapshot
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.shared.network.AppJson

/**
 * Mirrors ios/Features/Drafts/Storage/DraftRepository.swift.
 *
 * The iOS repository is synchronous (UserDefaults); the blocking API shape is
 * preserved here so `DraftStore` stays a line-for-line port. DataStore access
 * is bridged with `runBlocking`, matching the small, main-thread reads the
 * iOS app performs.
 */
interface DraftRepository {
    fun loadSnapshot(ownerID: String?): DraftSnapshot
    fun saveSnapshot(snapshot: DraftSnapshot, ownerID: String?)
    fun deleteDrafts(ownerID: String?)

    fun loadDrafts(ownerID: String?): List<Draft> = loadSnapshot(ownerID).drafts

    fun saveDrafts(drafts: List<Draft>, ownerID: String?) {
        val snapshot = loadSnapshot(ownerID).copy(drafts = drafts, ownerID = ownerID)
        saveSnapshot(snapshot, ownerID)
    }
}

private val Context.draftDataStore by preferencesDataStore(name = "bloomary_drafts_store")

class DataStoreDraftRepository(
    context: Context,
    private val key: String,
) : DraftRepository {
    private val dataStore = context.applicationContext.draftDataStore

    override fun loadSnapshot(ownerID: String?): DraftSnapshot {
        val raw = runBlocking { dataStore.data.first()[scopedKey(ownerID)] } ?: return DraftSnapshot.empty(ownerID)
        // Current format: versioned snapshot envelope.
        try {
            val snapshot = AppJson.decodeFromString(DraftSnapshot.serializer(), raw)
            if (snapshot.schemaVersion <= DraftSnapshot.currentSchemaVersion) {
                return snapshot
            }
        } catch (e: SerializationException) {
            // Fall through to the migration path.
        } catch (e: IllegalArgumentException) {
            // Fall through to the migration path.
        }
        // Migration point: pre-snapshot payloads were a bare [Draft] array.
        try {
            val drafts = AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Draft.serializer()), raw)
            return DraftSnapshot(drafts = drafts, ownerID = ownerID)
        } catch (e: SerializationException) {
            // Corrupt cache falls back to empty drafts.
        } catch (e: IllegalArgumentException) {
            // Corrupt cache falls back to empty drafts.
        }
        return DraftSnapshot.empty(ownerID)
    }

    override fun saveSnapshot(snapshot: DraftSnapshot, ownerID: String?) {
        val encoded = AppJson.encodeToString(DraftSnapshot.serializer(), snapshot)
        runBlocking {
            dataStore.edit { prefs -> prefs[scopedKey(ownerID)] = encoded }
        }
    }

    override fun deleteDrafts(ownerID: String?) {
        runBlocking {
            dataStore.edit { prefs -> prefs.remove(scopedKey(ownerID)) }
        }
    }

    private fun scopedKey(ownerID: String?) =
        stringPreferencesKey("${key}.${ownerID ?: "signed-out"}")
}
