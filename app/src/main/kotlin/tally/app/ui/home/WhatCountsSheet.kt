package tally.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import tally.app.data.CategoryEntity
import tally.app.data.inTreeOrder
import tally.app.data.isIncome
import tally.app.ui.components.EditorSheet
import tally.app.ui.components.FieldHint
import tally.app.ui.components.RowDivider
import tally.app.ui.theme.Tally
import tally.core.model.Money

/**
 * Which categories count toward the everyday number on Home and the widget. Switching a
 * category off takes its subcategories with it. [monthCents] is this month's spending per
 * category id (a parent's own transactions only, not its children's).
 */
@Composable
fun WhatCountsSheet(
    categories: List<CategoryEntity>,
    monthCents: Map<String, Long>,
    onSetCounted: (id: String, counted: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val tree = categories.filter { !it.deleted && !categories.isIncome(it.id) }.inTreeOrder()
    val byId = tree.associateBy { it.id }
    EditorSheet(title = "What counts", onDismiss = onDismiss, primary = "Done", onPrimary = onDismiss) {
        FieldHint(
            "The number on Home and the widget leaves out what's switched off: fixed costs like rent and bills. " +
                "Everything still shows in the month's details.",
        )
        Column(Modifier.padding(top = 4.dp)) {
            tree.forEachIndexed { index, category ->
                val parent = category.parentId?.let { byId[it] }
                val parentOff = parent != null && !parent.counted
                val total = if (parent == null) {
                    tree.filter { it.id == category.id || it.parentId == category.id }.sumOf { monthCents[it.id] ?: 0L }
                } else {
                    monthCents[category.id] ?: 0L
                }
                if (index > 0 && parent == null) RowDivider(start = 0.dp)
                CountRow(
                    name = category.name,
                    detail = if (total != 0L) "${Money.format(total)} this month" else null,
                    checked = category.counted && !parentOff,
                    enabled = !parentOff,
                    child = parent != null,
                    onChange = { onSetCounted(category.id, it) },
                )
            }
        }
    }
}

@Composable
private fun CountRow(name: String, detail: String?, checked: Boolean, enabled: Boolean, child: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(start = if (child) 20.dp else 0.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = if (child) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else Tally.colors.faint,
            )
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Tally.colors.muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.secondary,
                checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                uncheckedTrackColor = Tally.colors.chip,
                uncheckedBorderColor = Tally.colors.chip,
                uncheckedThumbColor = Tally.colors.faint,
            ),
        )
    }
}
