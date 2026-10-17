package tally.app.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tally.app.data.RulesRepository
import tally.core.rules.TomlImport

/**
 * Categories, aliases and rules as one text file, for bulk edits. Save stays off until the
 * current text validates; saving replaces everything (categories keep their history by name).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TomlEditorScreen(rules: RulesRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    // The text that last passed validation; Save is allowed only while it's still the text.
    var validText by rememberSaveable { mutableStateOf<String?>(null) }
    var messages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) { if (text == null) text = rules.exportToml() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rules as TOML") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = {
                        val current = text ?: return@TextButton
                        scope.launch {
                            when (val result = rules.validateToml(current)) {
                                is TomlImport.Ok -> {
                                    validText = current
                                    messages = listOf(
                                        "Valid: ${result.document.categories.size} categories, " +
                                            "${result.document.aliases.size} aliases, ${result.document.rules.size} rules",
                                    )
                                }
                                is TomlImport.Invalid -> {
                                    validText = null
                                    messages = result.errors.map { it.toString() }
                                }
                            }
                        }
                    }) { Text("Validate") }
                    TextButton(
                        enabled = text != null && text == validText,
                        onClick = {
                            val current = text ?: return@TextButton
                            scope.launch {
                                if (rules.importToml(current) is TomlImport.Ok) onBack()
                            }
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            messages.forEach { message ->
                Text(
                    message,
                    color = if (validText != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            Row(Modifier.padding(8.dp)) {
                OutlinedTextField(
                    value = text.orEmpty(),
                    onValueChange = { text = it },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp),
                )
            }
        }
    }
}
