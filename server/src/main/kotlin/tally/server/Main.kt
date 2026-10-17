package tally.server

import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = ServerConfig.fromEnv()
    val database = Database(config)
    database.migrate()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        tallyModule(config.access, database)
        monitor.subscribe(ApplicationStopped) { database.close() }
    }.start(wait = true)
}
