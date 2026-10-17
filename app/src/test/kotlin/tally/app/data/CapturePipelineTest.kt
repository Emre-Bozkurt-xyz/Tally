package tally.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tally.core.capture.WatchedPackages
import tally.core.model.ParseStatus
import tally.core.model.TransactionStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** End to end over the real notifications from the first fixture (Google Pay debit purchase). */
@RunWith(RobolectricTestRunner::class)
class CapturePipelineTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TallyDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-17T02:00:00Z"), ZoneId.of("America/Toronto"))
    private val memory = CaptureMemory(db, clock)
    private val pipeline = CapturePipeline(db, memory, clock)
    private val ledger = LedgerRepository(db, clock, memory)

    private val groceries = DefaultData.categoryId("Food/Groceries")
    private val chequing = DefaultData.accountId("RBC Chequing")

    // 2026-10-16 21:04:49 Toronto.
    private val t0 = Instant.ofEpochMilli(1_792_199_089_433)

    @Before
    fun seed() = runTest { DefaultData.seedIfEmpty(db) }

    @After
    fun close() = db.close()

    private suspend fun post(source: String, title: String?, text: String, at: Instant): CapturePipeline.Outcome {
        val event = RawEventEntity(
            id = UUID.randomUUID().toString(),
            source = source,
            receivedAt = at.toEpochMilli() + 300,
            postedAt = at.toEpochMilli(),
            zoneId = "America/Toronto",
            sbnKey = "key-${UUID.randomUUID()}",
            channelId = null,
            isGroupSummary = false,
            title = title,
            text = text,
            bigText = null,
            extrasJson = "{}",
            parseStatus = ParseStatus.PENDING.name,
            transactionId = null,
        )
        db.rawEventDao().insert(event)
        return pipeline.process(event)
    }

    private suspend fun wallet(at: Instant, cents: String = "34.32") =
        post(WatchedPackages.GOOGLE_WALLET, "FOODIE FRUITIE", "CA\$$cents with Royal Bank Debit/Débit ••3881", at)

    private suspend fun rbc(at: Instant, cents: String = "34.32") = post(
        WatchedPackages.RBC,
        "RBC Mobile",
        "A withdrawal of \$$cents was made from your RBC account ********8805 on 10/17.",
        at,
    )

    private suspend fun active() = db.transactionDao().observeActive().first()

    private fun CapturePipeline.Outcome.captured() = this as CapturePipeline.Outcome.Captured

    @Test
    fun `wallet then rbc become one transaction with the wallet's merchant`() = runTest {
        val first = wallet(t0).captured()
        val second = rbc(t0.plusSeconds(21)).captured()

        assertEquals(first.transaction.id, second.transaction.id)
        val transaction = active().single()
        assertEquals(3432L, transaction.amountCents)
        assertEquals(t0.toEpochMilli(), transaction.occurredAt)
        assertEquals("FOODIE FRUITIE", transaction.merchantRaw)
        assertEquals("Foodie Fruitie", second.merchantName)
        assertEquals(TransactionStatus.CAPTURED.name, transaction.status)
        assertNull(transaction.categoryId)
        assertNull(transaction.accountId) // Nothing learned yet.
        assertTrue(second.needsReview)
        assertEquals(
            listOf(ParseStatus.PARSED.name to transaction.id, ParseStatus.PARSED.name to transaction.id),
            db.rawEventDao().getAll().map { it.parseStatus to it.transactionId },
        )
    }

    @Test
    fun `rbc then wallet merges the same way`() = runTest {
        rbc(t0)
        val merged = wallet(t0.plusSeconds(30)).captured()
        assertEquals("FOODIE FRUITIE", merged.transaction.merchantRaw)
        assertEquals(1, active().size)
    }

    @Test
    fun `learns category and account from your edit, then trusts them`() = runTest {
        // First purchase: you set the category and account.
        wallet(t0)
        val first = rbc(t0.plusSeconds(21)).captured().transaction
        ledger.saveQuickEntry(first, first.amountCents, groceries, chequing, note = null)

        // Second purchase: both known; one confirmation is a suggestion, not trusted yet.
        val t1 = t0.plus(Duration.ofDays(1))
        val second = wallet(t1, "12.00").captured()
        assertEquals(chequing, second.transaction.accountId)
        assertEquals(groceries, second.transaction.categoryId)
        assertEquals("Groceries", second.categoryName)
        assertTrue(second.needsReview)
        assertTrue(ledger.confirmCapture(second.transaction.id))

        // Third purchase: confirmed twice, so it logs itself with no notification.
        val third = wallet(t1.plus(Duration.ofDays(1)), "8.50").captured()
        assertEquals(TransactionStatus.CONFIRMED.name, third.transaction.status)
        assertEquals(false, third.needsReview)
    }

    @Test
    fun `an ignored purchase absorbs its second notification`() = runTest {
        val first = wallet(t0).captured().transaction
        ledger.deleteTransaction(first.id)

        val second = rbc(t0.plusSeconds(21)).captured()

        assertEquals(first.id, second.transaction.id)
        assertEquals(false, second.needsReview)
        assertTrue(active().isEmpty())
    }

    @Test
    fun `inbox holds unconfirmed captures and unreadable notifications`() = runTest {
        val captured = wallet(t0).captured().transaction
        post(WatchedPackages.RBC, "RBC Mobile", "Your statement is ready.", t0.plusSeconds(60))
        assertEquals(2, ledger.observeInboxCount().first())

        val unread = ledger.observeUnreadNotifications().first().single()
        ledger.dismissUnreadNotification(unread.id)
        assertEquals(1, ledger.observeInboxCount().first())
        assertTrue(pipeline.reparseUnlinked().isEmpty()) // Dismissed ones aren't retried.

        ledger.saveQuickEntry(captured, captured.amountCents, groceries, captured.accountId, null)
        assertEquals(0, ledger.observeInboxCount().first())
    }

    @Test
    fun `two purchases from the same wallet stay separate`() = runTest {
        wallet(t0)
        wallet(t0.plus(Duration.ofMinutes(2)))
        assertEquals(2, active().size)
    }

    @Test
    fun `unknown shapes and unwatched packages are marked, not captured`() = runTest {
        val failed = post(WatchedPackages.RBC, "RBC Mobile", "Your statement is ready.", t0)
        assertEquals(CapturePipeline.Outcome.Marked::class, failed::class)
        assertEquals(CapturePipeline.Outcome.NotWatched, post("com.discord", "x", "\$5.00", t0))
        assertTrue(active().isEmpty())
    }

    @Test
    fun `reparse picks up events stored before the pipeline existed, in order`() = runTest {
        for ((source, title, text, at) in listOf(
            listOf(WatchedPackages.GOOGLE_WALLET, "FOODIE FRUITIE", "CA\$34.32 with Royal Bank Debit/Débit ••3881", t0),
            listOf(WatchedPackages.RBC, "RBC Mobile", "A withdrawal of \$34.32 was made from your RBC account ********8805 on 10/17.", t0.plusSeconds(21)),
        )) {
            db.rawEventDao().insert(
                RawEventEntity(
                    id = UUID.randomUUID().toString(), source = source as String, receivedAt = (at as Instant).toEpochMilli(),
                    postedAt = at.toEpochMilli(), zoneId = "America/Toronto", sbnKey = null, channelId = null,
                    isGroupSummary = false, title = title as String, text = text as String, bigText = null,
                    extrasJson = "{}", parseStatus = ParseStatus.PENDING.name, transactionId = null,
                ),
            )
        }

        assertEquals(2, pipeline.reparseUnlinked().size)
        assertEquals(1, active().size)
        assertTrue(pipeline.reparseUnlinked().isEmpty()) // Idempotent.
    }
}
