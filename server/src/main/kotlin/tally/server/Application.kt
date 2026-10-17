package tally.server

import com.auth0.jwk.JwkProvider
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.event.Level
import tally.core.llm.CategorizeRequest
import tally.core.llm.CategorizeService
import tally.core.llm.DailyLimitReached
import tally.core.sync.SyncRequest
import tally.core.sync.SyncServer
import tally.server.web.StatementImport
import tally.server.web.WebEdits
import tally.server.web.dashboard
import java.time.Clock
import java.time.ZoneId

@Serializable
data class HealthResponse(val ok: Boolean, val service: String, val database: String)

@Serializable
data class MeResponse(val name: String, val kind: String)

/**
 * The HTTP surface. [access] null means local dev without Cloudflare (ACCESS_DISABLED=true);
 * [jwks] is swappable for tests. [categorize] null means no LLM (no API key configured).
 */
fun Application.tallyModule(
    access: AccessConfig?,
    database: DatabaseCheck,
    sync: SyncServer,
    jwks: JwkProvider? = null,
    categorize: CategorizeService? = null,
    zone: ZoneId = ZoneId.of("America/Toronto"),
    clock: Clock = Clock.systemUTC(),
    /** Must be the hub [sync] announces to (see Main), or the stream stays silent. */
    events: SyncEvents = SyncEvents(),
) {
    install(ContentNegotiation) { json() }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path() != "/healthz" }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
        exception<DailyLimitReached> { call, cause ->
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to cause.message))
        }
        exception<java.io.IOException> { call, cause ->
            call.application.environment.log.warn("Upstream call failed: ${cause.message}")
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "upstream unavailable"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal error"))
        }
    }
    if (access != null) {
        install(Authentication) {
            if (jwks != null) cloudflareAccess(access, jwks) else cloudflareAccess(access)
        }
    } else {
        environment.log.warn("ACCESS_DISABLED=true: no authentication. Never use this in production.")
    }

    routing {
        // Liveness for Docker and the deploy script, which call it on the mini-PC directly. Says
        // nothing about your data, so it needs no login.
        get("/healthz") { call.respondText("ok") }

        protectedBy(access) {
            get("/api/health") {
                val up = database.isUp()
                call.respond(
                    if (up) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                    HealthResponse(ok = up, service = "tally", database = if (up) "ok" else "unreachable"),
                )
            }
            post("/api/sync") {
                val request = call.receive<SyncRequest>()
                val response = withContext(Dispatchers.IO) { sync.handle(request) }
                call.respond(response)
            }
            post("/api/categorize") {
                val request = call.receive<CategorizeRequest>()
                if (categorize == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "no ANTHROPIC_API_KEY on the server"))
                    return@post
                }
                call.respond(withContext(Dispatchers.IO) { categorize.categorize(request) })
            }
            syncEvents(events)
            get("/api/me") {
                val caller = call.caller()
                call.respond(MeResponse(caller.name, caller.kind.name))
            }
            dashboard(WebEdits(sync, clock), StatementImport(sync, clock, zone, categorize, this@tallyModule), zone, clock)
        }
    }
}

private fun Route.protectedBy(access: AccessConfig?, build: Route.() -> Unit) {
    if (access != null) authenticate(ACCESS_AUTH, build = build) else build()
}
