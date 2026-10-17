package tally.core.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.normalize.Alias

class RuleSuggestionsTest {
    private val history = listOf(
        MerchantHistory("Tim Hortons", listOf("HOSPITALITY-TIM HORTONS HAMILTON ON", "TIM HORTONS #2919"), mapOf("Food/Coffee" to 5), 0),
        MerchantHistory("Doordashwendys", listOf("DOORDASHWENDYS"), mapOf("Food/Delivery" to 2), 1),
        MerchantHistory("Doordashmaglici", listOf("DOORDASHMAGLICI"), emptyMap(), 2),
        MerchantHistory("Shoppers Drug Mart", listOf("SHOPPERS DRUG MART #14"), mapOf("Health" to 3, "Food/Convenience" to 2), 0),
        MerchantHistory("Presto", listOf("PRESTO GOOG/SMF"), mapOf("Transport/Transit" to 4), 0),
    )
    private val categories = setOf("Food/Coffee", "Food/Delivery", "Food/Convenience", "Health", "Transport/Transit")

    @Test
    fun `history suggests rules for merchants always filed the same way and not covered yet`() {
        val suggestions = RuleSuggestionRules.fromHistory(history) { it == "Presto" }
        assertEquals(listOf("Tim Hortons" to "Food/Coffee"), suggestions.map { it.merchant to it.category })
        assertTrue(suggestions.single().why.contains("5 times"))
    }

    @Test
    fun `the reply is read even when wrapped in prose or a code fence`() {
        val reply = "Here you go:\n```json\n{\"aliases\":[{\"match\":\"(?i)doordash\",\"name\":\"DoorDash\",\"why\":\"one merchant\"}],\"rules\":[]}\n```"
        assertEquals(listOf(SuggestedAlias("(?i)doordash", "DoorDash", "one merchant")), RuleSuggestionRules.parse(reply)?.aliases)
        assertNull(RuleSuggestionRules.parse("sorry"))
    }

    @Test
    fun `only usable suggestions survive, each dropped one says why`() {
        val raw = RuleSuggestions(
            aliases = listOf(
                SuggestedAlias("(?i)doordash", " DoorDash "),
                SuggestedAlias("(", "Broken"),
                SuggestedAlias("(?i)uber", "Uber"),
                SuggestedAlias("(?i)shoppers", "Shoppers Drug Mart"),
            ),
            rules = listOf(
                SuggestedRule("DoorDash", "Food/Delivery"),
                SuggestedRule("Presto", "Transport/Transit"),
                SuggestedRule("Uber", "Transport/Rideshare"),
                SuggestedRule("Amazon", "Shopping/Online"),
                SuggestedRule("Danya Ijaz", "Food/Coffee", ask = true),
            ),
        )
        val checked = RuleSuggestionRules.check(raw, history, categories, existingAliases = emptyList()) { name ->
            if (name == "Presto") "Transport/Transit" else null
        }
        assertEquals(listOf(SuggestedAlias("(?i)doordash", "DoorDash")), checked.suggestions.aliases)
        // "DoorDash" only exists once the suggested alias renames the DOORDASH texts.
        assertEquals(listOf("DoorDash"), checked.suggestions.rules.map { it.merchant })
        assertEquals(7, checked.dropped.size)
        assertTrue(checked.dropped.any { "not a valid pattern" in it })
        assertTrue(checked.dropped.any { "matches none of your transactions" in it })
        assertTrue(checked.dropped.any { "changes no names" in it })
        assertTrue(checked.dropped.any { "already file these there" in it })
        assertTrue(checked.dropped.any { "unknown category" in it })
    }

    @Test
    fun `the question lists categories, existing rules and merchants as JSON`() {
        val text = RuleSuggestionRules.user(
            history.take(1), categories.toList(), listOf(Alias(Regex("(?i)tim hortons"), "Tim Hortons")),
            listOf(Rule("r", 0, "Presto", categoryId = "transit")),
        ) { if (it == "transit") "Transport/Transit" else null }
        assertTrue("\"existingRules\":[{\"merchant\":\"Presto\",\"category\":\"Transport/Transit\"}]" in text, text)
        assertTrue("\"bankTexts\":[\"HOSPITALITY-TIM HORTONS HAMILTON ON\"" in text)
    }
}
