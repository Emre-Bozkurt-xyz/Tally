package tally.core.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RulesEngineTest {
    private val engine = RulesEngine(
        listOf(
            Rule("uber-cheap", priority = 1, merchantPattern = "Uber", maxAmountCents = 15_00, categoryId = "rideshare"),
            Rule("uber", priority = 2, merchantPattern = "Uber", categoryId = "transport"),
            Rule("tims", priority = 0, merchantPattern = "tim hortons", categoryId = "coffee"),
            Rule("visa-amazon", priority = 3, merchantPattern = "Amazon.*", accountId = "visa", categoryId = "online"),
        ),
    )

    @Test
    fun `whole-name, case-insensitive match`() {
        assertEquals("tims", engine.match("Tim Hortons", 425, null)?.id)
        assertNull(engine.match("Tim Hortons Express", 425, null))
        assertNull(engine.match("Uber Eats", 900, null))
    }

    @Test
    fun `priority order and amount range`() {
        assertEquals("uber-cheap", engine.match("Uber", 15_00, null)?.id)
        assertEquals("uber", engine.match("Uber", 15_01, null)?.id)
    }

    @Test
    fun `account filter`() {
        assertEquals("visa-amazon", engine.match("Amazon.ca", 2000, "visa")?.id)
        assertNull(engine.match("Amazon.ca", 2000, "chequing"))
        assertNull(engine.match(null, 2000, "visa"))
    }

    @Test
    fun `literal patterns escape regex characters`() {
        val pattern = Rule.literal("A&W (Main St.) $5+")
        assertNull(Rule.patternError(pattern))
        assertNotNull(RulesEngine(listOf(Rule("r", 0, pattern, categoryId = "c"))).match("A&W (Main St.) $5+", 1, null))
    }

    @Test
    fun `invalid patterns are reported and skipped`() {
        assertNotNull(Rule.patternError("Tim (Hortons"))
        assertNotNull(Rule.patternError(" "))
        assertNull(RulesEngine(listOf(Rule("bad", 0, "Tim (Hortons", categoryId = "c"))).match("Tim (Hortons", 1, null))
    }

    @Test
    fun `plain names read back from their pattern, real regexes don't`() {
        assertEquals("Sobeys (DoorDash)", Rule.literalName(Rule.literal("Sobeys (DoorDash)")))
        assertEquals("Foodie Fruitie", Rule.literalName("Foodie Fruitie"))
        assertNull(Rule.literalName("Uber.*"))
        assertNull(Rule.literalName("Tim Hortons|Second Cup"))
    }

    @Test
    fun `a rule with no merchant files bare bank alerts by amount, and only those`() {
        val rules = RulesEngine(
            listOf(
                Rule("rent", 0, "", minAmountCents = 750_00, maxAmountCents = 750_00, accountId = "chequing", categoryId = "rent"),
                Rule("presto", 1, "", minAmountCents = 20_00, maxAmountCents = 20_00, categoryId = "transit"),
                Rule("any", 2, ".*", categoryId = "other"),
            ),
        )
        assertEquals("rent", rules.match(null, 750_00, "chequing")?.id)
        assertNull(rules.match(null, 750_00, "visa"))
        assertEquals("presto", rules.match(" ", 20_00, null)?.id)
        // A purchase with a merchant never meets a no-merchant rule, and a pattern never meets a bare alert.
        assertEquals("any", rules.match("Shell", 750_00, "chequing")?.id)
        assertNull(rules.match(null, 12_00, "chequing"))
        // Money in isn't filed by amount.
        assertNull(RulesEngine(listOf(Rule("small", 0, "", maxAmountCents = 50_00, categoryId = "c"))).match(null, -20_00, null))
    }

    @Test
    fun `a rule with no merchant needs an amount`() {
        assertEquals(Rule.NO_MERCHANT_NEEDS_AMOUNT, Rule.patternError("", forRule = true))
        assertNull(Rule.patternError("", forRule = true, minAmountCents = 750_00))
        assertNull(Rule.patternError(" ", forRule = true, maxAmountCents = 20_00))
        assertNotNull(Rule.patternError("", minAmountCents = 750_00)) // Aliases always need a pattern.
        // One in a hand-edited database is skipped rather than catching everything.
        assertNull(RulesEngine(listOf(Rule("all", 0, "", categoryId = "c"))).match(null, 5_00, null))
    }

    @Test
    fun `spellings of one merchant share a key`() {
        assertEquals(Rule.merchantKey("Foodie Fruitie"), Rule.merchantKey("FOODIE-FRUITIE"))
        assertEquals(Rule.merchantKey("Foodie Fruitie"), Rule.merchantKey("foodie  fruitie"))
        assert(Rule.merchantKey("Foodie Fruitie") != Rule.merchantKey("Foodie Fruit"))
    }
}
