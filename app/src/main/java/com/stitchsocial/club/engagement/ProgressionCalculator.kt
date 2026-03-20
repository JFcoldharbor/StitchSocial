package com.stitchsocial.club

import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.RealUserStats
import kotlin.math.max
import kotlin.math.min

/**
 * ProgressionCalculator.kt - FIXED: Added AMBASSADOR tier
 */

enum class BadgeCategory(val displayName: String) {
    CONTENT_CREATOR("Content Creator"),
    THREAD_STARTER("Thread Starter"),
    ENGAGEMENT("Engagement"),
    COMMUNITY("Community"),
    CLOUT_BASED("Clout"),
    ENGAGEMENT_RATE("Engagement Rate")
}

data class BadgeInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val category: BadgeCategory,
    val requirement: String,
    val emoji: String
)

object ProgressionCalculator {

    private fun getAchievableTiers(): List<UserTier> {
        return listOf(
            UserTier.ROOKIE,
            UserTier.RISING,
            UserTier.VETERAN,
            UserTier.INFLUENCER,
            UserTier.AMBASSADOR,
            UserTier.ELITE,
            UserTier.PARTNER,
            UserTier.LEGENDARY,
            UserTier.TOP_CREATOR
        )
    }

    private fun isCloutInTierRange(clout: Int, tier: UserTier): Boolean {
        return when (tier) {
            UserTier.ROOKIE -> clout in 0..999
            UserTier.RISING -> clout in 1000..2499
            UserTier.VETERAN -> clout in 2500..4999
            UserTier.INFLUENCER -> clout in 5000..7499
            UserTier.AMBASSADOR -> clout in 7500..9999
            UserTier.ELITE -> clout in 10000..24999
            UserTier.PARTNER -> clout in 25000..49999
            UserTier.LEGENDARY -> clout in 50000..99999
            UserTier.TOP_CREATOR -> clout >= 100000
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> false
            UserTier.BUSINESS -> false
        }
    }

    private fun getMinCloutForTier(tier: UserTier): Int {
        return when (tier) {
            UserTier.ROOKIE -> 0
            UserTier.RISING -> 1000
            UserTier.VETERAN -> 2500
            UserTier.INFLUENCER -> 5000
            UserTier.AMBASSADOR -> 7500
            UserTier.ELITE -> 10000
            UserTier.PARTNER -> 25000
            UserTier.LEGENDARY -> 50000
            UserTier.TOP_CREATOR -> 100000
            UserTier.FOUNDER -> 250000
            UserTier.CO_FOUNDER -> 500000
            UserTier.BUSINESS -> 0
        }
    }

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

    fun isHigherTier(tier1: UserTier, tier2: UserTier): Boolean {
        return getTierLevel(tier1) > getTierLevel(tier2)
    }

    fun getTierLevel(tier: UserTier): Int {
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

    fun calculateCloutNeededForNextTier(currentClout: Int, currentTier: UserTier): Int {
        val nextTier = getNextTier(currentTier) ?: return 0
        val nextTierMin = getMinCloutForTier(nextTier)
        return max(0, nextTierMin - currentClout)
    }

    fun calculateBadgeEligibility(userStats: RealUserStats): List<String> {
        val eligibleBadges = mutableListOf<String>()

        if (userStats.posts >= 100) eligibleBadges.add("prolific_creator")
        if (userStats.posts >= 50) eligibleBadges.add("content_creator")
        if (userStats.posts >= 10) eligibleBadges.add("creator")

        if (userStats.threads >= 50) eligibleBadges.add("thread_master")
        if (userStats.threads >= 20) eligibleBadges.add("conversation_starter")
        if (userStats.threads >= 5) eligibleBadges.add("thread_starter")

        if (userStats.hypes >= 10000) eligibleBadges.add("hype_legend")
        if (userStats.hypes >= 1000) eligibleBadges.add("hype_master")
        if (userStats.hypes >= 100) eligibleBadges.add("hype_giver")

        if (userStats.followers >= 10000) eligibleBadges.add("influencer_badge")
        if (userStats.followers >= 1000) eligibleBadges.add("popular")
        if (userStats.followers >= 100) eligibleBadges.add("networker")

        if (userStats.engagementRate >= 0.8) eligibleBadges.add("engagement_expert")
        if (userStats.engagementRate >= 0.5) eligibleBadges.add("engaging")

        if (userStats.clout >= 100000) eligibleBadges.add("clout_champion")
        if (userStats.clout >= 50000) eligibleBadges.add("high_clout")
        if (userStats.clout >= 10000) eligibleBadges.add("clout_earner")

        return eligibleBadges
    }

    fun getAllBadgeInfo(): List<BadgeInfo> {
        return listOf(
            BadgeInfo("prolific_creator", "Prolific Creator", "Created 100+ posts", BadgeCategory.CONTENT_CREATOR, "100+ posts", "🏆"),
            BadgeInfo("content_creator", "Content Creator", "Created 50+ posts", BadgeCategory.CONTENT_CREATOR, "50+ posts", "📝"),
            BadgeInfo("creator", "Creator", "Created 10+ posts", BadgeCategory.CONTENT_CREATOR, "10+ posts", "✨"),

            BadgeInfo("thread_master", "Thread Master", "Started 50+ threads", BadgeCategory.THREAD_STARTER, "50+ threads", "🧵"),
            BadgeInfo("conversation_starter", "Conversation Starter", "Started 20+ threads", BadgeCategory.THREAD_STARTER, "20+ threads", "💬"),
            BadgeInfo("thread_starter", "Thread Starter", "Started 5+ threads", BadgeCategory.THREAD_STARTER, "5+ threads", "🔗"),

            BadgeInfo("hype_legend", "Hype Legend", "Given 10,000+ hypes", BadgeCategory.ENGAGEMENT, "10,000+ hypes", "🔥"),
            BadgeInfo("hype_master", "Hype Master", "Given 1,000+ hypes", BadgeCategory.ENGAGEMENT, "1,000+ hypes", "⚡"),
            BadgeInfo("hype_giver", "Hype Giver", "Given 100+ hypes", BadgeCategory.ENGAGEMENT, "100+ hypes", "👍"),

            BadgeInfo("influencer_badge", "Influencer", "10,000+ followers", BadgeCategory.COMMUNITY, "10,000+ followers", "🌟"),
            BadgeInfo("popular", "Popular", "1,000+ followers", BadgeCategory.COMMUNITY, "1,000+ followers", "📈"),
            BadgeInfo("networker", "Networker", "100+ followers", BadgeCategory.COMMUNITY, "100+ followers", "🤝"),

            BadgeInfo("engagement_expert", "Engagement Expert", "80%+ engagement rate", BadgeCategory.ENGAGEMENT_RATE, "80%+ engagement", "💯"),
            BadgeInfo("engaging", "Engaging", "50%+ engagement rate", BadgeCategory.ENGAGEMENT_RATE, "50%+ engagement", "📊"),

            BadgeInfo("clout_champion", "Clout Champion", "100,000+ clout points", BadgeCategory.CLOUT_BASED, "100,000+ clout", "👑"),
            BadgeInfo("high_clout", "High Clout", "50,000+ clout points", BadgeCategory.CLOUT_BASED, "50,000+ clout", "💎"),
            BadgeInfo("clout_earner", "Clout Earner", "10,000+ clout points", BadgeCategory.CLOUT_BASED, "10,000+ clout", "💰")
        )
    }

    fun getBadgeInfo(badgeId: String): BadgeInfo? {
        return getAllBadgeInfo().find { badge -> badge.id == badgeId }
    }

    fun calculateProgressionScore(userStats: RealUserStats, accountAge: Long): Double {
        val ageInDays = max(1.0, accountAge.toDouble() / (24 * 60 * 60))

        val postsPerDay = if (ageInDays > 0) userStats.posts.toDouble() / ageInDays else 0.0
        val hypesPerDay = if (ageInDays > 0) userStats.hypes.toDouble() / ageInDays else 0.0
        val followersPerDay = if (ageInDays > 0) userStats.followers.toDouble() / ageInDays else 0.0

        val contentScore = min(20.0, postsPerDay * 4.0)
        val engagementScore = min(20.0, hypesPerDay * 2.0)
        val socialScore = min(20.0, followersPerDay * 10.0)
        val qualityScore = userStats.engagementRate * 20.0
        val cloutScore = min(20.0, userStats.clout.toDouble() / 5000.0 * 20.0)

        return contentScore + engagementScore + socialScore + qualityScore + cloutScore
    }

    fun calculateProgressionBreakdown(userStats: RealUserStats, accountAge: Long): Map<String, Double> {
        val ageInDays = max(1.0, accountAge.toDouble() / (24 * 60 * 60))

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
}