package life.bloomary.app.features.drafts.domain

import java.time.Instant
import java.util.UUID
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.shared.network.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec-level counterpart of ios/BloomaryTests/DraftSnapshotTests.swift. The
 * iOS tests go through UserDefaultsDraftRepository; the Android repository is
 * DataStore-backed and needs a Context, so these cover the snapshot envelope
 * and its JSON round-trip that the repository persists.
 */
class DraftSnapshotTests {

    @Test
    fun snapshotRoundTripsThroughCodec() {
        val draft = Draft(
            id = UUID.randomUUID(),
            body = "Hello LinkedIn",
            createdAt = Instant.ofEpochSecond(50),
            updatedAt = Instant.ofEpochSecond(60),
        )
        val syncedAt = Instant.ofEpochSecond(100)
        val snapshot = DraftSnapshot(drafts = listOf(draft), ownerID = "member-1", lastSuccessfulSyncAt = syncedAt)

        val loaded = AppJson.decodeFromString(
            DraftSnapshot.serializer(),
            AppJson.encodeToString(DraftSnapshot.serializer(), snapshot),
        )

        assertEquals(listOf(draft), loaded.drafts)
        assertEquals("member-1", loaded.ownerID)
        assertEquals(syncedAt, loaded.lastSuccessfulSyncAt)
        assertEquals(DraftSnapshot.currentSchemaVersion, loaded.schemaVersion)
    }

    @Test
    fun snapshotWithImageDataRoundTripsThroughCodec() {
        val draft = Draft(
            id = UUID.randomUUID(),
            body = "With image",
            imageData = byteArrayOf(1, 2, 3),
            imageFilename = "image.jpg",
            imageMimeType = "image/jpeg",
        )
        val snapshot = DraftSnapshot(drafts = listOf(draft), ownerID = "member-1")

        val loaded = AppJson.decodeFromString(
            DraftSnapshot.serializer(),
            AppJson.encodeToString(DraftSnapshot.serializer(), snapshot),
        )

        assertEquals(1, loaded.drafts.size)
        assertTrue(loaded.drafts.first().imageData.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("image.jpg", loaded.drafts.first().imageFilename)
    }

    @Test
    fun emptySnapshotCarriesOwnerID() {
        val empty = DraftSnapshot.empty(ownerID = "member-1")

        assertTrue(empty.drafts.isEmpty())
        assertEquals("member-1", empty.ownerID)
        assertNull(empty.lastSuccessfulSyncAt)
        assertEquals(DraftSnapshot.currentSchemaVersion, empty.schemaVersion)
    }
}
