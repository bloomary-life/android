package life.bloomary.app.features.drafts.model

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import life.bloomary.app.features.drafts.domain.DraftPolicy
import life.bloomary.app.features.drafts.domain.DraftSnapshot
import life.bloomary.app.features.drafts.storage.DraftRepository
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.observability.PerformanceSignposts

/**
 * Mirrors ios/Features/Drafts/Model/DraftStore.swift.
 *
 * `@Published` properties map to [StateFlow]; call sites are the main thread
 * exactly as the iOS `@MainActor` store.
 */
class DraftStore(
    private val draftRepository: DraftRepository,
    private val telemetry: (AppTelemetryEvent, Map<String, String>) -> Unit = { event, params ->
        AppTelemetry.track(event, params)
    },
) {
    private val _editorText = MutableStateFlow("Write a LinkedIn post...")
    val editorText: StateFlow<String> = _editorText.asStateFlow()

    private val _editorImageData = MutableStateFlow<ByteArray?>(null)
    val editorImageData: StateFlow<ByteArray?> = _editorImageData.asStateFlow()

    private val _editorImageFilename = MutableStateFlow<String?>(null)
    val editorImageFilename: StateFlow<String?> = _editorImageFilename.asStateFlow()

    private val _editorImageMimeType = MutableStateFlow<String?>(null)
    val editorImageMimeType: StateFlow<String?> = _editorImageMimeType.asStateFlow()

    private val _drafts = MutableStateFlow<List<Draft>>(emptyList())
    val drafts: StateFlow<List<Draft>> = _drafts.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isPaywalled = MutableStateFlow(false)
    val isPaywalled: StateFlow<Boolean> = _isPaywalled.asStateFlow()

    private var editingDraftID: UUID? = null
    private var ownerID: String? = null
    private val maxDrafts: Int get() = if (_isPaywalled.value) 5 else 50

    init {
        val snapshot = PerformanceSignposts.draftCacheLoad { draftRepository.loadSnapshot(ownerID = null) }
        _drafts.value = snapshot.drafts
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

    fun setOwnerID(ownerID: String?) {
        if (this.ownerID == ownerID) return
        val previousOwnerID = this.ownerID
        this.ownerID = ownerID
        val snapshot = PerformanceSignposts.draftCacheLoad { draftRepository.loadSnapshot(ownerID = ownerID) }
        if (previousOwnerID == null && ownerID != null) {
            val signedOutSnapshot = draftRepository.loadSnapshot(ownerID = null)
            _drafts.value = DraftPolicy.merge(
                local = snapshot.drafts,
                remote = signedOutSnapshot.drafts,
            )
            persistDrafts(ownerID)
            draftRepository.deleteDrafts(ownerID = null)
        } else {
            _drafts.value = snapshot.drafts
        }
        startNewPost()
    }

    fun startNewPost() {
        if (_isPaywalled.value) {
            _statusMessage.value = "Free tier access ended. Writing is unavailable."
            return
        }

        _editorText.value = "Write a LinkedIn post..."
        _editorImageData.value = null
        _editorImageFilename.value = null
        _editorImageMimeType.value = null
        editingDraftID = null
        _statusMessage.value = null
    }

    fun saveCurrentDraft() {
        val body = DraftPolicy.trimmedBody(_editorText.value)
        if (body.isEmpty() && _editorImageData.value == null) {
            _statusMessage.value = "Draft is empty"
            return
        }

        val result = PerformanceSignposts.measure("DraftSave") {
            DraftPolicy.save(
                drafts = _drafts.value,
                editingDraftID = editingDraftID,
                body = body,
                maxDrafts = maxDrafts,
                imageData = _editorImageData.value,
                imageFilename = _editorImageFilename.value,
                imageMimeType = _editorImageMimeType.value,
            )
        }
        if (result.limitReached) {
            _statusMessage.value = "Draft limit reached. Delete a draft or upgrade to Pro."
            return
        }
        _drafts.value = result.drafts
        editingDraftID = result.editingDraftID
        if (result.changedDraft == null) {
            _statusMessage.value = "Draft saved"
            return
        }
        persistDrafts(ownerID)
        telemetry(AppTelemetryEvent.DraftSaved, emptyMap())
        _statusMessage.value = "Draft saved"
    }

    fun saveOpenedDraftAndClearEditor() {
        if (editingDraftID != null) {
            saveCurrentDraft()
        }
        _editorText.value = ""
        _editorImageData.value = null
        _editorImageFilename.value = null
        _editorImageMimeType.value = null
        editingDraftID = null
        _statusMessage.value = null
    }

    fun openDraft(draft: Draft) {
        _editorText.value = draft.body
        _editorImageData.value = draft.imageData
        _editorImageFilename.value = draft.imageFilename
        _editorImageMimeType.value = draft.imageMimeType
        editingDraftID = draft.id
    }

    fun deleteDraft(draft: Draft) {
        if (_isPaywalled.value) {
            _statusMessage.value = "Free tier access ended. Deleting drafts is unavailable."
            return
        }

        _drafts.value = DraftPolicy.delete(_drafts.value, draft.id)
        if (editingDraftID == draft.id) {
            editingDraftID = null
            _editorImageData.value = null
            _editorImageFilename.value = null
            _editorImageMimeType.value = null
        }
        persistDrafts(ownerID)
    }

    fun deletePostedDraftIfSaved() {
        if (_isPaywalled.value) return
        val currentEditingID = editingDraftID ?: return
        editingDraftID = null
        val remaining = DraftPolicy.delete(_drafts.value, currentEditingID)
        if (remaining.size != _drafts.value.size) {
            _drafts.value = remaining
            persistDrafts(ownerID)
        }
    }

    fun deleteAccountData(ownerID: String?) {
        if (ownerID != null) {
            draftRepository.deleteDrafts(ownerID = ownerID)
        }
        draftRepository.deleteDrafts(ownerID = null)
        this.ownerID = null
        _drafts.value = emptyList()
        startNewPost()
    }

    fun setPaywalled(isPaywalled: Boolean) {
        _isPaywalled.value = isPaywalled
    }

    private fun persistDrafts(ownerID: String?) {
        draftRepository.saveSnapshot(
            DraftSnapshot(drafts = _drafts.value, ownerID = ownerID),
            ownerID = ownerID,
        )
    }
}
