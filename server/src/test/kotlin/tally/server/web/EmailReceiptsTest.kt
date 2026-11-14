package tally.server.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.email.MailMessage
import tally.core.email.ReceiptReader
import tally.core.llm.CategoryModel
import tally.core.model.Source
import tally.core.model.TransactionStatus
import tally.core.sync.InMemorySyncStore
import tally.core.sync.SyncServer
import tally.server.EmailPoller
import tally.server.InMemoryMailState
import tally.server.MailCursor
import tally.server.Mailbox
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class EmailReceiptsTest {
    private val zone = ZoneId.of("America/Toronto")
    private val now = Instant.parse("2026-11-04T18:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val sync = SyncServer(InMemorySyncStore(), clock)
    private val orders = InMemoryOrderStore()

    /** What the model answers next; counts how often it was asked. */
    private var answer = receiptJson()
    private var asked = 0
    private val model = CategoryModel { _, _ -> asked++; answer }
    private val receipts = EmailReceipts(sync, ReceiptReader(model), orders, clock, zone)

    init {
        sync.write(
            listOf(
                Rows.categories.encode(CategoryRow("food", "Food", null, null, 0, false, 0), null),
                Rows.categories.encode(CategoryRow("groceries", "Groceries", "food", null, 0, false, 0), null),
                Rows.accounts.encode(AccountRow("visa", "RBC Visa", null, null, 0), null),
                Rows.accounts.encode(AccountRow("chequing", "RBC Chequing", null, null, 0), null),
                Rows.accountIdentifiers.encode(AccountIdentifierRow("i1", "visa", "0800", 0), null),
                Rows.rules.encode(RuleRow("r1", 0, "Walmart", null, null, null, "groceries", null, 0), null),
            ),
        )
    }

    private fun receiptJson(total: Double = 64.12, order: String = "200012345", last4: String = "0800", brand: String = "Visa") =
        """{"is_new_charge": true, "store": "Walmart", "total": $total, "currency": "CAD", "date": "2026-11-04",
           "card_brand": "$brand", "card_last4": "$last4", "order_number": "$order", "items": ["Bananas", "Milk 2L", "Bread", "Eggs"]}"""

    private fun mail(id: String = "m1", subject: String = "Thank you for shopping with us", at: Instant = now) =
        MailMessage(id, "Walmart <help@walmart.ca>", subject, at.toEpochMilli()) { "Order total..." }

    private fun stored() = Snapshot.load(sync).transactions

    @Test
    fun `a receipt becomes a capture in the inbox, on the card it names, filed by your rules`() {
        val outcome = receipts.handle(mail())
        val t = (outcome as EmailReceipts.Outcome.Added).transaction
        assertEquals(64_12L, t.amountCents)
        assertEquals(Source.EMAIL.name to TransactionStatus.CAPTURED.name, t.source to t.status)
        assertEquals("visa", t.accountId)
        assertEquals("groceries", t.categoryId)
        assertEquals("Bananas, Milk 2L, Bread", t.note)
        assertEquals("Walmart", Snapshot.load(sync).merchantName(t))
        assertEquals(t, stored().single { it.source == Source.EMAIL.name })
    }

    @Test
    fun `a later receipt for the same order updates the amount, and the same one again changes nothing`() {
        receipts.handle(mail("m1"))
        answer = receiptJson(total = 60.82)
        val updated = receipts.handle(mail("m2", "Your Walmart.ca order receipt")) as EmailReceipts.Outcome.Updated
        assertEquals(60_82L, updated.transaction.amountCents)
        assertEquals(1, stored().count { it.source == Source.EMAIL.name })
        assertEquals(EmailReceipts.Outcome.AlreadySeen, receipts.handle(mail("m3")))
    }

    @Test
    fun `a charge already captured by a tap gets the store and items instead of a second transaction`() {
        val tap = TransactionRow(
            id = "t-tap", amountCents = 64_12, currency = "CAD", occurredAt = now.minusSeconds(3600).toEpochMilli(), zoneId = zone.id,
            accountId = null, merchantId = null, merchantRaw = null, categoryId = null, note = null, source = "SAMSUNG_WALLET",
            status = TransactionStatus.CAPTURED.name, categorySource = null, kind = "EXPENSE", updatedAt = 1,
        )
        sync.write(listOf(Rows.transactions.encode(tap, null)))
        val matched = (receipts.handle(mail()) as EmailReceipts.Outcome.Matched).transaction
        assertEquals("t-tap", matched.id)
        assertEquals("visa" to "Bananas, Milk 2L, Bread", matched.accountId to matched.note)
        assertEquals("Walmart", Snapshot.load(sync).merchantName(stored().single()))
        assertEquals(1, stored().size)
    }

    @Test
    fun `shipping mail never reaches the model, and receipts that aren't charges add nothing`() {
        assertEquals(EmailReceipts.Outcome.NotCandidate, receipts.handle(mail(subject = "Your order has shipped")))
        assertEquals(0, asked)
        answer = """{"is_new_charge": false, "store": "Walmart", "total": 0, "currency": "", "date": "", "card_brand": "", "card_last4": "", "order_number": "", "items": []}"""
        assertEquals(EmailReceipts.Outcome.NotReceipt, receipts.handle(mail()))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `without a last 4 the card name picks the account`() {
        answer = receiptJson(last4 = "", brand = "RBC Visa")
        assertEquals("visa", (receipts.handle(mail()) as EmailReceipts.Outcome.Added).transaction.accountId)
    }

    /** A mailbox of messages with UIDs 1, 2, 3, ... */
    private class FakeMailbox(val messages: List<MailMessage>) : Mailbox {
        var sinceAsked: Instant? = null
        override fun read(cursor: MailCursor?, since: Instant, each: (MailMessage) -> Boolean): MailCursor {
            sinceAsked = since
            var last = cursor?.lastUid ?: 0
            for ((i, m) in messages.withIndex()) {
                val uid = i + 1L
                if (uid <= last) continue
                if (!each(m)) break
                last = uid
            }
            return MailCursor(7, last)
        }
    }

    @Test
    fun `the poller picks up where it left off, and stops for the day at the limit`() {
        val mailbox = FakeMailbox(
            listOf(
                mail("a", "Your order has shipped"),
                mail("b"),
                MailMessage("c", "x", "Your receipt from Cafe", now.toEpochMilli()) { "" },
                MailMessage("d", "x", "Your receipt from Deli", now.toEpochMilli()) { "" },
            ),
        )
        val state = InMemoryMailState()
        val poller = EmailPoller(mailbox, state, receipts, clock, dailyLimit = 2)
        assertEquals(3, poller.runOnce().size) // a (no model), b, c; d waits for tomorrow
        assertEquals(now.minus(java.time.Duration.ofDays(7)), mailbox.sinceAsked)
        assertEquals(MailCursor(7, 3), state.cursor())
        assertEquals(2, asked)
        assertTrue(poller.runOnce().isEmpty())
        assertEquals(MailCursor(7, 3), state.cursor())
    }

    @Test
    fun `when the model is down, the message waits for the next check`() {
        val down = EmailReceipts(sync, ReceiptReader { _, _ -> throw IOException("HTTP 529") }, orders, clock, zone)
        val state = InMemoryMailState()
        EmailPoller(FakeMailbox(listOf(mail("a", "Your order has shipped"), mail("b"))), state, down, clock).runOnce()
        assertEquals(MailCursor(7, 1), state.cursor())
    }
}
