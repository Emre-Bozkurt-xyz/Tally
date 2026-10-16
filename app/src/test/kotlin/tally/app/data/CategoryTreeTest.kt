package tally.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTreeTest {
    private fun category(id: String, parentId: String? = null, sortOrder: Int = 0) = CategoryEntity(
        id = id,
        name = id,
        parentId = parentId,
        monthlyBudgetCents = null,
        sortOrder = sortOrder,
        pinned = false,
        updatedAt = 0,
    )

    private val categories = listOf(
        category("health", sortOrder = 2),
        category("coffee", parentId = "food", sortOrder = 1),
        category("food", sortOrder = 0),
        category("groceries", parentId = "food", sortOrder = 0),
        category("transport", sortOrder = 1),
        category("transit", parentId = "transport", sortOrder = 0),
        category("orphan", parentId = "gone"),
    )

    @Test
    fun `tree order is parents by sortOrder, each followed by its children`() {
        assertEquals(
            listOf("food", "groceries", "coffee", "transport", "transit", "health"),
            categories.inTreeOrder().map { it.id },
        )
    }

    @Test
    fun `chips are subcategories plus childless parents`() {
        assertEquals(
            listOf("groceries", "coffee", "transit", "health"),
            categories.chipCategories().map { it.id },
        )
    }

    @Test
    fun `seeded tree offers every subcategory and the childless parents`() {
        val chips = DefaultData.categories().chipCategories().map { it.name }
        assertEquals(
            listOf(
                "Groceries", "Dining out", "Delivery", "Coffee", "Convenience",
                "Online", "In store", "Transit", "Rideshare", "Phone", "Subscriptions",
                "Entertainment", "Health",
            ),
            chips,
        )
    }
}
