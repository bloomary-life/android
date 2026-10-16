package life.bloomary.app.features.entitlement.api

import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import life.bloomary.app.features.linkedinpost.api.LinkedInPostCredential
import life.bloomary.app.shared.network.AppJson
import life.bloomary.app.shared.network.Iso8601InstantSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class EntitlementService(
    private val apiBaseURL: String,
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun entitlement(credential: LinkedInPostCredential): Entitlement {
        val url = apiBaseURL.toHttpUrl().newBuilder()
            .addPathSegments("api/entitlement")
            .addQueryParameter("memberId", credential.memberID)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .get()
            .build()

        val result = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                HttpResult(code = response.code, body = response.body?.string().orEmpty())
            }
        }
        if (result.code !in 200..299) {
            throw EntitlementError("Entitlement load failed.")
        }

        return AppJson.decodeFromString(Entitlement.serializer(), result.body)
    }

    private class HttpResult(val code: Int, val body: String)

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

class EntitlementError(message: String) : Exception(message)

@Serializable
data class Entitlement(
    val isPro: Boolean,
    @Serializable(with = Iso8601InstantSerializer::class)
    val accessUntil: Instant? = null,
) {
    val isPaywalled: Boolean
        get() = accessUntil?.isAfter(Instant.now()) != true
}
