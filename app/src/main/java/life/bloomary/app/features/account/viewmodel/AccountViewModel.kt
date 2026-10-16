package life.bloomary.app.features.account.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import life.bloomary.app.features.ai.storage.AISettingsStore
import life.bloomary.app.features.drafts.model.DraftStore
import life.bloomary.app.features.linkedinauth.model.LinkedInSessionStore
import life.bloomary.app.features.linkedinauth.model.LinkedInToken
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent

/**
 * Auth surface the account presentation layer depends on — mirrors the
 * `AccountAuthServicing` protocol in
 * ios/Features/Account/ViewModel/AccountViewModel.swift. On iOS,
 * `LinkedInAuthService` conforms via extension; the Android
 * `LinkedInAuthService` must implement this interface.
 */
interface AccountAuthServicing {
    suspend fun signIn(): LinkedInToken
    suspend fun disconnect(memberID: String, accessToken: String)
    suspend fun deleteAccount(memberID: String, accessToken: String)
    fun requireFreshSignInOnce()
}

/**
 * Mirrors ios/Features/Account/ViewModel/AccountViewModel.swift.
 *
 * `@Published` flags map to Compose state (same style as MainTabCoordinator);
 * session state is forwarded from LinkedInSessionStore's StateFlows, mirroring
 * the objectWillChange passthrough in the iOS initializer.
 */
class AccountViewModel(
    private val authService: AccountAuthServicing,
    private val sessionStore: LinkedInSessionStore,
    private val draftStore: DraftStore,
    private val aiSettingsStore: AISettingsStore,
    private val scope: CoroutineScope,
    private val clearStreakCache: (String) -> Unit = {},
    private val telemetry: (AppTelemetryEvent) -> Unit = { event -> AppTelemetry.track(event) },
) {
    var isConnecting by mutableStateOf(false)
        private set

    var isLoggingOut by mutableStateOf(false)
        private set

    var isDeletingAccount by mutableStateOf(false)
        private set

    val token: StateFlow<LinkedInToken?> = sessionStore.token

    val statusMessage: StateFlow<String?> = sessionStore.statusMessage

    val isConnected: StateFlow<Boolean> = sessionStore.token
        .map { token ->
            token != null && token.accessToken.isNotEmpty() && token.profile.memberID.isNotEmpty()
        }
        .stateIn(scope, SharingStarted.Eagerly, sessionStore.isConnected)

    fun connect() {
        telemetry(AppTelemetryEvent.LinkedInConnectStarted)
        isConnecting = true
        scope.launch {
            try {
                val token = authService.signIn()
                sessionStore.setToken(token)
                telemetry(AppTelemetryEvent.LinkedInConnected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionStore.setStatusMessage(e.message ?: "LinkedIn authorization failed.")
            } finally {
                isConnecting = false
            }
        }
    }

    fun logOut() {
        val token = sessionStore.token.value
        val memberID = token?.profile?.memberID
        isLoggingOut = true
        scope.launch {
            try {
                if (memberID != null && token != null) {
                    authService.disconnect(memberID, token.accessToken)
                    clearStreakCache(memberID)
                }
                sessionStore.disconnect()
                aiSettingsStore.clearAPIKey()
                sessionStore.setStatusMessage("Logged out.")
                isLoggingOut = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionStore.setStatusMessage(e.message ?: "Log out failed.")
                isLoggingOut = false
            }
        }
    }

    fun deleteAccount(onSuccess: () -> Unit = {}) {
        val token = sessionStore.token.value
        val memberID = token?.profile?.memberID
        isDeletingAccount = true
        scope.launch {
            try {
                if (memberID != null && token != null) {
                    authService.deleteAccount(memberID, token.accessToken)
                }
                draftStore.deleteAccountData(ownerID = memberID)
                if (memberID != null) {
                    clearStreakCache(memberID)
                }
                sessionStore.deleteAccountData()
                aiSettingsStore.clearAccountDeletionData()
                authService.requireFreshSignInOnce()
                sessionStore.setStatusMessage("Account deleted.")
                isDeletingAccount = false
                telemetry(AppTelemetryEvent.AccountDeleted)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionStore.setStatusMessage(e.message ?: "Account deletion failed.")
                isDeletingAccount = false
            }
        }
    }
}
