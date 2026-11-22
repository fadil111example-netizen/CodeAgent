package id.codeagent.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0F3B38), onPrimaryContainer = Color(0xFFB8F3EA),
    secondary = Color(0xFFFBBF24), onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFF3B2F0A), onSecondaryContainer = Color(0xFFFFE9A8),
    background = Color(0xFF0B1216), onBackground = Color(0xFFE6EEF1),
    surface = Color(0xFF0B1216), onSurface = Color(0xFFE6EEF1),
    surfaceVariant = Color(0xFF16242B), onSurfaceVariant = Color(0xFF9FB3BC),
    outline = Color(0xFF3A4D56), error = Color(0xFFFF8A80),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F766E), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCF5EE), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF9A6700), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFEFC2), onSecondaryContainer = Color(0xFF2B1D00),
    background = Color(0xFFF6FAFA), onBackground = Color(0xFF0E1A1F),
    surface = Color(0xFFF6FAFA), onSurface = Color(0xFF0E1A1F),
    surfaceVariant = Color(0xFFE3EEEE), onSurfaceVariant = Color(0xFF475A62),
    outline = Color(0xFF8AA0A8), error = Color(0xFFB3261E),
)

@Composable
fun CodeAgentTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
