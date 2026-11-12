package life.bloomary.app.features.drafts.model

import java.time.Instant

/**
 * What the drafts slice needs from the member's plan. The app layer maps the
 * server entitlement (`features/entitlement`) onto it, so this slice never
 * imports another feature. Rules from `docs/product/tiers.md`:
 * trial and pro have access; lapsed drafts are read-only.
 */
data class PlanAccess(
    /** null until the first entitlement response (signed out, or still loading). */
    val tier: Tier? = null,
    val isPro: Boolean = false,
    val subscriptionSource: String? = null,
    val accessUntil: Instant? = null,
    val maxDraftBodyRunes: Int = 4000,
    val maxImageBytes: Int = 5 * 1024 * 1024,
) {
    enum class Tier { Trial, Pro, Lapsed }

    val hasPlanAccess: Boolean get() = tier != null && tier != Tier.Lapsed
    val isLapsed: Boolean get() = tier == Tier.Lapsed
    val maxDrafts: Int get() = if (hasPlanAccess) 50 else 5
}

/** A server-side schedule for one draft. Mirrors `ScheduledPostResult` (LinkedInPostService.swift). */
data class DraftSchedule(
    val scheduleId: String,
    val draftId: String?,
    val status: String,
    /** ISO-8601 as sent by the server. */
    val scheduledAt: String,
    val message: String?,
) {
    val scheduledInstant: Instant?
        get() = runCatching { Instant.parse(scheduledAt) }.getOrNull()

    fun isFor(draft: Draft): Boolean = draftId?.equals(draft.serverID, ignoreCase = true) == true

    val isActive: Boolean get() = status in setOf("pending", "processing", "retry", "needs_review")
}

/**
 * Scheduling backend the drafts and editor screens call. Implemented in the
 * app layer on top of `LinkedInPostService`, so drafts does not depend on the
 * posting slice.
 */
interface DraftScheduling {
    suspend fun schedule(serverID: String, at: Instant, reviewConfirmed: Boolean, memberID: String, accessToken: String): DraftSchedule
    suspend fun unschedule(serverID: String, memberID: String, accessToken: String)
    suspend fun scheduleStatus(scheduleID: String, memberID: String, accessToken: String): DraftSchedule
    suspend fun scheduledPosts(memberID: String, accessToken: String): List<DraftSchedule>
}
