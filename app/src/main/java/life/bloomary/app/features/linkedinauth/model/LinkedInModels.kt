package life.bloomary.app.features.linkedinauth.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import life.bloomary.app.shared.network.Iso8601InstantSerializer

/**
 * Mirrors ios/Features/LinkedInAuth/Model/LinkedInModels.swift.
 * JSON keys stay camelCase per docs/api-ios-contract-verification-report.md.
 */
@Serializable
data class LinkedInProfile(
    @SerialName("memberId") val memberID: String,
    val name: String,
    @SerialName("pictureUrl") val pictureURL: String? = null,
    val email: String? = null,
    val headline: String? = null,
    val localizedHeadline: String? = null,
) {
    val displayHeadline: String?
        get() {
            val localized = localizedHeadline?.trim().orEmpty()
            if (localized.isNotEmpty()) return localized
            val fallback = headline?.trim().orEmpty()
            return fallback.ifEmpty { null }
        }
}

@Serializable
data class LinkedInToken(
    val accessToken: String,
    val tokenType: String? = null,
    val scope: String? = null,
    val expiresIn: Int? = null,
    val profile: LinkedInProfile,
    @Serializable(with = Iso8601InstantSerializer::class)
    val connectedAt: Instant,
)
