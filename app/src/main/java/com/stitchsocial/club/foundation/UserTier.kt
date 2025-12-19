/*
 * UserTier.kt - SYNCHRONIZED WITH iOS UserTier.swift
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - User tier system based on clout points
 * Dependencies: None (Pure Kotlin enum)
 *
 * ✅ UPDATED: Added AMBASSADOR tier (15k-20k clout range)
 * ✅ UPDATED: Fixed all clout ranges to match iOS exactly
 * ✅ UPDATED: Added requiredFollowers, crownBadge, nextTier properties
 *
 * EXACT PORT: UserTier.swift (iOS)
 */

package com.stitchsocial.club.foundation

/**
 * User tier system based on clout points
 * 11 tiers total (matching iOS exactly)
 */
enum class UserTier(val rawValue: String) {
    ROOKIE("rookie"),
    RISING("rising"),
    VETERAN("veteran"),
    INFLUENCER("influencer"),
    AMBASSADOR("ambassador"),      // ✅ NEW: 15k-20k range (was missing!)
    ELITE("elite"),
    PARTNER("partner"),
    LEGENDARY("legendary"),
    TOP_CREATOR("top_creator"),
    FOUNDER("founder"),
    CO_FOUNDER("co_founder");

    // ===== DISPLAY PROPERTIES =====

    /**
     * Human-readable tier name
     */
    val displayName: String
        get() = when (this) {
            ROOKIE -> "Rookie"
            RISING -> "Rising"
            VETERAN -> "Veteran"
            INFLUENCER -> "Influencer"
            AMBASSADOR -> "Ambassador"      // ✅ NEW
            ELITE -> "Elite"
            PARTNER -> "Partner"
            LEGENDARY -> "Legendary"
            TOP_CREATOR -> "Top Creator"
            FOUNDER -> "Founder"
            CO_FOUNDER -> "Co-Founder"
        }

    // ===== CLOUT RANGES (FIXED to match iOS) =====

    /**
     * Clout range required for this tier
     * ✅ SYNCHRONIZED with iOS UserTier.swift cloutRange
     */
    val cloutRange: IntRange
        get() = when (this) {
            ROOKIE -> 0..999
            RISING -> 1_000..4_999               // iOS: 1000...4999
            VETERAN -> 5_000..9_999              // iOS: 5000...9999
            INFLUENCER -> 10_000..14_999         // iOS: 10000...14999 (ADJUSTED for ambassador)
            AMBASSADOR -> 15_000..19_999         // ✅ NEW tier: 15000...19999
            ELITE -> 20_000..49_999              // iOS: 20000...49999
            PARTNER -> 50_000..99_999            // iOS: 50000...99999
            LEGENDARY -> 100_000..499_999        // iOS: 100000...499999
            TOP_CREATOR -> 500_000..Int.MAX_VALUE // iOS: 500000...Int.max
            FOUNDER -> 0..Int.MAX_VALUE          // Special - any clout
            CO_FOUNDER -> 0..Int.MAX_VALUE       // Special - any clout
        }

    // ===== FOLLOWER REQUIREMENTS =====

    /**
     * Required followers for tier eligibility
     * ✅ SYNCHRONIZED with iOS UserTier.swift requiredFollowers
     */
    val requiredFollowers: Int
        get() = when (this) {
            ROOKIE -> 0
            RISING -> 1_000
            VETERAN -> 10_000
            INFLUENCER -> 100_000
            AMBASSADOR -> 150_000               // ✅ NEW: between influencer and elite
            ELITE -> 250_000
            PARTNER -> 750_000
            LEGENDARY -> 2_000_000
            TOP_CREATOR -> 5_000_000
            FOUNDER -> 0                        // Special assignment only
            CO_FOUNDER -> 0                     // Special assignment only
        }

    // ===== CROWN BADGES =====

    /**
     * Crown badge ID for this tier (null if no badge)
     * ✅ SYNCHRONIZED with iOS UserTier.swift crownBadge
     */
    val crownBadge: String?
        get() = when (this) {
            ROOKIE -> null
            RISING -> null
            VETERAN -> null
            INFLUENCER -> "influencer_crown"
            AMBASSADOR -> "ambassador_crown"    // ✅ NEW badge
            ELITE -> null
            PARTNER -> "partner_crown"
            LEGENDARY -> null
            TOP_CREATOR -> "top_creator_crown"
            FOUNDER -> "founder_crown"
            CO_FOUNDER -> "co_founder_crown"
        }

    // ===== TIER PROGRESSION =====

    /**
     * Check if this tier has founder privileges
     */
    val isFounderTier: Boolean
        get() = this == FOUNDER || this == CO_FOUNDER

    /**
     * Check if this tier is achievable through normal progression
     */
    val isAchievableTier: Boolean
        get() = !isFounderTier

    /**
     * Get the next achievable tier in progression
     * ✅ SYNCHRONIZED with iOS UserTier.swift nextTier
     */
    val nextTier: UserTier?
        get() = when (this) {
            ROOKIE -> RISING
            RISING -> VETERAN
            VETERAN -> INFLUENCER
            INFLUENCER -> AMBASSADOR           // ✅ NEW progression path
            AMBASSADOR -> ELITE                // ✅ NEW progression path
            ELITE -> PARTNER
            PARTNER -> LEGENDARY
            LEGENDARY -> TOP_CREATOR
            TOP_CREATOR -> null                // Max achievable tier
            FOUNDER -> null                    // Special tier
            CO_FOUNDER -> null                 // Special tier
        }

    /**
     * Get the previous tier in progression
     */
    val previousTier: UserTier?
        get() = when (this) {
            ROOKIE -> null
            RISING -> ROOKIE
            VETERAN -> RISING
            INFLUENCER -> VETERAN
            AMBASSADOR -> INFLUENCER           // ✅ NEW
            ELITE -> AMBASSADOR                // ✅ CHANGED: was INFLUENCER, now AMBASSADOR
            PARTNER -> ELITE
            LEGENDARY -> PARTNER
            TOP_CREATOR -> LEGENDARY
            FOUNDER -> null
            CO_FOUNDER -> null
        }

    // ===== TIER LEVEL (for comparisons) =====

    /**
     * Numeric level for tier comparison (1-11)
     * ✅ UPDATED to include AMBASSADOR at level 5
     */
    val level: Int
        get() = when (this) {
            ROOKIE -> 1
            RISING -> 2
            VETERAN -> 3
            INFLUENCER -> 4
            AMBASSADOR -> 5                    // ✅ NEW
            ELITE -> 6                         // ✅ SHIFTED from 5
            PARTNER -> 7                       // ✅ SHIFTED from 6
            LEGENDARY -> 8                     // ✅ SHIFTED from 7
            TOP_CREATOR -> 9                   // ✅ SHIFTED from 8
            FOUNDER -> 10                      // ✅ SHIFTED from 9
            CO_FOUNDER -> 11                   // ✅ SHIFTED from 10
        }

    // ===== PROGRESS CALCULATION =====

    /**
     * Calculate progress toward next tier (0.0 to 1.0)
     * @param currentFollowers User's current follower count
     * @return Progress percentage as Double
     */
    fun progressToNext(currentFollowers: Int): Double {
        val next = nextTier ?: return 1.0

        val currentRequired = this.requiredFollowers
        val nextRequired = next.requiredFollowers
        val progressRange = nextRequired - currentRequired
        val currentProgress = currentFollowers - currentRequired

        return maxOf(0.0, minOf(1.0, currentProgress.toDouble() / progressRange.toDouble()))
    }

    /**
     * Calculate clout progress toward next tier (0.0 to 1.0)
     * @param currentClout User's current clout points
     * @return Progress percentage as Double
     */
    fun cloutProgressToNext(currentClout: Int): Double {
        val next = nextTier ?: return 1.0

        val currentMin = this.cloutRange.first
        val nextMin = next.cloutRange.first
        val progressRange = nextMin - currentMin
        val currentProgress = currentClout - currentMin

        return maxOf(0.0, minOf(1.0, currentProgress.toDouble() / progressRange.toDouble()))
    }

    // ===== COMPANION OBJECT =====

    companion object {
        /**
         * Get tier from raw string value
         */
        fun fromRawValue(rawValue: String): UserTier? {
            return values().find { it.rawValue == rawValue.lowercase() }
        }

        /**
         * Get tier for given clout amount
         */
        fun tierForClout(clout: Int): UserTier {
            // Check achievable tiers in reverse order (highest first)
            return when {
                clout >= 500_000 -> TOP_CREATOR
                clout >= 100_000 -> LEGENDARY
                clout >= 50_000 -> PARTNER
                clout >= 20_000 -> ELITE
                clout >= 15_000 -> AMBASSADOR      // ✅ NEW check
                clout >= 10_000 -> INFLUENCER
                clout >= 5_000 -> VETERAN
                clout >= 1_000 -> RISING
                else -> ROOKIE
            }
        }

        /**
         * Get all achievable tiers (excludes founder tiers)
         */
        fun achievableTiers(): List<UserTier> {
            return listOf(
                ROOKIE,
                RISING,
                VETERAN,
                INFLUENCER,
                AMBASSADOR,                        // ✅ ADDED
                ELITE,
                PARTNER,
                LEGENDARY,
                TOP_CREATOR
            )
        }

        /**
         * Get all tiers in order
         */
        fun allTiersOrdered(): List<UserTier> {
            return listOf(
                ROOKIE,
                RISING,
                VETERAN,
                INFLUENCER,
                AMBASSADOR,                        // ✅ ADDED
                ELITE,
                PARTNER,
                LEGENDARY,
                TOP_CREATOR,
                FOUNDER,
                CO_FOUNDER
            )
        }

        /**
         * Check if tier1 is higher than tier2
         */
        fun isHigherTier(tier1: UserTier, tier2: UserTier): Boolean {
            return tier1.level > tier2.level
        }

        /**
         * Calculate clout needed to reach next tier
         */
        fun cloutNeededForNextTier(currentClout: Int, currentTier: UserTier): Int {
            val nextTier = currentTier.nextTier ?: return 0
            val nextMin = nextTier.cloutRange.first
            return maxOf(0, nextMin - currentClout)
        }
    }
}

// ===== TIER COMPARISON EXTENSIONS =====

/**
 * Compare two tiers
 */
operator fun UserTier.compareTo(other: UserTier): Int {
    return this.level.compareTo(other.level)
}

// ===== DEBUG HELPER =====

/**
 * Debug description of all tiers
 */
fun UserTier.Companion.debugDescription(): String {
    return buildString {
        appendLine("UserTier System (11 tiers):")
        appendLine("=" .repeat(50))
        allTiersOrdered().forEach { tier ->
            appendLine("${tier.level}. ${tier.displayName}")
            appendLine("   Raw: ${tier.rawValue}")
            appendLine("   Clout: ${tier.cloutRange.first} - ${if (tier.cloutRange.last == Int.MAX_VALUE) "∞" else tier.cloutRange.last}")
            appendLine("   Followers: ${tier.requiredFollowers}")
            appendLine("   Crown: ${tier.crownBadge ?: "none"}")
            appendLine("   Next: ${tier.nextTier?.displayName ?: "MAX"}")
            appendLine()
        }
    }
}