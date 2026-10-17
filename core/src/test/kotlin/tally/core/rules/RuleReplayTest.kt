package tally.core.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import tally.core.model.CategoryNode
import tally.core.model.CategoryRules
import tally.core.normalize.MerchantIds

class RuleReplayTest {
    private val rules = RulesEngine(
        listOf(
            Rule("r-dd", 0, "DoorDash", categoryId = "delivery"),
            Rule("r-danya", 1, "Danya Ijaz", categoryId = "dining", ask = true),
        ),
    )

    private fun c(id: String, merchant: String?, categoryId: String? = null, captured: Boolean = true, transfer: Boolean = false) =
        ReplayCandidate(id, merchant, 20_00, null, categoryId, captured, transfer)

    @Test
    fun `waiting and uncategorized transactions get filed, yours and transfers don't`() {
        val changes = RuleReplay.plan(
            listOf(
                c("inbox", "DoorDash"),
                c("inbox-suggested", "DoorDash", categoryId = "groceries"),
                c("uncategorized", "DoorDash", captured = false),
                c("yours", "DoorDash", categoryId = "groceries", captured = false),
                c("transfer", "DoorDash", transfer = true),
                c("no-rule", "Presto"),
                c("no-merchant", null),
            ),
            rules,
        )
        assertEquals(
            listOf("inbox", "inbox-suggested", "uncategorized").map { ReplayChange(it, "delivery", confirm = true, ruleId = "r-dd") },
            changes,
        )
    }

    @Test
    fun `ask rules only suggest, and only once`() {
        assertEquals(
            listOf(ReplayChange("t", "dining", confirm = false, ruleId = "r-danya")),
            RuleReplay.plan(listOf(c("t", "Danya Ijaz")), rules),
        )
        assertEquals(emptyList<ReplayChange>(), RuleReplay.plan(listOf(c("t", "Danya Ijaz", categoryId = "dining")), rules))
    }

    @Test
    fun `merchant ids come from the name, whatever its case`() {
        assertEquals(MerchantIds.forName("DoorDash"), MerchantIds.forName("doordash"))
        assertNotEquals(MerchantIds.forName("DoorDash"), MerchantIds.forName("Presto"))
    }

    @Test
    fun `category tree rules`() {
        val all = listOf(CategoryNode("food", "Food", null), CategoryNode("coffee", "Coffee", "food"))
        assertEquals("coffee already exists there", CategoryRules.validate(all, "coffee", "food", null))
        assertEquals("Categories only go two levels deep", CategoryRules.validate(all, "Latte", "coffee", null))
        assertEquals(null, CategoryRules.validate(all, "Groceries", "food", null))
        assertEquals("Move or delete the subcategories of Food first", CategoryRules.deleteError(all, "food"))
        assertEquals(null, CategoryRules.deleteError(all, "coffee"))
    }
}
