package life.bloomary.app.application.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import life.bloomary.app.application.di.AppContainer
import life.bloomary.app.application.navigation.MainTabView
import life.bloomary.app.features.linkedinauth.model.LinkedInAuthForegroundSignal
import life.bloomary.app.shared.ui.BloomaryTheme

class MainActivity : ComponentActivity() {

    private var notificationPermissionResult: CompletableDeferred<Boolean>? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionResult?.complete(granted)
            notificationPermissionResult = null
        }

    private val container: AppContainer
        get() = (application as BloomaryApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container.notificationReminderService.notificationPermissionRequester = ::requestNotificationPermission
        handleDeepLink(intent)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Mirrors UIApplication.didBecomeActiveNotification in
                // LinkedInAuthService.waitForForeground(): a pending CLI OAuth
                // flow starts/resumes result polling when the app returns to
                // the foreground. Emissions without a pending flow are dropped.
                LinkedInAuthForegroundSignal.notifyForegroundReturn()
            }
        })
        setContent {
            BloomaryTheme {
                MainTabView(
                    coordinator = container.mainTabCoordinator,
                    draftStore = container.draftStore,
                    linkedInSessionStore = container.linkedInSessionStore,
                    editorViewModel = container.editorViewModel,
                    draftsViewModel = container.draftsViewModel,
                    accountViewModel = container.accountViewModel,
                    aiSettingsStore = container.aiSettingsStore,
                    feedbackService = container.feedbackService,
                    entitlementService = container.entitlementService,
                    notificationReminderService = container.notificationReminderService,
                    notificationPushRegistrar = container.notificationPushRegistrar,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * bloomary://linkedin-connected and bloomary://editor land here
     * (singleTask + onNewIntent).
     *
     * linkedin-connected: notifies the auth flow's foreground-return signal so
     * pending polling resumes (it also arrives via lifecycle STARTED; the
     * duplicate emission is harmless). The editor deep link is deliberately not
     * navigated — iOS never handles it on notification tap.
     */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != container.configuration.linkedIn.callbackURLScheme) return
        if (data.host == "linkedin-connected") {
            LinkedInAuthForegroundSignal.notifyForegroundReturn()
        }
    }

    private suspend fun requestNotificationPermission(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext true
        notificationPermissionResult?.let { return@withContext it.await() }
        val result = CompletableDeferred<Boolean>()
        notificationPermissionResult = result
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        result.await()
    }
}
