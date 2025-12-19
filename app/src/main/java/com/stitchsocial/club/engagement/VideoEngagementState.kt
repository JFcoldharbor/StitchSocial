/*
 * VideoEngagementState.kt - PER-USER/VIDEO ENGAGEMENT STATE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Tracks progressive tapping state for iOS-style engagement
 */

package com.stitchsocial.club.engagement

import com.stitchsocial.club.foundation.EngagementConfig
import java.util.Date
import kotlin.math.min
import kotlin.math.pow

data class VideoEngagementState(
    val videoID: String,
    val userID: String,
    var totalEngagements: Int = 0,
    var hypeEngagements: Int = 0,
    var coolEngagements: Int = 0,
    var hypeCurrentTaps: Int = 0,
    var hypeRequiredTaps: Int = 1,
    var coolCurrentTaps: Int = 0,
    var coolRequiredTaps: Int = 1,
    var cloutGivenToVideo: Int = 0,          // Track clout given by this user
    var lastEngagementAt: Date = Date(),
    val createdAt: Date = Date()
) {

    companion object {
        const val EXPIRATION_TIME_MS = 24 * 60 * 60 * 1000L

        fun create(videoID: String, userID: String): VideoEngagementState {
            return VideoEngagementState(
                videoID = videoID,
                userID = userID,
                totalEngagements = 0,
                hypeEngagements = 0,
                coolEngagements = 0,
                hypeCurrentTaps = 0,
                hypeRequiredTaps = 1,
                coolCurrentTaps = 0,
                coolRequiredTaps = 1,
                cloutGivenToVideo = 0,
                lastEngagementAt = Date(),
                createdAt = Date()
            )
        }
    }

    // MARK: - State Queries

    fun isExpired(): Boolean {
        return (Date().time - lastEngagementAt.time) > EXPIRATION_TIME_MS
    }

    fun isInInstantMode(): Boolean {
        return totalEngagements < EngagementConfig.INSTANT_ENGAGEMENT_THRESHOLD
    }

    /**
     * Check if this is the user's first hype engagement on this video
     */
    fun isFirstEngagement(): Boolean {
        return hypeEngagements == 0
    }

    /**
     * Get current tap number (for clout calculation)
     */
    fun getCurrentTapNumber(): Int {
        return hypeEngagements + 1
    }

    // MARK: - Hype Processing

    fun addHypeTap(): Boolean {
        hypeCurrentTaps++
        lastEngagementAt = Date()
        return hypeCurrentTaps >= hypeRequiredTaps
    }

    fun completeHypeEngagement(cloutAwarded: Int = 0) {
        hypeEngagements++
        totalEngagements++
        hypeCurrentTaps = 0
        hypeRequiredTaps = calculateNextRequirement(totalEngagements)
        cloutGivenToVideo += cloutAwarded
        lastEngagementAt = Date()
    }

    fun resetHypeTaps() {
        hypeCurrentTaps = 0
        lastEngagementAt = Date()
    }

    fun getHypeProgress(): Double {
        if (hypeRequiredTaps <= 0) return 0.0
        return min(1.0, hypeCurrentTaps.toDouble() / hypeRequiredTaps.toDouble())
    }

    fun getHypeTapsRemaining(): Int {
        return (hypeRequiredTaps - hypeCurrentTaps).coerceAtLeast(0)
    }

    // MARK: - Cool Processing

    fun addCoolTap(): Boolean {
        coolCurrentTaps++
        lastEngagementAt = Date()
        return coolCurrentTaps >= coolRequiredTaps
    }

    fun completeCoolEngagement(cloutAwarded: Int = 0) {
        coolEngagements++
        totalEngagements++
        coolCurrentTaps = 0
        coolRequiredTaps = calculateNextRequirement(totalEngagements)
        cloutGivenToVideo += cloutAwarded
        lastEngagementAt = Date()
    }

    fun resetCoolTaps() {
        coolCurrentTaps = 0
        lastEngagementAt = Date()
    }

    fun getCoolProgress(): Double {
        if (coolRequiredTaps <= 0) return 0.0
        return min(1.0, coolCurrentTaps.toDouble() / coolRequiredTaps.toDouble())
    }

    fun getCoolTapsRemaining(): Int {
        return (coolRequiredTaps - coolCurrentTaps).coerceAtLeast(0)
    }

    // MARK: - Clout Tracking

    fun addClout(amount: Int) {
        cloutGivenToVideo += amount
    }

    // MARK: - Private Helpers

    private fun calculateNextRequirement(currentTotal: Int): Int {
        if (currentTotal < EngagementConfig.INSTANT_ENGAGEMENT_THRESHOLD) {
            return 1
        }

        val progressiveIndex = currentTotal - EngagementConfig.INSTANT_ENGAGEMENT_THRESHOLD
        val requirement = EngagementConfig.FIRST_PROGRESSIVE_TAPS *
                (2.0.pow(progressiveIndex.toDouble()).toInt())

        return min(requirement, EngagementConfig.MAX_TAP_REQUIREMENT)
    }

    // MARK: - Debug

    fun debugDescription(): String {
        return """
            VideoEngagementState:
            - Video: $videoID
            - User: $userID
            - Total: $totalEngagements
            - Hype: $hypeEngagements ($hypeCurrentTaps/$hypeRequiredTaps)
            - Cool: $coolEngagements ($coolCurrentTaps/$coolRequiredTaps)
            - Clout Given: $cloutGivenToVideo
            - Instant Mode: ${isInInstantMode()}
        """.trimIndent()
    }
}