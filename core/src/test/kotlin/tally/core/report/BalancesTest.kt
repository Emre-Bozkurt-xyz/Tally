package tally.core.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.model.Kind
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class BalancesTest {
    private val day = 86_400_000L

    private fun owed(id: String, person: String, cents: Long, onDay: Int, category: String? = "dining", settled: Boolean = false) =
        Owed(id, person, cents, onDay * day, category, "t-$id", settledBefore = settled)

    private fun paid(id: String, person: String, cents: Long, onDay: Int, difference: Long = 0) =
        Repayment(id, person, cents, difference, onDay * day)

    @Test
    fun `a balance is what they owe minus what they paid, whoever wrote the name`() {
        val balances = Balances.of(
            listOf(owed("a", "Sam", 12_00, 1), owed("b", "Sam Lee", 30_40, 2), owed("c", "Jane", 8_00, 3)),
            listOf(paid("p", "SAM LEE", 20_00, 4)),
        )
        val sam = balances.first()
        assertEquals("Sam Lee", sam.person)
        assertEquals(42_40L, sam.owedCents)
        assertEquals(22_40L, sam.balanceCents)
        assertEquals(listOf("Sam Lee", "Jane"), balances.map { it.person })
    }

    @Test
    fun `payments cover the oldest parts first, so bulk and partial payments just work`() {
        val parts = listOf(owed("a", "Sam", 12_00, 1), owed("b", "Sam", 30_00, 2), owed("c", "Sam", 10_00, 3))
        val partial = Balances.of(parts, listOf(paid("p", "Sam", 20_00, 4))).single()
        assertEquals(listOf("c" to 10_00L, "b" to 22_00L), partial.open.map { it.first.id to it.second })
        assertTrue(partial.isPaid(parts[0]))
        assertFalse(partial.isPaid(parts[1]))

        val bulk = Balances.of(parts, listOf(paid("p", "Sam", 52_00, 4))).single()
        assertEquals(0L, bulk.balanceCents)
        assertEquals(emptyList<Pair<Owed, Long>>(), bulk.open)
    }

    @Test
    fun `paying more than owed leaves credit, and the next purchase uses it`() {
        val over = Balances.of(listOf(owed("a", "Sam", 10_00, 1)), listOf(paid("p", "Sam", 15_00, 2))).single()
        assertEquals(-5_00L, over.balanceCents)
        val later = Balances.of(listOf(owed("a", "Sam", 10_00, 1), owed("b", "Sam", 8_00, 3)), listOf(paid("p", "Sam", 15_00, 2))).single()
        assertEquals(3_00L, later.balanceCents)
        assertEquals(listOf("b" to 3_00L), later.open.map { it.first.id to it.second })
    }

    @Test
    fun `parts marked paid before balances existed stay paid`() {
        val balance = Balances.of(listOf(owed("a", "Sam", 12_00, 1, settled = true), owed("b", "Sam", 5_00, 2)), emptyList()).single()
        assertEquals(5_00L, balance.balanceCents)
        assertTrue(balance.isPaid(balance.items.last()))
        assertEquals(2, balance.items.size)
    }

    @Test
    fun `calling it even settles the whole balance`() {
        val short = Balances.of(listOf(owed("a", "Sam", 68_40, 1)), listOf(paid("p", "Sam", 67_00, 2, difference = 1_40))).single()
        assertEquals(0L, short.balanceCents)
        val over = Balances.of(listOf(owed("a", "Sam", 68_40, 1)), listOf(paid("p", "Sam", 70_00, 2, difference = -1_60))).single()
        assertEquals(0L, over.balanceCents)
    }

    @Test
    fun `within five dollars or ten percent, whichever is larger, it can be called even`() {
        assertEquals(Offer.Exact, Balances.offer(68_40, 68_40))
        assertEquals(Offer.Close(1_40), Balances.offer(68_40, 67_00))
        assertEquals(Offer.Close(-3_50), Balances.offer(68_40, 71_90))
        assertEquals(Offer.Close(5_00), Balances.offer(20_00, 15_00))
        assertEquals(Offer.Partial(5_01), Balances.offer(20_00, 14_99))
        assertEquals(Offer.Close(20_00), Balances.offer(200_00, 180_00))
        assertEquals(Offer.Partial(100_00), Balances.offer(200_00, 100_00))
        assertEquals(Offer.Over(30_00), Balances.offer(20_00, 50_00))
    }

    @Test
    fun `a call-it-even shortfall is spending in the most recent thing they owed for, an overpayment money back`() {
        val balances = Balances.of(
            listOf(owed("a", "Sam", 20_00, 1, "groceries"), owed("b", "Sam", 48_40, 3, "dining"), owed("c", "Sam", 5_00, 40, "coffee")),
            listOf(paid("p", "Sam", 67_00, 4, difference = 1_40), paid("q", "Sam", 6_00, 41, difference = -1_00)),
        )
        val entries = Balances.differenceEntries(balances, ZoneOffset.UTC)
        assertEquals(
            listOf(
                LedgerEntry(-1_00, Kind.EXPENSE, "coffee", LocalDate.of(1970, 2, 11)),
                LedgerEntry(1_40, Kind.EXPENSE, "dining", LocalDate.of(1970, 1, 5)),
            ),
            entries,
        )
        val january = MonthSummary.of(YearMonth.of(1970, 1), entries) { null }
        assertEquals(1_40L, january.spentCents)
        assertEquals(-1_00L, MonthSummary.of(YearMonth.of(1970, 2), entries) { null }.spentCents)
    }

    @Test
    fun `money from a sender finds the person by first or full name`() {
        val balances = Balances.of(listOf(owed("a", "Sam", 12_00, 1), owed("b", "Priya Shah", 31_00, 2)), emptyList())
        assertEquals("Priya Shah", Balances.forSender("PRIYA SHAH", balances)?.person)
        assertEquals("Priya Shah", Balances.forSender("Priya", balances)?.person)
        assertEquals("Sam", Balances.forSender("Sam Smith", balances)?.person)
        assertNull(Balances.forSender("Alex Kim", balances))
        assertNull(Balances.forSender(null, balances))
    }

    @Test
    fun `recent people, one per person, newest first`() {
        assertEquals(listOf("Sam Lee", "Priya"), People.recent(listOf("Sam", "Priya", "Sam Lee", "Jane")))
        assertEquals(listOf("Sam"), People.recent(listOf("Sam"), limit = 3))
    }

    @Test
    fun `you keep the odd cent`() {
        assertEquals(6_49L, shareOf(12_99, 2))
        assertEquals(4_33L, shareOf(12_99, 3))
        assertEquals(3_00L, shareOf(12_00, 4))
    }
}
