package tally.core.statement

import tally.core.model.Kind
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

enum class StatementKind { CHEQUING, CREDIT_CARD }

/** One row of a statement. Amounts follow the ledger: positive = money out, negative = money in. */
data class StatementLine(
    /** The transaction date (credit card) or posting date (chequing). */
    val date: LocalDate,
    /** As printed, lines joined: "Visa Debit purchase - 3092 DOORDASHWENDYS". */
    val description: String,
    /** The merchant part of [description]: "DOORDASHWENDYS". */
    val merchant: String,
    val amountCents: Long,
    val kind: Kind,
    /** Money to or from a person (e-Transfer): always asked about, whatever the rules say. */
    val person: Boolean = false,
)

data class Statement(
    val kind: StatementKind,
    /** Last 4 of the account (chequing) or card (credit card) number. */
    val last4: String,
    val from: LocalDate,
    val to: LocalDate,
    val lines: List<StatementLine>,
    /** Why the rows don't add up to the statement's own totals; empty when they do. */
    val problems: List<String>,
)

/**
 * Reads RBC PDF statements from their extracted text (PDFBox, sorted by position): the
 * chequing account statement and the credit card statement. Both print totals, which the rows
 * are checked against; a statement that doesn't add up says so in [Statement.problems].
 */
object RbcStatements {
    fun parse(text: String): Statement? = when {
        CHEQUING_PERIOD.containsMatchIn(text) -> chequing(text)
        CARD_PERIOD.containsMatchIn(text) -> creditCard(text)
        else -> null
    }

    // ---------- Credit card ----------

    private val CARD_PERIOD = Regex("STATEMENT FROM ([A-Z]{3}) (\\d{1,2}),? (?:(\\d{4}) )?TO ([A-Z]{3}) (\\d{1,2}), (\\d{4})")
    private val CARD_NUMBER = Regex("\\b\\d{4} \\d{2}\\*\\* \\*{4} (\\d{4})\\b")

    /** "SEP 08 SEP 09 HOSPITALITY-TIM HORTONS HAMILTON ON $5.51 <sidebar text>" */
    private val CARD_ROW = Regex("^([A-Z]{3}) (\\d{2}) [A-Z]{3} \\d{2} (.+?) (-?)\\$(\\d[\\d,]*\\.\\d{2})(?:\\s|$)")
    private val CARD_PURCHASES = Regex("Purchases & debits \\$(\\d[\\d,]*\\.\\d{2})")
    private val CARD_CREDITS = Regex("Payments & credits -?\\$(\\d[\\d,]*\\.\\d{2})")

    private fun creditCard(text: String): Statement {
        val period = CARD_PERIOD.find(text)!!.groupValues
        val endYear = period[6].toInt()
        val endMonth = month(period[4])
        val to = LocalDate.of(endYear, endMonth, period[5].toInt())
        val from = dateBefore(to, month(period[1]), period[2].toInt())
        val last4 = CARD_NUMBER.find(text)?.groupValues?.get(1).orEmpty()

        val lines = text.lineSequence().mapNotNull { CARD_ROW.find(it.trim())?.groupValues }.map { g ->
            // Groups: month, day, description, sign, amount.
            val description = g[3].trim()
            val cents = cents(g[5]) * if (g[4] == "-") -1 else 1
            // Paying the card off is money moving between your accounts; refunds lower spending.
            val kind = if (cents < 0 && PAYMENT.containsMatchIn(description)) Kind.TRANSFER else Kind.EXPENSE
            StatementLine(dateBefore(to, month(g[1]), g[2].toInt()), description, description, cents, kind)
        }.toList()

        val problems = mutableListOf<String>()
        CARD_PURCHASES.find(text)?.let { m ->
            val expected = cents(m.groupValues[1])
            val actual = lines.filter { it.amountCents > 0 }.sumOf { it.amountCents }
            if (expected != actual) problems += "Purchases add up to ${money(actual)}, the statement says ${money(expected)}"
        }
        CARD_CREDITS.find(text)?.let { m ->
            val expected = cents(m.groupValues[1])
            val actual = -lines.filter { it.amountCents < 0 }.sumOf { it.amountCents }
            if (expected != actual) problems += "Payments and credits add up to ${money(actual)}, the statement says ${money(expected)}"
        }
        return Statement(StatementKind.CREDIT_CARD, last4, from, to, lines, problems)
    }

    private val PAYMENT = Regex("(?i)\\bPAYMENT\\b")

    // ---------- Chequing ----------

    private val CHEQUING_PERIOD = Regex("From ([A-Z][a-z]+) (\\d{1,2}), (\\d{4}) to ([A-Z][a-z]+) (\\d{1,2}), (\\d{4})")
    private val ACCOUNT_NUMBER = Regex("account number:\\s*\\d{5}-\\d*(\\d{4})\\b")
    private val OPENING = Regex("^Opening Balance (\\d[\\d,]*\\.\\d{2})$")
    private val CLOSING = Regex("^Closing Balance \\$?(\\d[\\d,]*\\.\\d{2})$")
    private val TABLE_HEADER = Regex("^Date Description Withdrawals")
    private val PAGE_END = Regex("^\\d+ of \\d+$")

    /** A line ending in an amount, maybe a balance after it, maybe a date before it. */
    private val AMOUNT_LINE = Regex("^(?:(\\d{1,2}) ([A-Z][a-z]{2}) )?(.*?)\\s*(\\d{1,3}(?:,\\d{3})*\\.\\d{2})(?:\\s+(\\d{1,3}(?:,\\d{3})*\\.\\d{2}))?$")
    private val DATED_LINE = Regex("^(\\d{1,2}) ([A-Z][a-z]{2}) (.*)$")
    private val TOTAL_DEPOSITS = Regex("Total deposits into your account \\+ ?(\\d[\\d,]*\\.\\d{2})")
    private val TOTAL_WITHDRAWALS = Regex("Total withdrawals from your account - ?(\\d[\\d,]*\\.\\d{2})")

    /** "Visa Debit purchase - 3092 " and friends: the transaction type before the merchant. */
    private val TYPE_PREFIX = Regex(
        "^(?:Visa Debit (?:purchase|correction|refund)|Contactless Interac purchase|Interac purchase|" +
            "e-Transfer - Autodeposit|e-Transfer sent|Interac e-Transfer|Misc Payment|Prov/Local Gvt Payment|" +
            "Fed Gvt Payment|Payroll Deposit|Bill Payment|Online Banking transfer|ATM withdrawal|Deposit)" +
            "(?: - \\d+)?\\s*",
    )
    private val LOOKS_LIKE_DEPOSIT = Regex("(?i)refund|correction|autodeposit|deposit|gvt payment|transfer in")
    private val LOOKS_LIKE_TRANSFER = Regex("(?i)credit card|online banking transfer|transfer to")

    private class Pending(val date: LocalDate, val description: String, val cents: Long)

    private fun chequing(text: String): Statement {
        val period = CHEQUING_PERIOD.find(text)!!.groupValues
        val from = LocalDate.of(period[3].toInt(), fullMonth(period[1]), period[2].toInt())
        val to = LocalDate.of(period[6].toInt(), fullMonth(period[4]), period[5].toInt())
        val last4 = ACCOUNT_NUMBER.find(text)?.groupValues?.get(1).orEmpty()
        val problems = mutableListOf<String>()

        val lines = mutableListOf<StatementLine>()
        var balance: Long? = null
        var closing: Long? = null
        var date = from
        val description = mutableListOf<String>()
        val group = mutableListOf<Pending>()
        var inTable = false

        /** Signs for the rows since the last balance: whichever make the balance add up. */
        fun settle(newBalance: Long) {
            val start = balance
            val signs = if (start == null) null else solveSigns(group.map { it.cents }, newBalance - start, group.map { looksLikeDeposit(it.description) })
            if (signs == null) problems += "Rows before the ${money(newBalance)} balance don't add up; withdrawals and deposits guessed from their names"
            group.forEachIndexed { i, p ->
                val deposit = signs?.get(i) ?: looksLikeDeposit(p.description)
                lines += line(p, deposit)
            }
            group.clear()
            balance = newBalance
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val opening = OPENING.matchEntire(line)
            if (opening != null) {
                balance = cents(opening.groupValues[1])
                inTable = true
                continue
            }
            val closingMatch = CLOSING.matchEntire(line)
            if (closingMatch != null) {
                closing = cents(closingMatch.groupValues[1])
                if (group.isNotEmpty()) settle(closing!!)
                break
            }
            // Page footers and the next page's letterhead sit between "N of M" and the table header.
            if (PAGE_END.matches(line)) {
                inTable = false
                continue
            }
            if (TABLE_HEADER.containsMatchIn(line)) {
                inTable = true
                continue
            }
            if (!inTable) continue

            val amount = AMOUNT_LINE.matchEntire(line)
            if (amount == null) {
                val dated = DATED_LINE.matchEntire(line)
                if (dated != null && isMonth(dated.groupValues[2])) {
                    date = dateBefore(to, month(dated.groupValues[2]), dated.groupValues[1].toInt())
                    description += dated.groupValues[3]
                } else {
                    description += line
                }
                continue
            }
            // Groups: day, month, description, amount, balance.
            val g = amount.groupValues
            if (g[1].isNotEmpty() && isMonth(g[2])) date = dateBefore(to, month(g[2]), g[1].toInt())
            if (g[3].isNotBlank()) description += g[3]
            group += Pending(date, description.joinToString(" ").trim(), cents(g[4]))
            description.clear()
            if (g[5].isNotEmpty()) settle(cents(g[5]))
        }
        if (closing == null) problems += "No closing balance found"

        TOTAL_DEPOSITS.find(text)?.let { m ->
            val expected = cents(m.groupValues[1])
            val actual = -lines.filter { it.amountCents < 0 }.sumOf { it.amountCents }
            if (expected != actual) problems += "Deposits add up to ${money(actual)}, the statement says ${money(expected)}"
        }
        TOTAL_WITHDRAWALS.find(text)?.let { m ->
            val expected = cents(m.groupValues[1])
            val actual = lines.filter { it.amountCents > 0 }.sumOf { it.amountCents }
            if (expected != actual) problems += "Withdrawals add up to ${money(actual)}, the statement says ${money(expected)}"
        }
        return Statement(StatementKind.CHEQUING, last4, from, to, lines, problems.distinct())
    }

    private fun line(p: Pending, deposit: Boolean): StatementLine {
        val person = E_TRANSFER.containsMatchIn(p.description)
        // e-Transfers end in a reference code ("C1AEndddGycC"); the person's name is the rest.
        val merchant = TYPE_PREFIX.replace(p.description, "")
            .let { if (person) TRANSFER_REFERENCE.replace(it, "") else it }
            .ifBlank { p.description }
        val refund = Regex("(?i)refund|correction").containsMatchIn(p.description)
        val kind = when {
            deposit && refund -> Kind.EXPENSE // A refund lowers spending.
            deposit -> Kind.INCOME
            LOOKS_LIKE_TRANSFER.containsMatchIn(p.description) -> Kind.TRANSFER
            else -> Kind.EXPENSE
        }
        return StatementLine(p.date, p.description, merchant, if (deposit) -p.cents else p.cents, kind, person)
    }

    private val E_TRANSFER = Regex("(?i)e-transfer")

    /** A trailing code of 8+ letters and digits, with at least one digit (names have none). */
    private val TRANSFER_REFERENCE = Regex("\\s+(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{8,}$")

    private fun looksLikeDeposit(description: String) = LOOKS_LIKE_DEPOSIT.containsMatchIn(description)

    /**
     * Which of [amounts] are deposits so that deposits minus withdrawals is [net]? Of the
     * answers, the one closest to [hints]. Null if none (or too many rows to try).
     */
    internal fun solveSigns(amounts: List<Long>, net: Long, hints: List<Boolean>): List<Boolean>? {
        if (amounts.size > 16) return null
        var best: List<Boolean>? = null
        var bestDistance = Int.MAX_VALUE
        for (mask in 0 until (1 shl amounts.size)) {
            var sum = 0L
            for (i in amounts.indices) sum += if (mask and (1 shl i) != 0) amounts[i] else -amounts[i]
            if (sum != net) continue
            val signs = amounts.indices.map { mask and (1 shl it) != 0 }
            val distance = signs.indices.count { signs[it] != hints[it] }
            if (distance < bestDistance) {
                best = signs
                bestDistance = distance
            }
        }
        return best
    }

    // ---------- Shared ----------

    private fun cents(amount: String): Long {
        val clean = amount.replace(",", "")
        val (dollars, fraction) = clean.split('.')
        return dollars.toLong() * 100 + fraction.padEnd(2, '0').take(2).toLong()
    }

    private fun money(cents: Long) = "$" + "%d.%02d".format(Locale.ROOT, abs(cents) / 100, abs(cents) % 100)

    private fun isMonth(abbrev: String) = runCatching { month(abbrev) }.isSuccess

    private fun month(abbrev: String): Month = Month.entries.firstOrNull {
        it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).equals(abbrev.take(3), ignoreCase = true)
    } ?: throw IllegalArgumentException("Not a month: $abbrev")

    private fun fullMonth(name: String): Month = Month.valueOf(name.uppercase())

    /** The date with [month] and [day] on or before [end]: statements never run ahead of their end. */
    private fun dateBefore(end: LocalDate, month: Month, day: Int): LocalDate {
        val sameYear = LocalDate.of(end.year, month, day)
        return if (sameYear.isAfter(end)) sameYear.minusYears(1) else sameYear
    }
}
