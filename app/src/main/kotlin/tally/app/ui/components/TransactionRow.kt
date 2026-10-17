package tally.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tally.app.data.CategoryEntity
import tally.app.data.TransactionEntity
import tally.app.ui.theme.Tally
import tally.core.model.Kind
import tally.core.model.TransactionStatus

/** A transaction in a list: letter avatar (colored by category), name, details, amount. */
@Composable
fun TransactionRow(
    transaction: TransactionEntity,
    title: String,
    subtitle: String,
    avatarKey: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
) {
    val amountColor = when {
        transaction.kind == Kind.TRANSFER.name -> Tally.colors.muted
        transaction.amountCents < 0 -> Tally.colors.positive
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tally.colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LetterAvatar(title, avatarKey)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tally.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AmountText(transaction.amountCents, MaterialTheme.typography.titleMedium, color = amountColor, centsScale = 0.82f, centsAlpha = 0.6f)
            if (transaction.status == TransactionStatus.CAPTURED.name) StatusPill("Review", tone = PillTone.Warn)
        }
    }
}

/** The key that colors a transaction's avatar: its top-level category, so a group shares a tint. */
fun avatarKey(categoryId: String?, categories: Map<String, CategoryEntity>): String? =
    categoryId?.let { id -> categories[id]?.parentId ?: id }
