package life.bloomary.app.features.linkedinpost.api

import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import life.bloomary.app.features.streak.model.Streak
import life.bloomary.app.shared.network.AppJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Mirrors ios/Features/LinkedInPost/API/LinkedInPostService.swift.
 */
sealed class LinkedInPostError(message: String) : Exception(message) {
    data object EmptyPost : LinkedInPostError("Post is empty.")
    data object MissingAuth : LinkedInPostError("Connect LinkedIn first.")
    class Server(message: String) : LinkedInPostError(message)
}

class LinkedInPostService(
    private val apiBaseURL: String,
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun publish(
        text: String,
        credential: LinkedInPostCredential?,
        attachment: LinkedInPostAttachment? = null,
    ): PublishResult {
        val body = text.trim()
        if (body.isEmpty() && attachment == null) throw LinkedInPostError.EmptyPost
        if (credential == null || credential.accessToken.isEmpty() || credential.memberID.isEmpty()) {
            throw LinkedInPostError.MissingAuth
        }

        val boundary = "Boundary-${UUID.randomUUID()}"
        val builder = MultipartFormDataBuilder(boundary)
        val bodyBytes = builder.body(
            fields = mapOf(
                "accessToken" to credential.accessToken,
                "memberId" to credential.memberID,
                "commentary" to body,
                "timezone" to TimeZone.getDefault().id,
            ),
            file = attachment?.let {
                MultipartFormFile(
                    fieldName = "attachment",
                    filename = it.filename,
                    mimeType = it.mimeType,
                    data = it.data,
                )
            },
        )

        val request = Request.Builder()
            .url(apiBaseURL.toHttpUrl().newBuilder().addPathSegments("api/linkedin/post").build())
            .post(bodyBytes.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
            .build()

        val result = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                HttpResult(code = response.code, body = response.body?.string().orEmpty())
            }
        }

        if (result.code !in 200..299) {
            val apiError = runCatching {
                AppJson.decodeFromString(PostAPIErrorResponse.serializer(), result.body)
            }.getOrNull()
            if (apiError != null) {
                throw LinkedInPostError.Server(apiError.errorDescription ?: apiError.error)
            }
            throw LinkedInPostError.Server("LinkedIn post failed.")
        }

        // iOS decodes the success payload with `try?`: a malformed body yields
        // null postId/streak rather than an error.
        val success = runCatching {
            AppJson.decodeFromString(PostSuccessResponse.serializer(), result.body)
        }.getOrNull()
        return PublishResult(postID = success?.postId, streak = success?.streak)
    }

    private class HttpResult(val code: Int, val body: String)

    companion object {
        // URLSession.shared default timeoutIntervalForRequest is 60 s.
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

data class LinkedInPostAttachment(
    val data: ByteArray,
    val filename: String,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinkedInPostAttachment) return false
        return data.contentEquals(other.data) && filename == other.filename && mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

data class PublishResult(
    val postID: String?,
    val streak: Streak?,
)

@Serializable
private data class PostSuccessResponse(
    val status: String? = null,
    val postId: String? = null,
    val streak: Streak? = null,
)

data class LinkedInPostCredential(
    val accessToken: String,
    val memberID: String,
)

@Serializable
private data class PostAPIErrorResponse(
    val error: String,
    val errorDescription: String? = null,
)
