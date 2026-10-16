package life.bloomary.app.features.notifications.api

import java.time.Instant
import kotlinx.coroutines.test.runTest
import life.bloomary.app.features.linkedinauth.model.LinkedInProfile
import life.bloomary.app.features.linkedinauth.model.LinkedInToken
import life.bloomary.app.features.notifications.storage.PushTokenStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the registration orchestration around NotificationTokenService:
 * session gating, token-refresh handling, and per-member dedupe. HTTP is
 * faked with a short-circuiting OkHttp interceptor.
 */
class NotificationPushRegistrarTests {

    @Test
    fun syncRegistersFetchedTokenWhenSessionAvailable() = runTest {
        val store = FakePushTokenStore()
        var registrations = 0
        val registrar = registrar(store, onRegister = { registrations++ })

        registrar.syncRegistration(session())

        assertEquals(1, registrations)
        assertEquals("fcm-token-1", store.registeredToken("member-1"))
    }

    @Test
    fun syncSkipsRegistrationWhenSignedOut() = runTest {
        val store = FakePushTokenStore()
        var registrations = 0
        val registrar = registrar(store, onRegister = { registrations++ })

        registrar.syncRegistration(null)

        assertEquals(0, registrations)
        assertNull(store.registeredToken("member-1"))
    }

    @Test
    fun syncDedupesAlreadyRegisteredToken() = runTest {
        val store = FakePushTokenStore()
        var registrations = 0
        val registrar = registrar(store, onRegister = { registrations++ })

        registrar.syncRegistration(session())
        registrar.syncRegistration(session())

        assertEquals(1, registrations)
    }

    @Test
    fun onNewTokenStashesTokenWhenSignedOutAndRegistersOnNextSync() = runTest {
        val store = FakePushTokenStore()
        var registrations = 0
        // No FCM token fetchable — only the stashed token can be used.
        val registrar = registrar(store, fetchToken = { null }, onRegister = { registrations++ })

        registrar.onNewToken("fcm-token-2", token = null)
        assertEquals(0, registrations)
        assertEquals("fcm-token-2", store.latestToken())

        registrar.syncRegistration(session())

        assertEquals(1, registrations)
        assertEquals("fcm-token-2", store.registeredToken("member-1"))
    }

    @Test
    fun onNewTokenRegistersImmediatelyWhenSignedIn() = runTest {
        val store = FakePushTokenStore()
        var registrations = 0
        val registrar = registrar(store, onRegister = { registrations++ })

        registrar.onNewToken("fcm-token-3", session())

        assertEquals("fcm-token-3", store.latestToken())
        assertEquals(1, registrations)
        assertEquals("fcm-token-3", store.registeredToken("member-1"))
    }

    @Test
    fun failedRegistrationIsSoftAndNotMarkedRegistered() = runTest {
        val store = FakePushTokenStore()
        val registrar = registrar(store, serverCode = 500)

        registrar.syncRegistration(session())

        assertNull(store.registeredToken("member-1"))
    }

    private fun session() = LinkedInToken(
        accessToken = "access-123",
        profile = LinkedInProfile(memberID = "member-1", name = "Member One"),
        connectedAt = Instant.ofEpochSecond(100),
    )

    private fun registrar(
        store: PushTokenStore,
        fetchToken: suspend () -> String? = { "fcm-token-1" },
        serverCode: Int = 200,
        onRegister: () -> Unit = {},
    ): NotificationPushRegistrar {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            onRegister()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(serverCode)
                .message(if (serverCode in 200..299) "OK" else "Error")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return NotificationPushRegistrar(
            tokenService = NotificationTokenService(apiBaseURL = "https://api.bloomary.life", client = client),
            tokenStore = store,
            fetchPushToken = fetchToken,
        )
    }
}

private class FakePushTokenStore : PushTokenStore {
    private var latest: String? = null
    private val registered = mutableMapOf<String, String>()

    override fun latestToken(): String? = latest
    override fun setLatestToken(token: String) {
        latest = token
    }

    override fun registeredToken(memberID: String): String? = registered[memberID]
    override fun setRegisteredToken(memberID: String, token: String) {
        registered[memberID] = token
    }
}
