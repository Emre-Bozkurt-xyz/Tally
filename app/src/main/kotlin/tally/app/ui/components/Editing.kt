package tally.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import tally.app.data.CategoryEntity
import tally.app.data.inTreeOrder
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons

/** The small pill at the top of a sheet. */
@Composable
fun SheetHandle() {
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(Tally.colors.faint.copy(alpha = 0.5f)),
    )
}

/**
 * A bottom sheet for editing one thing: a title, the fields, then Delete (when [onDelete]
 * is given) and the main button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSheet(
    title: String,
    onDismiss: () -> Unit,
    primary: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    onDelete: (() -> Unit)? = null,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 6.dp))
            content()
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = Tally.colors.over, modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    PillButton("Delete", onDelete, Modifier.weight(1f), style = PillStyle.Danger, icon = TallyIcons.Trash, height = 52.dp)
                }
                PillButton(primary, onPrimary, Modifier.weight(1f), enabled = primaryEnabled, icon = TallyIcons.Check, height = 52.dp)
            }
        }
    }
}

/** A mono label above a field, with room above it to separate groups. */
@Composable
fun FieldLabel(text: String) {
    MonoLabel(text, Modifier.padding(top = 8.dp))
}

/** Help under a field. */
@Composable
fun FieldHint(text: String, color: androidx.compose.ui.graphics.Color = Tally.colors.muted) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 4.dp))
}

/** Up and down buttons for reorder mode. */
@Composable
fun ReorderButtons(canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CircleIconButton(TallyIcons.Up, "Move up", onUp, size = 34.dp, enabled = canUp)
        CircleIconButton(TallyIcons.Down, "Move down", onDown, size = 34.dp, enabled = canDown)
    }
}

/** Every category as chips, one group per top-level category (which is itself the first chip). */
@Composable
fun CategoryChooser(categories: List<CategoryEntity>, selectedId: String?, onPick: (String) -> Unit) {
    val tree = categories.inTreeOrder()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tree.filter { it.parentId == null }.forEach { parent ->
            val children = tree.filter { it.parentId == parent.id }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                PillChip(
                    if (children.isEmpty()) parent.name else "${parent.name} (any)",
                    selected = parent.id == selectedId,
                    onClick = { onPick(parent.id) },
                    leading = if (children.isEmpty()) null else TallyIcons.Shapes,
                )
                children.forEach { child ->
                    PillChip(child.name, selected = child.id == selectedId, onClick = { onPick(child.id) })
                }
            }
        }
    }
}
