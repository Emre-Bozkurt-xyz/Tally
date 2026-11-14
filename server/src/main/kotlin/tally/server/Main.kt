package tally.server

import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import tally.core.email.ReceiptReader
import tally.core.llm.CategorizeService
import tally.core.sync.SyncServer
import tally.server.web.EmailReceipts
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

fun main() {
    val config = ServerConfig.fromEnv()
    val database = Database(config)
    database.migrate()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        val categorize = config.llm?.let {
            // A category name: no need to think hard, but leave room in case the model does.
            CategorizeService(PostgresCategoryCache(database.dataSource, it.model), AnthropicModel(it.apiKey, it.model, effort = "low"), it.dailyLimit)
        }
        val events = SyncEvents()
        val sync = SyncServer(PostgresSyncStore(database.dataSource), onChange = events::publish)
        // Up to a page of JSON; well inside Cloudflare's 100 s limit for the request waiting on it.
        val suggestModel = config.llm?.let { AnthropicModel(it.apiKey, it.rulesModel, maxTokens = 4000, timeout = Duration.ofSeconds(85)) }
        tallyModule(config.access, database, sync, categorize = categorize, events = events, suggestModel = suggestModel)
        val email = config.email
        val llm = config.llm
        if (email != null && llm != null) {
            val store = PostgresEmailStore(database.dataSource, "${email.address}/${email.folder}")
            val reader = ReceiptReader(AnthropicModel(llm.apiKey, llm.model, maxTokens = 2048, effort = "low", outputSchema = ReceiptReader.SCHEMA))
            val receipts = EmailReceipts(sync, reader, store, Clock.systemUTC(), ZoneId.of("America/Toronto"))
            EmailPoller(ImapMailbox(email.address, email.appPassword, email.folder), store, receipts, Clock.systemUTC(), email.dailyLimit)
                .start(this, Duration.ofMinutes(email.pollMinutes))
            environment.log.info("Receipts by email: checking ${email.folder} every ${email.pollMinutes} min")
        } else if (email != null) {
            environment.log.warn("GMAIL_ADDRESS is set but ANTHROPIC_API_KEY isn't: receipts by email need both")
        }
        monitor.subscribe(ApplicationStopped) { database.close() }
    }.start(wait = true)
}
