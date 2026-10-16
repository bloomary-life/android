package life.bloomary.app.features.streak.model

import kotlinx.serialization.Serializable

/**
 * Mirrors ios/Features/Streak/Model/Streak.swift.
 */
@Serializable
data class Streak(
    val current: Int,
    val longest: Int,
    val lastPostedDay: String? = null,
    val weekDays: List<Boolean>,
)

object StreakFooter {
    fun text(day: Int): String = "\n\nDay $day 🔥 posting daily — tracked with Bloomary"
}
