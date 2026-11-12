package life.bloomary.app.features.drafts.api

import android.util.Log
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.features.drafts.model.DraftSyncImage
import life.bloomary.app.shared.network.AppJson
import life.bloomary.app.shared.network.Iso8601InstantSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Mirrors the `DraftSyncing` protocol in ios/Features/Drafts/API/SyncedDraftsService.swift. */
interface DraftSyncing {
    suspend fun listDrafts(memberID: String, accessToken: String): List<Draft>
    suspend fun upsertDraft(draft: Draft, memberID: String, accessToken: String): Draft
    suspend fun deleteDraft(serverID: String, memberID: String, accessToken: String)
}

sealed class SyncedDraftsError(message: String) : Exception(message) {
    /** 400/413/422: retrying the same payload cannot succeed, so the mutation is parked. */
    class Validation(message: String) : SyncedDraftsError(message)
    class Server(message: String) : SyncedDraftsError(message)
    data object AuthorizationExpired : SyncedDraftsError("LinkedIn authorization expired. Reconnect to resume syncing.")
}

/**
 * `POST /api/drafts/sync` client with presigned image upload/download.
 * Port of ios/Features/Drafts/API/SyncedDraftsService.swift.
 */
class SyncedDraftsService(
    private val apiBaseURL: String,
    private val client: OkHttpClient = defaultClient(),
) : DraftSyncing {
    // imageId:checksum → bytes; avoids re-downloading unchanged attachments on every refresh.
    private val imageCache = ConcurrentHashMap<String, ByteArray>()

    override suspend fun listDrafts(memberID: String, accessToken: String): List<Draft> =
        sync(memberID = memberID, accessToken = accessToken).drafts.mapNotNull { draft(it) }

    override suspend fun upsertDraft(draft: Draft, memberID: String, accessToken: String): Draft {
        val upsert = draftSyncUpsert(draft, memberID, accessToken)
        val response = sync(upserts = listOf(upsert), memberID = memberID, accessToken = accessToken)
        val synced = response.drafts.firstOrNull { it.draftId.equals(draft.serverID, ignoreCase = true) } ?: return draft
        return draft(synced) ?: draft
    }

    override suspend fun deleteDraft(serverID: String, memberID: String, accessToken: String) {
        sync(deletes = listOf(serverID), memberID = memberID, accessToken = accessToken)
    }

    private suspend fun sync(
        upserts: List<DraftSyncUpsert> = emptyList(),
        deletes: List<String> = emptyList(),
        memberID: String,
        accessToken: String,
    ): DraftsResponse {
        val body = AppJson.encodeToString(DraftSyncRequest.serializer(), DraftSyncRequest(upserts, deletes))
        val request = authorizedRequest("api/drafts/sync", memberID, accessToken)
            .post(body.toRequestBody(JSON))
            .build()
        val result = execute(request)
        validate(result, fallback = "Draft sync failed.", authAware = true)
        return AppJson.decodeFromString(DraftsResponse.serializer(), result.body.decodeToString())
    }

    private suspend fun draftSyncUpsert(draft: Draft, memberID: String, accessToken: String): DraftSyncUpsert {
        val imageData = draft.imageData
            ?: return DraftSyncUpsert(draft.serverID, draft.body, draft.updatedAt, removeImage = draft.remoteImage == null)
        val mimeType = draft.imageMimeType ?: "image/jpeg"
        val checksum = sha256Hex(imageData)
        if (draft.remoteImage?.checksumSHA256 == checksum) {
            return DraftSyncUpsert(draft.serverID, draft.body, draft.updatedAt)
        }
        val presign = presignUpload(draft.serverID, mimeType, imageData.size.toLong(), checksum, memberID, accessToken)
        uploadImageData(imageData, presign)
        imageCache["${presign.imageId}:$checksum"] = imageData
        return DraftSyncUpsert(
            draftId = draft.serverID,
            body = draft.body,
            updatedAt = draft.updatedAt,
            image = DraftSyncImage(
                imageId = presign.imageId,
                mimeType = mimeType,
                sizeBytes = imageData.size.toLong(),
                checksumSHA256 = checksum,
            ),
        )
    }

    private suspend fun presignUpload(
        draftID: String,
        mimeType: String,
        sizeBytes: Long,
        checksumSHA256: String,
        memberID: String,
        accessToken: String,
    ): PresignUploadResponse {
        val body = AppJson.encodeToString(
            PresignUploadRequest.serializer(),
            PresignUploadRequest(draftID, mimeType, sizeBytes, checksumSHA256),
        )
        val request = authorizedRequest("api/draft-images/presign-upload", memberID, accessToken)
            .post(body.toRequestBody(JSON))
            .build()
        val result = execute(request)
        validate(result, fallback = "Draft sync failed.", authAware = true)
        return AppJson.decodeFromString(PresignUploadResponse.serializer(), result.body.decodeToString())
    }

    private suspend fun uploadImageData(data: ByteArray, presign: PresignUploadResponse) {
        val url = presign.uploadUrl.toHttpUrlOrNull() ?: throw SyncedDraftsError.Server("Draft image upload URL is invalid.")
        val builder = Request.Builder().url(url)
        presign.headers.forEach { (key, value) -> builder.header(key, value) }
        // No Content-Type from OkHttp: the presigned headers carry the signed one.
        val result = execute(builder.put(data.toRequestBody(null)).build())
        validate(result, fallback = "Draft image transfer failed.", authAware = false)
    }

    private suspend fun draft(synced: SyncedDraft): Draft? {
        val uuid = runCatching { UUID.fromString(synced.draftId) }.getOrNull() ?: return null
        val image = try {
            downloadedImage(synced.image)
        } catch (e: Exception) {
            // Keep the draft text; the editor reports the missing image and a refresh retries.
            Log.e(TAG, "Attachment download failed; draft metadata retained: ${e.javaClass.simpleName}")
            null
        }
        return Draft(
            id = uuid,
            wireID = synced.draftId,
            body = synced.body,
            imageData = image?.data,
            imageFilename = image?.filename,
            imageMimeType = image?.mimeType,
            remoteImage = synced.image,
            status = synced.status,
            createdAt = synced.createdAt,
            updatedAt = synced.updatedAt,
        )
    }

    private suspend fun downloadedImage(image: DraftSyncImage?): DownloadedImage? {
        val downloadUrl = image?.downloadUrl?.toHttpUrlOrNull() ?: return null
        val key = "${image.imageId}:${image.checksumSHA256}"
        val data = imageCache[key] ?: run {
            val result = execute(Request.Builder().url(downloadUrl).get().build())
            validate(result, fallback = "Draft image transfer failed.", authAware = false)
            result.body.also { imageCache[key] = it }
        }
        return DownloadedImage(data, "${image.imageId}.${fileExtension(image.mimeType)}", image.mimeType)
    }

    private fun authorizedRequest(path: String, memberID: String, accessToken: String): Request.Builder =
        Request.Builder()
            .url(apiBaseURL.toHttpUrl().newBuilder().addPathSegments(path).addQueryParameter("memberId", memberID).build())
            .header("Authorization", "Bearer $accessToken")

    private suspend fun execute(request: Request): HttpResult = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            HttpResult(response.code, response.body?.bytes() ?: ByteArray(0), request.url.encodedPath)
        }
    }

    private fun validate(result: HttpResult, fallback: String, authAware: Boolean) {
        if (result.code in 200..299) return
        Log.e(TAG, "HTTP ${result.code} for ${result.path}")
        if (authAware && result.code == 401) throw SyncedDraftsError.AuthorizationExpired
        val apiError = runCatching {
            AppJson.decodeFromString(SyncedDraftsAPIError.serializer(), result.body.decodeToString())
        }.getOrNull()
        if (apiError != null) {
            val message = apiError.errorDescription ?: apiError.error
            if (result.code in listOf(400, 413, 422)) throw SyncedDraftsError.Validation(message)
            throw SyncedDraftsError.Server(message)
        }
        throw SyncedDraftsError.Server(fallback)
    }

    private class HttpResult(val code: Int, val body: ByteArray, val path: String)

    companion object {
        private const val TAG = "DraftSync"
        private val JSON = "application/json".toMediaType()

        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        fun fileExtension(mimeType: String): String = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            "image/webp" -> "webp"
            else -> "jpg"
        }

        // Matches the iOS request timeout for sync calls.
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
private data class DraftsResponse(val drafts: List<SyncedDraft>)

@Serializable
private data class DraftSyncRequest(val upserts: List<DraftSyncUpsert>, val deletes: List<String>)

@Serializable
private data class DraftSyncUpsert(
    val draftId: String,
    val body: String,
    @Serializable(with = Iso8601InstantSerializer::class)
    val updatedAt: Instant,
    val image: DraftSyncImage? = null,
    val removeImage: Boolean = false,
)

@Serializable
private data class SyncedDraft(
    val draftId: String,
    val body: String,
    val status: String? = null,
    val image: DraftSyncImage? = null,
    @Serializable(with = Iso8601InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = Iso8601InstantSerializer::class)
    val updatedAt: Instant,
)

@Serializable
private data class PresignUploadRequest(
    val draftId: String,
    val mimeType: String,
    val sizeBytes: Long,
    val checksumSHA256: String,
)

@Serializable
private data class PresignUploadResponse(
    val imageId: String,
    val uploadUrl: String,
    val headers: Map<String, String> = emptyMap(),
)

private class DownloadedImage(val data: ByteArray, val filename: String, val mimeType: String)

@Serializable
private data class SyncedDraftsAPIError(val error: String, val errorDescription: String? = null)
