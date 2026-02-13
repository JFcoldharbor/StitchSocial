/*
 * RecordingSource.kt - RECORDING SOURCE + CONTENT SCORE CALCULATOR
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Business Logic - Dynamic Quality & Discoverability Score Calculations
 * Dependencies: CoreVideoMetadata
 * Features: Recording source multipliers, engagement-based quality scoring, dynamic discoverability
 *
 * EXACT PORT: RecordingSource.swift + ContentScoreCalculator.swift
 */

package com.stitchsocial.club.foundation

import java.util.Date
import kotlin.math.max
import kotlin.math.min

// MARK: - Recording Source

/**
 * Tracks how a video was created — used for authenticity scoring
 */
enum class RecordingSource(val value: String) {
    IN_APP("inApp"),              // Recorded live via tap-and-hold in StitchSocial
    CAMERA_ROLL("cameraRoll"),    // Uploaded from device photo library
    UNKNOWN("unknown");           // Legacy content or undetermined

    /** Discoverability multiplier — rewards authentic in-app content */
    val discoverabilityMultiplier: Double
        get() = when (this) {
            IN_APP -> 1.25       // 25% boost for live-recorded content
            CAMERA_ROLL -> 0.70  // 30% penalty for uploads
            UNKNOWN -> 0.85      // Slight penalty for legacy/unknown
        }

    /** Display label for UI badge */
    val badgeLabel: String?
        get() = when (this) {
            IN_APP -> "Recorded Live"
            CAMERA_ROLL -> null
            UNKNOWN -> null
        }

    companion object {
        fun fromRawValue(value: String): RecordingSource {
            return entries.firstOrNull { it.value == value } ?: UNKNOWN
        }
    }
}

// MARK: - Content Score Calculator

/**
 * Calculates dynamic qualityScore (0–100) and discoverabilityScore (0.0–1.0)
 * based on real engagement data, recording source, and content signals.
 */
object ContentScoreCalculator {

    // MARK: - Quality Score (0–100)

    /**
     * Recalculate qualityScore from engagement data
     * Components:
     *   - Engagement ratio (hype vs cool):  0–25 points
     *   - Reply depth / conversation:       0–20 points
     *   - Share traction:                   0–15 points
     *   - View-to-engagement conversion:    0–20 points
     *   - Content completeness:             0–10 points
     *   - Recording source bonus:           0–10 points
     */
    fun calculateQualityScore(
        hypeCount: Int,
        coolCount: Int,
        replyCount: Int,
        shareCount: Int,
        viewCount: Int,
        conversationDepth: Int,
        duration: Double,
        recordingSource: RecordingSource
    ): Int {
        // 1. Engagement ratio (0–25)
        val totalReactions = hypeCount + coolCount
        val positivityRatio: Double = if (totalReactions > 0)
            hypeCount.toDouble() / totalReactions.toDouble()
        else 0.5
        val engagementPoints = positivityRatio * 25.0

        // 2. Reply/conversation depth (0–20)
        val replyPoints = min(20.0, replyCount.toDouble() * 2.0)

        // 3. Share traction (0–15)
        val sharePoints = min(15.0, shareCount.toDouble() * 3.0)

        // 4. View-to-engagement conversion (0–20)
        val totalEngagement = hypeCount + coolCount + replyCount + shareCount
        val conversionRate: Double = if (viewCount > 0)
            totalEngagement.toDouble() / viewCount.toDouble()
        else 0.0
        val conversionPoints = min(20.0, conversionRate * 100.0)

        // 5. Content completeness (0–10)
        val durationPoints: Double = when {
            duration >= 5.0 && duration <= 45.0 -> 10.0   // Sweet spot
            duration >= 3.0 && duration <= 55.0 -> 6.0    // Acceptable
            else -> 2.0                                    // Very short or maxed out
        }

        // 6. Recording source bonus (0–10)
        val sourcePoints: Double = when (recordingSource) {
            RecordingSource.IN_APP -> 10.0
            RecordingSource.CAMERA_ROLL -> 2.0
            RecordingSource.UNKNOWN -> 4.0
        }

        val rawScore = engagementPoints + replyPoints + sharePoints + conversionPoints + durationPoints + sourcePoints
        return max(0, min(100, rawScore.toInt()))
    }

    // MARK: - Discoverability Score (0.0–1.0)

    /**
     * Recalculate discoverabilityScore from quality, recency, temperature, and source.
     * This is the primary feed ranking signal.
     */
    fun calculateDiscoverabilityScore(
        qualityScore: Int,
        temperature: Temperature,
        createdAt: Date,
        recordingSource: RecordingSource,
        hypeCount: Int,
        viewCount: Int,
        replyCount: Int,
        isPromoted: Boolean
    ): Double {
        // 1. Quality base (0.0–0.40)
        val qualityBase = (qualityScore.toDouble() / 100.0) * 0.40

        // 2. Temperature multiplier (0.0–0.25)
        val temperatureBonus: Double = when (temperature) {
            Temperature.BLAZING -> 0.25
            Temperature.HOT -> 0.20
            Temperature.WARM -> 0.12
            Temperature.COOL -> 0.05
            Temperature.COLD -> 0.0
            Temperature.FROZEN -> 0.0
        }

        // 3. Recency decay (0.0–0.20)
        val ageHours = max(0.0, (Date().time - createdAt.time).toDouble() / 3600000.0)
        val recencyScore: Double = when {
            ageHours < 1 -> 0.20       // Brand new — full recency boost
            ageHours < 6 -> 0.16
            ageHours < 24 -> 0.10
            ageHours < 72 -> 0.05
            else -> 0.01               // Old content — minimal recency
        }

        // 4. Recording source multiplier (applied to total)
        val sourceMultiplier = recordingSource.discoverabilityMultiplier

        // 5. Engagement velocity bonus (0.0–0.15)
        val velocityBase: Double = if (ageHours > 0)
            (hypeCount + replyCount).toDouble() / ageHours
        else
            (hypeCount + replyCount).toDouble()
        val velocityBonus = min(0.15, velocityBase / 200.0)

        // Combine
        var rawScore = (qualityBase + temperatureBonus + recencyScore + velocityBonus) * sourceMultiplier

        // Promoted content gets a flat boost
        if (isPromoted) {
            rawScore += 0.10
        }

        return max(0.0, min(1.0, rawScore))
    }

    // MARK: - Convenience: Full Recalculation

    /**
     * Recalculate both scores for a video — returns Pair(qualityScore, discoverabilityScore)
     */
    fun recalculateScores(video: CoreVideoMetadata): Pair<Int, Double> {
        val source = RecordingSource.fromRawValue(video.recordingSource)

        val quality = calculateQualityScore(
            hypeCount = video.hypeCount,
            coolCount = video.coolCount,
            replyCount = video.replyCount,
            shareCount = video.shareCount,
            viewCount = video.viewCount,
            conversationDepth = video.conversationDepth,
            duration = video.duration,
            recordingSource = source
        )

        val discoverability = calculateDiscoverabilityScore(
            qualityScore = quality,
            temperature = video.temperature,
            createdAt = video.createdAt,
            recordingSource = source,
            hypeCount = video.hypeCount,
            viewCount = video.viewCount,
            replyCount = video.replyCount,
            isPromoted = video.isPromoted
        )

        return Pair(quality, discoverability)
    }
}