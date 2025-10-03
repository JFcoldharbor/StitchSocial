/*
 * Theme.kt - STITCH SOCIAL THEME SYSTEM
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Complete Material 3 theme integration with StitchColors
 * Features: Dark-first design, iOS 26 colors, dynamic color support
 *
 * UPDATED: Integrates with StitchColors.kt foundation color system
 * MATCHES: iOS 26 liquid glass purple theme throughout app
 */

package com.stitchsocial.club.ui.theme

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.stitchsocial.club.ui.theme.StitchColors
import com.stitchsocial.club.ui.theme.Typography

// MARK: - Stitch Social Color Schemes (iOS 26 Based)

/**
 * Dark color scheme - Primary theme for Stitch Social
 * Based on iOS 26 liquid glass purple theme with StitchColors integration
 */
private val StitchDarkColorScheme = darkColorScheme(
    // Primary brand colors from StitchColors
    primary = StitchColors.primary,           // Purple-500
    onPrimary = Color.White,
    primaryContainer = StitchColors.accent,   // Purple-700
    onPrimaryContainer = Color.White,

    // Secondary brand colors
    secondary = StitchColors.secondary,       // Purple-400
    onSecondary = Color.White,
    secondaryContainer = StitchColors.tertiary, // Light purple
    onSecondaryContainer = Color.White,

    // Tertiary colors
    tertiary = StitchColors.purpleGlow,       // Purple glow
    onTertiary = Color.White,
    tertiaryContainer = StitchColors.accent,
    onTertiaryContainer = Color.White,

    // Background colors - Pure black iOS style
    background = StitchColors.background,     // Pure black
    onBackground = StitchColors.textPrimary,  // White text

    // Surface colors - Cards and containers
    surface = StitchColors.surface,           // Dark gray surface
    onSurface = StitchColors.textPrimary,     // White text
    surfaceVariant = StitchColors.cardBackground, // Translucent black
    onSurfaceVariant = StitchColors.textSecondary, // Gray text

    // Outline and borders
    outline = StitchColors.glassBorder,       // Glass border effect
    outlineVariant = StitchColors.inputBorder, // Input borders

    // Error colors
    error = StitchColors.error,               // Red
    onError = Color.White,
    errorContainer = StitchColors.error.copy(alpha = 0.2f),
    onErrorContainer = StitchColors.error,

    // Surface tint for Material 3 elevation
    surfaceTint = StitchColors.primary,

    // Inverse colors for contrast
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    inversePrimary = StitchColors.primary,

    // Scrim for modals
    scrim = StitchColors.modalOverlay        // Black with transparency
)

/**
 * Light color scheme - Secondary theme (rarely used in video app)
 * Maintains purple branding but with light backgrounds
 */
private val StitchLightColorScheme = lightColorScheme(
    // Primary colors - Same purple branding
    primary = StitchColors.primary,
    onPrimary = Color.White,
    primaryContainer = StitchColors.primary.copy(alpha = 0.1f),
    onPrimaryContainer = StitchColors.primary,

    // Secondary colors
    secondary = StitchColors.secondary,
    onSecondary = Color.White,
    secondaryContainer = StitchColors.secondary.copy(alpha = 0.1f),
    onSecondaryContainer = StitchColors.secondary,

    // Tertiary colors
    tertiary = StitchColors.accent,
    onTertiary = Color.White,
    tertiaryContainer = StitchColors.accent.copy(alpha = 0.1f),
    onTertiaryContainer = StitchColors.accent,

    // Light backgrounds (for rare light mode usage)
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),

    // Light surfaces
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF4EFF4),
    onSurfaceVariant = Color(0xFF49454F),

    // Outline colors
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),

    // Error colors
    error = StitchColors.error,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    // Surface tint
    surfaceTint = StitchColors.primary,

    // Inverse colors
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = StitchColors.tertiary,

    // Scrim
    scrim = Color(0x80000000) // Semi-transparent black
)

// MARK: - Legacy Color Support (for migration)

/**
 * Legacy purple colors for backward compatibility
 * @deprecated Use StitchColors instead
 */
@Deprecated("Use StitchColors.primary instead", ReplaceWith("StitchColors.primary"))
val Purple80 = StitchColors.secondary

@Deprecated("Use StitchColors.tertiary instead", ReplaceWith("StitchColors.tertiary"))
val PurpleGrey80 = StitchColors.textSecondary

@Deprecated("Use StitchColors.accent instead", ReplaceWith("StitchColors.accent"))
val Pink80 = StitchColors.accent

@Deprecated("Use StitchColors.primary instead", ReplaceWith("StitchColors.primary"))
val Purple40 = StitchColors.primary

@Deprecated("Use StitchColors.textSecondary instead", ReplaceWith("StitchColors.textSecondary"))
val PurpleGrey40 = StitchColors.textSecondary

@Deprecated("Use StitchColors.secondary instead", ReplaceWith("StitchColors.secondary"))
val Pink40 = StitchColors.secondary

// MARK: - Main Theme Composable

/**
 * Stitch Social app theme with iOS 26 liquid glass integration
 *
 * @param darkTheme Force dark theme (default: system preference)
 * @param dynamicColor Enable Android 12+ dynamic colors (default: false for brand consistency)
 * @param content App content to theme
 */
@Composable
fun StitchSocialClubTheme(
    darkTheme: Boolean = true, // Changed: Default to dark theme (video app standard)
    dynamicColor: Boolean = false, // Changed: Disable by default for brand consistency
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic color support for Android 12+ (but prefer brand colors)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        // Use Stitch brand colors (preferred)
        darkTheme -> StitchDarkColorScheme
        else -> StitchLightColorScheme
    }

    val view = LocalView.current

    // Set system bar colors to match theme
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = StitchColors.background.toArgb() // Pure black status bar
            window.navigationBarColor = StitchColors.background.toArgb() // Pure black nav bar

            // Ensure status bar content is light (white icons/text on black background)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false // White status bar content
                isAppearanceLightNavigationBars = false // White nav bar content
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // Keep existing typography
        content = content
    )
}

// MARK: - Theme Extensions

/**
 * Access StitchColors from MaterialTheme for consistency
 */
val MaterialTheme.stitchColors: StitchColors
    @Composable
    get() = StitchColors

/**
 * Quick access to common Stitch colors
 */
object StitchTheme {
    val colors: StitchColors
        @Composable
        get() = StitchColors

    /**
     * Check if current theme is dark mode
     */
    val isDark: Boolean
        @Composable
        get() = !isSystemInDarkTheme() || true // Always consider dark for video app
}