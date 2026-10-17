package tally.app.ui.rules

import tally.app.ui.components.PillStyle
import tally.app.ui.components.PillButton
import tally.app.ui.components.TallyTopBar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import tally.app.ui.components.ScreenPadding
import tally.app.ui.theme.GeistMono
import tally.app.ui.theme.Tally
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
            TallyTopBar("Rules as TOML", onBack) {
                    PillButton("Check", onClick = {
                        val current = text ?: return@PillButton
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
                    }, style = PillStyle.Soft, height = 40.dp)
                    PillButton(
                        "Save",
                        enabled = text != null && text == validText,
                        onClick = {
                            val current = text ?: return@PillButton
                            scope.launch {
                                if (rules.importToml(current) is TomlImport.Ok) onBack()
                            }
                        },
                        height = 40.dp,
                    )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            messages.forEach { message ->
                Text(
                    message,
                    color = if (validText != null) Tally.colors.positive else Tally.colors.over,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 2.dp),
                )
            }
            BasicTextField(
                value = text.orEmpty(),
                onValueChange = { text = it },
                textStyle = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .padding(horizontal = ScreenPadding, vertical = 10.dp)
                    .fillMaxWidth()
                    .heightIn(min = 400.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Tally.colors.card)
                    .padding(16.dp),
            )
        }
    }
}
