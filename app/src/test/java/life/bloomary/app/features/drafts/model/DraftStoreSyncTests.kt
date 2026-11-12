package life.bloomary.app.features.drafts.model

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.bloomary.app.features.drafts.api.DraftSyncing
import life.bloomary.app.features.drafts.api.SyncedDraftsError
import life.bloomary.app.features.drafts.domain.DraftSnapshot
import life.bloomary.app.features.drafts.domain.DraftSyncMutation
import life.bloomary.app.features.drafts.storage.DraftRepository
import life.bloomary.app.features.drafts.storage.SyncSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
/** Store coroutines run in `backgroundScope`, which `advanceUntilIdle` skips; `runCurrent` drives them. */
class DraftStoreSyncTests {

    private class MemoryRepository : DraftRepository {
        val snapshots = mutableMapOf<String?, DraftSnapshot>()
        val queues = mutableMapOf<String, List<DraftSyncMutation>>()
        override fun loadSnapshot(ownerID: String?) = snapshots[ownerID] ?: DraftSnapshot.empty(ownerID)
        override fun saveSnapshot(snapshot: DraftSnapshot, ownerID: String?) { snapshots[ownerID] = snapshot }
        override fun deleteDrafts(ownerID: String?) { snapshots.remove(ownerID) }
        override fun loadSyncMutations(ownerID: String) = queues[ownerID].orEmpty()
        override fun saveSyncMutations(mutations: List<DraftSyncMutation>, ownerID: String) { queues[ownerID] = mutations }
        override fun deleteSyncMutations(ownerID: String) { queues.remove(ownerID) }
    }

    private class FakeSync : DraftSyncing {
        val server = mutableMapOf<UUID, Draft>()
        val upserts = mutableListOf<Draft>()
        var failWith: Exception? = null
        override suspend fun listDrafts(memberID: String, accessToken: String): List<Draft> {
            failWith?.let { throw it }
            return server.values.toList()
        }
        override suspend fun upsertDraft(draft: Draft, memberID: String, accessToken: String): Draft {
            failWith?.let { throw it }
            upserts += draft
            server[draft.id] = draft
            return draft
        }
        override suspend fun deleteDraft(serverID: String, memberID: String, accessToken: String) {
            failWith?.let { throw it }
            server.remove(UUID.fromString(serverID))
        }
    }

    private fun TestScope.store(repo: DraftRepository, sync: DraftSyncing) =
        DraftStore(repo, sync, SyncSettingsStore(), backgroundScope, telemetry = { _, _ -> }, periodicSyncIntervalMs = 0)

    // Verifies: AND-001
    @Test
    fun savedDraftUploadsAndRemoteDraftsMergeIn() = runTest {
        val repo = MemoryRepository()
        val sync = FakeSync()
        val remote = Draft(body = "from the web", updatedAt = Instant.parse("2026-11-10T09:00:00Z"))
        sync.server[remote.id] = remote
        val store = store(repo, sync)

        store.setSession("member-1", "token")
        runCurrent()
        assertEquals(listOf("from the web"), store.drafts.value.map { it.body })
        assertEquals(DraftSyncStatus.Synced, store.syncStatus.value)

        store.setEditorText("  written on Android  ")
        assertTrue(store.saveCurrentDraft())
        runCurrent()

        assertEquals("written on Android", sync.upserts.last().body)
        assertTrue(repo.loadSyncMutations("member-1").isEmpty())
        assertEquals(DraftSyncStatus.Synced, store.syncStatus.value)
    }

    // Verifies: AND-001
    @Test
    fun reduceDataPausesSync() = runTest {
        val sync = FakeSync()
        val settings = SyncSettingsStore()
        settings.setDataReductionEnabled(true)
        val store = DraftStore(MemoryRepository(), sync, settings, backgroundScope, telemetry = { _, _ -> }, periodicSyncIntervalMs = 0)
        store.setSession("member-1", "token")
        store.setEditorText("offline")
        store.saveCurrentDraft()
        runCurrent()

        assertTrue(sync.upserts.isEmpty())
        assertEquals(DraftSyncStatus.Disabled, store.syncStatus.value)
    }

    // Verifies: AND-005
    @Test
    fun rejectedTokenStopsSyncAndKeepsChangeQueued() = runTest {
        val repo = MemoryRepository()
        val sync = FakeSync()
        val store = store(repo, sync)
        var expiredCalls = 0
        store.onAuthorizationExpired = { expiredCalls += 1 }
        store.setSession("member-1", "token")
        runCurrent()

        sync.failWith = SyncedDraftsError.AuthorizationExpired
        store.setEditorText("needs reconnect")
        store.saveCurrentDraft()
        runCurrent()

        assertEquals(1, expiredCalls)
        val queued = repo.loadSyncMutations("member-1").single()
        assertEquals(0, queued.attemptCount) // not counted as a failure, so no retry loop
    }

    // Verifies: AND-004
    @Test
    fun whitespaceOnlyEditIsNotAnUnsavedChange() = runTest {
        val store = store(MemoryRepository(), FakeSync())
        store.setEditorText("Hello")
        store.saveCurrentDraft()

        store.setEditorText("  Hello \n")
        assertFalse(store.hasUnsavedChanges)
        store.setEditorText("Hello!")
        assertTrue(store.hasUnsavedChanges)
    }

    // Verifies: AND-004
    @Test
    fun openingAnotherDraftWithUnsavedChangesAsksFirst() = runTest {
        val store = store(MemoryRepository(), FakeSync())
        store.setEditorText("first")
        store.saveCurrentDraft()
        val first = store.drafts.value.single()
        store.startNewPost()
        store.setEditorText("unsaved")

        assertFalse(store.openDraft(first))
        assertEquals(first, store.pendingDraft.value)
        store.confirmOpenDraft()
        assertEquals("first", store.editorText.value)
    }

    // Verifies: AND-007
    @Test
    fun lapsedDraftsAreReadOnly() = runTest {
        val store = store(MemoryRepository(), FakeSync())
        store.setEditorText("kept")
        store.saveCurrentDraft()
        store.setPlan(PlanAccess(tier = PlanAccess.Tier.Lapsed))
        val draft = store.drafts.value.single()

        store.deleteDraft(draft)
        assertEquals(listOf(draft), store.drafts.value)
        assertEquals(DraftStore.LAPSED_FROZEN_MESSAGE, store.statusMessage.value)

        store.setEditorText("kept, edited")
        assertFalse(store.saveCurrentDraft())
        assertEquals("kept", store.drafts.value.single().body)
    }
}
