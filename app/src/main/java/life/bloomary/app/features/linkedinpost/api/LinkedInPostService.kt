package life.bloomary.app.features.linkedinpost.api

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
    data object AuthorizationExpired : LinkedInPostError("LinkedIn authorization expired. Connect LinkedIn again.")
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
        draftID: String? = null,
    ): PublishResult {
        val body = text.trim()
        if (body.isEmpty() && attachment == null) throw LinkedInPostError.EmptyPost
        if (credential == null || credential.accessToken.isEmpty() || credential.memberID.isEmpty()) {
            throw LinkedInPostError.MissingAuth
        }

        val boundary = "Boundary-${UUID.randomUUID()}"
        val builder = MultipartFormDataBuilder(boundary)
        val fields = mutableMapOf(
            "memberId" to credential.memberID,
            "commentary" to body,
            "timezone" to TimeZone.getDefault().id,
        )
        // The backend cancels this draft's schedule once the post is live (IOS-002 / AND-002).
        if (draftID != null) fields["draftId"] = draftID
        val bodyBytes = builder.body(
            fields = fields,
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
            .header("Authorization", "Bearer ${credential.accessToken}")
            .post(bodyBytes.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
            .build()

        val result = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                HttpResult(code = response.code, body = response.body?.string().orEmpty())
            }
        }

        if (result.code == 401) throw LinkedInPostError.AuthorizationExpired
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
        // null postId rather than an error.
        val success = runCatching {
            AppJson.decodeFromString(PostSuccessResponse.serializer(), result.body)
        }.getOrNull()
        return PublishResult(postID = success?.postId)
    }

    suspend fun schedule(
        draftID: String,
        at: Instant,
        reviewConfirmed: Boolean = false,
        credential: LinkedInPostCredential?,
    ): ScheduledPostResult {
        val valid = requireCredential(credential)
        val payload = AppJson.encodeToString(
            ScheduleRequest.serializer(),
            // Fractional seconds, like the iOS ISO8601DateFormatter with .withFractionalSeconds.
            ScheduleRequest(reviewConfirmed, draftID, DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.MILLIS))),
        )
        val request = authorized("api/linkedin/schedule", valid)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return AppJson.decodeFromString(ScheduledPostResult.serializer(), execute(request))
    }

    /** `DELETE /api/linkedin/schedule` for one draft (IOS-003 / AND-003). */
    suspend fun unschedule(draftID: String, credential: LinkedInPostCredential?) {
        val valid = requireCredential(credential)
        execute(authorized("api/linkedin/schedule", valid, "draftId" to draftID).delete().build())
    }

    suspend fun scheduleStatus(id: String, credential: LinkedInPostCredential?): ScheduledPostResult {
        val valid = requireCredential(credential)
        val body = execute(authorized("api/linkedin/schedule/status", valid, "scheduleId" to id).get().build())
        return AppJson.decodeFromString(ScheduledPostResult.serializer(), body)
    }

    suspend fun scheduledPosts(credential: LinkedInPostCredential?): List<ScheduledPostResult> {
        val valid = requireCredential(credential)
        val body = execute(authorized("api/linkedin/schedules", valid).get().build())
        return AppJson.decodeFromString(ScheduledPostsResponse.serializer(), body).schedules
    }

    private fun requireCredential(credential: LinkedInPostCredential?): LinkedInPostCredential {
        if (credential == null || credential.accessToken.isEmpty() || credential.memberID.isEmpty()) {
            throw LinkedInPostError.MissingAuth
        }
        return credential
    }

    private fun authorized(path: String, credential: LinkedInPostCredential, vararg query: Pair<String, String>): Request.Builder {
        val url = apiBaseURL.toHttpUrl().newBuilder().addPathSegments(path).addQueryParameter("memberId", credential.memberID)
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return Request.Builder().url(url.build()).header("Authorization", "Bearer ${credential.accessToken}")
    }

    /** Returns the body of a 2xx response; maps 401 and API errors like the iOS `validate`. */
    private suspend fun execute(request: Request): String {
        val result = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                HttpResult(code = response.code, body = response.body?.string().orEmpty())
            }
        }
        if (result.code in 200..299) return result.body
        if (result.code == 401) throw LinkedInPostError.AuthorizationExpired
        val apiError = runCatching { AppJson.decodeFromString(PostAPIErrorResponse.serializer(), result.body) }.getOrNull()
        throw LinkedInPostError.Server(apiError?.let { it.errorDescription ?: it.error } ?: "LinkedIn request failed.")
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
)

@Serializable
data class ScheduledPostResult(
    val scheduleId: String,
    val draftId: String? = null,
    val status: String,
    val scheduledAt: String,
    val message: String? = null,
)

@Serializable
private data class ScheduledPostsResponse(val schedules: List<ScheduledPostResult>)

@Serializable
private data class ScheduleRequest(
    val reviewConfirmed: Boolean,
    val draftId: String,
    val scheduledAt: String,
)

@Serializable
private data class PostSuccessResponse(
    val status: String? = null,
    val postId: String? = null,
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
