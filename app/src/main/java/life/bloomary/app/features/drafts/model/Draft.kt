package life.bloomary.app.features.drafts.model

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import life.bloomary.app.shared.network.Base64ByteArraySerializer
import life.bloomary.app.shared.network.Iso8601InstantSerializer
import life.bloomary.app.shared.network.UUIDSerializer

/**
 * Mirrors ios/Features/Drafts/Model/Draft.swift.
 */
@Serializable
data class Draft(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID = UUID.randomUUID(),
    /** Server spelling of the ID when it differs from `id` (case); null for local drafts. */
    val wireID: String? = null,
    val body: String,
    @Serializable(with = Base64ByteArraySerializer::class)
    val imageData: ByteArray? = null,
    val imageFilename: String? = null,
    val imageMimeType: String? = null,
    val remoteImage: DraftSyncImage? = null,
    val status: String? = "draft",
    @Serializable(with = Iso8601InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = Iso8601InstantSerializer::class)
    val updatedAt: Instant = Instant.now(),
) {
    /** ID sent to the API: the server's spelling, else the lowercased UUID. */
    val serverID: String
        get() = wireID ?: id.toString().lowercase()

    /**
     * First line of the body, trimmed and truncated to 72 characters.
     * Falls back to "Untitled draft" when there is no non-empty line.
     * (Swift's `split(separator:)` omits empty subsequences by default.)
     */
    val title: String
        get() = body
            .split('\n')
            .firstOrNull { it.isNotEmpty() }
            ?.trim()
            ?.take(72)
            ?: "Untitled draft"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Draft) return false
        return id == other.id &&
            wireID == other.wireID &&
            body == other.body &&
            imageData.contentEqualsNullable(other.imageData) &&
            imageFilename == other.imageFilename &&
            imageMimeType == other.imageMimeType &&
            remoteImage == other.remoteImage &&
            status == other.status &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (imageFilename?.hashCode() ?: 0)
        result = 31 * result + (imageMimeType?.hashCode() ?: 0)
        result = 31 * result + (remoteImage?.hashCode() ?: 0)
        result = 31 * result + (status?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
        this === other -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }
}

/** Server-side image metadata for a synced draft. Mirrors `DraftSyncImage` in SyncedDraftsService.swift. */
@Serializable
data class DraftSyncImage(
    val imageId: String,
    val downloadUrl: String? = null,
    val mimeType: String,
    val sizeBytes: Long,
    val checksumSHA256: String,
)
