package tally.server

import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import tally.core.llm.CategorizeService
import tally.core.sync.SyncServer
import java.time.Duration

fun main() {
    val config = ServerConfig.fromEnv()
    val database = Database(config)
    database.migrate()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        val categorize = config.llm?.let {
            CategorizeService(PostgresCategoryCache(database.dataSource, it.model), AnthropicModel(it.apiKey, it.model), it.dailyLimit)
        }
        val events = SyncEvents()
        val sync = SyncServer(PostgresSyncStore(database.dataSource), onChange = events::publish)
        // Up to a page of JSON; well inside Cloudflare's 100 s limit for the request waiting on it.
        val suggestModel = config.llm?.let { AnthropicModel(it.apiKey, it.rulesModel, maxTokens = 4000, timeout = Duration.ofSeconds(85)) }
        tallyModule(config.access, database, sync, categorize = categorize, events = events, suggestModel = suggestModel)
        monitor.subscribe(ApplicationStopped) { database.close() }
    }.start(wait = true)
}
