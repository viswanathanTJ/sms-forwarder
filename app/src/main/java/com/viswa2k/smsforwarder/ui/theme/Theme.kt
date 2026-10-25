package com.viswa2k.smsforwarder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = BrandPrimary, onPrimary = Color.White,
    primaryContainer = LightPrimarySoft, onPrimaryContainer = BrandPrimary,
    background = LightBackground, onBackground = LightText,
    surface = LightSurface, onSurface = LightText,
    surfaceVariant = LightSurfaceVariant, onSurfaceVariant = LightMuted,
    surfaceContainer = LightSurface, surfaceContainerHigh = LightSurface,
    outline = LightMuted, outlineVariant = LightLine,
    error = LightError, onError = Color.White,
    errorContainer = LightErrorSoft, onErrorContainer = LightError,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary, onPrimary = Color(0xFF0B1A5C),
    primaryContainer = DarkPrimarySoft, onPrimaryContainer = DarkPrimary,
    background = DarkBackground, onBackground = DarkText,
    surface = DarkSurface, onSurface = DarkText,
    surfaceVariant = DarkSurfaceVariant, onSurfaceVariant = DarkMuted,
    surfaceContainer = DarkSurface, surfaceContainerHigh = DarkSurface,
    outline = DarkMuted, outlineVariant = DarkLine,
    error = DarkError, onError = Color(0xFF4A0E0E),
    errorContainer = DarkErrorSoft, onErrorContainer = DarkError,
)

val LocalExtraColors = staticCompositionLocalOf { LightExtraColors }

/** Access to [ExtraColors] next to `MaterialTheme.colorScheme`. */
object ForwarderTheme {
    val extra: ExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtraColors.current
}

@Composable
fun SMSForwarderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalExtraColors provides if (darkTheme) DarkExtraColors else LightExtraColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            shapes = Shapes,
            content = content,
        )
    }
}
