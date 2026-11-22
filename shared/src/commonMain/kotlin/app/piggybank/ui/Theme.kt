package app.piggybank.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colour tokens from the mockups. */
object Piggy {
    val Ink = Color(0xFF15171C)
    val Ground = Color(0xFFF2F3F5)
    val Card = Color(0xFFFFFFFF)
    val Muted = Color(0xFF5B606B)
    val Line = Color(0xFFE3E5EA)
    val Accent = Color(0xFFFF6F9C)

    // Top-level colours: Needs, Wants and Investments also differ in lightness, not just hue.
    val Needs = Color(0xFF3D5BDB)
    val Wants = Color(0xFFFFAE57)
    val Invest = Color(0xFF22B38A)
    val Hidden = Color(0xFF9AA0AC)
}

private val scheme = lightColorScheme(
    primary = Piggy.Ink,
    onPrimary = Color.White,
    secondary = Piggy.Accent,
    onSecondary = Piggy.Ink,
    background = Piggy.Ground,
    onBackground = Piggy.Ink,
    surface = Piggy.Card,
    onSurface = Piggy.Ink,
    onSurfaceVariant = Piggy.Muted,
    outline = Piggy.Line,
)

@Composable
fun PiggyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
