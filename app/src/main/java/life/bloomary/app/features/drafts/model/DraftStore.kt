package life.bloomary.app.features.drafts.model

import android.util.Log
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import life.bloomary.app.features.drafts.api.DraftSyncing
import life.bloomary.app.features.drafts.api.SyncedDraftsError
import life.bloomary.app.features.drafts.domain.DraftPolicy
import life.bloomary.app.features.drafts.domain.DraftSnapshot
import life.bloomary.app.features.drafts.domain.DraftSyncMutation
import life.bloomary.app.features.drafts.domain.DraftSyncOperation
import life.bloomary.app.features.drafts.domain.DraftSyncPolicy
import life.bloomary.app.features.drafts.storage.DraftRepository
import life.bloomary.app.features.drafts.storage.SyncSettingsStore
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.observability.PerformanceSignposts

enum class DraftSyncStatus(val message: String) {
    LocalOnly("Saved locally"),
    Pending("Sync pending"),
    Synced("Synced"),
    Failed("Sync failed. Retry to resume."),
    Disabled("Sync disabled"),
}

/**
 * Mirrors ios/Features/Drafts/Model/DraftStore.swift.
 *
 * `@Published` properties map to [StateFlow]. Every public call and every
 * coroutine runs on [scope] (main dispatcher in the app), matching the iOS
 * `@MainActor` store, so the plain `var`s need no locking.
 *
 * The session arrives as plain member ID + access token ([setSession]) and the
 * plan as [PlanAccess], so this slice imports no other feature.
 */
class DraftStore(
    private val draftRepository: DraftRepository,
    private val syncService: DraftSyncing? = null,
    private val syncSettingsStore: SyncSettingsStore = SyncSettingsStore(),
    private val scope: CoroutineScope,
    private val telemetry: (AppTelemetryEvent, Map<String, String>) -> Unit = { event, params ->
        AppTelemetry.track(event, params)
    },
    /** 0 disables the 30 s background refresh (tests). */
    private val periodicSyncIntervalMs: Long = 30_000,
) {
    private val _editorText = MutableStateFlow("")
    val editorText: StateFlow<String> = _editorText.asStateFlow()

    private val _editorImageData = MutableStateFlow<ByteArray?>(null)
    val editorImageData: StateFlow<ByteArray?> = _editorImageData.asStateFlow()

    private val _editorImageFilename = MutableStateFlow<String?>(null)
    val editorImageFilename: StateFlow<String?> = _editorImageFilename.asStateFlow()

    private val _editorImageMimeType = MutableStateFlow<String?>(null)
    val editorImageMimeType: StateFlow<String?> = _editorImageMimeType.asStateFlow()

    private val _drafts = MutableStateFlow<List<Draft>>(emptyList())
    val drafts: StateFlow<List<Draft>> = _drafts.asStateFlow()

    private val _scheduledPosts = MutableStateFlow<List<DraftSchedule>>(emptyList())
    val scheduledPosts: StateFlow<List<DraftSchedule>> = _scheduledPosts.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _syncStatus = MutableStateFlow(DraftSyncStatus.LocalOnly)
    val syncStatus: StateFlow<DraftSyncStatus> = _syncStatus.asStateFlow()

    private val _plan = MutableStateFlow(PlanAccess())
    val plan: StateFlow<PlanAccess> = _plan.asStateFlow()

    private val _editingDraftID = MutableStateFlow<UUID?>(null)
    val editingDraftID: StateFlow<UUID?> = _editingDraftID.asStateFlow()

    /** Set when opening a draft would discard unsaved editor changes; the UI asks first. */
    private val _pendingDraft = MutableStateFlow<Draft?>(null)
    val pendingDraft: StateFlow<Draft?> = _pendingDraft.asStateFlow()

    /** Called when the server rejects the LinkedIn token (HTTP 401): sync stops and the member is asked to reconnect. */
    var onAuthorizationExpired: (() -> Unit)? = null

    val hasPlanAccess: Boolean get() = _plan.value.hasPlanAccess
    val isLapsed: Boolean get() = _plan.value.isLapsed
    val maxDrafts: Int get() = _plan.value.maxDrafts
    val isDraftLimitReached: Boolean get() = _drafts.value.size >= maxDrafts
    val isEditingExistingDraft: Boolean get() = _editingDraftID.value != null

    /** Member ID and access token of the current session, for callers that talk to the API on the store's behalf. */
    val sessionCredential: Pair<String, String>?
        get() {
            val owner = ownerID ?: return null
            val token = accessToken ?: return null
            return owner to token
        }

    data class EditorSnapshot(
        val ownerID: String?,
        val draftID: UUID?,
        val text: String,
        val imageData: ByteArray?,
        val imageFilename: String?,
        val imageMimeType: String?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (other !is EditorSnapshot) return false
            return ownerID == other.ownerID && draftID == other.draftID && text == other.text &&
                sameBytes(imageData, other.imageData) &&
                imageFilename == other.imageFilename && imageMimeType == other.imageMimeType
        }

        override fun hashCode(): Int = listOf(ownerID, draftID, text, imageFilename, imageMimeType).hashCode() * 31 +
            (imageData?.contentHashCode() ?: 0)
    }

    val editorSnapshot: EditorSnapshot
        get() = EditorSnapshot(
            ownerID = ownerID,
            draftID = _editingDraftID.value,
            text = _editorText.value,
            imageData = _editorImageData.value,
            imageFilename = _editorImageFilename.value,
            imageMimeType = _editorImageMimeType.value,
        )

    /** Whitespace-only edits at either end don't count (IOS-004 / AND-004). */
    val hasUnsavedChanges: Boolean
        get() {
            val body = DraftPolicy.trimmedBody(_editorText.value)
            val draft = _drafts.value.firstOrNull { it.id == _editingDraftID.value }
                ?: return body.isNotEmpty() || _editorImageData.value != null
            return draft.body != body || !sameBytes(draft.imageData, _editorImageData.value)
        }

    private var ownerID: String? = null
    private var accessToken: String? = null
    private var editorsByOwner = mutableMapOf<String, EditorSnapshot>()
    private var isDrainingSyncQueue = false
    private var syncGeneration = 0
    private var accountWorkSuspended = false
    private var fetchJob: Job? = null
    private var drainJob: Job? = null
    private var periodicJob: Job? = null
    private var schedulingUpload: Deferred<Draft>? = null
    private var lastScheduleResponseStartedAt: Instant = Instant.MIN
    private var localRevision = 0
    private var syncedIDs: MutableSet<UUID> = mutableSetOf()
    private var lastSuccessfulSyncAt: Instant? = null
    private var lastRemoteSyncAt: Instant = Instant.EPOCH

    private val isDraftSyncEnabled: Boolean
        get() = !accountWorkSuspended && !syncSettingsStore.isDataReductionEnabled.value

    init {
        val snapshot = PerformanceSignposts.draftCacheLoad { draftRepository.loadSnapshot(ownerID = null) }
        _drafts.value = snapshot.drafts
        lastSuccessfulSyncAt = snapshot.lastSuccessfulSyncAt
        scope.launch {
            syncSettingsStore.isDataReductionEnabled.drop(1).collect {
                syncGeneration += 1
                if (isDraftSyncEnabled) drainSyncQueue(thenFetchRemote = true) else _syncStatus.value = DraftSyncStatus.Disabled
            }
        }
    }

    fun setEditorText(text: String) {
        _editorText.value = text
    }

    fun setEditorImage(data: ByteArray?, filename: String?, mimeType: String?) {
        _editorImageData.value = data
        _editorImageFilename.value = filename
        _editorImageMimeType.value = mimeType
    }

    fun setStatusMessage(message: String?) {
        _statusMessage.value = message
    }

    /**
     * Switches the signed-in member. On first sign-in the signed-out drafts
     * merge into the account; each owner keeps its own unsaved editor.
     */
    fun setSession(memberID: String?, accessToken: String?) {
        if (ownerID == memberID && this.accessToken == accessToken) return
        syncGeneration += 1
        accountWorkSuspended = false
        val previousOwnerID = ownerID
        editorsByOwner[previousOwnerID ?: SIGNED_OUT] = editorSnapshot
        ownerID = memberID
        this.accessToken = accessToken
        localRevision += 1
        _scheduledPosts.value = emptyList()
        lastScheduleResponseStartedAt = Instant.MIN
        _plan.value = PlanAccess()
        val snapshot = PerformanceSignposts.draftCacheLoad { draftRepository.loadSnapshot(ownerID = memberID) }
        syncedIDs = (
            snapshot.syncedIDs ?: snapshot.drafts
                .filter { it.updatedAt <= (snapshot.lastSuccessfulSyncAt ?: Instant.MIN) }
                .map { it.id }
                .toSet()
            ).toMutableSet()
        lastSuccessfulSyncAt = snapshot.lastSuccessfulSyncAt
        if (previousOwnerID == null && memberID != null) {
            val signedOut = draftRepository.loadSnapshot(ownerID = null)
            _drafts.value = DraftPolicy.merge(local = snapshot.drafts, remote = signedOut.drafts)
            persistDrafts(memberID)
            draftRepository.deleteDrafts(ownerID = null)
        } else {
            _drafts.value = snapshot.drafts
        }
        restoreEditor(previousOwnerID)
        drainSyncQueue(thenFetchRemote = true)
        updatePeriodicSync()
    }

    private fun restoreEditor(previousOwnerID: String?) {
        val previous = editorsByOwner[previousOwnerID ?: SIGNED_OUT]
        val saved = editorsByOwner[ownerID ?: SIGNED_OUT]
            ?: if (previousOwnerID == null && ownerID != null) previous else null
        startNewPost()
        saved ?: return
        _editorText.value = saved.text
        _editingDraftID.value = saved.draftID
        _editorImageData.value = saved.imageData
        _editorImageFilename.value = saved.imageFilename
        _editorImageMimeType.value = saved.imageMimeType
    }

    /** Accepts a schedule list only for the current session and only if no newer response already landed. */
    fun setScheduledPosts(
        posts: List<DraftSchedule>,
        memberID: String,
        accessToken: String,
        requestedAt: Instant = Instant.now(),
    ): Boolean {
        if (ownerID != memberID || this.accessToken != accessToken || requestedAt < lastScheduleResponseStartedAt) return false
        lastScheduleResponseStartedAt = requestedAt
        _scheduledPosts.value = posts
        return true
    }

    fun startNewPost() {
        _editorText.value = ""
        _editorImageData.value = null
        _editorImageFilename.value = null
        _editorImageMimeType.value = null
        _editingDraftID.value = null
        _statusMessage.value = null
    }

    fun saveCurrentDraft(): Boolean {
        val body = DraftPolicy.trimmedBody(_editorText.value)
        val plan = _plan.value
        if (body.codePointCount(0, body.length) > plan.maxDraftBodyRunes) {
            _statusMessage.value = "Draft exceeds the ${plan.maxDraftBodyRunes} character sync limit. Shorten it before saving."
            return false
        }
        if ((_editorImageData.value?.size ?: 0) > plan.maxImageBytes) {
            _statusMessage.value = "Image exceeds the server upload limit (${plan.maxImageBytes} bytes). Replace it before saving."
            return false
        }
        if (body.isEmpty() && _editorImageData.value == null) {
            _statusMessage.value = "Draft is empty"
            return false
        }

        val result = PerformanceSignposts.measure("DraftSave") {
            DraftPolicy.save(
                drafts = _drafts.value,
                editingDraftID = _editingDraftID.value,
                body = body,
                maxDrafts = maxDrafts,
                imageData = _editorImageData.value,
                imageFilename = _editorImageFilename.value,
                imageMimeType = _editorImageMimeType.value,
            )
        }
        if (result.limitReached) {
            _statusMessage.value = when {
                isLapsed -> "You've reached 5 drafts without a plan."
                hasPlanAccess -> "Draft limit reached. Delete a draft to create a new one."
                else -> "Draft limit reached. Delete a draft or upgrade to Pro."
            }
            return false
        }
        if (_editingDraftID.value != null && result.changedDraft != null && isLapsed) {
            _statusMessage.value = LAPSED_FROZEN_MESSAGE
            return false
        }
        _drafts.value = result.drafts
        _editingDraftID.value = result.editingDraftID
        val changed = result.changedDraft
        if (changed == null) {
            _statusMessage.value = "Draft saved"
            return true
        }
        persistDrafts(ownerID)
        localRevision += 1
        enqueueSync(DraftSyncOperation.upsert, changed.id, changed)
        telemetry(AppTelemetryEvent.DraftSaved, emptyMap())
        _statusMessage.value = if (isLapsed) "Saved. $LAPSED_FROZEN_MESSAGE" else "Draft saved"
        return true
    }

    /** Saves the open editor and uploads it now (even with sync off) so the server can publish it later. */
    suspend fun syncCurrentDraftForScheduling(): Draft {
        if (accountWorkSuspended) throw SyncedDraftsError.Server("Connect LinkedIn first.")
        if (schedulingUpload != null) throw SyncedDraftsError.Server("A scheduling upload is already in progress.")
        if (!saveCurrentDraft()) throw SyncedDraftsError.Server(_statusMessage.value ?: "Draft could not be saved.")
        val owner = ownerID
        val token = accessToken
        val service = syncService
        val draft = _drafts.value.firstOrNull { it.id == _editingDraftID.value }
        if (owner == null || token == null || service == null || draft == null) {
            throw SyncedDraftsError.Server("Save and sync this draft before scheduling.")
        }
        val revision = localRevision
        val generation = syncGeneration
        val editor = editorSnapshot
        _statusMessage.value = if (isDraftSyncEnabled) "Uploading draft for scheduling…" else "Scheduling requires uploading this draft even with sync off."
        val synced = upload(service, draft, owner, token)
        val index = _drafts.value.indexOfFirst { it == draft }
        if (ownerID != owner || accessToken != token || localRevision != revision ||
            syncGeneration != generation || editorSnapshot != editor || index < 0
        ) {
            throw SyncedDraftsError.Server("Draft or account changed. Schedule the current draft again.")
        }
        replaceSynced(index, synced, owner)
        return synced
    }

    suspend fun syncDraftForScheduling(draft: Draft): Draft {
        if (_editingDraftID.value == draft.id) return syncCurrentDraftForScheduling()
        if (schedulingUpload != null) throw SyncedDraftsError.Server("A scheduling upload is already in progress.")
        val owner = ownerID
        val token = accessToken
        val service = syncService
        val saved = _drafts.value.firstOrNull { it.id == draft.id }
        if (accountWorkSuspended || owner == null || token == null || service == null || saved == null) {
            throw SyncedDraftsError.Server("Connect LinkedIn first.")
        }
        val generation = syncGeneration
        val synced = upload(service, saved, owner, token)
        val index = _drafts.value.indexOfFirst { it == saved }
        if (ownerID != owner || accessToken != token || syncGeneration != generation || index < 0) {
            throw SyncedDraftsError.Server("Draft or account changed. Schedule again.")
        }
        replaceSynced(index, synced, owner)
        return synced
    }

    private suspend fun upload(service: DraftSyncing, draft: Draft, owner: String, token: String): Draft {
        val job = scope.async { service.upsertDraft(draft, owner, token) }
        schedulingUpload = job
        try {
            return job.await()
        } finally {
            schedulingUpload = null
        }
    }

    private fun replaceSynced(index: Int, synced: Draft, owner: String) {
        _drafts.value = _drafts.value.toMutableList().also { it[index] = synced }
        syncedIDs.add(synced.id)
        persistDrafts(owner)
    }

    fun saveOpenedDraftAndClearEditor() {
        if (_editingDraftID.value != null && !saveCurrentDraft()) return
        startNewPost()
    }

    /** Returns false when unsaved changes would be lost; [pendingDraft] then holds the draft until the member confirms. */
    fun openDraft(draft: Draft): Boolean {
        if (_editingDraftID.value == draft.id) return true
        if (hasUnsavedChanges) {
            _pendingDraft.value = draft
            return false
        }
        applyDraft(draft)
        return true
    }

    fun confirmOpenDraft() {
        val draft = _pendingDraft.value ?: return
        applyDraft(draft)
        _pendingDraft.value = null
    }

    fun cancelOpenDraft() {
        _pendingDraft.value = null
    }

    private fun applyDraft(draft: Draft) {
        _editorText.value = draft.body
        _editorImageData.value = draft.imageData
        _editorImageFilename.value = draft.imageFilename
        _editorImageMimeType.value = draft.imageMimeType
        _editingDraftID.value = draft.id
        _statusMessage.value = if (draft.remoteImage != null && draft.imageData == null) "Image unavailable. Refresh to retry downloading it." else null
    }

    fun deleteDraft(draft: Draft) {
        if (!canEdit(draft)) {
            _statusMessage.value = LAPSED_FROZEN_MESSAGE
            return
        }
        _drafts.value = DraftPolicy.delete(_drafts.value, draft.id)
        if (_editingDraftID.value == draft.id) {
            _editingDraftID.value = null
            setEditorImage(null, null, null)
        }
        persistDrafts(ownerID)
        localRevision += 1
        enqueueSync(DraftSyncOperation.delete, draft.id, draft)
    }

    /** After a successful publish: drop the published draft and clear the editor if it still shows that submission. */
    fun completePublishedDraft(snapshot: EditorSnapshot, savedDraft: Draft?) {
        if (ownerID != snapshot.ownerID) return
        val stillEditingSubmission = editorSnapshot == snapshot
        if (savedDraft != null && _drafts.value.contains(savedDraft)) {
            _drafts.value = DraftPolicy.delete(_drafts.value, savedDraft.id)
            persistDrafts(ownerID)
            localRevision += 1
            enqueueSync(DraftSyncOperation.delete, savedDraft.id, savedDraft)
            if (_editingDraftID.value == savedDraft.id) _editingDraftID.value = null
        }
        if (stillEditingSubmission) startNewPost()
    }

    suspend fun suspendAccountWork() {
        accountWorkSuspended = true
        syncGeneration += 1
        stopPeriodicSync()
        val jobs = listOfNotNull(fetchJob, drainJob, schedulingUpload)
        jobs.forEach { it.cancel() }
        jobs.forEach { runCatching { it.join() } }
    }

    fun resumeAccountWork() {
        accountWorkSuspended = false
        updatePeriodicSync()
        drainSyncQueue(thenFetchRemote = true)
    }

    fun deleteAccountData(ownerID: String?) {
        accountWorkSuspended = true
        syncGeneration += 1
        accessToken = null
        stopPeriodicSync()
        if (ownerID != null) {
            draftRepository.deleteDrafts(ownerID = ownerID)
            draftRepository.deleteSyncMutations(ownerID = ownerID)
        }
        draftRepository.deleteDrafts(ownerID = null)
        editorsByOwner.clear()
        syncSettingsStore.setDataReductionEnabled(false)
        _plan.value = PlanAccess()
        _scheduledPosts.value = emptyList()
        this.ownerID = null
        _drafts.value = emptyList()
        _syncStatus.value = DraftSyncStatus.LocalOnly
        localRevision += 1
        startNewPost()
    }

    fun setPlan(plan: PlanAccess) {
        _plan.value = plan
    }

    /** `lapsed` drafts are read-only; a new one can still be saved while under the limit. */
    fun canEdit(@Suppress("UNUSED_PARAMETER") draft: Draft): Boolean = !isLapsed

    /** Pushes queued changes, then merges the server list. Throttled to once per 2 s. */
    fun refreshFromRemote() {
        val now = Instant.now()
        if (!isDraftSyncEnabled || Duration.between(lastRemoteSyncAt, now) < Duration.ofSeconds(2)) return
        lastRemoteSyncAt = now
        drainSyncQueue(thenFetchRemote = true)
    }

    fun retrySync() {
        rearmParkedSyncMutations()
        drainSyncQueue(thenFetchRemote = true)
    }

    private fun rearmParkedSyncMutations() {
        val owner = ownerID ?: return
        val queue = draftRepository.loadSyncMutations(owner)
        val rearmed = DraftSyncPolicy.resetParked(queue)
        if (rearmed != queue) draftRepository.saveSyncMutations(rearmed, owner)
    }

    private fun updatePeriodicSync() {
        if (ownerID != null && accessToken != null) startPeriodicSync() else stopPeriodicSync()
    }

    private fun startPeriodicSync() {
        if (periodicSyncIntervalMs <= 0 || periodicJob != null) return
        periodicJob = scope.launch {
            while (isActive) {
                delay(periodicSyncIntervalMs)
                refreshFromRemote()
            }
        }
    }

    private fun stopPeriodicSync() {
        periodicJob?.cancel()
        periodicJob = null
    }

    private fun persistDrafts(ownerID: String?) {
        PerformanceSignposts.measure("DraftPersist") {
            draftRepository.saveSnapshot(
                DraftSnapshot(
                    drafts = _drafts.value,
                    ownerID = ownerID,
                    lastSuccessfulSyncAt = lastSuccessfulSyncAt,
                    syncedIDs = syncedIDs.toSet(),
                ),
                ownerID = ownerID,
            )
        }
    }

    private fun isCurrent(owner: String, token: String, generation: Int): Boolean =
        ownerID == owner && accessToken == token && isDraftSyncEnabled && syncGeneration == generation

    private fun syncFromRemote() {
        val service = syncService
        val token = accessToken
        val owner = ownerID
        if (fetchJob != null || !isDraftSyncEnabled || service == null || token == null || owner == null) return
        val queue = draftRepository.loadSyncMutations(owner)
        if (DraftSyncPolicy.dueMutations(queue).isNotEmpty()) return
        val localDrafts = _drafts.value
        val startedRevision = localRevision
        val generation = syncGeneration
        fetchJob = scope.launch {
            try {
                val remoteDrafts = try {
                    service.listDrafts(owner, token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (ownerID != owner || accessToken != token || syncGeneration != generation) return@launch
                    if (e is SyncedDraftsError.AuthorizationExpired) {
                        onAuthorizationExpired?.invoke()
                        return@launch
                    }
                    Log.e(TAG, "POST /api/drafts/sync refresh failed: ${e.javaClass.simpleName}")
                    _syncStatus.value = DraftSyncStatus.Failed
                    _statusMessage.value = "Draft refresh failed. Showing the last saved data."
                    return@launch
                }
                if (!isCurrent(owner, token, generation) || localRevision != startedRevision) return@launch
                val remoteIDs = remoteDrafts.map { it.id }.toSet()
                val pendingIDs = queue.map { it.draftID }.toSet()
                val deletedIDs = queue.filter { it.operation == DraftSyncOperation.delete }.map { it.draftID }.toSet()
                // A draft the server once held but no longer lists was deleted elsewhere; drop it unless a local change is queued.
                val retained = localDrafts.filter { it.id !in syncedIDs || it.id in remoteIDs || it.id in pendingIDs }
                val merged = DraftSyncPolicy.merge(local = retained, remote = remoteDrafts.filter { it.id !in deletedIDs })
                syncedIDs.addAll(remoteIDs)
                _drafts.value = merged
                lastSuccessfulSyncAt = Instant.now()
                persistDrafts(owner)
                // Server draftIds differing only by case decode to one UUID; keep the newest.
                val remoteByID = remoteDrafts.groupBy { it.id }.mapValues { (_, v) -> v.maxBy { it.updatedAt } }
                val localNewer = merged.filter { draft ->
                    draft.id !in pendingIDs && (remoteByID[draft.id]?.let { draft.updatedAt > it.updatedAt } ?: true)
                }
                for (draft in localNewer) enqueueSync(DraftSyncOperation.upsert, draft.id, draft)
                drainSyncQueue()
            } finally {
                fetchJob = null
            }
        }
    }

    private fun enqueueSync(operation: DraftSyncOperation, draftID: UUID, draft: Draft? = null) {
        val owner = ownerID
        if (owner == null) {
            _syncStatus.value = DraftSyncStatus.LocalOnly
            return
        }
        val mutation = DraftSyncMutation(ownerID = owner, operation = operation, draftID = draftID, draft = draft)
        val updated = DraftSyncPolicy.enqueue(mutation, draftRepository.loadSyncMutations(owner))
        draftRepository.saveSyncMutations(updated, owner)
        telemetry(AppTelemetryEvent.SyncQueueLength, mapOf("count" to "${updated.size}"))
        _syncStatus.value = DraftSyncStatus.Pending
        drainSyncQueue()
    }

    private fun drainSyncQueue(thenFetchRemote: Boolean = false) {
        if (isDrainingSyncQueue) return
        if (!isDraftSyncEnabled) {
            _syncStatus.value = DraftSyncStatus.Disabled
            return
        }
        val service = syncService
        val token = accessToken
        val owner = ownerID
        if (service == null || token == null || owner == null) {
            _syncStatus.value = if (owner == null) DraftSyncStatus.LocalOnly else DraftSyncStatus.Pending
            return
        }
        var queue = draftRepository.loadSyncMutations(owner)
        if (isLapsed) {
            // The server rejects lapsed edits and deletes (402); only new drafts can still be pushed.
            val pushable = queue.filter { it.operation == DraftSyncOperation.upsert && it.draftID !in syncedIDs }
            if (pushable.size != queue.size) {
                queue = pushable
                draftRepository.saveSyncMutations(queue, owner)
            }
        }
        if (queue.isEmpty()) {
            _syncStatus.value = DraftSyncStatus.Synced
            if (thenFetchRemote) syncFromRemote()
            return
        }
        val due = DraftSyncPolicy.dueMutations(queue)
        if (due.isEmpty()) {
            _syncStatus.value = if (queue.any { it.lastError != null }) DraftSyncStatus.Failed else DraftSyncStatus.Pending
            return
        }
        isDrainingSyncQueue = true
        val generation = syncGeneration
        drainJob = scope.launch {
            var authExpired = false
            try {
                for (mutation in due) {
                    if (!isCurrent(owner, token, generation)) break
                    if (draftRepository.loadSyncMutations(owner).none { it.id == mutation.id }) continue
                    try {
                        when (mutation.operation) {
                            DraftSyncOperation.upsert -> {
                                val draft = mutation.draft ?: continue
                                val remote = service.upsertDraft(draft, owner, token)
                                if (!isCurrent(owner, token, generation)) break
                                if (draftRepository.loadSyncMutations(owner).any { it.id == mutation.id } &&
                                    _drafts.value.any { it == draft }
                                ) {
                                    syncedIDs.add(remote.id)
                                    _drafts.value = DraftSyncPolicy.merge(local = _drafts.value, remote = listOf(remote))
                                    persistDrafts(owner)
                                }
                            }
                            DraftSyncOperation.delete -> {
                                service.deleteDraft(mutation.draft?.serverID ?: mutation.draftID.toString().lowercase(), owner, token)
                                if (!isCurrent(owner, token, generation)) break
                            }
                        }
                        draftRepository.saveSyncMutations(draftRepository.loadSyncMutations(owner).filter { it.id != mutation.id }, owner)
                        telemetry(AppTelemetryEvent.SyncAttemptSucceeded, mapOf("operation" to mutation.operation.name))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!isCurrent(owner, token, generation)) break
                        if (e is SyncedDraftsError.AuthorizationExpired) {
                            // The mutation stays queued and syncs after reconnect (IOS-005 / AND-005).
                            authExpired = true
                            break
                        }
                        val current = draftRepository.loadSyncMutations(owner)
                        val index = current.indexOfFirst { it.id == mutation.id }
                        if (index >= 0) {
                            draftRepository.saveSyncMutations(
                                current.toMutableList().also { it[index] = DraftSyncPolicy.markFailed(mutation, e) },
                                owner,
                            )
                            telemetry(AppTelemetryEvent.SyncAttemptFailed, mapOf("operation" to mutation.operation.name))
                            Log.e(TAG, "Sync ${mutation.operation.name} failed: ${e.javaClass.simpleName}")
                        }
                    }
                }
            } finally {
                isDrainingSyncQueue = false
                drainJob = null
            }
            if (authExpired) {
                onAuthorizationExpired?.invoke()
                return@launch
            }
            if (!isCurrent(owner, token, generation)) {
                if (isDraftSyncEnabled) drainSyncQueue(thenFetchRemote = true)
                return@launch
            }
            val remaining = draftRepository.loadSyncMutations(owner)
            telemetry(AppTelemetryEvent.SyncQueueLength, mapOf("count" to "${remaining.size}"))
            if (remaining.isEmpty()) {
                lastSuccessfulSyncAt = Instant.now()
                persistDrafts(owner)
            }
            _syncStatus.value = when {
                remaining.isEmpty() -> DraftSyncStatus.Synced
                remaining.any { it.lastError != null } -> DraftSyncStatus.Failed
                else -> DraftSyncStatus.Pending
            }
            if (remaining.isEmpty() && thenFetchRemote) {
                syncFromRemote()
            } else if (DraftSyncPolicy.dueMutations(remaining).isNotEmpty()) {
                drainSyncQueue()
            }
        }
    }

    companion object {
        private const val TAG = "DraftStore"
        private const val SIGNED_OUT = "signed-out"
        const val LAPSED_FROZEN_MESSAGE = "Without a plan, drafts can't be edited, deleted, or published."

        private fun sameBytes(a: ByteArray?, b: ByteArray?): Boolean = when {
            a === b -> true
            a == null || b == null -> false
            else -> a.contentEquals(b)
        }
    }
}
