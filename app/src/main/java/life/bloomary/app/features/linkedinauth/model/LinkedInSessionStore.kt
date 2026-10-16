package life.bloomary.app.features.linkedinauth.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import life.bloomary.app.features.linkedinauth.storage.LinkedInTokenStore
import life.bloomary.app.features.linkedinpost.api.LinkedInPostCredential

/**
 * Mirrors ios/Features/LinkedInAuth/Model/LinkedInSessionStore.swift.
 *
 * @param requireFreshSignInFlag hook for `requireFreshSignInOnce()`. On iOS the
 *   flag lives on `LinkedInAuthService` (UserDefaults key
 *   `linkedin.requiresFreshSignIn`); the container wires this lambda to
 *   `LinkedInAuthService.requireFreshSignInOnce()` so the session store exposes
 *   the same surface the account layer calls.
 */
class LinkedInSessionStore(
    private val tokenStore: LinkedInTokenStore,
    private val requireFreshSignInFlag: () -> Unit = {},
) {
    private val _token = MutableStateFlow<LinkedInToken?>(tokenStore.load())
    val token: StateFlow<LinkedInToken?> = _token.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val isConnected: Boolean
        get() {
            val token = _token.value ?: return false
            return token.accessToken.isNotEmpty() && token.profile.memberID.isNotEmpty()
        }

    val postCredential: LinkedInPostCredential?
        get() = _token.value?.let {
            LinkedInPostCredential(accessToken = it.accessToken, memberID = it.profile.memberID)
        }

    fun setToken(token: LinkedInToken) {
        _token.value = token
        _statusMessage.value = null
        tokenStore.save(token)
    }

    /** Swift exposes `statusMessage` as a settable `@Published`; mirror via setter. */
    fun setStatusMessage(message: String?) {
        _statusMessage.value = message
    }

    fun disconnect() {
        _token.value = null
        tokenStore.clear()
    }

    fun deleteAccountData() {
        _token.value = null
        tokenStore.clearAccountDeletionData()
    }

    fun requireFreshSignInOnce() {
        requireFreshSignInFlag()
    }
}

/**
 * Android stand-in for `UIApplication.didBecomeActiveNotification` in the
 * LinkedIn CLI OAuth flow (`LinkedInAuthService.waitForForeground()`,
 * LinkedInAuthService.swift:103-110).
 *
 * `MainActivity` emits on lifecycle STARTED and when a
 * `bloomary://linkedin-connected` deep link arrives; the auth service
 * (`features/linkedinauth/api/LinkedInAuthService.kt`, owned by the API agent)
 * collects [foregroundReturns] to start/resume result polling. Emissions with
 * no pending sign-in are dropped, exactly like the iOS notification when no
 * flow is awaiting it.
 */
object LinkedInAuthForegroundSignal {
    private val _foregroundReturns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val foregroundReturns: SharedFlow<Unit> = _foregroundReturns.asSharedFlow()

    fun notifyForegroundReturn() {
        _foregroundReturns.tryEmit(Unit)
    }
}
