package com.tacticom.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkBg = Color(0xFF0B0E14)
private val DarkPanel = Color(0xFF141923)
private val AccentBlue = Color(0xFF388BFD)
private val AccentGreen = Color(0xFF2EA043)
private val AccentRed = Color(0xFFF85149)
private val AccentAmber = Color(0xFFD29922)

@Composable
fun TacticomTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = AccentBlue,
        secondary = AccentGreen,
        tertiary = AccentAmber,
        background = DarkBg,
        surface = DarkPanel,
        error = AccentRed,
        onBackground = Color(0xFFE6EDF3),
        onSurface = Color(0xFFE6EDF3),
        onPrimary = Color.White
    ) else lightColorScheme(
        primary = Color(0xFF0969DA),
        secondary = Color(0xFF1A7F37),
        tertiary = Color(0xFF9A6700),
        background = Color(0xFFF6F8FA),
        surface = Color(0xFFFFFFFF),
        error = AccentRed
    )
    MaterialTheme(colorScheme = colors, content = content)
}
