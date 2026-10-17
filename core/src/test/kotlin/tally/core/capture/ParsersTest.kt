package tally.core.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/** Variations on the real shapes in the fixtures. */
class ParsersTest {
    private val now = Instant.parse("2026-10-17T01:04:49Z")

    private fun rbc(text: String?) = RawNotification(WatchedPackages.RBC, "RBC Mobile", text, text, now)

    private fun wallet(title: String?, text: String?) =
        RawNotification(WatchedPackages.GOOGLE_WALLET, title, text, null, now)

    private fun parsed(result: ParseResult?): Capture = (result as ParseResult.Parsed).capture

    @Test
    fun `rbc withdrawal with thousands separator`() {
        val capture = parsed(
            RbcParser.parse(rbc("A withdrawal of \$1,234.56 was made from your RBC account ********8805 on 10/17.")),
        )
        assertEquals(1_234_56L, capture.amountCents)
        assertEquals("8805", capture.accountHint?.last4)
        assertNull(capture.merchantRaw)
        assertEquals(now, capture.occurredAt)
    }

    @Test
    fun `unknown rbc text fails so it reaches the inbox`() {
        assertInstanceOf(ParseResult.Failed::class.java, RbcParser.parse(rbc("Your statement is ready.")))
        assertInstanceOf(ParseResult.Failed::class.java, RbcParser.parse(rbc(null)))
    }

    @Test
    fun `google wallet purchase with a credit card and a larger amount`() {
        val capture = parsed(GoogleWalletParser.parse(wallet("TIM HORTONS #1234", "CA\$1,004.25 with RBC Visa ••1111")))
        assertEquals(1_004_25L, capture.amountCents)
        assertEquals("TIM HORTONS #1234", capture.merchantRaw)
        assertEquals("1111", capture.accountHint?.last4)
        assertEquals("RBC Visa", capture.accountHint?.accountName)
    }

    @Test
    fun `unknown google wallet text fails`() {
        assertInstanceOf(
            ParseResult.Failed::class.java,
            GoogleWalletParser.parse(wallet("Google Wallet", "Add your transit card for faster rides")),
        )
    }

    @Test
    fun `dispatch ignores unwatched packages and fails watched ones without a parser`() {
        assertNull(CaptureParsers.parse(RawNotification("com.discord", "x", "\$5.00", null, now)))
        assertInstanceOf(
            ParseResult.Failed::class.java,
            CaptureParsers.parse(RawNotification(WatchedPackages.SAMSUNG_WALLET, "x", "\$5.00", null, now)),
        )
    }
}
