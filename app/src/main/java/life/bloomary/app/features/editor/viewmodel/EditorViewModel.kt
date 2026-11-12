package life.bloomary.app.features.editor.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import life.bloomary.app.features.ai.api.NaturalizeService
import life.bloomary.app.features.ai.storage.AISettingsStore
import life.bloomary.app.features.drafts.domain.DraftPolicy
import life.bloomary.app.features.drafts.domain.SchedulePolicy
import life.bloomary.app.features.drafts.model.DraftScheduling
import life.bloomary.app.features.drafts.model.DraftStore
import life.bloomary.app.features.drafts.model.DraftSyncStatus
import life.bloomary.app.features.drafts.model.PlanAccess
import life.bloomary.app.features.editor.storage.InMemoryPublishAttemptStore
import life.bloomary.app.features.editor.storage.PublishAttemptStore
import life.bloomary.app.features.linkedinauth.api.LinkedInAuthService
import life.bloomary.app.features.linkedinauth.model.LinkedInProfile
import life.bloomary.app.features.linkedinauth.model.LinkedInSessionStore
import life.bloomary.app.features.linkedinpost.api.LinkedInPostAttachment
import life.bloomary.app.features.linkedinpost.api.LinkedInPostCredential
import life.bloomary.app.features.linkedinpost.api.LinkedInPostError
import life.bloomary.app.features.linkedinpost.api.LinkedInPostService
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.observability.PerformanceSignposts

/** Mirrors ios/Features/Editor/ViewModel/EditorViewModel.swift. */
class EditorViewModel(
    private val store: DraftStore,
    private val sessionStore: LinkedInSessionStore,
    private val authService: LinkedInAuthService,
    private val postService: LinkedInPostService,
    private val naturalizeService: NaturalizeService,
    private val aiSettingsStore: AISettingsStore,
    private val scope: CoroutineScope,
    private val scheduling: DraftScheduling? = null,
    private val publishAttempts: PublishAttemptStore = InMemoryPublishAttemptStore(),
    val scheduleMaxAheadDays: Int = SchedulePolicy.defaultMaxAheadDays,
) {
    private var _editorText by mutableStateOf(store.editorText.value)

    var editorText: String
        get() = _editorText
        set(value) {
            if (_editorText == value) return
            _editorText = value
            store.setEditorText(value)
        }

    var isPosting by mutableStateOf(false)
        private set
    var isNaturalizing by mutableStateOf(false)
        private set
    var showsPostSuccess by mutableStateOf(false)
    var showsPublishRetryConfirmation by mutableStateOf(false)
    var showsAIConsent by mutableStateOf(false)
    var showsSchedulePicker by mutableStateOf(false)
    var isScheduling by mutableStateOf(false)
        private set
    var scheduleMessage by mutableStateOf<String?>(null)
    var showsSchedulePublishedAlert by mutableStateOf(false)
    var showsScheduleFailureAlert by mutableStateOf(false)
    var scheduleFailureMessage by mutableStateOf<String?>(null)
        private set
    var scheduleStatus by mutableStateOf<String?>(null)
        private set

    private var isRefreshingSchedules = false
    private var scheduleIDs = listOf<String>()
    private var scheduleStatuses = mapOf<String, String>()

    val statusMessage: StateFlow<String?> = store.statusMessage
    val syncStatus: StateFlow<DraftSyncStatus> = store.syncStatus
    val plan: StateFlow<PlanAccess> = store.plan
    val drafts = store.drafts
    val editingDraftID: StateFlow<UUID?> = store.editingDraftID
    val editorImageData: StateFlow<ByteArray?> = store.editorImageData
    val editorImageFilename: StateFlow<String?> = store.editorImageFilename

    val isLapsed: Boolean get() = store.isLapsed
    val isEditingExistingDraft: Boolean get() = store.isEditingExistingDraft
    val isNewDraftBlockedByLimit: Boolean get() = store.isDraftLimitReached && !store.isEditingExistingDraft
    val maxImageBytes: Int get() = store.plan.value.maxImageBytes

    val draftLimitWarning: String?
        get() {
            if (!isNewDraftBlockedByLimit) return null
            if (store.isLapsed) return "You've reached 5 drafts without a plan."
            val action = if (store.hasPlanAccess) "Delete a draft to create a new one." else "Delete a draft or upgrade to Pro."
            val tier = if (store.hasPlanAccess) "Pro" else "free"
            return "You have ${store.drafts.value.size} drafts ($tier limit: ${store.maxDrafts}). $action"
        }

    val profile: LinkedInProfile?
        get() = sessionStore.token.value?.profile

    val hasEditorText: Boolean
        get() = DraftPolicy.trimmedBody(editorText).isNotEmpty()

    val previewText: String
        get() = if (DraftPolicy.trimmedBody(editorText).isEmpty()) "Preview appears here." else editorText

    val scheduleLimitText: String
        get() = "Schedule between 5 minutes and $scheduleMaxAheadDays days ahead."

    val postingSuggestions: String get() = SchedulePolicy.postingSuggestions()

    fun defaultScheduleDate(): Instant = SchedulePolicy.defaultScheduleDate()
    fun earliestScheduleDate(): Instant = Instant.now().plus(SchedulePolicy.minimumLeadTime)
    fun latestScheduleDate(): Instant = Instant.now().plus(Duration.ofDays(scheduleMaxAheadDays.toLong()))

    init {
        scope.launch {
            store.editorText.collect { text -> if (_editorText != text) _editorText = text }
        }
        // A new session or another open draft hides the previous draft's schedule state.
        scope.launch {
            sessionStore.token.map { it?.accessToken }.distinctUntilChanged().drop(1).collect {
                scheduleIDs = emptyList()
                scheduleStatuses = emptyMap()
                resetVisibleSchedule()
            }
        }
        scope.launch { store.editingDraftID.drop(1).collect { resetVisibleSchedule() } }
    }

    /** `lapsed` cannot publish or schedule; posts scheduled before the lapse still fire server-side. */
    fun showPlanRequired() {
        store.setStatusMessage(PUBLISH_NEEDS_PLAN)
    }

    fun save(): Boolean = store.saveCurrentDraft()

    fun clearEditor() = store.saveOpenedDraftAndClearEditor()

    fun setAttachedImage(data: ByteArray, filename: String, mimeType: String) {
        store.setEditorImage(data, filename, mimeType)
        store.setStatusMessage("Image attached.")
    }

    fun removeAttachedImage() {
        store.setEditorImage(null, null, null)
    }

    fun schedule(at: Instant) {
        if (store.isLapsed) return showPlanRequired()
        val service = scheduling ?: return
        val credential = sessionStore.postCredential
        if (isScheduling || credential == null) {
            if (credential == null) scheduleMessage = "Connect LinkedIn first."
            return
        }
        if (!SchedulePolicy.isWithinWindow(at, scheduleMaxAheadDays)) {
            scheduleMessage = "Choose a time between 5 minutes and $scheduleMaxAheadDays days from now."
            return
        }
        isScheduling = true
        scheduleMessage = null
        scope.launch {
            try {
                val draft = store.syncCurrentDraftForScheduling()
                if (sessionStore.postCredential != credential) throw LinkedInPostError.MissingAuth
                val snapshot = store.editorSnapshot
                val result = service.schedule(draft.serverID, at, false, credential.memberID, credential.accessToken)
                if (sessionStore.postCredential != credential || store.editorSnapshot != snapshot) return@launch
                store.setScheduledPosts(
                    store.scheduledPosts.value.filter { it.scheduleId != result.scheduleId } + result,
                    credential.memberID,
                    credential.accessToken,
                )
                if (result.scheduleId !in scheduleIDs) scheduleIDs = scheduleIDs + result.scheduleId
                scheduleStatus = result.status
                scheduleStatuses = scheduleStatuses + (result.scheduleId to result.status)
                scheduleMessage = "Scheduled for ${formatDateTime(at)}."
                showsSchedulePicker = false
                store.setStatusMessage(scheduleMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionStore.postCredential == credential) scheduleMessage = e.message
            } finally {
                isScheduling = false
            }
        }
    }

    private fun resetVisibleSchedule() {
        scheduleStatus = null
        scheduleMessage = null
        scheduleFailureMessage = null
        showsSchedulePublishedAlert = false
        showsScheduleFailureAlert = false
        showsSchedulePicker = false
    }

    /** Polls schedules; alerts when one the member is watching publishes or needs attention. */
    suspend fun refreshScheduleStatus() {
        val service = scheduling ?: return
        val credential = sessionStore.postCredential ?: return
        if (isRefreshingSchedules) return
        isRefreshingSchedules = true
        val requestedAt = Instant.now()
        try {
            val posts = service.scheduledPosts(credential.memberID, credential.accessToken).toMutableList()
            for (id in scheduleIDs) {
                if (posts.none { it.scheduleId == id }) posts += service.scheduleStatus(id, credential.memberID, credential.accessToken)
            }
            if (sessionStore.postCredential != credential) return
            if (!store.setScheduledPosts(posts, credential.memberID, credential.accessToken, requestedAt)) return
            for (post in posts) {
                val previous = scheduleStatuses[post.scheduleId] ?: continue
                if (previous == post.status) continue
                if (post.status == "published") showsSchedulePublishedAlert = true
                if (post.status in setOf("failed", "needs_review")) {
                    scheduleFailureMessage = post.message ?: "Scheduled post needs attention."
                    showsScheduleFailureAlert = true
                }
            }
            val active = posts.filter { it.isActive }
            scheduleIDs = active.map { it.scheduleId }
            scheduleStatuses = active.associate { it.scheduleId to it.status }
            val editing = store.drafts.value.firstOrNull { it.id == store.editingDraftID.value }
            val post = editing?.let { draft -> posts.firstOrNull { it.isFor(draft) } }
            scheduleStatus = post?.status
            scheduleMessage = post?.let { it.message ?: if (it.status == "published") "Published on LinkedIn." else null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (sessionStore.postCredential != credential) return
            Log.e("Schedules", "Schedule reconciliation failed: ${e.javaClass.simpleName}")
            scheduleMessage = "Schedule refresh failed. Showing the last known status."
        } finally {
            isRefreshingSchedules = false
        }
    }

    fun publish(confirmRetry: Boolean = false) {
        if (store.isLapsed) return showPlanRequired()
        if (isPosting) return
        val origin = store.editorSnapshot
        AppTelemetry.track(AppTelemetryEvent.PostStarted)
        isPosting = true
        scope.launch {
            val signpost = PerformanceSignposts.beginAsyncInterval("PublishAttempt")
            try {
                performPublish(origin, confirmRetry)
            } finally {
                PerformanceSignposts.endAsyncInterval("PublishAttempt", signpost)
            }
        }
    }

    /** IOS-006 / AND-006: nothing is sent to Gemini until the member allows it once. */
    fun naturalizeEnglish() {
        if (isNaturalizing) return
        if (!aiSettingsStore.hasAIConsent.value) {
            showsAIConsent = true
            return
        }
        isNaturalizing = true
        store.setStatusMessage("Naturalizing English...")
        val snapshot = store.editorSnapshot
        val credential = sessionStore.postCredential
        scope.launch {
            try {
                val text = naturalizeService.naturalize(snapshot.text, aiSettingsStore.current)
                if (store.editorSnapshot != snapshot || sessionStore.postCredential != credential) return@launch
                editorText = text
                store.setStatusMessage("Naturalized English.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (store.editorSnapshot == snapshot && sessionStore.postCredential == credential) {
                    store.setStatusMessage(e.message ?: "AI edit failed.")
                }
            } finally {
                isNaturalizing = false
            }
        }
    }

    fun allowAIAndNaturalize() {
        showsAIConsent = false
        aiSettingsStore.grantAIConsent()
        naturalizeEnglish()
    }

    private suspend fun performPublish(origin: DraftStore.EditorSnapshot, confirmRetry: Boolean) {
        var publishingCredential: LinkedInPostCredential? = null
        try {
            connectIfNeeded()
            val submitted = store.editorSnapshot
            val credential = sessionStore.postCredential
            if (submitted.text != origin.text || !submitted.imageData.sameAs(origin.imageData) ||
                submitted.draftID != origin.draftID ||
                (origin.ownerID != null && submitted.ownerID != origin.ownerID) || credential == null
            ) {
                store.setStatusMessage("Editor changed while connecting. Review your post and publish again.")
                return
            }
            publishingCredential = credential
            val fingerprint = publishFingerprint(submitted)
            if (!confirmRetry && publishAttempts.lastFingerprint(credential.memberID) == fingerprint) {
                showsPublishRetryConfirmation = true
                return
            }
            publishAttempts.setLastFingerprint(credential.memberID, fingerprint)
            val savedDraft = store.drafts.value.firstOrNull { it.id == submitted.draftID }
            postService.publish(
                text = editorText,
                credential = credential,
                attachment = postAttachment(),
                draftID = savedDraft?.serverID,
            )
            if (sessionStore.postCredential != credential) return
            store.completePublishedDraft(submitted, savedDraft)
            finishPost("Posted to LinkedIn", AppTelemetryEvent.PostSucceeded)
            showsPostSuccess = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkedInPostError.AuthorizationExpired) {
            if (sessionStore.postCredential != publishingCredential) return
            sessionStore.disconnect()
            finishPost(e.message ?: "LinkedIn authorization expired.", AppTelemetryEvent.PostFailed)
        } catch (e: Exception) {
            if (publishingCredential != null && sessionStore.postCredential != publishingCredential) return
            finishPost(e.message ?: "LinkedIn post failed.", AppTelemetryEvent.PostFailed)
        } finally {
            isPosting = false
        }
    }

    private fun postAttachment(): LinkedInPostAttachment? {
        val data = store.editorImageData.value ?: return null
        return LinkedInPostAttachment(
            data = data,
            filename = store.editorImageFilename.value ?: "bloomary-linkedin-image.jpg",
            mimeType = store.editorImageMimeType.value ?: "image/jpeg",
        )
    }

    private suspend fun connectIfNeeded() {
        if (sessionStore.isConnected) return
        AppTelemetry.track(AppTelemetryEvent.LinkedInConnectStarted)
        val token = authService.signIn()
        sessionStore.setToken(token)
        AppTelemetry.track(AppTelemetryEvent.LinkedInConnected)
    }

    private fun finishPost(message: String, event: AppTelemetryEvent) {
        store.setStatusMessage(message)
        isPosting = false
        AppTelemetry.track(event)
    }

    companion object {
        /** Same copy as `AccessBannerCopy.publishNeedsPlan` (`docs/product/tiers.md`). */
        const val PUBLISH_NEEDS_PLAN = "Publishing needs an active plan."

        fun publishFingerprint(snapshot: DraftStore.EditorSnapshot): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(snapshot.text.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(snapshot.imageData ?: ByteArray(0))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun formatDateTime(instant: Instant): String =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault())
                .format(instant)

        private fun ByteArray?.sameAs(other: ByteArray?): Boolean = when {
            this === other -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }
    }
}
