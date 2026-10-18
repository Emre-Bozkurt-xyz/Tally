package tally.server.web

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tally.core.model.CategorySource
import tally.core.model.TransactionStatus
import tally.core.sync.InMemorySyncStore
import tally.core.sync.SyncServer
import tally.server.tallyModule
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** Renders every page with made-up data into TALLY_RENDER_DIR, to look at a design change without a server. */
@EnabledIfEnvironmentVariable(named = "TALLY_RENDER_DIR", matches = ".+")
class RenderPagesTest {
    private val zone = ZoneId.of("America/Toronto")
    private val clock = Clock.fixed(Instant.parse("2026-10-17T16:00:00Z"), zone)
    private val sync = SyncServer(InMemorySyncStore(), clock)

    @Test
    fun render() = testApplication {
        val cats = listOf(
            CategoryRow("food", "Food", null, 600_00, 0, false, 0),
            CategoryRow("groceries", "Groceries", "food", null, 0, true, 0),
            CategoryRow("dining", "Dining out", "food", null, 1, true, 0),
            CategoryRow("coffee", "Coffee", "food", null, 2, true, 0),
            CategoryRow("transport", "Transport", null, 150_00, 1, false, 0),
            CategoryRow("transit", "Transit", "transport", null, 0, false, 0),
            CategoryRow("rideshare", "Rideshare", "transport", null, 1, false, 0),
            CategoryRow("bills", "Bills", null, null, 2, false, 0),
            CategoryRow("phone", "Phone", "bills", null, 0, false, 0),
            CategoryRow("subs", "Subscriptions", "bills", null, 1, false, 0),
            CategoryRow("income", "Income", null, null, 3, false, 0),
            CategoryRow("pay", "Paycheque", "income", null, 0, false, 0),
        )
        val merchants = listOf(
            "Fresh Market" to "groceries", "Noodle House" to "dining", "Corner Cafe" to "coffee", "Metro Transit" to "transit",
            "Ride Co" to "rideshare", "PhoneCo" to "phone", "StreamBox" to "subs", "Bean Bar" to "coffee", "Taco Stand" to "dining",
        ).mapIndexed { i, (name, cat) -> MerchantRow("m$i", name, cat, 3, 0) to cat }
        val r = Random(7)
        val txs = mutableListOf<TransactionRow>()
        var start = LocalDate.of(2025, 11, 1)
        val end = LocalDate.of(2026, 10, 17)
        var n = 0
        while (!start.isAfter(end)) {
            repeat(r.nextInt(0, 3)) {
                val (m, cat) = merchants[r.nextInt(merchants.size)]
                val cents = when (cat) { "groceries" -> r.nextLong(20_00, 90_00); "dining" -> r.nextLong(15_00, 60_00); "coffee" -> r.nextLong(3_00, 8_00); "phone" -> 55_00; "subs" -> 16_99; else -> r.nextLong(3_00, 30_00) }
                val captured = start.isAfter(end.minusDays(4)) && it == 0
                txs += TransactionRow(
                    id = "t${n++}", amountCents = cents, currency = "CAD",
                    occurredAt = start.atTime(9 + r.nextInt(12), r.nextInt(60)).atZone(zone).toInstant().toEpochMilli(),
                    zoneId = zone.id, accountId = if (r.nextBoolean()) "visa" else "cheq", merchantId = m.id, merchantRaw = m.name.uppercase(),
                    categoryId = cat, note = null, source = "GOOGLE_WALLET",
                    status = (if (captured) TransactionStatus.CAPTURED else TransactionStatus.CONFIRMED).name,
                    categorySource = (if (captured) CategorySource.LLM else CategorySource.RULE).name, kind = "EXPENSE", updatedAt = 1,
                )
            }
            if (start.dayOfMonth == 15 || start.dayOfMonth == 1) {
                txs += TransactionRow("p${n++}", -2100_00, "CAD", start.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), zone.id, "cheq",
                    null, "PAYROLL ACME", "pay", null, "RBC_ALERT", TransactionStatus.CONFIRMED.name, CategorySource.RULE.name, "INCOME", 1)
            }
            start = start.plusDays(1)
        }
        sync.write(
            cats.map { Rows.categories.encode(it, null) } +
                listOf(AccountRow("visa", "RBC Visa", null, null, 0), AccountRow("cheq", "RBC Chequing", null, null, 0)).map { Rows.accounts.encode(it, null) } +
                merchants.map { Rows.merchants.encode(it.first, null) } +
                txs.map { Rows.transactions.encode(it, null) },
        )
        application { tallyModule(null, { true }, sync, zone = zone, clock = clock) }
        val dir = File(System.getenv("TALLY_RENDER_DIR")).apply { mkdirs() }
        listOf("/" to "overview", "/ledger" to "ledger", "/inbox" to "inbox", "/rules" to "rules", "/statements" to "statements").forEach { (path, name) ->
            File(dir, "$name.html").writeText(client.get(path).bodyAsText().replace("url(/assets/", "url(assets/").replace("href=\"/assets/", "href=\"assets/"))
        }
    }
}
