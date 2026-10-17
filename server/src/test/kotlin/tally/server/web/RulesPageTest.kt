package tally.server.web

import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.model.CategorySource
import tally.core.model.TransactionStatus
import tally.core.normalize.MerchantIds
import tally.core.sync.InMemorySyncStore
import tally.core.sync.SyncServer
import tally.server.tallyModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class RulesPageTest {
    private val zone = ZoneId.of("America/Toronto")
    private val clock = Clock.fixed(Instant.parse("2026-10-17T16:00:00Z"), zone)
    private val sync = SyncServer(InMemorySyncStore(), clock)
    private val rules = RulesEdits(sync, clock)

    private val food = CategoryRow("food", "Food", null, null, 0, false, 0)
    private val delivery = CategoryRow("delivery", "Delivery", "food", null, 0, false, 0)
    private val coffee = CategoryRow("coffee", "Coffee", "food", null, 1, false, 0)
    private val wendys = MerchantRow("m-old", "Doordashwendys", null, 0, 0)
    private val order = TransactionRow(
        id = "t1", amountCents = 16_42, currency = "CAD", occurredAt = Instant.parse("2026-09-08T16:00:00Z").toEpochMilli(),
        zoneId = zone.id, accountId = null, merchantId = "m-old", merchantRaw = "DOORDASHWENDYS", categoryId = null, note = null,
        source = "CSV", status = TransactionStatus.CAPTURED.name, categorySource = null, kind = "EXPENSE", updatedAt = 1,
    )
    private val mine = order.copy(id = "t2", categoryId = "coffee", status = TransactionStatus.CONFIRMED.name, categorySource = "MANUAL")

    init {
        sync.write(
            listOf(food, delivery, coffee).map { Rows.categories.encode(it, null) } + Rows.merchants.encode(wendys, null) +
                listOf(order, mine).map { Rows.transactions.encode(it, null) },
        )
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { tallyModule(null, { true }, sync, zone = zone, clock = clock) }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(path: String, vararg form: Pair<String, String>): HttpResponse =
        client.post(path) {
            header("HX-Request", "true")
            setBody(FormDataContent(Parameters.build { form.forEach { (k, v) -> append(k, v) } }))
        }

    private fun s() = rules.snapshot()

    @Test
    fun `an alias renames past merchants, a rule files them, and replay applies it to the inbox only`() = app {
        assertTrue("Rules" in client.get("/rules").bodyAsText())

        val alias = post("/rules/alias/save", "pattern" to "(?i)doordash", "name" to "DoorDash")
        assertEquals("#aliases-editor", alias.headers["HX-Retarget"])
        val doordash = MerchantIds.forName("DoorDash")
        assertEquals(setOf(doordash), s().transactions.map { it.merchantId }.toSet())
        assertTrue("1 past" !in alias.bodyAsText() && "2 past" in alias.bodyAsText())

        val tried = post("/rules/try", "raw" to "DOORDASHMAGLICI", "amount" to "29.19").bodyAsText()
        assertTrue("Becomes" in tried && "DoorDash" in tried && "inbox" in tried)

        val saved = post("/rules/rule/save", "pattern" to "DoorDash", "category" to "delivery", "tags" to " takeout , ", "ask" to "")
        assertEquals("#rules-editor", saved.headers["HX-Retarget"])
        val rule = s().rules.single()
        assertEquals(Triple("DoorDash", "delivery", "takeout"), Triple(rule.merchantPattern, rule.categoryId, rule.tagsCsv))
        assertTrue("Files under Food › Delivery" in post("/rules/try", "raw" to "DOORDASHMAGLICI").bodyAsText())

        val preview = post("/rules/replay/preview").bodyAsText()
        assertTrue("1 would be filed" in preview, preview)
        assertTrue("Updated 1 transactions" in post("/rules/replay/apply").bodyAsText())
        val filed = s().transactions.associateBy { it.id }
        assertEquals(listOf("delivery", TransactionStatus.CONFIRMED.name, CategorySource.RULE.name), filed.getValue("t1").let { listOf(it.categoryId, it.status, it.categorySource) })
        assertEquals("coffee", filed.getValue("t2").categoryId, "yours stays")
        assertTrue("Nothing to change" in post("/rules/replay/preview").bodyAsText())
    }

    @Test
    fun `bad input comes back on its row, keeping what you typed`() = app {
        val badRegex = post("/rules/rule/save", "pattern" to "Doordash(", "category" to "delivery").bodyAsText()
        assertTrue("row-error" in badRegex && "Doordash(" in badRegex)
        assertNull(badRegex.lines().firstOrNull { "HX-Retarget" in it })
        assertTrue("isn't an amount" in post("/rules/rule/save", "pattern" to "X", "category" to "delivery", "min" to "ten").bodyAsText())
        assertTrue("minimum is above" in post("/rules/rule/save", "pattern" to "X", "category" to "delivery", "min" to "20", "max" to "10").bodyAsText())
        assertTrue("Not a valid pattern" in post("/rules/alias/save", "pattern" to "(", "name" to "X").bodyAsText())
        assertTrue(s().rules.isEmpty() && s().aliases.isEmpty())
    }

    @Test
    fun `rules reorder, and ask is kept`() = app {
        post("/rules/rule/save", "pattern" to "A", "category" to "coffee")
        post("/rules/rule/save", "pattern" to "B", "category" to "coffee", "ask" to "true")
        val b = s().rules.single { it.merchantPattern == "B" }
        assertTrue(b.ask)
        post("/rules/rule/${b.id}/up")
        assertEquals(listOf("B", "A"), s().rules.sortedBy { it.priority }.map { it.merchantPattern })
        post("/rules/rule/${b.id}/delete")
        assertEquals(listOf("A"), s().rules.filter { !it.deleted }.map { it.merchantPattern })
    }

    @Test
    fun `categories add, rename with a budget, and refuse deleting a parent`() = app {
        post("/rules/category/add", "name" to "Income")
        val income = s().categoryTree.single { it.name == "Income" }
        assertTrue("already exists" in post("/rules/category/add", "name" to "coffee", "parent" to "food").bodyAsText())

        post("/rules/category/coffee/save", "name" to "Coffee & tea", "parent" to "food", "budget" to "40", "pinned" to "true")
        val renamed = s().categoriesById.getValue("coffee")
        assertEquals(listOf("Coffee & tea", 40_00L, true), listOf(renamed.name, renamed.monthlyBudgetCents, renamed.pinned))

        assertTrue("subcategories of Food" in post("/rules/category/food/delete").bodyAsText())
        post("/rules/category/${income.id}/delete")
        assertTrue(s().categoryTree.none { it.id == income.id })
    }
}
