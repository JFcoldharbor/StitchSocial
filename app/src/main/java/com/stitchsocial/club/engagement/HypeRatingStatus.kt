/*
 * HypeRatingStatus.kt - HYPE RATING VALIDATION TYPES
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 2: Protocols & Events - Hype rating status data structures
 * Dependencies: None (Pure Kotlin data classes and enums)
 * Features: Hype rating validation, status messages, color indicators
 *
 * PURPOSE: Data structures for checking if user can afford engagements
 */

package com.stitchsocial.club.engagement

/**
 * Hype rating status with validation and UI feedback
 */
data class HypeRatingStatus(
    val canEngage: Boolean,
    val currentPercent: Double,
    val message: String,
    val statusLevel: HypeRatingLevel
) {
    /**
     * Get color for UI display
     */
    val color: String
        get() = statusLevel.hexColor

    /**
     * Check if hype rating is critical
     */
    val isCritical: Boolean
        get() = statusLevel == HypeRatingLevel.CRITICAL

    /**
     * Check if hype rating is low
     */
    val isLow: Boolean
        get() = statusLevel == HypeRatingLevel.LOW || isCritical

    /**
     * Check if hype rating is healthy
     */
    val isHealthy: Boolean
        get() = statusLevel == HypeRatingLevel.HEALTHY || statusLevel == HypeRatingLevel.FULL

    companion object {
        /**
         * Create status for sufficient hype rating
         */
        fun canEngage(currentPercent: Double): HypeRatingStatus {
            val level = HypeRatingLevel.fromPercent(currentPercent)
            return HypeRatingStatus(
                canEngage = true,
                currentPercent = currentPercent,
                message = "Ready to engage",
                statusLevel = level
            )
        }

        /**
         * Create status for insufficient hype rating
         */
        fun cannotEngage(currentPercent: Double, requiredPercent: Double): HypeRatingStatus {
            val level = HypeRatingLevel.fromPercent(currentPercent)
            val shortfall = requiredPercent - currentPercent
            return HypeRatingStatus(
                canEngage = false,
                currentPercent = currentPercent,
                message = "Need ${String.format("%.1f", shortfall)}% more hype rating",
                statusLevel = level
            )
        }

        /**
         * Create critical status
         */
        fun critical(currentPercent: Double): HypeRatingStatus {
            return HypeRatingStatus(
                canEngage = false,
                currentPercent = currentPercent,
                message = "Hype rating critically low - wait for regeneration",
                statusLevel = HypeRatingLevel.CRITICAL
            )
        }

        /**
         * Create low warning status
         */
        fun lowWarning(currentPercent: Double): HypeRatingStatus {
            return HypeRatingStatus(
                canEngage = true,
                currentPercent = currentPercent,
                message = "Hype rating low - engagements limited",
                statusLevel = HypeRatingLevel.LOW
            )
        }
    }
}

/**
 * Hype rating level indicators
 */
enum class HypeRatingLevel(val threshold: Double, val hexColor: String) {
    CRITICAL(10.0, "#FF3B30"),    // Red - below 10%
    LOW(20.0, "#FF9500"),         // Orange - below 20%
    MODERATE(50.0, "#FFCC00"),    // Yellow - below 50%
    HEALTHY(80.0, "#34C759"),     // Green - below 80%
    FULL(100.0, "#30D158");       // Bright green - 80%+

    val displayName: String
        get() = when (this) {
            CRITICAL -> "Critical"
            LOW -> "Low"
            MODERATE -> "Moderate"
            HEALTHY -> "Healthy"
            FULL -> "Full"
        }

    companion object {
        /**
         * Determine level from current percentage
         */
        fun fromPercent(percent: Double): HypeRatingLevel {
            return when {
                percent < CRITICAL.threshold -> CRITICAL
                percent < LOW.threshold -> LOW
                percent < MODERATE.threshold -> MODERATE
                percent < HEALTHY.threshold -> HEALTHY
                else -> FULL
            }
        }
    }
}

/**
 * Hype rating cost calculation result
 */
data class HypeRatingCost(
    val costPercent: Double,
    val canAfford: Boolean,
    val remainingPercent: Double
) {
    /**
     * Check if this would put user in critical range
     */
    val wouldBeCritical: Boolean
        get() = remainingPercent < HypeRatingLevel.CRITICAL.threshold

    /**
     * Check if this would put user in low range
     */
    val wouldBeLow: Boolean
        get() = remainingPercent < HypeRatingLevel.LOW.threshold

    companion object {
        /**
         * Calculate cost for engagement
         */
        fun calculate(currentPercent: Double, costPercent: Double): HypeRatingCost {
            val remaining = currentPercent - costPercent
            return HypeRatingCost(
                costPercent = costPercent,
                canAfford = remaining >= 0.0,
                remainingPercent = maxOf(0.0, remaining)
            )
        }
    }
}