package life.bloomary.app.features.drafts.api

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the zip of every server-side draft. Export is open in every access
 * state, lapsed included (`docs/product/tiers.md` "Export").
 * Mirrors ios/Features/Drafts/API/DraftExportService.swift; the caller writes
 * the bytes to a file the member picks.
 */
class DraftExportService(
    private val apiBaseURL: String,
    private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
) {
    suspend fun export(memberID: String, accessToken: String): ByteArray {
        val url = apiBaseURL.toHttpUrl().newBuilder()
            .addPathSegments("api/drafts/export")
            .addQueryParameter("memberId", memberID)
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SyncedDraftsError.Server("Draft export failed.")
                response.body?.bytes() ?: ByteArray(0)
            }
        }
    }
}
