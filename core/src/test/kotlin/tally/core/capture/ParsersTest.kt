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

    private fun samsung(title: String?, text: String?) = RawNotification(WatchedPackages.SAMSUNG_WALLET, title, text, text, now)

    @Test
    fun `samsung wallet purchase names the merchant and the card`() {
        val capture = parsed(CaptureParsers.parse(samsung("RBC Visa Card/Carte Visa RBC‡", "TIM HORTONS #1234 CAD 1,004.25")))
        assertEquals(1_004_25L, capture.amountCents)
        assertEquals("TIM HORTONS #1234", capture.merchantRaw)
        assertEquals("RBC Visa Card/Carte Visa RBC‡", capture.accountHint?.accountName)
        assertNull(capture.accountHint?.last4)
        assertEquals(4_50L, parsed(CaptureParsers.parse(samsung("Card", "CORNER STORE \$4.50"))).amountCents)
    }

    @Test
    fun `samsung rewards and blank summaries are ignored, other text fails`() {
        assertInstanceOf(ParseResult.Ignored::class.java, CaptureParsers.parse(samsung("Samsung Wallet", "You earned 8 points. Tap here to check your Samsung Rewards points.")))
        assertInstanceOf(ParseResult.Ignored::class.java, CaptureParsers.parse(samsung(null, null)))
        assertInstanceOf(ParseResult.Failed::class.java, CaptureParsers.parse(samsung("Samsung Wallet", "Your card was added.")))
    }

    @Test
    fun `a card name finds its account`() {
        val accounts = listOf("visa" to "RBC Visa", "cheq" to "RBC Chequing", "cash" to "Cash")
        assertEquals("visa", AccountNames.match("RBC Visa Card/Carte Visa RBC‡", accounts))
        assertEquals("visa", AccountNames.match("rbc visa", accounts))
        assertNull(AccountNames.match("Royal Bank Debit/Débit", accounts))
        assertNull(AccountNames.match("‡", accounts))
    }
}
