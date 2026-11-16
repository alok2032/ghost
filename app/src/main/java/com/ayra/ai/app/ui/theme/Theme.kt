package com.ayra.ai.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

data class AyraUiConfig(
    val themeMode: String = "dark",
    val isDark: Boolean = true,
    val isHighContrast: Boolean = false,
    val accentColor: Color = NeonCyan,
    val secondaryAccentColor: Color = NeonPurple,
    val fontScale: Float = 1.0f,
    val bubbleCornerDp: Int = 18,
    val uiDensity: String = "balanced",
    val reducedMotion: Boolean = false
) {
    val contentSpacingDp get() = when (uiDensity) {
        "compact" -> 8.dp
        "spacious" -> 18.dp
        else -> 13.dp
    }
    val bubblePaddingDp get() = when (uiDensity) {
        "compact" -> 10.dp
        "spacious" -> 18.dp
        else -> 14.dp
    }
}

val LocalAyraUiConfig = compositionLocalOf { AyraUiConfig() }

fun resolveAccentColor(preset: String, customHex: String, isDark: Boolean): Pair<Color, Color> {
    return when (preset.lowercase()) {
        "blue" -> (if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)) to Color(0xFF6366F1)
        "purple" -> (if (isDark) Color(0xFFC084FC) else Color(0xFF7C3AED)) to Color(0xFFF472B6)
        "green" -> (if (isDark) Color(0xFF34D399) else Color(0xFF059669)) to Color(0xFF06B6D4)
        "orange" -> (if (isDark) Color(0xFFFB923C) else Color(0xFFEA580C)) to Color(0xFFF59E0B)
        "pink" -> (if (isDark) Color(0xFFF472B6) else Color(0xFFDB2777)) to Color(0xFFA855F7)
        "red" -> (if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)) to Color(0xFFFB923C)
        "custom" -> {
            val parsed = runCatching { Color(android.graphics.Color.parseColor(customHex)) }.getOrNull()
                ?: if (isDark) NeonCyan else LightPrimaryAccent
            parsed to NeonPurple
        }
        else -> (if (isDark) NeonCyan else LightPrimaryAccent) to (if (isDark) NeonPurple else LightSecondaryAccent)
    }
}

private fun scaleTypography(base: Typography, scale: Float): Typography {
    if (scale == 1.0f) return base
    return base.copy(
        displayLarge = base.displayLarge.copy(fontSize = base.displayLarge.fontSize * scale),
        headlineLarge = base.headlineLarge.copy(fontSize = base.headlineLarge.fontSize * scale),
        headlineMedium = base.headlineMedium.copy(fontSize = base.headlineMedium.fontSize * scale),
        titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize * scale),
        titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize * scale),
        titleSmall = base.titleSmall.copy(fontSize = base.titleSmall.fontSize * scale),
        bodyLarge = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * scale),
        bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize * scale),
        bodySmall = base.bodySmall.copy(fontSize = base.bodySmall.fontSize * scale),
        labelLarge = base.labelLarge.copy(fontSize = base.labelLarge.fontSize * scale),
        labelMedium = base.labelMedium.copy(fontSize = base.labelMedium.fontSize * scale),
        labelSmall = base.labelSmall.copy(fontSize = base.labelSmall.fontSize * scale)
    )
}

@Composable
fun AyraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: String = if (darkTheme) "dark" else "light",
    accentPreset: String = "cyan",
    customAccentHex: String = "#00F0FF",
    fontScale: Float = 1.0f,
    bubbleCornerDp: Int = 18,
    uiDensity: String = "balanced",
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val isHighContrast = themeMode == "high_contrast"
    val effectiveDark = if (isHighContrast) true else darkTheme
    val (primaryAccent, secondaryAccent) = resolveAccentColor(accentPreset, customAccentHex, effectiveDark)

    val colorScheme = when {
        isHighContrast -> darkColorScheme(
            primary = Color(0xFF00FFFF),
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF141414),
            onPrimaryContainer = Color.White,
            secondary = Color(0xFFFFD600),
            onSecondary = Color.Black,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color(0xFF0A0A0A),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1A1A1A),
            onSurfaceVariant = Color(0xFFE2E8F0)
        )
        effectiveDark -> darkColorScheme(
            primary = primaryAccent,
            onPrimary = CosmicBackground,
            primaryContainer = GlassSurfaceDark,
            onPrimaryContainer = TextPrimary,
            secondary = secondaryAccent,
            onSecondary = CosmicBackground,
            background = if (themeMode == "custom") Color(0xFF060916) else CosmicBackground,
            onBackground = TextPrimary,
            surface = if (themeMode == "custom") Color(0xFF10162F) else CosmicSurface,
            onSurface = TextPrimary,
            surfaceVariant = Color(0xFF1E293B),
            onSurfaceVariant = TextSecondary
        )
        else -> lightColorScheme(
            primary = primaryAccent,
            onPrimary = Color.White,
            primaryContainer = LightSurfaceVariant,
            onPrimaryContainer = LightTextPrimary,
            secondary = secondaryAccent,
            onSecondary = Color.White,
            background = LightBackground,
            onBackground = LightTextPrimary,
            surface = LightSurface,
            onSurface = LightTextPrimary,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = LightTextSecondary
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !effectiveDark
                controller.isAppearanceLightNavigationBars = !effectiveDark
            }
        }
    }

    val uiConfig = AyraUiConfig(
        themeMode = themeMode,
        isDark = effectiveDark,
        isHighContrast = isHighContrast,
        accentColor = primaryAccent,
        secondaryAccentColor = secondaryAccent,
        fontScale = fontScale,
        bubbleCornerDp = bubbleCornerDp,
        uiDensity = uiDensity,
        reducedMotion = reducedMotion
    )

    CompositionLocalProvider(LocalAyraUiConfig provides uiConfig) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = scaleTypography(Typography, fontScale),
            content = content
        )
    }
}
