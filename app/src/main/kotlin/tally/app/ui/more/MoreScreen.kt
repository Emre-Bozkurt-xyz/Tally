package tally.app.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import tally.app.ui.components.IconTile
import tally.app.ui.components.LargeHeader
import tally.app.ui.components.ListRow
import tally.app.ui.components.LocalBottomClearance
import tally.app.ui.components.RowDivider
import tally.app.ui.components.ScreenPadding
import tally.app.ui.components.SectionHeader
import tally.app.ui.components.groupShape
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import androidx.compose.ui.draw.clip

private class Entry(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

/** Everything that isn't a tab: insights, how things get filed, and the plumbing. */
@Composable
fun MoreScreen(
    onOpenMonth: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenOwed: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenAliases: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenRawEvents: () -> Unit,
) {
    val groups = listOf(
        "Insights" to listOf(
            Entry(TallyIcons.Calendar, "Month", "Spending by category, against budgets", onOpenMonth),
            Entry(TallyIcons.Trend, "Trends", "Six months, money in and out, top merchants", onOpenStats),
            Entry(TallyIcons.Repeat, "Subscriptions", "What charges you every month or year", onOpenSubscriptions),
            Entry(TallyIcons.People, "Owed to me", "Splits people haven't paid back", onOpenOwed),
        ),
        "Filing" to listOf(
            Entry(TallyIcons.Shapes, "Categories", "Names, groups, budgets, quick-add stars", onOpenCategories),
            Entry(TallyIcons.Wand, "Rules", "What files captures automatically", onOpenRules),
            Entry(TallyIcons.Tag, "Aliases", "Clean names for messy merchant text", onOpenAliases),
        ),
        "System" to listOf(
            Entry(TallyIcons.Sync, "Sync", "Server connection and live updates", onOpenSync),
            Entry(TallyIcons.Sliders, "Setup", "Notification access and capture checks", onOpenSetup),
            Entry(TallyIcons.Bell, "Raw events", "Captured notifications, exports", onOpenRawEvents),
        ),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = LocalBottomClearance.current),
    ) {
        item { LargeHeader(label = "Tally", title = "More") }
        groups.forEachIndexed { g, (name, entries) ->
            item(key = name) {
                Column {
                    SectionHeader(name, Modifier.padding(top = if (g == 0) 0.dp else 4.dp))
                    Column(Modifier.padding(horizontal = ScreenPadding)) {
                        entries.forEachIndexed { i, entry ->
                            ListRow(
                                title = entry.title,
                                subtitle = entry.subtitle,
                                leading = { IconTile(entry.icon) },
                                trailing = { Icon(TallyIcons.Forward, null, Modifier.size(18.dp), tint = Tally.colors.faint) },
                                onClick = entry.onClick,
                                modifier = Modifier.clip(groupShape(i, entries.size)),
                            )
                            if (i < entries.lastIndex) RowDivider()
                        }
                    }
                }
            }
        }
    }
}
