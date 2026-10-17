package tally.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tally.app.data.LedgerRepository
import tally.app.ui.components.AmountText
import tally.app.ui.components.CumulativeChart
import tally.app.ui.components.IconTile
import tally.app.ui.components.LargeHeader
import tally.app.ui.components.LetterAvatar
import tally.app.ui.components.LocalBottomClearance
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.MonthBarChart
import tally.app.ui.components.PillTone
import tally.app.ui.components.RowDivider
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.SectionHeader
import tally.app.ui.components.Segmented
import tally.app.ui.components.StatusPill
import tally.app.ui.components.TallyCard
import tally.app.ui.components.ThinProgress
import tally.app.ui.components.TransactionRow
import tally.app.ui.components.avatarKey
import tally.app.ui.components.compactMoney
import tally.app.ui.components.groupShape
import tally.app.ui.ledger.dayLabel
import tally.app.ui.ledger.ledgerEntries
import tally.app.ui.ledger.occurredAtLocal
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import tally.core.model.Kind
import tally.core.model.Money
import tally.core.report.BudgetStatus
import tally.core.report.BudgetedCategory
import tally.core.report.Budgets
import tally.core.report.LedgerEntry
import tally.core.report.MonthSummary
import tally.core.report.Trends
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.absoluteValue

private val MONTH_SHORT = DateTimeFormatter.ofPattern("MMM")
private val MONTH_FULL = DateTimeFormatter.ofPattern("MMMM")
private val HEADER_DATE = DateTimeFormatter.ofPattern("EEEE · MMM d")
private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** The month at a glance: what you've spent, how it's going, what needs you, and the latest. */
@Composable
fun HomeScreen(
    ledger: LedgerRepository,
    onOpenMonth: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenTransaction: (String) -> Unit,
) {
    val transactions by remember { ledger.observeTransactions() }.collectAsStateWithLifecycle(initialValue = null)
    val splits by remember { ledger.observeAllSplits() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by remember { ledger.observeAllCategories() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val merchants by remember { ledger.observeAllMerchants() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by remember { ledger.observeAllAccounts() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val inboxCount by remember { ledger.observeInboxCount() }.collectAsStateWithLifecycle(initialValue = 0)

    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val byId = remember(categories) { categories.associateBy { it.id } }
    val merchantNames = remember(merchants) { merchants.associate { it.id to it.name } }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val entries = remember(transactions, splits) {
        val bySplit = splits.groupBy { it.transactionId }
        transactions.orEmpty().flatMap { it.ledgerEntries(bySplit[it.id].orEmpty()) }
    }
    val summary = remember(entries, byId) { MonthSummary.of(month, entries) { byId[it]?.parentId } }
    val budget = remember(categories) {
        Budgets.monthlyTotal(categories.filter { !it.deleted }.map { BudgetedCategory(it.id, it.parentId, it.monthlyBudgetCents) })
    }
    val thisMonth = remember(entries) { runningTotal(entries, month).take(today.dayOfMonth) }
    val lastMonth = remember(entries) { runningTotal(entries, month.minusMonths(1)) }
    var range by rememberSaveable { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = LocalBottomClearance.current),
    ) {
        item(key = "header") {
            LargeHeader(label = HEADER_DATE.format(today), title = "Overview")
        }
        item(key = "hero") {
            HeroCard(
                month = month,
                today = today,
                spentCents = summary.spentCents,
                incomeCents = summary.incomeCents,
                budgetCents = budget,
                sameDayLastMonth = lastMonth.getOrNull(minOf(today.dayOfMonth, lastMonth.size) - 1),
                onClick = onOpenMonth,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }
        if (inboxCount > 0) {
            item(key = "inbox") {
                TallyCard(
                    onClick = onOpenInbox,
                    modifier = Modifier.padding(horizontal = ScreenPadding).padding(top = 12.dp).fillMaxWidth(),
                    padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(TallyIcons.Inbox, background = MaterialTheme.colorScheme.tertiaryContainer, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (inboxCount == 1) "1 thing to review" else "$inboxCount things to review", style = MaterialTheme.typography.titleMedium)
                            Text("Captured purchases waiting for a yes", style = MaterialTheme.typography.bodySmall, color = Tally.colors.muted)
                        }
                        Icon(TallyIcons.Forward, null, Modifier.size(18.dp), tint = Tally.colors.muted)
                    }
                }
            }
        }
        item(key = "chart") {
            TallyCard(Modifier.padding(horizontal = ScreenPadding).padding(top = 12.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoLabel("Spending", Modifier.weight(1f))
                    Segmented(listOf("Month", "6M", "Year"), range, { range = it }, Modifier.width(176.dp))
                }
                Spacer(Modifier.height(14.dp))
                when (range) {
                    0 -> {
                        val previous = MONTH_SHORT.format(month.minusMonths(1))
                        CumulativeChart(
                            current = thisMonth,
                            previous = lastMonth,
                            days = month.lengthOfMonth(),
                            dayLabel = { day -> "${MONTH_SHORT.format(month)} ${day + 1}" },
                            previousLabel = previous,
                            budgetCents = budget,
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                        )
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Legend(MONTH_FULL.format(month), MaterialTheme.colorScheme.onSurface, dashed = false)
                            Legend(MONTH_FULL.format(month.minusMonths(1)), Tally.colors.faint, dashed = true)
                        }
                    }
                    else -> {
                        val count = if (range == 1) 6 else 12
                        val months = remember(entries, count) { Trends.months(entries, month, count) }
                        val average = months.dropLast(1).map { it.spentCents }.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.sum() / it.size }
                        MonthBarChart(
                            values = months.map { it.spentCents.coerceAtLeast(0) },
                            labels = months.map {
                                val name = MONTH_SHORT.format(it.month)
                                if (count > 6) name.take(1) else name
                            },
                            details = months.map { "${MONTH_SHORT.format(it.month)} ${Money.format(it.spentCents)}" },
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                        )
                        if (average > 0) {
                            Text(
                                "Averaging ${compactMoney(average)} a month before this one",
                                style = MaterialTheme.typography.bodySmall,
                                color = Tally.colors.muted,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }
        }
        if (summary.categories.isNotEmpty()) {
            item(key = "categories-header") { SectionHeader("Where it went", action = "Details", onAction = onOpenMonth) }
            val shown = summary.categories.filter { it.cents > 0 }.take(5)
            itemsIndexed(shown, key = { _, it -> "category:${it.categoryId}" }) { index, total ->
                val name = total.categoryId?.let { byId[it]?.name } ?: "Uncategorized"
                val categoryBudget = total.categoryId?.let { byId[it]?.monthlyBudgetCents }
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    Row(
                        Modifier
                            .clip(groupShape(index, shown.size))
                            .background(Tally.colors.card)
                            .clickable(onClick = onOpenMonth)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LetterAvatar(name, total.categoryId, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                                AmountText(total.cents, MaterialTheme.typography.titleSmall, centsScale = 0.85f)
                            }
                            val (fraction, color) = if (categoryBudget != null && categoryBudget > 0) {
                                (total.cents.toFloat() / categoryBudget) to when (BudgetStatus.of(total.cents, categoryBudget)) {
                                    BudgetStatus.OK -> MaterialTheme.colorScheme.secondary
                                    BudgetStatus.WARNING -> Tally.colors.warn
                                    BudgetStatus.OVER -> Tally.colors.over
                                }
                            } else {
                                (if (summary.spentCents > 0) total.cents.toFloat() / summary.spentCents else 0f) to MaterialTheme.colorScheme.secondary
                            }
                            ThinProgress(fraction, color, Tally.colors.chip, height = 4.dp)
                        }
                    }
                    if (index < shown.lastIndex) RowDivider(start = 64.dp)
                }
            }
        }
        val recent = transactions.orEmpty().take(5)
        if (recent.isNotEmpty()) {
            item(key = "recent-header") { SectionHeader("Recent", action = "See all", onAction = onOpenActivity) }
            itemsIndexed(recent, key = { _, it -> "recent:${it.id}" }) { index, t ->
                val categoryName = t.categoryId?.let { byId[it]?.name }
                val title = t.merchantId?.let { merchantNames[it] } ?: t.merchantRaw ?: categoryName ?: "Uncategorized"
                val local = t.occurredAtLocal()
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    TransactionRow(
                        transaction = t,
                        title = title,
                        subtitle = listOfNotNull(
                            (categoryName ?: "Uncategorized").takeIf { title != categoryName },
                            "${dayLabel(local.toLocalDate(), today)} ${TIME.format(local)}",
                        ).joinToString(" · "),
                        avatarKey = avatarKey(t.categoryId, byId),
                        onClick = { onOpenTransaction(t.id) },
                        shape = groupShape(index, recent.size),
                    )
                    if (index < recent.lastIndex) RowDivider()
                }
            }
        }
        if (transactions?.isEmpty() == true) {
            item(key = "empty") {
                TallyCard(Modifier.padding(horizontal = ScreenPadding).padding(top = 12.dp).fillMaxWidth()) {
                    Text("Nothing logged yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap + below, or use the Quick add tile or widget. Card purchases show up on their own once capture is set up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tally.colors.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** The dark card: spent this month, against the budget (with a tick for an even pace) or last month. */
@Composable
private fun HeroCard(
    month: YearMonth,
    today: LocalDate,
    spentCents: Long,
    incomeCents: Long,
    budgetCents: Long?,
    sameDayLastMonth: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tally.colors
    val monthName = MONTH_FULL.format(month)
    TallyCard(modifier.fillMaxWidth(), onClick = onClick, color = colors.hero, shape = RoundedCornerShape(28.dp), padding = PaddingValues(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("Spent in $monthName", Modifier.weight(1f), color = colors.onHeroMuted)
            MonoLabel("Day ${today.dayOfMonth} of ${month.lengthOfMonth()}", color = colors.onHeroMuted)
        }
        Spacer(Modifier.height(10.dp))
        AmountText(spentCents, MaterialTheme.typography.displayLarge, color = colors.onHero, centsScale = 0.5f, centsAlpha = 0.45f)
        Spacer(Modifier.height(14.dp))
        if (budgetCents != null && budgetCents > 0) {
            val left = budgetCents - spentCents
            val color = when (BudgetStatus.of(spentCents, budgetCents)) {
                BudgetStatus.OK -> colors.heroAccent
                BudgetStatus.WARNING -> colors.warn
                BudgetStatus.OVER -> colors.over
            }
            ThinProgress(
                fraction = spentCents.toFloat() / budgetCents,
                color = color,
                track = colors.heroTrack,
                marker = today.dayOfMonth.toFloat() / month.lengthOfMonth(),
                markerColor = colors.onHeroMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (left >= 0) "${Money.format(left)} left of ${Money.format(budgetCents)}" else "${Money.format(-left)} over ${Money.format(budgetCents)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onHero,
                    modifier = Modifier.weight(1f),
                )
                PaceNote(spentCents, sameDayLastMonth, month)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (incomeCents > 0) "${Money.format(incomeCents)} came in" else "No budget set",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onHeroMuted,
                    modifier = Modifier.weight(1f),
                )
                PaceNote(spentCents, sameDayLastMonth, month)
            }
        }
    }
}

/** "↓ 12% vs Sep": this month so far against the same day last month. */
@Composable
private fun PaceNote(spentCents: Long, sameDayLastMonth: Long?, month: YearMonth) {
    if (sameDayLastMonth == null || sameDayLastMonth <= 0) return
    val change = (spentCents - sameDayLastMonth) * 100 / sameDayLastMonth
    val previous = MONTH_SHORT.format(month.minusMonths(1))
    val arrow = if (change > 0) "↑" else "↓"
    StatusPill("$arrow ${change.absoluteValue}% vs $previous", tone = if (change > 0) PillTone.Warn else PillTone.Positive)
}

@Composable
private fun Legend(text: String, color: androidx.compose.ui.graphics.Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(if (dashed) 2 else 1) {
                Spacer(Modifier.width(if (dashed) 5.dp else 12.dp).height(2.dp).background(color, RoundedCornerShape(1.dp)))
            }
        }
        MonoLabel(text)
    }
}

/** Spending to date on each day of [month]: index 0 is the 1st. */
private fun runningTotal(entries: List<LedgerEntry>, month: YearMonth): List<Long> {
    val byDay = LongArray(month.lengthOfMonth())
    entries.forEach { e ->
        if (e.kind == Kind.EXPENSE && YearMonth.from(e.date) == month) byDay[e.date.dayOfMonth - 1] += e.amountCents
    }
    var sum = 0L
    return byDay.map { sum += it; sum }
}
