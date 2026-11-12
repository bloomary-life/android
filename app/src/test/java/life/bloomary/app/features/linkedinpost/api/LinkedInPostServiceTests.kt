package life.bloomary.app.features.linkedinpost.api

import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LinkedInPostServiceTests {
    private lateinit var server: MockWebServer
    private lateinit var service: LinkedInPostService
    private val credential = LinkedInPostCredential(accessToken = "token-1", memberID = "member-1")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        service = LinkedInPostService(apiBaseURL = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    // Verifies: AND-002
    @Test
    fun publishingSavedDraftSendsDraftIdWithBearerToken() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok","postId":"urn:li:share:1"}"""))

        val result = service.publish(text = "Hello", credential = credential, draftID = "draft-42")

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("/api/linkedin/post", request.path)
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
        assertTrue(body.contains("name=\"draftId\"\r\n\r\ndraft-42"))
        // The token travels in the header only, never in the form body.
        assertFalse(body.contains("token-1"))
        assertEquals("urn:li:share:1", result.postID)
    }

    // Verifies: AND-002
    @Test
    fun unsavedPostOmitsDraftId() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        service.publish(text = "Hello", credential = credential)
        assertFalse(server.takeRequest().body.readUtf8().contains("draftId"))
    }

    // Verifies: AND-003
    @Test
    fun unscheduleSendsDeleteWithDraftId() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        service.unschedule(draftID = "draft-42", credential = credential)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/linkedin/schedule?memberId=member-1&draftId=draft-42", request.path)
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
    }

    @Test
    fun scheduleRoundTrip() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"scheduleId":"s1","draftId":"draft-42","status":"pending","scheduledAt":"2026-11-12T10:00:00Z"}"""),
        )

        val result = service.schedule("draft-42", Instant.parse("2026-11-12T10:00:00Z"), credential = credential)

        val request = server.takeRequest()
        assertEquals("/api/linkedin/schedule?memberId=member-1", request.path)
        assertEquals("""{"reviewConfirmed":false,"draftId":"draft-42","scheduledAt":"2026-11-12T10:00:00Z"}""", request.body.readUtf8())
        assertEquals("pending", result.status)
    }

    @Test
    fun unauthorizedPublishMapsToAuthorizationExpired() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            service.publish(text = "Hello", credential = credential)
            fail("expected AuthorizationExpired")
        } catch (e: LinkedInPostError.AuthorizationExpired) {
            // expected
        }
    }
}
