package life.bloomary.app.application.di

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import life.bloomary.app.application.config.AppConfiguration
import life.bloomary.app.application.navigation.MainTabCoordinator
import life.bloomary.app.features.account.viewmodel.AccountViewModel
import life.bloomary.app.features.ai.api.NaturalizeService
import life.bloomary.app.features.ai.storage.AISettingsStore
import life.bloomary.app.features.ai.storage.KeystoreAIAPIKeyStore
import life.bloomary.app.features.drafts.model.DraftStore
import life.bloomary.app.features.drafts.storage.DataStoreDraftRepository
import life.bloomary.app.features.drafts.storage.DraftRepository
import life.bloomary.app.features.drafts.viewmodel.DraftsViewModel
import life.bloomary.app.features.editor.viewmodel.EditorViewModel
import life.bloomary.app.features.entitlement.api.EntitlementService
import life.bloomary.app.features.feedback.api.FeedbackService
import life.bloomary.app.features.linkedinauth.api.DataStoreLinkedInAuthFlagStore
import life.bloomary.app.features.linkedinauth.api.LinkedInAuthService
import life.bloomary.app.features.linkedinauth.model.LinkedInAuthForegroundSignal
import life.bloomary.app.features.linkedinauth.model.LinkedInSessionStore
import life.bloomary.app.features.linkedinauth.storage.AuthTokenStore
import life.bloomary.app.features.linkedinauth.storage.LinkedInTokenStore
import life.bloomary.app.features.linkedinpost.api.LinkedInPostService
import life.bloomary.app.features.notifications.api.NotificationPushRegistrar
import life.bloomary.app.features.notifications.api.NotificationTokenService
import life.bloomary.app.features.notifications.storage.DataStorePushTokenStore
import life.bloomary.app.shared.observability.AppTelemetry

/**
 * Manual DI container mirroring ios/Application/DI/AppContainer.swift.
 */
class AppContainer(
    val configuration: AppConfiguration,
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val draftRepository: DraftRepository =
        DataStoreDraftRepository(appContext, configuration.storage.draftsKey)

    val tokenStore: LinkedInTokenStore =
        AuthTokenStore(appContext, configuration.storage.linkedInTokenKey)

    private val authFlags = DataStoreLinkedInAuthFlagStore(appContext)

    val linkedInSessionStore: LinkedInSessionStore = LinkedInSessionStore(
        tokenStore = tokenStore,
        requireFreshSignInFlag = { authFlags.requiresFreshSignIn = true },
    )

    val authService: LinkedInAuthService = LinkedInAuthService(
        apiBaseURL = configuration.api.baseURL,
        callbackURLScheme = configuration.linkedIn.callbackURLScheme,
        tokenStore = tokenStore,
        flags = authFlags,
        openAuthorization = ::openAuthorization,
        waitForForeground = ::waitForLinkedInForegroundReturn,
    )

    val postService: LinkedInPostService =
        LinkedInPostService(apiBaseURL = configuration.api.baseURL)

    val naturalizeService: NaturalizeService = NaturalizeService()

    val entitlementService: EntitlementService =
        EntitlementService(apiBaseURL = configuration.api.baseURL)

    val feedbackService: FeedbackService =
        FeedbackService(apiBaseURL = configuration.api.baseURL)

    val notificationTokenService: NotificationTokenService =
        NotificationTokenService(apiBaseURL = configuration.api.baseURL)

    val notificationPushRegistrar: NotificationPushRegistrar = NotificationPushRegistrar(
        tokenService = notificationTokenService,
        tokenStore = DataStorePushTokenStore(appContext),
    )

    val draftStore: DraftStore = DraftStore(
        draftRepository = draftRepository,
        telemetry = AppTelemetry::track,
    )

    val aiSettingsStore: AISettingsStore = AISettingsStore(
        keyStore = KeystoreAIAPIKeyStore(appContext),
    )

    val mainTabCoordinator: MainTabCoordinator = MainTabCoordinator(draftStore)

    val draftsViewModel: DraftsViewModel = DraftsViewModel(draftStore)

    val editorViewModel: EditorViewModel = EditorViewModel(
        store = draftStore,
        sessionStore = linkedInSessionStore,
        authService = authService,
        postService = postService,
        naturalizeService = naturalizeService,
        aiSettingsStore = aiSettingsStore,
        scope = scope,
    )

    val accountViewModel: AccountViewModel = AccountViewModel(
        authService = authService,
        sessionStore = linkedInSessionStore,
        draftStore = draftStore,
        aiSettingsStore = aiSettingsStore,
        scope = scope,
    )

    private suspend fun openAuthorization(authURL: String): Boolean = withContext(Dispatchers.Main.immediate) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, authURL.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun waitForLinkedInForegroundReturn() {
        delay(500)
        LinkedInAuthForegroundSignal.foregroundReturns.first()
    }
}
