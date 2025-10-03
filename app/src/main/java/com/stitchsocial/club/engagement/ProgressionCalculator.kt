package com.stitchsocial.club

import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.RealUserStats
import kotlin.math.max
import kotlin.math.min

/**
 * ProgressionCalculator.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Business Logic - Pure User Progression Calculation Functions
 * Dependencies: UserTier, RealUserStats (Layer 1) ONLY
 * Features: Tier advancement, badge eligibility, clout calculation
 *
 * Exact translation from Swift UserProgressionCalculator.swift
 */

// MARK: - Badge Categories

enum class BadgeCategory(val displayName: String) {
    CONTENT_CREATOR("Content Creator"),
    THREAD_STARTER("Thread Starter"),
    ENGAGEMENT("Engagement"),
    COMMUNITY("Community"),
    CLOUT_BASED("Clout"),
    ENGAGEMENT_RATE("Engagement Rate")
}

// MARK: - Badge Data Class

data class BadgeInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val category: BadgeCategory,
    val requirement: String,
    val emoji: String
)

// MARK: - Pure Calculation Functions

/**
 * Pure calculation functions for user progression system
 * IMPORTANT: No dependencies - only pure functions for calculations
 */
object ProgressionCalculator {

    // MARK: - Helper Methods (Private)

    /**
     * Get all achievable tiers (excluding special founder tiers)
     */
    private fun getAchievableTiers(): List<UserTier> {
        return listOf(
            UserTier.ROOKIE,
            UserTier.RISING,
            UserTier.VETERAN,
            UserTier.INFLUENCER,
            UserTier.ELITE,
            UserTier.PARTNER,
            UserTier.LEGENDARY,
            UserTier.TOP_CREATOR
        )
    }

    /**
     * Check if clout falls within tier range
     */
    private fun isCloutInTierRange(clout: Int, tier: UserTier): Boolean {
        return when (tier) {
            UserTier.ROOKIE -> clout in 0..999
            UserTier.RISING -> clout in 1000..2499
            UserTier.VETERAN -> clout in 2500..4999
            UserTier.INFLUENCER -> clout in 5000..9999
            UserTier.ELITE -> clout in 10000..24999
            UserTier.PARTNER -> clout in 25000..49999
            UserTier.LEGENDARY -> clout in 50000..99999
            UserTier.TOP_CREATOR -> clout >= 100000
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> false // Special roles
        }
    }

    /**
     * Get minimum clout required for a tier
     */
    private fun getMinCloutForTier(tier: UserTier): Int {
        return when (tier) {
            UserTier.ROOKIE -> 0
            UserTier.RISING -> 1000
            UserTier.VETERAN -> 2500
            UserTier.INFLUENCER -> 5000
            UserTier.ELITE -> 10000
            UserTier.PARTNER -> 25000
            UserTier.LEGENDARY -> 50000
            UserTier.TOP_CREATOR -> 100000
            UserTier.FOUNDER -> 250000
            UserTier.CO_FOUNDER -> 500000
        }
    }

    /**
     * Get the next achievable tier
     */
    private fun getNextTier(currentTier: UserTier): UserTier? {
        val currentLevel = getTierLevel(currentTier)
        val allTiers = getAchievableTiers()

        for (tier in allTiers) {
            if (getTierLevel(tier) == currentLevel + 1) {
                return tier
            }
        }
        return null
    }

    // MARK: - Tier Advancement Calculations

    /**
     * Calculate tier advancement based on current clout
     * @param currentClout User's current clout points
     * @param currentTier User's current tier
     * @return New tier if advancement available, null otherwise
     */
    fun calculateTierAdvancement(currentClout: Int, currentTier: UserTier): UserTier? {
        val allTiers = getAchievableTiers()

        for (tier in allTiers) {
            if (tier != currentTier && isCloutInTierRange(currentClout, tier)) {
                if (isHigherTier(tier, currentTier)) {
                    return tier
                }
            }
        }

        return null
    }

    /**
     * Check if one tier is higher than another
     * @param tier1 First tier to compare
     * @param tier2 Second tier to compare
     * @return True if tier1 is higher than tier2
     */
    fun isHigherTier(tier1: UserTier, tier2: UserTier): Boolean {
        return getTierLevel(tier1) > getTierLevel(tier2)
    }

    /**
     * Get numeric level for tier comparison
     * @param tier User tier
     * @return Numeric level (1-10)
     */
    fun getTierLevel(tier: UserTier): Int {
        return when (tier) {
            UserTier.ROOKIE -> 1
            UserTier.RISING -> 2
            UserTier.VETERAN -> 3
            UserTier.INFLUENCER -> 4
            UserTier.ELITE -> 5
            UserTier.PARTNER -> 6
            UserTier.LEGENDARY -> 7
            UserTier.TOP_CREATOR -> 8
            UserTier.FOUNDER -> 9
            UserTier.CO_FOUNDER -> 10
        }
    }

    /**
     * Calculate clout needed for next tier
     * @param currentClout User's current clout
     * @param currentTier User's current tier
     * @return Clout points needed for next tier
     */
    fun calculateCloutNeededForNextTier(currentClout: Int, currentTier: UserTier): Int {
        val nextTier = getNextTier(currentTier) ?: return 0
        val nextTierMin = getMinCloutForTier(nextTier)
        return max(0, nextTierMin - currentClout)
    }

    // MARK: - Badge Eligibility Calculations

    /**
     * Calculate badge eligibility based on user statistics
     * @param userStats User's current statistics
     * @return List of eligible badge IDs
     */
    fun calculateBadgeEligibility(userStats: RealUserStats): List<String> {
        val eligibleBadges = mutableListOf<String>()

        // Content Creator Badges
        if (userStats.posts >= 100) eligibleBadges.add("prolific_creator")
        if (userStats.posts >= 50) eligibleBadges.add("content_creator")
        if (userStats.posts >= 10) eligibleBadges.add("creator")

        // Thread Starter Badges
        if (userStats.threads >= 50) eligibleBadges.add("thread_master")
        if (userStats.threads >= 20) eligibleBadges.add("conversation_starter")
        if (userStats.threads >= 5) eligibleBadges.add("thread_starter")

        // Engagement Badges
        if (userStats.hypes >= 10000) eligibleBadges.add("hype_legend")
        if (userStats.hypes >= 1000) eligibleBadges.add("hype_master")
        if (userStats.hypes >= 100) eligibleBadges.add("hype_giver")

        // Community Badges
        if (userStats.followers >= 10000) eligibleBadges.add("influencer_badge")
        if (userStats.followers >= 1000) eligibleBadges.add("popular")
        if (userStats.followers >= 100) eligibleBadges.add("networker")

        // Engagement Rate Badges
        if (userStats.engagementRate >= 0.8) eligibleBadges.add("engagement_expert")
        if (userStats.engagementRate >= 0.5) eligibleBadges.add("engaging")

        // Clout-based Badges
        if (userStats.clout >= 100000) eligibleBadges.add("clout_champion")
        if (userStats.clout >= 50000) eligibleBadges.add("high_clout")
        if (userStats.clout >= 10000) eligibleBadges.add("clout_earner")

        return eligibleBadges
    }

    /**
     * Get all available badge information
     * @return List of all possible badges
     */
    fun getAllBadgeInfo(): List<BadgeInfo> {
        return listOf(
            // Content Creator Badges
            BadgeInfo("prolific_creator", "Prolific Creator", "Created 100+ posts", BadgeCategory.CONTENT_CREATOR, "100+ posts", "🏆"),
            BadgeInfo("content_creator", "Content Creator", "Created 50+ posts", BadgeCategory.CONTENT_CREATOR, "50+ posts", "📝"),
            BadgeInfo("creator", "Creator", "Created 10+ posts", BadgeCategory.CONTENT_CREATOR, "10+ posts", "✨"),

            // Thread Starter Badges
            BadgeInfo("thread_master", "Thread Master", "Started 50+ threads", BadgeCategory.THREAD_STARTER, "50+ threads", "🧵"),
            BadgeInfo("conversation_starter", "Conversation Starter", "Started 20+ threads", BadgeCategory.THREAD_STARTER, "20+ threads", "💬"),
            BadgeInfo("thread_starter", "Thread Starter", "Started 5+ threads", BadgeCategory.THREAD_STARTER, "5+ threads", "🔗"),

            // Engagement Badges
            BadgeInfo("hype_legend", "Hype Legend", "Given 10,000+ hypes", BadgeCategory.ENGAGEMENT, "10,000+ hypes", "🔥"),
            BadgeInfo("hype_master", "Hype Master", "Given 1,000+ hypes", BadgeCategory.ENGAGEMENT, "1,000+ hypes", "⚡"),
            BadgeInfo("hype_giver", "Hype Giver", "Given 100+ hypes", BadgeCategory.ENGAGEMENT, "100+ hypes", "👍"),

            // Community Badges
            BadgeInfo("influencer_badge", "Influencer", "10,000+ followers", BadgeCategory.COMMUNITY, "10,000+ followers", "🌟"),
            BadgeInfo("popular", "Popular", "1,000+ followers", BadgeCategory.COMMUNITY, "1,000+ followers", "📈"),
            BadgeInfo("networker", "Networker", "100+ followers", BadgeCategory.COMMUNITY, "100+ followers", "🤝"),

            // Engagement Rate Badges
            BadgeInfo("engagement_expert", "Engagement Expert", "80%+ engagement rate", BadgeCategory.ENGAGEMENT_RATE, "80%+ engagement", "💯"),
            BadgeInfo("engaging", "Engaging", "50%+ engagement rate", BadgeCategory.ENGAGEMENT_RATE, "50%+ engagement", "📊"),

            // Clout-based Badges
            BadgeInfo("clout_champion", "Clout Champion", "100,000+ clout points", BadgeCategory.CLOUT_BASED, "100,000+ clout", "👑"),
            BadgeInfo("high_clout", "High Clout", "50,000+ clout points", BadgeCategory.CLOUT_BASED, "50,000+ clout", "💎"),
            BadgeInfo("clout_earner", "Clout Earner", "10,000+ clout points", BadgeCategory.CLOUT_BASED, "10,000+ clout", "💰")
        )
    }

    /**
     * Get badge info by ID
     * @param badgeId Badge identifier
     * @return BadgeInfo or null if not found
     */
    fun getBadgeInfo(badgeId: String): BadgeInfo? {
        return getAllBadgeInfo().find { badge -> badge.id == badgeId }
    }

    // MARK: - Progression Score Calculations

    /**
     * Calculate overall user progression score (0.0 to 100.0)
     * @param userStats User's statistics
     * @param accountAge Account age in seconds
     * @return Progression score (0-100)
     */
    fun calculateProgressionScore(userStats: RealUserStats, accountAge: Long): Double {
        val ageInDays = max(1.0, accountAge.toDouble() / (24 * 60 * 60))

        // Calculate daily averages
        val postsPerDay = if (ageInDays > 0) userStats.posts.toDouble() / ageInDays else 0.0
        val hypesPerDay = if (ageInDays > 0) userStats.hypes.toDouble() / ageInDays else 0.0
        val followersPerDay = if (ageInDays > 0) userStats.followers.toDouble() / ageInDays else 0.0

        // Component scores (0-20 each, total 100)
        val contentScore = min(20.0, postsPerDay * 4.0)
        val engagementScore = min(20.0, hypesPerDay * 2.0)
        val socialScore = min(20.0, followersPerDay * 10.0)
        val qualityScore = userStats.engagementRate * 20.0
        val cloutScore = min(20.0, userStats.clout.toDouble() / 5000.0 * 20.0)

        return contentScore + engagementScore + socialScore + qualityScore + cloutScore
    }

    /**
     * Calculate progression breakdown by component
     * @param userStats User's statistics
     * @param accountAge Account age in seconds
     * @return Map of component names to scores
     */
    fun calculateProgressionBreakdown(userStats: RealUserStats, accountAge: Long): Map<String, Double> {
        val ageInDays = max(1.0, accountAge.toDouble() / (24 * 60 * 60))

        // Calculate daily averages
        val postsPerDay = if (ageInDays > 0) userStats.posts.toDouble() / ageInDays else 0.0
        val hypesPerDay = if (ageInDays > 0) userStats.hypes.toDouble() / ageInDays else 0.0
        val followersPerDay = if (ageInDays > 0) userStats.followers.toDouble() / ageInDays else 0.0

        return mapOf(
            "Content Creation" to min(20.0, postsPerDay * 4.0),
            "Engagement Activity" to min(20.0, hypesPerDay * 2.0),
            "Social Growth" to min(20.0, followersPerDay * 10.0),
            "Quality Score" to userStats.engagementRate * 20.0,
            "Clout Points" to min(20.0, userStats.clout.toDouble() / 5000.0 * 20.0)
        )
    }

    /**
     * Calculate next milestone for user progression
     * @param userStats Current user statistics
     * @return Next achievable milestone description
     */
    fun calculateNextMilestone(userStats: RealUserStats): String {
        return when {
            userStats.threads < 5 -> "Start ${5 - userStats.threads} more threads to earn Thread Starter badge"
            userStats.posts < 10 -> "Create ${10 - userStats.posts} more posts to earn Creator badge"
            userStats.hypes < 100 -> "Give ${100 - userStats.hypes} more hypes to earn Hype Giver badge"
            userStats.followers < 100 -> "Gain ${100 - userStats.followers} more followers to earn Networker badge"
            userStats.clout < 10000 -> "Earn ${10000 - userStats.clout} more clout to earn Clout Earner badge"
            userStats.posts < 50 -> "Create ${50 - userStats.posts} more posts to earn Content Creator badge"
            userStats.threads < 20 -> "Start ${20 - userStats.threads} more threads to earn Conversation Starter badge"
            userStats.hypes < 1000 -> "Give ${1000 - userStats.hypes} more hypes to earn Hype Master badge"
            userStats.followers < 1000 -> "Gain ${1000 - userStats.followers} more followers to earn Popular badge"
            userStats.clout < 50000 -> "Earn ${50000 - userStats.clout} more clout to earn High Clout badge"
            else -> "Keep engaging to unlock legendary badges!"
        }
    }

    // MARK: - Test Function

    /**
     * Test user progression calculations with mock data
     * @return Test results string
     */
    fun helloWorldTest(): String {
        val mockStats = RealUserStats(
            followers = 150,
            hypes = 75,
            threads = 12,
            posts = 25,
            engagementRate = 0.65,
            clout = 3500
        )

        val tierAdvancement = calculateTierAdvancement(currentClout = 3500, currentTier = UserTier.ROOKIE)
        val badges = calculateBadgeEligibility(userStats = mockStats)
        val progressScore = calculateProgressionScore(userStats = mockStats, accountAge = 30 * 24 * 60 * 60)
        val nextMilestone = calculateNextMilestone(mockStats)
        val cloutNeeded = calculateCloutNeededForNextTier(3500, UserTier.ROOKIE)

        val result = """
        🚀 PROGRESSION CALCULATOR: Hello World - Pure user progression functions ready!
        
        Test Results for Mock User (30 days old):
        → Tier Advancement: ${tierAdvancement?.name ?: "None available"}
        → Eligible Badges: ${badges.size} badges (${badges.take(3).joinToString(", ")})
        → Progress Score: ${"%.1f".format(progressScore)}/100.0
        → Next Milestone: $nextMilestone
        → Clout Needed: $cloutNeeded points for next tier
        
        Badge System (${getAllBadgeInfo().size} total badges):
        - Content Creator: creator → content_creator → prolific_creator
        - Thread Starter: thread_starter → conversation_starter → thread_master
        - Engagement: hype_giver → hype_master → hype_legend
        - Community: networker → popular → influencer_badge
        - Engagement Rate: engaging → engagement_expert
        - Clout-based: clout_earner → high_clout → clout_champion
        
        Status: Layer 5 ProgressionCalculator ready for production!
        """.trimIndent()

        return result
    }
}