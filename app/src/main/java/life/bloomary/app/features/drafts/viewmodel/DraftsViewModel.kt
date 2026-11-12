package life.bloomary.app.features.drafts.viewmodel

import android.util.Log
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import life.bloomary.app.features.drafts.api.SyncedDraftsError
import life.bloomary.app.features.drafts.domain.SchedulePolicy
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.features.drafts.model.DraftSchedule
import life.bloomary.app.features.drafts.model.DraftScheduling
import life.bloomary.app.features.drafts.model.DraftStore
import life.bloomary.app.features.drafts.model.DraftSyncStatus
import life.bloomary.app.features.drafts.model.PlanAccess

/**
 * Mirrors ios/Features/Drafts/ViewModel/DraftsViewModel.swift. Scheduling
 * goes through [DraftScheduling] (wired in the app layer) and the session
 * through [DraftStore.sessionCredential], so this slice imports no other feature.
 */
class DraftsViewModel(
    private val store: DraftStore,
    private val scheduling: DraftScheduling? = null,
    private val scope: CoroutineScope,
    val scheduleMaxAheadDays: Int = SchedulePolicy.defaultMaxAheadDays,
) {
    val drafts: StateFlow<List<Draft>> = store.drafts
    val syncStatus: StateFlow<DraftSyncStatus> = store.syncStatus
    val statusMessage: StateFlow<String?> = store.statusMessage
    val scheduledPosts: StateFlow<List<DraftSchedule>> = store.scheduledPosts
    val plan: StateFlow<PlanAccess> = store.plan

    val isOverDraftLimit: Boolean
        get() = store.drafts.value.size > store.maxDrafts

    val overLimitMessage: String
        get() {
            if (store.isLapsed) return "You've reached 5 drafts without a plan."
            val tier = if (store.hasPlanAccess) "Pro" else "free"
            val action = if (store.hasPlanAccess) "Delete a draft to create a new one." else "Delete some to create new ones or upgrade to Pro."
            return "You have ${store.drafts.value.size} drafts ($tier limit: ${store.maxDrafts}). $action"
        }

    /** Scheduling needs a LinkedIn session; signed-out drafts stay local. */
    val canSchedule: Boolean get() = scheduling != null && store.sessionCredential != null

    fun open(draft: Draft): Boolean = store.openDraft(draft)

    fun delete(draft: Draft) = store.deleteDraft(draft)

    fun canEdit(draft: Draft): Boolean = store.canEdit(draft)

    fun retrySync() = store.retrySync()

    fun refreshFromRemote() {
        store.refreshFromRemote()
        refreshSchedules()
    }

    fun scheduledPost(draft: Draft, posts: List<DraftSchedule> = scheduledPosts.value): DraftSchedule? =
        posts.firstOrNull { it.isFor(draft) }

    suspend fun reschedule(draft: Draft, at: Instant, reviewConfirmed: Boolean = false) {
        val service = scheduling ?: throw SyncedDraftsError.Server("Connect LinkedIn first.")
        val (memberID, token) = store.sessionCredential ?: throw SyncedDraftsError.Server("Connect LinkedIn first.")
        if (!SchedulePolicy.isWithinWindow(at, scheduleMaxAheadDays)) {
            throw SyncedDraftsError.Server("Choose a time between five minutes and the scheduling limit.")
        }
        val synced = store.syncDraftForScheduling(draft)
        if (store.sessionCredential != memberID to token) throw SyncedDraftsError.Server("Connect LinkedIn first.")
        val result = service.schedule(synced.serverID, at, reviewConfirmed, memberID, token)
        store.setScheduledPosts(scheduledPosts.value.filterNot { it.isFor(draft) } + result, memberID, token)
    }

    /** IOS-003 / AND-003: removes the server schedule for this draft. */
    suspend fun unschedule(draft: Draft) {
        val service = scheduling ?: throw SyncedDraftsError.Server("Connect LinkedIn first.")
        val (memberID, token) = store.sessionCredential ?: throw SyncedDraftsError.Server("Connect LinkedIn first.")
        service.unschedule(draft.serverID, memberID, token)
        store.setScheduledPosts(scheduledPosts.value.filterNot { it.isFor(draft) }, memberID, token)
    }

    private fun refreshSchedules() {
        val service = scheduling ?: return
        val (memberID, token) = store.sessionCredential ?: return
        val requestedAt = Instant.now()
        scope.launch {
            try {
                val posts = service.scheduledPosts(memberID, token)
                store.setScheduledPosts(posts, memberID, token, requestedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (store.sessionCredential != memberID to token) return@launch
                Log.e("Schedules", "GET /api/linkedin/schedules failed: ${e.javaClass.simpleName}")
                store.setStatusMessage("Schedule refresh failed. Showing the last known status.")
            }
        }
    }

    companion object {
        /** Lead time plus a minute, so a just-valid existing time isn't rejected by the time the member taps Save. */
        val reuseExistingTimeMargin: Duration = SchedulePolicy.minimumLeadTime.plusMinutes(1)
    }
}
