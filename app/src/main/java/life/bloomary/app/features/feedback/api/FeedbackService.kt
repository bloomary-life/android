package life.bloomary.app.features.feedback.api

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import life.bloomary.app.shared.network.AppJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Mirrors ios/Features/Feedback/API/FeedbackService.swift.
 */
sealed class FeedbackError(message: String) : Exception(message) {
    data object EmptyMessage : FeedbackError("Feedback message is empty.")
    class Server(message: String) : FeedbackError(message)
}

/**
 * `content` is the Base64 image payload; [id] mirrors the Swift `Identifiable`
 * conformance (excluded from the JSON payload there via CodingKeys).
 */
@Serializable
data class FeedbackImageAttachment(
    val filename: String,
    val content: String,
) {
    @Transient
    val id: UUID = UUID.randomUUID()
}

data class FeedbackSenderIdentity(
    val name: String,
    val linkedInID: String,
    val email: String?,
)

class FeedbackService(
    private val apiBaseURL: String,
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun send(
        title: String,
        message: String,
        identity: FeedbackSenderIdentity?,
        attachments: List<FeedbackImageAttachment>,
    ) {
        val body = message.trim()
        if (body.isEmpty()) throw FeedbackError.EmptyMessage

        val payload = FeedbackRequest(
            name = identity?.name ?: "",
            linkedInID = identity?.linkedInID ?: "",
            email = identity?.email ?: "",
            title = title.trim(),
            message = body,
            attachments = attachments,
        )
        val request = Request.Builder()
            .url(apiBaseURL.toHttpUrl().newBuilder().addPathSegments("api/feedback").build())
            .post(
                AppJson.encodeToString(FeedbackRequest.serializer(), payload)
                    .toByteArray(Charsets.UTF_8)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        val result = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                HttpResult(code = response.code, body = response.body?.string().orEmpty())
            }
        }

        if (result.code !in 200..299) {
            val apiError = runCatching {
                AppJson.decodeFromString(FeedbackAPIErrorResponse.serializer(), result.body)
            }.getOrNull()
            if (apiError != null) {
                throw FeedbackError.Server(apiError.errorDescription ?: apiError.error)
            }
            throw FeedbackError.Server("Feedback send failed.")
        }
    }

    private class HttpResult(val code: Int, val body: String)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // URLSession.shared default timeoutIntervalForRequest is 60 s.
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
private data class FeedbackRequest(
    val name: String,
    @SerialName("linkedInId") val linkedInID: String,
    val email: String,
    val title: String,
    val message: String,
    val attachments: List<FeedbackImageAttachment>,
)

@Serializable
private data class FeedbackAPIErrorResponse(
    val error: String,
    val errorDescription: String? = null,
)
