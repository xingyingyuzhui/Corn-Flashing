package dev.corn.flashing.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Charcoal + corn-yellow palette from the approved mockups (screens 01–23). */
object CornColors {
    val Background = Color(0xFF121212)
    val Surface1 = Color(0xFF1B1B1B)
    val Surface2 = Color(0xFF232323)
    val Surface3 = Color(0xFF2C2C2C)
    val Line = Color(0xFF2F2F2F)
    val Text = Color(0xFFF3EFE4)
    val Dim = Color(0xFF9B968A)
    val Mute = Color(0xFF6D6961)
    val Corn = Color(0xFFF7C948)
    val CornDeep = Color(0xFFE9A93A)
    val CornOn = Color(0xFF1A1606)
    val CornContainer = Color(0x1FF7C948)
    val Leaf = Color(0xFF86A96F)
    val LeafContainer = Color(0x2486A96F)
    val Red = Color(0xFFEF6B5B)
    val RedContainer = Color(0x21EF6B5B)
    val Blue = Color(0xFF7FB0E8)
    val BlueContainer = Color(0x1F7FB0E8)
    val NavBackground = Color(0xFF161616)
}

private val CornScheme = darkColorScheme(
    primary = CornColors.Corn,
    onPrimary = CornColors.CornOn,
    primaryContainer = CornColors.CornContainer,
    onPrimaryContainer = CornColors.Corn,
    secondary = CornColors.Leaf,
    onSecondary = CornColors.CornOn,
    secondaryContainer = CornColors.LeafContainer,
    onSecondaryContainer = CornColors.Leaf,
    tertiary = CornColors.Red,
    onTertiary = CornColors.CornOn,
    tertiaryContainer = CornColors.RedContainer,
    onTertiaryContainer = CornColors.Red,
    error = CornColors.Red,
    onError = CornColors.CornOn,
    errorContainer = CornColors.RedContainer,
    onErrorContainer = CornColors.Red,
    background = CornColors.Background,
    onBackground = CornColors.Text,
    surface = CornColors.Surface1,
    onSurface = CornColors.Text,
    surfaceVariant = CornColors.Surface2,
    onSurfaceVariant = CornColors.Dim,
    surfaceContainer = CornColors.Surface1,
    surfaceContainerHigh = CornColors.Surface2,
    surfaceContainerHighest = CornColors.Surface3,
    outline = CornColors.Line,
    outlineVariant = CornColors.Line,
    scrim = Color(0xCC000000),
)

private val CornTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
        labelSmall = labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

val Mono = FontFamily.Monospace

@Composable
fun CornTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CornScheme,
        typography = CornTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

/** Big monospace number used by counters, progress and serial ranges. */
val numericStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
