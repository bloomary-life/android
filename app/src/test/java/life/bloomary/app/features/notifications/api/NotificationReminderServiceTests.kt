package life.bloomary.app.features.notifications.api

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of ios/BloomaryTests/NotificationReminderServiceTests.swift. Covers the
 * pure scheduling policy; alarm/permission plumbing needs a Context and stays
 * untested, like the iOS tests covering only `pendingReminderSchedules`.
 */
class NotificationReminderServiceTests {

    private val zone: ZoneId = ZoneId.of("UTC")

    @Test
    fun pendingReminderSchedulesBeforeMorningIncludesBothSlots() {
        val schedules = NotificationReminderService.pendingReminderSchedules(
            memberID = "member-1",
            localDate = "2026-09-05",
            now = Instant.parse("2026-09-05T08:30:00Z"),
            zone = zone,
        )

        assertEquals(
            listOf(
                "bloomary.notification.member-1.2026-09-05.post_reminder.0900",
                "bloomary.notification.member-1.2026-09-05.post_reminder.1630",
            ),
            schedules.map { it.identifier },
        )
        assertEquals(listOf(9, 16), schedules.map { hourOf(it.triggerAtMillis) })
        assertEquals(listOf(0, 30), schedules.map { minuteOf(it.triggerAtMillis) })
    }

    @Test
    fun pendingReminderSchedulesAfterMorningIncludesAfternoonOnly() {
        val schedules = NotificationReminderService.pendingReminderSchedules(
            memberID = "member-1",
            localDate = "2026-09-05",
            now = Instant.parse("2026-09-05T09:01:00Z"),
            zone = zone,
        )

        assertEquals(
            listOf("bloomary.notification.member-1.2026-09-05.post_reminder.1630"),
            schedules.map { it.identifier },
        )
        assertEquals(listOf(16), schedules.map { hourOf(it.triggerAtMillis) })
        assertEquals(listOf(30), schedules.map { minuteOf(it.triggerAtMillis) })
    }

    @Test
    fun pendingReminderSchedulesAfterAfternoonIncludesNoSlots() {
        val schedules = NotificationReminderService.pendingReminderSchedules(
            memberID = "member-1",
            localDate = "2026-09-05",
            now = Instant.parse("2026-09-05T16:31:00Z"),
            zone = zone,
        )

        assertTrue(schedules.isEmpty())
    }

    private fun hourOf(triggerAtMillis: Long): Int =
        Instant.ofEpochMilli(triggerAtMillis).atZone(zone).hour

    private fun minuteOf(triggerAtMillis: Long): Int =
        Instant.ofEpochMilli(triggerAtMillis).atZone(zone).minute
}
