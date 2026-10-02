package io.evren.morsel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm palette: ivory card background, charcoal text, peach accent, golden kibble.
private val Ivory = Color(0xFFF5EFE2)
private val CardIvory = Color(0xFFFFF9EE)
private val Charcoal = Color(0xFF2E2A26)
private val CharcoalSoft = Color(0xFF5A524A)
private val Peach = Color(0xFFB36A3C)
private val PeachContainer = Color(0xFFF6D9BE)
private val Golden = Color(0xFFD9A05B)

private val DarkCard = Color(0xFF2A2521)
private val DarkBackground = Color(0xFF171310)
private val DarkText = Color(0xFFEDE6DA)
private val DarkTextSoft = Color(0xFFB8AEA1)
private val DarkPeach = Color(0xFFE8A87C)
private val DarkPeachContainer = Color(0xFF5A3E28)

private val LightColors = lightColorScheme(
    primary = Peach,
    onPrimary = Color.White,
    primaryContainer = PeachContainer,
    onPrimaryContainer = Charcoal,
    secondary = Golden,
    onSecondary = Charcoal,
    background = Color.Transparent,
    onBackground = Charcoal,
    surface = CardIvory,
    onSurface = Charcoal,
    surfaceVariant = Ivory,
    onSurfaceVariant = CharcoalSoft,
)

private val DarkColors = darkColorScheme(
    primary = DarkPeach,
    onPrimary = Color(0xFF2E1D10),
    primaryContainer = DarkPeachContainer,
    onPrimaryContainer = Color(0xFFF6D9BE),
    secondary = Golden,
    onSecondary = Color(0xFF2E1D10),
    background = Color.Transparent,
    onBackground = DarkText,
    surface = DarkCard,
    onSurface = DarkText,
    surfaceVariant = Color(0xFF3A332D),
    onSurfaceVariant = DarkTextSoft,
)

@Composable
fun MorselTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
