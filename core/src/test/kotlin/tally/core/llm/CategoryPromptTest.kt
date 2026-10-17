package tally.core.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CategoryPromptTest {
    private val categories = listOf("Food", "Food/Coffee", "Food/Groceries", "Shopping/Online")

    @Test
    fun `only an offered path counts as an answer`() {
        assertEquals("Food/Coffee", CategoryPrompt.parse("Food/Coffee", categories))
        assertEquals("Food/Coffee", CategoryPrompt.parse("  \"food/coffee\".\n", categories))
        assertNull(CategoryPrompt.parse("UNKNOWN", categories))
        assertNull(CategoryPrompt.parse("unknown.", categories))
        assertNull(CategoryPrompt.parse("Coffee", categories))
        assertNull(CategoryPrompt.parse("Food/Coffee or Food/Groceries", categories))
        assertNull(CategoryPrompt.parse("", categories))
    }

    @Test
    fun `the question lists the merchant, amount and every path`() {
        val text = CategoryPrompt.user(CategorizeRequest("Foodie Fruitie", 34_32, categories))
        assertEquals(
            """
            Merchant: Foodie Fruitie
            Amount: $34.32
            Categories:
            Food
            Food/Coffee
            Food/Groceries
            Shopping/Online
            """.trimIndent(),
            text,
        )
        assert("-$0.05" in CategoryPrompt.user(CategorizeRequest("Refund", -5, categories)))
    }

    @Test
    fun `cache keys ignore case and spacing, and the hash ignores order`() {
        assertEquals(CategoryPrompt.merchantKey("Tim Hortons"), CategoryPrompt.merchantKey("  TIM   hortons "))
        assertEquals(CategoryPrompt.categoriesHash(categories), CategoryPrompt.categoriesHash(categories.reversed()))
        assertNotEquals(CategoryPrompt.categoriesHash(categories), CategoryPrompt.categoriesHash(categories + "Health"))
    }
}
