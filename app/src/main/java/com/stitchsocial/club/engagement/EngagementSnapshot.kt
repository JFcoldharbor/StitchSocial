package com.stitchsocial.club.engagement

import com.stitchsocial.club.foundation.UserTier
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import java.util.Date

/**
 * HypeRatingCalculator.kt - FIXED: Added AMBASSADOR tier
 */

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

enum class VideoTemperature(val displayName: String, val emoji: String, val boostFactor: Double) {
    HOT("Hot", "🔥", 2.0),
    WARM("Warm", "🌡️", 1.5),
    COOL("Cool", "❄️", 1.0),
    COLD("Cold", "🧊", 0.8)
}

enum class ViralCategory(val displayName: String) {
    HIGHLY_VIRAL("Highly Viral"),
    VIRAL("Viral"),
    TRENDING("Trending"),
    EMERGING("Emerging"),
    NORMAL("Normal"),
    UNKNOWN("Unknown")
}

enum class TrendingStatus(val displayName: String, val algorithmBoost: Double) {
    VIRAL("Viral", 3.0),
    TRENDING("Trending", 2.0),
    RISING("Rising", 1.5),
    NORMAL("Normal", 1.0)
}

object HypeRatingCalculator {

    fun calculateTemperature(
        hypeCount: Int,
        coolCount: Int,
        viewCount: Int,
        ageInMinutes: Double,
        creatorTier: UserTier
    ): VideoTemperature {
        val hypeScore = calculateHypeScore(hypeCount, coolCount, viewCount, ageInMinutes, creatorTier)
        return temperatureFromScore(hypeScore)
    }

    fun calculateHypeScore(
        hypeCount: Int,
        coolCount: Int,
        viewCount: Int,
        ageInMinutes: Double,
        creatorTier: UserTier
    ): Double {
        val totalEngagement = hypeCount + coolCount
        val engagementVelocity = if (ageInMinutes > 0) totalEngagement.toDouble() / ageInMinutes else 0.0
        val viewVelocity = if (ageInMinutes > 0) viewCount.toDouble() / ageInMinutes else 0.0
        val positivityRatio = if (totalEngagement > 0) hypeCount.toDouble() / totalEngagement.toDouble() else 0.5
        val tierMultiplier = calculateCreatorTierMultiplier(creatorTier)
        val timeFactor = calculateTimeDecayFactor(ageInMinutes)

        val velocityScore = min(40.0, engagementVelocity * 20.0)
        val volumeScore = min(25.0, totalEngagement.toDouble() / 10.0)
        val qualityScore = positivityRatio * 20.0
        val viewScore = min(10.0, viewVelocity / 10.0)
        val tierBonus = tierMultiplier * 5.0

        val rawScore = velocityScore + volumeScore + qualityScore + viewScore + tierBonus
        val adjustedScore = rawScore * timeFactor

        return max(0.0, min(100.0, adjustedScore))
    }

    fun temperatureFromScore(score: Double): VideoTemperature {
        return when {
            score >= 80.0 -> VideoTemperature.HOT
            score >= 50.0 -> VideoTemperature.WARM
            score >= 20.0 -> VideoTemperature.COOL
            else -> VideoTemperature.COLD
        }
    }

    fun calculateViralPotential(
        engagementHistory: List<EngagementSnapshot>,
        currentAgeMs: Long
    ): ViralPrediction {
        if (engagementHistory.isEmpty()) {
            return ViralPrediction(0.0, ViralCategory.UNKNOWN, 0.0)
        }

        val ageInHours = currentAgeMs.toDouble() / (60 * 60 * 1000)
        val earlyEngagementIndicator = calculateEarlyEngagementIndicator(engagementHistory, ageInHours)
        val growthTrajectory = if (engagementHistory.size >= 2) {
            calculateGrowthTrajectory(engagementHistory)
        } else 0.5

        val longevityScore = calculateLongevityScore(engagementHistory, ageInHours)
        val viralScore = (earlyEngagementIndicator * 0.4) + (growthTrajectory * 0.4) + (longevityScore * 0.2)
        val category = viralCategoryFromScore(viralScore)
        val confidence = calculatePredictionConfidence(engagementHistory, viralScore)

        return ViralPrediction(viralScore, category, confidence)
    }

    fun isTrending(
        currentHypeScore: Double,
        recentGrowthRate: Double,
        ageInHours: Double,
        categoryBaseline: Double
    ): TrendingStatus {
        val ageFactor = calculateAgeFactor(ageInHours)
        val adjustedScore = currentHypeScore * ageFactor
        val momentumFactor = calculateMomentumFactor(recentGrowthRate)
        val trendingScore = adjustedScore * momentumFactor

        val relativePerformance = if (categoryBaseline > 0) {
            trendingScore / categoryBaseline
        } else 1.0

        return when {
            relativePerformance >= 3.0 && trendingScore >= 60.0 -> TrendingStatus.VIRAL
            relativePerformance >= 2.0 && trendingScore >= 40.0 -> TrendingStatus.TRENDING
            relativePerformance >= 1.5 && trendingScore >= 25.0 -> TrendingStatus.RISING
            else -> TrendingStatus.NORMAL
        }
    }

    fun calculateTrendingMomentum(
        hypeScore: Double,
        ageInMinutes: Double,
        engagementVelocity: Double,
        viewVelocity: Double
    ): Double {
        val baseMomentum = hypeScore / 100.0
        val velocityBonus = min(0.5, (engagementVelocity + viewVelocity) / 20.0)
        val recencyBonus = calculateRecencyBonus(ageInMinutes)
        val momentum = baseMomentum + velocityBonus + recencyBonus
        return max(0.0, min(2.0, momentum))
    }

    fun calculateContentQualityScore(
        engagementMetrics: EngagementSnapshot,
        creatorTier: UserTier,
        contentAgeMs: Long
    ): ContentQualityScore {
        val snapshot = engagementMetrics
        val engagementQuality = calculateEngagementQuality(snapshot.hypeCount, snapshot.coolCount, snapshot.viewCount)
        val interactionDepth = calculateInteractionDepth(snapshot.replyCount, snapshot.shareCount, snapshot.totalEngagement)
        val credibility = calculateCreatorCredibility(creatorTier)
        val longevity = calculateContentLongevity(snapshot, contentAgeMs)
        val overall = (engagementQuality * 0.3) + (interactionDepth * 0.25) + (credibility * 0.25) + (longevity * 0.2)

        return ContentQualityScore(
            overall = overall,
            engagement = engagementQuality,
            depth = interactionDepth,
            credibility = credibility,
            longevity = longevity
        )
    }

    private fun calculateCreatorTierMultiplier(tier: UserTier): Double {
        return when (tier) {
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> 1.5
            UserTier.TOP_CREATOR -> 1.4
            UserTier.LEGENDARY -> 1.3
            UserTier.PARTNER -> 1.2
            UserTier.ELITE -> 1.1
            UserTier.AMBASSADOR -> 1.08
            UserTier.INFLUENCER -> 1.05
            UserTier.VETERAN -> 1.0
            UserTier.RISING -> 1.0
            UserTier.ROOKIE -> 1.0
            UserTier.BUSINESS -> 1.0
        }
    }

    private fun calculateTimeDecayFactor(ageInMinutes: Double): Double {
        return when {
            ageInMinutes <= 60.0 -> 1.0
            ageInMinutes <= 360.0 -> 0.9
            ageInMinutes <= 1440.0 -> 0.8
            else -> 0.7
        }
    }

    private fun calculateEarlyEngagementIndicator(history: List<EngagementSnapshot>, ageInHours: Double): Double {
        if (history.isEmpty()) return 0.0
        val latestSnapshot = history.last()
        val earlyEngagementRate = if (ageInHours > 0) latestSnapshot.totalEngagement.toDouble() / ageInHours else 0.0
        val conversionRate = if (latestSnapshot.viewCount > 0) latestSnapshot.totalEngagement.toDouble() / latestSnapshot.viewCount.toDouble() else 0.0
        val diversityScore = calculateEngagementDiversity(latestSnapshot)
        val rateScore = min(1.0, earlyEngagementRate / 100.0)
        val conversionScore = min(1.0, conversionRate * 10.0)
        return (rateScore * 0.5) + (conversionScore * 0.3) + (diversityScore * 0.2)
    }

    private fun calculateGrowthTrajectory(history: List<EngagementSnapshot>): Double {
        if (history.size < 2) return 0.5
        val recent = history.takeLast(3)
        var totalGrowth = 0.0
        var growthPoints = 0
        for (i in 1 until recent.size) {
            val growth = calculateEngagementVelocity(recent[i], recent[i - 1])
            totalGrowth += growth
            growthPoints++
        }
        val averageGrowth = if (growthPoints > 0) totalGrowth / growthPoints else 0.0
        return min(1.0, averageGrowth / 10.0)
    }

    private fun calculateLongevityScore(history: List<EngagementSnapshot>, ageInHours: Double): Double {
        if (history.isEmpty() || ageInHours <= 0) return 0.0
        val latestSnapshot = history.last()
        val sustainedEngagement = latestSnapshot.totalEngagement.toDouble() / ageInHours
        return min(1.0, sustainedEngagement / 10.0)
    }

    private fun calculateEngagementVelocity(current: EngagementSnapshot, previous: EngagementSnapshot): Double {
        val timeDiff = (current.timestamp.time - previous.timestamp.time).toDouble() / (60 * 1000)
        if (timeDiff <= 0) return 0.0
        val engagementDiff = current.totalEngagement - previous.totalEngagement
        return engagementDiff.toDouble() / timeDiff
    }

    private fun calculateEngagementDiversity(snapshot: EngagementSnapshot): Double {
        val total = snapshot.totalEngagement
        if (total <= 0) return 0.0
        val hypeRatio = snapshot.hypeCount.toDouble() / total.toDouble()
        val coolRatio = snapshot.coolCount.toDouble() / total.toDouble()
        val shareRatio = snapshot.shareCount.toDouble() / total.toDouble()
        val replyRatio = snapshot.replyCount.toDouble() / total.toDouble()
        val ratios = listOf(hypeRatio, coolRatio, shareRatio, replyRatio).filter { it > 0 }
        val diversity = -ratios.sumOf { ratio -> ratio * log2(ratio) }
        return min(1.0, diversity / 2.0)
    }

    private fun viralCategoryFromScore(score: Double): ViralCategory {
        return when {
            score >= 0.8 -> ViralCategory.HIGHLY_VIRAL
            score >= 0.6 -> ViralCategory.VIRAL
            score >= 0.4 -> ViralCategory.TRENDING
            score >= 0.2 -> ViralCategory.EMERGING
            else -> ViralCategory.NORMAL
        }
    }

    private fun calculatePredictionConfidence(history: List<EngagementSnapshot>, score: Double): Double {
        val dataPoints = history.size.toDouble()
        val timeSpan = if (history.isNotEmpty()) (history.last().timestamp.time - history.first().timestamp.time).toDouble() else 0.0
        val dataConfidence = min(1.0, dataPoints / 10.0)
        val timeConfidence = min(1.0, timeSpan / (3600.0 * 1000))
        return (dataConfidence + timeConfidence) / 2.0
    }

    private fun calculateAgeFactor(ageInHours: Double): Double {
        return when {
            ageInHours <= 1.0 -> 1.5
            ageInHours <= 6.0 -> 1.2
            ageInHours <= 24.0 -> 1.0
            else -> 0.8
        }
    }

    private fun calculateMomentumFactor(growthRate: Double): Double {
        return when {
            growthRate >= 2.0 -> 1.5
            growthRate >= 1.5 -> 1.3
            growthRate >= 1.0 -> 1.0
            else -> 0.8
        }
    }

    private fun calculateRecencyBonus(ageInMinutes: Double): Double {
        return when {
            ageInMinutes <= 30.0 -> 0.3
            ageInMinutes <= 120.0 -> 0.2
            ageInMinutes <= 360.0 -> 0.1
            else -> 0.0
        }
    }

    private fun calculateEngagementQuality(hypes: Int, cools: Int, views: Int): Double {
        val totalEngagement = hypes + cools
        if (totalEngagement == 0) return 0.0
        val positivityRatio = hypes.toDouble() / totalEngagement.toDouble()
        val engagementRate = if (views > 0) totalEngagement.toDouble() / views.toDouble() else 0.0
        return (positivityRatio * 0.6) + (min(1.0, engagementRate * 10.0) * 0.4)
    }

    private fun calculateInteractionDepth(replies: Int, shares: Int, totalEngagement: Int): Double {
        if (totalEngagement == 0) return 0.0
        val deepInteractions = replies + shares
        val deepInteractionRatio = deepInteractions.toDouble() / totalEngagement.toDouble()
        return min(1.0, deepInteractionRatio * 5.0)
    }

    private fun calculateCreatorCredibility(tier: UserTier): Double {
        return when (tier) {
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> 1.0
            UserTier.TOP_CREATOR -> 0.95
            UserTier.LEGENDARY -> 0.9
            UserTier.PARTNER -> 0.85
            UserTier.ELITE -> 0.8
            UserTier.AMBASSADOR -> 0.78
            UserTier.INFLUENCER -> 0.75
            UserTier.VETERAN -> 0.7
            UserTier.RISING -> 0.6
            UserTier.ROOKIE -> 0.5
            UserTier.BUSINESS -> 0.5
        }
    }

    private fun calculateContentLongevity(snapshot: EngagementSnapshot, ageMs: Long): Double {
        val ageInHours = ageMs.toDouble() / (60 * 60 * 1000)
        if (ageInHours <= 0) return 0.5
        val engagementPerHour = snapshot.totalEngagement.toDouble() / ageInHours
        return min(1.0, engagementPerHour / 5.0)
    }
}