/*
 * EngagementTypes.kt - ALL ENGAGEMENT TYPE DEFINITIONS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * ⚠️ THIS FILE MUST EXIST - Contains TapMilestone, EngagementAnimationType, EngagementResult, TapResult
 */

package com.stitchsocial.club.engagement

/**
 * Animation types for different engagement results
 */
enum class EngagementAnimationType {
    NONE,
    STANDARD_HYPE,
    STANDARD_COOL,
    FOUNDER_EXPLOSION,
    PREMIUM_BOOST,
    TIER_BOOST,
    MILESTONE,
    TAP_PROGRESS,
    TAP_MILESTONE,
    TROLL_WARNING,
    TIER_MILESTONE;

    val displayName: String
        get() = when (this) {
            NONE -> ""
            STANDARD_HYPE -> "Hype!"
            STANDARD_COOL -> "Cool"
            FOUNDER_EXPLOSION -> "Founder Boost!"
            PREMIUM_BOOST -> "Premium Boost!"
            TIER_BOOST -> "Tier Boost!"
            MILESTONE -> "Milestone!"
            TAP_PROGRESS -> "Tapping..."
            TAP_MILESTONE -> "Milestone!"
            TROLL_WARNING -> "Warning"
            TIER_MILESTONE -> "Tier Up!"
        }
}

/**
 * Progressive tapping milestones
 */
enum class TapMilestone {
    QUARTER,
    HALF,
    THREE_QUARTERS,
    ALMOST_DONE,
    COMPLETE;

    val displayName: String
        get() = when (this) {
            QUARTER -> "Keep Going!"
            HALF -> "Halfway There!"
            THREE_QUARTERS -> "Almost Done!"
            ALMOST_DONE -> "So Close!"
            COMPLETE -> "Complete!"
        }

    val progressValue: Double
        get() = when (this) {
            QUARTER -> 0.25
            HALF -> 0.5
            THREE_QUARTERS -> 0.75
            ALMOST_DONE -> 0.9
            COMPLETE -> 1.0
        }
}

/**
 * Result of engagement processing
 */
data class EngagementResult(
    val success: Boolean,
    val cloutAwarded: Int,
    val newHypeCount: Int,
    val newCoolCount: Int,
    val isFounderFirstTap: Boolean,
    val visualHypeIncrement: Int,
    val visualCoolIncrement: Int,
    val animationType: EngagementAnimationType,
    val message: String
) {
    val isSignificant: Boolean
        get() = cloutAwarded > 10 || isFounderFirstTap

    val hasVisualImpact: Boolean
        get() = visualHypeIncrement > 0 || visualCoolIncrement > 0

    companion object {
        fun failure(message: String) = EngagementResult(
            success = false,
            cloutAwarded = 0,
            newHypeCount = 0,
            newCoolCount = 0,
            isFounderFirstTap = false,
            visualHypeIncrement = 0,
            visualCoolIncrement = 0,
            animationType = EngagementAnimationType.NONE,
            message = message
        )

        fun tapProgress(currentHype: Int, currentCool: Int) = EngagementResult(
            success = true,
            cloutAwarded = 0,
            newHypeCount = currentHype,
            newCoolCount = currentCool,
            isFounderFirstTap = false,
            visualHypeIncrement = 0,
            visualCoolIncrement = 0,
            animationType = EngagementAnimationType.TAP_PROGRESS,
            message = "Keep tapping..."
        )
    }
}

/**
 * Result of progressive tapping
 */
data class TapResult(
    val isComplete: Boolean,
    val progress: Double,
    val milestone: TapMilestone?,
    val tapsRemaining: Int
) {
    val hasMilestone: Boolean get() = milestone != null
    val progressPercent: Int get() = (progress * 100).toInt()

    val progressMessage: String
        get() = when {
            isComplete -> "Complete!"
            milestone != null -> milestone.displayName
            tapsRemaining == 1 -> "One more tap!"
            tapsRemaining <= 3 -> "$tapsRemaining taps left"
            else -> "${progressPercent}% complete"
        }

    companion object {
        fun fromState(currentTaps: Int, requiredTaps: Int, isComplete: Boolean): TapResult {
            val progress = if (requiredTaps > 0) {
                (currentTaps.toDouble() / requiredTaps.toDouble()).coerceIn(0.0, 1.0)
            } else 0.0

            val milestone = when {
                progress >= 1.0 -> TapMilestone.COMPLETE
                progress >= 0.9 -> TapMilestone.ALMOST_DONE
                progress >= 0.75 -> TapMilestone.THREE_QUARTERS
                progress >= 0.5 -> TapMilestone.HALF
                progress >= 0.25 -> TapMilestone.QUARTER
                else -> null
            }

            return TapResult(
                isComplete = isComplete,
                progress = progress,
                milestone = milestone,
                tapsRemaining = (requiredTaps - currentTaps).coerceAtLeast(0)
            )
        }
    }
}