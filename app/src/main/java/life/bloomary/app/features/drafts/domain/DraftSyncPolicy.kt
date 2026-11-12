package life.bloomary.app.features.drafts.domain

import java.time.Instant
import java.util.UUID
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.serialization.Serializable
import life.bloomary.app.features.drafts.api.SyncedDraftsError
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.shared.network.Iso8601InstantSerializer
import life.bloomary.app.shared.network.UUIDSerializer

/**
 * Offline sync queue and merge rules. Pure: no Android, network or storage
 * imports. Mirrors ios/Features/Drafts/Domain/DraftSyncPolicy.swift.
 */
@Serializable
enum class DraftSyncOperation { upsert, delete }

@Serializable
data class DraftSyncMutation(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val ownerID: String,
    val operation: DraftSyncOperation,
    @Serializable(with = UUIDSerializer::class)
    val draftID: UUID,
    val draft: Draft? = null,
    @Serializable(with = Iso8601InstantSerializer::class)
    val queuedAt: Instant = Instant.now(),
    val attemptCount: Int = 0,
    val lastError: String? = null,
    @Serializable(with = Iso8601InstantSerializer::class)
    val nextAttemptAt: Instant? = null,
)

object DraftSyncPolicy {
    const val maxRetryAttempts = 5
    const val maxBackoffSeconds = 300L
    const val maxJitterSeconds = 15L

    /** Stand-in for Swift's `Date.distantFuture`: a parked mutation never comes due on its own. */
    val parkedAt: Instant = Instant.parse("9999-12-31T23:59:59Z")

    /** A newer mutation for the same draft replaces any queued one. */
    fun enqueue(mutation: DraftSyncMutation, queue: List<DraftSyncMutation>): List<DraftSyncMutation> =
        queue.filterNot { it.ownerID == mutation.ownerID && it.draftID == mutation.draftID } + mutation

    fun dueMutations(queue: List<DraftSyncMutation>, now: Instant = Instant.now()): List<DraftSyncMutation> =
        queue.filter { !isParked(it) && (it.nextAttemptAt ?: Instant.MIN) <= now }

    fun markFailed(
        mutation: DraftSyncMutation,
        error: Throwable,
        now: Instant = Instant.now(),
        jitterSeconds: Long = Random.nextLong(0, maxJitterSeconds + 1),
    ): DraftSyncMutation {
        val attempts = mutation.attemptCount + 1
        val parked = error is SyncedDraftsError.Validation || attempts >= maxRetryAttempts
        return mutation.copy(
            attemptCount = attempts,
            lastError = error.message,
            nextAttemptAt = if (parked) parkedAt else now.plusSeconds(backoffSeconds(attempts, jitterSeconds)),
        )
    }

    fun isParked(mutation: DraftSyncMutation): Boolean = mutation.nextAttemptAt == parkedAt

    fun resetParked(queue: List<DraftSyncMutation>): List<DraftSyncMutation> =
        queue.map { if (isParked(it)) it.copy(attemptCount = 0, nextAttemptAt = null) else it }

    /**
     * Newest `updatedAt` wins per draft; a local winner still takes the
     * server's status. A remote winner without downloaded image bytes keeps the
     * local bytes when the image ID matches. The draft limit is not enforced
     * here (soft limit, checked on new draft creation).
     */
    fun merge(local: List<Draft>, remote: List<Draft>): List<Draft> {
        val byID = local.associateBy { it.id }.toMutableMap()
        for (draft in remote) {
            val existing = byID[draft.id]
            if (existing != null && existing.updatedAt >= draft.updatedAt) {
                byID[draft.id] = existing.copy(status = draft.status)
                continue
            }
            var merged = draft
            if (existing != null && merged.imageData == null && merged.remoteImage != null &&
                merged.remoteImage?.imageId == existing.remoteImage?.imageId
            ) {
                merged = merged.copy(
                    imageData = existing.imageData,
                    imageFilename = existing.imageFilename,
                    imageMimeType = existing.imageMimeType,
                )
            }
            byID[draft.id] = merged
        }
        return byID.values.sortedByDescending { it.updatedAt }
    }

    private fun backoffSeconds(attemptCount: Int, jitterSeconds: Long): Long {
        val jitter = jitterSeconds.coerceIn(0, maxJitterSeconds)
        val base = (2.0.pow(attemptCount - 1) * 30).toLong()
        return minOf(maxBackoffSeconds, maxOf(30L, base) + jitter)
    }
}
