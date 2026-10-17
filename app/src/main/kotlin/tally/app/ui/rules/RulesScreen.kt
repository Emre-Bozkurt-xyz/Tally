package tally.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tally.app.data.AccountEntity
import tally.app.data.CategoryEntity
import tally.app.data.LedgerRepository
import tally.app.data.RuleEntity
import tally.app.data.RulesRepository
import tally.app.data.inTreeOrder
import tally.app.ui.components.AddButton
import tally.app.ui.components.CategoryChooser
import tally.app.ui.components.CircleIconButton
import tally.app.ui.components.EditorSheet
import tally.app.ui.components.FieldHint
import tally.app.ui.components.FieldLabel
import tally.app.ui.components.MonoLabel
import tally.app.ui.components.PillButton
import tally.app.ui.components.PillChip
import tally.app.ui.components.PillTone
import tally.app.ui.components.ReorderButtons
import tally.app.ui.components.RowDivider
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.SectionHeader
import tally.app.ui.components.Segmented
import tally.app.ui.components.SoftTextField
import tally.app.ui.components.StatusPill
import tally.app.ui.components.TallyTopBar
import tally.app.ui.components.groupShape
import tally.app.ui.theme.GeistMono
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import tally.core.model.Money
import tally.core.rules.Rule
import java.util.UUID

/** Rules in priority order (first match wins): what they do at a glance, edit in a sheet. */
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
    val merchants by remember { ledger.observeAllMerchants() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val merchantNames = remember(merchants) { merchants.map { it.name }.distinct().sorted() }
    val paths = remember(categories) { categoryPaths(categories) }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    var editing by remember { mutableStateOf<RuleEntity?>(null) }
    var reordering by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    // What the rules would do to the inbox now; recomputed whenever the rules change.
    val replay by produceState(initialValue = emptyList(), list) { value = rules.replayPlan() }

    val shown = remember(list, query, paths) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) list else list.filter { q in it.merchantPattern.lowercase() || q in paths[it.categoryId].orEmpty().lowercase() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TallyTopBar("Rules", onBack) {
                CircleIconButton(
                    TallyIcons.Reorder,
                    if (reordering) "Done reordering" else "Reorder",
                    { reordering = !reordering; query = "" },
                    background = if (reordering) MaterialTheme.colorScheme.primary else Tally.colors.chip,
                    tint = if (reordering) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                CircleIconButton(TallyIcons.Tag, "Aliases", onOpenAliases)
                CircleIconButton(TallyIcons.Code, "Edit as TOML", onEditToml)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (!reordering) AddButton("Add rule", onClick = { editing = newRule(categories) }) },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp)) {
            item(key = "intro") {
                Text(
                    if (reordering) {
                        "First match wins: move the specific rules above the broad ones."
                    } else {
                        "First match wins. File rules log a purchase with no review; Ask rules suggest a category and wait in your inbox."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.colors.muted,
                    modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 8.dp),
                )
            }
            if (replay.isNotEmpty() && !reordering) {
                item(key = "replay") {
                    val filed = replay.count { it.confirm }
                    Row(
                        Modifier
                            .padding(horizontal = ScreenPadding, vertical = 6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Tally.colors.positiveSoft)
                            .padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${replay.size} waiting match your rules",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                listOfNotNull(
                                    "$filed to file".takeIf { filed > 0 },
                                    "${replay.size - filed} to suggest".takeIf { replay.size > filed },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                            )
                        }
                        PillButton("Apply", onClick = {
                            scope.launch { snackbar.showSnackbar("Updated ${rules.applyReplay(replay)} transactions") }
                        }, height = 40.dp)
                    }
                }
            }
            if (list.size > 6 && !reordering) {
                item(key = "search") {
                    SoftTextField(
                        query,
                        { query = it },
                        "Search merchants or categories",
                        leading = TallyIcons.Search,
                        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 6.dp),
                    )
                }
            }
            item(key = "count") {
                SectionHeader(
                    when {
                        list.isEmpty() -> "No rules yet"
                        shown.size != list.size -> "${shown.size} of ${list.size} rules"
                        else -> "${list.size} rules"
                    },
                )
            }
            itemsIndexed(shown, key = { _, it -> it.id }) { index, rule ->
                Column(Modifier.padding(horizontal = ScreenPadding).animateItem()) {
                    RuleRow(
                        rule = rule,
                        number = list.indexOf(rule) + 1,
                        path = paths[rule.categoryId],
                        conditions = conditions(rule, accountNames),
                        reordering = reordering,
                        canUp = index > 0,
                        canDown = index < shown.lastIndex,
                        onUp = { scope.launch { rules.moveRule(rule.id, -1) } },
                        onDown = { scope.launch { rules.moveRule(rule.id, 1) } },
                        onClick = { editing = rule },
                        modifier = Modifier.clip(groupShape(index, shown.size)),
                    )
                    if (index < shown.lastIndex) RowDivider(start = 60.dp)
                }
            }
        }
    }

    editing?.let { rule ->
        RuleSheet(
            rule = rule,
            isNew = list.none { it.id == rule.id },
            categories = categories,
            accounts = accounts,
            merchantNames = merchantNames,
            onSave = { rules.saveRule(it) },
            onDelete = { rules.deleteRule(rule.id) },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun RuleRow(
    rule: RuleEntity,
    number: Int,
    path: String?,
    conditions: String?,
    reordering: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = Rule.literalName(rule.merchantPattern)
    Row(
        modifier
            .fillMaxWidth()
            .background(Tally.colors.card)
            .clickable(enabled = !reordering, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Tally.colors.chip), contentAlignment = Alignment.Center) {
            MonoLabel("$number", color = Tally.colors.muted)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                name ?: rule.merchantPattern,
                style = if (name != null) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleSmall.copy(fontFamily = GeistMono)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "→ " + (path ?: "(deleted category)"),
                style = MaterialTheme.typography.bodySmall,
                color = if (path == null) Tally.colors.over else Tally.colors.muted,
                maxLines = 1,
            )
            if (conditions != null) MonoLabel(conditions, color = Tally.colors.faint)
        }
        Spacer(Modifier.width(10.dp))
        if (reordering) {
            ReorderButtons(canUp, canDown, onUp, onDown)
        } else {
            StatusPill(if (rule.ask) "Ask" else "File", tone = if (rule.ask) PillTone.Warn else PillTone.Neutral)
            Icon(TallyIcons.Forward, null, Modifier.padding(start = 6.dp).size(16.dp), tint = Tally.colors.faint)
        }
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

/** "UP TO $15 · VISA · #WORK", or null when the rule has no conditions. */
private fun conditions(rule: RuleEntity, accounts: Map<String, String>): String? = listOfNotNull(
    rule.minAmountCents?.let { "from ${Money.format(it)}" },
    rule.maxAmountCents?.let { "up to ${Money.format(it)}" },
    rule.accountId?.let { accounts[it] },
    rule.tagsCsv?.split(',')?.joinToString(" ") { "#$it" },
).joinToString(" · ").ifEmpty { null }

/** "Food › Coffee" style labels for every category. */
internal fun categoryPaths(categories: List<CategoryEntity>): Map<String, String> {
    val byId = categories.associateBy { it.id }
    return categories.associate { c -> c.id to (c.parentId?.let { "${byId[it]?.name} › ${c.name}" } ?: c.name) }
}

/** "15.00" or "15" to cents; blank is no limit; a failure with the reason otherwise. */
private fun parseAmount(text: String): Result<Long?> {
    val trimmed = text.trim().removePrefix("$")
    if (trimmed.isEmpty()) return Result.success(null)
    val match = Regex("(\\d+)(?:\\.(\\d{1,2}))?").matchEntire(trimmed)
        ?: return Result.failure(IllegalArgumentException("Amounts look like 15 or 15.00"))
    return Result.success(match.groupValues[1].toLong() * 100 + match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong())
}

private fun amountText(cents: Long?) = cents?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" }.orEmpty()

@Composable
private fun RuleSheet(
    rule: RuleEntity,
    isNew: Boolean,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    merchantNames: List<String>,
    onSave: suspend (RuleEntity) -> String?,
    onDelete: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pattern by remember { mutableStateOf(rule.merchantPattern) }
    var categoryId by remember { mutableStateOf(rule.categoryId) }
    var ask by remember { mutableStateOf(rule.ask) }
    var min by remember { mutableStateOf(amountText(rule.minAmountCents)) }
    var max by remember { mutableStateOf(amountText(rule.maxAmountCents)) }
    var accountId by remember { mutableStateOf(rule.accountId) }
    var tags by remember { mutableStateOf(rule.tagsCsv?.replace(",", ", ").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    // Which known merchants the pattern catches, as you type.
    val matches = remember(pattern, merchantNames) {
        val p = pattern.trim()
        if (p.isEmpty() || Rule.patternError(p) != null) null
        else Regex(p, RegexOption.IGNORE_CASE).let { r -> merchantNames.filter { r.matches(it) } }
    }

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
            ask = ask,
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

    EditorSheet(
        title = if (isNew) "New rule" else "Edit rule",
        onDismiss = onDismiss,
        primary = "Save",
        onPrimary = ::save,
        primaryEnabled = categoryId.isNotEmpty() && pattern.isNotBlank(),
        onDelete = if (isNew) null else ({ scope.launch { onDelete(); onDismiss() } }),
        error = error,
    ) {
        FieldLabel("Merchant")
        SoftTextField(pattern, { pattern = it; error = null }, "Tim Hortons, or Uber.* for all Uber", leading = TallyIcons.Store)
        FieldHint(
            when {
                pattern.isBlank() -> "The whole cleaned-up name, any capitalization. Regex works too: A|B, Uber.*"
                matches == null -> "That pattern isn't valid yet"
                matches.isEmpty() -> "Matches no merchant you've bought from yet"
                else -> "Matches ${matches.size}: " + matches.take(4).joinToString(", ") + if (matches.size > 4) ", …" else ""
            },
            color = if (matches == null && pattern.isNotBlank()) Tally.colors.over else Tally.colors.muted,
        )

        FieldLabel("Then")
        Segmented(listOf("File it", "Ask me"), if (ask) 1 else 0, { ask = it == 1 }, Modifier.fillMaxWidth())
        FieldHint(
            if (ask) {
                "Waits in the inbox with this category suggested; one tap to accept. Good for people and mixed shops."
            } else {
                "Logged with this category straight away, no review."
            },
        )

        FieldLabel("Category")
        CategoryChooser(categories, categoryId) { categoryId = it }

        FieldLabel("Only when (optional)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftTextField(
                min, { min = it; error = null }, "From $",
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            SoftTextField(
                max, { max = it; error = null }, "Up to $",
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PillChip("Any account", selected = accountId == null, onClick = { accountId = null }, leading = TallyIcons.Wallet)
            accounts.forEach { account ->
                PillChip(account.name, selected = accountId == account.id, onClick = { accountId = account.id })
            }
        }

        FieldLabel("Tags (optional)")
        SoftTextField(tags, { tags = it }, "Comma separated", leading = TallyIcons.Tag)
    }
}
