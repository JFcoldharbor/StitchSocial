/*
 * EngagementCoordinator.kt - HYBRID CLOUT SYSTEM
 * STITCH SOCIAL - ANDROID KOTLIN
 */

package com.stitchsocial.club.coordination

import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.engagement.*
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.NotificationService
import com.stitchsocial.club.services.StitchNotificationType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Date

class EngagementCoordinator(
    private val videoService: VideoServiceImpl,
    private val userService: UserService,
    private val notificationService: NotificationService = NotificationService()
) {
    private val engagementStates = mutableMapOf<String, VideoEngagementState>()

    private val _userHypeRating = MutableStateFlow(25.0)
    val userHypeRating: StateFlow<Double> = _userHypeRating.asStateFlow()

    private val lastEngagementTimes = mutableMapOf<String, Long>()
    private val processingLocks = mutableMapOf<String, Boolean>()

    // STATE MANAGEMENT

    private fun getOrCreateState(videoID: String, userID: String): VideoEngagementState {
        val key = "$videoID:$userID"
        val existing = engagementStates[key]
        if (existing != null && !existing.isExpired()) {
            return existing
        }
        val newState = VideoEngagementState.create(videoID, userID)
        engagementStates[key] = newState
        return newState
    }

    fun getEngagementState(videoID: String, userID: String): VideoEngagementState? {
        val key = "$videoID:$userID"
        return engagementStates[key]?.takeIf { !it.isExpired() }
    }

    // HYPE RATING

    suspend fun loadUserHypeRating(userID: String) {
        _userHypeRating.value = 25.0
    }

    fun getHypeRatingStatus(userTier: UserTier): HypeRatingStatus {
        val currentPercent = _userHypeRating.value
        val requiredPercent = EngagementCalculator.calculateHypeRatingCost(userTier)
        return EngagementCalculator.calculateHypeRatingStatus(currentPercent, requiredPercent)
    }

    private fun deductHypeRating(cost: Double) {
        val current = _userHypeRating.value
        _userHypeRating.value = EngagementCalculator.applyHypeRatingCost(current, cost)
    }

    // RATE LIMITING

    private fun canEngageNow(videoID: String): Boolean {
        val lastTime = lastEngagementTimes[videoID] ?: 0L
        return EngagementCalculator.canEngageNow(lastTime, System.currentTimeMillis())
    }

    private fun recordEngagementTime(videoID: String) {
        lastEngagementTimes[videoID] = System.currentTimeMillis()
    }

    // NOTIFICATION

    private suspend fun sendEngagementNotification(
        recipientID: String,
        senderID: String,
        videoID: String,
        engagementType: String,
        senderUsername: String,
        cloutAwarded: Int,
        isFounderFirstTap: Boolean,
        isPremiumBoost: Boolean = false
    ) {
        try {
            if (recipientID == senderID) return

            val type = if (engagementType == "hype") StitchNotificationType.HYPE else StitchNotificationType.COOL

            val title = when {
                isFounderFirstTap -> "🎉 FOUNDER BOOST! $senderUsername hyped your video!"
                isPremiumBoost -> "⚡ PREMIUM BOOST! $senderUsername hyped your video!"
                engagementType == "hype" -> "$senderUsername hyped your video"
                else -> "$senderUsername cooled your video"
            }

            val message = when {
                cloutAwarded > 0 -> "+$cloutAwarded clout awarded"
                else -> "New ${if (engagementType == "hype") "hype" else "cool"} on your video"
            }

            notificationService.createNotification(
                type = type,
                title = title,
                message = message,
                senderID = senderID,
                recipientID = recipientID,
                payload = mapOf("videoID" to videoID, "cloutAwarded" to cloutAwarded.toString())
            )
        } catch (e: Exception) {
            println("❌ NOTIFICATION: Error - ${e.message}")
        }
    }

    // PROCESS HYPE

    suspend fun processHype(
        videoID: String,
        userID: String,
        userTier: UserTier
    ): Boolean = withContext(Dispatchers.Default) {

        val lockKey = "$videoID:$userID:hype"
        if (processingLocks[lockKey] == true) return@withContext false
        processingLocks[lockKey] = true

        try {
            if (!canEngageNow(videoID)) return@withContext false

            val hypeCost = EngagementCalculator.calculateHypeRatingCost(userTier)
            if (!EngagementCalculator.canAffordEngagement(_userHypeRating.value, hypeCost)) {
                return@withContext false
            }

            val state = getOrCreateState(videoID, userID)
            val isComplete = state.addHypeTap()

            if (!isComplete) return@withContext false

            // ENGAGEMENT COMPLETE
            deductHypeRating(hypeCost)

            val video = videoService.getVideoById(videoID)
            if (video == null) {
                state.resetHypeTaps()
                return@withContext false
            }

            // Capture state BEFORE completion
            val isFirstEngagement = state.isFirstEngagement()
            val tapNumber = state.getCurrentTapNumber()
            val currentCloutFromUser = state.cloutGivenToVideo

            // Calculate clout
            val cloutAwarded = EngagementCalculator.calculateCloutReward(
                userTier = userTier,
                tapNumber = tapNumber,
                isFirstEngagement = isFirstEngagement,
                currentCloutFromUser = currentCloutFromUser
            )

            val isFounderFirstTap = isFirstEngagement &&
                    (userTier == UserTier.FOUNDER || userTier == UserTier.CO_FOUNDER)
            val isPremiumBoost = isFirstEngagement &&
                    EngagementCalculator.hasFirstTapBonus(userTier) && !isFounderFirstTap

            // Update Firebase
            try {
                videoService.updateEngagementCounts(videoID, video.hypeCount + 1, video.coolCount)
            } catch (e: Exception) {
                state.resetHypeTaps()
                return@withContext false
            }

            // Award clout
            if (cloutAwarded > 0) {
                try {
                    userService.awardClout(video.creatorID, cloutAwarded)
                } catch (e: Exception) { }
            }

            // Notification
            val senderUsername = try {
                userService.getBasicUserInfo(userID)?.username ?: "Someone"
            } catch (e: Exception) { "Someone" }

            sendEngagementNotification(
                recipientID = video.creatorID,
                senderID = userID,
                videoID = videoID,
                engagementType = "hype",
                senderUsername = senderUsername,
                cloutAwarded = cloutAwarded,
                isFounderFirstTap = isFounderFirstTap,
                isPremiumBoost = isPremiumBoost
            )

            // Complete state WITH clout
            state.completeHypeEngagement(cloutAwarded)
            recordEngagementTime(videoID)

            return@withContext true

        } catch (e: Exception) {
            return@withContext false
        } finally {
            processingLocks.remove(lockKey)
        }
    }

    // PROCESS COOL

    suspend fun processCool(
        videoID: String,
        userID: String,
        userTier: UserTier
    ): Boolean = withContext(Dispatchers.Default) {

        val lockKey = "$videoID:$userID:cool"
        if (processingLocks[lockKey] == true) return@withContext false
        processingLocks[lockKey] = true

        try {
            if (!canEngageNow(videoID)) return@withContext false

            val state = getOrCreateState(videoID, userID)
            val isComplete = state.addCoolTap()

            if (!isComplete) return@withContext false

            val video = videoService.getVideoById(videoID)
            if (video == null) {
                state.resetCoolTaps()
                return@withContext false
            }

            try {
                videoService.updateEngagementCounts(videoID, video.hypeCount, video.coolCount + 1)
            } catch (e: Exception) {
                state.resetCoolTaps()
                return@withContext false
            }

            val senderUsername = try {
                userService.getBasicUserInfo(userID)?.username ?: "Someone"
            } catch (e: Exception) { "Someone" }

            sendEngagementNotification(
                recipientID = video.creatorID,
                senderID = userID,
                videoID = videoID,
                engagementType = "cool",
                senderUsername = senderUsername,
                cloutAwarded = 0,
                isFounderFirstTap = false
            )

            state.completeCoolEngagement()
            recordEngagementTime(videoID)

            return@withContext true

        } catch (e: Exception) {
            return@withContext false
        } finally {
            processingLocks.remove(lockKey)
        }
    }

    // CLEANUP

    fun cleanupExpiredStates() {
        engagementStates.filter { it.value.isExpired() }.keys.forEach { engagementStates.remove(it) }
    }

    fun resetState(videoID: String, userID: String) {
        engagementStates.remove("$videoID:$userID")
    }
}