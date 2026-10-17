package tally.app.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.capture.CaptureNotifier
import tally.app.data.LedgerRepository
import tally.app.data.RawEventEntity
import tally.app.data.RulesRepository
import tally.app.data.TransactionEntity
import tally.app.data.chipCategories
import tally.app.quickentry.QuickEntryActivity
import tally.app.ui.ledger.dayLabel
import tally.app.ui.ledger.displayAmount
import tally.app.ui.ledger.occurredAtLocal
import tally.app.ui.theme.OkGreen
import tally.core.capture.WatchedPackages
import tally.core.model.CategoryKinds
import tally.core.report.OwedMatch
import tally.core.report.OwedPart
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TIME_FORMAT = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/**
 * Everything waiting for you: captures to confirm (swipe right to accept the suggestion, left to
 * choose a category, tap to edit) and notifications Tally couldn't read (swipe to dismiss).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    ledger: LedgerRepository,
    rules: RulesRepository,
    onOpenRawEvent: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val review by remember { ledger.observeNeedsReview() }.collectAsStateWithLifecycle(initialValue = null)
    val unread by remember { ledger.observeUnreadNotifications() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by remember { ledger.observeAllCategories() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeCategories by remember { ledger.observeCategories() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val merchants by remember { ledger.observeAllMerchants() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by remember { ledger.observeAllAccounts() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val merchantNames = remember(merchants) { merchants.associate { it.id to it.name } }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val chips = remember(activeCategories) { activeCategories.chipCategories() }
    var picking by remember { mutableStateOf<TransactionEntity?>(null) }
    val today = LocalDate.now()
    // Open "owed to me" parts, for money in that pays one back. Re-read as the inbox changes.
    val owed by produceState(initialValue = emptyList<OwedPart>(), review) { value = ledger.openOwedParts() }

    fun nameOf(transaction: TransactionEntity) = transaction.merchantId?.let { merchantNames[it] } ?: transaction.merchantRaw

    fun settles(transaction: TransactionEntity): OwedPart? =
        if (transaction.amountCents < 0) OwedMatch.find(nameOf(transaction), -transaction.amountCents, owed) else null

    fun incomeCategory(categoryId: String): Boolean {
        val category = categories.find { it.id == categoryId } ?: return false
        val top = category.parentId?.let { parent -> categories.find { it.id == parent } } ?: category
        return CategoryKinds.isIncome(top.name)
    }

    fun accept(transaction: TransactionEntity) {
        settles(transaction)?.let { part ->
            scope.launch {
                ledger.settleOwedWith(transaction.id, part.splitId)
                snackbar.showSnackbar("${part.owedBy} paid back ${displayAmount(part.amountCents)}")
            }
            return
        }
        if (transaction.categoryId == null) {
            picking = transaction // Nothing to accept yet.
            return
        }
        scope.launch {
            ledger.confirmCapture(transaction.id)
            CaptureNotifier.cancel(context, transaction.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inbox") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val transactions = review ?: return@Scaffold
        if (transactions.isEmpty() && unread.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("All caught up", style = MaterialTheme.typography.titleMedium)
                Text(
                    "New card and wallet purchases show up here until you confirm them.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(contentPadding = padding) {
            if (transactions.isNotEmpty()) {
                item {
                    Text(
                        "Swipe right to accept, left to choose a category, tap to edit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(transactions, key = { it.id }) { transaction ->
                val merchantName = nameOf(transaction)
                val settling = settles(transaction)
                val suggestion = when {
                    settling != null -> "Settles ${settling.owedBy}'s ${displayAmount(settling.amountCents)}"
                    transaction.categoryId != null -> "Suggested: ${categoryNames[transaction.categoryId]}" +
                        if (transaction.amountCents < 0 && !incomeCategory(transaction.categoryId)) " (money back)" else ""
                    transaction.amountCents < 0 -> "Money in: pick income or what it pays back"
                    else -> "No category yet"
                }
                SwipeRow(
                    rightIcon = Icons.Default.Check,
                    rightColor = OkGreen,
                    leftIcon = Icons.Default.Edit,
                    leftColor = MaterialTheme.colorScheme.primary,
                    onSwipeRight = { accept(transaction) },
                    onSwipeLeft = { picking = transaction },
                ) {
                    ListItem(
                        modifier = Modifier.clickable {
                            context.startActivity(QuickEntryActivity.intent(context, transactionId = transaction.id))
                        },
                        headlineContent = { Text(merchantName ?: "Card purchase", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    suggestion,
                                    transaction.accountId?.let { accountNames[it] },
                                    "${dayLabel(transaction.occurredAtLocal().toLocalDate(), today)} " +
                                        TIME_FORMAT.format(transaction.occurredAtLocal()),
                                ).joinToString(" · "),
                                maxLines = 2,
                            )
                        },
                        trailingContent = {
                            Text(displayAmount(transaction.amountCents), style = MaterialTheme.typography.titleMedium)
                        },
                    )
                }
                HorizontalDivider()
            }
            if (unread.isNotEmpty()) {
                item {
                    Text(
                        "Couldn't read these. Send an export so the parser can learn them; swipe to dismiss.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                }
            }
            items(unread, key = { it.id }) { event ->
                SwipeRow(
                    rightIcon = Icons.Default.Close,
                    rightColor = MaterialTheme.colorScheme.outline,
                    leftIcon = Icons.Default.Close,
                    leftColor = MaterialTheme.colorScheme.outline,
                    onSwipeRight = { scope.launch { ledger.dismissUnreadNotification(event.id) } },
                    onSwipeLeft = { scope.launch { ledger.dismissUnreadNotification(event.id) } },
                ) {
                    UnreadRow(event, today, onClick = { onOpenRawEvent(event.id) })
                }
                HorizontalDivider()
            }
        }
    }

    picking?.let { transaction ->
        val merchantName = transaction.merchantId?.let { merchantNames[it] }
        CategoryPickerSheet(
            transaction = transaction,
            merchantName = merchantName,
            categories = chips,
            onDismiss = { picking = null },
            onPick = { choice ->
                picking = null
                scope.launch {
                    ledger.saveQuickEntry(transaction, transaction.amountCents, choice.category.id, transaction.accountId, transaction.note)
                    CaptureNotifier.cancel(context, transaction.id)
                    val always = choice.always
                    if (always != null && transaction.merchantId != null && merchantName != null) {
                        val changed = rules.alwaysCategorize(
                            merchantId = transaction.merchantId,
                            merchantName = merchantName,
                            categoryId = choice.category.id,
                            maxAmountCents = always.maxAmountCents,
                            applyToPast = always.applyToPast,
                        )
                        snackbar.showSnackbar(
                            "Rule added: $merchantName → ${choice.category.name}" +
                                if (always.applyToPast) " ($changed updated)" else "",
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun UnreadRow(event: RawEventEntity, today: LocalDate, onClick: () -> Unit) {
    val time = Instant.ofEpochMilli(event.postedAt).atZone(ZoneId.of(event.zoneId))
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        overlineContent = {
            Text("${WatchedPackages.displayName(event.source)} · ${dayLabel(time.toLocalDate(), today)} ${TIME_FORMAT.format(time)}")
        },
        headlineContent = { Text(event.title ?: "(no title)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(event.bigText ?: event.text ?: "(no text)", maxLines = 3, overflow = TextOverflow.Ellipsis) },
    )
}

/** A row with an action on each swipe direction. The row springs back unless the list drops it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(
    rightIcon: ImageVector,
    rightColor: Color,
    leftIcon: ImageVector,
    leftColor: Color,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onSwipeRight()
                SwipeToDismissBoxValue.EndToStart -> onSwipeLeft()
                SwipeToDismissBoxValue.Settled -> {}
            }
            scope.launch { state.reset() }
        },
        backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (toRight) rightColor else leftColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(if (toRight) rightIcon else leftIcon, contentDescription = null, tint = Color.White)
            }
        },
    ) { content() }
}
