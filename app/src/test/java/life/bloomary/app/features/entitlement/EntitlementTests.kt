package life.bloomary.app.features.entitlement

import java.time.Instant
import kotlinx.coroutines.test.runTest
import life.bloomary.app.features.entitlement.api.Entitlement
import life.bloomary.app.features.entitlement.api.EntitlementService
import life.bloomary.app.features.entitlement.api.EntitlementTier
import life.bloomary.app.features.entitlement.ui.AccessBannerCopy
import life.bloomary.app.features.linkedinpost.api.LinkedInPostCredential
import life.bloomary.app.shared.network.AppJson
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementTests {

    // Verifies: AND-007
    @Test
    fun serverTierIsRenderedNotDerived() {
        val trial = AppJson.decodeFromString(Entitlement.serializer(), """{"isPro":false,"tier":"trial"}""")
        assertEquals(EntitlementTier.trial, trial.accessTier)
        val future = AppJson.decodeFromString(Entitlement.serializer(), """{"isPro":true,"tier":"gold"}""")
        assertEquals(EntitlementTier.pro, future.accessTier) // unknown tier falls back to isPro, never fails decoding
    }

    // Verifies: AND-007
    @Test
    fun lapsedBannerNamesWhatEnded() {
        val ended = Instant.parse("2026-11-01T12:00:00Z")
        assertTrue(AccessBannerCopy.lapsed("paddle", ended).startsWith("Your subscription ended on "))
        assertTrue(AccessBannerCopy.lapsed(null, null) == "Your 14-day trial ended. Your drafts are read-only.")
    }

    @Test
    fun entitlementRequestCarriesBearerAndCaches() = runTest {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody("""{"isPro":false,"tier":"lapsed","subscriptionSource":"trial"}"""))
        val service = EntitlementService(apiBaseURL = server.url("/").toString().trimEnd('/'))
        val credential = LinkedInPostCredential(accessToken = "token-1", memberID = "member-1")

        val first = service.entitlement(credential)
        val second = service.entitlement(credential)

        assertEquals(EntitlementTier.lapsed, first.accessTier)
        assertEquals(first, second)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/api/entitlement?memberId=member-1", request.path)
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
        server.shutdown()
    }
}
