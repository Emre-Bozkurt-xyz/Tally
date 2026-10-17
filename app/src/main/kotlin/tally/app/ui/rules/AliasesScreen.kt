package tally.app.ui.rules

import tally.app.ui.components.AddButton
import tally.app.ui.components.TallyTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.data.AliasEntity
import tally.app.data.RulesRepository
import tally.core.normalize.MerchantNormalizer
import tally.core.rules.Rule

/** Aliases rename messy merchant strings ("AMZN MKTP CA*2X4" → "Amazon"). First match wins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliasesScreen(rules: RulesRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val list by remember { rules.observeAliases() }.collectAsStateWithLifecycle(initialValue = emptyList())
    // null = closed; Editing(null) = new.
    var editing by remember { mutableStateOf<Editing?>(null) }

    Scaffold(
        topBar = {
            TallyTopBar("Aliases", onBack)
        },
        floatingActionButton = {
            AddButton("Add alias", onClick = { editing = Editing(null) })
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    "A raw merchant string matching the pattern (a regex, anywhere in the string) gets the " +
                        "name instead of the automatic cleanup. Rules then match that name. Saving renames past " +
                        "purchases too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(list, key = { it.id }) { alias ->
                ListItem(
                    modifier = Modifier.clickable { editing = Editing(alias) },
                    headlineContent = { Text(alias.merchantName) },
                    supportingContent = { Text(alias.pattern, fontFamily = FontFamily.Monospace) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { scope.launch { rules.moveAlias(alias.id, -1) } }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(onClick = { scope.launch { rules.moveAlias(alias.id, 1) } }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { current ->
        AliasDialog(
            alias = current.alias,
            rules = rules,
            onDismiss = { editing = null },
        )
    }
}

private data class Editing(val alias: AliasEntity?)

@Composable
private fun AliasDialog(alias: AliasEntity?, rules: RulesRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pattern by remember { mutableStateOf(alias?.pattern.orEmpty()) }
    var name by remember { mutableStateOf(alias?.merchantName.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val recent by produceState(initialValue = emptyList<String>()) { value = rules.recentMerchantRaws() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (alias == null) "New alias" else "Edit alias") },
        text = {
            Column {
                if (alias == null && recent.isNotEmpty()) {
                    FromRecent(recent) { raw ->
                        pattern = "(?i)^" + Rule.literal(raw)
                        name = MerchantNormalizer().normalize(raw)
                        error = null
                    }
                }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it; error = null },
                    label = { Text("Pattern (regex)") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Merchant name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = rules.saveAlias(alias?.id, pattern.trim(), name)
                    if (error == null) onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (alias != null) {
                    TextButton(onClick = { scope.launch { rules.deleteAlias(alias.id); onDismiss() } }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** "From a recent purchase": prefills the pattern with that exact raw string. */
@Composable
private fun FromRecent(recent: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("From a recent purchase")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            recent.forEach { raw ->
                DropdownMenuItem(text = { Text(raw) }, onClick = { onPick(raw); open = false })
            }
        }
    }
}
