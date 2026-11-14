package tally.core.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.model.CategoryKinds
import tally.core.model.CategorySource
import tally.core.model.Kind

/** Money to and from people: rules as defaults, memory that suggests but never files, paying back. */
class PeopleRulesTest {
    private val rules = RulesEngine(
        listOf(
            Rule("r1", 0, "Priya Shah", categoryId = "groceries", ask = true),
            Rule("r2", 1, "Canada", categoryId = "income-gov"),
        ),
    )
    private val categorizer = Categorizer(rules)

    @Test
    fun `an ask rule suggests, a plain rule files`() {
        val priya = categorizer.categorize("Priya Shah", -31_00, null, null)
        assertEquals(Suggestion("groceries", CategorySource.RULE, Confidence.MEDIUM), priya)
        assertEquals(Confidence.HIGH, categorizer.categorize("Canada", -78_75, null, null).confidence)
    }

    @Test
    fun `memory of a person suggests but never files`() {
        val memory = MerchantMemory("dining", confirmCount = 5)
        assertEquals(Confidence.HIGH, Categorizer().categorize("Sam", -20_00, null, memory).confidence)
        assertEquals(Confidence.MEDIUM, Categorizer().categorize("Sam", -20_00, null, memory, trustMemory = false).confidence)
    }

    @Test
    fun `ask round trips through the rules file`() {
        val document = RulesDocument(
            categories = listOf(CategorySpec("Food", listOf("Groceries")), CategorySpec("Income", listOf("Government"))),
            aliases = emptyList(),
            rules = listOf(
                RuleSpec("Priya Shah", "Food/Groceries", ask = true),
                RuleSpec("Canada", "Income/Government"),
            ),
        )
        val text = RulesToml.export(document)
        assertTrue("ask = true" in text)
        assertEquals(TomlImport.Ok(document), RulesToml.import(text, emptySet()))
        val bad = RulesToml.import(text.replace("ask = true", "ask = \"yes\""), emptySet())
        assertTrue(bad is TomlImport.Invalid && bad.errors.single().message.contains("ask"))
    }

    @Test
    fun `money in is income under Income, money back under a spending category`() {
        assertEquals(Kind.INCOME, CategoryKinds.kindFor(Kind.INCOME, -78_75, incomeCategory = true))
        assertEquals(Kind.EXPENSE, CategoryKinds.kindFor(Kind.INCOME, -31_00, incomeCategory = false))
        assertEquals(Kind.EXPENSE, CategoryKinds.kindFor(Kind.EXPENSE, 4_25, incomeCategory = false))
        assertEquals(Kind.TRANSFER, CategoryKinds.kindFor(Kind.TRANSFER, -387_97, incomeCategory = true))
        assertTrue(CategoryKinds.isIncome("income"))
    }
}
