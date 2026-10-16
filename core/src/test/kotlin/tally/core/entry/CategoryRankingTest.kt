package tally.core.entry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CategoryRankingTest {
    private val zone = ZoneId.of("America/Toronto")
    private val tree = listOf("coffee", "groceries", "dining", "transit", "online")

    private fun at(hour: Int, day: Int = 1): ZonedDateTime =
        ZonedDateTime.of(LocalDate.of(2026, 10, day), LocalTime.of(hour, 0), zone)

    private fun uses(categoryId: String, hour: Int, times: Int) =
        List(times) { CategoryUse(categoryId, at(hour, day = it + 1)) }

    @ParameterizedTest
    @CsvSource("5,MORNING", "11,MORNING", "12,AFTERNOON", "16,AFTERNOON", "17,EVENING", "21,EVENING", "22,NIGHT", "0,NIGHT", "4,NIGHT")
    fun `buckets hours`(hour: Int, expected: TimeOfDay) {
        assertEquals(expected, TimeOfDay.of(hour))
    }

    @Test
    fun `no history keeps tree order`() {
        assertEquals(tree, CategoryRanking.rank(tree, emptySet(), emptyList(), at(9)))
    }

    @Test
    fun `pinned come first in tree order regardless of use`() {
        val ranked = CategoryRanking.rank(
            treeOrder = tree,
            pinned = setOf("dining", "coffee"),
            uses = uses("online", hour = 9, times = 10),
            now = at(9),
        )
        assertEquals(listOf("coffee", "dining", "online", "groceries", "transit"), ranked)
    }

    @Test
    fun `current time of day outranks overall use`() {
        val history = uses("transit", hour = 8, times = 3) + uses("dining", hour = 19, times = 5)
        assertEquals("transit", CategoryRanking.rank(tree, emptySet(), history, at(9)).first())
        assertEquals("dining", CategoryRanking.rank(tree, emptySet(), history, at(20)).first())
    }

    @Test
    fun `overall use breaks ties within the bucket`() {
        // Neither used in the afternoon; groceries used more overall.
        val history = uses("transit", hour = 8, times = 1) + uses("groceries", hour = 19, times = 2)
        val ranked = CategoryRanking.rank(tree, emptySet(), history, at(14))
        assertEquals(listOf("groceries", "transit", "coffee", "dining", "online"), ranked)
    }
}
