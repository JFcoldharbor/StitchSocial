/*
 * FloatingIconType.kt - iOS-STYLE FLOATING ICONS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * iOS-STYLE: Flames float up on hype, snowflakes on cool
 */

package com.stitchsocial.club.engagement

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.stitchsocial.club.foundation.UserTier
import java.util.UUID

enum class FloatingIconType {
    HYPE,   // 🔥 Flames
    COOL    // ❄️ Snowflakes
}

enum class IconAnimationType {
    STANDARD,           // Normal single icon
    FOUNDER_EXPLOSION,  // Founder's first tap = explosion
    TIER_BOOST,         // Premium tier boost
    MILESTONE           // Milestone reached burst
}

data class FloatingIcon(
    val id: String = UUID.randomUUID().toString(),
    val startPosition: Offset,
    val iconType: FloatingIconType,
    val animationType: IconAnimationType,
    val tier: UserTier,
    val spawnTime: Long = System.currentTimeMillis()
) {
    fun isExpired(): Boolean = (System.currentTimeMillis() - spawnTime) > 3500

    // iOS-STYLE: Always use flame for hype, snowflake for cool
    fun getIconSymbol(): String {
        return when (iconType) {
            FloatingIconType.HYPE -> "flame"      // Always flame 🔥
            FloatingIconType.COOL -> "snowflake" // Always snowflake ❄️
        }
    }

    fun getIconSize(): Float {
        return when (animationType) {
            IconAnimationType.FOUNDER_EXPLOSION -> 36f
            IconAnimationType.TIER_BOOST -> 32f
            IconAnimationType.MILESTONE -> 30f
            IconAnimationType.STANDARD -> 28f
        }
    }

    fun getGradientColors(): List<Color> {
        return when (iconType) {
            FloatingIconType.HYPE -> when (animationType) {
                IconAnimationType.FOUNDER_EXPLOSION -> listOf(
                    Color(0xFFFFD700), Color(0xFFFF8C00), Color(0xFFFF4500), Color(0xFFFF0000)
                )
                IconAnimationType.TIER_BOOST -> listOf(
                    Color(0xFFFFD700), Color(0xFFFF8C00), Color(0xFFFF6347)
                )
                IconAnimationType.MILESTONE -> listOf(
                    Color(0xFFFFD700), Color(0xFFFF8C00), Color(0xFFFF4500)
                )
                IconAnimationType.STANDARD -> listOf(
                    Color(0xFFFF8C00), Color(0xFFFF4500), Color(0xFFFF0000)
                )
            }
            FloatingIconType.COOL -> when (animationType) {
                IconAnimationType.FOUNDER_EXPLOSION -> listOf(
                    Color.White, Color(0xFF00FFFF), Color(0xFF00BFFF), Color(0xFF1E90FF)
                )
                IconAnimationType.TIER_BOOST -> listOf(
                    Color.White, Color(0xFF87CEEB), Color(0xFF00BFFF)
                )
                IconAnimationType.MILESTONE -> listOf(
                    Color.White, Color(0xFF00FFFF), Color(0xFF00BFFF)
                )
                IconAnimationType.STANDARD -> listOf(
                    Color(0xFF87CEEB), Color(0xFF00BFFF), Color(0xFF1E90FF)
                )
            }
        }
    }

    fun getShadowColor(): Color {
        return when (iconType) {
            FloatingIconType.HYPE -> Color(0x99000000)
            FloatingIconType.COOL -> Color(0x66000000)
        }
    }

    fun getGlowColor(): Color {
        return when (iconType) {
            FloatingIconType.HYPE -> Color(0xFFFF8C00)
            FloatingIconType.COOL -> Color(0xFF00FFFF)
        }
    }

    private fun getTierColors(): List<Color> {
        return when (tier) {
            UserTier.FOUNDER -> listOf(Color(0xFFFF00FF), Color(0xFFFFD700), Color(0xFFFF8C00))
            UserTier.CO_FOUNDER -> listOf(Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF))
            UserTier.TOP_CREATOR -> listOf(Color(0xFF0000FF), Color(0xFF00FFFF), Color.White)
            UserTier.LEGENDARY -> listOf(Color(0xFFFF0000), Color(0xFFFF8C00), Color(0xFFFFD700))
            UserTier.PARTNER -> listOf(Color(0xFF00FF00), Color(0xFF98FB98), Color(0xFF00FFFF))
            UserTier.ELITE -> listOf(Color(0xFFFF00FF), Color(0xFFFF69B4), Color(0xFFFF0000))
            UserTier.AMBASSADOR -> listOf(Color(0xFF9B59B6), Color(0xFFFF00FF), Color(0xFF00FFFF))
            UserTier.INFLUENCER -> listOf(Color(0xFFFF8C00), Color(0xFFFF0000), Color(0xFFFF69B4))
            UserTier.VETERAN -> listOf(Color(0xFF0000FF), Color(0xFF00FFFF), Color(0xFF98FB98))
            UserTier.RISING -> listOf(Color(0xFF00FF00), Color(0xFFFFD700), Color(0xFFFF8C00))
            UserTier.ROOKIE -> listOf(Color(0xFFFF8C00), Color(0xFFFF0000), Color(0xFFFFD700))
            UserTier.BUSINESS -> listOf(Color(0xFF00BCD4), Color(0xFF26C6DA), Color(0xFF00ACC1))
        }
    }
}