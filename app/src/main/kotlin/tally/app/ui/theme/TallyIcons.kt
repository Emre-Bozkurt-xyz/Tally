package tally.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Thin line icons on a 24 grid, tinted by Icon like any other. */
object TallyIcons {
    private fun icon(name: String, vararg paths: String, stroke: Float = 1.8f, filled: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Home by lazy {
        icon(
            "home",
            "M4 10.2a2 2 0 0 1 .7-1.5l6-5.2a2 2 0 0 1 2.6 0l6 5.2a2 2 0 0 1 .7 1.5V19a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z",
            "M9.5 21v-6.5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1V21",
        )
    }
    val Activity by lazy { icon("activity", "M9 6h11", "M9 12h11", "M9 18h11", "M4.5 6h.01", "M4.5 12h.01", "M4.5 18h.01", stroke = 2f) }
    val Inbox by lazy {
        icon(
            "inbox",
            "M21.5 12.5h-5.5l-1.8 2.8h-4.4l-1.8-2.8H2.5",
            "M5.6 5.2 2.5 12.5V18a2 2 0 0 0 2 2h15a2 2 0 0 0 2-2v-5.5l-3.1-7.3A2 2 0 0 0 16.6 4H7.4a2 2 0 0 0-1.8 1.2z",
        )
    }
    val More by lazy { icon("more", "M5 5h5v5H5z", "M14 5h5v5h-5z", "M5 14h5v5H5z", "M14 14h5v5h-5z") }
    val Plus by lazy { icon("plus", "M12 5v14", "M5 12h14", stroke = 2.2f) }
    val Back by lazy { icon("back", "M15 18l-6-6 6-6", stroke = 2f) }
    val Forward by lazy { icon("forward", "M9 18l6-6-6-6", stroke = 2f) }
    val Down by lazy { icon("down", "M6 9l6 6 6-6", stroke = 2f) }
    val Up by lazy { icon("up", "M18 15l-6-6-6 6", stroke = 2f) }
    val Check by lazy { icon("check", "M20 6 9 17l-5-5", stroke = 2.2f) }
    val Close by lazy { icon("close", "M18 6 6 18", "M6 6l12 12", stroke = 2f) }
    val Trash by lazy {
        icon("trash", "M4 7h16", "M18 7v12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V7", "M9 7V5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2", "M10 11v6", "M14 11v6")
    }
    val Pencil by lazy { icon("pencil", "M16.9 3.6a2 2 0 0 1 2.9 2.9L8 18.3l-4 1.1 1.1-4z", "M14.5 6l3.5 3.5") }
    val Note by lazy { icon("note", "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z", "M14 3v6h6", "M8 13h8", "M8 17h5") }
    val Store by lazy { icon("store", "M3 9l1.6-4.3A1 1 0 0 1 5.5 4h13a1 1 0 0 1 .9.7L21 9", "M3 9h18v1.5a2.5 2.5 0 0 1-4.5 1.5 2.5 2.5 0 0 1-4.5 0 2.5 2.5 0 0 1-4.5 0A2.5 2.5 0 0 1 3 10.5z", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6", "M10 21v-4h4v4") }
    val Wallet by lazy { icon("wallet", "M19 7V5a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h14a1 1 0 0 1 1 1v3h-3a2 2 0 0 0 0 4h3v3a1 1 0 0 1-1 1H5a2 2 0 0 1-2-2V6") }
    val Calendar by lazy { icon("calendar", "M5 4.5h14a2 2 0 0 1 2 2V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6.5a2 2 0 0 1 2-2z", "M3 10h18", "M8 2.5v4", "M16 2.5v4") }
    val Chart by lazy { icon("chart", "M3 3v16a2 2 0 0 0 2 2h16", "M8 16v-3", "M13 16V8", "M18 16v-6") }
    val Trend by lazy { icon("trend", "M22 7l-8.5 8.5-5-5L2 17", "M16 7h6v6") }
    val Repeat by lazy { icon("repeat", "M17 2l4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "M7 22l-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3") }
    val People by lazy {
        icon("people", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z", "M22 21v-2a4 4 0 0 0-3-3.9", "M16 3.1a4 4 0 0 1 0 7.8")
    }
    val Shapes by lazy { icon("shapes", "M8.3 3 3 12h10.6z", "M14 14h7v7h-7z", "M17.5 3a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z", "M6.5 14.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z") }
    val Wand by lazy { icon("wand", "M15 4V2", "M15 16v-2", "M8 9h2", "M20 9h2", "M17.8 11.8 19 13", "M17.8 6.2 19 5", "M3 21l9-9", "M12.2 6.2 11 5") }
    val Sync by lazy { icon("sync", "M3 12a9 9 0 0 1 15.7-6L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-15.7 6L3 16", "M8 16H3v5") }
    val Sliders by lazy { icon("sliders", "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3", "M14 2v4", "M8 10v4", "M16 18v4") }
    val Bell by lazy {
        icon("bell", "M10.3 21a2 2 0 0 0 3.4 0", "M3.3 15.3A1 1 0 0 0 4 17h16a1 1 0 0 0 .7-1.7C19.4 14 18 12.5 18 8A6 6 0 0 0 6 8c0 4.5-1.4 6-2.7 7.3z")
    }
    val Tag by lazy {
        icon("tag", "M12.6 2.6A2 2 0 0 0 11.2 2H4a2 2 0 0 0-2 2v7.2a2 2 0 0 0 .6 1.4l8.7 8.7a2.4 2.4 0 0 0 3.4 0l6.6-6.6a2.4 2.4 0 0 0 0-3.4z", "M7.5 7.5h.01", stroke = 1.8f)
    }
    val Split by lazy { icon("split", "M16 3h5v5", "M8 3H3v5", "M12 22v-8.3a4 4 0 0 0-1.2-2.9L3 3", "M15 9l6-6") }
    val Backspace by lazy {
        icon("backspace", "M10 5a2 2 0 0 0-1.3.5l-6.3 5.7a1 1 0 0 0 0 1.6l6.3 5.7A2 2 0 0 0 10 19h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z", "M12 9l6 6", "M18 9l-6 6")
    }
    val ArrowUpRight by lazy { icon("arrow-up-right", "M7 17 17 7", "M8 7h9v9", stroke = 2f) }
    val ArrowDownRight by lazy { icon("arrow-down-right", "M7 7l10 10", "M17 8v9H8", stroke = 2f) }
    val Star by lazy { icon("star", STAR) }
    val StarFilled by lazy { icon("star-filled", STAR, filled = true) }
    private const val STAR = "M12 2.8l2.8 5.8 6.3.9-4.6 4.4 1.1 6.3L12 17.2l-5.6 3 1.1-6.3-4.6-4.4 6.3-.9z"
    val Dots by lazy { icon("dots", "M5 12h.01", "M12 12h.01", "M19 12h.01", stroke = 3f) }
    val Download by lazy { icon("download", "M12 3v12", "M7 10l5 5 5-5", "M5 21h14") }
    val Code by lazy { icon("code", "M16 18l6-6-6-6", "M8 6l-6 6 6 6") }
    val Phone by lazy { icon("phone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2") }
}
