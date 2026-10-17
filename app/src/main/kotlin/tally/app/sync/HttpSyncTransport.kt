package tally.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tally.core.llm.CategorizeRequest
import tally.core.llm.CategorizeResponse
import tally.core.sync.SyncRequest
import tally.core.sync.SyncResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Server address and the Cloudflare Access service token the phone signs in with. */
data class ServerSettings(val url: String, val clientId: String, val clientSecret: String) {
    val isComplete get() = url.isNotBlank() && clientId.isNotBlank() && clientSecret.isNotBlank()
}

/** Access refused the service token, or the server isn't behind Access as expected. */
class SyncAuthException(message: String) : IOException(message)

/** The server answered with an error status. */
class ServerHttpException(val code: Int, message: String) : IOException(message)

@Serializable
data class ServerIdentity(val name: String, val kind: String)

/** "Rows changed on the server" from `/api/events`; [source] is the device or page that wrote them. */
@Serializable
data class ServerEvent(val cursor: Long, val source: String)

/**
 * HTTP to the Tally server through Cloudflare Access. Access checks the service token
 * headers and forwards a signed JWT; the server only trusts that JWT.
 */
class HttpSyncTransport(private val settings: suspend () -> ServerSettings) : SyncTransport {
    override suspend fun exchange(request: SyncRequest): SyncResponse =
        json.decodeFromString(call("POST", "/api/sync", json.encodeToString(SyncRequest.serializer(), request)))

    /** A category for a merchant from the server's LLM (cached there per merchant). */
    suspend fun categorize(request: CategorizeRequest): CategorizeResponse =
        json.decodeFromString(call("POST", "/api/categorize", json.encodeToString(CategorizeRequest.serializer(), request)))

    /** Who the server thinks we are: the "Test connection" button. */
    suspend fun whoAmI(): ServerIdentity = json.decodeFromString(call("GET", "/api/me", body = null))

    /**
     * Follows `/api/events` until cancelled or the connection drops (then it throws), calling
     * [onEvent] for each change announced. Cancelling closes the connection.
     */
    suspend fun events(onEvent: suspend (ServerEvent) -> Unit): Unit = coroutineScope {
        val connection = withContext(Dispatchers.IO) {
            // Pings arrive every 25 s; a minute of silence means the stream is dead.
            open("GET", "/api/events", body = null, accept = "text/event-stream", readTimeoutMs = 60_000)
        }
        // A blocking read ignores cancellation; closing the connection ends it.
        val closer = launch { try { awaitCancellation() } finally { connection.disconnect() } }
        try {
            val parser = SseParser()
            withContext(Dispatchers.IO) {
                connection.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val frame = parser.feed(line) ?: continue
                        if (frame.event == "sync") onEvent(json.decodeFromString(ServerEvent.serializer(), frame.data))
                    }
                }
            }
            throw IOException("Event stream ended")
        } finally {
            closer.cancel()
        }
    }

    private suspend fun call(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        val connection = open(method, path, body, accept = "application/json", readTimeoutMs = TIMEOUT_MS)
        try {
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    /** Sends the request and checks the answer is a 2xx of [accept]; the caller reads and disconnects. */
    private suspend fun open(method: String, path: String, body: String?, accept: String, readTimeoutMs: Int): HttpURLConnection {
        val server = settings()
        if (!server.isComplete) throw SyncAuthException("Set the server URL, client ID and client secret first")
        val url = URI(server.url.trimEnd('/') + path).toURL()
        if (url.protocol != "https") throw IOException("The server URL must start with https://")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            // A bad token gets redirected to the Access login page; following it would make
            // an HTML page look like success.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("CF-Access-Client-Id", server.clientId.trim())
            connection.setRequestProperty("CF-Access-Client-Secret", server.clientSecret.trim())
            connection.setRequestProperty("Accept", accept)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val type = connection.contentType.orEmpty()
            when {
                code in 300..399 || code == 401 || code == 403 ->
                    throw SyncAuthException("Cloudflare Access refused the service token (HTTP $code)")
                code !in 200..299 -> {
                    val error = connection.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                    throw ServerHttpException(code, "Server error HTTP $code ${error.take(200)}".trim())
                }
                !type.startsWith(accept) ->
                    throw SyncAuthException("Expected $accept from the server, got ${type.ifEmpty { "nothing" }}")
            }
            return connection
        } catch (e: Throwable) {
            connection.disconnect()
            throw e
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Reads Server-Sent Events line by line: `event:` and `data:` fields, a blank line ends a frame. */
class SseParser {
    data class Frame(val event: String, val data: String)

    private var event = "message"
    private val data = StringBuilder()

    /** Returns a frame when [line] completes one. Comments (`:`) and other fields are skipped. */
    fun feed(line: String): Frame? {
        if (line.isEmpty()) {
            val frame = if (data.isNotEmpty()) Frame(event, data.toString()) else null
            event = "message"
            data.clear()
            return frame
        }
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }
}
