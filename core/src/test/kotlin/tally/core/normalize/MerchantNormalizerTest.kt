package tally.core.normalize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MerchantNormalizerTest {
    private val normalizer = MerchantNormalizer()

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            // Real, from the first fixture.
            "FOODIE FRUITIE|Foodie Fruitie",
            "TIM HORTONS #1234|Tim Hortons",
            "TIM HORTONS #1234 HAMILTON ON|Tim Hortons",
            "SQ *SECOND CUP|Second Cup",
            "TST* THE BURGER PRIEST|The Burger Priest",
            "PAYPAL *STEAM GAMES|Steam Games",
            "SHOPPERS DRUG MART 01234|Shoppers Drug Mart",
            "  STARBUCKS   COFFEE  |Starbucks Coffee",
            "Amazon.ca|Amazon.ca",
            "uber eats|Uber Eats",
            "7-ELEVEN|7-Eleven",
        ],
    )
    fun `generic cleanup`(raw: String, expected: String) {
        assertEquals(expected, normalizer.normalize(raw))
    }

    @Test
    fun `first matching alias wins over cleanup`() {
        val withAliases = MerchantNormalizer(
            listOf(
                Alias(Regex("(?i)^tim hortons"), "Tims"),
                Alias(Regex("(?i)tim"), "Never reached"),
            ),
        )
        assertEquals("Tims", withAliases.normalize("TIM HORTONS #1234"))
        assertEquals("Second Cup", withAliases.normalize("SQ *SECOND CUP"))
    }

    @Test
    fun `never returns empty`() {
        assertEquals("#1234", normalizer.normalize("#1234"))
    }
}
