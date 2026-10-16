package life.bloomary.app.features.drafts.domain

import java.time.Instant
import java.util.UUID
import life.bloomary.app.features.drafts.model.Draft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of ios/BloomaryTests/DraftPolicyTests.swift.
 */
class DraftPolicyTests {

    @Test
    fun saveCreatesNewDraftAtTop() {
        val existing = Draft(body = "Existing")
        val now = Instant.ofEpochSecond(100)

        val result = DraftPolicy.save(drafts = listOf(existing), editingDraftID = null, body = "New post", maxDrafts = 5, now = now)

        assertEquals(2, result.drafts.size)
        assertEquals("New post", result.drafts.first().body)
        assertEquals(now, result.drafts.first().createdAt)
        assertEquals(result.drafts.first().id, result.editingDraftID)
        assertEquals("New post", result.changedDraft?.body)
        assertEquals(false, result.limitReached)
    }

    @Test
    fun saveUpdatesEditedDraftInPlace() {
        val edited = Draft(id = UUID.randomUUID(), body = "Old", updatedAt = Instant.ofEpochSecond(10))
        val other = Draft(id = UUID.randomUUID(), body = "Other", updatedAt = Instant.ofEpochSecond(20))
        val now = Instant.ofEpochSecond(100)

        val result = DraftPolicy.save(drafts = listOf(other, edited), editingDraftID = edited.id, body = "Updated", maxDrafts = 5, now = now)

        assertEquals(2, result.drafts.size)
        assertEquals(edited.id, result.editingDraftID)
        assertEquals(edited.id, result.changedDraft?.id)
        assertEquals("Updated", result.changedDraft?.body)
        assertEquals(now, result.changedDraft?.updatedAt)
        assertEquals(false, result.limitReached)
    }

    @Test
    fun savePersistsImageData() {
        val imageData = byteArrayOf(1, 2, 3)

        val result = DraftPolicy.save(
            drafts = emptyList(),
            editingDraftID = null,
            body = "",
            maxDrafts = 5,
            imageData = imageData,
            imageFilename = "image.jpg",
            imageMimeType = "image/jpeg",
        )

        assertTrue(result.drafts.first().imageData.contentEquals(imageData))
        assertEquals("image.jpg", result.drafts.first().imageFilename)
        assertEquals("image/jpeg", result.drafts.first().imageMimeType)
        assertTrue(result.changedDraft?.imageData.contentEquals(imageData) == true)
        assertEquals(false, result.limitReached)
    }

    @Test
    fun saveWithUnchangedBodyReportsNoChange() {
        val edited = Draft(id = UUID.randomUUID(), body = "Same", imageData = byteArrayOf(1), updatedAt = Instant.ofEpochSecond(10))

        val result = DraftPolicy.save(
            drafts = listOf(edited),
            editingDraftID = edited.id,
            body = "Same",
            maxDrafts = 5,
            imageData = byteArrayOf(1),
            now = Instant.ofEpochSecond(100),
        )

        assertEquals(listOf(edited), result.drafts)
        assertNull(result.changedDraft)
        assertEquals(false, result.limitReached)
    }

    @Test
    fun saveWithUnknownEditingIDCreatesNewDraft() {
        val result = DraftPolicy.save(drafts = emptyList(), editingDraftID = UUID.randomUUID(), body = "Fresh", maxDrafts = 5, now = Instant.ofEpochSecond(100))

        assertEquals(1, result.drafts.size)
        assertTrue(result.changedDraft != null)
        assertEquals(result.drafts.first().id, result.editingDraftID)
        assertEquals(false, result.limitReached)
    }

    @Test
    fun saveBlocksNewDraftAtLimit() {
        val drafts = (0 until 5).map { Draft(body = "Draft $it") }

        val result = DraftPolicy.save(drafts = drafts, editingDraftID = null, body = "Overflow", maxDrafts = 5, now = Instant.ofEpochSecond(100))

        assertEquals(5, result.drafts.size)
        assertNull(result.changedDraft)
        assertEquals(true, result.limitReached)
    }

    @Test
    fun deleteRemovesOnlyMatchingDraft() {
        val target = Draft(id = UUID.randomUUID(), body = "Target")
        val other = Draft(id = UUID.randomUUID(), body = "Other")

        val remaining = DraftPolicy.delete(listOf(target, other), id = target.id)

        assertEquals(listOf(other), remaining)
    }

    @Test
    fun trimmedBodyRemovesWhitespace() {
        assertEquals("hello", DraftPolicy.trimmedBody("  hello \n"))
    }
}
