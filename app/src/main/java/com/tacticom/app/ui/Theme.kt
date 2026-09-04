package com.tacticom.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF388BFD), onPrimary = Color.White,
    secondary = Color(0xFF2EA043), onSecondary = Color.White,
    background = Color(0xFF0B0E14), onBackground = Color(0xFFE6EDF3), // Light text forced
    surface = Color(0xFF141923), onSurface = Color(0xFFE6EDF3),     // Light text forced
    surfaceVariant = Color(0xFF232D3F), onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFF85149), onError = Color.White
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0969DA), onPrimary = Color.White,
    secondary = Color(0xFF1A7F37), onSecondary = Color.White,
    background = Color(0xFFF6F8FA), onBackground = Color(0xFF1F2328), // Dark text forced
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1F2328),     // Dark text forced
    surfaceVariant = Color(0xFFE1E4E8), onSurfaceVariant = Color(0xFF57606A),
    error = Color(0xFFD1242F), onError = Color.White
)

@Composable
fun TacticomTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
