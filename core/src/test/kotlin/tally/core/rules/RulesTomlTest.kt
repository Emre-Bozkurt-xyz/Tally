package tally.core.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RulesTomlTest {
    private val accounts = setOf("RBC Visa", "RBC Chequing", "Cash")

    private val document = RulesDocument(
        categories = listOf(
            CategorySpec("Food", listOf("Groceries", "Dining out", "Coffee")),
            CategorySpec("Transport", listOf("Transit", "Rideshare")),
            CategorySpec("Health", emptyList()),
        ),
        aliases = listOf(AliasSpec("(?i)^tim hortons\\b.*", "Tim Hortons"), AliasSpec("(?i)amzn mktp", "Amazon")),
        rules = listOf(
            RuleSpec("Tim Hortons", "Food/Coffee"),
            RuleSpec("Uber", "Transport/Rideshare", maxAmountCents = 15_00, account = "RBC Visa", tags = listOf("rideshare")),
            RuleSpec("A&W \\(Main\\)", "Food/Dining out", minAmountCents = 5, maxAmountCents = 120_50),
            RuleSpec("Shoppers.*", "Health"),
        ),
    )

    private fun import(text: String) = RulesToml.import(text, accounts)

    private fun errors(text: String) = (import(text) as TomlImport.Invalid).errors

    @Test
    fun `round trips`() {
        val text = RulesToml.export(document)
        val imported = (import(text) as TomlImport.Ok).document
        assertEquals(document, imported)
        assertEquals(text, RulesToml.export(imported))
    }

    @Test
    fun `regexes are written as readable literal strings`() {
        val text = RulesToml.export(document)
        assert("match = '(?i)^tim hortons\\b.*'" in text) { text }
        assert("merchant = 'A&W \\(Main\\)'" in text) { text }
    }

    @Test
    fun `hand-written file with quoted keys, comments, multi-line lists and whole-dollar amounts`() {
        val text = """
            # mine
            [categories]
            "Eating out" = [
              "Coffee",   # morning
              "Lunch",
            ]
            Bills = []

            [[rule]]
            merchant = "Starbucks"   # the usual
            category = "Eating out/Coffee"
            max_amount = 20
        """.trimIndent()
        val doc = (import(text) as TomlImport.Ok).document
        assertEquals(listOf(CategorySpec("Eating out", listOf("Coffee", "Lunch")), CategorySpec("Bills", emptyList())), doc.categories)
        assertEquals(RuleSpec("Starbucks", "Eating out/Coffee", maxAmountCents = 20_00), doc.rules.single())
    }

    @Test
    fun `semantic errors carry line numbers and are all reported`() {
        val text = """
            [categories]
            Food = ["Coffee"]

            [[alias]]
            match = "(unclosed"
            name = "X"

            [[alias]]
            match = "a"
            name = "A"

            [[alias]]
            match = "a"
            name = "Again"

            [[rule]]
            merchant = "Tims"
            category = "Food/Tea"
            max_amount = -3.00
            account = "Amex"
        """.trimIndent()
        assertEquals(
            listOf(
                TomlError(5, "Invalid pattern"),
                TomlError(12, "Duplicate alias"),
                TomlError(18, "Unknown category \"Food/Tea\""),
                TomlError(19, "max_amount must be a non-negative amount"),
                TomlError(20, "Unknown account \"Amex\""),
            ),
            errors(text).map { TomlError(it.line, it.message.take(expectedPrefixLength(it.message))) },
        )
    }

    /** Compares only the start of each message; the tail includes regex engine details. */
    private fun expectedPrefixLength(message: String) = when {
        message.startsWith("Invalid pattern") -> "Invalid pattern".length
        message.startsWith("Duplicate alias") -> "Duplicate alias".length
        message.startsWith("max_amount") -> "max_amount must be a non-negative amount".length
        message.startsWith("Unknown account") -> "Unknown account \"Amex\"".length
        else -> message.length
    }

    @Test
    fun `structural errors`() {
        assertEquals(listOf(TomlError(1, "Missing [categories] section")), errors(""))
        assertEquals(3, errors("[categories]\nFood = []\n[[rules]]").single().line)
        assert(TomlError(5, "Unknown key colour") in errors("[categories]\n[[rule]]\nmerchant = \"x\"\ncategory = \"x\"\ncolour = \"red\""))
        assertEquals(2, errors("[categories]\nFood = [\"Coffee\", \"coffee\"]").single().line)
    }

    @Test
    fun `syntax errors stop at the first one with its line`() {
        assertEquals(listOf(TomlError(2, "Unterminated string")), errors("[categories]\nFood = [\"Coffee]"))
        assertEquals(2, errors("[categories]\nFood = [\"a\"] trailing").single().line)
        assertInstanceOf(TomlImport.Invalid::class.java, import("[categories]\nFood = \"\\d\""))
    }
}
