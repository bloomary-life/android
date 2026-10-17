package life.bloomary.app.shared.observability

import android.content.Context
import com.telemetrydeck.sdk.TelemetryDeck
import java.util.UUID
import life.bloomary.app.application.config.AppConfiguration

/**
 * Analytics contract — mirrors ios/Shared/Observability/AppTelemetry.swift
 * verbatim. Never pass post bodies, tokens, feedback text, or attachment
 * content as parameters; only numeric counters and fixed category labels.
 */
enum class AppTelemetryEvent(val eventName: String) {
    AppOpened("app_opened"),
    InsightOpened("insight_opened"),
    EditorStarted("editor_started"),
    DraftSaved("draft_saved"),
    NotificationSyncFailed("notification_sync_failed"),
    LinkedInConnectStarted("linkedin_connect_started"),
    LinkedInConnected("linkedin_connected"),
    AccountDeleted("account_deleted"),
    PostStarted("post_started"),
    PostSucceeded("post_succeeded"),
    PostFailed("post_failed"),
    FeedbackOpened("feedback_opened"),
    FeedbackSent("feedback_sent"),
}

object AppTelemetry {
    @Volatile
    private var isConfigured = false

    fun start(context: Context, configuration: AppConfiguration.Observability) {
        val appID = configuration.telemetryDeckAppID.trim()
        if (appID.isEmpty()) return

        val namespace = configuration.telemetryDeckNamespace.trim()
        val builder = TelemetryDeck.Builder()
            .appID(appID)
            .namespace(namespace.ifEmpty { null })
            // Fresh anonymous UUID per launch — never a stable user identifier.
            .defaultUser(UUID.randomUUID().toString())
            // Session signals disabled (parity with the iOS configuration).
            .sendNewSessionBeganSignal(false)
        TelemetryDeck.start(context.applicationContext, builder)
        isConfigured = true
    }

    fun track(event: AppTelemetryEvent, parameters: Map<String, String> = emptyMap()) {
        if (!isConfigured) return
        TelemetryDeck.signal(event.eventName, null, parameters)
    }
}
