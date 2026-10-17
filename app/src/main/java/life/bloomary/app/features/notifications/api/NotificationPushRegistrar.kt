package life.bloomary.app.features.notifications.api

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import life.bloomary.app.features.linkedinauth.model.LinkedInToken
import life.bloomary.app.features.linkedinpost.api.LinkedInPostCredential
import life.bloomary.app.features.notifications.storage.PushTokenStore
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent

/**
 * Orchestrates FCM token registration with the backend, the Android half of
 * the APNs flow behind ios/Features/Notifications/API/NotificationTokenService.swift.
 *
 * Triggers mirror the iOS registration moments:
 * - [syncRegistration] runs when the LinkedIn session is available (root
 *   appear / token change), fetching the current FCM token and registering it.
 * - [onNewToken] runs on FCM token refresh; the token is stashed so the next
 *   session-available sync can register it when signed out.
 *
 * Registration is deduped per memberID + token via [PushTokenStore]; failures
 * are soft (telemetry only).
 */
class NotificationPushRegistrar(
    private val tokenService: NotificationTokenService,
    private val tokenStore: PushTokenStore,
    private val fetchPushToken: suspend () -> String? = ::fetchCurrentFcmToken,
) {
    /**
     * Wired by MainActivity to the POST_NOTIFICATIONS launcher, mirroring iOS
     * `requestAuthorization` in NotificationTokenService before registering.
     */
    var notificationPermissionRequester: (suspend () -> Boolean)? = null

    suspend fun syncRegistration(token: LinkedInToken?) {
        if (token == null || token.accessToken.isEmpty() || token.profile.memberID.isEmpty()) return
        notificationPermissionRequester?.invoke()
        val pushToken = (fetchPushToken() ?: tokenStore.latestToken())?.trim().orEmpty()
        if (pushToken.isEmpty()) return
        registerIfNeeded(pushToken, token)
    }

    suspend fun onNewToken(pushToken: String, token: LinkedInToken?) {
        tokenStore.setLatestToken(pushToken)
        if (token == null || token.accessToken.isEmpty() || token.profile.memberID.isEmpty()) return
        registerIfNeeded(pushToken, token)
    }

    private suspend fun registerIfNeeded(pushToken: String, token: LinkedInToken) {
        val memberID = token.profile.memberID
        if (tokenStore.registeredToken(memberID) == pushToken) return
        try {
            tokenService.register(
                token = pushToken,
                credential = LinkedInPostCredential(accessToken = token.accessToken, memberID = memberID),
            )
            tokenStore.setRegisteredToken(memberID, pushToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppTelemetry.track(AppTelemetryEvent.NotificationSyncFailed)
        }
    }
}

/**
 * Fetches the current FCM registration token. Null when Firebase is not
 * initialised (missing google-services.json) or the fetch fails — token
 * registration is best-effort and never blocks the session flow.
 */
private suspend fun fetchCurrentFcmToken(): String? = runCatching {
    FirebaseMessaging.getInstance().token.await()
}.getOrNull()
