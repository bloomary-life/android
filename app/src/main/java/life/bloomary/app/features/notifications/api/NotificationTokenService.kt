package life.bloomary.app.features.notifications.api

import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import life.bloomary.app.features.linkedinpost.api.LinkedInPostCredential
import life.bloomary.app.shared.network.AppJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Mirrors ios/Features/Notifications/API/NotificationTokenService.swift.
 *
 * Registers the device's push token (APNs on iOS, FCM here) with the backend:
 * `PUT /api/notifications/token` with `{platform, token, timezone, enabled}`
 * and the LinkedIn access token as Bearer auth, per
 * wiki/daily-posting-workflow-and-notifications.md.
 */
sealed class NotificationTokenError(message: String) : Exception(message) {
    data object MissingAuth : NotificationTokenError("Connect LinkedIn first.")
    data object EmptyToken : NotificationTokenError("Notification token is empty.")
    class Server(message: String) : NotificationTokenError(message)
}

class NotificationTokenService(
    private val apiBaseURL: String,
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun register(token: String, credential: LinkedInPostCredential, enabled: Boolean = true) {
        val trimmedToken = token.trim()
        if (credential.accessToken.isEmpty() || credential.memberID.isEmpty()) {
            throw NotificationTokenError.MissingAuth
        }
        if (trimmedToken.isEmpty()) throw NotificationTokenError.EmptyToken

        val request = Request.Builder()
            .url(apiBaseURL.toHttpUrl().newBuilder().addPathSegments("api/notifications/token").build())
            .header("Authorization", "Bearer ${credential.accessToken}")
            .put(
                AppJson.encodeToString(
                    NotificationTokenRequest.serializer(),
                    NotificationTokenRequest(
                        platform = PLATFORM,
                        token = trimmedToken,
                        timezone = TimeZone.getDefault().id,
                        enabled = enabled,
                    ),
                ).toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()

        val result = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                HttpResult(code = response.code, body = response.body?.string().orEmpty())
            }
        }
        if (result.code !in 200..299) {
            val apiError = runCatching {
                AppJson.decodeFromString(NotificationTokenAPIErrorResponse.serializer(), result.body)
            }.getOrNull()
            if (apiError != null) {
                throw NotificationTokenError.Server(apiError.errorDescription ?: apiError.error)
            }
            throw NotificationTokenError.Server("Notification registration failed.")
        }
    }

    private class HttpResult(val code: Int, val body: String)

    companion object {
        /** `platform: "android"` per the wiki contract (iOS sends "ios"). */
        private const val PLATFORM = "android"

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
private data class NotificationTokenRequest(
    val platform: String,
    val token: String,
    val timezone: String,
    val enabled: Boolean,
)

@Serializable
private data class NotificationTokenAPIErrorResponse(
    val error: String,
    val errorDescription: String? = null,
)
