/*
 * EngagementCoordinator.kt - HYBRID CLOUT SYSTEM
 * STITCH SOCIAL - ANDROID KOTLIN
 */

package com.stitchsocial.club.coordination
import com.stitchsocial.club.services.StreakService
import com.stitchsocial.club.services.StreakAction

import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.engagement.*
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.NotificationService
import com.stitchsocial.club.services.SocialSignalService
import com.stitchsocial.club.services.EngagementService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Date
import com.stitchsocial.club.BuildConfig

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

    fun getOrCreateState(videoID: String, userID: String): VideoEngagementState {
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

    // NOTIFICATION (via Cloud Functions - matches iOS)

    private suspend fun sendEngagementNotificationToCreator(
        recipientID: String,
        senderID: String,
        videoID: String,
        videoTitle: String,
        engagementType: String
    ) {
        try {
            if (recipientID == senderID) return

            // Cloud Function handles: username lookup, title/message, FCM push
            notificationService.sendEngagementNotification(
                recipientID = recipientID,
                videoID = videoID,
                engagementType = engagementType,
                videoTitle = videoTitle
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("NOTIFICATION: Error - ${e.message}") }
        }
    }

    // PROCESS HYPE

    suspend fun processHype(
        videoID: String,
        userID: String,
        userTier: UserTier,
        creatorID: String? = null
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

            // One call replaces the two writes that used to be here: the
            // counter write, which firestore.rules has refused for months, and
            // the clout payment, which used a tier this phone chose to pay a
            // creatorID this phone named. The server reads both from documents.
            //
            // The tap number, first-engagement flag and running clout total
            // that used to be captured here fed a local calculateCloutReward.
            // The server keeps that state per viewer and per video now, which
            // is the only place it can be trusted — this phone's copy resets
            // when the app does.
            val serverResult = EngagementService.process(
                videoID = videoID,
                engagementType = "hype",
                isBurst = false
            )
            if (serverResult == null) {
                state.resetHypeTaps()
                return@withContext false
            }

            val cloutAwarded = serverResult.cloutAwarded

            // A hype is a qualifying daily action -> feed the engagement streak.
            StreakService.shared.recordAction(StreakAction.HYPE)

            // Clout is paid by stitchnoti_processEngagement above, which also
            // advances the tier and gives accepted collaborators their third.
            // awardClout() used to be called here and is now unreachable.

            // Notification (Cloud Function handles username)
            sendEngagementNotificationToCreator(
                recipientID = video.creatorID,
                senderID = userID,
                videoID = videoID,
                videoTitle = video.title,
                engagementType = "hype"
            )

            // 📢 MEGAPHONE: Record notable engagement if Partner+ tier
            val hypeWeight = EngagementConfig.getVisualHypeMultiplier(userTier)
            CoroutineScope(Dispatchers.IO).launch {
                SocialSignalService.shared.recordNotableEngagement(
                    engagerID = userID,
                    engagerName = "", // Cloud Function fetches display name
                    engagerTier = userTier.name.lowercase(),
                    engagerProfileImageURL = null,
                    videoID = videoID,
                    videoCreatorID = video.creatorID,
                    hypeWeight = hypeWeight,
                    cloutAwarded = cloutAwarded
                )
            }

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
        userTier: UserTier,
        creatorID: String? = null
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

            // Same as hype: the server owns the counter and the clout penalty.
            if (EngagementService.process(videoID, engagementType = "cool") == null) {
                state.resetCoolTaps()
                return@withContext false
            }

            // Notification (Cloud Function handles username)
            sendEngagementNotificationToCreator(
                recipientID = video.creatorID,
                senderID = userID,
                videoID = videoID,
                videoTitle = video.title,
                engagementType = "cool"
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