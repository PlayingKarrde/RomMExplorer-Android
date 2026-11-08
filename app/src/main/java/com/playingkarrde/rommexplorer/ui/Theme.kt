package com.playingkarrde.rommexplorer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Background = Color(0xFF0C0E14)
    val Panel = Color(0xFF12151E)
    val Surface = Color(0xFF1A1E2B)
    val SurfaceHi = Color(0xFF23283A)
    val Border = Color(0xFF262B3D)
    val Accent = Color(0xFF7C5CFF)
    val AccentHi = Color(0xFF9C84FF)
    val Accent2 = Color(0xFF38BDF8)
    val Success = Color(0xFF22C55E)
    val Danger = Color(0xFFEF4444)
    val Warn = Color(0xFFF59E0B)
    val Text = Color(0xFFE8EAF2)
    val TextDim = Color(0xFFB2B7CA)
    val Muted = Color(0xFF7A8098)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    primaryContainer = Palette.Accent,
    onPrimaryContainer = Color.White,
    secondary = Palette.Accent2,
    onSecondary = Palette.Background,
    secondaryContainer = Palette.SurfaceHi,
    onSecondaryContainer = Palette.Text,
    tertiary = Palette.Success,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Background,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.Panel,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Surface,
    surfaceContainerHighest = Palette.SurfaceHi,
    outline = Palette.Border,
    outlineVariant = Palette.Border,
    error = Palette.Danger,
    onError = Color.White,
)

@Composable
fun RommTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
