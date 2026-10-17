package tally.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tally.app.ui.ledger.displayAmount
import tally.app.ui.theme.MonoLabelStyle
import tally.app.ui.theme.Tally
import tally.app.ui.theme.TallyIcons
import kotlin.math.absoluteValue

/** Side padding for screen content. */
val ScreenPadding = 20.dp

/** The small uppercase mono label used above numbers and sections. */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = Tally.colors.muted) {
    Text(text.uppercase(), style = MonoLabelStyle, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * An amount with the cents set smaller and lighter: "$1,204" + ".56". Money in shows "+".
 * [centsScale] 1 keeps the cents the same size (list rows).
 */
@Composable
fun AmountText(
    cents: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    centsScale: Float = 0.6f,
    centsAlpha: Float = 0.5f,
    text: String = displayAmount(cents),
) {
    val dot = text.lastIndexOf('.')
    val annotated = buildAnnotatedString {
        if (dot < 0) {
            append(text)
        } else {
            append(text.substring(0, dot))
            withStyle(SpanStyle(fontSize = style.fontSize * centsScale, color = color.copy(alpha = color.alpha * centsAlpha))) {
                append(text.substring(dot))
            }
        }
    }
    Text(annotated, style = style, color = color, modifier = modifier, maxLines = 1)
}

/** A rounded card on the page. */
@Composable
fun TallyCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Tally.colors.card,
    padding: PaddingValues = PaddingValues(20.dp),
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** A pill chip: ink when selected, a soft fill otherwise. */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (selected) colors.primary else Tally.colors.chip
    val content = if (selected) colors.onPrimary else colors.onSurface
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(CircleShape)
            .background(background.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = if (leading != null) 12.dp else 16.dp, end = if (trailing != null) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, Modifier.size(16.dp), tint = content)
        Text(text, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = if (enabled) 1f else 0.6f), maxLines = 1)
        if (trailing != null) {
            Icon(
                trailing,
                null,
                Modifier.size(16.dp).then(if (onTrailingClick != null) Modifier.clickable(onClick = onTrailingClick) else Modifier),
                tint = content.copy(alpha = 0.7f),
            )
        }
    }
}

enum class PillStyle { Primary, Accent, Soft, Outline, Danger }

/** The app's buttons: full pills. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
) {
    val colors = MaterialTheme.colorScheme
    val (background, content) = when (style) {
        PillStyle.Primary -> colors.primary to colors.onPrimary
        PillStyle.Accent -> colors.secondary to colors.onSecondary
        PillStyle.Soft -> Tally.colors.chip to colors.onSurface
        PillStyle.Outline -> Color.Transparent to colors.onSurface
        PillStyle.Danger -> colors.errorContainer to colors.onErrorContainer
    }
    Row(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .then(if (style == PillStyle.Outline) Modifier.border(1.dp, Tally.colors.line, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = content.copy(alpha = if (enabled) 1f else 0.5f))
        Text(text, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = if (enabled) 1f else 0.5f), maxLines = 1)
    }
}

/** A round icon button with a soft fill (back, close, actions in the top bar). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    background: Color = Tally.colors.chip,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(20.dp), tint = if (enabled) tint else tint.copy(alpha = 0.35f))
    }
}

/** The bar on pushed screens: a round back button, the title, and actions on the right. */
@Composable
fun TallyTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) CircleIconButton(TallyIcons.Back, "Back", onBack)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(start = if (onBack == null) 6.dp else 0.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** The big title on a tab: a mono label over a large heading, with actions on the right. */
@Composable
fun LargeHeader(label: String, title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = ScreenPadding, end = 14.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoLabel(label)
            Text(title, style = MaterialTheme.typography.headlineLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A mono section label, with an optional link on the right ("See all"). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = ScreenPadding).padding(top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel(text, Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** A round letter avatar in one of the soft tints, chosen by [key] so a category keeps its color. */
@Composable
fun LetterAvatar(name: String, key: String?, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    val tints = Tally.colors.tints
    val (background, foreground) = tints[((key ?: name).hashCode().absoluteValue) % tints.size]
    Box(modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Text(
            name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * (size / 42.dp)),
            color = foreground,
        )
    }
}

/** An icon in a soft rounded square, for menu rows. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, background: Color = Tally.colors.chip, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Box(modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
    }
}

enum class PillTone { Neutral, Positive, Warn, Danger, Ink }

/** A small status pill ("REVIEW", "MONEY BACK"). */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, tone: PillTone = PillTone.Neutral) {
    val colors = MaterialTheme.colorScheme
    val (background, content) = when (tone) {
        PillTone.Neutral -> Tally.colors.chip to Tally.colors.muted
        PillTone.Positive -> Tally.colors.positiveSoft to colors.onSecondaryContainer
        PillTone.Warn -> colors.tertiaryContainer to colors.onTertiaryContainer
        PillTone.Danger -> colors.errorContainer to colors.onErrorContainer
        PillTone.Ink -> colors.primary to colors.onPrimary
    }
    Text(
        text.uppercase(),
        style = MonoLabelStyle.copy(fontSize = MonoLabelStyle.fontSize * 0.9f, letterSpacing = MonoLabelStyle.letterSpacing * 0.8f),
        color = content,
        maxLines = 1,
        modifier = modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A thin rounded progress bar, with an optional tick (where you'd be at an even pace). */
@Composable
fun ThinProgress(
    fraction: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    marker: Float? = null,
    markerColor: Color = color,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height + 6.dp)
            .drawBehind {
                val h = height.toPx()
                val top = (size.height - h) / 2
                val radius = CornerRadius(h / 2)
                drawRoundRect(track, Offset(0f, top), Size(size.width, h), radius)
                val w = size.width * fraction.coerceIn(0f, 1f)
                if (w > 0f) drawRoundRect(color, Offset(0f, top), Size(maxOf(w, h), h), radius)
                marker?.let { m ->
                    val x = size.width * m.coerceIn(0f, 1f)
                    drawRoundRect(markerColor, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
                }
            },
    )
}

/** A segmented pill control ("Month | 6 months | Year"). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Tally.colors.chip)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, option ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .animateContentSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else Tally.colors.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The round ink "+" button that adds things on list screens. */
@Composable
fun AddButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(TallyIcons.Plus, description, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/**
 * Shapes for rows grouped into one card: the first rounds its top, the last its bottom.
 * Lets lazy lists draw a card around several items.
 */
fun groupShape(index: Int, count: Int, radius: Dp = 22.dp): Shape = when {
    count == 1 -> RoundedCornerShape(radius)
    index == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius)
    index == count - 1 -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    else -> RoundedCornerShape(0.dp)
}

/** A hairline between rows inside a card, inset past the leading avatar. */
@Composable
fun RowDivider(start: Dp = 74.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Tally.colors.card)
            .padding(start = start, end = 18.dp)
            .height(1.dp)
            .background(Tally.colors.line),
    )
}

/** A row in a grouped card: leading slot, title and subtitle, trailing slot. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    subtitleLines: Int = 1,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Tally.colors.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tally.colors.muted,
                    maxLines = subtitleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A label with a switch, for options in sheets and dialogs. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.secondary,
                checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                uncheckedTrackColor = Tally.colors.chip,
                uncheckedBorderColor = Tally.colors.chip,
                uncheckedThumbColor = Tally.colors.faint,
            ),
        )
    }
}

/** A centered message for an empty screen. */
@Composable
fun EmptyMessage(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = Tally.colors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** A filled, borderless rounded text field. */
@Composable
fun SoftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val fill = Tally.colors.chip
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Tally.colors.muted) },
        leadingIcon = leading?.let { { Icon(it, null, Modifier.size(18.dp), tint = Tally.colors.muted) } },
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = fill,
            unfocusedContainerColor = fill,
            errorContainerColor = fill,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Lets content run [horizontal] past its parent's padding on both sides (scrolling rows edge to edge). */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}
