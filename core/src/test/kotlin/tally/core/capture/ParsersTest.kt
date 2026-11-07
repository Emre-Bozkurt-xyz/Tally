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
    fun `unknown google wallet text with an amount fails, without one it's ignored`() {
        assertInstanceOf(
            ParseResult.Failed::class.java,
            GoogleWalletParser.parse(wallet("Google Wallet", "Refund of CA\$5.00 is on its way")),
        )
        assertInstanceOf(
            ParseResult.Ignored::class.java,
            GoogleWalletParser.parse(wallet("Google Wallet", "Add your transit card for faster rides")),
        )
        assertInstanceOf(ParseResult.Ignored::class.java, GoogleWalletParser.parse(wallet("HALLOWEEN ON KING ticket", "")))
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
    fun `samsung rewards, blank summaries and text without an amount are ignored, other text fails`() {
        assertInstanceOf(ParseResult.Ignored::class.java, CaptureParsers.parse(samsung("Samsung Wallet", "You earned 8 points. Tap here to check your Samsung Rewards points.")))
        assertInstanceOf(ParseResult.Ignored::class.java, CaptureParsers.parse(samsung(null, null)))
        assertInstanceOf(ParseResult.Failed::class.java, CaptureParsers.parse(samsung("Samsung Wallet", "Refund of CAD 5.00 pending")))
        assertInstanceOf(ParseResult.Ignored::class.java, CaptureParsers.parse(samsung("Samsung Wallet", "Installing…")))
    }

    @Test
    fun `a card name finds its account`() {
        val accounts = listOf("visa" to "RBC Visa", "cheq" to "RBC Chequing", "cash" to "Cash")
        assertEquals("visa", AccountNames.match("RBC Visa Card/Carte Visa RBC‡", accounts))
        assertEquals("visa", AccountNames.match("rbc visa", accounts))
        assertNull(AccountNames.match("Royal Bank Debit/Débit", accounts))
        assertNull(AccountNames.match("‡", accounts))
    }

    private fun text(body: String?) = RawNotification(WatchedPackages.GOOGLE_MESSAGES, "Interac", body, null, now)

    @Test
    fun `an interac text names who sent the money`() {
        val capture = parsed(CaptureParsers.parse(text("INTERAC e-Transfer: \$70.00 (CAD) from Jane Doe has been deposited to your account")))
        assertEquals(-70_00L, capture.amountCents)
        assertEquals("Jane Doe", capture.merchantRaw)
        assertEquals(tally.core.model.Source.INTERAC, capture.source)
        assertEquals(tally.core.model.Kind.INCOME, capture.kind)
        assertEquals(1_250_00L, -parsed(CaptureParsers.parse(text("INTERAC e-Transfer: \$1,250.00 (CAD) from JOHN Q SMITH has been deposited to your account"))).amountCents)
    }

    @Test
    fun `only interac texts are wanted from messaging apps, and nothing else parses`() {
        assertEquals(true, WatchedPackages.wanted(WatchedPackages.SAMSUNG_MESSAGES, "INTERAC e-Transfer: \$5.00 (CAD) from A B has been deposited"))
        assertEquals(false, WatchedPackages.wanted(WatchedPackages.GOOGLE_MESSAGES, "hey are we still on for dinner? I sent \$20"))
        assertEquals(false, WatchedPackages.wanted(WatchedPackages.GOOGLE_MESSAGES, null))
        assertEquals(true, WatchedPackages.wanted(WatchedPackages.RBC, "anything"))
        assertInstanceOf(ParseResult.Ignored::class.java, CaptureParsers.parse(text("dinner at 7?")))
        assertInstanceOf(ParseResult.Failed::class.java, CaptureParsers.parse(text("INTERAC e-Transfer: your transfer is waiting")))
    }
}
