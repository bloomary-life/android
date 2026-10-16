package life.bloomary.app.features.linkedinauth.api

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import life.bloomary.app.features.account.viewmodel.AccountAuthServicing
import life.bloomary.app.features.linkedinauth.model.LinkedInToken
import life.bloomary.app.features.linkedinauth.storage.LinkedInTokenStore
import life.bloomary.app.shared.network.AppJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Mirrors ios/Features/LinkedInAuth/API/LinkedInAuthService.swift.
 */
sealed class LinkedInAuthError(message: String) : Exception(message) {
    data object InvalidStartURL : LinkedInAuthError("LinkedIn authorization URL is invalid.")
    data object MissingWindow : LinkedInAuthError("Cannot open LinkedIn authorization.")
    data object Cancelled : LinkedInAuthError("LinkedIn authorization was cancelled.")
    data object TimedOut : LinkedInAuthError("LinkedIn authorization timed out.")
    class Server(message: String) : LinkedInAuthError(message)
}

/**
 * UserDefaults keys used by the iOS service ("linkedin.pendingState",
 * "linkedin.requiresFreshSignIn"), backed by DataStore. The blocking API shape
 * matches the foundation's DraftRepository idiom (runBlocking bridge).
 */
interface LinkedInAuthFlagStore {
    var pendingState: String?
    var requiresFreshSignIn: Boolean
}

private val Context.linkedInAuthFlagDataStore by preferencesDataStore(name = "bloomary_linkedin_auth_store")

class DataStoreLinkedInAuthFlagStore(
    context: Context,
) : LinkedInAuthFlagStore {
    private val dataStore = context.applicationContext.linkedInAuthFlagDataStore

    override var pendingState: String?
        get() = runBlocking { dataStore.data.first()[PENDING_STATE] }
        set(value) = runBlocking {
            dataStore.edit { prefs ->
                if (value == null) prefs.remove(PENDING_STATE) else prefs[PENDING_STATE] = value
            }
        }

    override var requiresFreshSignIn: Boolean
        get() = runBlocking { dataStore.data.first()[FRESH_SIGN_IN] ?: false }
        set(value) = runBlocking {
            dataStore.edit { prefs -> prefs[FRESH_SIGN_IN] = value }
        }

    companion object {
        private val PENDING_STATE = stringPreferencesKey("linkedin.pendingState")
        private val FRESH_SIGN_IN = booleanPreferencesKey("linkedin.requiresFreshSignIn")
    }
}

/**
 * CLI-style LinkedIn OAuth against the Bloomary backend.
 *
 * Android UI/lifecycle seams the iOS service reaches directly are constructor
 * lambdas; step 6 wires them:
 * - [openAuthorization]: open the auth URL in the EXTERNAL browser
 *   (parity with `UIApplication.shared.open`, not a Custom Tab), return whether
 *   the browser opened. Maps to `LinkedInAuthError.missingWindow` on false.
 * - [waitForForeground]: mirror of `waitForForeground()` — sleep 500 ms, return
 *   immediately if the app is already foreground, otherwise suspend until the
 *   next foreground (ON_STARTED) event. Polling starts only after this returns.
 */
class LinkedInAuthService(
    private val apiBaseURL: String,
    @Suppress("unused") callbackURLScheme: String,
    private val tokenStore: LinkedInTokenStore,
    private val flags: LinkedInAuthFlagStore,
    private val client: OkHttpClient = defaultClient(),
    private val openAuthorization: suspend (authURL: String) -> Boolean,
    private val waitForForeground: suspend () -> Unit,
) : AccountAuthServicing {
    override suspend fun signIn(): LinkedInToken {
        // iOS: `try? await completePendingSignIn()` — recovery errors are
        // swallowed and the flow restarts fresh.
        runCatching { completePendingSignIn() }.getOrNull()?.let { return it }
        val start = startFlow()
        flags.pendingState = start.state
        if (!openAuthorization(start.authURL)) throw LinkedInAuthError.MissingWindow
        waitForForeground()
        return completePendingSignIn() ?: throw LinkedInAuthError.TimedOut
    }

    override suspend fun disconnect(memberID: String, accessToken: String) {
        val request = Request.Builder()
            .url(apiBaseURL.toHttpUrl().newBuilder().addPathSegments("api/linkedin/disconnect").build())
            .header("Authorization", "Bearer $accessToken")
            .post(jsonBody(DisconnectRequest(linkedInID = memberID)))
            .build()
        val result = execute(request)
        validate(result)
    }

    override suspend fun deleteAccount(memberID: String, accessToken: String) {
        val request = Request.Builder()
            .url(apiBaseURL.toHttpUrl().newBuilder().addPathSegments("api/account/delete").build())
            .header("Authorization", "Bearer $accessToken")
            .post(jsonBody(DisconnectRequest(linkedInID = memberID)))
            .build()
        val result = execute(request)
        validate(result)
    }

    override fun requireFreshSignInOnce() {
        flags.requiresFreshSignIn = true
    }

    private suspend fun startFlow(): StartResponse {
        val urlBuilder = apiBaseURL.toHttpUrl().newBuilder()
            .addPathSegments("api/linkedin/cli/start")
        if (flags.requiresFreshSignIn) {
            urlBuilder.addQueryParameter("fresh", "1")
        }
        val request = Request.Builder().url(urlBuilder.build()).get().build()
        val result = execute(request)
        validate(result)
        return AppJson.decodeFromString(StartResponse.serializer(), result.body)
    }

    private suspend fun completePendingSignIn(): LinkedInToken? {
        val state = flags.pendingState ?: return null
        try {
            val token = pollResult(state)
            flags.pendingState = null
            flags.requiresFreshSignIn = false
            tokenStore.save(token)
            return token
        } catch (e: LinkedInAuthError.TimedOut) {
            throw e
        } catch (e: Exception) {
            flags.pendingState = null
            throw e
        }
    }

    private suspend fun pollResult(state: String): LinkedInToken {
        // Server long-polls 2 s per request (`wait=2`); no client-side sleep,
        // 45 tries ≈ 90 s before `timedOut`. The returned `pollUrl` is ignored:
        // the poll URL is always built from the configured apiBaseURL.
        repeat(45) {
            val url = apiBaseURL.toHttpUrl().newBuilder()
                .addPathSegments("api/linkedin/cli/result")
                .addQueryParameter("state", state)
                .addQueryParameter("wait", "2")
                .build()
            val result = execute(Request.Builder().url(url).get().build())
            validate(result)

            val envelope = AppJson.decodeFromString(ResultEnvelope.serializer(), result.body)
            when (envelope.status) {
                "done" -> return envelope.token
                    ?: throw LinkedInAuthError.Server("LinkedIn token is missing.")
                "pending" -> Unit
                "error" -> throw LinkedInAuthError.Server(
                    envelope.errorDescription ?: envelope.error ?: "LinkedIn authorization failed."
                )
                else -> throw LinkedInAuthError.Server("Unexpected LinkedIn authorization status.")
            }
        }
        throw LinkedInAuthError.TimedOut
    }

    private fun validate(result: HttpResult) {
        if (result.code !in 200..299) {
            val apiError = runCatching {
                AppJson.decodeFromString(APIErrorResponse.serializer(), result.body)
            }.getOrNull()
            if (apiError != null) {
                throw LinkedInAuthError.Server(apiError.errorDescription ?: apiError.error)
            }
            throw LinkedInAuthError.Server("Request failed with status ${result.code}.")
        }
    }

    private fun jsonBody(payload: DisconnectRequest) =
        AppJson.encodeToString(DisconnectRequest.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
            .toRequestBody(JSON_MEDIA_TYPE)

    private suspend fun execute(request: Request): HttpResult = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            HttpResult(code = response.code, body = response.body?.string().orEmpty())
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
data class StartResponse(
    @SerialName("authUrl") val authURL: String,
    val state: String,
    @SerialName("redirectUri") val redirectURI: String,
    @SerialName("pollUrl") val pollURL: String,
)

@Serializable
data class ResultEnvelope(
    val status: String,
    val token: LinkedInToken? = null,
    val error: String? = null,
    val errorDescription: String? = null,
    val httpStatus: Int? = null,
    val upstreamBody: String? = null,
)

@Serializable
data class APIErrorResponse(
    val error: String,
    val errorDescription: String? = null,
)

@Serializable
private data class DisconnectRequest(
    @SerialName("linkedInId") val linkedInID: String,
)
