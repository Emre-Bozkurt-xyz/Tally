package tally.app.ui.ledger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.data.LedgerRepository
import tally.app.data.TransactionEntity
import tally.app.quickentry.QuickEntryActivity
import tally.app.ui.theme.OkGreen
import tally.core.model.Kind
import tally.core.model.TransactionStatus
import tally.core.model.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TIME_FORMAT = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Transactions grouped by day, newest first. Swipe left to delete, tap to edit. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LedgerScreen(
    ledger: LedgerRepository,
    onOpenMonth: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenRawEvents: () -> Unit,
    onOpenTransaction: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val transactions by remember { ledger.observeTransactions() }
        .collectAsStateWithLifecycle(initialValue = null)
    val categories by remember { ledger.observeAllCategories() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by remember { ledger.observeAllAccounts() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val merchants by remember { ledger.observeAllMerchants() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val merchantNames = remember(merchants) { merchants.associate { it.id to it.name } }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    // The list is newest first and groupBy keeps first-seen order, so days stay newest first.
    val tags by remember { ledger.observeTags() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tagLinks by remember { ledger.observeTagLinks() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = remember(transactions, tagLinks, tagFilter) {
        val filter = tagFilter ?: return@remember transactions.orEmpty()
        val tagged = tagLinks.filter { it.tagId == filter }.map { it.transactionId }.toSet()
        transactions.orEmpty().filter { it.id in tagged }
    }
    val days = remember(shown) { shown.groupBy { it.occurredAtLocal().toLocalDate() } }
    val today = LocalDate.now()
    val inboxCount by remember { ledger.observeInboxCount() }.collectAsStateWithLifecycle(initialValue = 0)

    fun delete(transaction: TransactionEntity, label: String) {
        scope.launch {
            ledger.deleteTransaction(transaction.id)
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = "Deleted ${displayAmount(transaction.amountCents)} · $label",
                actionLabel = "Undo",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) ledger.restoreTransaction(transaction.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tally") },
                actions = {
                    IconButton(onClick = onOpenInbox) {
                        BadgedBox(badge = { if (inboxCount > 0) Badge { Text("$inboxCount") } }) {
                            Icon(Icons.Default.Notifications, contentDescription = "Inbox, $inboxCount to review")
                        }
                    }
                    IconButton(onClick = onOpenMonth) {
                        Icon(Icons.Default.DateRange, contentDescription = "Month view")
                    }
                    OverflowMenu(
                        "Categories" to onOpenCategories,
                        "Rules" to onOpenRules,
                        "Setup" to onOpenSetup,
                        "Raw events" to onOpenRawEvents,
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { context.startActivity(QuickEntryActivity.intent(context)) }) {
                Icon(Icons.Default.Add, contentDescription = "Quick add")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (transactions?.isEmpty() == true) {
            EmptyState(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(contentPadding = padding) {
            if (tags.isNotEmpty()) {
                item(key = "tags") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { FilterChip(selected = tagFilter == null, onClick = { tagFilter = null }, label = { Text("All") }) }
                        items(tags, key = { it.id }) { tag ->
                            FilterChip(
                                selected = tagFilter == tag.id,
                                onClick = { tagFilter = if (tagFilter == tag.id) null else tag.id },
                                label = { Text("#${tag.name}") },
                            )
                        }
                    }
                }
            }
            days.forEach { (date, dayTransactions) ->
                stickyHeader(key = date.toString()) {
                    DayHeader(
                        label = dayLabel(date, today),
                        spentCents = dayTransactions.filter { it.kind == Kind.EXPENSE.name }.sumOf { it.amountCents },
                    )
                }
                items(dayTransactions, key = { it.id }) { transaction ->
                    val categoryName = transaction.categoryId?.let { categoryNames[it] }
                    val merchantName = transaction.merchantId?.let { merchantNames[it] } ?: transaction.merchantRaw
                    val title = merchantName ?: categoryName ?: "Uncategorized"
                    DeletableRow(onDelete = { delete(transaction, title) }) {
                        TransactionRow(
                            transaction = transaction,
                            title = title,
                            details = listOfNotNull(
                                "Needs review".takeIf { transaction.status == TransactionStatus.CAPTURED.name },
                                (categoryName ?: "Uncategorized").takeIf { merchantName != null },
                                transaction.note,
                                transaction.accountId?.let { accountNames[it] },
                                TIME_FORMAT.format(transaction.occurredAtLocal()),
                            ).joinToString(" · "),
                            onClick = { onOpenTransaction(transaction.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(vararg items: Pair<String, () -> Unit>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, spentCents: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            Money.format(spentCents),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeletableRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) { content() }
}

@Composable
private fun TransactionRow(
    transaction: TransactionEntity,
    title: String,
    details: String,
    onClick: () -> Unit,
) {
    val amountColor = when {
        transaction.kind == Kind.TRANSFER.name -> MaterialTheme.colorScheme.onSurfaceVariant
        transaction.amountCents < 0 -> OkGreen
        else -> MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Text(
                displayAmount(transaction.amountCents),
                style = MaterialTheme.typography.titleMedium,
                color = amountColor,
            )
        },
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing logged yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap + here, or use the Quick add tile in Quick Settings.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
