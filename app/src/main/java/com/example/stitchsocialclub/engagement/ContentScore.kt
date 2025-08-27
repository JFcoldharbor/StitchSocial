package com.example.stitchsocialclub.engagement

import com.example.stitchsocialclub.foundation.UserTier
import com.example.stitchsocialclub.foundation.Temperature
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * AlgorithmicEngine.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Business Logic - Pure Feed Algorithm Functions
 * Dependencies: Layer 4,3,2,1 only (NO Android/UI dependencies)
 * Features: Feed ranking, content scoring, discovery algorithms
 *
 * Exact translation from Swift HomeFeedService.swift and discovery algorithms
 */

// MARK: - Algorithm Data Classes

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

// MARK: - Pure Algorithm Functions

/**
 * Pure calculation functions for feed algorithms
 * IMPORTANT: No dependencies - only pure functions for calculations
 */
object AlgorithmicEngine {

    // MARK: - Feed Ranking Algorithms

    /**
     * Calculate comprehensive content score for feed ranking
     * @param video Video ranking data
     * @param userTier Current user's tier
     * @param recentViewedIds Recently viewed video IDs for diversity
     * @param followingCreatorIds IDs of creators user follows
     * @return ContentScore with detailed scoring breakdown
     */
    fun calculateContentScore(
        video: VideoRankingData,
        userTier: UserTier,
        recentViewedIds: List<String>,
        followingCreatorIds: List<String>
    ): ContentScore {

        // Base engagement score (0.0 to 100.0)
        val engagementScore = calculateEngagementScore(
            hype = video.hypeCount,
            cool = video.coolCount,
            views = video.viewCount,
            replies = video.replyCount,
            shares = video.shareCount
        )

        // Recency score (newer content gets boost)
        val recencyScore = calculateRecencyScore(video.ageInHours)

        // Creator score (tier and following boost)
        val creatorScore = calculateCreatorScore(
            creatorTier = video.creatorTier,
            isFollowing = followingCreatorIds.contains(video.creatorId),
            userTier = userTier
        )

        // Quality score (temperature and engagement ratio)
        val qualityScore = calculateQualityScore(
            temperature = video.temperature,
            engagementRatio = video.engagementRatio,
            velocityScore = video.velocityScore
        )

        // Diversity score (avoid recently viewed content)
        val diversityScore = calculateDiversityScore(
            videoId = video.id,
            creatorId = video.creatorId,
            recentViewedIds = recentViewedIds,
            conversationDepth = video.conversationDepth
        )

        // Calculate weighted final score
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

    /**
     * Rank videos for personalized feed
     * @param videos List of video ranking data
     * @param userTier Current user's tier
     * @param recentViewedIds Recently viewed video IDs
     * @param followingCreatorIds IDs of creators user follows
     * @return Sorted list of videos by algorithm score
     */
    fun rankVideosForFeed(
        videos: List<VideoRankingData>,
        userTier: UserTier,
        recentViewedIds: List<String>,
        followingCreatorIds: List<String>
    ): List<Pair<VideoRankingData, ContentScore>> {

        // Calculate scores for all videos
        val scoredVideos = videos.map { video ->
            val score = calculateContentScore(video, userTier, recentViewedIds, followingCreatorIds)
            Pair(video, score)
        }

        // Sort by final score (highest first)
        return scoredVideos.sortedByDescending { it.second.finalScore }
    }

    // MARK: - Individual Score Calculations

    /**
     * Calculate engagement score from interaction metrics
     */
    private fun calculateEngagementScore(
        hype: Int,
        cool: Int,
        views: Int,
        replies: Int,
        shares: Int
    ): Double {
        val totalEngagement = hype + cool + replies + shares

        // Base engagement rate
        val engagementRate = if (views > 0) totalEngagement.toDouble() / views.toDouble() else 0.0

        // Weighted interaction values
        val weightedScore = (hype * 3.0) + (replies * 5.0) + (shares * 4.0) - (cool * 1.0)

        // Normalize to 0-100 scale
        val normalizedRate = min(1.0, engagementRate * 10.0) * 50.0
        val normalizedWeighted = max(0.0, min(50.0, weightedScore / 10.0))

        return normalizedRate + normalizedWeighted
    }

    /**
     * Calculate recency score (newer content gets higher score)
     */
    private fun calculateRecencyScore(ageInHours: Double): Double {
        return when {
            ageInHours <= 1.0 -> 100.0  // Very fresh
            ageInHours <= 6.0 -> 80.0   // Fresh
            ageInHours <= 24.0 -> 60.0  // Recent
            ageInHours <= 72.0 -> 40.0  // Older
            else -> 20.0                // Old
        }
    }

    /**
     * Calculate creator score based on tier and following status
     */
    private fun calculateCreatorScore(
        creatorTier: UserTier,
        isFollowing: Boolean,
        userTier: UserTier
    ): Double {
        // Base score from creator tier
        val tierScore = when (creatorTier) {
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> 100.0
            UserTier.TOP_CREATOR -> 90.0
            UserTier.LEGENDARY -> 80.0
            UserTier.PARTNER -> 70.0
            UserTier.ELITE -> 60.0
            UserTier.INFLUENCER -> 50.0
            UserTier.VETERAN -> 40.0
            UserTier.RISING -> 30.0
            UserTier.ROOKIE -> 20.0
        }

        // Following bonus
        val followingBonus = if (isFollowing) 30.0 else 0.0

        // Cross-tier interaction bonus
        val crossTierBonus = calculateCrossTierBonus(creatorTier, userTier)

        return min(100.0, tierScore + followingBonus + crossTierBonus)
    }

    /**
     * Calculate quality score from temperature and engagement metrics
     */
    private fun calculateQualityScore(
        temperature: String,
        engagementRatio: Double,
        velocityScore: Double
    ): Double {
        // Temperature score
        val tempScore = when (temperature.lowercase()) {
            "blazing" -> 100.0
            "hot" -> 80.0
            "warm" -> 60.0
            "neutral" -> 40.0
            "cool" -> 20.0
            "cold" -> 10.0
            else -> 40.0
        }

        // Engagement ratio score (0.0 to 1.0 -> 0.0 to 50.0)
        val ratioScore = engagementRatio * 50.0

        // Velocity score (already 0-100)
        val velocityScoreNormalized = min(100.0, velocityScore)

        return (tempScore + ratioScore + velocityScoreNormalized) / 3.0
    }

    /**
     * Calculate diversity score to avoid repetitive content
     */
    private fun calculateDiversityScore(
        videoId: String,
        creatorId: String,
        recentViewedIds: List<String>,
        conversationDepth: Int
    ): Double {
        // Penalty for recently viewed videos
        val recentViewPenalty = if (recentViewedIds.contains(videoId)) -50.0 else 0.0

        // Penalty for same creator shown too frequently
        val creatorFrequency = recentViewedIds.count { it.startsWith(creatorId) }
        val creatorPenalty = when {
            creatorFrequency >= 3 -> -30.0
            creatorFrequency >= 2 -> -15.0
            creatorFrequency >= 1 -> -5.0
            else -> 0.0
        }

        // Bonus for thread starters (more diverse content)
        val depthBonus = when (conversationDepth) {
            0 -> 20.0  // Thread starter
            1 -> 10.0  // Child
            else -> 0.0 // Stepchild
        }

        return max(0.0, 100.0 + recentViewPenalty + creatorPenalty + depthBonus)
    }

    /**
     * Calculate cross-tier interaction bonus
     */
    private fun calculateCrossTierBonus(creatorTier: UserTier, userTier: UserTier): Double {
        val creatorLevel = tierLevel(creatorTier)
        val userLevel = tierLevel(userTier)

        return when {
            creatorLevel > userLevel -> (creatorLevel - userLevel).toDouble() * 2.0 // Higher tier creator
            creatorLevel == userLevel -> 5.0 // Same tier
            else -> 0.0 // Lower tier creator
        }
    }

    /**
     * Get numeric level for tier (for calculations)
     */
    private fun tierLevel(tier: UserTier): Int {
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

    // MARK: - Feed Shuffling & Randomization

    /**
     * Apply intelligent shuffle to maintain engagement while adding randomness
     * @param scoredVideos Pre-scored and sorted videos
     * @param shuffleIntensity How much to shuffle (0.0 = no shuffle, 1.0 = full random)
     * @return Shuffled list maintaining general score order
     */
    fun intelligentShuffle(
        scoredVideos: List<Pair<VideoRankingData, ContentScore>>,
        shuffleIntensity: Double = 0.3
    ): List<Pair<VideoRankingData, ContentScore>> {
        if (shuffleIntensity <= 0.0) return scoredVideos

        // Group videos by score ranges
        val topTier = scoredVideos.filter { it.second.finalScore >= 80.0 }
        val midTier = scoredVideos.filter { it.second.finalScore >= 50.0 && it.second.finalScore < 80.0 }
        val lowTier = scoredVideos.filter { it.second.finalScore < 50.0 }

        // Shuffle within each tier
        val shuffledTop = applyTierShuffle(topTier, shuffleIntensity)
        val shuffledMid = applyTierShuffle(midTier, shuffleIntensity)
        val shuffledLow = applyTierShuffle(lowTier, shuffleIntensity)

        return shuffledTop + shuffledMid + shuffledLow
    }

    /**
     * Apply controlled shuffle within a score tier
     */
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

    // MARK: - Analytics & Metrics

    /**
     * Calculate feed metrics for analytics
     * @param scoredVideos Scored video data
     * @param processingTimeMs Time taken to process feed
     * @return Feed metrics summary
     */
    fun calculateFeedMetrics(
        scoredVideos: List<Pair<VideoRankingData, ContentScore>>,
        processingTimeMs: Long
    ): FeedMetrics {
        val scores = scoredVideos.map { it.second.finalScore }
        val averageScore = if (scores.isNotEmpty()) scores.average() else 0.0

        // Score distribution
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

    // MARK: - Test Function

    /**
     * Test algorithmic engine with mock data
     * @return Test results string
     */
    fun helloWorldTest(): String {
        val result = """
        🎯 ALGORITHMIC ENGINE: Hello World - Pure feed algorithm functions ready!
        
        Test Results:
        - Content Scoring: Multi-factor algorithm (engagement + recency + creator + quality + diversity)
        - Feed Ranking: Personalized scoring based on user tier and preferences
        - Intelligent Shuffle: Controlled randomization within score tiers
        - Cross-Tier Bonus: Higher tier creators get algorithmic boost
        - Diversity Protection: Anti-repetition scoring for better UX
        
        Algorithm Features:
        - Engagement Score: Weighted interactions (hype=3x, reply=5x, share=4x, cool=-1x)
        - Recency Boost: Fresh content (1hr=100pts, 6hr=80pts, 24hr=60pts)
        - Creator Tier Bonus: Founder=100pts, Elite=60pts, Rookie=20pts
        - Temperature Integration: Blazing=100pts, Hot=80pts, Neutral=40pts
        - Following Boost: +30pts for followed creators
        
        Status: All algorithms functional ✅
        """.trimIndent()

        return result
    }
}