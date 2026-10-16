package life.bloomary.app.features.editor.viewmodel

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
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
import life.bloomary.app.features.notifications.api.NotificationReminderService
import life.bloomary.app.features.streak.api.StreakService
import life.bloomary.app.features.streak.model.Streak
import life.bloomary.app.features.streak.model.StreakFooter
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
    private val streakService: StreakService,
    private val notificationReminderService: NotificationReminderService,
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

    var addStreakFooter by mutableStateOf(false)

    var knownStreak by mutableStateOf<Streak?>(null)
        private set

    var publishedStreak by mutableStateOf<Streak?>(null)
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
        get() {
            val body = DraftPolicy.trimmedBody(editorText)
            if (!addStreakFooter || body.isEmpty()) return editorText
            return body + StreakFooter.text(day = (knownStreak?.current ?: 0) + 1)
        }

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

    fun startPostWithStreakBadge(image: ImageBitmap) {
        store.startNewPost()
        val streakDay = publishedStreak?.current ?: knownStreak?.current
        editorText = if (streakDay != null) {
            "My \uD83D\uDD25 streak is Day $streakDay. Keeping the habit visible with Bloomary."
        } else {
            "My \uD83D\uDD25 streak is growing. Keeping the habit visible with Bloomary."
        }
        store.setEditorImage(image.pngData(), "bloomary-streak-badge.png", "image/png")
        store.saveCurrentDraft()
        AppTelemetry.track(AppTelemetryEvent.StreakBadgeShared)
    }

    fun streakFooterChanged() {
        if (!addStreakFooter) return
        AppTelemetry.track(AppTelemetryEvent.StreakFooterEnabled)
        if (knownStreak == null) {
            scope.launch { loadStreak() }
        }
    }

    suspend fun loadStreak(forceRefresh: Boolean = false) {
        val credential = sessionStore.postCredential
        if (credential == null) {
            clearStreakState()
            return
        }
        knownStreak = runCatching { streakService.streak(credential, forceRefresh) }.getOrNull()
    }

    fun clearStreakState() {
        knownStreak = null
        publishedStreak = null
        addStreakFooter = false
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
            val result = postService.publish(
                text = composedText,
                credential = sessionStore.postCredential,
                attachment = postAttachment(),
            )
            notificationReminderService.recordTodayPostAndCancelReminders(sessionStore.token.value)
            store.deletePostedDraftIfSaved()
            finishPost("Posted to LinkedIn", AppTelemetryEvent.PostSucceeded)
            addStreakFooter = false
            knownStreak = result.streak ?: knownStreak
            loadStreak(forceRefresh = true)
            val freshStreak = knownStreak ?: result.streak
            trackStreakMilestone(freshStreak)
            publishedStreak = freshStreak
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

    private fun trackStreakMilestone(streak: Streak?) {
        when (streak?.current) {
            7 -> AppTelemetry.track(AppTelemetryEvent.StreakMilestone7)
            30 -> AppTelemetry.track(AppTelemetryEvent.StreakMilestone30)
            100 -> AppTelemetry.track(AppTelemetryEvent.StreakMilestone100)
        }
    }
}

private fun ImageBitmap.pngData(): ByteArray {
    val stream = ByteArrayOutputStream()
    asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
}
