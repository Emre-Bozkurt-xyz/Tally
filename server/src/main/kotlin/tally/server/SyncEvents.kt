package tally.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import tally.core.sync.SyncEvent

/** Fans [SyncEvent]s out to every open `/api/events` stream (phones in the foreground, dashboards). */
class SyncEvents {
    private val flow = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<SyncEvent> = flow

    fun publish(event: SyncEvent) {
        flow.tryEmit(event)
    }
}

/** One Server-Sent Events frame: `event: sync` with `{"cursor":..,"source":".."}`. */
fun SyncEvent.toSse(): String {
    val data = buildJsonObject {
        put("cursor", JsonPrimitive(cursor))
        put("source", JsonPrimitive(source))
    }
    return "event: sync\ndata: $data\n\n"
}

/**
 * `GET /api/events`: a Server-Sent Events stream that says "something changed, sync now".
 * Carries no data beyond the cursor and who wrote it. A comment line every 25 s keeps
 * Cloudflare (100 s idle limit) and FRP from closing a quiet stream.
 */
fun Route.syncEvents(events: SyncEvents) {
    get("/api/events") {
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header("X-Accel-Buffering", "no")
        call.respondTextWriter(ContentType.Text.EventStream) {
            val lock = Mutex()
            suspend fun send(text: String) = lock.withLock {
                withContext(Dispatchers.IO) {
                    write(text)
                    flush()
                }
            }
            send("retry: 5000\n: connected\n\n")
            // A write to a closed connection throws, which ends both loops.
            coroutineScope {
                launch {
                    while (true) {
                        delay(PING_MS)
                        send(": ping\n\n")
                    }
                }
                events.events.collect { send(it.toSse()) }
            }
        }
    }
}

private const val PING_MS = 25_000L
