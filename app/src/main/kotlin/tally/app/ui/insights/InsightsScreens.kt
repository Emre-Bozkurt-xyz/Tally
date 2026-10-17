package tally.app.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.data.LedgerRepository
import tally.app.data.SplitEntity
import tally.app.data.TransactionEntity
import tally.app.ui.ledger.dayLabel
import tally.app.ui.ledger.ledgerEntries
import tally.app.ui.ledger.occurredAtLocal
import tally.app.ui.theme.OkGreen
import tally.core.model.Money
import tally.core.report.LedgerEntry
import tally.core.report.MonthTotals
import tally.core.report.Recurring
import tally.core.report.Trends
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_SHORT = DateTimeFormatter.ofPattern("MMM")
private val MONTH_LONG = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DAY = DateTimeFormatter.ofPattern("MMM d")

/** Every transaction as report entries, splits expanded. Recomputed when anything changes. */
@Composable
private fun rememberEntries(ledger: LedgerRepository): List<LedgerEntry> {
    val transactions by remember { ledger.observeTransactions() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val splits by remember { ledger.observeAllSplits() }.collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(transactions, splits) {
        val byTransaction = splits.groupBy { it.transactionId }
        transactions.flatMap { it.ledgerEntries(byTransaction[it.id].orEmpty()) }
    }
}

@Composable
private fun rememberMerchantNames(ledger: LedgerRepository): Map<String, String> {
    val merchants by remember { ledger.observeAllMerchants() }.collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(merchants) { merchants.associate { it.id to it.name } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InsightScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
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

    InsightScaffold("Stats", onBack) { modifier ->
        LazyColumn(modifier) {
            item {
                Section("Spending, last 6 months")
                MonthBars(months, Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 16.dp))
            }
            item { Section("Money in and out") }
            items(months.reversed(), key = { it.month.toString() }) { totals ->
                ListItem(
                    headlineContent = { Text(MONTH_LONG.format(totals.month)) },
                    supportingContent = {
                        Text("Spent ${Money.format(totals.spentCents)} · income ${Money.format(totals.incomeCents)}")
                    },
                    trailingContent = {
                        val net = totals.netCents
                        Text(
                            (if (net >= 0) "+" else "") + Money.format(net),
                            color = if (net >= 0) OkGreen else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                )
            }
            item { Section("Top merchants, last 30 days") }
            if (top.isEmpty()) {
                item { Text("No merchant purchases yet.", modifier = Modifier.padding(16.dp)) }
            }
            items(top, key = { it.merchantId }) { merchant ->
                ListItem(
                    headlineContent = { Text(merchantNames[merchant.merchantId] ?: "Unknown") },
                    supportingContent = { Text(if (merchant.count == 1) "1 purchase" else "${merchant.count} purchases") },
                    trailingContent = { Text(Money.format(merchant.cents), style = MaterialTheme.typography.titleMedium) },
                )
            }
        }
    }
}

/** A simple column chart: bar height is the month's spending relative to the biggest month. */
@Composable
private fun MonthBars(months: List<MonthTotals>, modifier: Modifier = Modifier) {
    val max = months.maxOfOrNull { it.spentCents }?.takeIf { it > 0 } ?: 1L
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        months.forEachIndexed { i, totals ->
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                val share = (totals.spentCents.toFloat() / max).coerceIn(0.01f, 1f)
                // Empty space above, then the label sitting on the bar, then the bar.
                Box(Modifier.weight(1.0001f - share))
                Text(
                    compact(totals.spentCents),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(share)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(
                            if (i == months.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        ),
                )
                Text(MONTH_SHORT.format(totals.month), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** "$1.2k" style labels for the chart. */
private fun compact(cents: Long): String {
    val dollars = cents / 100.0
    return when {
        dollars >= 1000 -> "$" + "%.1fk".format(Locale.getDefault(), dollars / 1000)
        else -> "$" + "%.0f".format(Locale.getDefault(), dollars)
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

    InsightScaffold("Subscriptions", onBack) { modifier ->
        LazyColumn(modifier) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Money.format(found.sumOf { it.yearlyCostCents }), style = MaterialTheme.typography.displaySmall)
                    Text("a year", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Found automatically: the same merchant at a similar amount about every month or year. " +
                            "Needs at least two charges.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                HorizontalDivider()
            }
            items(found, key = { it.merchantId }) { recurring ->
                ListItem(
                    headlineContent = { Text(merchantNames[recurring.merchantId] ?: "Unknown") },
                    supportingContent = {
                        val period = if (recurring.period == Recurring.Period.MONTHLY) "monthly" else "yearly"
                        Text("${Money.format(recurring.amountCents)} $period · next around ${DAY.format(recurring.nextExpected)}")
                    },
                    trailingContent = {
                        Text("${Money.format(recurring.yearlyCostCents)}/yr", style = MaterialTheme.typography.titleMedium)
                    },
                )
            }
        }
    }
}

// --- Owed to me ---

/** Split portions others owe you, by person; mark them settled when paid back. */
@Composable
fun OwedScreen(ledger: LedgerRepository, onOpenTransaction: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val owed by remember { ledger.observeOwed() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val transactions by remember { ledger.observeTransactions() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val merchantNames = rememberMerchantNames(ledger)
    val byId = remember(transactions) { transactions.associateBy { it.id } }
    // Parts of deleted transactions don't count.
    val live = owed.filter { it.transactionId in byId }
    val outstanding = live.filter { it.settledAt == null }.groupBy { it.owedBy!! }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val settled = live.filter { it.settledAt != null }.sortedByDescending { it.settledAt }.take(20)
    val today = LocalDate.now()

    InsightScaffold("Owed to me", onBack) { modifier ->
        LazyColumn(modifier) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Money.format(outstanding.values.flatten().sumOf { it.amountCents }), style = MaterialTheme.typography.displaySmall)
                    Text("outstanding", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Add these from a purchase: Split off part → Someone owes me.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                HorizontalDivider()
            }
            outstanding.forEach { (person, parts) ->
                item(key = "person:$person") { Section("$person · ${Money.format(parts.sumOf { it.amountCents })}") }
                items(parts, key = { it.id }) { part ->
                    OwedRow(part, byId[part.transactionId], merchantNames, today, onOpenTransaction) {
                        TextButton(onClick = { scope.launch { ledger.setSettled(part.id, true) } }) { Text("Settled") }
                    }
                }
            }
            if (settled.isNotEmpty()) {
                item { Section("Recently settled") }
                items(settled, key = { "settled:${it.id}" }) { part ->
                    OwedRow(part, byId[part.transactionId], merchantNames, today, onOpenTransaction) {
                        TextButton(onClick = { scope.launch { ledger.setSettled(part.id, false) } }) { Text("Undo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun OwedRow(
    part: SplitEntity,
    transaction: TransactionEntity?,
    merchantNames: Map<String, String>,
    today: LocalDate,
    onOpenTransaction: (String) -> Unit,
    action: @Composable () -> Unit,
) {
    val title = transaction?.merchantId?.let { merchantNames[it] } ?: transaction?.merchantRaw ?: "Purchase"
    ListItem(
        modifier = Modifier.clickable { onOpenTransaction(part.transactionId) },
        headlineContent = { Text("${part.owedBy} · ${Money.format(part.amountCents)}") },
        supportingContent = {
            Text(listOfNotNull(title, transaction?.let { dayLabel(it.occurredAtLocal().toLocalDate(), today) }).joinToString(" · "))
        },
        trailingContent = action,
    )
}
