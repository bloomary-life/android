package life.bloomary.app.features.drafts.domain

import java.time.Instant
import java.util.UUID
import life.bloomary.app.features.drafts.api.SyncedDraftsError
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.features.drafts.model.DraftSyncImage
import life.bloomary.app.shared.network.AppJson
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftSyncPolicyTests {
    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val draftID = UUID.randomUUID()

    private fun mutation(attempts: Int = 0) =
        DraftSyncMutation(ownerID = "m1", operation = DraftSyncOperation.upsert, draftID = draftID, attemptCount = attempts)

    // Verifies: AND-008
    @Test
    fun failedMutationBacksOffThenParksAfterFiveAttempts() {
        val first = DraftSyncPolicy.markFailed(mutation(), SyncedDraftsError.Server("down"), now, jitterSeconds = 0)
        assertEquals(now.plusSeconds(30), first.nextAttemptAt)
        assertTrue(DraftSyncPolicy.dueMutations(listOf(first), now).isEmpty())
        assertEquals(listOf(first), DraftSyncPolicy.dueMutations(listOf(first), now.plusSeconds(30)))

        val fifth = DraftSyncPolicy.markFailed(mutation(attempts = 4), SyncedDraftsError.Server("down"), now, jitterSeconds = 0)
        assertTrue(DraftSyncPolicy.isParked(fifth))
        assertTrue(DraftSyncPolicy.dueMutations(listOf(fifth), now.plusSeconds(86_400)).isEmpty())
    }

    // Verifies: AND-008
    @Test
    fun validationErrorParksImmediatelyAndRetryRearms() {
        val failed = DraftSyncPolicy.markFailed(mutation(), SyncedDraftsError.Validation("too long"), now)
        assertTrue(DraftSyncPolicy.isParked(failed))

        val rearmed = DraftSyncPolicy.resetParked(listOf(failed)).single()
        assertEquals(0, rearmed.attemptCount)
        assertEquals(listOf(rearmed), DraftSyncPolicy.dueMutations(listOf(rearmed), now))
    }

    @Test
    fun parkedMutationSurvivesPersistence() {
        val parked = DraftSyncPolicy.markFailed(mutation(), SyncedDraftsError.Validation("x"), now)
        val codec = ListSerializer(DraftSyncMutation.serializer())
        val decoded = AppJson.decodeFromString(codec, AppJson.encodeToString(codec, listOf(parked))).single()
        assertTrue(DraftSyncPolicy.isParked(decoded))
    }

    @Test
    fun enqueueReplacesQueuedMutationForSameDraft() {
        val delete = mutation().copy(operation = DraftSyncOperation.delete)
        assertEquals(listOf(delete), DraftSyncPolicy.enqueue(delete, listOf(mutation())))
    }

    @Test
    fun mergeKeepsNewerLocalButTakesRemoteStatusAndKeepsImageBytes() {
        val image = DraftSyncImage(imageId = "img", mimeType = "image/jpeg", sizeBytes = 3, checksumSHA256 = "abc")
        val local = Draft(id = draftID, body = "local", imageData = byteArrayOf(1, 2, 3), remoteImage = image, updatedAt = now)
        val olderRemote = local.copy(body = "remote", imageData = null, status = "published", updatedAt = now.minusSeconds(5))
        assertEquals(local.copy(status = "published"), DraftSyncPolicy.merge(listOf(local), listOf(olderRemote)).single())

        val newerRemote = olderRemote.copy(updatedAt = now.plusSeconds(5))
        val merged = DraftSyncPolicy.merge(listOf(local), listOf(newerRemote)).single()
        assertEquals("remote", merged.body)
        assertTrue(merged.imageData.contentEquals(byteArrayOf(1, 2, 3)))
    }
}
