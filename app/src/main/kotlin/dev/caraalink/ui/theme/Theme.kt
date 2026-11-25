package dev.caraalink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** Neutral dark palette shared by all screens. */
object Dash {
    val Background = Color(0xFF101216)
    val Tile = Color(0xFF1A1D23)
    val TileBorder = Color(0xFF30343D)
    val Track = Color(0xFF252A33)
    val Teal = Color(0xFFA7C7FF)
    val Cyan = Color(0xFF82A9F3)
    val Amber = Color(0xFFFFCC80)
    val Orange = Color(0xFFFFB86B)
    val Text = Color(0xFFF1F3F7)
    val Muted = Color(0xFFADB3BF)
    val GlowTeal = Color(0xFF25354E)
    val GlowAmber = Color(0xFF3B2D1C)
    val GlowIdle = Color(0xFF101216)
}

private val Scheme = darkColorScheme(
    primary = Dash.Teal, onPrimary = Color(0xFF132747),
    primaryContainer = Dash.GlowTeal, onPrimaryContainer = Color(0xFFD5E4FF),
    secondary = Dash.Cyan, onSecondary = Color(0xFF132747),
    tertiary = Dash.Amber, onTertiary = Color(0xFF1A0F02),
    tertiaryContainer = Dash.GlowAmber, onTertiaryContainer = Color(0xFFFDE68A),
    error = Dash.Amber, onError = Color(0xFF1A0F02),
    background = Dash.Background, onBackground = Dash.Text,
    surface = Dash.Background, onSurface = Dash.Text,
    surfaceVariant = Dash.Track, onSurfaceVariant = Dash.Muted,
    surfaceContainerLowest = Color(0xFF0D0F13), surfaceContainerLow = Color(0xFF171A20),
    surfaceContainer = Dash.Tile, surfaceContainerHigh = Color(0xFF21252D), surfaceContainerHighest = Color(0xFF2A2F38),
    outline = Dash.TileBorder, outlineVariant = Dash.Track,
)

private val Base = Typography()
private val Type = Base.copy(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun AaBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
