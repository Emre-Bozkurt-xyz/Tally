package tally.core.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tally.core.model.CategorySource

class CategorizerTest {
    private val memoryOnly = Categorizer()

    private fun categorize(categorizer: Categorizer, memory: MerchantMemory?, merchant: String = "Tim Hortons", cents: Long = 425) =
        categorizer.categorize(merchant, cents, accountId = null, memory = memory)

    @Test
    fun `no memory is low confidence without a category`() {
        assertEquals(Suggestion(null, null, Confidence.LOW), categorize(memoryOnly, null))
        assertEquals(Suggestion(null, null, Confidence.LOW), categorize(memoryOnly, MerchantMemory(null, 0)))
    }

    @Test
    fun `one confirmation suggests, two are trusted`() {
        assertEquals(
            Suggestion("coffee", CategorySource.MEMORY, Confidence.MEDIUM),
            categorize(memoryOnly, MerchantMemory("coffee", 1)),
        )
        assertEquals(Confidence.HIGH, categorize(memoryOnly, MerchantMemory("coffee", 2)).confidence)
    }

    @Test
    fun `rules beat memory and are trusted`() {
        val categorizer = Categorizer(
            RulesEngine(listOf(Rule("r", 0, "Tim Hortons", categoryId = "coffee", tags = listOf("caffeine")))),
        )
        assertEquals(
            Suggestion("coffee", CategorySource.RULE, Confidence.HIGH, listOf("caffeine")),
            categorize(categorizer, MerchantMemory("groceries", 5)),
        )
        // No rule match: memory as before.
        assertEquals("groceries", categorize(categorizer, MerchantMemory("groceries", 5), merchant = "Metro").categoryId)
    }

    @Test
    fun `confirming the same category counts up, a different one starts over`() {
        val memory = MerchantMemory(null, 0).confirmed("coffee")
        assertEquals(MerchantMemory("coffee", 1), memory)
        assertEquals(MerchantMemory("coffee", 2), memory.confirmed("coffee"))
        assertEquals(MerchantMemory("groceries", 1), memory.confirmed("coffee").confirmed("groceries"))
    }
}
