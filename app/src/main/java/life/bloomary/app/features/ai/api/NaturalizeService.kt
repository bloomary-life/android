package life.bloomary.app.features.ai.api

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import life.bloomary.app.features.ai.storage.AISettings
import life.bloomary.app.shared.network.AppJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Mirrors ios/Features/AI/API/NaturalizeService.swift.
 */
sealed class NaturalizeError(message: String) : Exception(message) {
    data object EmptyText : NaturalizeError("Post is empty.")
    data object MissingAPIKey : NaturalizeError("Add your AI API key in Settings first.")
    class Server(message: String) : NaturalizeError(message)
}

class NaturalizeService(
    client: OkHttpClient = defaultClient(),
) {
    // iOS sets timeoutInterval = 30 on the request.
    private val client = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun naturalize(text: String, settings: AISettings): String {
        val body = text.trim()
        if (body.isEmpty()) throw NaturalizeError.EmptyText
        val apiKey = settings.apiKey.trim()
        if (apiKey.isEmpty()) throw NaturalizeError.MissingAPIKey

        val payload = ChatCompletionRequest(
            model = MODEL,
            messages = listOf(
                ChatMessage(role = "system", content = NATURALIZE_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = "Edit this LinkedIn draft:\n\n$text"),
            ),
            temperature = 0.2,
        )
        val request = Request.Builder()
            .url(CHAT_COMPLETION_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(
                AppJson.encodeToString(ChatCompletionRequest.serializer(), payload)
                    .toByteArray(Charsets.UTF_8)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        val result = withContext(Dispatchers.IO) {
            this@NaturalizeService.client.newCall(request).execute().use { response ->
                HttpResult(code = response.code, body = response.body?.string().orEmpty())
            }
        }

        if (result.code !in 200..299) {
            val apiError = runCatching {
                AppJson.decodeFromString(GeminiAPIErrorResponse.serializer(), result.body)
            }.getOrNull()
            if (apiError != null) {
                throw NaturalizeError.Server(apiError.error.message)
            }
            throw NaturalizeError.Server("AI edit failed.")
        }
        val edited = AppJson.decodeFromString(ChatCompletionResponse.serializer(), result.body)
            .choices.firstOrNull()?.message?.content?.trim()
        if (edited.isNullOrEmpty()) {
            throw NaturalizeError.Server("AI edit failed.")
        }
        return edited
    }

    private class HttpResult(val code: Int, val body: String)

    companion object {
        private const val CHAT_COMPLETION_URL =
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        private const val MODEL = "gemini-3.8-flash"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Verbatim copy of naturalizeSystemPrompt in NaturalizeService.swift —
        // wording affects output parity, do not edit without changing iOS too.
        private const val NATURALIZE_SYSTEM_PROMPT =
            "You receive a LinkedIn post draft from the user. Draft a LinkedIn post, fix grammar, naturalize English, preserve the user's style, and do not modify meaning. Make the result sound more like the user: preserve their voice, rhythm, directness, emotional logic, and slightly non-native but clear English phrasing when it adds authenticity. Make it hooked in the first 2-3 sentences. Carefully compress the draft: eliminate unnecessary words and replace longer words with shorter ones, ensuring the post retains its original meaning, information, and tone. Wrap any moments you want highlighted — results, numbers, surprising facts, punchy one-liners — in double asterisks like **this**. End the post with exactly three relevant hashtags on one final line. Replace or deduplicate any existing hashtags so the final post contains exactly three hashtags total. Do not explain your edits or wrap the answer in quotes. Return only the edited post."

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder().build()
    }
}

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
)

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatCompletionResponse(
    val choices: List<Choice>,
) {
    @Serializable
    data class Choice(
        val message: ChatMessage,
    )
}

@Serializable
private data class GeminiAPIErrorResponse(
    val error: GeminiAPIError,
) {
    @Serializable
    data class GeminiAPIError(
        val message: String,
    )
}
