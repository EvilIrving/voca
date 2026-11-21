package com.parley.speaker.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color

private val ParleyColors = lightColorScheme(
    primary = Palette.clay,
    onPrimary = Color.White,
    primaryContainer = Palette.clayTint,
    onPrimaryContainer = Palette.clayDeep,
    secondary = Palette.olive,
    onSecondary = Color.White,
    secondaryContainer = Palette.oliveTint,
    onSecondaryContainer = Palette.olive,
    background = Palette.paper,
    onBackground = Palette.ink,
    surface = Palette.card,
    onSurface = Palette.ink,
    surfaceVariant = Palette.tint,
    onSurfaceVariant = Palette.ink2,
    outline = Palette.hairStrong,
    outlineVariant = Palette.hair,
    error = Palette.danger,
    onError = Color.White,
)

@Composable
fun ParleyTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
    CompositionLocalProvider(LocalIndication provides ripple(color = Palette.ink4)) {
        MaterialTheme(
            colorScheme = ParleyColors,
            typography = ParleyTypography,
            content = content,
        )
    }
}
