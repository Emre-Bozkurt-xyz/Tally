package tally.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    /** A category name needs a few tokens; rule suggestions need a page of JSON. */
    private val maxTokens: Int = 32,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val endpoint: URI = URI("https://api.anthropic.com/v1/messages"),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : CategoryModel {
    override fun ask(system: String, user: String): String {
        val body = json.encodeToString(
            MessagesRequest.serializer(),
            MessagesRequest(model = model, maxTokens = maxTokens, system = system, messages = listOf(Message("user", user))),
        )
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            // The error body names the problem (bad key, unknown model); it never echoes the key.
            throw IOException("Anthropic API HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        return json.decodeFromString(MessagesResponse.serializer(), response.body())
            .content.filter { it.type == "text" }.joinToString("") { it.text.orEmpty() }
    }

    @Serializable
    private data class MessagesRequest(
        val model: String,
        @kotlinx.serialization.SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<Message>,
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class MessagesResponse(val content: List<Block> = emptyList())

    @Serializable
    private data class Block(val type: String, val text: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
