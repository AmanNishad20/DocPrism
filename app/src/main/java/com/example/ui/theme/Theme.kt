package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.data.ThemePreference

private val DarkColorScheme = darkColorScheme(
    primary = M3PrimaryDark,
    onPrimary = M3OnPrimaryDark,
    primaryContainer = M3PrimaryContainerDark,
    onPrimaryContainer = M3OnPrimaryContainerDark,
    secondary = M3SecondaryDark,
    background = M3BackgroundDark,
    onBackground = Color(0xFFE2E2E6),
    surface = M3SurfaceDark,
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = M3SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFC2C7CE),
    outline = M3OutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = M3PrimaryLight,
    onPrimary = M3OnPrimaryLight,
    primaryContainer = M3PrimaryContainerLight,
    onPrimaryContainer = M3OnPrimaryContainerLight,
    secondary = M3SecondaryLight,
    background = M3BackgroundLight,
    onBackground = Color(0xFF1A1C1E),
    surface = M3SurfaceLight,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = M3SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF42474E),
    outline = M3OutlineLight
)

// Dedicated Eye-Comfort Sepia Reading Theme
private val ReadingColorScheme = lightColorScheme(
    primary = SepiaPrimary,
    onPrimary = SepiaOnPrimary,
    primaryContainer = SepiaPrimaryContainer,
    onPrimaryContainer = SepiaOnPrimaryContainer,
    secondary = Color(0xFF6B5D3F),
    background = SepiaBackground,
    onBackground = SepiaOnSurface,
    surface = SepiaSurface,
    onSurface = SepiaOnSurface,
    surfaceVariant = SepiaSurfaceVariant,
    onSurfaceVariant = Color(0xFF534631),
    outline = SepiaOutline
)

@Composable
fun MyApplicationTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current

    val colorScheme = when (themePreference) {
        ThemePreference.READING -> ReadingColorScheme
        ThemePreference.DARK -> {
            if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else {
                DarkColorScheme
            }
        }
        ThemePreference.LIGHT -> {
            if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(context)
            } else {
                LightColorScheme
            }
        }
        ThemePreference.SYSTEM -> {
            if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (systemDark) DarkColorScheme else LightColorScheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
