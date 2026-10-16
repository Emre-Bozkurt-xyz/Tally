package tally.core.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class AmountsTest {
    @ParameterizedTest(name = "parseCents({0}) = {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "$4.25|425",
            "$1,234.56|123456",
            "$1,234|123400",
            "$5|500",
            "$4.5|450",
            "CAD 4.25|425",
            "C$4.25|425",
            "CA$ 12.00|1200",
            "-$5.00|-500",
            "($5.00)|-500",
            "4,25 $|425",
            "1 234,56 $|123456",
            "1.234,56 $|123456",
            "  $0.99  |99",
            "$1,000,000.00|100000000",
        ],
    )
    fun `parses single amounts`(raw: String, expected: Long) {
        assertEquals(expected, Amounts.parseCents(raw))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "$", "abc", "$1,23,456", "4.25.1", "$12a"])
    fun `rejects non amounts`(raw: String) {
        assertNull(Amounts.parseCents(raw))
    }

    @Test
    fun `parses French narrow no-break space grouping`() {
        assertEquals(123456L, Amounts.parseCents("1 234,56 $"))
    }

    @ParameterizedTest(name = "findFirst({0}) = {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "A purchase of $12.34 was made at TIM HORTONS #1234|1234",
            "Card ending 1234 was charged $5.00|500",
            "Card ending 1234 $5.00 at SHOPPERS|500",
            "Achat de 1 234,56 $ chez METRO|123456",
            "Achat de 4,25 $ chez TIM HORTONS|425",
            "Payment of CAD 1,500.00 received|150000",
            "Refund: -$3.10 from AMAZON|-310",
            "You paid $1,234.56 and then $2.00|123456",
        ],
    )
    fun `finds the first amount in text`(text: String, expected: Long) {
        assertEquals(expected, Amounts.findFirst(text))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Card ending in 1234", "Your statement is ready", "Order #5512 shipped"])
    fun `finds nothing without a currency marker`(text: String) {
        assertNull(Amounts.findFirst(text))
    }
}
