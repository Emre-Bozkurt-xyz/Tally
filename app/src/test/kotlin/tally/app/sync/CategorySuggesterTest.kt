package tally.app.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tally.app.data.CaptureMemory
import tally.app.data.CapturePipeline
import tally.app.data.DefaultData
import tally.app.data.LedgerRepository
import tally.app.data.RawEventEntity
import tally.app.data.TallyDatabase
import tally.core.capture.WatchedPackages
import tally.core.llm.CategorizeRequest
import tally.core.llm.CategorizeResponse
import tally.core.model.CategorySource
import tally.core.model.ParseStatus
import tally.core.model.TransactionStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class CategorySuggesterTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TallyDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val now = Instant.parse("2026-10-17T13:30:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("America/Toronto"))
    private val memory = CaptureMemory(db, clock)
    private val pipeline = CapturePipeline(db, memory, clock)
    private val ledger = LedgerRepository(db, clock, memory)
    private val groceries = DefaultData.categoryId("Food/Groceries")

    private val asked = mutableListOf<CategorizeRequest>()
    private var answer: String? = "Food/Groceries"
    private val suggested = mutableListOf<CapturePipeline.Outcome.Captured>()
    private val suggester = CategorySuggester(db, { asked += it; CategorizeResponse(answer, cached = false) }, clock) { suggested += it }

    @After
    fun close() = db.close()

    private suspend fun capture(merchant: String, at: Instant = now): CapturePipeline.Outcome.Captured {
        val event = RawEventEntity(
            id = UUID.randomUUID().toString(), source = WatchedPackages.GOOGLE_WALLET,
            receivedAt = at.toEpochMilli(), postedAt = at.toEpochMilli(), zoneId = "America/Toronto",
            sbnKey = null, channelId = null, isGroupSummary = false, title = merchant,
            text = "CA\$34.32 with Royal Bank Debit/Débit ••3881", bigText = null, extrasJson = "{}",
            parseStatus = ParseStatus.PENDING.name, transactionId = null,
        )
        db.rawEventDao().insert(event)
        return pipeline.process(event) as CapturePipeline.Outcome.Captured
    }

    @Test
    fun `an unknown merchant gets a suggestion that waits for you, then becomes memory`() = runTest {
        DefaultData.seedIfEmpty(db)
        val first = capture("FOODIE FRUITIE")
        assertNull(first.transaction.categoryId)

        assertEquals(1, suggester.suggestPending())
        val request = asked.single()
        assertEquals("Foodie Fruitie", request.merchant)
        assertEquals(34_32L, request.amountCents)
        assertTrue("Food/Groceries" in request.categories && "Food" in request.categories)
        val t = ledger.getTransaction(first.transaction.id)!!
        assertEquals(groceries, t.categoryId)
        assertEquals(CategorySource.LLM.name, t.categorySource)
        assertEquals(TransactionStatus.CAPTURED.name, t.status)
        assertEquals("Groceries", suggested.single().categoryName)

        // Nothing left to ask about.
        assertEquals(0, suggester.suggestPending())
        assertEquals(1, asked.size)

        // You confirm; next time memory answers and the LLM isn't needed.
        assertTrue(ledger.confirmCapture(t.id))
        val second = capture("FOODIE FRUITIE", now.plusSeconds(3600))
        assertEquals(groceries, second.transaction.categoryId)
        assertEquals(CategorySource.MEMORY.name, second.transaction.categorySource)
        assertEquals(0, suggester.suggestPending())
        assertEquals(1, asked.size)
    }

    @Test
    fun `don't know and unlisted answers change nothing, and your own pick is never overwritten`() = runTest {
        DefaultData.seedIfEmpty(db)
        val captured = capture("XYZ HOLDINGS")
        answer = null
        assertEquals(0, suggester.suggestPending())
        answer = "Pets"
        assertEquals(0, suggester.suggestPending())
        assertNull(ledger.getTransaction(captured.transaction.id)!!.categoryId)

        // A slow answer arriving after you picked a category doesn't win.
        val coffee = DefaultData.categoryId("Food/Coffee")
        val slow = CategorySuggester(db, {
            ledger.saveQuickEntry(ledger.getTransaction(captured.transaction.id), 34_32, coffee, null, note = null)
            CategorizeResponse("Food/Groceries", cached = false)
        }, clock)
        assertEquals(0, slow.suggestPending())
        assertEquals(coffee, ledger.getTransaction(captured.transaction.id)!!.categoryId)
    }
}
