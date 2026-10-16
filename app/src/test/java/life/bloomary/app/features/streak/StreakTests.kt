package life.bloomary.app.features.streak

import kotlinx.serialization.Serializable
import life.bloomary.app.features.streak.model.Streak
import life.bloomary.app.features.streak.model.StreakFooter
import life.bloomary.app.shared.network.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Port of ios/BloomaryTests/StreakTests.swift.
 */
class StreakTests {

    @Test
    fun streakDecodesContractJSON() {
        val json = """
        {
          "current": 12,
          "longest": 21,
          "lastPostedDay": "2026-08-31",
          "weekDays": [true, true, false, false, false]
        }
        """.trimIndent()

        val streak = AppJson.decodeFromString(Streak.serializer(), json)

        assertEquals(12, streak.current)
        assertEquals(21, streak.longest)
        assertEquals("2026-08-31", streak.lastPostedDay)
        assertEquals(listOf(true, true, false, false, false), streak.weekDays)
    }

    @Test
    fun streakDecodesZeroStreakWithoutLastPostedDay() {
        val json = """
        {
          "current": 0,
          "longest": 0,
          "lastPostedDay": null,
          "weekDays": [false, false, false, false, false]
        }
        """.trimIndent()

        val streak = AppJson.decodeFromString(Streak.serializer(), json)

        assertEquals(0, streak.current)
        assertNull(streak.lastPostedDay)
        assertEquals(listOf(false, false, false, false, false), streak.weekDays)
    }

    @Test
    fun publishResponseDecodesExtendedPayload() {
        val json = """
        {
          "status": "published",
          "postId": "urn:li:share:123",
          "streak": {
            "current": 12,
            "longest": 21,
            "lastPostedDay": "2026-08-31",
            "weekDays": [true, true, false, false, false]
          }
        }
        """.trimIndent()

        val streak = AppJson.decodeFromString(PostSuccessResponseFixture.serializer(), json).streak!!

        assertEquals(12, streak.current)
        assertEquals(5, streak.weekDays.size)
    }

    @Test
    fun footerTextMatchesContract() {
        assertEquals("\n\nDay 1 🔥 posting daily — tracked with Bloomary", StreakFooter.text(day = 1))
        assertEquals("\n\nDay 13 🔥 posting daily — tracked with Bloomary", StreakFooter.text(day = 13))
    }
}

@Serializable
private data class PostSuccessResponseFixture(
    val status: String? = null,
    val postId: String? = null,
    val streak: Streak? = null,
)
