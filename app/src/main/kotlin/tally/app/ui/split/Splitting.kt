package tally.app.ui.split

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tally.app.data.SplitEntity
import tally.app.ui.components.EditorSheet
import tally.app.ui.components.FieldHint
import tally.app.ui.components.FieldLabel
import tally.app.ui.components.PillChip
import tally.app.ui.components.Segmented
import tally.app.ui.components.SoftTextField
import tally.app.ui.theme.TallyIcons
import tally.core.model.Money
import tally.core.report.shareOf

/**
 * One-tap splits: "½ with Sam" for the people you split with lately (long-press for another
 * share), then "Split…" for anyone or anything else. Parts already owed on this purchase show
 * first; tap one to take it back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickSplitRow(
    people: List<String>,
    amountCents: Long,
    leftCents: Long,
    owed: List<SplitEntity>,
    onSplit: (person: String, cents: Long) -> Unit,
    onRemove: (SplitEntity) -> Unit,
    onMore: (person: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val owedNow = owed.filter { it.owedBy != null }
    val left = leftCents
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        owedNow.forEach { part ->
            PillChip(
                "${part.owedBy} owes ${Money.format(part.amountCents)}",
                selected = true,
                onClick = { onRemove(part) },
                trailing = TallyIcons.Close,
            )
        }
        if (left > 0) {
            people.filter { person -> owedNow.none { it.owedBy == person } }.forEach { person ->
                val half = shareOf(amountCents, 2)
                PillChip(
                    "½ with ${person.substringBefore(' ')}",
                    selected = false,
                    enabled = half <= left,
                    onClick = { onSplit(person, half) },
                    onLongClick = { onMore(person) },
                    leading = TallyIcons.People,
                )
            }
            PillChip("Split…", selected = false, onClick = { onMore(null) }, leading = TallyIcons.Split)
        }
    }
}

private val SHARES = listOf("½" to 2, "⅓" to 3, "¼" to 4)

/**
 * Who owes what for a purchase: a person (recent ones as chips, or anyone), and their share,
 * a fraction (you keep the odd cent) or an amount.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitSheet(
    amountCents: Long,
    leftCents: Long,
    people: List<String>,
    initialPerson: String?,
    onSplit: suspend (person: String, cents: Long) -> String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var person by remember { mutableStateOf(initialPerson ?: people.firstOrNull()) }
    var typed by remember { mutableStateOf("") }
    var share by remember { mutableIntStateOf(0) }
    var custom by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val who = (person ?: typed).trim()
    val cents = if (share < SHARES.size) shareOf(amountCents, SHARES[share].second) else parseCents(custom)

    EditorSheet(
        title = "Split with someone",
        onDismiss = onDismiss,
        primary = if (cents != null && who.isNotEmpty()) "${who.substringBefore(' ')} owes ${Money.format(cents)}" else "Split",
        onPrimary = {
            val amount = cents
            if (amount == null) {
                error = "Amounts look like 10 or 10.50"
            } else {
                scope.launch {
                    error = onSplit(who, amount)
                    if (error == null) onDismiss()
                }
            }
        },
        primaryEnabled = who.isNotEmpty() && (share < SHARES.size || custom.isNotBlank()),
        error = error,
    ) {
        FieldLabel("Who")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            people.forEach { name ->
                PillChip(name, selected = person == name, onClick = { person = name; error = null })
            }
            PillChip("Someone else", selected = person == null, onClick = { person = null; error = null }, leading = TallyIcons.Plus)
        }
        if (person == null) {
            SoftTextField(typed, { typed = it; error = null }, "Name", leading = TallyIcons.People)
        }
        FieldLabel("Their share")
        Segmented(SHARES.map { it.first } + "Amount", share, { share = it; error = null }, Modifier.fillMaxWidth())
        if (share == SHARES.size) {
            SoftTextField(
                custom,
                { custom = it; error = null },
                "Up to ${Money.format(leftCents)}",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        } else {
            Text(
                "Of ${Money.format(amountCents)}. You keep the odd cent.",
                style = MaterialTheme.typography.bodySmall,
                color = tally.app.ui.theme.Tally.colors.muted,
            )
        }
        FieldHint("When they pay you back, the inbox offers to settle it.")
    }
}

/** "10", "10.5", "$10.50" in cents; null if it isn't an amount. */
fun parseCents(text: String): Long? {
    val match = Regex("\\$?(\\d+)(?:\\.(\\d{1,2}))?").matchEntire(text.trim()) ?: return null
    return match.groupValues[1].toLong() * 100 + match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
}
