package tally.app.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import tally.app.MainActivity
import tally.app.R
import tally.app.appContainer
import tally.app.data.CategoryEntity
import tally.app.data.LedgerRepository
import tally.app.data.chipCategories
import tally.app.quickentry.QuickEntryActivity
import tally.app.ui.ledger.ledgerEntries
import tally.app.ui.theme.DarkColors
import tally.app.ui.theme.LightColors
import tally.core.model.Money
import tally.core.report.BudgetStatus
import tally.core.report.BudgetedCategory
import tally.core.report.Budgets
import tally.core.report.WeekSpend
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

private val colors = ColorProviders(light = LightColors, dark = DarkColors)

/** Home screen widget: this week's spending, plus the starred categories as quick-add buttons. */
class QuickAddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context.appContainer.ledger)
        provideContent {
            GlanceTheme(colors = colors) { Content(context, data) }
        }
    }

    companion object {
        private const val TAG = "QuickAddWidget"
        private const val MAX_CATEGORIES = 4

        /** Redraws the widget whenever transactions or categories change. Runs for the app's lifetime. */
        @OptIn(FlowPreview::class)
        suspend fun refreshOnChanges(context: Context, ledger: LedgerRepository) {
            merge(ledger.observeTransactions(), ledger.observeCategories(), ledger.observeInboxCount())
                .debounce(500)
                .collect {
                    try {
                        QuickAddWidget().updateAll(context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Widget update failed", e)
                    }
                }
        }
    }

    private data class WidgetData(val week: WeekSpend, val categories: List<CategoryEntity>, val toReview: Int) {
        companion object {
            suspend fun load(ledger: LedgerRepository): WidgetData {
                val today = LocalDate.now()
                val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
                // A day of slack covers transactions logged in other time zones.
                val since = today.minusDays(8).atStartOfDay(ZoneId.systemDefault()).toInstant()
                val splits = ledger.observeAllSplits().first().groupBy { it.transactionId }
                val entries = ledger.transactionsSince(since).flatMap { it.ledgerEntries(splits[it.id].orEmpty()) }
                val categories = ledger.observeCategories().first()
                val week = WeekSpend.of(
                    entries = entries,
                    today = today,
                    firstDayOfWeek = firstDay,
                    monthlyBudgetsCents = listOfNotNull(
                        Budgets.monthlyTotal(categories.map { BudgetedCategory(it.id, it.parentId, it.monthlyBudgetCents) }),
                    ),
                )
                val pinned = categories.chipCategories().filter { it.pinned }.take(MAX_CATEGORIES)
                return WidgetData(week, pinned, ledger.observeInboxCount().first())
            }
        }
    }

    @Composable
    private fun Content(context: Context, data: WidgetData) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(12.dp),
        ) {
            Header(context, data.week, data.toReview)
            Spacer(GlanceModifier.height(8.dp))
            if (data.categories.isEmpty()) {
                Text(
                    "Star categories in Tally to show them here",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            } else {
                // Two rows of two: four names in one row don't fit at 4x2.
                data.categories.chunked(2).forEachIndexed { index, row ->
                    if (index > 0) Spacer(GlanceModifier.height(6.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        row.forEachIndexed { i, category ->
                            if (i > 0) Spacer(GlanceModifier.width(6.dp))
                            CategoryButton(context, category)
                        }
                        if (row.size == 1) {
                            Spacer(GlanceModifier.width(6.dp))
                            Box(GlanceModifier.defaultWeight()) {}
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(context: Context, week: WeekSpend, toReview: Int) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
                ) {
                    Text(
                        if (toReview > 0) "This week · $toReview to review" else "This week",
                        style = TextStyle(
                            color = if (toReview > 0) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp,
                        ),
                    )
                    val budget = week.budgetCents?.let { " of ${Money.format(it)}" }.orEmpty()
                    Text(
                        Money.format(week.spentCents) + budget,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                Image(
                    provider = ImageProvider(R.drawable.ic_quick_add),
                    contentDescription = "Quick add",
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier
                        .size(36.dp)
                        .clickable(actionStartActivity(quickAddIntent(context, categoryId = null))),
                )
            }
            val budget = week.budgetCents
            if (budget != null && budget > 0) {
                Spacer(GlanceModifier.height(4.dp))
                LinearProgressIndicator(
                    progress = (week.spentCents.toFloat() / budget).coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth(),
                    color = when (BudgetStatus.of(week.spentCents, budget)) {
                        BudgetStatus.OK -> GlanceTheme.colors.primary
                        BudgetStatus.WARNING -> GlanceTheme.colors.tertiary
                        BudgetStatus.OVER -> GlanceTheme.colors.error
                    },
                    backgroundColor = GlanceTheme.colors.surfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun androidx.glance.layout.RowScope.CategoryButton(context: Context, category: CategoryEntity) {
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxSize()
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(16.dp)
                .clickable(actionStartActivity(quickAddIntent(context, category.id))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                category.name,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSecondaryContainer,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }

    /** A distinct data URI per button, so each gets its own PendingIntent. */
    private fun quickAddIntent(context: Context, categoryId: String?): Intent =
        QuickEntryActivity.intent(context, categoryId = categoryId)
            .setData("tally://quick-add/${categoryId ?: "any"}".toUri())
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}
