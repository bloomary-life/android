package life.bloomary.app.features.editor.viewmodel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.bloomary.app.features.ai.api.NaturalizeService
import life.bloomary.app.features.ai.storage.AIAPIKeyStore
import life.bloomary.app.features.ai.storage.AISettingsStore
import life.bloomary.app.features.drafts.domain.DraftSnapshot
import life.bloomary.app.features.drafts.model.DraftStore
import life.bloomary.app.features.drafts.storage.DraftRepository
import life.bloomary.app.features.linkedinauth.api.LinkedInAuthFlagStore
import life.bloomary.app.features.linkedinauth.api.LinkedInAuthService
import life.bloomary.app.features.linkedinauth.model.LinkedInSessionStore
import life.bloomary.app.features.linkedinauth.model.LinkedInToken
import life.bloomary.app.features.linkedinauth.storage.LinkedInTokenStore
import life.bloomary.app.features.linkedinpost.api.LinkedInPostService
import life.bloomary.app.shared.storage.InMemoryBooleanSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelConsentTests {

    private object NoDrafts : DraftRepository {
        override fun loadSnapshot(ownerID: String?) = DraftSnapshot.empty(ownerID)
        override fun saveSnapshot(snapshot: DraftSnapshot, ownerID: String?) {}
        override fun deleteDrafts(ownerID: String?) {}
    }

    private object NoTokens : LinkedInTokenStore {
        override fun load(): LinkedInToken? = null
        override fun save(token: LinkedInToken) {}
        override fun clear() {}
    }

    private class Keys(var key: String? = "gemini-key") : AIAPIKeyStore {
        override fun load() = key
        override fun save(apiKey: String): Boolean { key = apiKey; return true }
        override fun clear(): Boolean { key = null; return true }
    }

    // Verifies: AND-006
    @Test
    fun naturalizeSendsNothingUntilAllowedAndAllowIsRememberedUntilAccountDeletion() = runTest {
        // OkHttp runs on Dispatchers.IO (a real thread), so count with thread-safe primitives.
        val geminiRequests = AtomicInteger(0)
        val requested = CountDownLatch(1)
        val gemini = OkHttpClient.Builder().addInterceptor { chain ->
            geminiRequests.incrementAndGet()
            requested.countDown()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"choices":[{"message":{"role":"assistant","content":"Hello there."}}]}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val consent = InMemoryBooleanSetting()
        val aiSettings = AISettingsStore(Keys(), consent)
        val store = DraftStore(NoDrafts, scope = backgroundScope, telemetry = { _, _ -> }, periodicSyncIntervalMs = 0)
        val flags = object : LinkedInAuthFlagStore {
            override var pendingState: String? = null
            override var requiresFreshSignIn: Boolean = false
        }
        val viewModel = EditorViewModel(
            store = store,
            sessionStore = LinkedInSessionStore(NoTokens),
            authService = LinkedInAuthService("https://api.invalid", "bloomary", NoTokens, flags, openAuthorization = { false }, waitForForeground = {}),
            postService = LinkedInPostService("https://api.invalid"),
            naturalizeService = NaturalizeService(client = gemini),
            aiSettingsStore = aiSettings,
            scope = backgroundScope,
        )
        viewModel.editorText = "hello their"

        viewModel.naturalizeEnglish()
        runCurrent()
        assertTrue(viewModel.showsAIConsent)
        assertEquals(0, geminiRequests.get())

        viewModel.allowAIAndNaturalize()
        runCurrent()
        assertTrue(consent.load())
        assertTrue(requested.await(5, TimeUnit.SECONDS))
        assertEquals(1, geminiRequests.get())

        // A fresh store reads the remembered choice.
        assertTrue(AISettingsStore(Keys(), consent).hasAIConsent.value)

        aiSettings.clearAccountDeletionData()
        assertFalse(AISettingsStore(Keys(), consent).hasAIConsent.value)
    }
}
