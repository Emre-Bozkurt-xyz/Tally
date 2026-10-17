package tally.app.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.color.ColorProvider as DayNight
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
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
import tally.core.entry.CategoryRanking
import tally.core.model.Money
import tally.core.report.BudgetStatus
import tally.core.report.BudgetedCategory
import tally.core.report.Budgets
import tally.core.report.WeekSpend
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale

private val CHIP_HEIGHT = 40.dp
private val CHIP_GAP = 6.dp

/** The app's paper and ink, day and night. Glance can't use the app's fonts, so labels use the system mono. */
private object WidgetColors {
    val background = DayNight(day = Color(0xFFF4F2ED), night = Color(0xFF1B1A17))
    val ink = DayNight(day = Color(0xFF1B1A17), night = Color(0xFFEFEBE3))
    val muted = DayNight(day = Color(0xFF6E6A62), night = Color(0xFFA29D93))
    val chip = DayNight(day = Color(0xFFE9E6DF), night = Color(0xFF292724))
    val accent = DayNight(day = Color(0xFF2F6B4E), night = Color(0xFF8CCBA6))
    val warn = DayNight(day = Color(0xFFA3661A), night = Color(0xFFE0A85A))
    val over = DayNight(day = Color(0xFFB3432E), night = Color(0xFFEE917D))
    val review = DayNight(day = Color(0xFFF4E3C8), night = Color(0xFF3D2C14))
    val onReview = DayNight(day = Color(0xFF4A2D06), night = Color(0xFFF4D9AE))
}

/**
 * Home screen widget: this week's spending, plus categories as quick-add buttons. Laid out
 * for its actual size: fixed-height pills, one to three columns by width, as many rows as fit,
 * starred categories first and then the ones you use most.
 */
class QuickAddWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context.appContainer.ledger)
        provideContent { Content(context, data) }
    }

    companion object {
        private const val TAG = "QuickAddWidget"
        private const val MAX_CATEGORIES = 12

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
                val choices = categories.chipCategories()
                val byId = choices.associateBy { it.id }
                val ranked = CategoryRanking.rank(
                    treeOrder = choices.map { it.id },
                    pinned = choices.filter { it.pinned }.map { it.id }.toSet(),
                    uses = ledger.recentCategoryUses(),
                    now = ZonedDateTime.now(),
                ).mapNotNull { byId[it] }.take(MAX_CATEGORIES)
                return WidgetData(week, ranked, ledger.observeInboxCount().first())
            }
        }
    }

    @Composable
    private fun Content(context: Context, data: WidgetData) {
        val size = LocalSize.current
        val innerWidth = size.width - 32.dp
        val headerHeight = if (data.week.budgetCents != null) 74.dp else 62.dp
        val free = size.height - 28.dp - headerHeight - 10.dp
        val columns = when {
            innerWidth >= 300.dp -> 3
            innerWidth >= 150.dp -> 2
            else -> 1
        }
        val fit = ((free + CHIP_GAP) / (CHIP_HEIGHT + CHIP_GAP)).toInt().coerceIn(0, 4)
        val shown = data.categories.take(fit * columns)
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.background)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Header(context, data.week, data.toReview)
            // Leftover height goes above the buttons, so they sit at the bottom near your thumb.
            Spacer(GlanceModifier.defaultWeight())
            if (data.categories.isEmpty() && fit > 0) {
                Text(
                    "Star categories in Tally to show them here",
                    style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp),
                )
            }
            shown.chunked(columns).forEachIndexed { index, row ->
                if (index > 0) Spacer(GlanceModifier.height(CHIP_GAP))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    row.forEachIndexed { i, category ->
                        if (i > 0) Spacer(GlanceModifier.width(CHIP_GAP))
                        CategoryButton(context, category)
                    }
                    repeat(columns - row.size) {
                        Spacer(GlanceModifier.width(CHIP_GAP))
                        Box(GlanceModifier.defaultWeight().height(CHIP_HEIGHT)) {}
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("THIS WEEK", style = mono(WidgetColors.muted))
                        if (toReview > 0) {
                            Spacer(GlanceModifier.width(8.dp))
                            Text(
                                "$toReview TO REVIEW",
                                style = mono(WidgetColors.onReview),
                                modifier = GlanceModifier
                                    .background(WidgetColors.review)
                                    .cornerRadius(8.dp)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(GlanceModifier.height(2.dp))
                    val amount = Money.format(week.spentCents)
                    val dot = amount.lastIndexOf('.')
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            amount.substring(0, dot),
                            style = TextStyle(color = WidgetColors.ink, fontSize = 28.sp, fontWeight = FontWeight.Medium),
                        )
                        Text(
                            amount.substring(dot),
                            style = TextStyle(color = WidgetColors.muted, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.padding(bottom = 3.dp),
                        )
                        week.budgetCents?.let { budget ->
                            Text(
                                "  of ${Money.format(budget)}",
                                style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp),
                                modifier = GlanceModifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }
                Box(
                    modifier = GlanceModifier
                        .size(44.dp)
                        .background(WidgetColors.ink)
                        .cornerRadius(22.dp)
                        .clickable(actionStartActivity(quickAddIntent(context, categoryId = null))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_plus),
                        contentDescription = "Quick add",
                        colorFilter = ColorFilter.tint(WidgetColors.background),
                        modifier = GlanceModifier.size(22.dp),
                    )
                }
            }
            val budget = week.budgetCents
            if (budget != null && budget > 0) {
                Spacer(GlanceModifier.height(8.dp))
                LinearProgressIndicator(
                    progress = (week.spentCents.toFloat() / budget).coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth().height(5.dp).cornerRadius(3.dp),
                    color = when (BudgetStatus.of(week.spentCents, budget)) {
                        BudgetStatus.OK -> WidgetColors.accent
                        BudgetStatus.WARNING -> WidgetColors.warn
                        BudgetStatus.OVER -> WidgetColors.over
                    },
                    backgroundColor = WidgetColors.chip,
                )
            }
        }
    }

    @Composable
    private fun androidx.glance.layout.RowScope.CategoryButton(context: Context, category: CategoryEntity) {
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .height(CHIP_HEIGHT)
                .background(WidgetColors.chip)
                .cornerRadius(CHIP_HEIGHT / 2)
                .clickable(actionStartActivity(quickAddIntent(context, category.id))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                category.name,
                maxLines = 1,
                style = TextStyle(
                    color = WidgetColors.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }

    private fun mono(color: ColorProvider) =
        TextStyle(color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)

    /** A distinct data URI per button, so each gets its own PendingIntent. */
    private fun quickAddIntent(context: Context, categoryId: String?): Intent =
        QuickEntryActivity.intent(context, categoryId = categoryId)
            .setData("tally://quick-add/${categoryId ?: "any"}".toUri())
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}
