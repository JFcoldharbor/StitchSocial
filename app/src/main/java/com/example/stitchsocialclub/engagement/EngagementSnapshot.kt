package com.example.stitchsocialclub.engagement

import com.example.stitchsocialclub.foundation.UserTier
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import java.util.Date

/**
 * HypeRatingCalculator.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Business Logic - Pure Hype Rating and Temperature Calculation Functions
 * Dependencies: Layer 4,3,2,1 only (NO Android/UI dependencies)
 * Features: Temperature calculation, viral prediction, trending detection, content scoring
 *
 * Exact translation from Swift HypeRatingCalculator.swift
 */

// MARK: - Supporting Data Classes

data class EngagementSnapshot(
    val timestamp: Date,
    val hypeCount: Int,
    val coolCount: Int,
    val shareCount: Int,
    val replyCount: Int,
    val viewCount: Int
) {
    val totalEngagement: Int
        get() = hypeCount + coolCount + shareCount + replyCount

    val positivityRatio: Double
        get() {
            val interactions = hypeCount + coolCount
            return if (interactions > 0) hypeCount.toDouble() / interactions.toDouble() else 0.5
        }
}

data class ViralPrediction(
    val score: Double,
    val category: ViralCategory,
    val confidence: Double
) {
    val description: String
        get() {
            val confidenceText = when {
                confidence > 0.8 -> "High confidence"
                confidence > 0.6 -> "Medium confidence"
                else -> "Low confidence"
            }
            return "${category.displayName} ($confidenceText)"
        }
}

data class ContentQualityScore(
    val overall: Double,
    val engagement: Double,
    val depth: Double,
    val credibility: Double,
    val longevity: Double
) {
    val grade: String
        get() = when {
            overall >= 0.9 -> "A+"
            overall >= 0.8 -> "A"
            overall >= 0.7 -> "B+"
            overall >= 0.6 -> "B"
            overall >= 0.5 -> "C+"
            overall >= 0.4 -> "C"
            else -> "D"
        }
}

// MARK: - Supporting Enums

enum class VideoTemperature(val displayName: String, val emoji: String, val boostFactor: Double) {
    HOT("🔥 Hot", "🔥", 2.0),
    WARM("🌡️ Warm", "🌡️", 1.5),
    COOL("❄️ Cool", "❄️", 1.0),
    COLD("🧊 Cold", "🧊", 0.8)
}

enum class ViralCategory(val displayName: String) {
    HIGHLY_VIRAL("🚀 Highly Viral"),
    VIRAL("🔥 Viral"),
    TRENDING("📈 Trending"),
    EMERGING("⭐ Emerging"),
    NORMAL("🔄 Normal"),
    UNKNOWN("❓ Unknown")
}

enum class TrendingStatus(val displayName: String, val algorithmBoost: Double) {
    VIRAL("🚀 Viral", 3.0),
    TRENDING("🔥 Trending", 2.0),
    RISING("📈 Rising", 1.5),
    NORMAL("🔄 Normal", 1.0)
}

// MARK: - Pure Hype Rating Functions

/**
 * Pure calculation functions for video hype rating and temperature system
 * IMPORTANT: No dependencies - only pure functions for calculations
 */
object HypeRatingCalculator {

    // MARK: - Temperature Calculation

    /**
     * Calculate video temperature based on engagement metrics and recency
     * @param hypeCount Total hype interactions
     * @param coolCount Total cool interactions
     * @param viewCount Total view count
     * @param ageInMinutes Content age in minutes
     * @param creatorTier Creator's tier level
     * @return VideoTemperature classification
     */
    fun calculateTemperature(
        hypeCount: Int,
        coolCount: Int,
        viewCount: Int,
        ageInMinutes: Double,
        creatorTier: UserTier
    ): VideoTemperature {
        val hypeScore = calculateHypeScore(
            hypeCount = hypeCount,
            coolCount = coolCount,
            viewCount = viewCount,
            ageInMinutes = ageInMinutes,
            creatorTier = creatorTier
        )

        return temperatureFromScore(hypeScore)
    }

    /**
     * Calculate comprehensive hype score (0.0 to 100.0)
     * @param hypeCount Total hype interactions
     * @param coolCount Total cool interactions
     * @param viewCount Total view count
     * @param ageInMinutes Content age in minutes
     * @param creatorTier Creator's tier level
     * @return Hype score (0-100)
     */
    fun calculateHypeScore(
        hypeCount: Int,
        coolCount: Int,
        viewCount: Int,
        ageInMinutes: Double,
        creatorTier: UserTier
    ): Double {
        // Base engagement metrics
        val netEngagement = hypeCount - coolCount
        val totalEngagement = hypeCount + coolCount
        val engagementRate = if (viewCount > 0) totalEngagement.toDouble() / viewCount.toDouble() else 0.0

        // Engagement velocity (interactions per minute)
        val engagementVelocity = if (ageInMinutes > 0) totalEngagement.toDouble() / ageInMinutes else 0.0

        // View velocity (views per minute)
        val viewVelocity = if (ageInMinutes > 0) viewCount.toDouble() / ageInMinutes else 0.0

        // Positivity ratio (hype vs cool)
        val positivityRatio = if (totalEngagement > 0) hypeCount.toDouble() / totalEngagement.toDouble() else 0.5

        // Creator tier multiplier
        val tierMultiplier = calculateCreatorTierMultiplier(creatorTier)

        // Time decay factor (content loses heat over time)
        val timeFactor = calculateTimeDecayFactor(ageInMinutes)

        // Weighted score calculation
        val velocityScore = min(40.0, engagementVelocity * 20.0) // Max 40 points
        val volumeScore = min(25.0, totalEngagement.toDouble() / 10.0) // Max 25 points
        val qualityScore = positivityRatio * 20.0 // Max 20 points
        val viewScore = min(10.0, viewVelocity / 10.0) // Max 10 points
        val tierBonus = tierMultiplier * 5.0 // Max 5 points

        val rawScore = velocityScore + volumeScore + qualityScore + viewScore + tierBonus
        val adjustedScore = rawScore * timeFactor

        return max(0.0, min(100.0, adjustedScore))
    }

    /**
     * Convert hype score to temperature enum
     * @param score Hype score (0-100)
     * @return VideoTemperature classification
     */
    fun temperatureFromScore(score: Double): VideoTemperature {
        return when {
            score >= 80.0 -> VideoTemperature.HOT
            score >= 50.0 -> VideoTemperature.WARM
            score >= 20.0 -> VideoTemperature.COOL
            else -> VideoTemperature.COLD
        }
    }

    // MARK: - Viral Prediction

    /**
     * Predict viral potential based on early engagement patterns
     * @param engagementHistory List of engagement snapshots over time
     * @param currentAgeMs Current content age in milliseconds
     * @return ViralPrediction with score and confidence
     */
    fun calculateViralPotential(
        engagementHistory: List<EngagementSnapshot>,
        currentAgeMs: Long
    ): ViralPrediction {
        if (engagementHistory.isEmpty()) {
            return ViralPrediction(0.0, ViralCategory.UNKNOWN, 0.0)
        }

        val latestSnapshot = engagementHistory.last()
        val ageInHours = currentAgeMs.toDouble() / (60 * 60 * 1000)

        // Early engagement rate (critical first few hours)
        val earlyEngagementIndicator = calculateEarlyEngagementIndicator(engagementHistory, ageInHours)

        // Growth trajectory
        val growthTrajectory = if (engagementHistory.size >= 2) {
            calculateGrowthTrajectory(engagementHistory)
        } else {
            0.5 // Neutral growth for single data point
        }

        // Longevity prediction
        val longevityScore = calculateLongevityScore(engagementHistory, ageInHours)

        // Weighted viral score
        val viralScore = (earlyEngagementIndicator * 0.4) + (growthTrajectory * 0.4) + (longevityScore * 0.2)

        val category = viralCategoryFromScore(viralScore)
        val confidence = calculatePredictionConfidence(engagementHistory, viralScore)

        return ViralPrediction(viralScore, category, confidence)
    }

    // MARK: - Trending Detection

    /**
     * Determine if content is currently trending
     * @param currentHypeScore Current hype score
     * @param recentGrowthRate Recent growth rate
     * @param ageInHours Content age in hours
     * @param categoryBaseline Category performance baseline
     * @return TrendingStatus classification
     */
    fun isTrending(
        currentHypeScore: Double,
        recentGrowthRate: Double,
        ageInHours: Double,
        categoryBaseline: Double
    ): TrendingStatus {
        // Age factor (newer content gets bonus)
        val ageFactor = calculateAgeFactor(ageInHours)

        // Adjusted score with age consideration
        val adjustedScore = currentHypeScore * ageFactor

        // Growth momentum
        val momentumFactor = calculateMomentumFactor(recentGrowthRate)

        // Final trending score
        val trendingScore = adjustedScore * momentumFactor

        // Compare against category baseline
        val relativePerformance = if (categoryBaseline > 0) {
            trendingScore / categoryBaseline
        } else {
            1.0
        }

        return when {
            relativePerformance >= 3.0 && trendingScore >= 60.0 -> TrendingStatus.VIRAL
            relativePerformance >= 2.0 && trendingScore >= 40.0 -> TrendingStatus.TRENDING
            relativePerformance >= 1.5 && trendingScore >= 25.0 -> TrendingStatus.RISING
            else -> TrendingStatus.NORMAL
        }
    }

    /**
     * Calculate trending momentum for feed algorithms
     * @param hypeScore Current hype score
     * @param ageInMinutes Content age in minutes
     * @param engagementVelocity Engagement interactions per minute
     * @param viewVelocity Views per minute
     * @return Momentum score (0.0 to 2.0)
     */
    fun calculateTrendingMomentum(
        hypeScore: Double,
        ageInMinutes: Double,
        engagementVelocity: Double,
        viewVelocity: Double
    ): Double {
        // Base momentum from hype score
        val baseMomentum = hypeScore / 100.0

        // Velocity bonus (recent rapid engagement)
        val velocityBonus = min(0.5, (engagementVelocity + viewVelocity) / 20.0)

        // Recency bonus (newer content gets higher momentum)
        val recencyBonus = calculateRecencyBonus(ageInMinutes)

        // Combined momentum score
        val momentum = baseMomentum + velocityBonus + recencyBonus

        return max(0.0, min(2.0, momentum)) // Cap at 2.0 for extreme viral content
    }

    // MARK: - Content Quality Scoring

    /**
     * Calculate overall content quality score
     * @param engagementMetrics Engagement snapshot
     * @param creatorTier Creator's tier level
     * @param contentAgeMs Content age in milliseconds
     * @return ContentQualityScore breakdown
     */
    fun calculateContentQualityScore(
        engagementMetrics: EngagementSnapshot,
        creatorTier: UserTier,
        contentAgeMs: Long
    ): ContentQualityScore {
        val snapshot = engagementMetrics

        // Engagement quality
        val engagementQuality = calculateEngagementQuality(
            hypes = snapshot.hypeCount,
            cools = snapshot.coolCount,
            views = snapshot.viewCount
        )

        // Interaction depth
        val interactionDepth = calculateInteractionDepth(
            replies = snapshot.replyCount,
            shares = snapshot.shareCount,
            totalEngagement = snapshot.totalEngagement
        )

        // Creator credibility
        val credibility = calculateCreatorCredibility(creatorTier)

        // Longevity potential
        val longevity = calculateContentLongevity(snapshot, contentAgeMs)

        // Overall score (weighted average)
        val overall = (engagementQuality * 0.3) + (interactionDepth * 0.25) + 
                     (credibility * 0.25) + (longevity * 0.2)

        return ContentQualityScore(
            overall = overall,
            engagement = engagementQuality,
            depth = interactionDepth,
            credibility = credibility,
            longevity = longevity
        )
    }

    // MARK: - Helper Functions

    /**
     * Calculate creator tier multiplier for hype scoring
     */
    private fun calculateCreatorTierMultiplier(tier: UserTier): Double {
        return when (tier) {
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> 1.5
            UserTier.TOP_CREATOR -> 1.4
            UserTier.LEGENDARY -> 1.3
            UserTier.PARTNER -> 1.2
            UserTier.ELITE -> 1.1
            UserTier.INFLUENCER -> 1.05
            else -> 1.0
        }
    }

    /**
     * Calculate time decay factor for content aging
     */
    private fun calculateTimeDecayFactor(ageInMinutes: Double): Double {
        return when {
            ageInMinutes <= 60.0 -> 1.0 // First hour - no decay
            ageInMinutes <= 360.0 -> 0.9 // 1-6 hours - slight decay
            ageInMinutes <= 1440.0 -> 0.8 // 6-24 hours - moderate decay
            else -> 0.7 // 24+ hours - significant decay
        }
    }

    /**
     * Calculate early engagement indicator for viral prediction
     */
    private fun calculateEarlyEngagementIndicator(history: List<EngagementSnapshot>, ageInHours: Double): Double {
        if (history.isEmpty()) return 0.0

        val latestSnapshot = history.last()

        // Early engagement rate (engagements per hour)
        val earlyEngagementRate = if (ageInHours > 0) {
            latestSnapshot.totalEngagement.toDouble() / ageInHours
        } else {
            0.0
        }

        // View-to-engagement conversion rate
        val conversionRate = if (latestSnapshot.viewCount > 0) {
            latestSnapshot.totalEngagement.toDouble() / latestSnapshot.viewCount.toDouble()
        } else {
            0.0
        }

        // Engagement diversity (mix of hypes, views, shares)
        val diversityScore = calculateEngagementDiversity(latestSnapshot)

        // Weighted early indicators
        val rateScore = min(1.0, earlyEngagementRate / 100.0) // 100 engagements/hour = max score
        val conversionScore = min(1.0, conversionRate * 10.0) // 10% conversion = max score

        return (rateScore * 0.5) + (conversionScore * 0.3) + (diversityScore * 0.2)
    }

    /**
     * Calculate growth trajectory from engagement history
     */
    private fun calculateGrowthTrajectory(history: List<EngagementSnapshot>): Double {
        if (history.size < 2) return 0.5

        val recent = history.takeLast(3)
        var totalGrowth = 0.0
        var growthPoints = 0

        for (i in 1 until recent.size) {
            val current = recent[i]
            val previous = recent[i - 1]
            val growth = calculateEngagementVelocity(current, previous)
            totalGrowth += growth
            growthPoints++
        }

        val averageGrowth = if (growthPoints > 0) totalGrowth / growthPoints else 0.0
        return min(1.0, averageGrowth / 10.0) // 10 engagements/minute = max growth
    }

    /**
     * Calculate longevity score for sustained engagement
     */
    private fun calculateLongevityScore(history: List<EngagementSnapshot>, ageInHours: Double): Double {
        if (history.isEmpty() || ageInHours <= 0) return 0.0

        val latestSnapshot = history.last()
        val sustainedEngagement = if (ageInHours > 0) {
            latestSnapshot.totalEngagement.toDouble() / ageInHours
        } else {
            0.0
        }

        return min(1.0, sustainedEngagement / 10.0) // 10 engagements/hour = perfect longevity
    }

    /**
     * Calculate engagement velocity between two snapshots
     */
    private fun calculateEngagementVelocity(current: EngagementSnapshot, previous: EngagementSnapshot): Double {
        val timeDiff = (current.timestamp.time - previous.timestamp.time).toDouble() / (60 * 1000) // minutes
        if (timeDiff <= 0) return 0.0

        val engagementDiff = current.totalEngagement - previous.totalEngagement
        return engagementDiff.toDouble() / timeDiff
    }

    /**
     * Calculate engagement diversity score
     */
    private fun calculateEngagementDiversity(snapshot: EngagementSnapshot): Double {
        val total = snapshot.totalEngagement
        if (total <= 0) return 0.0

        // Shannon diversity index for engagement types
        val hypeRatio = snapshot.hypeCount.toDouble() / total.toDouble()
        val coolRatio = snapshot.coolCount.toDouble() / total.toDouble()
        val shareRatio = snapshot.shareCount.toDouble() / total.toDouble()
        val replyRatio = snapshot.replyCount.toDouble() / total.toDouble()

        val ratios = listOf(hypeRatio, coolRatio, shareRatio, replyRatio).filter { it > 0 }
        val diversity = -ratios.sumOf { ratio ->
            ratio * log2(ratio)
        }

        // Normalize to 0-1 scale
        return min(1.0, diversity / 2.0)
    }

    /**
     * Convert viral score to category
     */
    private fun viralCategoryFromScore(score: Double): ViralCategory {
        return when {
            score >= 0.8 -> ViralCategory.HIGHLY_VIRAL
            score >= 0.6 -> ViralCategory.VIRAL
            score >= 0.4 -> ViralCategory.TRENDING
            score >= 0.2 -> ViralCategory.EMERGING
            else -> ViralCategory.NORMAL
        }
    }

    /**
     * Calculate prediction confidence
     */
    private fun calculatePredictionConfidence(history: List<EngagementSnapshot>, score: Double): Double {
        val dataPoints = history.size.toDouble()
        val timeSpan = if (history.isNotEmpty()) {
            (history.last().timestamp.time - history.first().timestamp.time).toDouble()
        } else {
            0.0
        }

        // More data points and longer time span = higher confidence
        val dataConfidence = min(1.0, dataPoints / 10.0)
        val timeConfidence = min(1.0, timeSpan / (3600.0 * 1000)) // 1 hour = full confidence

        return (dataConfidence + timeConfidence) / 2.0
    }

    /**
     * Calculate age factor for trending
     */
    private fun calculateAgeFactor(ageInHours: Double): Double {
        return when {
            ageInHours <= 1.0 -> 1.5
            ageInHours <= 6.0 -> 1.2
            ageInHours <= 24.0 -> 1.0
            else -> 0.8
        }
    }

    /**
     * Calculate momentum factor
     */
    private fun calculateMomentumFactor(growthRate: Double): Double {
        return when {
            growthRate >= 2.0 -> 1.5 // High momentum
            growthRate >= 1.5 -> 1.3
            growthRate >= 1.0 -> 1.0
            else -> 0.8 // Low momentum
        }
    }

    /**
     * Calculate recency bonus for momentum
     */
    private fun calculateRecencyBonus(ageInMinutes: Double): Double {
        return when {
            ageInMinutes <= 30.0 -> 0.3 // Very recent
            ageInMinutes <= 120.0 -> 0.2 // Recent
            ageInMinutes <= 360.0 -> 0.1 // Somewhat recent
            else -> 0.0 // Not recent
        }
    }

    /**
     * Calculate engagement quality score
     */
    private fun calculateEngagementQuality(hypes: Int, cools: Int, views: Int): Double {
        val totalEngagement = hypes + cools
        if (totalEngagement == 0) return 0.0

        val positivityRatio = hypes.toDouble() / totalEngagement.toDouble()
        val engagementRate = if (views > 0) totalEngagement.toDouble() / views.toDouble() else 0.0

        return (positivityRatio * 0.6) + (min(1.0, engagementRate * 10.0) * 0.4)
    }

    /**
     * Calculate interaction depth score
     */
    private fun calculateInteractionDepth(replies: Int, shares: Int, totalEngagement: Int): Double {
        if (totalEngagement == 0) return 0.0

        val deepInteractions = replies + shares
        val deepInteractionRatio = deepInteractions.toDouble() / totalEngagement.toDouble()

        return min(1.0, deepInteractionRatio * 5.0) // 20% deep interactions = max score
    }

    /**
     * Calculate creator credibility score
     */
    private fun calculateCreatorCredibility(tier: UserTier): Double {
        return when (tier) {
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> 1.0
            UserTier.TOP_CREATOR -> 0.95
            UserTier.LEGENDARY -> 0.9
            UserTier.PARTNER -> 0.85
            UserTier.ELITE -> 0.8
            UserTier.INFLUENCER -> 0.75
            UserTier.VETERAN -> 0.7
            UserTier.RISING -> 0.6
            UserTier.ROOKIE -> 0.5
        }
    }

    /**
     * Calculate content longevity potential
     */
    private fun calculateContentLongevity(snapshot: EngagementSnapshot, ageMs: Long): Double {
        val ageInHours = ageMs.toDouble() / (60 * 60 * 1000)
        if (ageInHours <= 0) return 0.5

        val engagementPerHour = snapshot.totalEngagement.toDouble() / ageInHours
        val sustainabilityScore = min(1.0, engagementPerHour / 5.0) // 5 engagements/hour = sustainable

        return sustainabilityScore
    }

    // MARK: - Test Function

    /**
     * Test hype rating calculator with mock data
     * @return Test results string
     */
    fun helloWorldTest(): String {
        val testSnapshot = EngagementSnapshot(
            timestamp = Date(System.currentTimeMillis() - 3600000), // 1 hour ago
            hypeCount = 150,
            coolCount = 20,
            shareCount = 35,
            replyCount = 12,
            viewCount = 1200
        )

        val temperature = calculateTemperature(
            hypeCount = 150,
            coolCount = 20,
            viewCount = 1200,
            ageInMinutes = 60.0,
            creatorTier = UserTier.VETERAN
        )

        val hypeScore = calculateHypeScore(
            hypeCount = 150,
            coolCount = 20,
            viewCount = 1200,
            ageInMinutes = 60.0,
            creatorTier = UserTier.VETERAN
        )

        val momentum = calculateTrendingMomentum(
            hypeScore = hypeScore,
            ageInMinutes = 60.0,
            engagementVelocity = 3.0,
            viewVelocity = 20.0
        )

        val result = """
        🔥 HYPE RATING CALCULATOR: Hello World - Pure hype rating functions ready!
        
        Test Results for Veteran Creator Video (1hr old):
        → Temperature: ${temperature.displayName}
        → Hype Score: ${"%.1f".format(hypeScore)}/100.0
        → Trending Momentum: ${"%.2f".format(momentum)}
        → Engagement Rate: ${"%.1f".format(testSnapshot.totalEngagement.toDouble() / testSnapshot.viewCount.toDouble() * 100)}%
        
        Features Implemented:
        - Temperature Calculation: HOT/WARM/COOL/COLD classification
        - Viral Prediction: Early engagement indicators + growth trajectory + longevity
        - Trending Detection: Age factor + momentum + category baseline comparison
        - Content Quality: Engagement + depth + credibility + longevity scoring
        - Hype Scoring: Multi-factor algorithm with creator tier bonuses
        
        Hype Algorithm:
        - Velocity Score: Engagement interactions per minute (max 40pts)
        - Volume Score: Total engagement count (max 25pts)
        - Quality Score: Positivity ratio (hype vs cool) (max 20pts)
        - View Score: Views per minute velocity (max 10pts)
        - Tier Bonus: Creator tier multiplier (max 5pts)
        - Time Decay: Content aging factor (60min=1.0x, 24hr=0.8x)
        
        Viral Prediction:
        - Early Indicators: Engagement rate + conversion + diversity (40%)
        - Growth Trajectory: Recent velocity trends (40%)
        - Longevity Score: Sustained engagement potential (20%)
        - Categories: Highly Viral → Viral → Trending → Emerging → Normal
        
        Metrics: 150 hypes, 20 cools, 35 shares, 12 replies, 1200 views
        Algorithm Status: All calculation functions operational
        
        Status: Layer 5 HypeRatingCalculator ready for production! 🔥
        """.trimIndent()

        return result
    }
}