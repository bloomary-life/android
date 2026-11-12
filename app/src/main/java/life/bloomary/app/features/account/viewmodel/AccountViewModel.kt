package life.bloomary.app.features.account.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import life.bloomary.app.features.ai.storage.AISettingsStore
import life.bloomary.app.features.drafts.model.DraftStore
import life.bloomary.app.features.drafts.model.PlanAccess
import life.bloomary.app.features.drafts.storage.SyncSettingsStore
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
    private val telemetry: (AppTelemetryEvent) -> Unit = { event -> AppTelemetry.track(event) },
    private val syncSettingsStore: SyncSettingsStore = SyncSettingsStore(),
    /** (memberID, accessToken) → zip bytes of every server-side draft. */
    private val exportDraftArchive: (suspend (String, String) -> ByteArray)? = null,
    /** Clears per-member caches outside this slice (entitlement, publish attempts). */
    private val clearAccountCaches: (String) -> Unit = {},
) {
    var isConnecting by mutableStateOf(false)
        private set

    var isLoggingOut by mutableStateOf(false)
        private set

    var isDeletingAccount by mutableStateOf(false)
        private set

    var isExportingDrafts by mutableStateOf(false)
        private set

    /** Zip bytes waiting for the member to pick a file; cleared once written. */
    var exportedDrafts by mutableStateOf<ByteArray?>(null)
        private set

    val plan: StateFlow<PlanAccess> = draftStore.plan

    val isDataReductionEnabled: StateFlow<Boolean> = syncSettingsStore.isDataReductionEnabled

    fun setDataReductionEnabled(enabled: Boolean) = syncSettingsStore.setDataReductionEnabled(enabled)

    /** Plan line from `docs/product/tiers.md`; purchases happen on the web or iOS, Android only shows them. */
    fun planStatus(plan: PlanAccess, now: Instant = Instant.now()): String = when {
        plan.isPro -> "Pro · " + if (plan.subscriptionSource == "apple") "App Store" else "Web"
        plan.tier == PlanAccess.Tier.Trial -> {
            val end = plan.accessUntil
            if (end != null && end.isAfter(now)) {
                val days = maxOf(1L, ceil(Duration.between(now, end).seconds / 86_400.0).toLong())
                "Free trial · $days days left · ends ${AccessDates.formatDate(end)}"
            } else {
                "Free trial"
            }
        }
        plan.tier == PlanAccess.Tier.Lapsed -> "No active plan"
        else -> "Your plan"
    }

    /** Every access state may export, lapsed included. */
    fun exportDrafts() {
        val export = exportDraftArchive ?: return
        val token = sessionStore.token.value ?: return
        if (isExportingDrafts) return
        isExportingDrafts = true
        scope.launch {
            try {
                exportedDrafts = export(token.profile.memberID, token.accessToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionStore.setStatusMessage(e.message ?: "Draft export failed.")
            } finally {
                isExportingDrafts = false
            }
        }
    }

    fun exportWritten(success: Boolean) {
        exportedDrafts = null
        sessionStore.setStatusMessage(if (success) "Drafts exported." else "Export not saved.")
    }

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
        if (isDeletingAccount) return
        isDeletingAccount = true
        scope.launch {
            // Stop sync first so no queued upload recreates server drafts after deletion.
            draftStore.suspendAccountWork()
            try {
                if (memberID != null && token != null) {
                    authService.deleteAccount(memberID, token.accessToken)
                }
                draftStore.deleteAccountData(ownerID = memberID)
                memberID?.let(clearAccountCaches)
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
                draftStore.resumeAccountWork()
                sessionStore.setStatusMessage(e.message ?: "Account deletion failed.")
                isDeletingAccount = false
            }
        }
    }
}

private object AccessDates {
    fun formatDate(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)
}
