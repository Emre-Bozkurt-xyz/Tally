package tally.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import tally.app.ui.theme.Geist
import tally.app.ui.theme.GeistMono
import tally.app.ui.theme.Tally
import tally.core.model.Money
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Running total through the month: this month solid with a soft fill, last month dashed
 * behind it. Touch and drag to read any day.
 */
@Composable
fun CumulativeChart(
    current: List<Long>,
    previous: List<Long>,
    days: Int,
    dayLabel: (Int) -> String,
    previousLabel: String,
    modifier: Modifier = Modifier,
    budgetCents: Long? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.secondary
    val faint = Tally.colors.faint
    val line = Tally.colors.line
    val card = Tally.colors.card
    val tipBackground = MaterialTheme.colorScheme.primary
    val tipText = MaterialTheme.colorScheme.onPrimary
    val measurer = rememberTextMeasurer()
    var touched by remember { mutableStateOf<Int?>(null) }

    val top = maxOf(current.maxOrNull() ?: 0, previous.maxOrNull() ?: 0, budgetCents?.takeIf { it < (current.lastOrNull() ?: 0) * 2 } ?: 0)
        .coerceAtLeast(100) * 1.12f

    Canvas(
        modifier
            .semantics { contentDescription = "Spending so far this month: ${Money.format(current.lastOrNull() ?: 0)}" }
            .pointerInput(current.size, days) {
                fun pick(x: Float) {
                    touched = ((x / size.width) * (days - 1)).roundToInt().coerceIn(0, (current.size - 1).coerceAtLeast(0))
                }
                // Horizontal only, so the page still scrolls vertically over the chart.
                detectHorizontalDragGestures(
                    onDragStart = { pick(it.x) },
                    onDragEnd = { touched = null },
                    onDragCancel = { touched = null },
                ) { change, _ -> pick(change.position.x) }
            }
            .pointerInput(current.size, days) {
                detectTapGestures(onPress = { offset ->
                    touched = ((offset.x / size.width) * (days - 1)).roundToInt().coerceIn(0, (current.size - 1).coerceAtLeast(0))
                    tryAwaitRelease()
                    touched = null
                })
            },
    ) {
        val labelSpace = 22.dp.toPx()
        val tipSpace = 34.dp.toPx()
        val chartTop = tipSpace
        val chartHeight = size.height - labelSpace - tipSpace
        fun x(day: Int) = if (days <= 1) 0f else size.width * day / (days - 1)
        fun y(cents: Long) = chartTop + chartHeight - (cents / top) * chartHeight

        // Baseline and the budget line.
        drawLine(line, Offset(0f, chartTop + chartHeight), Offset(size.width, chartTop + chartHeight), 1.dp.toPx())
        budgetCents?.let { budget ->
            if (budget < top) {
                drawLine(
                    faint.copy(alpha = 0.6f), Offset(0f, y(budget)), Offset(size.width, y(budget)), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
                )
                drawLabel(measurer, "BUDGET", Offset(0f, y(budget) - 16.dp.toPx()), faint)
            }
        }

        if (previous.isNotEmpty()) {
            drawPath(
                linePath(previous.mapIndexed { i, v -> Offset(x(i), y(v)) }),
                faint,
                style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))),
            )
        }
        if (current.isNotEmpty()) {
            val points = current.mapIndexed { i, v -> Offset(x(i), y(v)) }
            val stroke = linePath(points)
            val fill = Path().apply {
                addPath(stroke)
                lineTo(points.last().x, chartTop + chartHeight)
                lineTo(points.first().x, chartTop + chartHeight)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0f)), startY = chartTop, endY = chartTop + chartHeight))
            drawPath(stroke, ink, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val end = touched ?: current.lastIndex
            val dot = points[end]
            if (touched != null) {
                drawLine(ink.copy(alpha = 0.25f), Offset(dot.x, chartTop), Offset(dot.x, chartTop + chartHeight), 1.dp.toPx())
                previous.getOrNull(end)?.let { drawCircle(faint, 3.5.dp.toPx(), Offset(dot.x, y(it))) }
            }
            drawCircle(card, 7.dp.toPx(), dot)
            drawCircle(accent, 4.5.dp.toPx(), dot)

            touched?.let { day ->
                val previousText = previous.getOrNull(day)?.let { "  ·  $previousLabel ${Money.format(it)}" }.orEmpty()
                tooltip(measurer, "${dayLabel(day)}  ${Money.format(current[day])}$previousText", dot.x, tipBackground, tipText)
            }
        }

        // Day labels: first, middle, last.
        listOf(0, days / 2, days - 1).distinct().forEach { day ->
            val text = measurer.measure("${day + 1}", TextStyle(fontFamily = GeistMono, fontSize = 10.sp, color = faint))
            val left = (x(day) - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
            drawText(text, topLeft = Offset(left, size.height - text.size.height))
        }
    }
}

/** Bars per month, the latest in green. Tap a bar to see its total. */
@Composable
fun MonthBarChart(values: List<Long>, labels: List<String>, details: List<String>, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.secondary
    val bar = Tally.colors.chip
    val selectedBar = MaterialTheme.colorScheme.onSurface
    val faint = Tally.colors.faint
    val tipBackground = MaterialTheme.colorScheme.primary
    val tipText = MaterialTheme.colorScheme.onPrimary
    val measurer = rememberTextMeasurer()
    var selected by remember(values.size) { mutableIntStateOf(values.lastIndex) }
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(100).toFloat()

    Canvas(
        modifier
            .semantics { contentDescription = details.zip(labels).joinToString { (d, l) -> "$l $d" } }
            .pointerInput(values.size) {
                detectTapGestures { offset ->
                    selected = ((offset.x / size.width) * values.size).toInt().coerceIn(0, values.lastIndex)
                }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val labelSpace = 22.dp.toPx()
        val tipSpace = 34.dp.toPx()
        val chartHeight = size.height - labelSpace - tipSpace
        val slot = size.width / values.size
        val width = (slot * 0.56f).coerceAtMost(34.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = (v / max * chartHeight).coerceAtLeast(4.dp.toPx())
            val left = slot * i + (slot - width) / 2
            val color = when {
                i == selected && i == values.lastIndex -> accent
                i == selected -> selectedBar
                i == values.lastIndex -> accent.copy(alpha = 0.55f)
                else -> bar
            }
            drawRoundRect(color, Offset(left, tipSpace + chartHeight - h), Size(width, h), CornerRadius(minOf(8.dp.toPx(), width / 2)))
            val label = measurer.measure(labels[i], TextStyle(fontFamily = GeistMono, fontSize = 10.sp, color = if (i == selected) selectedBar else faint))
            drawText(label, topLeft = Offset(left + width / 2 - label.size.width / 2f, size.height - label.size.height))
        }
        val h = (values[selected] / max * chartHeight).coerceAtLeast(4.dp.toPx())
        tooltip(measurer, details[selected], slot * selected + slot / 2, tipBackground, tipText, anchorY = tipSpace + chartHeight - h)
    }
}

private fun linePath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    // Gentle curves between points; the running total never dips, so no overshoot to worry about.
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val mid = (a.x + b.x) / 2
        cubicTo(mid, a.y, mid, b.y, b.x, b.y)
    }
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, at: Offset, color: Color) {
    drawText(measurer.measure(text, TextStyle(fontFamily = GeistMono, fontSize = 9.sp, letterSpacing = 1.sp, color = color)), topLeft = at)
}

/** A dark pill with [text], centered over [centerX] and kept inside the chart. */
private fun DrawScope.tooltip(measurer: TextMeasurer, text: String, centerX: Float, background: Color, color: Color, anchorY: Float? = null) {
    val measured = measurer.measure(text, TextStyle(fontFamily = Geist, fontSize = 12.sp, color = color, fontFeatureSettings = "tnum"))
    val padX = 10.dp.toPx()
    val padY = 6.dp.toPx()
    val w = measured.size.width + padX * 2
    val h = measured.size.height + padY * 2
    val left = (centerX - w / 2).coerceIn(0f, size.width - w)
    val top = anchorY?.let { (it - h - 8.dp.toPx()).coerceAtLeast(0f) } ?: 0f
    drawRoundRect(background, Offset(left, top), Size(w, h), CornerRadius(h / 2))
    drawText(measured, topLeft = Offset(left + padX, top + padY))
}

/** "$1.2k" style labels for charts. */
fun compactMoney(cents: Long): String {
    val dollars = cents / 100.0
    return when {
        dollars >= 1000 -> "$" + "%.1fk".format(Locale.getDefault(), dollars / 1000)
        else -> "$" + "%.0f".format(Locale.getDefault(), dollars)
    }
}
