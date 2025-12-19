/*
 * EngagementConfig.kt - HYBRID CLOUT SYSTEM
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Engagement System Configuration
 * Dependencies: UserTier (Layer 1) ONLY
 * Features: All engagement constants, tier costs, progressive tapping settings
 *
 * ✅ INCLUDES: AMBASSADOR tier, FOUNDER_FIRST_TAP_CLOUT_BONUS
 */

package com.stitchsocial.club.foundation

object EngagementConfig {

    // MARK: - Progressive Tapping Configuration

    /** First 4 engagements are instant (1 tap each) */
    const val INSTANT_ENGAGEMENT_THRESHOLD = 4

    /** Starting requirement for progressive tapping (5th engagement = 2 taps) */
    const val FIRST_PROGRESSIVE_TAPS = 2

    /** Maximum tap requirement to prevent infinite progression */
    const val MAX_TAP_REQUIREMENT = 256

    // MARK: - Hype Rating Configuration

    /** Maximum hype rating points */
    const val MAX_HYPE_RATING_POINTS = 15000.0

    /** Starting hype rating for new users (25%) */
    const val NEW_USER_HYPE_PERCENT = 25.0

    /** Passive regeneration rate per hour (0.5% per hour) */
    const val PASSIVE_REGEN_PER_HOUR = 0.5

    /** Low hype warning threshold (below 20%) */
    const val LOW_HYPE_WARNING_THRESHOLD = 20.0

    /** Critical hype threshold (below 10%) */
    const val CRITICAL_HYPE_THRESHOLD = 10.0

    // MARK: - Founder First Tap Bonus

    /** Bonus clout for Founder's first tap on a video */
    const val FOUNDER_FIRST_TAP_CLOUT_BONUS = 100

    /** Visual hype multiplier for Founder's first tap */
    const val FOUNDER_FIRST_TAP_MULTIPLIER = 10

    // MARK: - Tier-Based Hype Costs (as percentages)

    val TIER_HYPE_COSTS: Map<UserTier, Double> = mapOf(
        UserTier.ROOKIE to 1.0,
        UserTier.RISING to 0.67,
        UserTier.VETERAN to 0.5,
        UserTier.INFLUENCER to 0.33,
        UserTier.AMBASSADOR to 0.25,
        UserTier.ELITE to 0.2,
        UserTier.PARTNER to 0.13,
        UserTier.LEGENDARY to 0.1,
        UserTier.TOP_CREATOR to 0.067,
        UserTier.FOUNDER to 0.033,
        UserTier.CO_FOUNDER to 0.033
    )

    // MARK: - Visual Hype Multipliers (Tier-Based)

    val TIER_VISUAL_HYPE_MULTIPLIER: Map<UserTier, Int> = mapOf(
        UserTier.FOUNDER to 20,
        UserTier.CO_FOUNDER to 20,
        UserTier.TOP_CREATOR to 15,
        UserTier.LEGENDARY to 12,
        UserTier.PARTNER to 10,
        UserTier.ELITE to 8,
        UserTier.AMBASSADOR to 6,
        UserTier.INFLUENCER to 1,
        UserTier.VETERAN to 1,
        UserTier.RISING to 1,
        UserTier.ROOKIE to 1
    )

    // MARK: - Tier-Based Clout System

    val TIER_BASE_CLOUT: Map<UserTier, Int> = mapOf(
        UserTier.FOUNDER to 50,
        UserTier.CO_FOUNDER to 50,
        UserTier.TOP_CREATOR to 40,
        UserTier.LEGENDARY to 30,
        UserTier.PARTNER to 25,
        UserTier.ELITE to 20,
        UserTier.AMBASSADOR to 15,
        UserTier.INFLUENCER to 5,
        UserTier.VETERAN to 5,
        UserTier.RISING to 5,
        UserTier.ROOKIE to 5
    )

    val MAX_CLOUT_PER_USER_PER_VIDEO: Map<UserTier, Int> = mapOf(
        UserTier.FOUNDER to 500,
        UserTier.CO_FOUNDER to 500,
        UserTier.TOP_CREATOR to 400,
        UserTier.LEGENDARY to 350,
        UserTier.PARTNER to 300,
        UserTier.ELITE to 250,
        UserTier.AMBASSADOR to 200,
        UserTier.INFLUENCER to 100,
        UserTier.VETERAN to 75,
        UserTier.RISING to 50,
        UserTier.ROOKIE to 25
    )

    /** First tap bonus multiplier for premium tiers */
    const val FIRST_TAP_BONUS_MULTIPLIER = 2.0

    /** Premium tiers that receive first tap bonus */
    val PREMIUM_TIERS_WITH_FIRST_TAP_BONUS: Set<UserTier> = setOf(
        UserTier.FOUNDER,
        UserTier.CO_FOUNDER,
        UserTier.TOP_CREATOR,
        UserTier.LEGENDARY,
        UserTier.PARTNER,
        UserTier.ELITE,
        UserTier.AMBASSADOR
    )

    fun getDiminishingMultiplier(tapNumber: Int): Double {
        return when (tapNumber) {
            in 1..3 -> 1.0
            4 -> 0.9
            5 -> 0.8
            6 -> 0.7
            7 -> 0.6
            8 -> 0.5
            else -> 0.4
        }
    }

    // MARK: - Engagement Thresholds & Caps

    const val MAX_TOTAL_CLOUT_PER_VIDEO = 1000
    const val COOL_CLOUT_PENALTY = -5
    const val MAX_COOLS_PER_HOUR = 10
    const val MAX_COOLS_PER_DAY = 50
    const val REGULAR_CLOUT_THRESHOLD = 5

    // MARK: - Rate Limiting

    const val ENGAGEMENT_COOLDOWN_SECONDS = 0.5
    const val TAP_COOLDOWN_MS = 100L
    const val MAX_ENGAGEMENTS_PER_MINUTE = 30

    // MARK: - Helper Methods

    fun getHypeCost(tier: UserTier): Double {
        return TIER_HYPE_COSTS[tier] ?: 1.0
    }

    fun getVisualHypeMultiplier(tier: UserTier): Int {
        return TIER_VISUAL_HYPE_MULTIPLIER[tier] ?: 1
    }

    fun getBaseClout(tier: UserTier): Int {
        return TIER_BASE_CLOUT[tier] ?: 5
    }

    fun getMaxCloutPerUserPerVideo(tier: UserTier): Int {
        return MAX_CLOUT_PER_USER_PER_VIDEO[tier] ?: 25
    }

    fun hasFirstTapBonus(tier: UserTier): Boolean {
        return PREMIUM_TIERS_WITH_FIRST_TAP_BONUS.contains(tier)
    }

    fun getCloutReward(tier: UserTier): Int {
        return getBaseClout(tier)
    }

    fun calculateClout(
        tier: UserTier,
        tapNumber: Int,
        isFirstEngagement: Boolean,
        currentCloutFromUser: Int
    ): Int {
        var clout = getBaseClout(tier)

        if (isFirstEngagement && hasFirstTapBonus(tier)) {
            clout = (clout * FIRST_TAP_BONUS_MULTIPLIER).toInt()
        }

        val diminishingMultiplier = getDiminishingMultiplier(tapNumber)
        clout = (clout * diminishingMultiplier).toInt()

        val maxAllowed = getMaxCloutPerUserPerVideo(tier)
        val remainingAllowance = maxOf(0, maxAllowed - currentCloutFromUser)
        clout = minOf(clout, remainingAllowance)

        return maxOf(0, clout)
    }

    fun getCostForEngagement(engagementNumber: Int, userTier: UserTier): Double {
        val baseCost = getHypeCost(userTier)
        if (engagementNumber > 10) {
            val multiplier = 1.0 + ((engagementNumber - 10) * 0.1)
            return baseCost * multiplier
        }
        return baseCost
    }
}