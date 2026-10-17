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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.llm.CategoryModel
import tally.core.model.TransactionStatus
import tally.core.normalize.MerchantIds
import tally.core.sync.InMemorySyncStore
import tally.core.sync.SyncServer
import tally.server.tallyModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class RuleSuggestionsPageTest {
    private val zone = ZoneId.of("America/Toronto")
    private val clock = Clock.fixed(Instant.parse("2026-10-17T16:00:00Z"), zone)
    private val sync = SyncServer(InMemorySyncStore(), clock)
    private val edits = RulesEdits(sync, clock)
    private val asked = mutableListOf<String>()
    private val model = CategoryModel { _, user ->
        asked += user
        """Sure: {"aliases":[{"match":"(?i)doordash","name":"DoorDash","why":"one merchant"},{"match":"(?i)nothing","name":"X"}],
           "rules":[{"merchant":"DoorDash","category":"Food/Delivery","why":"food delivery"}]}"""
    }

    private val food = CategoryRow("food", "Food", null, null, 0, false, 0)
    private val delivery = CategoryRow("delivery", "Delivery", "food", null, 0, false, 0)
    private val coffee = CategoryRow("coffee", "Coffee", "food", null, 1, false, 0)

    private fun tx(id: String, merchant: MerchantRow, raw: String, category: String?, status: TransactionStatus) = TransactionRow(
        id = id, amountCents = 10_00, currency = "CAD", occurredAt = Instant.parse("2026-09-08T16:00:00Z").toEpochMilli(),
        zoneId = zone.id, accountId = null, merchantId = merchant.id, merchantRaw = raw, categoryId = category, note = null,
        source = "CSV", status = status.name, categorySource = null, kind = "EXPENSE", updatedAt = 1,
    )

    init {
        val tims = MerchantRow("m-tims", "Tim Hortons", "coffee", 3, 0)
        val wendys = MerchantRow("m-w", "Doordashwendys", null, 0, 0)
        val maglici = MerchantRow("m-m", "Doordashmaglici", null, 0, 0)
        sync.write(
            listOf(food, delivery, coffee).map { Rows.categories.encode(it, null) } +
                listOf(tims, wendys, maglici).map { Rows.merchants.encode(it, null) } +
                (1..3).map { Rows.transactions.encode(tx("tims$it", tims, "TIM HORTONS #$it", "coffee", TransactionStatus.CONFIRMED), null) } +
                Rows.transactions.encode(tx("w", wendys, "DOORDASHWENDYS", null, TransactionStatus.CAPTURED), null) +
                Rows.transactions.encode(tx("m", maglici, "DOORDASHMAGLICI", null, TransactionStatus.CAPTURED), null),
        )
    }

    private fun app(withModel: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { tallyModule(null, { true }, sync, zone = zone, clock = clock, suggestModel = model.takeIf { withModel }) }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(path: String, vararg form: Pair<String, String>): HttpResponse =
        client.post(path) {
            header("HX-Request", "true")
            setBody(FormDataContent(Parameters.build { form.forEach { (k, v) -> append(k, v) } }))
        }

    @Test
    fun `history suggests a rule for a merchant always filed the same way, and adding it saves it`() = app {
        val shown = post("/rules/suggest/history").bodyAsText()
        assertTrue("Tim Hortons" in shown && "3 times" in shown, shown)
        assertTrue("Doordash" !in shown)

        val added = post("/rules/suggest/add", "pick" to "r0", "r0_merchant" to "Tim Hortons", "r0_category" to "Food/Coffee", "r0_ask" to "false").bodyAsText()
        assertTrue("Added 0 aliases and 1 rules" in added, added)
        assertTrue("hx-swap-oob=\"true\"" in added)
        val rule = edits.snapshot().rules.single()
        assertEquals("Tim Hortons" to "coffee", rule.merchantPattern to rule.categoryId)
        assertTrue("Nothing yet" in post("/rules/suggest/history").bodyAsText())
    }

    @Test
    fun `Claude's suggestions are checked, shown with their effect, and added as ticked`() = app {
        val shown = post("/rules/suggest/model").bodyAsText()
        assertTrue("Doordashwendys" in asked.single() && "Food/Delivery" in asked.single())
        assertTrue("renames 2 transactions" in shown, shown)
        assertTrue("matches 2, 2 waiting to be filed" in shown, shown)
        assertTrue("1 suggestions left out" in shown)

        val added = post(
            "/rules/suggest/add",
            "pick" to "a0", "a0_match" to "(?i)doordash", "a0_name" to "DoorDash",
            "pick" to "r0", "r0_merchant" to "DoorDash", "r0_category" to "Food/Delivery", "r0_ask" to "false",
        ).bodyAsText()
        assertTrue("Added 1 aliases and 1 rules" in added, added)
        assertTrue("match 2 transactions waiting" in added)
        val s = edits.snapshot()
        assertEquals(setOf(MerchantIds.forName("DoorDash")), s.transactions.filter { it.id in setOf("w", "m") }.map { it.merchantId }.toSet())
        assertEquals(2, edits.replayPlan(s).size)
    }

    @Test
    fun `without a model the button is off and the endpoint says why`() = app(withModel = false) {
        assertTrue("Needs ANTHROPIC_API_KEY" in client.get("/rules").bodyAsText())
        assertTrue("Couldn't get suggestions" in post("/rules/suggest/model").bodyAsText())
    }
}
