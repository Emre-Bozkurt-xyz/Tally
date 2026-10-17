package tally.core.statement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.model.Kind
import java.time.LocalDate

class StatementMatcherTest {
    private val from = LocalDate.of(2026, 9, 1)
    private val to = LocalDate.of(2026, 9, 30)
    private fun line(day: Int, merchant: String, cents: Long) =
        StatementLine(LocalDate.of(2026, 9, day), merchant, merchant, cents, Kind.EXPENSE)
    private fun tx(id: String, day: Int, merchant: String?, cents: Long) =
        MatchCandidate(id, cents, LocalDate.of(2026, 9, day), merchant)

    @Test
    fun `same amount close by matches, posting a few days later is fine`() {
        val result = StatementMatcher.match(
            listOf(line(21, "FOODIE FRUITIE", 36_42), line(23, "HOSPITALITY-TIM", 3_87)),
            listOf(tx("a", 19, "Foodie Fruitie", 36_42), tx("b", 23, "Tim Hortons", 3_87)),
            from, to,
        )
        assertEquals(mapOf(0 to "a", 1 to "b"), result.matched.mapValues { it.value.id })
        assertTrue(result.unmatched.isEmpty() && result.missing.isEmpty())
    }

    @Test
    fun `two same-amount purchases go to the right merchants`() {
        val result = StatementMatcher.match(
            listOf(line(25, "PRESTO MOBI/SPT", 20_00), line(25, "DOORDASHWENDYS", 20_00)),
            listOf(tx("wendys", 25, "Wendy's", 20_00), tx("presto", 25, "Presto", 20_00)),
            from, to,
        )
        assertEquals(mapOf(0 to "presto", 1 to "wendys"), result.matched.mapValues { it.value.id })
    }

    @Test
    fun `a tip is matched only when the merchant agrees`() {
        val tipped = listOf(line(10, "QUESADA HAMILTON", 23_00))
        assertEquals("q", StatementMatcher.match(tipped, listOf(tx("q", 10, "Quesada", 19_75)), from, to).matched[0]?.id)
        assertTrue(StatementMatcher.match(tipped, listOf(tx("x", 10, "Subway", 19_75)), from, to).matched.isEmpty())
    }

    @Test
    fun `far dates, refunds against purchases, and leftovers on both sides`() {
        val result = StatementMatcher.match(
            listOf(line(20, "TEMU.COM", 14_35), line(22, "Temu.com", -14_35), line(28, "NEW PLACE", 9_99)),
            listOf(tx("old", 2, "Temu", 14_35), tx("refund", 22, "Temu", -14_35), tx("cash", 15, null, 5_00)),
            from, to,
        )
        assertEquals(mapOf(1 to "refund"), result.matched.mapValues { it.value.id })
        assertEquals(listOf(0, 2), result.unmatched)
        assertEquals(setOf("old", "cash"), result.missing.map { it.id }.toSet())
    }

    @Test
    fun `merchant similarity copes with squashed bank text`() {
        assertTrue(StatementMatcher.similarity("DOORDASHWENDYS", "Wendy's") >= 0.5)
        assertTrue(StatementMatcher.similarity("HOSPITALITY-TIM HORTONS HAMILTON ON", "Tim Hortons") >= 0.5)
        assertEquals(0.0, StatementMatcher.similarity("PRESTO MOBI/SPT", "Wendy's"))
    }
}
