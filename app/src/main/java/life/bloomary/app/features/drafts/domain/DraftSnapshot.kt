package life.bloomary.app.features.drafts.domain

import java.time.Instant
import kotlinx.serialization.Serializable
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.shared.network.Iso8601InstantSerializer

/**
 * Versioned local cache envelope for drafts. Persisted behind `DraftRepository`
 * so a later SQLite/Room swap requires no UI changes.
 * Mirrors ios/Features/Drafts/Domain/DraftSnapshot.swift.
 */
@Serializable
data class DraftSnapshot(
    val drafts: List<Draft> = emptyList(),
    val ownerID: String? = null,
    @Serializable(with = Iso8601InstantSerializer::class)
    val loadedAt: Instant = Instant.now(),
    @Serializable(with = Iso8601InstantSerializer::class)
    val lastSuccessfulSyncAt: Instant? = null,
    val schemaVersion: Int = currentSchemaVersion,
) {
    companion object {
        const val currentSchemaVersion = 1

        fun empty(ownerID: String?): DraftSnapshot = DraftSnapshot(drafts = emptyList(), ownerID = ownerID)
    }
}
