package tally.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import tally.core.llm.CategoryModel
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Claude through the Messages API. A short answer is all we need, so no SDK. */
class AnthropicModel(
    private val apiKey: String,
    private val model: String,
    /**
     * Room for the answer, and for any thinking before it: current models think by default,
     * and that counts toward this cap.
     */
    private val maxTokens: Int = 1024,
    /** "low" for short, simple answers (categories, receipts); null leaves the model's default. */
    private val effort: String? = null,
    /** A JSON schema the answer must follow (structured outputs); null for free text. */
    private val outputSchema: JsonObject? = null,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val endpoint: URI = URI("https://api.anthropic.com/v1/messages"),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : CategoryModel {
    override fun ask(system: String, user: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            if (effort != null || outputSchema != null) {
                putJsonObject("output_config") {
                    effort?.let { put("effort", it) }
                    outputSchema?.let { schema ->
                        putJsonObject("format") {
                            put("type", "json_schema")
                            put("schema", schema)
                        }
                    }
                }
            }
        }
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            // The error body names the problem (bad key, unknown model); it never echoes the key.
            throw IOException("Anthropic API HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        val message = json.decodeFromString(MessagesResponse.serializer(), response.body())
        if (message.stopReason == "refusal") throw IOException("Anthropic API declined the request")
        // Thinking blocks come first on current models; the answer is in the text blocks.
        return message.content.filter { it.type == "text" }.joinToString("") { it.text.orEmpty() }
    }

    @Serializable
    private data class MessagesResponse(
        val content: List<Block> = emptyList(),
        @kotlinx.serialization.SerialName("stop_reason") val stopReason: String? = null,
    )

    @Serializable
    private data class Block(val type: String, val text: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
