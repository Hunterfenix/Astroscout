package com.astroscout.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette "notturna": il blu profondo non abbaglia sul campo e mantiene l'adattamento al buio.
private val NightBlue = Color(0xFF0B1026)
private val Indigo = Color(0xFF8C9EFF)
private val StarWhite = Color(0xFFE9EEFF)

val BortleDark = Color(0xFF2E7D32)
val BortleModerate = Color(0xFFF9A825)
val BortleBright = Color(0xFFC62828)
val Unknown = Color(0xFF9E9E9E)

private val DarkColors = darkColorScheme(
    primary = Indigo,
    onPrimary = NightBlue,
    secondary = Color(0xFF80CBC4),
    background = NightBlue,
    surface = Color(0xFF141A36),
    onSurface = StarWhite,
    onBackground = StarWhite,
    surfaceContainerLow = Color(0xFF161D3C),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3949AB),
    secondary = Color(0xFF00897B),
)

@Composable
fun AstroScoutTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
