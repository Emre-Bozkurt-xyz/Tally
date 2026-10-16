package tally.app.quickentry

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import tally.app.data.TransactionEntity
import tally.app.data.chipCategories
import tally.core.entry.AmountEntry
import tally.core.entry.CategoryRanking
import tally.core.model.Money
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
            accountId = transaction.accountId
            selectedCategoryId = transaction.categoryId
            loaded = true
        }
    }

    val categories by remember { ledger.observeCategories() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by remember { ledger.observeAccounts() }
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
    // Manual entries default to Cash; fall back to any account if Cash was deleted.
    val account = accounts.find { it.id == accountId }
        ?: accounts.find { it.id == DefaultData.accountId(DefaultData.CASH_ACCOUNT_NAME) }
        ?: accounts.firstOrNull()

    val isEdit = request.transactionId != null
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
            )
            val verb = if (isEdit) "Updated" else "Saved"
            onFinished("$verb ${Money.format(amountCents)} · ${category.name}")
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onFinished(null) },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountPicker(account, accounts, onPick = { accountId = it.id })
                Spacer(Modifier.weight(1f))
                if (isEdit) {
                    Text("Editing", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = { noteOpen = !noteOpen }) {
                    Icon(Icons.Default.Edit, contentDescription = "Add a note")
                }
            }

            Text(
                text = Money.format(amountCents),
                style = MaterialTheme.typography.displayMedium,
                color = if (amountCents == 0L) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            if (noteOpen) NoteField(note, onChange = { note = it })

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

            Text(
                text = if (amountCents == 0L) "Enter an amount" else "Tap a category to save",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { category ->
                    FilterChip(
                        selected = category.id == selectedCategoryId,
                        onClick = { save(category) },
                        label = { Text(category.name) },
                        enabled = ready,
                    )
                }
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
        TextButton(onClick = { open = true }, enabled = accounts.size > 1) {
            Text(current?.name ?: "No account")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
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
private fun NoteField(note: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    OutlinedTextField(
        value = note,
        onValueChange = onChange,
        placeholder = { Text("Note") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** 1 to 9, then 00, 0, backspace. Long-press backspace clears. */
@Composable
private fun Numpad(onDigit: (Int) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    NumpadKey(label = "$digit", onClick = { onDigit(digit) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumpadKey(label = "00", onClick = {
                onDigit(0)
                onDigit(0)
            })
            NumpadKey(label = "0", onClick = { onDigit(0) })
            NumpadKey(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
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
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null)
            } else {
                Text(label.orEmpty(), style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}
