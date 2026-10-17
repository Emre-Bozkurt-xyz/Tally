package tally.app.sync

import kotlinx.coroutines.Dispatchers
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

    private suspend fun call(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        val server = settings()
        if (!server.isComplete) throw SyncAuthException("Set the server URL, client ID and client secret first")
        val url = URI(server.url.trimEnd('/') + path).toURL()
        if (url.protocol != "https") throw IOException("The server URL must start with https://")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // A bad token gets redirected to the Access login page; following it would make
            // an HTML page look like success.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("CF-Access-Client-Id", server.clientId.trim())
            connection.setRequestProperty("CF-Access-Client-Secret", server.clientSecret.trim())
            connection.setRequestProperty("Accept", "application/json")
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
                !type.startsWith("application/json") ->
                    throw SyncAuthException("Expected JSON from the server, got ${type.ifEmpty { "nothing" }}")
            }
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        val json = Json { ignoreUnknownKeys = true }
    }
}
