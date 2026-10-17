package tally.app.ui.ledger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.data.LedgerRepository
import tally.app.data.TransactionEntity
import tally.app.ui.components.EmptyMessage
import tally.app.ui.components.LargeHeader
import tally.app.ui.components.LocalBottomClearance
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.PillChip
import tally.app.ui.components.RowDivider
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.TransactionRow
import tally.app.ui.components.avatarKey
import tally.app.ui.components.groupShape
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import tally.core.model.Kind
import tally.core.model.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TIME_FORMAT = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Every transaction, by day, newest first. Swipe left to delete, tap to open. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LedgerScreen(ledger: LedgerRepository, onOpenTransaction: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val transactions by remember { ledger.observeTransactions() }.collectAsStateWithLifecycle(initialValue = null)
    val categories by remember { ledger.observeAllCategories() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by remember { ledger.observeAllAccounts() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val merchants by remember { ledger.observeAllMerchants() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val byId = remember(categories) { categories.associateBy { it.id } }
    val merchantNames = remember(merchants) { merchants.associate { it.id to it.name } }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val tags by remember { ledger.observeTags() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tagLinks by remember { ledger.observeTagLinks() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = remember(transactions, tagLinks, tagFilter) {
        val filter = tagFilter ?: return@remember transactions.orEmpty()
        val tagged = tagLinks.filter { it.tagId == filter }.map { it.transactionId }.toSet()
        transactions.orEmpty().filter { it.id in tagged }
    }
    // The list is newest first and groupBy keeps first-seen order, so days stay newest first.
    val days = remember(shown) { shown.groupBy { it.occurredAtLocal().toLocalDate() } }
    val today = LocalDate.now()

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

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = LocalBottomClearance.current),
        ) {
            item(key = "header") {
                val count = transactions.orEmpty().size
                LargeHeader(label = if (count == 1) "1 transaction" else "$count transactions", title = "Activity")
            }
            if (tags.isNotEmpty()) {
                item(key = "tags") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ScreenPadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        item { PillChip("All", selected = tagFilter == null, onClick = { tagFilter = null }) }
                        items(tags, key = { it.id }) { tag ->
                            PillChip(
                                "#${tag.name}",
                                selected = tagFilter == tag.id,
                                onClick = { tagFilter = if (tagFilter == tag.id) null else tag.id },
                            )
                        }
                    }
                }
            }
            if (transactions?.isEmpty() == true) {
                item(key = "empty") {
                    EmptyMessage("Nothing logged yet", "Tap + below, or use the Quick add tile in Quick Settings.")
                }
            }
            days.forEach { (date, dayTransactions) ->
                stickyHeader(key = date.toString()) {
                    DayHeader(
                        label = dayLabel(date, today),
                        spentCents = dayTransactions.filter { it.kind == Kind.EXPENSE.name }.sumOf { it.amountCents },
                    )
                }
                itemsIndexed(dayTransactions, key = { _, it -> it.id }) { index, transaction ->
                    val categoryName = transaction.categoryId?.let { byId[it]?.name }
                    val merchantName = transaction.merchantId?.let { merchantNames[it] } ?: transaction.merchantRaw
                    val title = merchantName ?: categoryName ?: "Uncategorized"
                    val shape = groupShape(index, dayTransactions.size)
                    Column(Modifier.padding(horizontal = ScreenPadding).animateItem()) {
                        DeletableRow(shape, onDelete = { delete(transaction, title) }) {
                            TransactionRow(
                                transaction = transaction,
                                title = title,
                                subtitle = listOfNotNull(
                                    (categoryName ?: "Uncategorized").takeIf { merchantName != null },
                                    transaction.note,
                                    transaction.accountId?.let { accountNames[it] },
                                    TIME_FORMAT.format(transaction.occurredAtLocal()),
                                ).joinToString(" · "),
                                avatarKey = avatarKey(transaction.categoryId, byId),
                                onClick = { onOpenTransaction(transaction.id) },
                                shape = shape,
                            )
                        }
                        if (index < dayTransactions.lastIndex) RowDivider()
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomClearance.current))
    }
}

@Composable
private fun DayHeader(label: String, spentCents: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = ScreenPadding + 4.dp)
            .padding(top = 18.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MonoLabel(label)
        MonoLabel(Money.format(spentCents), color = Tally.colors.faint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeletableRow(shape: Shape, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.clip(shape),
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
                Icon(TallyIcons.Trash, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(22.dp))
            }
        },
    ) { content() }
}
