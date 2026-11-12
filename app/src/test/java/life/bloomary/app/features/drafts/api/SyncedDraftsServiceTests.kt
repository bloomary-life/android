package life.bloomary.app.features.drafts.api

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import life.bloomary.app.features.drafts.model.Draft
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Round-trips through a real HTTP server: request shape, JSON codec and status mapping. */
class SyncedDraftsServiceTests {
    private lateinit var server: MockWebServer
    private lateinit var service: SyncedDraftsService

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        service = SyncedDraftsService(apiBaseURL = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    // Verifies: AND-001
    @Test
    fun upsertPostsDraftAndDecodesServerCopy() = runTest {
        val id = UUID.fromString("0D8A2F43-6E4B-4F7B-9F4C-6B7D0A1B2C3D")
        server.enqueue(
            MockResponse().setBody(
                """{"drafts":[{"draftId":"0d8a2f43-6e4b-4f7b-9f4c-6b7d0a1b2c3d","body":"Hello","status":"draft",""" +
                    """"createdAt":"2026-11-10T09:00:00Z","updatedAt":"2026-11-11T09:00:00Z"}]}""",
            ),
        )

        val synced = service.upsertDraft(
            Draft(id = id, body = "Hello", updatedAt = Instant.parse("2026-11-11T09:00:00Z")),
            memberID = "member-1",
            accessToken = "token-1",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/drafts/sync?memberId=member-1", request.path)
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
        val upsert = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["upserts"]!!.jsonArray.single().jsonObject
        assertEquals("0d8a2f43-6e4b-4f7b-9f4c-6b7d0a1b2c3d", upsert["draftId"]!!.jsonPrimitive.content)
        assertEquals("2026-11-11T09:00:00Z", upsert["updatedAt"]!!.jsonPrimitive.content)
        assertEquals(id, synced.id)
        assertEquals("draft", synced.status)
    }

    // Verifies: AND-005
    @Test
    fun unauthorizedMapsToAuthorizationExpired() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        try {
            service.listDrafts("member-1", "stale")
            fail("expected AuthorizationExpired")
        } catch (e: SyncedDraftsError.AuthorizationExpired) {
            // expected
        }
    }

    // Verifies: AND-008
    @Test
    fun payloadRejectionMapsToValidation() = runTest {
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"error":"too_large","errorDescription":"Draft too long."}"""))
        try {
            service.listDrafts("member-1", "token")
            fail("expected Validation")
        } catch (e: SyncedDraftsError.Validation) {
            assertTrue(e.message == "Draft too long.")
        }
    }
}
