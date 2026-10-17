package tally.server

import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import tally.core.llm.CategorizeService
import tally.core.sync.SyncServer

fun main() {
    val config = ServerConfig.fromEnv()
    val database = Database(config)
    database.migrate()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        val categorize = config.llm?.let {
            CategorizeService(PostgresCategoryCache(database.dataSource, it.model), AnthropicModel(it.apiKey, it.model), it.dailyLimit)
        }
        tallyModule(config.access, database, SyncServer(PostgresSyncStore(database.dataSource)), categorize = categorize)
        monitor.subscribe(ApplicationStopped) { database.close() }
    }.start(wait = true)
}
