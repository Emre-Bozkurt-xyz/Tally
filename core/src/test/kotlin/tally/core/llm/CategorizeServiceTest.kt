package tally.core.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class CategorizeServiceTest {
    private val categories = listOf("Food", "Food/Coffee", "Food/Groceries")
    private val asked = mutableListOf<String>()
    private var reply = "Food/Groceries"
    private val model = CategoryModel { _, user -> asked += user.lineSequence().first(); reply }
    private val service = CategorizeService(InMemoryCategoryCache(), model, dailyLimit = 3)

    private fun ask(merchant: String, categories: List<String> = this.categories) =
        service.categorize(CategorizeRequest(merchant, 34_32, categories))

    @Test
    fun `each merchant is asked once`() {
        assertEquals(CategorizeResponse("Food/Groceries", cached = false), ask("Foodie Fruitie"))
        assertEquals(CategorizeResponse("Food/Groceries", cached = true), ask("FOODIE  fruitie"))
        assertEquals(listOf("Merchant: Foodie Fruitie"), asked)
    }

    @Test
    fun `a cached answer that's no longer a category is asked again`() {
        ask("Foodie Fruitie")
        reply = "Food"
        assertEquals("Food", ask("Foodie Fruitie", listOf("Food", "Food/Coffee")).category)
        assertEquals(2, asked.size)
    }

    @Test
    fun `don't know is remembered until the categories change`() {
        reply = "UNKNOWN"
        assertEquals(CategorizeResponse(null, cached = false), ask("Xyz Holdings"))
        assertEquals(CategorizeResponse(null, cached = true), ask("Xyz Holdings"))
        reply = "Health"
        assertEquals("Health", ask("Xyz Holdings", categories + "Health").category)
        assertEquals(2, asked.size)
    }

    @Test
    fun `an answer outside the list counts as don't know`() {
        reply = "Coffee shops"
        assertEquals(null, ask("Café X").category)
    }

    @Test
    fun `the daily cap stops model calls but not cache hits`() {
        ask("A"); ask("B"); ask("C")
        assertThrows<DailyLimitReached> { ask("D") }
        assertEquals(true, ask("A").cached)
    }

    @Test
    fun `the cap resets each day`() {
        var now = Instant.parse("2026-10-17T23:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val capped = CategorizeService(InMemoryCategoryCache(), model, dailyLimit = 1, clock = clock)
        capped.categorize(CategorizeRequest("A", 1, categories))
        assertThrows<DailyLimitReached> { capped.categorize(CategorizeRequest("B", 1, categories)) }
        now += Duration.ofHours(2)
        assertEquals(false, capped.categorize(CategorizeRequest("B", 1, categories)).cached)
    }
}
