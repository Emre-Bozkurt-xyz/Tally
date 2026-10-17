package tally.app.ui.categories

import tally.app.ui.components.AddButton
import tally.app.ui.components.TallyTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import tally.core.model.Money
import tally.app.data.CategoryEntity
import tally.app.data.CategoryRepository
import tally.app.data.LedgerRepository
import tally.app.data.inTreeOrder

/** Add, rename, move, reorder, pin and delete categories. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(ledger: LedgerRepository, repository: CategoryRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val categories by remember { ledger.observeCategories() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val tree = remember(categories) { categories.inTreeOrder() }
    val parentIds = remember(categories) { categories.mapNotNull { it.parentId }.toSet() }
    // null = closed; Editing(null) = adding a new category.
    var editing by remember { mutableStateOf<Editing?>(null) }

    /** Runs a repository call and shows its error, if any. */
    fun run(action: suspend () -> String?) {
        scope.launch { action()?.let { snackbar.showSnackbar(it) } }
    }

    Scaffold(
        topBar = {
            TallyTopBar("Categories", onBack)
        },
        floatingActionButton = {
            AddButton("Add category", onClick = { editing = Editing(null) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    "Starred categories come first in Quick add. Tap a category to rename, move or delete it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(tree, key = { it.id }) { category ->
                CategoryRow(
                    category = category,
                    // Pinning only matters for categories offered as quick-entry chips.
                    pinnable = category.id !in parentIds,
                    onClick = { editing = Editing(category) },
                    onTogglePin = { run { repository.setPinned(category.id, !category.pinned) } },
                    onMoveUp = { run { repository.move(category.id, -1) } },
                    onMoveDown = { run { repository.move(category.id, 1) } },
                )
            }
        }
    }

    editing?.let { current ->
        val category = current.category
        CategoryDialog(
            category = category,
            parents = tree.filter { it.parentId == null && it.id != category?.id },
            canHaveParent = category == null || category.id !in parentIds,
            onSave = { name, parentId, budgetCents ->
                if (category == null) {
                    repository.add(name, parentId)
                } else {
                    repository.update(category.id, name, parentId) ?: repository.setBudget(category.id, budgetCents)
                }
            },
            onDelete = category?.let { { repository.delete(it.id) } },
            onDismiss = { editing = null },
        )
    }
}

private data class Editing(val category: CategoryEntity?)

@Composable
private fun CategoryRow(
    category: CategoryEntity,
    pinnable: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val child = category.parentId != null
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (child) Spacer(Modifier.width(24.dp))
                Column {
                    Text(
                        category.name,
                        style = if (child) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    )
                    category.monthlyBudgetCents?.let {
                        Text(
                            "${Money.format(it)} a month",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        trailingContent = {
            Row {
                if (pinnable) {
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = if (category.pinned) "Unpin" else "Pin",
                            tint = if (category.pinned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        )
                    }
                }
                IconButton(onClick = onMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
            }
        },
    )
}

/** Add (category = null) or edit. [onSave] and [onDelete] return an error message, or null when done. */
@Composable
private fun CategoryDialog(
    category: CategoryEntity?,
    parents: List<CategoryEntity>,
    canHaveParent: Boolean,
    onSave: suspend (name: String, parentId: String?, budgetCents: Long?) -> String?,
    onDelete: (suspend () -> String?)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(category?.name.orEmpty()) }
    var parentId by remember { mutableStateOf(category?.parentId) }
    var budget by remember {
        mutableStateOf(category?.monthlyBudgetCents?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" }.orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit(action: suspend () -> String?) {
        scope.launch {
            error = action()
            if (error == null) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (category == null) "New category" else "Edit category") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (canHaveParent) {
                    ParentPicker(
                        parents = parents,
                        selectedId = parentId,
                        onPick = {
                            parentId = it
                            error = null
                        },
                    )
                } else {
                    Text(
                        "Has subcategories, so it stays top level.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (category != null) {
                    OutlinedTextField(
                        value = budget,
                        onValueChange = {
                            budget = it
                            error = null
                        },
                        label = { Text("Monthly budget (optional)") },
                        supportingText = {
                            Text(
                                if (category.parentId == null) {
                                    "Counts for the whole group; subcategory budgets are then ignored in totals."
                                } else {
                                    "Counted in totals only if the parent has no budget."
                                },
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = parseBudget(budget)
                if (cents == null) {
                    error = "Budgets look like 400 or 400.00"
                } else {
                    submit { onSave(name, parentId, cents.getOrNull()) }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { submit(onDelete) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ParentPicker(parents: List<CategoryEntity>, selectedId: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selectedName = parents.find { it.id == selectedId }?.name ?: "None (top level)"
    Box(Modifier.padding(top = 8.dp)) {
        TextButton(onClick = { open = true }) {
            Text("Parent: $selectedName")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("None (top level)") },
                onClick = {
                    onPick(null)
                    open = false
                },
            )
            parents.forEach { parent ->
                DropdownMenuItem(
                    text = { Text(parent.name) },
                    onClick = {
                        onPick(parent.id)
                        open = false
                    },
                )
            }
        }
    }
}

/** Blank is no budget (success(null)); otherwise dollars to cents, or null if unreadable. */
private fun parseBudget(text: String): Result<Long?>? {
    val trimmed = text.trim().removePrefix("$")
    if (trimmed.isEmpty()) return Result.success(null)
    val match = Regex("(\\d+)(?:\\.(\\d{1,2}))?").matchEntire(trimmed) ?: return null
    return Result.success(match.groupValues[1].toLong() * 100 + match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong())
}
