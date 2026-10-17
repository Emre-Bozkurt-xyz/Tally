package tally.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons

enum class Tab(val route: String, val label: String, val icon: () -> ImageVector) {
    Home("home", "Home", { TallyIcons.Home }),
    Activity("activity", "Activity", { TallyIcons.Activity }),
    Inbox("inbox", "Inbox", { TallyIcons.Inbox }),
    More("more", "More", { TallyIcons.More }),
}

/** Space a tab screen leaves at the bottom so its last rows clear the floating bar. */
val LocalBottomClearance = compositionLocalOf { 0.dp }

private val BarHeight = 62.dp
private val BarGap = 12.dp

@Composable
fun bottomClearance(): Dp = BarHeight + BarGap + 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** The floating pill with the four tabs, and the round add button beside it. */
@Composable
fun TallyBottomBar(current: Tab?, inboxCount: Int, onSelect: (Tab) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Tally.colors
    Box(modifier.fillMaxWidth()) {
        // Fades the list out under the bar.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to MaterialTheme.colorScheme.background)),
        )
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = BarGap)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(BarHeight)
                    .shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .clip(CircleShape)
                    .background(colors.navBar)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { tab ->
                    NavItem(tab, selected = tab == current, badge = if (tab == Tab.Inbox) inboxCount else 0) { onSelect(tab) }
                }
            }
            Box(
                modifier = Modifier
                    .size(BarHeight)
                    .shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
                    .clickable(role = Role.Button, onClickLabel = "Quick add", onClick = onAdd)
                    .semantics { contentDescription = "Quick add" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(TallyIcons.Plus, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSecondary)
            }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val colors = Tally.colors
    Row(
        modifier = Modifier
            .height(50.dp)
            .widthIn(min = 50.dp)
            .clip(CircleShape)
            .background(if (selected) colors.navSelected else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = if (badge > 0) "${tab.label}, $badge to review" else tab.label
            }
            .padding(horizontal = 14.dp)
            .animateContentSize(spring(stiffness = 600f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(tab.icon(), null, Modifier.size(22.dp), tint = if (selected) colors.onNavSelected else colors.onNavBar)
            if (badge > 0) {
                Box(
                    Modifier
                        .offset(x = 12.dp, y = (-6).dp)
                        .clip(CircleShape)
                        .background(colors.over)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        if (badge > 99) "99+" else "$badge",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    )
                }
            }
        }
        AnimatedVisibility(selected, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            Text(
                tab.label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onNavSelected,
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
            )
        }
    }
}
