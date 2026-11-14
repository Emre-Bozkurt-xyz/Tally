package tally.core.email

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.llm.CategoryModel
import java.time.LocalDate

class ReceiptsTest {
    @Test
    fun `order confirmations and receipts are candidates, shipping and marketing are not`() {
        listOf(
            "Thank you for shopping with us",
            "Your Walmart.ca order confirmation",
            "Order confirmed: #200012345",
            "Your receipt from Apple",
            "Your Uber Eats order with Pizza Place",
            "Thanks for your order!",
        ).forEach { assertTrue(ReceiptFilter.isCandidate(it), it) }
        listOf(
            "Your order has shipped",
            "Your order is out for delivery",
            "Your delivery is on its way",
            "How did we do? Review your recent order",
            "Your order was cancelled",
            "Your refund has been processed",
            "40% off everything this weekend",
            "Your statement is ready",
            "Sam sent you a message",
        ).forEach { assertFalse(ReceiptFilter.isCandidate(it), it) }
    }

    @Test
    fun `html email becomes plain text`() {
        val html = """
            <html><head><style>.x{color:red}</style></head><body>
            <table><tr><td>Bananas</td><td>&#36;1.29</td></tr><tr><td>Milk&nbsp;2L</td><td>$5.49</td></tr></table>
            <p>Total &amp; tax: <b>${'$'}6.78</b></p><!-- tracking --><script>x()</script>
            </body></html>
        """.trimIndent()
        assertEquals("Bananas \$1.29\nMilk 2L \$5.49\nTotal & tax: \$6.78", HtmlText.plain(html))
    }

    private fun reader(answer: String) = ReceiptReader(CategoryModel { _, _ -> answer })

    private val email = MailMessage("m1", "Walmart <help@walmart.ca>", "Thank you for shopping with us", 0) { "Order total $64.12" }

    @Test
    fun `a receipt answer becomes a receipt`() {
        val receipt = reader(
            """{"is_new_charge": true, "store": "Walmart", "total": 64.12, "currency": "CAD", "date": "2026-11-03",
               "card_brand": "Visa", "card_last4": "0800", "order_number": "200012345",
               "items": ["Bananas", "Milk 2L", "", "Bread", "Eggs", "Rice", "Beans"]}""",
        ).read(email)!!
        assertEquals(6412L, receipt.totalCents)
        assertEquals(LocalDate.of(2026, 11, 3), receipt.date)
        assertEquals("0800", receipt.last4)
        assertEquals("walmart:200012345", receipt.orderKey)
        assertEquals(listOf("Bananas", "Milk 2L", "Bread", "Eggs", "Rice"), receipt.items)
    }

    @Test
    fun `answers that fail the checks are dropped`() {
        val good = """"store": "Walmart", "total": 64.12, "currency": "CAD", "date": "", "card_brand": "", "card_last4": "", "order_number": "", "items": []"""
        assertNull(reader("""{"is_new_charge": false, $good}""").read(email))
        assertNull(reader("""{"is_new_charge": true, ${good.replace("CAD", "USD")}}""").read(email))
        assertNull(reader("""{"is_new_charge": true, ${good.replace("64.12", "0")}}""").read(email))
        assertNull(reader("""{"is_new_charge": true, ${good.replace("\"Walmart\"", "\"\"")}}""").read(email))
        assertNull(reader("I can't tell").read(email))
        val noDetails = reader("""{"is_new_charge": true, $good}""").read(email)!!
        assertNull(noDetails.orderKey)
        assertNull(noDetails.date)
        assertNull(noDetails.cardBrand)
    }

    @Test
    fun `the email is read only for candidates the reader is given, and long ones are cut`() {
        var asked = ""
        val long = MailMessage("m2", "a@b.c", "Your receipt", 0) { "x".repeat(ReceiptReader.MAX_CHARS + 50) }
        ReceiptReader { _, user -> asked = user; "{}" }.read(long)
        assertTrue(asked.endsWith("[...]"))
        assertTrue(asked.length < ReceiptReader.MAX_CHARS + 200)
    }
}
