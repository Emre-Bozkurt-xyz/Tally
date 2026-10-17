package tally.server.web

import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.model.CategorySource
import tally.core.model.Kind
import tally.core.model.TransactionStatus
import tally.core.statement.Statement
import tally.core.statement.StatementKind
import tally.core.statement.StatementLine
import tally.core.sync.InMemorySyncStore
import tally.core.sync.SyncServer
import tally.server.tallyModule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StatementImportTest {
    private val zone = ZoneId.of("America/Toronto")
    private val now = Instant.parse("2026-10-17T16:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val sync = SyncServer(InMemorySyncStore(), clock)
    private val import = StatementImport(sync, clock, zone)

    private val coffee = CategoryRow("coffee", "Coffee", null, null, 0, true, 0)
    private val visa = AccountRow("visa", "RBC Visa", null, null, 0)
    private val chequing = AccountRow("chq", "RBC Chequing", null, null, 0)
    private val tims = MerchantRow("m-tims", "Tim Hortons", "coffee", 3, 0)
    private val fruitie = MerchantRow("m-fruitie", "Foodie Fruitie", "coffee", 1, 0)
    private val captured = TransactionRow(
        id = "t-fruitie", amountCents = 34_32, currency = "CAD",
        occurredAt = Instant.parse("2026-09-16T19:00:00Z").toEpochMilli(), zoneId = zone.id,
        accountId = null, merchantId = "m-fruitie", merchantRaw = "FOODIE FRUITIE", categoryId = "coffee", note = null,
        source = "GOOGLE_WALLET", status = TransactionStatus.CONFIRMED.name, categorySource = CategorySource.MANUAL.name,
        kind = "EXPENSE", updatedAt = 1,
    )
    private val elsewhere = captured.copy(id = "t-missing", amountCents = 7_00, merchantId = null, merchantRaw = "SOMEWHERE", accountId = "visa",
        occurredAt = Instant.parse("2026-09-05T19:00:00Z").toEpochMilli())

    private fun line(day: Int, merchant: String, cents: Long, kind: Kind = Kind.EXPENSE) =
        StatementLine(LocalDate.of(2026, 9, day), merchant, merchant, cents, kind)

    private val statement = Statement(
        StatementKind.CREDIT_CARD, "0800", LocalDate.of(2026, 8, 19), LocalDate.of(2026, 9, 18),
        listOf(
            line(17, "FOODIE FRUITIE HAMILTON ON", 36_32), // A tip on top of what the wallet showed.
            line(8, "HOSPITALITY-TIM HORTONS HAMILTON ON", 5_51),
            line(10, "TIM HORTONS #2919 HAMILTON ON", 2_51),
            line(12, "DAY AND NIGHT MARKET HAMILTON ON", 6_75),
            line(8, "AUTOMATIC PAYMENT -THANK YOU", -387_97, Kind.TRANSFER),
        ),
        problems = emptyList(),
    )

    init {
        sync.write(
            listOf(
                Rows.categories.encode(coffee, null), Rows.accounts.encode(visa, null), Rows.accounts.encode(chequing, null),
                Rows.accountIdentifiers.encode(AccountIdentifierRow("i1", "visa", "0800", 0), null),
                Rows.merchants.encode(tims, null), Rows.merchants.encode(fruitie, null),
                Rows.transactions.encode(captured, null), Rows.transactions.encode(elsewhere, null),
                Rows.aliases.encode(AliasRow("a1", "(?i)tim hortons", "Tim Hortons", 0, 0), null),
            ),
        )
    }

    private fun snapshot() = Snapshot.load(sync)

    @Test
    fun `the account is found from the card's last 4, else by name`() {
        assertEquals("visa", import.guessAccount(snapshot(), statement))
        assertEquals("chq", import.guessAccount(snapshot(), statement.copy(kind = StatementKind.CHEQUING, last4 = "9999")))
    }

    @Test
    fun `a preview matches, adds and flags without writing anything`() {
        val before = sync.current("transactions").toSet()
        val plan = import.plan(snapshot(), statement, "visa")
        assertEquals(before, sync.current("transactions").toSet())

        val matched = plan.matched.single()
        assertEquals("t-fruitie", matched.transaction.id)
        assertEquals(36_32L, matched.updated!!.amountCents)
        assertEquals(TransactionStatus.RECONCILED.name, matched.updated.status)
        assertEquals("visa", matched.updated.accountId)

        assertEquals(4, plan.added.size)
        val byMerchant = plan.added.associateBy { it.line.merchant }
        // Aliases name it, trusted memory confirms it.
        val tim = byMerchant.getValue("HOSPITALITY-TIM HORTONS HAMILTON ON").transaction
        assertEquals(Triple("m-tims", "coffee", TransactionStatus.CONFIRMED.name), Triple(tim.merchantId, tim.categoryId, tim.status))
        assertEquals("m-tims", byMerchant.getValue("TIM HORTONS #2919 HAMILTON ON").transaction.merchantId)
        // Unknown merchant: a new merchant, waiting in the inbox.
        val market = byMerchant.getValue("DAY AND NIGHT MARKET HAMILTON ON")
        assertEquals("Day And Night Market", market.merchant?.name)
        assertEquals(null to TransactionStatus.CAPTURED.name, market.transaction.categoryId to market.transaction.status)
        // The card payment is a confirmed transfer with its description as the note.
        val payment = byMerchant.getValue("AUTOMATIC PAYMENT -THANK YOU").transaction
        assertEquals(Triple(Kind.TRANSFER.name, TransactionStatus.CONFIRMED.name, -387_97L), Triple(payment.kind, payment.status, payment.amountCents))

        assertEquals(listOf("t-missing"), plan.missing.map { it.id })
    }

    @Test
    fun `importing writes the plan, and importing again changes nothing`() {
        val token = import.stash(statement)
        val first = import.commit(token, "visa", "web-test")!!
        assertEquals(4, first.added.size)
        val s = snapshot()
        assertEquals(36_32L, s.transactions.single { it.id == "t-fruitie" }.amountCents)
        assertEquals(1 + 1 + 4, s.transactions.size)
        assertTrue(s.merchantsById.values.any { it.name == "Day And Night Market" })
        assertTrue(s.transactions.filter { it.source == "CSV" }.all { s.date(it).monthValue == 9 && it.accountId == "visa" })

        val again = import.commit(import.stash(statement), "visa", "web-test")!!
        assertEquals(0, again.added.size)
        assertEquals(4, again.alreadyImported)
        assertTrue(again.matched.all { it.updated == null })
        assertEquals(s.transactions.size, snapshot().transactions.size)
    }

    @Test
    fun `the page uploads, and non-statements are refused`() = testApplication {
        application { tallyModule(null, { true }, sync, zone = zone, clock = clock) }
        assertTrue("Read statement" in client.get("/statements").bodyAsText())
        val response = client.submitFormWithBinaryData(
            url = "/statements/preview",
            formData = formData {
                append("pdf", "not a pdf".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "application/pdf")
                    append(HttpHeaders.ContentDisposition, "filename=\"x.pdf\"")
                })
            },
        ) { header("HX-Request", "true") }
        assertTrue("isn't an RBC statement" in response.bodyAsText())
    }
}
