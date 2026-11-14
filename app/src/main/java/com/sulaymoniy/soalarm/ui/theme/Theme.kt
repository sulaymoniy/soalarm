package com.sulaymoniy.soalarm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// "Fresh water" palette: a clear blue with a soft aqua accent.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF00838F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F1F7),
    onSecondaryContainer = Color(0xFF002024),
    background = Color(0xFFF8FAFF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8FAFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainer = Color(0xFFECF0F8),
    surfaceContainerHigh = Color(0xFFE6EAF2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA3C9FF),
    onPrimary = Color(0xFF00315E),
    primaryContainer = Color(0xFF004884),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFF7FD6E0),
    onSecondary = Color(0xFF00363C),
    secondaryContainer = Color(0xFF004F57),
    onSecondaryContainer = Color(0xFFB8F1F7),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
)

@Composable
fun SoAlarmTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
