package life.bloomary.app.features.drafts.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Scheduling window and fixed posting suggestions. Mirrors
 * `AppConfiguration.Scheduling` in ios/Application/Config/AppConfiguration.swift.
 */
object SchedulePolicy {
    /** Sunday-first, like `Calendar.component(.weekday) - 1` on iOS. */
    val weekdayPostTimes = listOf("10:00–12:00", "09:00–11:00", "10:00–12:00", "09:00–11:00", "10:00–12:00", "09:00–10:00", "10:00–12:00")
    val minimumLeadTime: Duration = Duration.ofMinutes(5)
    const val defaultMaxAheadDays = 31

    /** Sunday = 0 … Saturday = 6. */
    private fun sundayFirstIndex(date: LocalDate): Int = date.dayOfWeek.value % 7

    /** Tomorrow at the start of that weekday's suggested slot. */
    fun defaultScheduleDate(now: ZonedDateTime = ZonedDateTime.now()): Instant {
        val tomorrow = now.toLocalDate().plusDays(1)
        val hour = weekdayPostTimes[sundayFirstIndex(tomorrow)].take(2).toInt()
        return tomorrow.atTime(LocalTime.of(hour, 0)).atZone(now.zone).toInstant()
    }

    fun isWithinWindow(date: Instant, maxAheadDays: Int, now: Instant = Instant.now()): Boolean =
        date >= now.plus(minimumLeadTime) && date <= now.plus(Duration.ofDays(maxAheadDays.toLong()))

    fun postingSuggestions(today: LocalDate = LocalDate.now(ZoneId.systemDefault())): String {
        val day = sundayFirstIndex(today)
        return "Posting suggestions · Today: ${weekdayPostTimes[day]} · Tomorrow: ${weekdayPostTimes[(day + 1) % 7]}. " +
            "Fixed weekday suggestions in your local time; not personalized analytics."
    }
}
