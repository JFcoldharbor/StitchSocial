/*
 * StitchTheme.kt - ADAPTIVE LIGHT/DARK NEUTRALS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Port of iOS Theme.swift. Semantic, theme-adaptive NEUTRALS (bg / text /
 * surface / hairline) that flip between light and dark. Brand colors (magenta,
 * tiers, business, money) live in StitchColors and intentionally do NOT adapt.
 *
 * Usage in composables: StitchTheme.colors.bg / .textPrimary / .surface / ...
 */

package com.stitchsocial.club.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** User-selectable appearance (persisted in settings). Mirrors iOS AppThemeMode. */
enum class AppThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun from(raw: String?): AppThemeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

/** Adaptive neutral tokens (only neutrals flip; brand stays fixed). */
data class StitchSemanticColors(
    val bg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val surface: Color,
    val surfaceStrong: Color,
    val hairline: Color
)

/** Dark scheme — matches iOS Theme dark values. */
val DarkSemanticColors = StitchSemanticColors(
    bg = Color.Black,
    textPrimary = Color.White,
    textSecondary = Color.White.copy(alpha = 0.60f),
    textTertiary = Color.White.copy(alpha = 0.30f),
    surface = Color.White.copy(alpha = 0.06f),
    surfaceStrong = Color.White.copy(alpha = 0.10f),
    hairline = Color.White.copy(alpha = 0.12f)
)

/** Light scheme — matches iOS Theme light values. */
val LightSemanticColors = StitchSemanticColors(
    bg = Color.White,
    textPrimary = Color(0xFF1A1A1A),
    textSecondary = Color.Black.copy(alpha = 0.60f),
    textTertiary = Color.Black.copy(alpha = 0.30f),
    surface = Color.Black.copy(alpha = 0.04f),
    surfaceStrong = Color.Black.copy(alpha = 0.06f),
    hairline = Color.Black.copy(alpha = 0.12f)
)

/** Provided by StitchSocialClubTheme; defaults to dark. */
val LocalStitchColors = staticCompositionLocalOf { DarkSemanticColors }

/**
 * Access adaptive neutrals in a composable: `AppTheme.colors.bg`.
 * (Named AppTheme to avoid the existing `StitchTheme` in Theme.kt, whose
 * `.colors` returns the fixed brand StitchColors.)
 */
object AppTheme {
    val colors: StitchSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStitchColors.current
}

/**
 * Holds the selected AppThemeMode as Compose state + persists it to SharedPrefs
 * ("stitch_prefs" / "app_theme"). MainActivity reads ThemeState.mode and passes
 * it to StitchSocialClubTheme; the Appearance picker calls setMode().
 */
object ThemeState {
    var mode by mutableStateOf(AppThemeMode.DARK)
        private set

    fun load(context: Context) {
        val raw = context.getSharedPreferences("stitch_prefs", Context.MODE_PRIVATE)
            .getString("app_theme", null)
        mode = AppThemeMode.from(raw)
    }

    fun setMode(context: Context, newMode: AppThemeMode) {
        mode = newMode
        context.getSharedPreferences("stitch_prefs", Context.MODE_PRIVATE)
            .edit().putString("app_theme", newMode.name).apply()
    }
}
