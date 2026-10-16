package tally.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Our own palette; dynamic color stays off so the app looks the same on every phone.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5C4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EBD6),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4C6359),
    onSecondary = Color.White,
    tertiary = Color(0xFF8A5100),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF7FAF7),
    surface = Color(0xFFF7FAF7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD4BA),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF005139),
    onPrimaryContainer = Color(0xFFB8EBD6),
    secondary = Color(0xFFB3CCC0),
    onSecondary = Color(0xFF1E352B),
    tertiary = Color(0xFFFFB86E),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF0F1512),
    surface = Color(0xFF0F1512),
)

/** Status colors for the setup checklist. */
val OkGreen = Color(0xFF2E7D32)
val WarnAmber = Color(0xFFB26A00)

@Composable
fun TallyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
