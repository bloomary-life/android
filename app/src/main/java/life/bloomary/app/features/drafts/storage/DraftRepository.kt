package life.bloomary.app.features.drafts.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import life.bloomary.app.features.drafts.domain.DraftSnapshot
import life.bloomary.app.features.drafts.domain.DraftSyncMutation
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.shared.network.AppJson

/**
 * Mirrors ios/Features/Drafts/Storage/DraftRepository.swift.
 *
 * The iOS repository is synchronous (UserDefaults); the blocking API shape is
 * preserved here so `DraftStore` stays a line-for-line port. DataStore access
 * is bridged with `runBlocking` behind an in-memory cache, matching the small,
 * main-thread reads the iOS app performs.
 */
interface DraftRepository {
    fun loadSnapshot(ownerID: String?): DraftSnapshot
    fun saveSnapshot(snapshot: DraftSnapshot, ownerID: String?)
    fun deleteDrafts(ownerID: String?)

    fun loadSyncMutations(ownerID: String): List<DraftSyncMutation> = emptyList()
    fun saveSyncMutations(mutations: List<DraftSyncMutation>, ownerID: String) {}
    fun deleteSyncMutations(ownerID: String) {}

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
    private val snapshots = mutableMapOf<String, DraftSnapshot>()
    private val queues = mutableMapOf<String, List<DraftSyncMutation>>()

    @Synchronized
    override fun loadSnapshot(ownerID: String?): DraftSnapshot {
        val cacheKey = scopedKey(ownerID)
        snapshots[cacheKey]?.let { return it }
        val snapshot = decodeSnapshot(read(cacheKey), ownerID)
        snapshots[cacheKey] = snapshot
        return snapshot
    }

    private fun decodeSnapshot(raw: String?, ownerID: String?): DraftSnapshot {
        raw ?: return DraftSnapshot.empty(ownerID)
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
            val drafts = AppJson.decodeFromString(ListSerializer(Draft.serializer()), raw)
            return DraftSnapshot(drafts = drafts, ownerID = ownerID)
        } catch (e: SerializationException) {
            // Corrupt cache falls back to empty drafts.
        } catch (e: IllegalArgumentException) {
            // Corrupt cache falls back to empty drafts.
        }
        return DraftSnapshot.empty(ownerID)
    }

    @Synchronized
    override fun saveSnapshot(snapshot: DraftSnapshot, ownerID: String?) {
        val cacheKey = scopedKey(ownerID)
        snapshots[cacheKey] = snapshot
        write(cacheKey, AppJson.encodeToString(DraftSnapshot.serializer(), snapshot))
    }

    @Synchronized
    override fun deleteDrafts(ownerID: String?) {
        val cacheKey = scopedKey(ownerID)
        snapshots[cacheKey] = DraftSnapshot.empty(ownerID)
        write(cacheKey, null)
    }

    @Synchronized
    override fun loadSyncMutations(ownerID: String): List<DraftSyncMutation> {
        val cacheKey = syncQueueKey(ownerID)
        queues[cacheKey]?.let { return it }
        val raw = read(cacheKey) ?: return emptyList()
        val stored = try {
            AppJson.decodeFromString(ListSerializer(DraftSyncMutation.serializer()), raw)
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
        // Queued upserts are stored without image bytes when the cached draft holds them; restore them.
        val drafts = loadSnapshot(ownerID).drafts
        val mutations = stored.map { mutation ->
            val draft = mutation.draft
            if (draft == null || draft.imageData != null) return@map mutation
            val cached = drafts.firstOrNull { it.id == draft.id && it.updatedAt == draft.updatedAt } ?: return@map mutation
            mutation.copy(draft = draft.copy(imageData = cached.imageData))
        }
        queues[cacheKey] = mutations
        return mutations
    }

    @Synchronized
    override fun saveSyncMutations(mutations: List<DraftSyncMutation>, ownerID: String) {
        val cacheKey = syncQueueKey(ownerID)
        queues[cacheKey] = mutations
        val savedDrafts = snapshots[scopedKey(ownerID)]?.drafts.orEmpty()
        // Don't store image bytes twice: drop them when the cached draft already holds the same bytes.
        val lightweight = mutations.map { mutation ->
            val draft = mutation.draft ?: return@map mutation
            val sameInCache = savedDrafts.any {
                it.id == draft.id && it.updatedAt == draft.updatedAt &&
                    (it.imageData?.contentEquals(draft.imageData ?: ByteArray(0)) ?: (draft.imageData == null))
            }
            if (sameInCache) mutation.copy(draft = draft.copy(imageData = null)) else mutation
        }
        write(cacheKey, AppJson.encodeToString(ListSerializer(DraftSyncMutation.serializer()), lightweight))
    }

    @Synchronized
    override fun deleteSyncMutations(ownerID: String) {
        val cacheKey = syncQueueKey(ownerID)
        queues[cacheKey] = emptyList()
        write(cacheKey, null)
    }

    private fun read(key: String): String? =
        runBlocking { dataStore.data.first()[stringPreferencesKey(key)] }

    private fun write(key: String, value: String?) {
        runBlocking {
            dataStore.edit { prefs ->
                if (value == null) prefs.remove(stringPreferencesKey(key)) else prefs[stringPreferencesKey(key)] = value
            }
        }
    }

    private fun scopedKey(ownerID: String?) = "$key.${ownerID ?: "signed-out"}"

    private fun syncQueueKey(ownerID: String) = "$key.syncQueue.$ownerID"
}
