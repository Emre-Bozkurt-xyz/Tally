package tally.server

import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import tally.core.sync.SyncServer

fun main() {
    val config = ServerConfig.fromEnv()
    val database = Database(config)
    database.migrate()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        tallyModule(config.access, database, SyncServer(PostgresSyncStore(database.dataSource)))
        monitor.subscribe(ApplicationStopped) { database.close() }
    }.start(wait = true)
}
