/*
 * VideoEngagementState.kt - PER-USER/VIDEO ENGAGEMENT STATE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * UPDATED to match iOS VideoEngagementState:
 * - EngagementSide enum (hype/cool/none)
 * - Grace period (60s) with side-switching
 * - Clout cap + engagement cap
 * - firstEngagementAt tracking
 * - totalCloutGiven alias
 * - addHypeEngagement / addCoolEngagement (instant, no tapping)
 * - recordCloutAwarded
 * - getRemainingCloutAllowance
 *
 * Retains original progressive tapping methods for backward compatibility.
 */

package com.stitchsocial.club.engagement

import com.stitchsocial.club.foundation.EngagementConfig
import com.stitchsocial.club.foundation.UserTier
import java.util.Date
import kotlin.math.min
import kotlin.math.pow

/** Which side the user has committed to on this video */
enum class EngagementSide {
    NONE,
    HYPE,
    COOL
}

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
    var cloutGivenToVideo: Int = 0,
    var lastEngagementAt: Date = Date(),
    val createdAt: Date = Date(),

    // --- NEW: iOS-matching fields ---
    var firstEngagementAt: Date? = null,
    var totalCloutGiven: Int = 0
) {

    companion object {
        const val EXPIRATION_TIME_MS = 24 * 60 * 60 * 1000L
        const val GRACE_PERIOD_SECONDS = 60L  // matches iOS 60-second grace period
        const val MAX_ENGAGEMENTS_PER_VIDEO = 100  // matches iOS engagement cap

        fun create(videoID: String, userID: String): VideoEngagementState {
            return VideoEngagementState(
                videoID = videoID,
                userID = userID
            )
        }
    }

    // =====================================================================
    // NEW: iOS-matching computed properties
    // =====================================================================

    /** Which side the user is on (matches iOS currentSide) */
    val currentSide: EngagementSide
        get() = when {
            hypeEngagements > 0 && coolEngagements == 0 -> EngagementSide.HYPE
            coolEngagements > 0 && hypeEngagements == 0 -> EngagementSide.COOL
            hypeEngagements > 0 && coolEngagements > 0 -> {
                // Both present after a grace-period switch; last action wins
                EngagementSide.HYPE // default to hype if ambiguous
            }
            else -> EngagementSide.NONE
        }

    /** Whether user is still within the 60-second grace period (matches iOS) */
    val isWithinGracePeriod: Boolean
        get() {
            val first = firstEngagementAt ?: return false
            val elapsed = (Date().time - first.time) / 1000L
            return elapsed <= GRACE_PERIOD_SECONDS
        }

    /** Check if clout cap reached for this tier (matches iOS hasHitCloutCap) */
    fun hasHitCloutCap(userTier: UserTier): Boolean {
        val maxClout = EngagementConfig.getMaxCloutPerUserPerVideo(userTier)
        return totalCloutGiven >= maxClout
    }

    /** Check if engagement cap reached (matches iOS hasHitEngagementCap) */
    fun hasHitEngagementCap(): Boolean {
        return totalEngagements >= MAX_ENGAGEMENTS_PER_VIDEO
    }

    /** Get remaining clout allowance (matches iOS getRemainingCloutAllowance) */
    fun getRemainingCloutAllowance(userTier: UserTier): Int {
        val maxClout = EngagementConfig.getMaxCloutPerUserPerVideo(userTier)
        return (maxClout - totalCloutGiven).coerceAtLeast(0)
    }

    // =====================================================================
    // NEW: iOS-matching instant engagement methods (no progressive tapping)
    // =====================================================================

    /** Instant hype engagement - matches iOS addHypeEngagement() */
    fun addHypeEngagement() {
        hypeEngagements++
        totalEngagements++
        lastEngagementAt = Date()
    }

    /** Instant cool engagement - matches iOS addCoolEngagement() */
    fun addCoolEngagement() {
        coolEngagements++
        totalEngagements++
        lastEngagementAt = Date()
    }

    /** Record clout awarded - matches iOS recordCloutAwarded() */
    fun recordCloutAwarded(amount: Int, isHype: Boolean) {
        totalCloutGiven += amount
        cloutGivenToVideo += amount  // keep legacy field in sync
    }

    // =====================================================================
    // ORIGINAL: State Queries (unchanged)
    // =====================================================================

    fun isExpired(): Boolean {
        return (Date().time - lastEngagementAt.time) > EXPIRATION_TIME_MS
    }

    fun isInInstantMode(): Boolean {
        return totalEngagements < EngagementConfig.INSTANT_ENGAGEMENT_THRESHOLD
    }

    fun isFirstEngagement(): Boolean {
        return hypeEngagements == 0
    }

    fun getCurrentTapNumber(): Int {
        return hypeEngagements + 1
    }

    // =====================================================================
    // ORIGINAL: Progressive Tapping - Hype (unchanged, backward compat)
    // =====================================================================

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
        totalCloutGiven += cloutAwarded  // keep new field in sync
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

    // =====================================================================
    // ORIGINAL: Progressive Tapping - Cool (unchanged, backward compat)
    // =====================================================================

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
        totalCloutGiven += cloutAwarded  // keep new field in sync
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

    // =====================================================================
    // ORIGINAL: Clout Tracking (unchanged)
    // =====================================================================

    fun addClout(amount: Int) {
        cloutGivenToVideo += amount
        totalCloutGiven += amount  // keep new field in sync
    }

    // =====================================================================
    // ORIGINAL: Private Helpers (unchanged)
    // =====================================================================

    private fun calculateNextRequirement(currentTotal: Int): Int {
        if (currentTotal < EngagementConfig.INSTANT_ENGAGEMENT_THRESHOLD) {
            return 1
        }

        val progressiveIndex = currentTotal - EngagementConfig.INSTANT_ENGAGEMENT_THRESHOLD
        val requirement = EngagementConfig.FIRST_PROGRESSIVE_TAPS *
                (2.0.pow(progressiveIndex.toDouble()).toInt())

        return min(requirement, EngagementConfig.MAX_TAP_REQUIREMENT)
    }

    // =====================================================================
    // ORIGINAL: Debug (unchanged)
    // =====================================================================

    fun debugDescription(): String {
        return """
            VideoEngagementState:
            - Video: $videoID
            - User: $userID
            - Total: $totalEngagements
            - Hype: $hypeEngagements ($hypeCurrentTaps/$hypeRequiredTaps)
            - Cool: $coolEngagements ($coolCurrentTaps/$coolRequiredTaps)
            - Clout Given: $cloutGivenToVideo
            - Total Clout Given: $totalCloutGiven
            - Current Side: $currentSide
            - Grace Period Active: $isWithinGracePeriod
            - Instant Mode: ${isInInstantMode()}
        """.trimIndent()
    }
}