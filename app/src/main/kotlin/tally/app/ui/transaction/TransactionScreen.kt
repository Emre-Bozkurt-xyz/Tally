package tally.app.ui.transaction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.capture.CaptureNotifier
import tally.app.data.CategoryEntity
import tally.app.data.LedgerRepository
import tally.app.data.RulesRepository
import tally.app.data.TagEntity
import tally.app.data.chipCategories
import tally.app.quickentry.QuickEntryActivity
import tally.app.ui.inbox.CategoryPickerSheet
import tally.app.ui.ledger.dayLabel
import tally.app.ui.ledger.displayAmount
import tally.app.ui.ledger.occurredAtLocal
import tally.core.capture.WatchedPackages
import tally.core.model.Money
import tally.core.model.Source
import tally.core.model.TransactionStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TIME_FORMAT = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** One transaction: details, tags, splits, and the notifications it came from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionScreen(
    id: String,
    ledger: LedgerRepository,
    rules: RulesRepository,
    onOpenRawEvent: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val transaction by remember(id) { ledger.observeTransaction(id) }.collectAsStateWithLifecycle(initialValue = null)
    val categories by remember { ledger.observeAllCategories() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeCategories by remember { ledger.observeCategories() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by remember { ledger.observeAllAccounts() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val merchants by remember { ledger.observeAllMerchants() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tags by remember(id) { ledger.observeTagsFor(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val allTags by remember { ledger.observeTags() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val splits by remember(id) { ledger.observeSplitsFor(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val notifications by remember(id) { ledger.observeNotificationsFor(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val chips = remember(activeCategories) { activeCategories.chipCategories() }

    var addingTag by remember { mutableStateOf(false) }
    var addingSplit by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            ledger.deleteTransaction(id)
                            CaptureNotifier.cancel(context, id)
                            onBack()
                        }
                    }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val t = transaction ?: return@Scaffold
        val merchantName = t.merchantId?.let { mid -> merchants.find { it.id == mid }?.name } ?: t.merchantRaw
        val local = t.occurredAtLocal()

        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(displayAmount(t.amountCents), style = MaterialTheme.typography.displaySmall)
            Text(merchantName ?: categoryNames[t.categoryId] ?: "Uncategorized", style = MaterialTheme.typography.titleLarge)
            Text(
                listOfNotNull(
                    categoryNames[t.categoryId] ?: "Uncategorized",
                    accounts.find { it.id == t.accountId }?.name,
                    "${dayLabel(local.toLocalDate(), LocalDate.now())} ${TIME_FORMAT.format(local)}",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
            )
            t.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                listOfNotNull(
                    sourceLabel(t.source),
                    "Needs review".takeIf { t.status == TransactionStatus.CAPTURED.name },
                    t.categorySource?.let { "category by ${it.lowercase()}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { context.startActivity(QuickEntryActivity.intent(context, transactionId = id)) }) {
                    Text("Edit")
                }
                if (t.merchantId != null) {
                    OutlinedButton(onClick = { picking = true }) { Text("Always do this…") }
                }
            }

            HorizontalDivider()
            SectionTitle("Tags")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { scope.launch { ledger.removeTag(id, tag.id) } },
                        label = { Text("#${tag.name}") },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove ${tag.name}") },
                    )
                }
                AssistChip(onClick = { addingTag = true }, label = { Text("Add tag") })
            }

            if (t.amountCents > 0) {
                HorizontalDivider()
                SectionTitle("Split")
                splits.forEach { split ->
                    ListItem(
                        headlineContent = {
                            Text(split.owedBy?.let { "Owed by $it" } ?: categoryNames[split.categoryId] ?: "Uncategorized")
                        },
                        trailingContent = {
                            Row {
                                Text(Money.format(split.amountCents), modifier = Modifier.padding(top = 12.dp))
                                IconButton(onClick = { scope.launch { ledger.deleteSplit(split.id) } }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove split")
                                }
                            }
                        },
                    )
                }
                val remainder = t.amountCents - splits.sumOf { it.amountCents }
                if (splits.isNotEmpty()) {
                    Text(
                        "${Money.format(remainder)} stays in ${categoryNames[t.categoryId] ?: "Uncategorized"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (remainder > 0) {
                    OutlinedButton(onClick = { addingSplit = true }) { Text("Split off part") }
                }
            }

            if (notifications.isNotEmpty()) {
                HorizontalDivider()
                SectionTitle("From notifications")
                notifications.forEach { event ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenRawEvent(event.id) },
                        overlineContent = { Text(WatchedPackages.displayName(event.source)) },
                        headlineContent = { Text(event.title.orEmpty()) },
                        supportingContent = { Text(event.bigText ?: event.text.orEmpty(), maxLines = 3) },
                    )
                }
            }
        }

        if (addingTag) {
            TagDialog(
                existing = allTags.filter { all -> tags.none { it.id == all.id } },
                onAdd = { name -> ledger.addTag(id, name) },
                onDismiss = { addingTag = false },
            )
        }
        if (addingSplit) {
            SplitDialog(
                categories = chips,
                remainderCents = t.amountCents - splits.sumOf { it.amountCents },
                onAdd = { cents, categoryId, owedBy -> ledger.addSplit(id, cents, categoryId, owedBy) },
                onDismiss = { addingSplit = false },
            )
        }
        if (picking && t.merchantId != null && merchantName != null) {
            CategoryPickerSheet(
                transaction = t,
                merchantName = merchantName,
                categories = chips,
                onDismiss = { picking = false },
                onPick = { choice ->
                    picking = false
                    scope.launch {
                        ledger.saveQuickEntry(t, t.amountCents, choice.category.id, t.accountId, t.note)
                        CaptureNotifier.cancel(context, id)
                        choice.always?.let { always ->
                            val changed = rules.alwaysCategorize(t.merchantId, merchantName, choice.category.id, always.maxAmountCents, always.applyToPast)
                            snackbar.showSnackbar("Rule added: $merchantName → ${choice.category.name} ($changed updated)")
                        }
                    }
                },
            )
        }
    }
}

private fun sourceLabel(source: String): String = when (source) {
    Source.MANUAL.name -> "Entered by hand"
    Source.RBC_ALERT.name -> "Captured from RBC"
    Source.GOOGLE_WALLET.name -> "Captured from Google Wallet"
    Source.SAMSUNG_WALLET.name -> "Captured from Samsung Wallet"
    else -> "From ${source.lowercase()}"
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TagDialog(existing: List<TagEntity>, onAdd: suspend (String) -> String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun add(value: String) = scope.launch {
        error = onAdd(value)
        if (error == null) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add tag") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Tag") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    existing.forEach { tag -> AssistChip(onClick = { add(tag.name) }, label = { Text("#${tag.name}") }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { add(name) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SplitDialog(
    categories: List<CategoryEntity>,
    remainderCents: Long,
    onAdd: suspend (Long, String?, String?) -> String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var owed by remember { mutableStateOf(false) }
    var owedBy by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split off part") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it; error = null },
                    label = { Text("Amount (up to ${Money.format(remainderCents)})") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !owed, onClick = { owed = false }, label = { Text("Another category") })
                    FilterChip(selected = owed, onClick = { owed = true }, label = { Text("Someone owes me") })
                }
                if (owed) {
                    OutlinedTextField(
                        value = owedBy,
                        onValueChange = { owedBy = it; error = null },
                        label = { Text("Who") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { c ->
                            FilterChip(selected = c.id == categoryId, onClick = { categoryId = c.id }, label = { Text(c.name) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = parseCents(amount)
                if (cents == null) {
                    error = "Amounts look like 10 or 10.50"
                    return@TextButton
                }
                scope.launch {
                    error = onAdd(cents, categoryId.takeIf { !owed }, owedBy.takeIf { owed })
                    if (error == null) onDismiss()
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun parseCents(text: String): Long? {
    val match = Regex("\\$?(\\d+)(?:\\.(\\d{1,2}))?").matchEntire(text.trim()) ?: return null
    return match.groupValues[1].toLong() * 100 + match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
}
