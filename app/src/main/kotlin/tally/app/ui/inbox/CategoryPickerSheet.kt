package tally.app.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import tally.app.data.CategoryEntity
import tally.app.data.TransactionEntity
import tally.app.ui.ledger.displayAmount
import tally.core.model.Money

/** What you chose in the picker. [always] is null unless you ticked "Always put … here". */
data class CategoryChoice(val category: CategoryEntity, val always: AlwaysRule?)

data class AlwaysRule(val maxAmountCents: Long?, val applyToPast: Boolean)

/**
 * Pick a category for a capture, optionally turning the choice into a rule for its merchant.
 * [merchantName] null hides the rule options (nothing to match on).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    transaction: TransactionEntity,
    merchantName: String?,
    categories: List<CategoryEntity>,
    onPick: (CategoryChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var always by remember { mutableStateOf(false) }
    // Prefilled with the amount rounded up to the next dollar, as a sensible cap.
    val cap = ((transaction.amountCents + 99) / 100) * 100
    var capped by remember { mutableStateOf(false) }
    var applyToPast by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${merchantName ?: "Card purchase"} · ${displayAmount(transaction.amountCents)}",
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { category ->
                    FilterChip(
                        selected = category.id == transaction.categoryId,
                        onClick = {
                            onPick(
                                CategoryChoice(
                                    category,
                                    if (always) AlwaysRule(cap.takeIf { capped }, applyToPast) else null,
                                ),
                            )
                        },
                        label = { Text(category.name) },
                    )
                }
            }
            if (merchantName != null) {
                CheckRow("Always put $merchantName here", always) { always = it }
                if (always) {
                    Column(Modifier.padding(start = 32.dp)) {
                        CheckRow("Only up to ${Money.format(cap)}", capped) { capped = it }
                        CheckRow("Also change past $merchantName purchases", applyToPast) { applyToPast = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
