package tally.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import tally.app.R

private fun geist(weight: Int) = Font(
    R.font.geist,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun geistMono(weight: Int) = Font(
    R.font.geist_mono,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Geist for everything; numbers use tabular figures so amounts line up. */
val Geist = FontFamily(geist(400), geist(500), geist(600), geist(700))

/** For the small letter-spaced labels ("SPENT IN OCTOBER"). */
val GeistMono = FontFamily(geistMono(400), geistMono(500))

private fun style(size: Int, weight: FontWeight, tracking: Double = 0.0, line: Int? = null) = TextStyle(
    fontFamily = Geist,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    lineHeight = line?.sp ?: TextUnit.Unspecified,
    fontFeatureSettings = "tnum",
)

val TallyTypography = Typography(
    displayLarge = style(60, FontWeight.SemiBold, -2.4, 64),
    displayMedium = style(48, FontWeight.SemiBold, -1.8, 54),
    displaySmall = style(38, FontWeight.SemiBold, -1.3, 44),
    headlineLarge = style(32, FontWeight.SemiBold, -1.0, 38),
    headlineMedium = style(28, FontWeight.SemiBold, -0.8, 34),
    headlineSmall = style(23, FontWeight.SemiBold, -0.5, 29),
    titleLarge = style(20, FontWeight.SemiBold, -0.3, 26),
    titleMedium = style(16, FontWeight.Medium, -0.1, 22),
    titleSmall = style(14, FontWeight.Medium, 0.0, 20),
    bodyLarge = style(16, FontWeight.Normal, -0.1, 23),
    bodyMedium = style(14, FontWeight.Normal, 0.0, 20),
    bodySmall = style(12, FontWeight.Normal, 0.1, 17),
    labelLarge = style(14, FontWeight.Medium, 0.0, 20),
    labelMedium = style(12, FontWeight.Medium, 0.1, 16),
    labelSmall = style(11, FontWeight.Medium, 0.2, 14),
)

/** The mono micro-label: uppercase text, set wide. */
val MonoLabelStyle = TextStyle(
    fontFamily = GeistMono,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    letterSpacing = 1.1.sp,
    lineHeight = 14.sp,
)
