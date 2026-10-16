package life.bloomary.app.features.drafts.domain

import java.time.Instant
import java.util.UUID
import life.bloomary.app.features.drafts.model.Draft

/**
 * Pure draft list policies: save (create/update), pruning to the max draft
 * count, and deletion. No Android, Compose, network, or persistence imports —
 * unit-testable in isolation. Mirrors ios/Features/Drafts/Domain/DraftPolicy.swift.
 */
object DraftPolicy {
    data class SaveResult(
        val drafts: List<Draft>,
        val editingDraftID: UUID?,
        /** Non-null when a draft was created or its content actually changed. */
        val changedDraft: Draft?,
        val limitReached: Boolean,
    )

    fun trimmedBody(text: String): String = text.trim()

    /**
     * Applies an editor save: updates the edited draft in place when content
     * changed, or creates a new draft at the top of the list. Creating a new
     * draft is blocked when at the limit.
     */
    fun save(
        drafts: List<Draft>,
        editingDraftID: UUID?,
        body: String,
        maxDrafts: Int,
        imageData: ByteArray? = null,
        imageFilename: String? = null,
        imageMimeType: String? = null,
        now: Instant = Instant.now(),
    ): SaveResult {
        if (editingDraftID != null) {
            val index = drafts.indexOfFirst { it.id == editingDraftID }
            if (index >= 0) {
                val mutable = drafts.toMutableList()
                val existing = mutable[index]
                val unchanged = existing.body == body &&
                    imageData.contentEqualsNullable(existing.imageData) &&
                    existing.imageFilename == imageFilename &&
                    existing.imageMimeType == imageMimeType
                if (unchanged) {
                    return SaveResult(drafts = mutable, editingDraftID = editingDraftID, changedDraft = null, limitReached = false)
                }
                val updated = existing.copy(
                    body = body,
                    imageData = imageData,
                    imageFilename = imageFilename,
                    imageMimeType = imageMimeType,
                    updatedAt = now,
                )
                mutable[index] = updated
                return SaveResult(drafts = mutable, editingDraftID = editingDraftID, changedDraft = updated, limitReached = false)
            }
        }
        if (drafts.size >= maxDrafts) {
            return SaveResult(drafts = drafts, editingDraftID = editingDraftID, changedDraft = null, limitReached = true)
        }
        val draft = Draft(
            body = body,
            imageData = imageData,
            imageFilename = imageFilename,
            imageMimeType = imageMimeType,
            createdAt = now,
            updatedAt = now,
        )
        return SaveResult(drafts = listOf(draft) + drafts, editingDraftID = draft.id, changedDraft = draft, limitReached = false)
    }

    fun delete(drafts: List<Draft>, id: UUID): List<Draft> =
        drafts.filter { it.id != id }

    /**
     * Merges two draft lists by ID, keeping the newest `updatedAt` per draft
     * and preserving local image data when the newer entry has none. Used to
     * migrate signed-out drafts into the signed-in account on first login.
     */
    fun merge(local: List<Draft>, remote: List<Draft>): List<Draft> {
        val byID = local.associateBy { it.id }.toMutableMap()
        for (draft in remote) {
            val existing = byID[draft.id]
            if (existing != null && existing.updatedAt >= draft.updatedAt) {
                continue
            }
            var merged = draft
            if (existing != null && merged.imageData == null) {
                merged = merged.copy(
                    imageData = existing.imageData,
                    imageFilename = existing.imageFilename,
                    imageMimeType = existing.imageMimeType,
                )
            }
            byID[draft.id] = merged
        }
        // Soft enforcement: keep all drafts regardless of limit.
        // Limit is enforced on new draft creation, not on sync/merge.
        return byID.values
            .sortedByDescending { it.updatedAt }
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
        this === other -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }
}
