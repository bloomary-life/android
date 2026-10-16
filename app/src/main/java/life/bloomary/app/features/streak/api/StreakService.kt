package life.bloomary.app.features.streak.api

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import life.bloomary.app.features.linkedinpost.api.LinkedInPostCredential
import life.bloomary.app.features.streak.model.Streak
import life.bloomary.app.shared.network.AppJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Mirrors ios/Features/Streak/API/StreakService.swift.
 */
sealed class StreakError(message: String) : Exception(message) {
    class Server(message: String) : StreakError(message)
}

/** Cache payload stored at UserDefaults key `bloomary_streak_{memberID}` on iOS. */
@Serializable
data class CachedStreak(
    val day: String,
    val streak: Streak,
)

/**
 * UserDefaults-backed streak cache on iOS; DataStore here. Blocking API shape
 * matches the foundation's DraftRepository idiom (runBlocking bridge).
 */
interface StreakCacheStore {
    fun load(memberID: String): CachedStreak?
    fun save(entry: CachedStreak, memberID: String)
    fun clear(memberID: String)
}

private val Context.streakDataStore by preferencesDataStore(name = "bloomary_streak_store")

class DataStoreStreakCacheStore(
    context: Context,
) : StreakCacheStore {
    private val dataStore = context.applicationContext.streakDataStore

    override fun load(memberID: String): CachedStreak? {
        val raw = runBlocking { dataStore.data.first()[cacheKey(memberID)] } ?: return null
        return runCatching { AppJson.decodeFromString(CachedStreak.serializer(), raw) }.getOrNull()
    }

    override fun save(entry: CachedStreak, memberID: String) {
        val encoded = AppJson.encodeToString(CachedStreak.serializer(), entry)
        runBlocking {
            dataStore.edit { prefs -> prefs[cacheKey(memberID)] = encoded }
        }
    }

    override fun clear(memberID: String) {
        runBlocking {
            dataStore.edit { prefs -> prefs.remove(cacheKey(memberID)) }
        }
    }

    private fun cacheKey(memberID: String) = stringPreferencesKey("bloomary_streak_$memberID")
}

class StreakService(
    private val apiBaseURL: String,
    private val cacheStore: StreakCacheStore,
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun streak(credential: LinkedInPostCredential, forceRefresh: Boolean = false): Streak {
        // Streak data changes once per day at most; serve the same-day cache
        // without a network call, and fall back to it when the fetch fails.
        val memberID = credential.memberID
        val today = dayString()
        if (!forceRefresh) {
            val cached = cacheStore.load(memberID)
            if (cached != null && cached.day == today) {
                return cached.streak
            }
        }

        val request = Request.Builder()
            .url(apiBaseURL.toHttpUrl().newBuilder().addPathSegments("api/streak").build())
            .header("Authorization", "Bearer ${credential.accessToken}")
            .post(
                AppJson.encodeToString(StreakRequest.serializer(), StreakRequest(memberID = memberID))
                    .toByteArray(Charsets.UTF_8)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        try {
            val result = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    HttpResult(code = response.code, body = response.body?.string().orEmpty())
                }
            }
            if (result.code !in 200..299) {
                throw StreakError.Server("Streak load failed.")
            }
            val streak = AppJson.decodeFromString(Streak.serializer(), result.body)
            cacheStore.save(CachedStreak(day = dayString(), streak = streak), memberID)
            return streak
        } catch (e: Exception) {
            cacheStore.load(memberID)?.let { return it.streak }
            throw e
        }
    }

    fun clearCache(memberID: String) {
        cacheStore.clear(memberID)
    }

    private class HttpResult(val code: Int, val body: String)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // UTC "yyyy-MM-dd", gregorian — mirrors DateFormatter in StreakService.swift.
        private val DAY_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        private fun dayString(): String = DAY_FORMATTER.format(Instant.now())

        // URLSession.shared default timeoutIntervalForRequest is 60 s.
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
private data class StreakRequest(
    @SerialName("memberId") val memberID: String,
)
