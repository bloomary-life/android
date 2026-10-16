package life.bloomary.app.shared.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Mirrors ios/Shared/Network/Coding.swift: ISO-8601 dates WITHOUT fractional
 * seconds in both directions, decoded strictly (fractional seconds rejected,
 * matching Foundation's `.iso8601` strategy).
 */
object Iso8601InstantSerializer : KSerializer<Instant> {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("life.bloomary.app.Iso8601Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        // Truncate to whole seconds — never emit fractional seconds.
        encoder.encodeString(formatter.format(value.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        try {
            return formatter.parse(raw, Instant::from)
        } catch (e: DateTimeParseException) {
            throw kotlinx.serialization.SerializationException("Invalid ISO-8601 date: $raw", e)
        }
    }
}

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("life.bloomary.app.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        // Foundation encodes UUID uppercased.
        encoder.encodeString(value.toString().uppercase())
    }

    override fun deserialize(decoder: Decoder): UUID =
        try {
            UUID.fromString(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw kotlinx.serialization.SerializationException("Invalid UUID", e)
        }
}

/**
 * Foundation encodes `Data` as a Base64 string in JSON; mirror that for
 * `ByteArray` so local snapshots stay compact and iOS-shaped.
 */
object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("life.bloomary.app.Base64Data", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(java.util.Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        try {
            java.util.Base64.getDecoder().decode(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw kotlinx.serialization.SerializationException("Invalid Base64 data", e)
        }
}

/** Shared JSON codec. Codable ignores unknown keys; mirror that. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
