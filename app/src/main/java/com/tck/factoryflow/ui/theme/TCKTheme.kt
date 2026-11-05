package com.tck.factoryflow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val TCKLightColorScheme = lightColorScheme(
    primary = TCKColors.Primary,
    onPrimary = TCKColors.TextOnPrimary,
    primaryContainer = TCKColors.PrimaryContainer,
    onPrimaryContainer = TCKColors.PrimaryDark,
    secondary = TCKColors.Navy,
    onSecondary = TCKColors.Surface,
    background = TCKColors.Background,
    onBackground = TCKColors.TextPrimary,
    surface = TCKColors.Surface,
    onSurface = TCKColors.TextPrimary,
    surfaceVariant = TCKColors.SurfaceVariant,
    onSurfaceVariant = TCKColors.TextSecondary,
    outline = TCKColors.Border,
    outlineVariant = TCKColors.BorderStrong,
    error = TCKColors.Error,
    onError = TCKColors.TextOnPrimary
)

@Composable
fun TCKTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TCKLightColorScheme,
        content = content
    )
}
