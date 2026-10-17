package tally.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.data.CategoryEntity
import tally.app.data.CategoryRepository
import tally.app.data.LedgerRepository
import tally.app.data.inTreeOrder
import tally.app.ui.components.AddButton
import tally.app.ui.components.CircleIconButton
import tally.app.ui.components.EditorSheet
import tally.app.ui.components.FieldHint
import tally.app.ui.components.FieldLabel
import tally.app.ui.components.LetterAvatar
import tally.app.ui.components.PillChip
import tally.app.ui.components.ReorderButtons
import tally.app.ui.components.RowDivider
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.SoftTextField
import tally.app.ui.components.TallyTopBar
import tally.app.ui.components.groupShape
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import tally.core.model.Money

/** Categories in their groups: star for quick add, tap to edit, reorder mode to move. */
@Composable
fun CategoriesScreen(ledger: LedgerRepository, repository: CategoryRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val categories by remember { ledger.observeCategories() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val tree = remember(categories) { categories.inTreeOrder() }
    val parents = remember(tree) { tree.filter { it.parentId == null } }
    val parentIds = remember(categories) { categories.mapNotNull { it.parentId }.toSet() }
    // null = closed; Editing(null) = adding a new category.
    var editing by remember { mutableStateOf<Editing?>(null) }
    var reordering by remember { mutableStateOf(false) }

    /** Runs a repository call and shows its error, if any. */
    fun run(action: suspend () -> String?) {
        scope.launch { action()?.let { snackbar.showSnackbar(it) } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TallyTopBar("Categories", onBack) {
                CircleIconButton(
                    TallyIcons.Reorder,
                    if (reordering) "Done reordering" else "Reorder",
                    { reordering = !reordering },
                    background = if (reordering) MaterialTheme.colorScheme.primary else Tally.colors.chip,
                    tint = if (reordering) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        floatingActionButton = { if (!reordering) AddButton("Add category", onClick = { editing = Editing(null) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp)) {
            item(key = "intro") {
                Text(
                    "Starred ones come first in quick add and on the widget. Tap one to rename, regroup, budget or delete it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.colors.muted,
                    modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 14.dp),
                )
            }
            parents.forEachIndexed { p, parent ->
                val rows = listOf(parent) + tree.filter { it.parentId == parent.id }
                item(key = parent.id) {
                    Column(Modifier.padding(horizontal = ScreenPadding).padding(bottom = 12.dp).animateItem()) {
                        rows.forEachIndexed { i, category ->
                            val siblings = if (i == 0) parents else rows.drop(1)
                            val at = siblings.indexOf(category)
                            CategoryRow(
                                category = category,
                                child = i > 0,
                                pinnable = category.id !in parentIds,
                                reordering = reordering,
                                canUp = at > 0,
                                canDown = at < siblings.lastIndex,
                                onClick = { editing = Editing(category) },
                                onTogglePin = { run { repository.setPinned(category.id, !category.pinned) } },
                                onUp = { run { repository.move(category.id, -1) } },
                                onDown = { run { repository.move(category.id, 1) } },
                                modifier = Modifier.clip(groupShape(i, rows.size)),
                            )
                            if (i < rows.lastIndex) RowDivider(start = if (i == 0) 66.dp else 66.dp)
                        }
                    }
                }
            }
        }
    }

    editing?.let { current ->
        val category = current.category
        CategorySheet(
            category = category,
            parents = parents.filter { it.id != category?.id },
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
    child: Boolean,
    pinnable: Boolean,
    reordering: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Tally.colors.card)
            .clickable(enabled = !reordering, onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = if (child) 8.dp else 12.dp, bottom = if (child) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (child) {
            Spacer(Modifier.width(50.dp))
        } else {
            LetterAvatar(category.name, category.id, size = 36.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                category.name,
                style = if (child) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
            )
            category.monthlyBudgetCents?.let {
                Text("${Money.format(it)} a month", style = MaterialTheme.typography.bodySmall, color = Tally.colors.muted)
            }
        }
        when {
            reordering -> ReorderButtons(canUp, canDown, onUp, onDown)
            pinnable -> CircleIconButton(
                if (category.pinned) TallyIcons.StarFilled else TallyIcons.Star,
                if (category.pinned) "Unstar ${category.name}" else "Star ${category.name}",
                onTogglePin,
                size = 38.dp,
                background = Color.Transparent,
                tint = if (category.pinned) Tally.colors.warn else Tally.colors.faint,
            )
        }
    }
}

/** Add (category = null) or edit. [onSave] and [onDelete] return an error message, or null when done. */
@Composable
private fun CategorySheet(
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

    EditorSheet(
        title = if (category == null) "New category" else "Edit category",
        onDismiss = onDismiss,
        primary = "Save",
        onPrimary = {
            val cents = parseBudget(budget)
            if (cents == null) error = "Budgets look like 400 or 400.00" else submit { onSave(name, parentId, cents.getOrNull()) }
        },
        primaryEnabled = name.isNotBlank(),
        onDelete = onDelete?.let { delete -> { submit(delete) } },
        error = error,
    ) {
        FieldLabel("Name")
        SoftTextField(name, { name = it; error = null }, "Groceries")

        FieldLabel("Group")
        if (canHaveParent) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PillChip("Top level", selected = parentId == null, onClick = { parentId = null; error = null })
                parents.forEach { parent ->
                    PillChip(parent.name, selected = parentId == parent.id, onClick = { parentId = parent.id; error = null })
                }
            }
        } else {
            FieldHint("Has subcategories, so it stays top level.")
        }

        if (category != null) {
            FieldLabel("Monthly budget (optional)")
            SoftTextField(
                budget,
                { budget = it; error = null },
                "No budget",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            FieldHint(
                if (category.parentId == null) {
                    "Counts for the whole group; subcategory budgets are then left out of totals."
                } else {
                    "Counted in totals only if the group has no budget."
                },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Blank is no budget (success(null)); otherwise dollars to cents, or null if unreadable. */
private fun parseBudget(text: String): Result<Long?>? {
    val trimmed = text.trim().removePrefix("$")
    if (trimmed.isEmpty()) return Result.success(null)
    val match = Regex("(\\d+)(?:\\.(\\d{1,2}))?").matchEntire(trimmed) ?: return null
    return Result.success(match.groupValues[1].toLong() * 100 + match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong())
}
