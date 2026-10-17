package tally.app.quickentry

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.staggeredgrid.LazyHorizontalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import tally.app.ui.components.AmountText
import tally.app.ui.components.bleed
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.PillButton
import tally.app.ui.components.PillChip
import tally.app.ui.components.SheetHandle
import tally.app.ui.components.SoftTextField
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.data.AccountEntity
import tally.app.data.CategoryEntity
import tally.app.data.DefaultData
import tally.app.data.LedgerRepository
import tally.app.data.MerchantEntity
import tally.app.data.TransactionEntity
import tally.app.data.chipCategories
import tally.core.entry.AmountEntry
import tally.core.entry.CategoryRanking
import tally.core.model.Money
import tally.core.model.Source
import java.time.ZonedDateTime

/**
 * Numpad, then tap a category chip to save. [onFinished] gets a confirmation message after
 * a save, or null when the sheet was dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickEntrySheet(
    ledger: LedgerRepository,
    request: QuickEntryRequest,
    onFinished: (message: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var amountCents by rememberSaveable { mutableLongStateOf(request.amountCents) }
    var note by rememberSaveable { mutableStateOf("") }
    var noteOpen by rememberSaveable { mutableStateOf(false) }
    var merchant by rememberSaveable { mutableStateOf("") }
    var merchantOpen by rememberSaveable { mutableStateOf(false) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCategoryId by rememberSaveable { mutableStateOf(request.categoryId) }
    var existing by remember { mutableStateOf<TransactionEntity?>(null) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // Edit mode: load the transaction. `existing` isn't saveable, so reload it after
    // recreation, but only prefill the fields the first time.
    LaunchedEffect(request.transactionId) {
        val id = request.transactionId ?: return@LaunchedEffect
        val transaction = ledger.getTransaction(id)
        if (transaction == null) {
            onFinished("That transaction no longer exists")
            return@LaunchedEffect
        }
        existing = transaction
        if (!loaded) {
            amountCents = transaction.amountCents
            note = transaction.note.orEmpty()
            noteOpen = note.isNotEmpty()
            if (transaction.source == Source.MANUAL.name) {
                merchant = transaction.merchantRaw.orEmpty()
                merchantOpen = merchant.isNotEmpty()
            }
            accountId = transaction.accountId
            selectedCategoryId = transaction.categoryId
            loaded = true
        }
    }

    val categories by remember { ledger.observeCategories() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by remember { ledger.observeAccounts() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val merchants by remember { ledger.observeMerchantChoices() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val uses by produceState(initialValue = emptyList()) { value = ledger.recentCategoryUses() }
    val chips = remember(categories, uses) {
        val choices = categories.chipCategories()
        val byId = choices.associateBy { it.id }
        CategoryRanking.rank(
            treeOrder = choices.map { it.id },
            pinned = choices.filter { it.pinned }.map { it.id }.toSet(),
            uses = uses,
            now = ZonedDateTime.now(),
        ).map { byId.getValue(it) }
    }
    // New entries default to Cash (or any account if Cash was deleted). Edits keep what the
    // transaction has, even none: a capture's unknown account must not silently become Cash.
    val account = accounts.find { it.id == accountId }
        ?: if (request.transactionId != null) {
            null
        } else {
            accounts.find { it.id == DefaultData.accountId(DefaultData.CASH_ACCOUNT_NAME) } ?: accounts.firstOrNull()
        }

    val isEdit = request.transactionId != null
    // Captures keep the merchant their notification named; manual entries can have one typed.
    val canTypeMerchant = !isEdit || existing?.source == Source.MANUAL.name
    val ready = amountCents > 0 && !saving && (!isEdit || existing != null)

    fun save(category: CategoryEntity) {
        if (!ready) return
        saving = true
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            ledger.saveQuickEntry(
                existing = existing,
                amountCents = amountCents,
                categoryId = category.id,
                accountId = account?.id,
                note = note.trim().ifEmpty { null },
                merchantName = if (canTypeMerchant) merchant.trim() else null,
            )
            val verb = if (isEdit) "Updated" else "Saved"
            val at = merchant.trim().takeIf { canTypeMerchant && it.isNotEmpty() }?.let { " at $it" }.orEmpty()
            onFinished("$verb ${Money.format(amountCents)}$at · ${category.name}")
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onFinished(null) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(
                    when {
                        !isEdit -> "New expense"
                        canTypeMerchant -> "Edit"
                        else -> existing?.merchantRaw ?: "Edit"
                    },
                    Modifier.weight(1f),
                )
                AccountPicker(account, accounts, onPick = { accountId = it.id })
            }

            AmountText(
                cents = amountCents,
                style = MaterialTheme.typography.displayMedium,
                color = if (amountCents == 0L) Tally.colors.faint else MaterialTheme.colorScheme.onSurface,
                centsScale = 0.55f,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                text = Money.format(amountCents),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (canTypeMerchant) {
                    PillChip(
                        text = merchant.trim().ifEmpty { "Merchant" },
                        selected = merchantOpen,
                        onClick = { merchantOpen = !merchantOpen },
                        leading = TallyIcons.Store,
                    )
                }
                PillChip(
                    text = note.trim().ifEmpty { "Note" }.let { if (it.length > 18) it.take(17) + "…" else it },
                    selected = noteOpen,
                    onClick = { noteOpen = !noteOpen },
                    leading = TallyIcons.Note,
                )
            }

            if (merchantOpen && canTypeMerchant) {
                MerchantField(
                    merchant = merchant,
                    known = merchants,
                    onChange = { merchant = it },
                    onPick = { picked ->
                        merchant = picked.name
                        // Its usual category, unless you came from a category button already.
                        if (selectedCategoryId == null) selectedCategoryId = picked.lastCategoryId
                    },
                )
            }
            if (noteOpen) NoteField(note, onChange = { note = it })

            val selected = categories.find { it.id == selectedCategoryId }
            MonoLabel(
                when {
                    amountCents == 0L -> "Enter an amount"
                    selected != null -> "Or tap another category"
                    else -> "Tap a category to save"
                },
            )
            // Three rows that scroll sideways, most used first, so the numpad stays on screen.
            LazyHorizontalStaggeredGrid(
                rows = StaggeredGridCells.Fixed(if (chips.size > 8) 3 else 2),
                horizontalItemSpacing = 8.dp,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                modifier = Modifier
                    .bleed(20.dp)
                    .fillMaxWidth()
                    .height(if (chips.size > 8) 130.dp else 84.dp),
            ) {
                items(chips, key = { it.id }) { category ->
                    PillChip(
                        text = category.name,
                        selected = category.id == selectedCategoryId,
                        onClick = { save(category) },
                        enabled = ready,
                    )
                }
            }

            Numpad(
                onDigit = { digit ->
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    amountCents = AmountEntry.type(amountCents, digit)
                },
                onBackspace = {
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    amountCents = AmountEntry.backspace(amountCents)
                },
                onClear = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    amountCents = 0
                },
            )

            // Preselected (from a widget button) or the current category (editing): one-tap save.
            if (selected != null) {
                PillButton(
                    text = "Save to ${selected.name}",
                    onClick = { save(selected) },
                    enabled = ready,
                    icon = TallyIcons.Check,
                    height = 56.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AccountPicker(
    current: AccountEntity?,
    accounts: List<AccountEntity>,
    onPick: (AccountEntity) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PillChip(
            text = current?.name ?: "No account",
            selected = false,
            onClick = { open = true },
            enabled = accounts.size > 1,
            leading = TallyIcons.Wallet,
            trailing = TallyIcons.Down,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onPick(account)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MerchantField(
    merchant: String,
    known: List<MerchantEntity>,
    onChange: (String) -> Unit,
    onPick: (MerchantEntity) -> Unit,
) {
    val focus = remember { FocusRequester() }
    SoftTextField(
        value = merchant,
        onValueChange = onChange,
        placeholder = "Merchant (optional)",
        leading = TallyIcons.Store,
        modifier = Modifier.focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
    val query = merchant.trim()
    val matches = remember(query, known) {
        known.filter { query.isEmpty() || (query.lowercase() in it.name.lowercase() && !it.name.equals(query, ignoreCase = true)) }
            .take(MERCHANT_SUGGESTIONS)
    }
    if (matches.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            matches.forEach { m -> PillChip(m.name, selected = false, onClick = { onPick(m) }) }
        }
    }
}

private const val MERCHANT_SUGGESTIONS = 4

@Composable
private fun NoteField(note: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    SoftTextField(
        value = note,
        onValueChange = onChange,
        placeholder = "Note",
        leading = TallyIcons.Note,
        modifier = Modifier.focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** 1 to 9, then 00, 0, backspace. Long-press backspace clears. */
@Composable
private fun Numpad(onDigit: (Int) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { digit ->
                    NumpadKey(label = "$digit", onClick = { onDigit(digit) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumpadKey(label = "00", onClick = {
                onDigit(0)
                onDigit(0)
            })
            NumpadKey(label = "0", onClick = { onDigit(0) })
            NumpadKey(
                icon = TallyIcons.Backspace,
                description = "Backspace, hold to clear",
                onClick = onBackspace,
                onLongClick = onClear,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.NumpadKey(
    onClick: () -> Unit,
    label: String? = null,
    icon: ImageVector? = null,
    description: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
        } else {
            Text(label.orEmpty(), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal))
        }
    }
}
