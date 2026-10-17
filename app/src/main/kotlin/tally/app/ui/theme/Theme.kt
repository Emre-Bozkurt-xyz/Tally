package tally.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Warm paper and ink, one green for money in and progress. Dynamic color stays off so the
 * app looks the same on every phone. The dashboard's CSS uses the same values.
 */

private val Paper = Color(0xFFF4F2ED)
private val Ink = Color(0xFF1B1A17)
private val Night = Color(0xFF11100E)
private val Cream = Color(0xFFEFEBE3)

internal val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color(0xFFF7F5F0),
    primaryContainer = Color(0xFFE9E6DF),
    onPrimaryContainer = Ink,
    secondary = Color(0xFF2F6B4E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEADF),
    onSecondaryContainer = Color(0xFF173A29),
    tertiary = Color(0xFFA3661A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4E3C8),
    onTertiaryContainer = Color(0xFF4A2D06),
    error = Color(0xFFB3432E),
    onError = Color.White,
    errorContainer = Color(0xFFF5DDD6),
    onErrorContainer = Color(0xFF5C1B10),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9E6DF),
    onSurfaceVariant = Color(0xFF6E6A62),
    surfaceTint = Paper,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDCD8CE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF9F6),
    surfaceContainer = Color(0xFFEFECE6),
    surfaceContainerHigh = Color(0xFFE9E6DF),
    surfaceContainerHighest = Color(0xFFE2DED6),
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = Cream,
    outline = Color(0xFFA9A499),
    outlineVariant = Color(0xFFE3DFD6),
    scrim = Color.Black,
)

internal val DarkColors = darkColorScheme(
    primary = Cream,
    onPrimary = Color(0xFF171614),
    primaryContainer = Color(0xFF2A2825),
    onPrimaryContainer = Cream,
    secondary = Color(0xFF8CCBA6),
    onSecondary = Color(0xFF0E2A1C),
    secondaryContainer = Color(0xFF1F3529),
    onSecondaryContainer = Color(0xFFC3E7D0),
    tertiary = Color(0xFFE0A85A),
    onTertiary = Color(0xFF2E1D05),
    tertiaryContainer = Color(0xFF3D2C14),
    onTertiaryContainer = Color(0xFFF4D9AE),
    error = Color(0xFFEE917D),
    onError = Color(0xFF3A120A),
    errorContainer = Color(0xFF4A2017),
    onErrorContainer = Color(0xFFF8D3CA),
    background = Night,
    onBackground = Cream,
    surface = Night,
    onSurface = Cream,
    surfaceVariant = Color(0xFF282623),
    onSurfaceVariant = Color(0xFFA29D93),
    surfaceTint = Night,
    surfaceBright = Color(0xFF33312D),
    surfaceDim = Night,
    surfaceContainerLowest = Color(0xFF0C0B0A),
    surfaceContainerLow = Color(0xFF1B1A17),
    surfaceContainer = Color(0xFF211F1C),
    surfaceContainerHigh = Color(0xFF292724),
    surfaceContainerHighest = Color(0xFF33312D),
    inverseSurface = Cream,
    inverseOnSurface = Color(0xFF171614),
    inversePrimary = Ink,
    outline = Color(0xFF6A665E),
    outlineVariant = Color(0xFF2E2C28),
    scrim = Color.Black,
)

/** The roles Material doesn't have: cards, the dark hero card, money in, and soft avatar tints. */
@Immutable
data class TallyColors(
    val card: Color,
    val hero: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    val heroAccent: Color,
    val heroTrack: Color,
    val positive: Color,
    val positiveSoft: Color,
    val warn: Color,
    val over: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val chip: Color,
    val navBar: Color,
    val onNavBar: Color,
    val navSelected: Color,
    val onNavSelected: Color,
    /** Background and foreground pairs for letter avatars, picked per category. */
    val tints: List<Pair<Color, Color>>,
)

internal val LightTally = TallyColors(
    card = Color.White,
    hero = Ink,
    onHero = Color(0xFFF4F2ED),
    onHeroMuted = Color(0xFFA8A398),
    heroAccent = Color(0xFF8CCBA6),
    heroTrack = Color(0xFF34322D),
    positive = Color(0xFF2F6B4E),
    positiveSoft = Color(0xFFDDEADF),
    warn = Color(0xFFA3661A),
    over = Color(0xFFB3432E),
    muted = Color(0xFF6E6A62),
    faint = Color(0xFFA9A499),
    line = Color(0xFFE8E4DC),
    chip = Color(0xFFE9E6DF),
    navBar = Ink,
    onNavBar = Color(0xFF9D988D),
    navSelected = Color(0xFFF4F2ED),
    onNavSelected = Ink,
    tints = listOf(
        Color(0xFFDCE7DC) to Color(0xFF2F5A41),
        Color(0xFFF0E4CF) to Color(0xFF6B4E1F),
        Color(0xFFF2DDD4) to Color(0xFF7A3A26),
        Color(0xFFDDE3EA) to Color(0xFF33475B),
        Color(0xFFE9DFE8) to Color(0xFF5A3A5A),
        Color(0xFFE6E6D2) to Color(0xFF4F5226),
    ),
)

internal val DarkTally = TallyColors(
    card = Color(0xFF1B1A17),
    hero = Color(0xFFECE7DD),
    onHero = Color(0xFF171614),
    onHeroMuted = Color(0xFF6F6A60),
    heroAccent = Color(0xFF2F6B4E),
    heroTrack = Color(0xFFD5CFC3),
    positive = Color(0xFF8CCBA6),
    positiveSoft = Color(0xFF1F3529),
    warn = Color(0xFFE0A85A),
    over = Color(0xFFEE917D),
    muted = Color(0xFFA29D93),
    faint = Color(0xFF6A665E),
    line = Color(0xFF2A2825),
    chip = Color(0xFF262421),
    navBar = Color(0xFF262421),
    onNavBar = Color(0xFF8C877D),
    navSelected = Cream,
    onNavSelected = Color(0xFF171614),
    tints = listOf(
        Color(0xFF22332A) to Color(0xFFA9D3B6),
        Color(0xFF382E1F) to Color(0xFFE3C792),
        Color(0xFF3B251E) to Color(0xFFEBAE98),
        Color(0xFF232B35) to Color(0xFFAFC2D6),
        Color(0xFF32263A) to Color(0xFFD6B6D6),
        Color(0xFF2E2F20) to Color(0xFFCBCD97),
    ),
)

private val LocalTallyColors = staticCompositionLocalOf { LightTally }

/** Rounded everywhere; chips (the "small" shape) are full pills. */
private val TallyShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(percent = 50),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

object Tally {
    val colors: TallyColors
        @Composable @ReadOnlyComposable get() = LocalTallyColors.current
}

@Composable
fun TallyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTallyColors provides if (dark) DarkTally else LightTally) {
        val scheme = if (dark) DarkColors else LightColors
        MaterialTheme(colorScheme = scheme, typography = TallyTypography, shapes = TallyShapes) {
            // Screens draw straight on the page, not on a Surface, so set the text color here.
            CompositionLocalProvider(
                LocalContentColor provides scheme.onBackground,
                LocalTextStyle provides TallyTypography.bodyLarge,
                content = content,
            )
        }
    }
}

/** Status colors for the setup checklist. */
val OkGreen = Color(0xFF3E8A63)
val WarnAmber = Color(0xFFB8781F)
