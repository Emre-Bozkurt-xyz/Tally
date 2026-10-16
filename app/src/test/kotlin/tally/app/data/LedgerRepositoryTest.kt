package tally.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tally.core.entry.CategoryRanking
import tally.core.model.CategorySource
import tally.core.model.Kind
import tally.core.model.Source
import tally.core.model.TransactionStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TallyDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val zone = ZoneId.of("America/Toronto")
    private var now = Instant.parse("2026-10-16T13:30:00Z")
    private val ledger get() = LedgerRepository(db, Clock.fixed(now, zone))

    private val coffee = DefaultData.categoryId("Food/Coffee")
    private val groceries = DefaultData.categoryId("Food/Groceries")
    private val cash = DefaultData.accountId("Cash")
    private val visa = DefaultData.accountId("RBC Visa")

    @After
    fun close() = db.close()

    @Test
    fun `new quick entry is a confirmed manual expense happening now`() = runTest {
        val saved = ledger.saveQuickEntry(null, 425, coffee, cash, note = null)

        val stored = ledger.getTransaction(saved.id)!!
        assertEquals(425L, stored.amountCents)
        assertEquals("CAD", stored.currency)
        assertEquals(now.toEpochMilli(), stored.occurredAt)
        assertEquals("America/Toronto", stored.zoneId)
        assertEquals(coffee, stored.categoryId)
        assertEquals(cash, stored.accountId)
        assertNull(stored.note)
        assertEquals(Source.MANUAL.name, stored.source)
        assertEquals(TransactionStatus.CONFIRMED.name, stored.status)
        assertEquals(CategorySource.MANUAL.name, stored.categorySource)
        assertEquals(Kind.EXPENSE.name, stored.kind)
    }

    @Test
    fun `editing keeps id, time and source but updates the rest`() = runTest {
        val original = ledger.saveQuickEntry(null, 425, coffee, cash, note = null)
        now = now.plus(Duration.ofHours(2))

        ledger.saveQuickEntry(original, 1299, groceries, visa, note = "milk")

        val stored = ledger.getTransaction(original.id)!!
        assertEquals(1299L, stored.amountCents)
        assertEquals(groceries, stored.categoryId)
        assertEquals(visa, stored.accountId)
        assertEquals("milk", stored.note)
        assertEquals(original.occurredAt, stored.occurredAt)
        assertEquals(Source.MANUAL.name, stored.source)
        assertEquals(now.toEpochMilli(), stored.updatedAt)
    }

    @Test
    fun `category uses only cover the ranking window`() = runTest {
        ledger.saveQuickEntry(null, 100, groceries, cash, note = null)
        now = now.plus(Duration.ofDays(CategoryRanking.WINDOW_DAYS - 1))
        ledger.saveQuickEntry(null, 200, coffee, cash, note = null)
        now = now.plus(Duration.ofDays(2))

        val uses = ledger.recentCategoryUses()

        assertEquals(listOf(coffee), uses.map { it.categoryId })
        assertEquals(zone, uses.single().at.zone)
    }
}
