package tally.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
    fun `delete hides a transaction and undo brings it back`() = runTest {
        val saved = ledger.saveQuickEntry(null, 425, coffee, cash, note = null)

        ledger.deleteTransaction(saved.id)
        assertNull(ledger.getTransaction(saved.id))
        assertEquals(emptyList<TransactionEntity>(), ledger.observeTransactions().first())

        ledger.restoreTransaction(saved.id)
        assertEquals(listOf(saved.id), ledger.observeTransactions().first().map { it.id })
    }

    @Test
    fun `tags are created once, linked, and removed`() = runTest {
        val a = ledger.saveQuickEntry(null, 425, coffee, cash, note = null)
        val b = ledger.saveQuickEntry(null, 999, groceries, cash, note = null)

        assertNull(ledger.addTag(a.id, "#Trip"))
        assertNull(ledger.addTag(b.id, "trip"))
        assertEquals("Tags can't contain commas", ledger.addTag(a.id, "a,b"))
        assertEquals(listOf("Trip"), ledger.observeTags().first().map { it.name })
        assertEquals(2, ledger.observeTagLinks().first().size)

        ledger.removeTag(a.id, ledger.observeTags().first().single().id)
        assertEquals(emptyList<TagEntity>(), ledger.observeTagsFor(a.id).first())
        assertEquals(listOf(b.id), ledger.observeTagLinks().first().map { it.transactionId })
    }

    @Test
    fun `splits can't exceed the purchase`() = runTest {
        val dinner = ledger.saveQuickEntry(null, 3000, groceries, cash, note = null)

        assertNull(ledger.addSplit(dinner.id, 1000, coffee, owedBy = null))
        assertNull(ledger.addSplit(dinner.id, 1500, null, owedBy = "Sam"))
        assertEquals("Only \$5.00 left to split", ledger.addSplit(dinner.id, 600, coffee, null))
        assertEquals("Pick a category or who owes it", ledger.addSplit(dinner.id, 100, null, "  "))

        val splits = ledger.observeSplitsFor(dinner.id).first()
        assertEquals(setOf(coffee to null, null to "Sam"), splits.map { it.categoryId to it.owedBy }.toSet())
        ledger.deleteSplit(splits.first().id)
        assertEquals(1, ledger.observeSplitsFor(dinner.id).first().size)
    }

    @Test
    fun `owed parts can be settled and unsettled`() = runTest {
        val dinner = ledger.saveQuickEntry(null, 3000, groceries, cash, note = null)
        ledger.addSplit(dinner.id, 1000, null, owedBy = "Sam")
        val part = ledger.observeOwed().first().single()
        assertNull(part.settledAt)

        ledger.setSettled(part.id, true)
        assertEquals(now.toEpochMilli(), ledger.observeOwed().first().single().settledAt)
        ledger.setSettled(part.id, false)
        assertNull(ledger.observeOwed().first().single().settledAt)
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

    @Test
    fun `a manual entry can name its merchant, which then remembers the category`() = runTest {
        val first = ledger.saveQuickEntry(null, 425, coffee, cash, note = null, merchantName = "  Tim Hortons ")
        val merchant = db.merchantDao().getById(first.merchantId!!)!!
        assertEquals("Tim Hortons", merchant.name)
        assertEquals("Tim Hortons", first.merchantRaw)
        assertEquals(coffee to 1, merchant.lastCategoryId to merchant.confirmCount)

        val second = ledger.saveQuickEntry(null, 525, coffee, cash, note = null, merchantName = "Tim Hortons")
        assertEquals(first.merchantId, second.merchantId)
        assertEquals(2, db.merchantDao().getById(first.merchantId!!)!!.confirmCount)
    }

    @Test
    fun `editing keeps the merchant unless you change or clear it`() = runTest {
        val saved = ledger.saveQuickEntry(null, 425, coffee, cash, note = null, merchantName = "Tim Hortons")
        val kept = ledger.saveQuickEntry(saved, 450, coffee, cash, note = null)
        assertEquals(saved.merchantId, kept.merchantId)

        val cleared = ledger.saveQuickEntry(kept, 450, coffee, cash, note = null, merchantName = "")
        assertNull(cleared.merchantId)
        assertNull(cleared.merchantRaw)
        assertNull(ledger.saveQuickEntry(null, 100, coffee, cash, note = null, merchantName = " ").merchantId)
    }
}
