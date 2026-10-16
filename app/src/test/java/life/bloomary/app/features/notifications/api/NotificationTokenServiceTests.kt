package life.bloomary.app.features.notifications.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import life.bloomary.app.features.linkedinpost.api.LinkedInPostCredential
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers the port of ios/Features/Notifications/API/NotificationTokenService.swift
 * (untested on iOS). HTTP is faked with a short-circuiting OkHttp interceptor,
 * so no network or mock framework is needed.
 */
class NotificationTokenServiceTests {

    private val credential = LinkedInPostCredential(accessToken = "access-123", memberID = "member-1")

    @Test
    fun registerSendsContractRequest() = runTest {
        var captured: Request? = null
        val service = NotificationTokenService(
            apiBaseURL = "https://api.bloomary.life",
            client = fakeClient(code = 200, body = "{}", capture = { captured = it }),
        )

        service.register(token = "  fcm-token-1  ", credential = credential)

        val request = captured!!
        assertEquals("PUT", request.method)
        assertEquals("https://api.bloomary.life/api/notifications/token", request.url.toString())
        assertEquals("Bearer access-123", request.header("Authorization"))
        assertTrue(request.body?.contentType()?.toString()?.startsWith("application/json") == true)

        val payload = Json.parseToJsonElement(request.bodyBytes()).jsonObject
        assertEquals("android", payload.getValue("platform").jsonPrimitive.content)
        assertEquals("fcm-token-1", payload.getValue("token").jsonPrimitive.content)
        assertEquals("true", payload.getValue("enabled").jsonPrimitive.content)
        assertTrue(payload.getValue("timezone").jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun registerSendsEnabledFalseWhenRequested() = runTest {
        var captured: Request? = null
        val service = NotificationTokenService(
            apiBaseURL = "https://api.bloomary.life",
            client = fakeClient(code = 200, body = "{}", capture = { captured = it }),
        )

        service.register(token = "fcm-token-1", credential = credential, enabled = false)

        val payload = Json.parseToJsonElement(captured!!.bodyBytes()).jsonObject
        assertEquals("false", payload.getValue("enabled").jsonPrimitive.content)
    }

    @Test
    fun registerThrowsMissingAuthWithoutCredential() = runTest {
        val service = NotificationTokenService(
            apiBaseURL = "https://api.bloomary.life",
            client = fakeClient(code = 200, body = "{}"),
        )

        try {
            service.register(token = "fcm-token-1", credential = LinkedInPostCredential(accessToken = "", memberID = ""))
            fail("Expected MissingAuth")
        } catch (e: NotificationTokenError.MissingAuth) {
            assertEquals("Connect LinkedIn first.", e.message)
        }
    }

    @Test
    fun registerThrowsEmptyTokenForBlankToken() = runTest {
        val service = NotificationTokenService(
            apiBaseURL = "https://api.bloomary.life",
            client = fakeClient(code = 200, body = "{}"),
        )

        try {
            service.register(token = "   ", credential = credential)
            fail("Expected EmptyToken")
        } catch (e: NotificationTokenError.EmptyToken) {
            assertEquals("Notification token is empty.", e.message)
        }
    }

    @Test
    fun registerThrowsServerWithDecodedDescription() = runTest {
        val service = NotificationTokenService(
            apiBaseURL = "https://api.bloomary.life",
            client = fakeClient(code = 400, body = """{"error":"bad_request","errorDescription":"Token rejected."}"""),
        )

        try {
            service.register(token = "fcm-token-1", credential = credential)
            fail("Expected Server")
        } catch (e: NotificationTokenError.Server) {
            assertEquals("Token rejected.", e.message)
        }
    }

    @Test
    fun registerThrowsGenericServerErrorForUndecodableBody() = runTest {
        val service = NotificationTokenService(
            apiBaseURL = "https://api.bloomary.life",
            client = fakeClient(code = 500, body = "not json"),
        )

        try {
            service.register(token = "fcm-token-1", credential = credential)
            fail("Expected Server")
        } catch (e: NotificationTokenError.Server) {
            assertEquals("Notification registration failed.", e.message)
        }
    }

    private fun fakeClient(code: Int, body: String, capture: (Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            capture(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

    private fun Request.bodyBytes(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
