package com.viswa2k.smsforwarder.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = md_primary, onPrimary = md_onPrimary,
    primaryContainer = md_primaryContainer, onPrimaryContainer = md_onPrimaryContainer,
    secondary = md_secondary, onSecondary = md_onSecondary,
    secondaryContainer = md_secondaryContainer, onSecondaryContainer = md_onSecondaryContainer,
    tertiary = md_tertiary, onTertiary = md_onTertiary,
    tertiaryContainer = md_tertiaryContainer, onTertiaryContainer = md_onTertiaryContainer,
    background = md_background, onBackground = md_onBackground,
    surface = md_surface, onSurface = md_onSurface,
    surfaceVariant = md_surfaceVariant, onSurfaceVariant = md_onSurfaceVariant,
    outline = md_outline, outlineVariant = md_outlineVariant,
    error = md_error, onError = md_onError,
    errorContainer = md_errorContainer, onErrorContainer = md_onErrorContainer,
    surfaceTint = md_surfaceTint,
)

private val DarkColors = darkColorScheme(
    primary = md_primary_d, onPrimary = md_onPrimary_d,
    primaryContainer = md_primaryContainer_d, onPrimaryContainer = md_onPrimaryContainer_d,
    secondary = md_secondary_d, onSecondary = md_onSecondary_d,
    secondaryContainer = md_secondaryContainer_d, onSecondaryContainer = md_onSecondaryContainer_d,
    tertiary = md_tertiary_d, onTertiary = md_onTertiary_d,
    tertiaryContainer = md_tertiaryContainer_d, onTertiaryContainer = md_onTertiaryContainer_d,
    background = md_background_d, onBackground = md_onBackground_d,
    surface = md_surface_d, onSurface = md_onSurface_d,
    surfaceVariant = md_surfaceVariant_d, onSurfaceVariant = md_onSurfaceVariant_d,
    outline = md_outline_d, outlineVariant = md_outlineVariant_d,
    error = md_error_d, onError = md_onError_d,
    errorContainer = md_errorContainer_d, onErrorContainer = md_onErrorContainer_d,
    surfaceTint = md_primary_d,
)

@Composable
fun SMSForwarderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Default to the brand palette for a consistent modern look; opt into wallpaper-based
    // dynamic color explicitly if desired.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
