package tally.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.data.AliasEntity
import tally.app.data.RulesRepository
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
import tally.app.ui.components.SectionHeader
import tally.app.ui.components.SoftTextField
import tally.app.ui.components.TallyTopBar
import tally.app.ui.components.bleed
import tally.app.ui.components.groupShape
import tally.app.ui.theme.GeistMono
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import tally.core.normalize.MerchantNormalizer
import tally.core.rules.Rule

/** Aliases rename messy merchant strings ("AMZN MKTP CA*2X4" → "Amazon"). First match wins. */
@Composable
fun AliasesScreen(rules: RulesRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val list by remember { rules.observeAliases() }.collectAsStateWithLifecycle(initialValue = emptyList())
    // null = closed; Editing(null) = new.
    var editing by remember { mutableStateOf<Editing?>(null) }
    var reordering by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TallyTopBar("Aliases", onBack) {
                CircleIconButton(
                    TallyIcons.Reorder,
                    if (reordering) "Done reordering" else "Reorder",
                    { reordering = !reordering },
                    background = if (reordering) MaterialTheme.colorScheme.primary else Tally.colors.chip,
                    tint = if (reordering) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        floatingActionButton = { if (!reordering) AddButton("Add alias", onClick = { editing = Editing(null) }) },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp)) {
            item(key = "intro") {
                Text(
                    "Turn messy bank text into one clean merchant name. Rules then match the clean name. " +
                        "Saving renames past purchases too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.colors.muted,
                    modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 8.dp),
                )
            }
            item(key = "count") { SectionHeader(if (list.isEmpty()) "No aliases yet" else "${list.size} aliases") }
            itemsIndexed(list, key = { _, it -> it.id }) { index, alias ->
                Column(Modifier.padding(horizontal = ScreenPadding).animateItem()) {
                    Row(
                        Modifier
                            .clip(groupShape(index, list.size))
                            .fillMaxWidth()
                            .background(Tally.colors.card)
                            .clickable(enabled = !reordering) { editing = Editing(alias) }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LetterAvatar(alias.merchantName, alias.merchantName, size = 36.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(alias.merchantName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                alias.pattern,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono),
                                color = Tally.colors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        if (reordering) {
                            ReorderButtons(
                                canUp = index > 0,
                                canDown = index < list.lastIndex,
                                onUp = { scope.launch { rules.moveAlias(alias.id, -1) } },
                                onDown = { scope.launch { rules.moveAlias(alias.id, 1) } },
                            )
                        } else {
                            Icon(TallyIcons.Forward, null, Modifier.size(16.dp), tint = Tally.colors.faint)
                        }
                    }
                    if (index < list.lastIndex) RowDivider(start = 66.dp)
                }
            }
        }
    }

    editing?.let { current ->
        AliasSheet(alias = current.alias, rules = rules, onDismiss = { editing = null })
    }
}

private data class Editing(val alias: AliasEntity?)

@Composable
private fun AliasSheet(alias: AliasEntity?, rules: RulesRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pattern by remember { mutableStateOf(alias?.pattern.orEmpty()) }
    var name by remember { mutableStateOf(alias?.merchantName.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val recent by produceState(initialValue = emptyList<String>()) { value = rules.recentMerchantRaws() }
    val matches = remember(pattern, recent) {
        val p = pattern.trim()
        if (p.isEmpty() || Rule.patternError(p) != null) null else Regex(p).let { r -> recent.filter { r.containsMatchIn(it) } }
    }

    EditorSheet(
        title = if (alias == null) "New alias" else "Edit alias",
        onDismiss = onDismiss,
        primary = "Save",
        onPrimary = {
            scope.launch {
                error = rules.saveAlias(alias?.id, pattern.trim(), name)
                if (error == null) onDismiss()
            }
        },
        primaryEnabled = pattern.isNotBlank() && name.isNotBlank(),
        onDelete = alias?.let { { scope.launch { rules.deleteAlias(it.id); onDismiss() } } },
        error = error,
    ) {
        if (alias == null && recent.isNotEmpty()) {
            FieldLabel("From a recent purchase")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                modifier = Modifier.bleed(20.dp),
            ) {
                items(recent) { raw ->
                    PillChip(raw, selected = false, onClick = {
                        pattern = "(?i)^" + Rule.literal(raw)
                        name = MerchantNormalizer().normalize(raw)
                        error = null
                    })
                }
            }
        }
        FieldLabel("Bank text contains")
        SoftTextField(pattern, { pattern = it; error = null }, "(?i)^amzn mktp", leading = TallyIcons.Code)
        FieldHint(
            when {
                pattern.isBlank() -> "A regex found anywhere in the raw text. (?i) ignores capitalization."
                matches == null -> "That pattern isn't valid yet"
                matches.isEmpty() -> "Matches none of your recent purchases"
                else -> "Matches ${matches.size} recent: " + matches.take(3).joinToString(", ") + if (matches.size > 3) ", …" else ""
            },
            color = if (matches == null && pattern.isNotBlank()) Tally.colors.over else Tally.colors.muted,
        )
        FieldLabel("Call it")
        SoftTextField(name, { name = it; error = null }, "Amazon", leading = TallyIcons.Store)
    }
}
