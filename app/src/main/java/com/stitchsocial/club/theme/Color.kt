/*
 * StitchColors.kt - COMPLETE STITCH SOCIAL COLOR SYSTEM
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Complete app color system
 * Dependencies: None (pure Kotlin/Compose colors)
 * Features: iOS 26 liquid glass colors, temperature-based colors, engagement colors
 *
 * COMPILED FROM: CustomDippedTabBar.kt, OverlayContext.kt, ParallelProcessingView.kt
 * MATCHES: iOS 26 design system with purple theme and liquid glass effects
 */

package com.stitchsocial.club.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Complete Stitch Social color system
 * Based on iOS 26 liquid glass design with purple theme
 */
object StitchColors {

    // MARK: - Primary Brand Colors (iOS 26 Purple Theme)

    /** Primary brand color - Purple-500 (iOS primary) */
    val primary = Color(0xFF8B5CF6)

    /** Secondary brand color - Purple-400 (iOS secondary) */
    val secondary = Color(0xFFA855F7)

    /** Accent color - Purple-700 (iOS accent) */
    val accent = Color(0xFF7C3AED)

    /** Tertiary purple for depth */
    val tertiary = Color(0xFFBB86FC)

    // MARK: - Background & Surface Colors

    /** Main app background - Pure black */
    val background = Color.Black

    /** Surface color for cards/containers */
    val surface = Color(0xFF1F2937)

    /** Secondary text color */
    val textSecondary = Color(0xFF9CA3AF)

    /** Primary text color */
    val textPrimary = Color.White

    // MARK: - iOS 26 Liquid Glass System

    /** Ultra thin material effect (iOS .ultraThinMaterial equivalent) */
    val ultraThinMaterial = Color.White.copy(alpha = 0.08f)

    /** Specular highlights for glass effects */
    val glassHighlight = Color.White.copy(alpha = 0.6f)

    /** Refraction effects */
    val glassRefraction = Color.White.copy(alpha = 0.3f)

    /** Environmental reflection */
    val environmentalReflection = primary.copy(alpha = 0.1f)

    /** Glass borders */
    val glassBorder = Color.White.copy(alpha = 0.4f)

    /** Purple glow effect */
    val purpleGlow = Color(0xFFBB86FC)

    // MARK: - Temperature-Based Colors (Video Engagement)

    /** Blazing hot content */
    val temperatureBlazingStart = Color.Red
    val temperatureBlazingEnd = Color(0xFFFF8C00) // Orange

    /** Hot content */
    val temperatureHotStart = Color.Red
    val temperatureHotEnd = Color(0xFFFF8C00) // Orange

    /** Warm content */
    val temperatureWarmStart = Color(0xFFFF8C00) // Orange
    val temperatureWarmEnd = Color.Yellow

    /** Cool content */
    val temperatureCoolStart = Color.Blue
    val temperatureCoolEnd = Color.Cyan

    /** Cold content */
    val temperatureColdStart = Color.Cyan
    val temperatureColdEnd = Color.Blue

    /** Frozen content */
    val temperatureFrozenStart = Color.Cyan
    val temperatureFrozenEnd = Color.Blue

    // MARK: - Engagement Action Colors

    /** Hype/Like action color */
    val hypeColor = Color.Red

    /** Cool/Dislike action color */
    val coolColor = Color.Cyan

    /** Reply/Comment action color */
    val replyColor = Color(0xFFFF8C00) // Orange

    /** Share action color */
    val shareColor = Color(0xFFFF8C00) // Orange

    /** Stitch action color */
    val stitchColor = Color(0xFFFF8C00) // Orange

    /** Thread view action color */
    val threadColor = Color(0xFFFF8C00) // Orange

    /** Follow action color */
    val followColor = Color.Cyan

    /** Following state color */
    val followingColor = Color.Green

    // MARK: - Thread Hierarchy Colors

    /** Parent thread indicator */
    val threadParent = Color.White

    /** Child reply indicator */
    val threadChild = Color.Cyan

    /** Thread container background */
    val threadContainer = Color.Black.copy(alpha = 0.4f)

    // MARK: - Processing & Progress Colors

    /** Audio processing color */
    val processAudio = Color(0xFF00CED1) // Turquoise

    /** Video compression color */
    val processCompression = Color(0xFFFF6B35) // Orange

    /** AI analysis color */
    val processAI = Color(0xFF7B68EE) // Purple

    /** Upload progress color */
    val processUpload = primary

    /** Success state color */
    val success = Color.Green

    /** Error state color */
    val error = Color.Red

    /** Warning state color */
    val warning = Color(0xFFFF8C00) // Orange

    // MARK: - UI Element Colors

    /** Modal overlay background */
    val modalOverlay = Color.Black.copy(alpha = 0.9f)

    /** Card background with transparency */
    val cardBackground = Color.Black.copy(alpha = 0.7f)

    /** Button disabled state */
    val buttonDisabled = Color.Gray.copy(alpha = 0.3f)

    /** Input field background */
    val inputBackground = Color.White.copy(alpha = 0.1f)

    /** Border color for inputs */
    val inputBorder = Color.White.copy(alpha = 0.2f)

    /** Placeholder text color */
    val placeholder = Color.Gray

    // MARK: - Tier Colors

    /** Business account tier color — teal, matches iOS StitchColors.tierBusiness */
    val tierBusiness = Color(0xFF00BCD4)

    // MARK: - Creator Pill Colors (Context-Aware)

    /** Creator pill for thread content */
    val creatorPillThread = listOf(Color(0xFF9C27B0), Color(0xFFE91E63)) // Purple to Pink

    /** Creator pill for neutral content */
    val creatorPillNeutral = listOf(Color.Gray, Color.LightGray)

    // MARK: - Engagement Button Borders

    /** Engagement button container */
    val engagementContainer = Color.Black.copy(alpha = 0.3f)

    /** Engagement button border alpha */
    const val engagementBorderAlpha = 0.4f

    // MARK: - Animation & Interaction Colors

    /** Ripple effect color */
    val ripple = primary.copy(alpha = 0.2f)

    /** Selection highlight */
    val selection = primary.copy(alpha = 0.1f)

    /** Hover state color */
    val hover = Color.White.copy(alpha = 0.05f)

    /** Focus indicator color */
    val focus = primary

    // MARK: - Shadow & Elevation Colors

    /** Drop shadow spot color */
    val shadowSpot = primary.copy(alpha = 0.4f)

    /** Drop shadow ambient color */
    val shadowAmbient = Color.Black.copy(alpha = 0.2f)

    /** Blur shadow color */
    val shadowBlur = Color.White.copy(alpha = 0.6f)

    // MARK: - Utility Functions

    /**
     * Get temperature gradient colors based on temperature enum
     */
    fun getTemperatureColors(temperature: com.stitchsocial.club.foundation.Temperature): List<Color> {
        return when (temperature) {
            com.stitchsocial.club.foundation.Temperature.BLAZING -> listOf(temperatureBlazingStart, temperatureBlazingEnd)
            com.stitchsocial.club.foundation.Temperature.HOT -> listOf(temperatureHotStart, temperatureHotEnd)
            com.stitchsocial.club.foundation.Temperature.WARM -> listOf(temperatureWarmStart, temperatureWarmEnd)
            com.stitchsocial.club.foundation.Temperature.COOL -> listOf(temperatureCoolStart, temperatureCoolEnd)
            com.stitchsocial.club.foundation.Temperature.COLD -> listOf(temperatureColdStart, temperatureColdEnd)
            com.stitchsocial.club.foundation.Temperature.FROZEN -> listOf(temperatureFrozenStart, temperatureFrozenEnd)
        }
    }

    /**
     * Get creator pill colors based on content type and temperature
     */
    fun getCreatorPillColors(
        isThread: Boolean = false,
        temperature: com.stitchsocial.club.foundation.Temperature = com.stitchsocial.club.foundation.Temperature.WARM
    ): List<Color> {
        return if (isThread) {
            creatorPillThread
        } else {
            getTemperatureColors(temperature)
        }
    }

    /**
     * Get engagement color by interaction type
     */
    fun getEngagementColor(type: String): Color {
        return when (type.lowercase()) {
            "hype" -> hypeColor
            "cool" -> coolColor
            "reply" -> replyColor
            "share" -> shareColor
            "stitch" -> stitchColor
            "thread" -> threadColor
            "follow" -> followColor
            else -> primary
        }
    }

    /**
     * Get processing color by phase
     */
    fun getProcessingColor(phase: String): Color {
        return when {
            phase.contains("audio", ignoreCase = true) -> processAudio
            phase.contains("compression", ignoreCase = true) || phase.contains("compress", ignoreCase = true) -> processCompression
            phase.contains("ai", ignoreCase = true) || phase.contains("analysis", ignoreCase = true) -> processAI
            phase.contains("upload", ignoreCase = true) -> processUpload
            else -> primary
        }
    }
}