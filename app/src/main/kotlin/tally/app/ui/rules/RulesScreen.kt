package tally.app.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.data.AccountEntity
import tally.app.data.CategoryEntity
import tally.app.data.LedgerRepository
import tally.app.data.RuleEntity
import tally.app.data.RulesRepository
import tally.app.data.inTreeOrder
import tally.core.model.Money
import java.util.UUID

/** Rules in priority order (first match wins): reorder, edit, add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    rules: RulesRepository,
    ledger: LedgerRepository,
    onEditToml: () -> Unit,
    onOpenAliases: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val list by remember { rules.observeRules() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by remember { ledger.observeCategories() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by remember { ledger.observeAccounts() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val paths = remember(categories) { categoryPaths(categories) }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    var editing by remember { mutableStateOf<RuleEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = onOpenAliases) { Text("Aliases") }
                    TextButton(onClick = onEditToml) { Text("TOML") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = newRule(categories) }) {
                Icon(Icons.Default.Add, contentDescription = "Add rule")
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    "Checked top to bottom; the first match wins and logs the purchase without asking. " +
                        "Patterns are case-insensitive regexes for the whole merchant name (\"Uber.*\" also " +
                        "matches Uber Eats). The quickest way to add one: \"Always put … here\" in the inbox.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(list, key = { it.id }) { rule ->
                ListItem(
                    modifier = Modifier.clickable { editing = rule },
                    headlineContent = { Text(rule.merchantPattern, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text(describe(rule, paths, accountNames)) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { scope.launch { rules.moveRule(rule.id, -1) } }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(onClick = { scope.launch { rules.moveRule(rule.id, 1) } }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { rule ->
        RuleDialog(
            rule = rule,
            isNew = list.none { it.id == rule.id },
            categories = categories,
            paths = paths,
            accounts = accounts,
            onSave = { rules.saveRule(it) },
            onDelete = { rules.deleteRule(rule.id) },
            onDismiss = { editing = null },
        )
    }
}

private fun newRule(categories: List<CategoryEntity>) = RuleEntity(
    id = UUID.randomUUID().toString(),
    priority = 0,
    merchantPattern = "",
    minAmountCents = null,
    maxAmountCents = null,
    accountId = null,
    categoryId = categories.inTreeOrder().firstOrNull()?.id.orEmpty(),
    tagsCsv = null,
    updatedAt = 0,
)

private fun describe(rule: RuleEntity, paths: Map<String, String>, accounts: Map<String, String>) = listOfNotNull(
    "→ ${paths[rule.categoryId] ?: "(deleted category)"}",
    rule.minAmountCents?.let { "from ${Money.format(it)}" },
    rule.maxAmountCents?.let { "up to ${Money.format(it)}" },
    rule.accountId?.let { accounts[it] },
    rule.tagsCsv?.split(',')?.joinToString(" ") { "#$it" },
).joinToString(" · ")

/** "Food › Coffee" style labels for every category. */
internal fun categoryPaths(categories: List<CategoryEntity>): Map<String, String> {
    val byId = categories.associateBy { it.id }
    return categories.associate { c -> c.id to (c.parentId?.let { "${byId[it]?.name} › ${c.name}" } ?: c.name) }
}

/** "15.00" or "15" to cents; blank is no limit; null result with error text otherwise. */
private fun parseAmount(text: String): Result<Long?> {
    val trimmed = text.trim().removePrefix("$")
    if (trimmed.isEmpty()) return Result.success(null)
    val match = Regex("(\\d+)(?:\\.(\\d{1,2}))?").matchEntire(trimmed)
        ?: return Result.failure(IllegalArgumentException("Amounts look like 15 or 15.00"))
    return Result.success(match.groupValues[1].toLong() * 100 + match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong())
}

private fun amountText(cents: Long?) = cents?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" }.orEmpty()

@Composable
private fun RuleDialog(
    rule: RuleEntity,
    isNew: Boolean,
    categories: List<CategoryEntity>,
    paths: Map<String, String>,
    accounts: List<AccountEntity>,
    onSave: suspend (RuleEntity) -> String?,
    onDelete: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pattern by remember { mutableStateOf(rule.merchantPattern) }
    var categoryId by remember { mutableStateOf(rule.categoryId) }
    var min by remember { mutableStateOf(amountText(rule.minAmountCents)) }
    var max by remember { mutableStateOf(amountText(rule.maxAmountCents)) }
    var accountId by remember { mutableStateOf(rule.accountId) }
    var tags by remember { mutableStateOf(rule.tagsCsv?.replace(",", ", ").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val minCents = parseAmount(min)
        val maxCents = parseAmount(max)
        val bad = minCents.exceptionOrNull() ?: maxCents.exceptionOrNull()
        if (bad != null) {
            error = bad.message
            return
        }
        val tagList = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val updated = rule.copy(
            merchantPattern = pattern.trim(),
            categoryId = categoryId,
            minAmountCents = minCents.getOrNull(),
            maxAmountCents = maxCents.getOrNull(),
            accountId = accountId,
            tagsCsv = tagList.joinToString(",").ifEmpty { null },
        )
        scope.launch {
            error = onSave(updated)
            if (error == null) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it; error = null },
                    label = { Text("Merchant pattern") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "Regex for the whole name, e.g. Tim Hortons or Uber.*") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Picker(
                    label = "Category",
                    selected = paths[categoryId] ?: "Choose",
                    options = categories.inTreeOrder().map { it.id to (paths[it.id] ?: it.name) },
                    onPick = { categoryId = it.orEmpty() },
                )
                Row {
                    OutlinedTextField(
                        value = min,
                        onValueChange = { min = it; error = null },
                        label = { Text("From $") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    OutlinedTextField(
                        value = max,
                        onValueChange = { max = it; error = null },
                        label = { Text("Up to $") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Picker(
                    label = "Account",
                    selected = accounts.find { it.id == accountId }?.name ?: "Any",
                    options = listOf<Pair<String?, String>>(null to "Any") + accounts.map { it.id to it.name },
                    onPick = { accountId = it },
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::save, enabled = categoryId.isNotEmpty()) { Text("Save") } },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { scope.launch { onDelete(); onDismiss() } }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Picker(label: String, selected: String, options: List<Pair<String?, String>>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(vertical = 4.dp)) {
        TextButton(onClick = { open = true }) {
            Text("$label: $selected")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(id); open = false })
            }
        }
    }
}
