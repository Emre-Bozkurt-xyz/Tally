package tally.core.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.model.CategoryKinds
import tally.core.model.CategorySource
import tally.core.model.Kind
import tally.core.report.OwedMatch
import tally.core.report.OwedPart

/** Money to and from people: rules as defaults, memory that suggests but never files, paying back. */
class PeopleRulesTest {
    private val rules = RulesEngine(
        listOf(
            Rule("r1", 0, "Danya Ijaz", categoryId = "groceries", ask = true),
            Rule("r2", 1, "Canada", categoryId = "income-gov"),
        ),
    )
    private val categorizer = Categorizer(rules)

    @Test
    fun `an ask rule suggests, a plain rule files`() {
        val danya = categorizer.categorize("Danya Ijaz", -31_00, null, null)
        assertEquals(Suggestion("groceries", CategorySource.RULE, Confidence.MEDIUM), danya)
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
                RuleSpec("Danya Ijaz", "Food/Groceries", ask = true),
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

    @Test
    fun `a payment from a person settles the open part with that amount and name`() {
        val open = listOf(
            OwedPart("s1", "Sam", 12_00),
            OwedPart("s2", "Danya", 31_00),
            OwedPart("s3", "Danya", 31_00),
        )
        assertEquals("s2", OwedMatch.find("DANYA IJAZ", 31_00, open)?.splitId)
        assertEquals("s1", OwedMatch.find("Sam Smith", 12_00, open)?.splitId)
        assertNull(OwedMatch.find("Danya Ijaz", 30_00, open))
        assertNull(OwedMatch.find("Raiena Haque", 12_00, open))
        assertNull(OwedMatch.find(null, 12_00, open))
    }
}
