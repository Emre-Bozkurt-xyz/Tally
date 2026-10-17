package tally.server.web

import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.model.CategorySource
import tally.core.model.TransactionStatus
import tally.core.sync.InMemorySyncStore
import tally.core.sync.SyncServer
import tally.server.tallyModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class DashboardTest {
    private val zone = ZoneId.of("America/Toronto")
    private val now = Instant.parse("2026-10-17T16:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val events = mutableListOf<tally.core.sync.SyncEvent>()
    private val sync = SyncServer(InMemorySyncStore(), clock) { events += it }
    private val edits = WebEdits(sync, clock)

    private val food = CategoryRow("food", "Food", null, 400_00, 0, false, 0)
    private val coffee = CategoryRow("coffee", "Coffee", "food", null, 0, true, 0)
    private val groceries = CategoryRow("groceries", "Groceries", "food", null, 1, true, 0)
    private val health = CategoryRow("health", "Health", null, null, 1, false, 0)
    private val visa = AccountRow("visa", "RBC Visa", null, null, 0)
    private val fruitie = MerchantRow("m-fruitie", "Foodie Fruitie", null, 0, 0)
    private val tims = MerchantRow("m-tims", "Tim Hortons", "coffee", 3, 0)

    private fun tx(id: String, cents: Long, day: Int, merchant: MerchantRow?, category: String?, status: TransactionStatus, source: CategorySource?) =
        TransactionRow(
            id = id, amountCents = cents, currency = "CAD",
            occurredAt = Instant.parse("2026-10-${day.toString().padStart(2, '0')}T15:00:00Z").toEpochMilli(),
            zoneId = zone.id, accountId = "visa", merchantId = merchant?.id, merchantRaw = merchant?.name?.uppercase(),
            categoryId = category, note = null, source = "GOOGLE_WALLET", status = status.name,
            categorySource = source?.name, kind = "EXPENSE", updatedAt = 1,
        )

    private val coffee1 = tx("t-coffee", 425, 15, tims, "coffee", TransactionStatus.CONFIRMED, CategorySource.MEMORY)
    private val captured = tx("t-fruitie", 34_32, 16, fruitie, "groceries", TransactionStatus.CAPTURED, CategorySource.LLM)
    private val lastMonth = tx("t-old", 50_00, 3, tims, "coffee", TransactionStatus.CONFIRMED, CategorySource.MANUAL)
        .copy(occurredAt = Instant.parse("2026-09-03T15:00:00Z").toEpochMilli())

    init {
        sync.write(
            listOf(food, coffee, groceries, health).map { Rows.categories.encode(it, null) } +
                Rows.accounts.encode(visa, null) +
                listOf(fruitie, tims).map { Rows.merchants.encode(it, null) } +
                listOf(coffee1, captured, lastMonth).map { Rows.transactions.encode(it, null) },
        )
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { tallyModule(null, { true }, sync, zone = zone, clock = clock) }
        block()
    }

    private suspend fun ApplicationTestBuilder.edit(path: String, vararg form: Pair<String, String>, htmx: Boolean = true) =
        client.post(path) {
            if (htmx) header("HX-Request", "true")
            header("X-Tally-Source", "web-page1")
            setBody(FormDataContent(Parameters.build { form.forEach { (k, v) -> append(k, v) } }))
        }

    private fun stored(id: String) = edits.snapshot().transactions.single { it.id == id }

    @Test
    fun `overview shows the month by category, with budget and charts`() = app {
        val html = client.get("/").bodyAsText()
        assertTrue("data-live=\"auto\"" in html && "new EventSource('/api/events')" in html)
        assertTrue("data-live=\"banner\"" in client.get("/rules").bodyAsText())
        assertTrue("October 2026" in html)
        assertTrue("\$38.57" in html, "spent this month") // 4.25 + 34.32
        assertTrue("\$361.43 left" in html, "food budget 400 minus spending")
        assertTrue("Foodie Fruitie" in html && "Tim Hortons" in html)
        assertTrue("const tally = " in html && "chart.umd.min.js" in html)
        assertTrue("\$50.00" in client.get("/?month=2026-09").bodyAsText())
    }

    @Test
    fun `ledger filters by category tree, account and text`() = app {
        val all = client.get("/ledger").bodyAsText()
        assertTrue("2 transactions" in all)
        assertTrue("1 transactions" in client.get("/ledger?category=groceries").bodyAsText())
        assertTrue("2 transactions" in client.get("/ledger?category=food").bodyAsText())
        assertTrue("0 transactions" in client.get("/ledger?category=health").bodyAsText())
        assertTrue("1 transactions" in client.get("/ledger?q=tim").bodyAsText())
        assertTrue("3 transactions" in client.get("/ledger?from=2026-09-01&to=2026-10-31&account=visa").bodyAsText())
    }

    @Test
    fun `changing a category confirms it and teaches merchant memory`() = app {
        assertEquals(HttpStatusCode.Forbidden, edit("/ledger/t-fruitie/category", "categoryId" to "coffee", htmx = false).status)
        assertEquals(TransactionStatus.CAPTURED.name, stored("t-fruitie").status)

        val row = edit("/ledger/t-fruitie/category", "categoryId" to "coffee").bodyAsText()
        assertTrue(row.startsWith("<tr") && "selected" in row)
        val t = stored("t-fruitie")
        assertEquals("coffee", t.categoryId)
        assertEquals(CategorySource.MANUAL.name, t.categorySource)
        assertEquals(TransactionStatus.CONFIRMED.name, t.status)
        assertEquals(now.toEpochMilli(), t.updatedAt)
        val memory = edits.snapshot().merchantsById.getValue("m-fruitie")
        assertEquals("coffee" to 1, memory.lastCategoryId to memory.confirmCount)
        // Announced as this page's edit, so the page itself doesn't reload.
        assertEquals("web-page1", events.last().source)
        val count = events.size

        // An unknown category id changes nothing.
        edit("/ledger/t-coffee/category", "categoryId" to "nope")
        assertEquals("coffee", stored("t-coffee").categoryId)
        assertEquals(count, events.size, "nothing stored, nothing announced")
    }

    @Test
    fun `inbox confirms a suggestion, and ignoring deletes`() = app {
        val inbox = client.get("/inbox").bodyAsText()
        assertTrue("Foodie Fruitie" in inbox && "suggested" in inbox)
        assertTrue("Confirmed." in edit("/inbox/t-fruitie/confirm").bodyAsText())
        val t = stored("t-fruitie")
        assertEquals(TransactionStatus.CONFIRMED.name to CategorySource.LLM.name, t.status to t.categorySource)
        assertEquals("groceries", edits.snapshot().merchantsById.getValue("m-fruitie").lastCategoryId)
        assertTrue("Already handled." in edit("/inbox/t-fruitie/confirm").bodyAsText())
        assertTrue("Nothing to review" in client.get("/inbox").bodyAsText())

        edit("/inbox/t-coffee/ignore")
        assertFalse(edits.snapshot().transactions.any { it.id == "t-coffee" })
    }

    @Test
    fun `money in that matches an owed part is settled from the inbox`() = app {
        sync.write(
            listOf(
                Rows.splits.encode(SplitRow("s1", "t-coffee", 2_00, null, "Danya", 1), null),
                Rows.merchants.encode(MerchantRow("m-danya", "Danya Ijaz", null, 0, 0), null),
                Rows.transactions.encode(
                    coffee1.copy(id = "t-in", amountCents = -2_00, merchantId = "m-danya", merchantRaw = "DANYA IJAZ", categoryId = null,
                        status = TransactionStatus.CAPTURED.name, categorySource = null, kind = "INCOME"),
                    null,
                ),
            ),
        )
        assertTrue("Settles Danya" in client.get("/inbox").bodyAsText())
        assertTrue("Settled." in edit("/inbox/t-in/settle/s1").bodyAsText())
        val s = edits.snapshot()
        assertEquals(TransactionStatus.CONFIRMED.name to "TRANSFER", s.transactions.single { it.id == "t-in" }.let { it.status to it.kind })
        assertTrue(s.openOwed.isEmpty())
    }

    @Test
    fun `rules round trip, and saving writes only what changed`() = app {
        val toml = edits.exportToml()
        assertTrue("Food = [\"Coffee\", \"Groceries\"]" in toml, toml)
        val before = sync.current("categories").toSet()
        assertTrue("Saved" in edit("/rules/save", "toml" to toml).bodyAsText())
        assertEquals(before, sync.current("categories").toSet())
        assertTrue(sync.current("rules").isEmpty())

        val added = toml + "\n[[rule]]\nmerchant = \"Tim Hortons\"\ncategory = \"Food/Coffee\"\ntags = [\"daily\"]\n"
        assertTrue("1 rules" in edit("/rules/check", "toml" to added).bodyAsText())
        assertTrue(sync.current("rules").isEmpty(), "check doesn't save")
        edit("/rules/save", "toml" to added)
        val rule = edits.snapshot().rules.single()
        assertEquals(Triple("Tim Hortons", "coffee", "daily"), Triple(rule.merchantPattern, rule.categoryId, rule.tagsCsv))
        assertTrue("Tim Hortons" in client.get("/rules").bodyAsText())

        val broken = edit("/rules/save", "toml" to "[[rule]]\nmerchant = \"X\"\ncategory = \"Nope\"\n").bodyAsText()
        assertTrue("Not saved" in broken && "Line" in broken, broken)
        assertEquals(1, edits.snapshot().rules.size)
    }

    @Test
    fun `edits keep columns this server doesn't know about`() {
        val stored = Rows.transactions.encode(coffee1, null).data
        val newer = kotlinx.serialization.json.JsonObject(stored + ("futureColumn" to kotlinx.serialization.json.JsonPrimitive("kept")))
        val change = Rows.transactions.encode(coffee1.copy(note = "hi"), newer)
        assertEquals("kept", (change.data["futureColumn"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("hi", (change.data["note"] as kotlinx.serialization.json.JsonPrimitive).content)
    }
}
