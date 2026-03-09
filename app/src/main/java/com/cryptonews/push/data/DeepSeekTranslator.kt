package com.cryptonews.push.data

import com.cryptonews.push.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DeepSeekTranslator(
    private val client: OkHttpClient,
    private val json: Json
) {
    suspend fun translateToEnglish(item: NewsEntity): TranslationResult {
        check(BuildConfig.DEEPSEEK_API_KEY.isNotBlank()) {
            "DEEPSEEK_API_KEY is missing. Put it in local.properties before building the app."
        }

        val prompt = buildString {
            appendLine("Translate the following crypto news content into natural English.")
            appendLine("Keep the URL unchanged and do not add commentary.")
            appendLine("Preserve meaning and proper nouns.")
            appendLine()
            appendLine("source_name: ${item.sourceName}")
            appendLine("news_title: ${item.newsTitle}")
            appendLine("coins_included: ${item.coinsIncludedCsv}")
            appendLine("timestamp: ${item.timestamp}")
            appendLine("url: ${item.url}")
            appendLine()
            appendLine("Return only this format:")
            appendLine("source_name: ...")
            appendLine("news_title: ...")
            appendLine("coins_included: ...")
            appendLine("timestamp: ...")
        }

        val body = ChatRequest(
            model = "deepseek-chat",
            messages = listOf(
                ChatMessage(
                    role = "system",
                    content = "You are a translation engine for a crypto news Android app."
                ),
                ChatMessage(role = "user", content = prompt)
            ),
            temperature = 0.1
        )

        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer ${BuildConfig.DEEPSEEK_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(ChatRequest.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("DeepSeek translation failed with HTTP ${response.code}")
            }
            val responseBody = response.body?.string().orEmpty()
            val payload = json.decodeFromString(ChatResponse.serializer(), responseBody)
            val translatedText = payload.choices.firstOrNull()?.message?.content?.trim().orEmpty()
            if (translatedText.isBlank()) {
                error("DeepSeek translation returned an empty response")
            }
            return TranslationResult(translatedText)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double
)

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
private data class ChatResponse(
    val choices: List<Choice>
)

@Serializable
private data class Choice(
    val message: AssistantMessage
)

@Serializable
private data class AssistantMessage(
    @SerialName("content") val content: String
)
