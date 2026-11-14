package tally.app.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tally.app.data.CategoryEntity
import tally.app.data.TransactionEntity
import tally.app.ui.components.SheetHandle
import tally.app.ui.ledger.fallbackTitle
import tally.app.ui.components.AmountText
import tally.app.ui.components.LetterAvatar
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.PillChip
import tally.app.ui.theme.TallyIcons
import tally.app.ui.components.SwitchRow
import tally.app.ui.theme.Tally
import tally.core.model.Kind
import tally.core.model.Money

/** What you chose in the picker. [always] is null unless you ticked "Always put … here". */
data class CategoryChoice(val category: CategoryEntity, val always: AlwaysRule?)

data class AlwaysRule(val maxAmountCents: Long?, val applyToPast: Boolean)

/**
 * Pick a category for a capture, optionally turning the choice into a rule: for its merchant,
 * or, for a bank alert with no merchant (rent leaving chequing), for that exact amount on
 * [accountName]'s account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    transaction: TransactionEntity,
    merchantName: String?,
    categories: List<CategoryEntity>,
    accountName: String? = null,
    onPick: (CategoryChoice) -> Unit,
    onDismiss: () -> Unit,
    /** "Transfer between my accounts": shown when given and it isn't one already. */
    onTransfer: (() -> Unit)? = null,
) {
    var always by remember { mutableStateOf(false) }
    // Prefilled with the amount rounded up to the next dollar, as a sensible cap.
    val cap = ((transaction.amountCents + 99) / 100) * 100
    var capped by remember { mutableStateOf(false) }
    var applyToPast by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = merchantName ?: transaction.fallbackTitle()
                LetterAvatar(title, transaction.categoryId)
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                AmountText(
                    transaction.amountCents,
                    MaterialTheme.typography.titleLarge,
                    color = if (transaction.amountCents < 0) Tally.colors.positive else MaterialTheme.colorScheme.onSurface,
                    centsScale = 0.7f,
                )
            }
            MonoLabel("Choose a category")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { category ->
                    PillChip(
                        text = category.name,
                        selected = category.id == transaction.categoryId,
                        onClick = {
                            onPick(
                                CategoryChoice(
                                    category,
                                    if (always) AlwaysRule(cap.takeIf { capped }, applyToPast) else null,
                                ),
                            )
                        },
                    )
                }
            }
            if (onTransfer != null && transaction.kind != Kind.TRANSFER.name) {
                PillChip(
                    text = "Transfer between my accounts",
                    selected = false,
                    onClick = onTransfer,
                    leading = TallyIcons.Split,
                )
            }
            if (merchantName == null && transaction.amountCents > 0 && transaction.kind == Kind.EXPENSE.name) {
                Column {
                    val from = accountName?.let { " from $it" }.orEmpty()
                    SwitchRow("Always file ${Money.format(transaction.amountCents)} withdrawals$from here", always, { always = it })
                    if (always) SwitchRow("Also change past ones", applyToPast, { applyToPast = it })
                }
            }
            if (merchantName != null) {
                Column {
                    SwitchRow("Always put $merchantName here", always, { always = it })
                    if (always) {
                        SwitchRow("Only up to ${Money.format(cap)}", capped, { capped = it })
                        SwitchRow("Also change past $merchantName purchases", applyToPast, { applyToPast = it })
                    }
                }
            }
        }
    }
}
