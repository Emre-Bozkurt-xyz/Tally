package tally.app.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tally.app.data.LedgerRepository
import tally.core.model.Kind
import tally.core.model.Money
import tally.core.report.CategoryTotal
import tally.core.report.LedgerEntry
import tally.core.report.MonthSummary
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy")

/** One month's spending: total, then per top-level category with subcategories below. */
@OptIn(ExperimentalMaterial3Api::class)
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
    val summary = remember(transactions, byId, month) {
        val entries = transactions.map {
            LedgerEntry(it.amountCents, Kind.valueOf(it.kind), it.categoryId, it.occurredAtLocal().toLocalDate())
        }
        MonthSummary.of(month, entries) { id -> byId[id]?.parentId }
    }
    fun nameOf(total: CategoryTotal) = total.categoryId?.let { byId[it]?.name } ?: "Uncategorized"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Month") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Text(
                        MONTH_FORMAT.format(month),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(
                        onClick = { monthText = month.plusMonths(1).toString() },
                        enabled = month < thisMonth,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(Money.format(summary.spentCents), style = MaterialTheme.typography.displaySmall)
                    Text("spent", style = MaterialTheme.typography.labelLarge)
                    if (summary.incomeCents != 0L) {
                        Text(
                            "Income ${Money.format(summary.incomeCents)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
            if (summary.categories.isEmpty()) {
                item {
                    Text(
                        "Nothing spent this month",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            summary.categories.forEach { total ->
                item(key = "top:${total.categoryId}") {
                    CategoryRow(name = nameOf(total), cents = total.cents, ofCents = summary.spentCents, child = false)
                }
                total.children.forEach { child ->
                    item(key = "child:${child.categoryId}") {
                        CategoryRow(name = nameOf(child), cents = child.cents, ofCents = summary.spentCents, child = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(name: String, cents: Long, ofCents: Long, child: Boolean) {
    val share = if (ofCents > 0) (cents.toFloat() / ofCents).coerceIn(0f, 1f) else 0f
    ListItem(
        modifier = if (child) Modifier.padding(start = 24.dp) else Modifier,
        headlineContent = {
            Text(
                name,
                style = if (child) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = if (child) {
            null
        } else {
            {
                LinearProgressIndicator(
                    progress = { share },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    drawStopIndicator = {},
                )
            }
        },
        trailingContent = {
            Text(
                Money.format(cents),
                style = if (child) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            )
        },
    )
}
