package tally.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI

/** Against a local stand-in for the Messages API: the request shape, and reading the reply. */
class AnthropicModelTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var status = 200
    private var reply = """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"Food/Groceries"}],"stop_reason":"end_turn"}"""
    private val received = mutableListOf<Pair<Map<String, String>, String>>()

    init {
        server.createContext("/v1/messages") { exchange ->
            received += exchange.requestHeaders.mapValues { it.value.single() }.mapKeys { it.key.lowercase() } to
                exchange.requestBody.readBytes().decodeToString()
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private val model = AnthropicModel(
        apiKey = "test-key",
        model = "claude-haiku-5-5",
        endpoint = URI("http://127.0.0.1:${server.address.port}/v1/messages"),
    )

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `sends a messages request and returns the text`() {
        assertEquals("Food/Groceries", model.ask("system prompt", "Merchant: X"))
        val (headers, body) = received.single()
        assertEquals("test-key", headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("claude-haiku-5-5", json["model"]?.jsonPrimitive?.content)
        assertEquals("system prompt", json["system"]?.jsonPrimitive?.content)
        assertEquals(32, json["max_tokens"]?.jsonPrimitive?.content?.toInt())
        val message = json["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]?.jsonPrimitive?.content)
        assertEquals("Merchant: X", message["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `api errors surface with their message`() {
        status = 404
        reply = """{"type":"error","error":{"type":"not_found_error","message":"model: claude-nope"}}"""
        val error = assertThrows<IOException> { model.ask("s", "u") }
        assertTrue("404" in error.message!! && "claude-nope" in error.message!!)
    }
}
