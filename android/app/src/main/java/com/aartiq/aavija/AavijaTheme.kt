package com.aartiq.aavija

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class AppThemeMode {
    SYSTEM,
    DARK,
    LIGHT;

    fun next(): AppThemeMode = when (this) {
        SYSTEM -> DARK
        DARK -> LIGHT
        LIGHT -> SYSTEM
    }
}

@Immutable
data class AavijaColors(
    val bgTop: Color,
    val bgBottom: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceBorder: Color,
    val surfaceBorderHighlight: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val primaryVariant: Color,
    val secondary: Color,
    val accentCyan: Color,
    val accentPurple: Color,
    val statusSuccess: Color,
    val statusWarning: Color,
    val statusError: Color,
    val isDark: Boolean,
) {
    val bgGradient: Brush
        get() = Brush.verticalGradient(listOf(bgTop, bgBottom))

    val cardGradient: Brush
        get() = Brush.linearGradient(listOf(surfaceElevated, surface))

    val activeGlowBrush: Brush
        get() = Brush.linearGradient(listOf(primary, secondary))
}

val DarkAavijaColors = AavijaColors(
    bgTop = Color(0xFF080B11),
    bgBottom = Color(0xFF0F1522),
    surface = Color(0xCC131926),
    surfaceElevated = Color(0xD91A2335),
    surfaceBorder = Color(0x26FFFFFF),
    surfaceBorderHighlight = Color(0x5900E5FF),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textTertiary = Color(0xFF64748B),
    primary = Color(0xFF00E5FF),
    primaryVariant = Color(0xFF0284C7),
    secondary = Color(0xFF8B5CF6),
    accentCyan = Color(0xFF38BDF8),
    accentPurple = Color(0xFFA855F7),
    statusSuccess = Color(0xFF10B981),
    statusWarning = Color(0xFFF59E0B),
    statusError = Color(0xFFEF4444),
    isDark = true,
)

val LightAavijaColors = AavijaColors(
    bgTop = Color(0xFFF8FAFD),
    bgBottom = Color(0xFFEEF2F8),
    surface = Color(0xE6FFFFFF),
    surfaceElevated = Color(0xE6F1F5F9),
    surfaceBorder = Color(0x33000000),
    surfaceBorderHighlight = Color(0x660284C7),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textTertiary = Color(0xFF94A3B8),
    primary = Color(0xFF0284C7),
    primaryVariant = Color(0xFF0369A1),
    secondary = Color(0xFF6366F1),
    accentCyan = Color(0xFF0EA5E9),
    accentPurple = Color(0xFF7C3AED),
    statusSuccess = Color(0xFF059669),
    statusWarning = Color(0xFFD97706),
    statusError = Color(0xFFDC2626),
    isDark = false,
)

val LocalAavijaColors = compositionLocalOf { DarkAavijaColors }
val LocalThemeMode = compositionLocalOf { AppThemeMode.DARK }

@Composable
fun AavijaAppTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemDark
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val targetColors = if (isDark) DarkAavijaColors else LightAavijaColors

    // Smoothly animate between light and dark palette transitions
    val bgTop by animateColorAsState(targetColors.bgTop, tween(300), label = "bgTop")
    val bgBottom by animateColorAsState(targetColors.bgBottom, tween(300), label = "bgBottom")
    val surface by animateColorAsState(targetColors.surface, tween(300), label = "surface")
    val surfaceElevated by animateColorAsState(targetColors.surfaceElevated, tween(300), label = "surfaceElevated")
    val surfaceBorder by animateColorAsState(targetColors.surfaceBorder, tween(300), label = "surfaceBorder")
    val textPrimary by animateColorAsState(targetColors.textPrimary, tween(300), label = "textPrimary")
    val textSecondary by animateColorAsState(targetColors.textSecondary, tween(300), label = "textSecondary")
    val primary by animateColorAsState(targetColors.primary, tween(300), label = "primary")
    val secondary by animateColorAsState(targetColors.secondary, tween(300), label = "secondary")

    val animatedColors = targetColors.copy(
        bgTop = bgTop,
        bgBottom = bgBottom,
        surface = surface,
        surfaceElevated = surfaceElevated,
        surfaceBorder = surfaceBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        primary = primary,
        secondary = secondary,
    )

    val materialColorScheme = if (isDark) {
        darkColorScheme(
            primary = targetColors.primary,
            secondary = targetColors.secondary,
            background = targetColors.bgTop,
            surface = targetColors.surface,
            onPrimary = Color.Black,
            onSecondary = Color.White,
            onBackground = targetColors.textPrimary,
            onSurface = targetColors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = targetColors.primary,
            secondary = targetColors.secondary,
            background = targetColors.bgTop,
            surface = targetColors.surface,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = targetColors.textPrimary,
            onSurface = targetColors.textPrimary,
        )
    }

    CompositionLocalProvider(
        LocalAavijaColors provides animatedColors,
        LocalThemeMode provides themeMode,
    ) {
        MaterialTheme(colorScheme = materialColorScheme) {
            content()
        }
    }
}
