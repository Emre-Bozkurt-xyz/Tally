package tally.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MoneyTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "0|$0.00",
            "5|$0.05",
            "425|$4.25",
            "123456|$1,234.56",
            "100000000|$1,000,000.00",
            "-310|-$3.10",
        ],
    )
    fun `formats cents`(cents: Long, expected: String) {
        assertEquals(expected, Money.format(cents))
    }
}
