package tally.core.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tally.core.model.CategorySource

class CategorizerTest {
    @Test
    fun `no memory is low confidence without a category`() {
        assertEquals(Suggestion(null, null, Confidence.LOW), Categorizer.categorize(null))
        assertEquals(Suggestion(null, null, Confidence.LOW), Categorizer.categorize(MerchantMemory(null, 0)))
    }

    @Test
    fun `one confirmation suggests, two are trusted`() {
        assertEquals(
            Suggestion("coffee", CategorySource.MEMORY, Confidence.MEDIUM),
            Categorizer.categorize(MerchantMemory("coffee", 1)),
        )
        assertEquals(Confidence.HIGH, Categorizer.categorize(MerchantMemory("coffee", 2)).confidence)
    }

    @Test
    fun `confirming the same category counts up, a different one starts over`() {
        val memory = MerchantMemory(null, 0).confirmed("coffee")
        assertEquals(MerchantMemory("coffee", 1), memory)
        assertEquals(MerchantMemory("coffee", 2), memory.confirmed("coffee"))
        assertEquals(MerchantMemory("groceries", 1), memory.confirmed("coffee").confirmed("groceries"))
    }
}
