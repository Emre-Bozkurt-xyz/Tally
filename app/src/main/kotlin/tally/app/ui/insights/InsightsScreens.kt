package tally.app.ui.insights

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.data.LedgerRepository
import tally.app.ui.components.AmountText
import tally.app.ui.components.LetterAvatar
import tally.app.ui.components.ListRow
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.MonthBarChart
import tally.app.ui.components.PillButton
import tally.app.ui.components.PillStyle
import tally.app.ui.components.RowDivider
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.SectionHeader
import tally.app.ui.components.TallyCard
import tally.app.ui.components.TallyTopBar
import tally.app.ui.components.groupShape
import tally.app.ui.ledger.dayLabel
import tally.app.ui.ledger.observeLedgerEntries
import tally.app.ui.ledger.occurredAtLocal
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import tally.core.model.Money
import tally.core.report.LedgerEntry
import tally.core.report.Recurring
import tally.core.report.Trends
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val MONTH_SHORT = DateTimeFormatter.ofPattern("MMM")
private val MONTH_LONG = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DAY = DateTimeFormatter.ofPattern("MMM d")

/** Every transaction as report entries, splits expanded. Recomputed when anything changes. */
@Composable
private fun rememberEntries(ledger: LedgerRepository): List<LedgerEntry> {
    val entries by remember { ledger.observeLedgerEntries() }.collectAsStateWithLifecycle(initialValue = emptyList())
    return entries
}

@Composable
private fun rememberMerchantNames(ledger: LedgerRepository): Map<String, String> {
    val merchants by remember { ledger.observeAllMerchants() }.collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(merchants) { merchants.associate { it.id to it.name } }
}

@Composable
private fun InsightScaffold(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TallyTopBar(title, onBack) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            content = content,
        )
    }
}

/** The dark card at the top of an insight: a label and a big number. */
private fun LazyListScope.hero(label: String, cents: Long, note: String) {
    item(key = "hero") {
        val colors = Tally.colors
        TallyCard(
            Modifier.padding(horizontal = ScreenPadding).padding(top = 4.dp).fillMaxWidth(),
            color = colors.hero,
            shape = RoundedCornerShape(28.dp),
            padding = PaddingValues(22.dp),
        ) {
            MonoLabel(label, color = colors.onHeroMuted)
            Spacer(Modifier.height(10.dp))
            AmountText(cents, MaterialTheme.typography.displayMedium, color = colors.onHero, centsScale = 0.5f, centsAlpha = 0.45f)
            Spacer(Modifier.height(10.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onHeroMuted)
        }
    }
}

/** Rows drawn as one card. */
private fun <T> LazyListScope.grouped(
    rows: List<T>,
    key: (T) -> Any,
    row: @Composable (T, Modifier) -> Unit,
) {
    itemsIndexed(rows, key = { _, it -> key(it) }) { index, item ->
        Column(Modifier.padding(horizontal = ScreenPadding)) {
            row(item, Modifier.clip(groupShape(index, rows.size)))
            if (index < rows.lastIndex) RowDivider(start = 18.dp)
        }
    }
}

// --- Stats ---

/** The last six months, net income per month, and where the money went lately. */
@Composable
fun StatsScreen(ledger: LedgerRepository, onBack: () -> Unit) {
    val entries = rememberEntries(ledger)
    val merchantNames = rememberMerchantNames(ledger)
    val today = LocalDate.now()
    val months = remember(entries) { Trends.months(entries, YearMonth.from(today), 6) }
    val top = remember(entries) { Trends.topMerchants(entries, today.minusDays(29), today) }

    InsightScaffold("Trends", onBack) {
        item {
            TallyCard(Modifier.padding(horizontal = ScreenPadding).padding(top = 4.dp).fillMaxWidth()) {
                MonoLabel("Spending, last 6 months")
                Spacer(Modifier.height(12.dp))
                MonthBarChart(
                    values = months.map { it.spentCents.coerceAtLeast(0) },
                    labels = months.map { MONTH_SHORT.format(it.month) },
                    details = months.map { "${MONTH_SHORT.format(it.month)} ${Money.format(it.spentCents)}" },
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                )
            }
        }
        item { SectionHeader("Money in and out") }
        grouped(months.reversed(), key = { it.month.toString() }) { totals, modifier ->
            val net = totals.netCents
            ListRow(
                title = MONTH_LONG.format(totals.month),
                subtitle = "Spent ${Money.format(totals.spentCents)} · in ${Money.format(totals.incomeCents)}",
                modifier = modifier,
                trailing = {
                    AmountText(
                        net,
                        MaterialTheme.typography.titleMedium,
                        color = if (net >= 0) Tally.colors.positive else Tally.colors.over,
                        centsScale = 0.85f,
                        text = (if (net >= 0) "+" else "") + Money.format(net),
                    )
                },
            )
        }
        item { SectionHeader("Top merchants, last 30 days") }
        if (top.isEmpty()) {
            item { Text("No merchant purchases yet.", color = Tally.colors.muted, modifier = Modifier.padding(horizontal = ScreenPadding)) }
        }
        grouped(top, key = { it.merchantId }) { merchant, modifier ->
            val name = merchantNames[merchant.merchantId] ?: "Unknown"
            ListRow(
                title = name,
                subtitle = if (merchant.count == 1) "1 purchase" else "${merchant.count} purchases",
                modifier = modifier,
                leading = { LetterAvatar(name, merchant.merchantId, size = 36.dp) },
                trailing = { AmountText(merchant.cents, MaterialTheme.typography.titleMedium, centsScale = 0.85f) },
            )
        }
    }
}

// --- Subscriptions ---

/** Merchants charging about every month or year, with what they cost per year. */
@Composable
fun SubscriptionsScreen(ledger: LedgerRepository, onBack: () -> Unit) {
    val entries = rememberEntries(ledger)
    val merchantNames = rememberMerchantNames(ledger)
    val today = LocalDate.now()
    val found = remember(entries) { Trends.recurring(entries, today) }

    InsightScaffold("Subscriptions", onBack) {
        hero(
            "A year in subscriptions",
            found.sumOf { it.yearlyCostCents },
            "Found automatically: the same merchant at a similar amount about every month or year. Needs at least two charges.",
        )
        if (found.isNotEmpty()) item { SectionHeader("${found.size} found") }
        grouped(found, key = { it.merchantId }) { recurring, modifier ->
            val name = merchantNames[recurring.merchantId] ?: "Unknown"
            val period = if (recurring.period == Recurring.Period.MONTHLY) "monthly" else "yearly"
            ListRow(
                title = name,
                subtitle = "${Money.format(recurring.amountCents)} $period · next around ${DAY.format(recurring.nextExpected)}",
                modifier = modifier,
                leading = { LetterAvatar(name, recurring.merchantId, size = 36.dp) },
                trailing = {
                    AmountText(
                        recurring.yearlyCostCents,
                        MaterialTheme.typography.titleMedium,
                        centsScale = 0.85f,
                        text = "${Money.format(recurring.yearlyCostCents)}/yr",
                    )
                },
            )
        }
    }
}

// --- Owed to me ---

/**
 * Who owes you what: one balance per person (what they owe for, minus what they paid back),
 * the items still open, Settle up for cash or anything Tally didn't see, and their history.
 */
@Composable
fun OwedScreen(ledger: LedgerRepository, onOpenTransaction: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val balances by remember { ledger.observeBalances() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val transactions by remember { ledger.observeTransactions() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val merchantNames = rememberMerchantNames(ledger)
    val byId = remember(transactions) { transactions.associateBy { it.id } }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val today = LocalDate.now()
    val (square, open) = balances.partition { it.balanceCents == 0L }

    fun titleOf(transactionId: String?) = transactionId?.let { byId[it] }?.let { t ->
        t.merchantId?.let { merchantNames[it] } ?: t.merchantRaw
    }

    fun dayOf(epochMillis: Long) = dayLabel(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate(), today)

    InsightScaffold("Owed to me", onBack) {
        hero(
            "Owed to you",
            open.filter { it.balanceCents > 0 }.sumOf { it.balanceCents },
            "Split a purchase with someone: ½ with Sam in the inbox, or Split on a purchase. Money from them settles it in the inbox.",
        )
        (open + square).forEach { balance ->
            val person = balance.person
            val showing = expanded[person] == true
            val history = balance.repayments.isNotEmpty() || balance.items.any { balance.isPaid(it) }
            item(key = "person:$person") {
                SectionHeader(
                    when {
                        balance.balanceCents > 0 -> "$person owes ${Money.format(balance.balanceCents)}"
                        balance.balanceCents < 0 -> "$person · ${Money.format(-balance.balanceCents)} credit"
                        else -> "$person · all square"
                    },
                    action = if (history) (if (showing) "Hide history" else "History") else null,
                    onAction = { expanded[person] = !showing },
                )
            }
            grouped(balance.open, key = { "open:${it.first.id}" }) { (part, left), modifier ->
                ListRow(
                    title = titleOf(part.transactionId) ?: "Purchase",
                    subtitle = listOfNotNull(
                        dayOf(part.occurredAt),
                        if (left < part.amountCents) "${Money.format(left)} of ${Money.format(part.amountCents)} left" else null,
                    ).joinToString(" · "),
                    modifier = modifier,
                    leading = { LetterAvatar(person, person, size = 36.dp) },
                    trailing = { AmountText(left, MaterialTheme.typography.titleMedium, centsScale = 0.85f) },
                    onClick = part.transactionId?.let { id -> { onOpenTransaction(id) } },
                )
            }
            if (balance.balanceCents > 0) {
                item(key = "settle:$person") {
                    PillButton(
                        "Settle up ${Money.format(balance.balanceCents)}",
                        { scope.launch { ledger.settle(person, balance.balanceCents) } },
                        Modifier.padding(horizontal = ScreenPadding).padding(top = 10.dp).fillMaxWidth(),
                        style = PillStyle.Soft,
                        icon = TallyIcons.Check,
                        height = 44.dp,
                    )
                }
            }
            if (showing) {
                val rows: List<Pair<Long, @Composable (Modifier) -> Unit>> =
                    balance.repayments.map { paid ->
                        paid.occurredAt to @Composable { modifier: Modifier ->
                            ListRow(
                                title = "Paid ${Money.format(paid.amountCents)}",
                                subtitle = listOfNotNull(
                                    dayOf(paid.occurredAt),
                                    if (paid.transactionId != null) "e-Transfer" else "Settled up",
                                    when {
                                        paid.differenceCents > 0 -> "called even, ${Money.format(paid.differenceCents)} short"
                                        paid.differenceCents < 0 -> "called even, ${Money.format(-paid.differenceCents)} over"
                                        else -> null
                                    },
                                ).joinToString(" · "),
                                modifier = modifier,
                                trailing = {
                                    PillButton("Remove", { scope.launch { ledger.undoSettlement(paid.id) } }, style = PillStyle.Soft, height = 36.dp)
                                },
                            )
                        }
                    } + balance.items.filter { balance.isPaid(it) }.map { part ->
                        part.occurredAt to @Composable { modifier: Modifier ->
                            ListRow(
                                title = titleOf(part.transactionId) ?: "Purchase",
                                subtitle = "${dayOf(part.occurredAt)} · ${Money.format(part.amountCents)} · paid",
                                modifier = modifier,
                                onClick = part.transactionId?.let { id -> { onOpenTransaction(id) } },
                            )
                        }
                    }
                val sorted = rows.sortedByDescending { it.first }.take(30)
                grouped(sorted.indices.toList(), key = { "history:$person:$it" }) { index, modifier -> sorted[index].second(modifier) }
            }
        }
    }
}
