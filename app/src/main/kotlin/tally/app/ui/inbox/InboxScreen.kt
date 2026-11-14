package tally.app.ui.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import tally.app.ui.components.AmountText
import tally.app.ui.components.EmptyMessage
import tally.app.ui.components.LargeHeader
import tally.app.ui.components.LetterAvatar
import tally.app.ui.components.LocalBottomClearance
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.PillButton
import tally.app.ui.components.PillStyle
import tally.app.ui.components.PillTone
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.SectionHeader
import tally.app.ui.components.StatusPill
import tally.app.ui.components.avatarKey
import tally.app.ui.ledger.dayLabel
import tally.app.ui.ledger.displayAmount
import tally.app.ui.ledger.fallbackTitle
import tally.app.ui.ledger.occurredAtLocal
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import tally.core.model.Money
import tally.core.capture.WatchedPackages
import tally.core.model.CategoryKinds
import tally.app.ui.components.PillChip
import tally.app.ui.split.QuickSplitRow
import tally.app.ui.split.SplitSheet
import tally.core.model.Kind
import tally.core.report.Balance
import tally.core.report.Balances
import tally.core.report.Offer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TIME_FORMAT = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/**
 * Everything waiting for you: captures to confirm (Accept, or swipe right; Change, or swipe
 * left; tap to edit) and notifications Tally couldn't read (swipe to dismiss).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(
    ledger: LedgerRepository,
    rules: RulesRepository,
    onOpenRawEvent: (String) -> Unit,
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
    val byId = remember(categories) { categories.associateBy { it.id } }
    val merchantNames = remember(merchants) { merchants.associate { it.id to it.name } }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val chips = remember(activeCategories) { activeCategories.chipCategories() }
    var picking by remember { mutableStateOf<TransactionEntity?>(null) }
    val today = LocalDate.now()
    // Who owes you what, for money in that pays it back; who you split with lately, for "½ with Sam".
    val balances by remember { ledger.observeBalances() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by remember { ledger.observeRecentPeople() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val splits by remember { ledger.observeAllSplits() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val splitsFor = remember(splits) { splits.groupBy { it.transactionId } }
    val owing = balances.filter { it.balanceCents > 0 }
    // A deposit with no name you said came from someone: transaction id to their name.
    val fromSomeone = remember { mutableStateMapOf<String, String>() }
    var splitting by remember { mutableStateOf<Pair<TransactionEntity, String?>?>(null) }

    fun nameOf(transaction: TransactionEntity) = transaction.merchantId?.let { merchantNames[it] } ?: transaction.merchantRaw

    fun isDeposit(transaction: TransactionEntity) = transaction.amountCents < 0 && transaction.kind != Kind.TRANSFER.name

    /** Who money in pays back: the sender, if they owe you, or whoever you picked for an unnamed deposit. */
    fun payer(transaction: TransactionEntity): Balance? {
        if (!isDeposit(transaction)) return null
        val picked = fromSomeone[transaction.id]
        val balance = if (picked != null) owing.firstOrNull { it.person == picked } else Balances.forSender(nameOf(transaction), owing)
        return balance?.takeIf { it.balanceCents > 0 }
    }

    fun settle(transaction: TransactionEntity, balance: Balance, callItEven: Boolean) {
        val received = -transaction.amountCents
        val first = balance.person.substringBefore(' ')
        val left = if (callItEven) 0 else balance.balanceCents - received
        scope.launch {
            val id = ledger.settle(balance.person, received, transaction.id, callItEven) ?: return@launch
            fromSomeone.remove(transaction.id)
            CaptureNotifier.cancel(context, transaction.id)
            val message = when {
                callItEven -> "Even with $first"
                left > 0 -> "$first paid ${Money.format(received)} · ${Money.format(left)} still owed"
                left < 0 -> "$first paid ${Money.format(received)} · ${Money.format(-left)} credit"
                else -> "$first paid you back"
            }
            if (snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                ledger.undoSettlement(id, restore = transaction)
            }
        }
    }

    fun splitWith(transaction: TransactionEntity, person: String, cents: Long) {
        scope.launch {
            val id = ledger.splitWith(transaction.id, person, cents).getOrNull() ?: return@launch
            val result = snackbar.showSnackbar(
                "${person.substringBefore(' ')} owes ${Money.format(cents)}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) ledger.deleteSplit(id)
        }
    }

    fun incomeCategory(categoryId: String): Boolean {
        val category = byId[categoryId] ?: return false
        val top = category.parentId?.let { byId[it] } ?: category
        return CategoryKinds.isIncome(top.name)
    }

    fun accept(transaction: TransactionEntity) {
        payer(transaction)?.let { balance ->
            settle(transaction, balance, callItEven = Balances.offer(balance.balanceCents, -transaction.amountCents) is Offer.Close)
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

    Box(Modifier.fillMaxSize()) {
        val transactions = review
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = LocalBottomClearance.current),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                val waiting = transactions.orEmpty().size + unread.size
                LargeHeader(label = if (waiting == 0) "Nothing waiting" else "$waiting waiting", title = "Inbox")
            }
            if (transactions != null && transactions.isEmpty() && unread.isEmpty()) {
                item(key = "empty") {
                    EmptyMessage("All caught up", "New card and wallet purchases show up here until you confirm them.")
                }
            }
            items(transactions.orEmpty(), key = { it.id }) { transaction ->
                val merchantName = nameOf(transaction)
                val settling = payer(transaction)
                val offer = settling?.let { Balances.offer(it.balanceCents, -transaction.amountCents) }
                val categoryName = transaction.categoryId?.let { byId[it]?.name }
                val moneyBack = transaction.amountCents < 0 && transaction.categoryId != null && !incomeCategory(transaction.categoryId)
                SwipeRow(
                    rightIcon = TallyIcons.Check,
                    rightColor = MaterialTheme.colorScheme.secondary,
                    leftIcon = TallyIcons.Pencil,
                    leftColor = MaterialTheme.colorScheme.primary,
                    onSwipeRight = { accept(transaction) },
                    onSwipeLeft = { picking = transaction },
                    modifier = Modifier.padding(horizontal = ScreenPadding).animateItem(),
                ) {
                    CaptureCard(
                        title = merchantName ?: transaction.fallbackTitle(),
                        avatarKey = avatarKey(transaction.categoryId, byId),
                        amountCents = transaction.amountCents,
                        details = listOfNotNull(
                            transaction.accountId?.let { accountNames[it] },
                            "${dayLabel(transaction.occurredAtLocal().toLocalDate(), today)} ${TIME_FORMAT.format(transaction.occurredAtLocal())}",
                        ).joinToString(" · "),
                        suggestion = when {
                            settling != null -> "${settling.person} owes ${Money.format(settling.balanceCents)}" +
                                settling.open.size.let { n -> " ($n item${if (n == 1) "" else "s"})" }
                            categoryName != null -> categoryName
                            transaction.amountCents < 0 -> "Money in: income, or what it pays back?"
                            else -> null
                        },
                        tag = when {
                            settling != null -> "Owed" to PillTone.Positive
                            moneyBack -> "Money back" to PillTone.Positive
                            transaction.amountCents < 0 && categoryName != null -> "Income" to PillTone.Positive
                            else -> null
                        },
                        acceptLabel = when {
                            offer is Offer.Close -> "Call it even"
                            settling != null -> "Settle"
                            transaction.categoryId == null -> "Pick category"
                            else -> "Accept"
                        },
                        extra = when {
                            settling != null && offer != null -> {
                                { SettleChoices(settling, offer, picked = transaction.id in fromSomeone, onKeep = { settle(transaction, settling, callItEven = false) }, onNotThem = { fromSomeone.remove(transaction.id) }) }
                            }
                            isDeposit(transaction) && merchantName.isNullOrBlank() && owing.isNotEmpty() -> {
                                { FromSomeone(owing) { fromSomeone[transaction.id] = it.person } }
                            }
                            transaction.amountCents > 0 && transaction.kind == Kind.EXPENSE.name &&
                                (people.isNotEmpty() || splitsFor[transaction.id].orEmpty().any { it.owedBy != null }) -> {
                                {
                                    val parts = splitsFor[transaction.id].orEmpty()
                                    QuickSplitRow(
                                        people = people,
                                        amountCents = transaction.amountCents,
                                        leftCents = transaction.amountCents - parts.sumOf { it.amountCents },
                                        owed = parts,
                                        onSplit = { person, cents -> splitWith(transaction, person, cents) },
                                        onRemove = { part -> scope.launch { ledger.deleteSplit(part.id) } },
                                        onMore = { person -> splitting = transaction to person },
                                    )
                                }
                            }
                            else -> null
                        },
                        onAccept = { accept(transaction) },
                        onChange = { picking = transaction },
                        onOpen = { context.startActivity(QuickEntryActivity.intent(context, transactionId = transaction.id)) },
                    )
                }
            }
            if (unread.isNotEmpty()) {
                item(key = "unread-header") {
                    Column {
                        SectionHeader("Couldn't read these")
                        Text(
                            "Send an export so the parser can learn them. Swipe to dismiss.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Tally.colors.muted,
                            modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 4.dp),
                        )
                    }
                }
            }
            items(unread, key = { it.id }) { event ->
                SwipeRow(
                    rightIcon = TallyIcons.Close,
                    rightColor = Tally.colors.faint,
                    leftIcon = TallyIcons.Close,
                    leftColor = Tally.colors.faint,
                    onSwipeRight = { scope.launch { ledger.dismissUnreadNotification(event.id) } },
                    onSwipeLeft = { scope.launch { ledger.dismissUnreadNotification(event.id) } },
                    modifier = Modifier.padding(horizontal = ScreenPadding).animateItem(),
                ) {
                    UnreadCard(event, today, onClick = { onOpenRawEvent(event.id) })
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomClearance.current))
    }

    splitting?.let { (transaction, person) ->
        SplitSheet(
            amountCents = transaction.amountCents,
            leftCents = transaction.amountCents - splitsFor[transaction.id].orEmpty().sumOf { it.amountCents },
            people = people,
            initialPerson = person,
            onSplit = { who, cents -> ledger.splitWith(transaction.id, who, cents).exceptionOrNull()?.message },
            onDismiss = { splitting = null },
        )
    }

    picking?.let { transaction ->
        val merchantName = transaction.merchantId?.let { merchantNames[it] }
        CategoryPickerSheet(
            transaction = transaction,
            merchantName = merchantName,
            categories = chips,
            accountName = transaction.accountId?.let { accountNames[it] },
            onDismiss = { picking = null },
            onTransfer = {
                picking = null
                scope.launch {
                    ledger.markTransfer(transaction.id)
                    CaptureNotifier.cancel(context, transaction.id)
                    val result = snackbar.showSnackbar("Marked as a transfer", actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) ledger.restore(transaction)
                }
            },
            onPick = { choice ->
                picking = null
                scope.launch {
                    ledger.saveQuickEntry(transaction, transaction.amountCents, choice.category.id, transaction.accountId, transaction.note)
                    CaptureNotifier.cancel(context, transaction.id)
                    val always = choice.always
                    if (always != null && transaction.merchantId == null) {
                        val changed = rules.alwaysFileAmount(transaction.amountCents, transaction.accountId, choice.category.id, always.applyToPast)
                        snackbar.showSnackbar(
                            "Rule added: ${Money.format(transaction.amountCents)} withdrawals → ${choice.category.name}" +
                                if (always.applyToPast) " ($changed updated)" else "",
                        )
                    } else if (always != null && merchantName != null) {
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

private val CardShape = RoundedCornerShape(24.dp)

@Composable
private fun CaptureCard(
    title: String,
    avatarKey: String?,
    amountCents: Long,
    details: String,
    suggestion: String?,
    tag: Pair<String, PillTone>?,
    acceptLabel: String,
    onAccept: () -> Unit,
    onChange: () -> Unit,
    onOpen: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Tally.colors.card)
            .clickable(onClick = onOpen)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterAvatar(title, avatarKey)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(details, style = MaterialTheme.typography.bodySmall, color = Tally.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            AmountText(
                amountCents,
                MaterialTheme.typography.titleLarge,
                color = if (amountCents < 0) Tally.colors.positive else MaterialTheme.colorScheme.onSurface,
                centsScale = 0.7f,
            )
        }
        Row(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MonoLabel(if (suggestion != null) "Suggested" else "Category")
            Text(
                suggestion ?: "None yet",
                style = MaterialTheme.typography.labelLarge,
                color = if (suggestion != null) MaterialTheme.colorScheme.onSurface else Tally.colors.muted,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            tag?.let { (text, tone) -> StatusPill(text, tone = tone) }
        }
        if (extra != null) {
            Box(Modifier.padding(top = 12.dp)) { extra() }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Change", onChange, Modifier.weight(1f), style = PillStyle.Soft, height = 44.dp)
            PillButton(acceptLabel, onAccept, Modifier.weight(1f), style = PillStyle.Primary, icon = TallyIcons.Check, height = 44.dp)
        }
    }
}

@Composable
private fun UnreadCard(event: RawEventEntity, today: LocalDate, onClick: () -> Unit) {
    val time = Instant.ofEpochMilli(event.postedAt).atZone(ZoneId.of(event.zoneId))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Tally.colors.card)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MonoLabel("${WatchedPackages.displayName(event.source)} · ${dayLabel(time.toLocalDate(), today)} ${TIME_FORMAT.format(time)}")
        Text(event.title ?: "(no title)", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            event.bigText ?: event.text ?: "(no text)",
            style = MaterialTheme.typography.bodySmall,
            color = Tally.colors.muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A card with an action on each swipe direction. It springs back unless the list drops it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(
    rightIcon: ImageVector,
    rightColor: Color,
    leftIcon: ImageVector,
    leftColor: Color,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        modifier = modifier.clip(CardShape),
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
            val color = if (toRight) rightColor else leftColor
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 28.dp),
                contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    if (toRight) rightIcon else leftIcon,
                    contentDescription = null,
                    tint = if (color == MaterialTheme.colorScheme.primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    ) { content() }
}

/**
 * What else money from someone who owes you can do: keep the small difference owing (or as
 * credit) instead of calling it even, or what settling leaves when it's further off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettleChoices(balance: Balance, offer: Offer, picked: Boolean, onKeep: () -> Unit, onNotThem: () -> Unit) {
    val first = balance.person.substringBefore(' ')
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        when (offer) {
            is Offer.Close -> PillChip(
                if (offer.differenceCents > 0) "Keep ${Money.format(offer.differenceCents)} owing" else "Keep ${Money.format(-offer.differenceCents)} as credit",
                selected = false,
                onClick = onKeep,
            )
            is Offer.Partial -> Text(
                "Settling leaves ${Money.format(offer.leftCents)} owing",
                style = MaterialTheme.typography.bodySmall,
                color = Tally.colors.muted,
            )
            is Offer.Over -> Text(
                "That's ${Money.format(offer.creditCents)} more than $first owes: settling keeps it as credit",
                style = MaterialTheme.typography.bodySmall,
                color = Tally.colors.muted,
            )
            Offer.Exact -> {}
        }
        if (picked) PillChip("Not $first", selected = false, onClick = onNotThem, trailing = TallyIcons.Close)
    }
}

/** A deposit with no name: was it someone paying you back? */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FromSomeone(owing: List<Balance>, onPick: (Balance) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel("From someone?")
        owing.take(3).forEach { balance ->
            PillChip("${balance.person.substringBefore(' ')} · ${Money.format(balance.balanceCents)}", selected = false, onClick = { onPick(balance) })
        }
    }
}
