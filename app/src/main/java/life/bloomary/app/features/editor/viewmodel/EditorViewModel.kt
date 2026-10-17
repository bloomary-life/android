package life.bloomary.app.features.editor.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import life.bloomary.app.features.ai.api.NaturalizeService
import life.bloomary.app.features.ai.storage.AISettingsStore
import life.bloomary.app.features.drafts.domain.DraftPolicy
import life.bloomary.app.features.drafts.model.DraftStore
import life.bloomary.app.features.linkedinauth.api.LinkedInAuthService
import life.bloomary.app.features.linkedinauth.model.LinkedInProfile
import life.bloomary.app.features.linkedinauth.model.LinkedInSessionStore
import life.bloomary.app.features.linkedinpost.api.LinkedInPostAttachment
import life.bloomary.app.features.linkedinpost.api.LinkedInPostService
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.observability.PerformanceSignposts

class EditorViewModel(
    private val store: DraftStore,
    private val sessionStore: LinkedInSessionStore,
    private val authService: LinkedInAuthService,
    private val postService: LinkedInPostService,
    private val naturalizeService: NaturalizeService,
    private val aiSettingsStore: AISettingsStore,
    private val scope: CoroutineScope,
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

    val statusMessage: StateFlow<String?> = store.statusMessage
    val isPaywalled: StateFlow<Boolean> = store.isPaywalled
    val editorImageData: StateFlow<ByteArray?> = store.editorImageData
    val editorImageFilename: StateFlow<String?> = store.editorImageFilename

    val profile: LinkedInProfile?
        get() = sessionStore.token.value?.profile

    val hasEditorText: Boolean
        get() = DraftPolicy.trimmedBody(editorText).isNotEmpty()

    val composedText: String
        get() = editorText

    val previewText: String
        get() = DraftPolicy.trimmedBody(composedText).ifEmpty { "Preview appears here." }

    init {
        scope.launch {
            store.editorText.collect { text ->
                if (_editorText != text) {
                    _editorText = text
                }
            }
        }
    }

    fun save() {
        if (store.isPaywalled.value) {
            store.setStatusMessage("Free tier access ended. Saving drafts is unavailable.")
            return
        }

        store.saveCurrentDraft()
    }

    fun clearEditor() {
        if (store.isPaywalled.value) {
            store.setStatusMessage("Free tier access ended. Editing drafts is unavailable.")
            return
        }

        store.saveOpenedDraftAndClearEditor()
    }

    fun setAttachedImage(data: ByteArray, filename: String, mimeType: String) {
        store.setEditorImage(data, filename, mimeType)
    }

    fun removeAttachedImage() {
        store.setEditorImage(null, null, null)
    }

    fun publish() {
        if (store.isPaywalled.value) {
            store.setStatusMessage("Free tier access ended. Posting to LinkedIn is unavailable.")
            return
        }

        AppTelemetry.track(AppTelemetryEvent.PostStarted)
        isPosting = true
        scope.launch {
            val signpost = PerformanceSignposts.beginAsyncInterval("PublishAttempt")
            try {
                performPublish()
            } finally {
                PerformanceSignposts.endAsyncInterval("PublishAttempt", signpost)
            }
        }
    }

    fun naturalizeEnglish() {
        isNaturalizing = true
        scope.launch {
            try {
                val text = naturalizeService.naturalize(editorText, aiSettingsStore.current)
                editorText = text
                store.setStatusMessage("Naturalized English.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.setStatusMessage(e.message ?: "AI edit failed.")
            } finally {
                isNaturalizing = false
            }
        }
    }

    private suspend fun performPublish() {
        try {
            connectIfNeeded()
            postService.publish(
                text = composedText,
                credential = sessionStore.postCredential,
                attachment = postAttachment(),
            )
            store.deletePostedDraftIfSaved()
            finishPost("Posted to LinkedIn", AppTelemetryEvent.PostSucceeded)
            showsPostSuccess = true
            removeAttachedImage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishPost(e.message ?: "LinkedIn post failed.", AppTelemetryEvent.PostFailed)
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
}
