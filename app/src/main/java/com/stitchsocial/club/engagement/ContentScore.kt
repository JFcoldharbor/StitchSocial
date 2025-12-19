package com.stitchsocial.club.engagement

import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.Temperature
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * AlgorithmicEngine.kt - FIXED: Added AMBASSADOR tier
 */

data class ContentScore(
    val videoId: String,
    val baseScore: Double,
    val engagementScore: Double,
    val recencyScore: Double,
    val creatorScore: Double,
    val qualityScore: Double,
    val diversityScore: Double,
    val finalScore: Double
)

data class FeedMetrics(
    val totalVideos: Int,
    val averageScore: Double,
    val scoreDistribution: Map<String, Int>,
    val algorithmVersion: String,
    val processingTimeMs: Long
)

data class VideoRankingData(
    val id: String,
    val creatorId: String,
    val creatorTier: UserTier,
    val hypeCount: Int,
    val coolCount: Int,
    val viewCount: Int,
    val replyCount: Int,
    val shareCount: Int,
    val temperature: String,
    val ageInHours: Double,
    val conversationDepth: Int,
    val qualityScore: Double,
    val engagementRatio: Double,
    val velocityScore: Double,
    val isPromoted: Boolean
)

object AlgorithmicEngine {

    fun calculateContentScore(
        video: VideoRankingData,
        userTier: UserTier,
        recentViewedIds: List<String>,
        followingCreatorIds: List<String>
    ): ContentScore {
        val engagementScore = calculateEngagementScore(
            hype = video.hypeCount,
            cool = video.coolCount,
            views = video.viewCount,
            replies = video.replyCount,
            shares = video.shareCount
        )
        val recencyScore = calculateRecencyScore(video.ageInHours)
        val creatorScore = calculateCreatorScore(
            creatorTier = video.creatorTier,
            isFollowing = followingCreatorIds.contains(video.creatorId),
            userTier = userTier
        )
        val qualityScore = calculateQualityScore(
            temperature = video.temperature,
            engagementRatio = video.engagementRatio,
            velocityScore = video.velocityScore
        )
        val diversityScore = calculateDiversityScore(
            videoId = video.id,
            creatorId = video.creatorId,
            recentViewedIds = recentViewedIds,
            conversationDepth = video.conversationDepth
        )

        val baseScore = (engagementScore + qualityScore) / 2.0
        val bonusScore = (creatorScore + diversityScore) / 2.0
        val promotionBonus = if (video.isPromoted) 20.0 else 0.0
        val finalScore = (baseScore * 0.6) + (recencyScore * 0.2) + (bonusScore * 0.2) + promotionBonus

        return ContentScore(
            videoId = video.id,
            baseScore = baseScore,
            engagementScore = engagementScore,
            recencyScore = recencyScore,
            creatorScore = creatorScore,
            qualityScore = qualityScore,
            diversityScore = diversityScore,
            finalScore = max(0.0, min(100.0, finalScore))
        )
    }

    fun rankVideosForFeed(
        videos: List<VideoRankingData>,
        userTier: UserTier,
        recentViewedIds: List<String>,
        followingCreatorIds: List<String>
    ): List<Pair<VideoRankingData, ContentScore>> {
        val scoredVideos = videos.map { video ->
            val score = calculateContentScore(video, userTier, recentViewedIds, followingCreatorIds)
            Pair(video, score)
        }
        return scoredVideos.sortedByDescending { it.second.finalScore }
    }

    private fun calculateEngagementScore(
        hype: Int,
        cool: Int,
        views: Int,
        replies: Int,
        shares: Int
    ): Double {
        val totalEngagement = hype + cool + replies + shares
        val engagementRate = if (views > 0) totalEngagement.toDouble() / views.toDouble() else 0.0
        val weightedScore = (hype * 3.0) + (replies * 5.0) + (shares * 4.0) - (cool * 1.0)
        val normalizedRate = min(1.0, engagementRate * 10.0) * 50.0
        val normalizedWeighted = max(0.0, min(50.0, weightedScore / 10.0))
        return normalizedRate + normalizedWeighted
    }

    private fun calculateRecencyScore(ageInHours: Double): Double {
        return when {
            ageInHours <= 1.0 -> 100.0
            ageInHours <= 6.0 -> 80.0
            ageInHours <= 24.0 -> 60.0
            ageInHours <= 72.0 -> 40.0
            else -> 20.0
        }
    }

    private fun calculateCreatorScore(
        creatorTier: UserTier,
        isFollowing: Boolean,
        userTier: UserTier
    ): Double {
        val tierScore = when (creatorTier) {
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> 100.0
            UserTier.TOP_CREATOR -> 90.0
            UserTier.LEGENDARY -> 80.0
            UserTier.PARTNER -> 70.0
            UserTier.ELITE -> 60.0
            UserTier.AMBASSADOR -> 55.0
            UserTier.INFLUENCER -> 50.0
            UserTier.VETERAN -> 40.0
            UserTier.RISING -> 30.0
            UserTier.ROOKIE -> 20.0
        }

        val followingBonus = if (isFollowing) 30.0 else 0.0
        val crossTierBonus = calculateCrossTierBonus(creatorTier, userTier)

        return min(100.0, tierScore + followingBonus + crossTierBonus)
    }

    private fun calculateQualityScore(
        temperature: String,
        engagementRatio: Double,
        velocityScore: Double
    ): Double {
        val tempScore = when (temperature.lowercase()) {
            "blazing" -> 100.0
            "hot" -> 80.0
            "warm" -> 60.0
            "neutral" -> 40.0
            "cool" -> 20.0
            "cold" -> 10.0
            else -> 40.0
        }
        val ratioScore = engagementRatio * 50.0
        val velocityScoreNormalized = min(100.0, velocityScore)
        return (tempScore + ratioScore + velocityScoreNormalized) / 3.0
    }

    private fun calculateDiversityScore(
        videoId: String,
        creatorId: String,
        recentViewedIds: List<String>,
        conversationDepth: Int
    ): Double {
        val recentViewPenalty = if (recentViewedIds.contains(videoId)) -50.0 else 0.0
        val creatorFrequency = recentViewedIds.count { it.startsWith(creatorId) }
        val creatorPenalty = when {
            creatorFrequency >= 3 -> -30.0
            creatorFrequency >= 2 -> -15.0
            creatorFrequency >= 1 -> -5.0
            else -> 0.0
        }
        val depthBonus = when (conversationDepth) {
            0 -> 20.0
            1 -> 10.0
            else -> 0.0
        }
        return max(0.0, 100.0 + recentViewPenalty + creatorPenalty + depthBonus)
    }

    private fun calculateCrossTierBonus(creatorTier: UserTier, userTier: UserTier): Double {
        val creatorLevel = tierLevel(creatorTier)
        val userLevel = tierLevel(userTier)
        return when {
            creatorLevel > userLevel -> (creatorLevel - userLevel).toDouble() * 2.0
            creatorLevel == userLevel -> 5.0
            else -> 0.0
        }
    }

    private fun tierLevel(tier: UserTier): Int {
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
        }
    }

    fun intelligentShuffle(
        scoredVideos: List<Pair<VideoRankingData, ContentScore>>,
        shuffleIntensity: Double = 0.3
    ): List<Pair<VideoRankingData, ContentScore>> {
        if (shuffleIntensity <= 0.0) return scoredVideos

        val topTier = scoredVideos.filter { it.second.finalScore >= 80.0 }
        val midTier = scoredVideos.filter { it.second.finalScore >= 50.0 && it.second.finalScore < 80.0 }
        val lowTier = scoredVideos.filter { it.second.finalScore < 50.0 }

        val shuffledTop = applyTierShuffle(topTier, shuffleIntensity)
        val shuffledMid = applyTierShuffle(midTier, shuffleIntensity)
        val shuffledLow = applyTierShuffle(lowTier, shuffleIntensity)

        return shuffledTop + shuffledMid + shuffledLow
    }

    private fun applyTierShuffle(
        tierVideos: List<Pair<VideoRankingData, ContentScore>>,
        intensity: Double
    ): List<Pair<VideoRankingData, ContentScore>> {
        if (tierVideos.size <= 1) return tierVideos

        val shuffled = tierVideos.toMutableList()
        val swapCount = (tierVideos.size * intensity).toInt()

        repeat(swapCount) {
            val i = Random.nextInt(shuffled.size)
            val j = Random.nextInt(shuffled.size)
            if (i != j) {
                val temp = shuffled[i]
                shuffled[i] = shuffled[j]
                shuffled[j] = temp
            }
        }

        return shuffled
    }

    fun calculateFeedMetrics(
        scoredVideos: List<Pair<VideoRankingData, ContentScore>>,
        processingTimeMs: Long
    ): FeedMetrics {
        val scores = scoredVideos.map { it.second.finalScore }
        val averageScore = if (scores.isNotEmpty()) scores.average() else 0.0

        val distribution = mutableMapOf<String, Int>()
        scores.forEach { score ->
            val bracket = when {
                score >= 80.0 -> "High (80-100)"
                score >= 60.0 -> "Good (60-80)"
                score >= 40.0 -> "Medium (40-60)"
                score >= 20.0 -> "Low (20-40)"
                else -> "Poor (0-20)"
            }
            distribution[bracket] = distribution.getOrDefault(bracket, 0) + 1
        }

        return FeedMetrics(
            totalVideos = scoredVideos.size,
            averageScore = averageScore,
            scoreDistribution = distribution,
            algorithmVersion = "1.0.0",
            processingTimeMs = processingTimeMs
        )
    }
}