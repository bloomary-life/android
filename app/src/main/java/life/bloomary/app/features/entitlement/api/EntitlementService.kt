package life.bloomary.app.features.entitlement.api

import java.time.Duration
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

/**
 * `GET /api/entitlement` with a short in-memory cache.
 * Mirrors ios/Features/Entitlement/API/EntitlementService.swift (the iOS cache
 * also persists across launches; here a cold start refetches).
 */
class EntitlementService(
    private val apiBaseURL: String,
    private val client: OkHttpClient = defaultClient(),
    private val cacheTTL: Duration = Duration.ofMinutes(5),
) {
    private val cache = mutableMapOf<String, Pair<Instant, Entitlement>>()

    @Synchronized
    private fun cached(memberID: String): Entitlement? {
        val (fetchedAt, entitlement) = cache[memberID] ?: return null
        if (fetchedAt.plus(cacheTTL) <= Instant.now()) return null
        // An access window that has since ended must be refetched, not served as active.
        if (entitlement.accessTier != EntitlementTier.lapsed && entitlement.accessUntil?.isBefore(Instant.now()) == true) return null
        return entitlement
    }

    @Synchronized
    private fun store(memberID: String, entitlement: Entitlement?) {
        if (entitlement == null) cache.remove(memberID) else cache[memberID] = Instant.now() to entitlement
    }

    suspend fun entitlement(credential: LinkedInPostCredential, forceRefresh: Boolean = false): Entitlement {
        if (credential.accessToken.isEmpty() || credential.memberID.isEmpty()) throw EntitlementError("LinkedIn connection is required.")
        if (!forceRefresh) cached(credential.memberID)?.let { return it }

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
            store(credential.memberID, null)
            val apiError = runCatching { AppJson.decodeFromString(EntitlementAPIError.serializer(), result.body) }.getOrNull()
            throw EntitlementError(apiError?.let { it.errorDescription ?: it.error } ?: "Entitlement load failed.")
        }

        val entitlement = AppJson.decodeFromString(Entitlement.serializer(), result.body)
        store(credential.memberID, entitlement)
        return entitlement
    }

    fun clearCache(memberID: String) = store(memberID, null)

    private class HttpResult(val code: Int, val body: String)

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

class EntitlementError(message: String) : Exception(message)

/** Server-computed access state (`docs/product/tiers.md`). Render it, never derive it. */
@Suppress("EnumEntryName")
enum class EntitlementTier { trial, pro, lapsed }

@Serializable
data class Entitlement(
    val isPro: Boolean,
    val draftLimit: Int = 0,
    val postCount: Int = 0,
    @Serializable(with = Iso8601InstantSerializer::class)
    val accessUntil: Instant? = null,
    val subscriptionSource: String? = null,
    val maxDraftBodyRunes: Int? = null,
    val maxImageBytes: Int? = null,
    /** Raw server value; kept as a string so an unknown future tier does not fail decoding. */
    val tier: String? = null,
) {
    /** Falls back to `isPro` only for responses from servers that don't send `tier` yet. */
    val accessTier: EntitlementTier
        get() = EntitlementTier.entries.firstOrNull { it.name == tier } ?: if (isPro) EntitlementTier.pro else EntitlementTier.lapsed
}

@Serializable
private data class EntitlementAPIError(val error: String, val errorDescription: String? = null)
