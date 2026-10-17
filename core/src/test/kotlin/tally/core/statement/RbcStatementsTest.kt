package tally.core.statement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tally.core.model.Kind
import java.time.LocalDate

/**
 * Made-up statements in the layout PDFBox extracts from real RBC PDFs (sorted by position):
 * same headers, page breaks, sidebar text and row shapes, invented names and amounts.
 */
class RbcStatementsTest {
    private val chequing = """
        Royal Bank of Canada
        JANE DOE Your account number: 01234-5551234
        From December 3, 2025 to January 2, 2026
        Summary of your account for this period
        Your opening balance on December 3, 2025 ${'$'}1,000.00
        Total deposits into your account + 1,587.80
        Total withdrawals from your account - 1,343.30
        Your closing balance on January 2, 2026 = ${'$'}1,244.50
        Details of your account activity
        Date Description Withdrawals (${'$'}) Deposits (${'$'}) Balance (${'$'})
        Opening Balance 1,000.00
        8 Dec Visa Debit purchase - 3092
        DOORDASHWENDYS 16.50
        Visa Debit purchase - 1342
        SP SOME SHOP 33.50 950.00
        9 Dec Misc Payment RBC CREDIT CARD 200.00
        Prov/Local Gvt Payment CANADA 75.00 825.00
        14 Dec e-Transfer - Autodeposit SAM SMITH
        C1AEabcdefgh 50.00 875.00
        Contactless Interac purchase - 9126
        HOSPITALITY-TIM 4.00
        1 of 2
        RBPDA10020_0000000_100 - 0000000 HRI - 00 - 1 - 0000 - 17 - - 11333
        Your RBC personal banking account statement
        From December 3, 2025 to January 2, 2026
        Details of your account activity - continued
        Date Description Withdrawals (${'$'}) Deposits (${'$'}) Balance (${'$'})
        Visa Debit purchase - 4691 TEMU.COM 21.00 850.00
        31 Dec Payroll Deposit ACME CORP 1,400.00 2,250.00
        2 Jan Visa Debit correction - 1886
        DOORDASHSOBEYS 41.80
        Visa Debit purchase - 1886
        DOORDASHSOBEYS 41.80
        Misc Payment ACME PROPERTIES 1,000.00
        Visa Debit refund - 4276 Temu.com 21.00
        Visa Debit purchase - 9999
        SOME PLACE 26.50 1,244.50
        Closing Balance ${'$'}1,244.50
        2 of 2
    """.trimIndent()

    private val card = """
        RBC® ION+TM Visa‡
        JANE DOE  4510 15** **** 4242
        STATEMENT FROM DEC 19 TO JAN 18, 2026
        1 OF 2
        PREVIOUS ACCOUNT BALANCE ${'$'}100.00 IMPORTANT INFORMATION
        TRANSACTION POSTING
        ACTIVITY DESCRIPTION AMOUNT (${'$'}) Bonus points earned this statement 276
        DATE DATE New points balance 35,802
        DEC 23 DEC 24 PHONECO ******1234 888-555-0100 ON ${'$'}56.50
        74537886235402390109829 CONTACT US
        DEC 27 DEC 27 OPENAI *CHATGPT SUBSCR OPENAI.COM CA ${'$'}28.25 Customer Service / Lost & Stolen 1-800-769-2512
        JAN 01 JAN 02 DIGITALOCEAN.COM SAINT JOHN NB ${'$'}9.67 Rewards Website avionrewards.com
        74106246244100170230032
        Foreign Currency  - USD   6.78  Exchange rate - 1.426253 PAYMENTS & INTEREST RATES
        JAN 08 JAN 08 AUTOMATIC PAYMENT -THANK YOU -${'$'}100.00 Fees ${'$'}0.00
        JAN 08 JAN 09 HOSPITALITY-TIM HORTONS HAMILTON ON ${'$'}5.51 Payments & credits -${'$'}103.00
        74064496251820149107588 Purchases & debits ${'$'}99.93
        JAN 10 JAN 11 SOME STORE HAMILTON ON -${'$'}3.00
        TOTAL ACCOUNT BALANCE ${'$'}96.93
    """.trimIndent()

    @Test
    fun `chequing rows, signs from the running balance, and totals that check out`() {
        val s = RbcStatements.parse(chequing)!!
        assertEquals(StatementKind.CHEQUING, s.kind)
        assertEquals("1234", s.last4)
        assertEquals(LocalDate.of(2025, 12, 3) to LocalDate.of(2026, 1, 2), s.from to s.to)
        assertEquals(emptyList<String>(), s.problems)
        assertEquals(13, s.lines.size)

        val first = s.lines[0]
        assertEquals(LocalDate.of(2025, 12, 8), first.date)
        assertEquals("Visa Debit purchase - 3092 DOORDASHWENDYS", first.description)
        assertEquals("DOORDASHWENDYS", first.merchant)
        assertEquals(16_50L to Kind.EXPENSE, first.amountCents to first.kind)

        fun line(merchant: String) = s.lines.first { it.merchant.startsWith(merchant) }
        assertEquals(200_00L to Kind.TRANSFER, line("RBC CREDIT CARD").let { it.amountCents to it.kind })
        assertEquals(-75_00L to Kind.INCOME, line("CANADA").let { it.amountCents to it.kind })
        assertEquals(-50_00L to Kind.INCOME, line("SAM SMITH").let { it.amountCents to it.kind })
        // An e-Transfer is from a person, named without its reference code.
        assertEquals("SAM SMITH" to true, line("SAM SMITH").let { it.merchant to it.person })
        assertEquals(false, first.person)
        assertEquals(-1_400_00L to Kind.INCOME, line("ACME").let { it.amountCents to it.kind })
        assertEquals(1_000_00L to Kind.EXPENSE, line("ACME PROPERTIES").let { it.amountCents to it.kind })
        // A row across the page break keeps its date; a refund is a negative expense.
        assertEquals(LocalDate.of(2025, 12, 14), line("TEMU.COM").date)
        assertEquals(-21_00L to Kind.EXPENSE, line("Temu.com").let { it.amountCents to it.kind })
        // The correction and the repeat purchase: same text, opposite signs, January.
        val sobeys = s.lines.filter { it.merchant == "DOORDASHSOBEYS" }
        assertEquals(listOf(-41_80L, 41_80L), sobeys.map { it.amountCents })
        assertTrue(sobeys.all { it.date == LocalDate.of(2026, 1, 2) })
    }

    @Test
    fun `a chequing statement that doesn't add up says so`() {
        val broken = chequing.replace("SP SOME SHOP 33.50 950.00", "SP SOME SHOP 33.00 950.00")
        assertTrue(RbcStatements.parse(broken)!!.problems.isNotEmpty())
    }

    @Test
    fun `credit card rows across the new year, payments are transfers`() {
        val s = RbcStatements.parse(card)!!
        assertEquals(StatementKind.CREDIT_CARD, s.kind)
        assertEquals("4242", s.last4)
        assertEquals(LocalDate.of(2025, 12, 19) to LocalDate.of(2026, 1, 18), s.from to s.to)
        assertEquals(emptyList<String>(), s.problems)
        assertEquals(
            listOf(
                Triple(LocalDate.of(2025, 12, 23), "PHONECO ******1234 888-555-0100 ON", 56_50L),
                Triple(LocalDate.of(2025, 12, 27), "OPENAI *CHATGPT SUBSCR OPENAI.COM CA", 28_25L),
                Triple(LocalDate.of(2026, 1, 1), "DIGITALOCEAN.COM SAINT JOHN NB", 9_67L),
                Triple(LocalDate.of(2026, 1, 8), "AUTOMATIC PAYMENT -THANK YOU", -100_00L),
                Triple(LocalDate.of(2026, 1, 8), "HOSPITALITY-TIM HORTONS HAMILTON ON", 5_51L),
                Triple(LocalDate.of(2026, 1, 10), "SOME STORE HAMILTON ON", -3_00L),
            ),
            s.lines.map { Triple(it.date, it.description, it.amountCents) },
        )
        assertEquals(Kind.TRANSFER, s.lines[3].kind)
        assertEquals(Kind.EXPENSE, s.lines[5].kind)
    }

    @Test
    fun `other text isn't a statement`() {
        assertNull(RbcStatements.parse("Hello\nworld"))
    }

    @Test
    fun `sign solving prefers the hinted answer`() {
        // +10 -10 and -10 +10 both net 0; the hint picks.
        assertEquals(listOf(true, false), RbcStatements.solveSigns(listOf(1000, 1000), 0, listOf(true, false)))
        assertEquals(listOf(false, true), RbcStatements.solveSigns(listOf(1000, 1000), 0, listOf(false, true)))
        assertNull(RbcStatements.solveSigns(listOf(1000), 500, listOf(false)))
    }
}
