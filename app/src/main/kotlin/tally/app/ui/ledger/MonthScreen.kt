package tally.app.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import tally.app.ui.components.CircleIconButton
import tally.app.ui.components.LetterAvatar
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.RowDivider
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.SectionHeader
import tally.app.ui.components.TallyCard
import tally.app.ui.components.TallyTopBar
import tally.app.ui.components.ThinProgress
import tally.app.ui.components.groupShape
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import tally.core.model.Money
import tally.core.report.BudgetStatus
import tally.core.report.BudgetedCategory
import tally.core.report.Budgets
import tally.core.report.CategoryTotal
import tally.core.report.MonthSummary
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy")

/** One month's spending: total, then per top-level category with subcategories below. */
@Composable
fun MonthScreen(ledger: LedgerRepository, onBack: () -> Unit) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthText)
    val thisMonth = YearMonth.now()

    val transactions by remember { ledger.observeTransactions() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by remember { ledger.observeAllCategories() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val byId = remember(categories) { categories.associateBy { it.id } }
    val splits by remember { ledger.observeAllSplits() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val summary = remember(transactions, splits, byId, month) {
        val splitsByTransaction = splits.groupBy { it.transactionId }
        val entries = transactions.flatMap { it.ledgerEntries(splitsByTransaction[it.id].orEmpty()) }
        MonthSummary.of(month, entries) { id -> byId[id]?.parentId }
    }
    val totalBudget = remember(categories) {
        Budgets.monthlyTotal(categories.filter { !it.deleted }.map { BudgetedCategory(it.id, it.parentId, it.monthlyBudgetCents) })
    }
    fun nameOf(total: CategoryTotal) = total.categoryId?.let { byId[it]?.name } ?: "Uncategorized"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TallyTopBar("Month", onBack) },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIconButton(TallyIcons.Back, "Previous month", { monthText = month.minusMonths(1).toString() }, size = 36.dp)
                    Text(
                        MONTH_FORMAT.format(month),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    CircleIconButton(
                        TallyIcons.Forward,
                        "Next month",
                        { monthText = month.plusMonths(1).toString() },
                        size = 36.dp,
                        enabled = month < thisMonth,
                    )
                }
            }
            item {
                val colors = Tally.colors
                TallyCard(
                    Modifier.padding(horizontal = ScreenPadding).padding(top = 12.dp).fillMaxWidth(),
                    color = colors.hero,
                    shape = RoundedCornerShape(28.dp),
                    padding = PaddingValues(22.dp),
                ) {
                    MonoLabel(if (totalBudget != null) "Spent of ${Money.format(totalBudget)}" else "Spent", color = colors.onHeroMuted)
                    Spacer(Modifier.height(10.dp))
                    AmountText(summary.spentCents, MaterialTheme.typography.displayLarge, color = colors.onHero, centsScale = 0.5f, centsAlpha = 0.45f)
                    totalBudget?.let { budget ->
                        Spacer(Modifier.height(14.dp))
                        ThinProgress(
                            fraction = if (budget > 0) summary.spentCents.toFloat() / budget else 1f,
                            color = budgetColor(summary.spentCents, budget, colors.heroAccent),
                            track = colors.heroTrack,
                        )
                    }
                    if (summary.incomeCents != 0L) {
                        Spacer(Modifier.height(12.dp))
                        Text("Income ${Money.format(summary.incomeCents)}", style = MaterialTheme.typography.bodyMedium, color = colors.onHeroMuted)
                    }
                }
            }
            if (summary.categories.isEmpty()) {
                item {
                    Text(
                        "Nothing spent this month",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tally.colors.muted,
                        modifier = Modifier.padding(ScreenPadding),
                    )
                }
            } else {
                item { SectionHeader("By category") }
            }
            summary.categories.forEach { total ->
                val rows = listOf(total) + total.children
                rows.forEachIndexed { index, row ->
                    item(key = "${if (index == 0) "top" else "child"}:${row.categoryId}:${total.categoryId}") {
                        Column(Modifier.padding(horizontal = ScreenPadding)) {
                            CategoryRow(
                                name = nameOf(row),
                                colorKey = total.categoryId,
                                cents = row.cents,
                                ofCents = summary.spentCents,
                                budgetCents = row.categoryId?.let { byId[it]?.monthlyBudgetCents },
                                child = index > 0,
                                modifier = Modifier.clip(groupShape(index, rows.size)),
                            )
                            if (index < rows.lastIndex) RowDivider(start = 64.dp)
                        }
                    }
                }
                item(key = "gap:${total.categoryId}") { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    name: String,
    colorKey: String?,
    cents: Long,
    ofCents: Long,
    budgetCents: Long?,
    child: Boolean,
    modifier: Modifier = Modifier,
) {
    val share = if (ofCents > 0) (cents.toFloat() / ofCents).coerceIn(0f, 1f) else 0f
    Row(
        modifier
            .fillMaxWidth()
            .background(Tally.colors.card)
            .padding(horizontal = 16.dp, vertical = if (child) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (child) {
            Spacer(Modifier.width(48.dp))
        } else {
            LetterAvatar(name, colorKey, size = 36.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = if (child) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    color = if (child) Tally.colors.muted else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                AmountText(
                    cents,
                    if (child) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    color = if (child) Tally.colors.muted else MaterialTheme.colorScheme.onSurface,
                    centsScale = 0.85f,
                )
            }
            // With a budget: progress against it, colored by status. Without: share of the month.
            when {
                budgetCents != null -> {
                    BudgetBar(cents, budgetCents)
                    MonoLabel("of ${Money.format(budgetCents)}", color = Tally.colors.faint)
                }
                !child -> ThinProgress(share, MaterialTheme.colorScheme.secondary, Tally.colors.chip, height = 4.dp)
            }
        }
    }
}

@Composable
private fun budgetColor(spentCents: Long, budgetCents: Long, ok: androidx.compose.ui.graphics.Color) =
    when (BudgetStatus.of(spentCents, budgetCents)) {
        BudgetStatus.OK -> ok
        BudgetStatus.WARNING -> Tally.colors.warn
        BudgetStatus.OVER -> Tally.colors.over
    }

/** Spending against a budget: green under 80%, amber to 100%, red over. */
@Composable
internal fun BudgetBar(spentCents: Long, budgetCents: Long, modifier: Modifier = Modifier) {
    ThinProgress(
        fraction = if (budgetCents > 0) spentCents.toFloat() / budgetCents else 1f,
        color = budgetColor(spentCents, budgetCents, MaterialTheme.colorScheme.secondary),
        track = Tally.colors.chip,
        height = 4.dp,
        modifier = modifier,
    )
}
