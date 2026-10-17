package tally.app.ui.transaction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import tally.app.ui.components.AmountText
import tally.app.ui.components.CategoryChooser
import tally.app.ui.components.EditorSheet
import tally.app.ui.components.FieldHint
import tally.app.ui.components.FieldLabel
import tally.app.ui.components.Segmented
import tally.app.ui.components.SoftTextField
import tally.app.ui.components.CircleIconButton
import tally.app.ui.components.LetterAvatar
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.PillButton
import tally.app.ui.components.PillChip
import tally.app.ui.components.PillStyle
import tally.app.ui.components.PillTone
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.StatusPill
import tally.app.ui.components.TallyCard
import tally.app.ui.components.TallyTopBar
import tally.app.ui.components.avatarKey
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import tally.app.ui.ledger.occurredAtLocal
import tally.core.capture.WatchedPackages
import tally.core.model.CategorySource
import tally.core.model.Money
import tally.core.model.Source
import tally.core.model.TransactionStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TIME_FORMAT = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** One transaction: details, tags, splits, and the notifications it came from. */
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TallyTopBar("", onBack = onBack) {
                CircleIconButton(TallyIcons.Trash, "Delete", onClick = {
                    scope.launch {
                        ledger.deleteTransaction(id)
                        CaptureNotifier.cancel(context, id)
                        onBack()
                    }
                })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val t = transaction ?: return@Scaffold
        val merchantName = t.merchantId?.let { mid -> merchants.find { it.id == mid }?.name } ?: t.merchantRaw
        val local = t.occurredAtLocal()
        val categoryName = categoryNames[t.categoryId] ?: "Uncategorized"
        val title = merchantName ?: categoryName

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LetterAvatar(title, avatarKey(t.categoryId, categories.associateBy { it.id }), size = 60.dp)
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                AmountText(
                    t.amountCents,
                    MaterialTheme.typography.displayMedium,
                    color = if (t.amountCents < 0) Tally.colors.positive else MaterialTheme.colorScheme.onSurface,
                    centsScale = 0.55f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (t.status == TransactionStatus.CAPTURED.name) StatusPill("Needs review", tone = PillTone.Warn)
                    t.categorySource?.let { StatusPill(filedBy(it)) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    "Edit",
                    onClick = { context.startActivity(QuickEntryActivity.intent(context, transactionId = id)) },
                    icon = TallyIcons.Pencil,
                    modifier = Modifier.weight(1f),
                )
                if (t.merchantId != null) {
                    PillButton("Always do this…", onClick = { picking = true }, style = PillStyle.Soft, icon = TallyIcons.Wand, modifier = Modifier.weight(1f))
                }
            }

            TallyCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                DetailRow("Category", categoryName)
                accounts.find { it.id == t.accountId }?.let { DetailRow("Account", it.name) }
                DetailRow("When", "${dayLabel(local.toLocalDate(), LocalDate.now())}, ${TIME_FORMAT.format(local)}")
                DetailRow("Source", sourceLabel(t.source))
                t.note?.let { DetailRow("Note", it) }
            }

            TallyCard(Modifier.fillMaxWidth()) {
                MonoLabel("Tags")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    tags.forEach { tag ->
                        PillChip(
                            "#${tag.name}",
                            selected = true,
                            onClick = { scope.launch { ledger.removeTag(id, tag.id) } },
                            trailing = TallyIcons.Close,
                        )
                    }
                    PillChip("Add tag", selected = false, onClick = { addingTag = true }, leading = TallyIcons.Plus)
                }
            }

            if (t.amountCents > 0) {
                TallyCard(Modifier.fillMaxWidth()) {
                    MonoLabel("Split")
                    splits.forEach { split ->
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                split.owedBy?.let { "Owed by $it" } ?: categoryNames[split.categoryId] ?: "Uncategorized",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            AmountText(split.amountCents, MaterialTheme.typography.titleMedium, centsScale = 0.85f)
                            CircleIconButton(
                                TallyIcons.Close,
                                "Remove split",
                                onClick = { scope.launch { ledger.deleteSplit(split.id) } },
                                size = 32.dp,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                    val remainder = t.amountCents - splits.sumOf { it.amountCents }
                    Text(
                        if (splits.isEmpty()) {
                            "Put part of this in another category, or note who owes you for it."
                        } else {
                            "${Money.format(remainder)} stays in $categoryName"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Tally.colors.muted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    if (remainder > 0) {
                        PillButton(
                            "Split off part",
                            onClick = { addingSplit = true },
                            style = PillStyle.Soft,
                            icon = TallyIcons.Split,
                            height = 42.dp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }

            if (notifications.isNotEmpty()) {
                TallyCard(Modifier.fillMaxWidth()) {
                    MonoLabel("From notifications")
                    notifications.forEach { event ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .clickable { onOpenRawEvent(event.id) }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            MonoLabel(WatchedPackages.displayName(event.source), color = Tally.colors.faint)
                            Text(event.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                            Text(event.bigText ?: event.text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Tally.colors.muted, maxLines = 3)
                        }
                    }
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
                categories = activeCategories,
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

private fun filedBy(categorySource: String): String = when (categorySource) {
    CategorySource.RULE.name -> "Filed by a rule"
    CategorySource.MEMORY.name -> "Filed like last time"
    CategorySource.LLM.name -> "Suggested by Claude"
    else -> "Filed by you"
}

private fun sourceLabel(source: String): String = when (source) {
    Source.MANUAL.name -> "Entered by hand"
    Source.RBC_ALERT.name -> "Captured from RBC"
    Source.GOOGLE_WALLET.name -> "Captured from Google Wallet"
    Source.SAMSUNG_WALLET.name -> "Captured from Samsung Wallet"
    else -> "From ${source.lowercase()}"
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MonoLabel(label, Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
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
    EditorSheet(
        title = "Add tag",
        onDismiss = onDismiss,
        primary = "Add",
        onPrimary = { add(name) },
        primaryEnabled = name.isNotBlank(),
        error = error,
    ) {
        SoftTextField(name, { name = it; error = null }, "trip, work, gift…", leading = TallyIcons.Tag)
        if (existing.isNotEmpty()) {
            FieldLabel("Your tags")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                existing.forEach { tag -> PillChip("#${tag.name}", selected = false, onClick = { add(tag.name) }) }
            }
        }
    }
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

    EditorSheet(
        title = "Split off part",
        onDismiss = onDismiss,
        primary = "Add",
        onPrimary = {
            val cents = parseCents(amount)
            if (cents == null) {
                error = "Amounts look like 10 or 10.50"
            } else {
                scope.launch {
                    error = onAdd(cents, categoryId.takeIf { !owed }, owedBy.takeIf { owed })
                    if (error == null) onDismiss()
                }
            }
        },
        primaryEnabled = amount.isNotBlank() && (if (owed) owedBy.isNotBlank() else categoryId != null),
        error = error,
    ) {
        FieldLabel("Amount")
        SoftTextField(
            amount,
            { amount = it; error = null },
            "Up to ${Money.format(remainderCents)}",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        FieldLabel("It's for")
        Segmented(listOf("Another category", "Someone owes me"), if (owed) 1 else 0, { owed = it == 1 }, Modifier.fillMaxWidth())
        if (owed) {
            FieldLabel("Who")
            SoftTextField(owedBy, { owedBy = it; error = null }, "Name", leading = TallyIcons.People)
            FieldHint("When they pay you back, the inbox offers to settle it.")
        } else {
            FieldLabel("Category")
            CategoryChooser(categories, categoryId) { categoryId = it }
        }
    }
}

private fun parseCents(text: String): Long? {
    val match = Regex("\\$?(\\d+)(?:\\.(\\d{1,2}))?").matchEntire(text.trim()) ?: return null
    return match.groupValues[1].toLong() * 100 + match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
}
