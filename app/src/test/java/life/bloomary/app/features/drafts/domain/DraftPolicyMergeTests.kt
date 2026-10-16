package life.bloomary.app.features.drafts.domain

import java.time.Instant
import java.util.UUID
import life.bloomary.app.features.drafts.model.Draft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of ios/BloomaryTests/DraftPolicyMergeTests.swift.
 */
class DraftPolicyMergeTests {

    @Test
    fun mergeKeepsNewestDraft() {
        val draftID = UUID.randomUUID()
        val local = Draft(id = draftID, body = "local", updatedAt = Instant.ofEpochSecond(20))
        val remote = Draft(id = draftID, body = "remote", updatedAt = Instant.ofEpochSecond(10))

        val merged = DraftPolicy.merge(local = listOf(local), remote = listOf(remote))

        assertEquals(listOf(local), merged)
    }

    @Test
    fun mergePreservesLocalImageDataWhenRemoteIsNewer() {
        val draftID = UUID.randomUUID()
        val imageData = byteArrayOf(1, 2, 3)
        val local = Draft(
            id = draftID,
            body = "local",
            imageData = imageData,
            imageFilename = "image.jpg",
            imageMimeType = "image/jpeg",
            updatedAt = Instant.ofEpochSecond(10),
        )
        val remote = Draft(id = draftID, body = "remote", updatedAt = Instant.ofEpochSecond(20))

        val merged = DraftPolicy.merge(local = listOf(local), remote = listOf(remote))

        assertEquals("remote", merged.first().body)
        assertTrue(merged.first().imageData.contentEquals(imageData))
        assertEquals("image.jpg", merged.first().imageFilename)
        assertEquals("image/jpeg", merged.first().imageMimeType)
    }
}
