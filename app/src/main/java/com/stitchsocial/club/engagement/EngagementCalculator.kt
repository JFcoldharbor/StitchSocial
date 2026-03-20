/*
 * EngagementCalculator.kt - ENGAGEMENT CALCULATIONS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Includes: calculateTapMilestone for iOS-style milestone detection
 */

package com.stitchsocial.club.engagement

import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.EngagementConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object EngagementCalculator {

    // ========================================================================
    // TAP MILESTONE CALCULATION (for iOS-style floating icons)
    // ========================================================================

    /**
     * Calculate which milestone was reached (if any)
     * Used by buttons to spawn milestone burst effects
     */
    fun calculateTapMilestone(currentTaps: Int, requiredTaps: Int): TapMilestone? {
        if (requiredTaps <= 0) return null

        val progress = currentTaps.toDouble() / requiredTaps.toDouble()

        return when {
            progress >= 1.0 -> TapMilestone.COMPLETE
            progress >= 0.9 -> TapMilestone.ALMOST_DONE
            progress >= 0.75 -> TapMilestone.THREE_QUARTERS
            progress >= 0.5 -> TapMilestone.HALF
            progress >= 0.25 -> TapMilestone.QUARTER
            else -> null
        }
    }

    // ========================================================================
    // PROGRESSIVE TAPPING
    // ========================================================================

    fun calculateProgressiveTapRequirement(engagementNumber: Int): Int {
        if (engagementNumber <= EngagementConfig.INSTANT_ENGAGEMENT_THRESHOLD) {
            return 1
        }
        val progressiveIndex = engagementNumber - EngagementConfig.INSTANT_ENGAGEMENT_THRESHOLD - 1
        val requirement = EngagementConfig.FIRST_PROGRESSIVE_TAPS * (2.0.pow(progressiveIndex.toDouble()).toInt())
        return min(requirement, EngagementConfig.MAX_TAP_REQUIREMENT)
    }

    fun calculateTapProgress(currentTaps: Int, targetTaps: Int): Double {
        if (targetTaps <= 0) return 0.0
        return min(1.0, currentTaps.toDouble() / targetTaps.toDouble())
    }

    // ========================================================================
    // HYPE RATING
    // ========================================================================

    fun calculateHypeRatingCost(tier: UserTier): Double {
        return EngagementConfig.getHypeCost(tier)
    }

    fun canAffordEngagement(currentHypePercent: Double, costPercent: Double): Boolean {
        return currentHypePercent >= costPercent
    }

    fun applyHypeRatingCost(currentPercent: Double, costPercent: Double): Double {
        return max(0.0, currentPercent - costPercent)
    }

    fun calculateHypeRatingStatus(currentPercent: Double, requiredPercent: Double): HypeRatingStatus {
        return when {
            currentPercent < EngagementConfig.CRITICAL_HYPE_THRESHOLD -> HypeRatingStatus.critical(currentPercent)
            currentPercent < requiredPercent -> HypeRatingStatus.cannotEngage(currentPercent, requiredPercent)
            currentPercent < EngagementConfig.LOW_HYPE_WARNING_THRESHOLD -> HypeRatingStatus.lowWarning(currentPercent)
            else -> HypeRatingStatus.canEngage(currentPercent)
        }
    }

    // ========================================================================
    // CLOUT REWARDS
    // ========================================================================

    fun calculateCloutReward(
        userTier: UserTier,
        tapNumber: Int = 1,
        isFirstEngagement: Boolean = false,
        currentCloutFromUser: Int = 0
    ): Int {
        return EngagementConfig.calculateClout(
            tier = userTier,
            tapNumber = tapNumber,
            isFirstEngagement = isFirstEngagement,
            currentCloutFromUser = currentCloutFromUser
        )
    }

    fun getRemainingCloutAllowance(userTier: UserTier, currentCloutGiven: Int): Int {
        val maxAllowed = EngagementConfig.getMaxCloutPerUserPerVideo(userTier)
        return max(0, maxAllowed - currentCloutGiven)
    }

    fun hasReachedCloutCap(userTier: UserTier, currentCloutGiven: Int): Boolean {
        return getRemainingCloutAllowance(userTier, currentCloutGiven) <= 0
    }

    fun hasFirstTapBonus(tier: UserTier): Boolean {
        return EngagementConfig.hasFirstTapBonus(tier)
    }

    fun calculateFounderFirstTapBonus(): Int = EngagementConfig.FOUNDER_FIRST_TAP_CLOUT_BONUS

    // ========================================================================
    // VISUAL HYPE
    // ========================================================================

    fun calculateVisualHypeIncrement(userTier: UserTier): Int {
        return EngagementConfig.getVisualHypeMultiplier(userTier)
    }

    // ========================================================================
    // TEMPERATURE
    // ========================================================================

    fun calculateTemperature(hypeCount: Int, coolCount: Int): String {
        val total = hypeCount + coolCount
        if (total == 0) return "frozen"
        val hypeRatio = hypeCount.toDouble() / total.toDouble()
        return when {
            hypeRatio >= 0.95 -> "blazing"
            hypeRatio >= 0.8 -> "hot"
            hypeRatio >= 0.6 -> "warm"
            hypeRatio >= 0.3 -> "cool"
            hypeRatio >= 0.1 -> "cold"
            else -> "frozen"
        }
    }

    // ========================================================================
    // TIER CALCULATIONS (with AMBASSADOR)
    // ========================================================================

    fun calculateTierMultiplier(tier: UserTier): Double {
        return when (tier) {
            UserTier.ROOKIE -> 1.0
            UserTier.RISING -> 1.2
            UserTier.VETERAN -> 1.5
            UserTier.INFLUENCER -> 2.0
            UserTier.AMBASSADOR -> 2.5
            UserTier.ELITE -> 3.0
            UserTier.PARTNER -> 3.5
            UserTier.LEGENDARY -> 4.0
            UserTier.TOP_CREATOR -> 5.0
            UserTier.FOUNDER -> 10.0
            UserTier.CO_FOUNDER -> 15.0
            UserTier.BUSINESS -> 1.0
        }
    }

    fun calculateCrossTierBonus(giverTier: UserTier, receiverTier: UserTier): Double {
        val giverLevel = getTierLevel(giverTier)
        val receiverLevel = getTierLevel(receiverTier)
        val difference = giverLevel - receiverLevel
        return if (difference > 0) 1.0 + (difference * 0.1) else 1.0
    }

    private fun getTierLevel(tier: UserTier): Int {
        return when (tier) {
            UserTier.ROOKIE -> 1
            UserTier.RISING -> 2
            UserTier.VETERAN -> 3
            UserTier.INFLUENCER -> 4
            UserTier.AMBASSADOR -> 5
            UserTier.ELITE -> 6
            UserTier.PARTNER -> 7
            UserTier.LEGENDARY -> 8
            UserTier.TOP_CREATOR -> 9
            UserTier.FOUNDER -> 10
            UserTier.CO_FOUNDER -> 11
            UserTier.BUSINESS -> 0
        }
    }

    // ========================================================================
    // RATE LIMITING
    // ========================================================================

    fun canEngageNow(lastEngagementTime: Long, currentTime: Long): Boolean {
        val cooldownMs = (EngagementConfig.ENGAGEMENT_COOLDOWN_SECONDS * 1000).toLong()
        return (currentTime - lastEngagementTime) >= cooldownMs
    }

    fun wouldTriggerTrollWarning(currentCoolCount: Int, hypeCount: Int): Boolean {
        val newCoolCount = currentCoolCount + 1
        return newCoolCount >= 20 && (hypeCount == 0 || newCoolCount / hypeCount >= 20)
    }
}