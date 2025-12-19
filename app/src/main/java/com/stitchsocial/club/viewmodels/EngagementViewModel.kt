/*
 * EngagementViewModel.kt - ENGAGEMENT UI STATE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Connects iOS-style buttons to engagement coordinator
 * ✅ FIXED: Works with existing EngagementCalculator signatures
 */

package com.stitchsocial.club.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.engagement.*
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.EngagementConfig
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.UserService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LastEngagementFeedback(
    val cloutAwarded: Int,
    val visualHypeIncrement: Int,
    val animationType: EngagementAnimationType,
    val message: String,
    val isFounderFirstTap: Boolean
)

class EngagementViewModel(
    private val coordinator: EngagementCoordinator,
    private val videoService: VideoServiceImpl? = null,
    private val userService: UserService? = null
) : ViewModel() {

    constructor(
        authService: AuthService,
        videoService: VideoServiceImpl,
        userService: UserService
    ) : this(
        coordinator = EngagementCoordinator(
            videoService = videoService,
            userService = userService
        ),
        videoService = videoService,
        userService = userService
    )

    private val _tapProgress = MutableStateFlow(0.0)
    val tapProgress: StateFlow<Double> = _tapProgress.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentVideoState = MutableStateFlow<VideoEngagementState?>(null)
    val currentVideoState: StateFlow<VideoEngagementState?> = _currentVideoState.asStateFlow()

    private val _lastEngagementFeedback = MutableStateFlow<LastEngagementFeedback?>(null)
    val lastEngagementFeedback: StateFlow<LastEngagementFeedback?> = _lastEngagementFeedback.asStateFlow()

    private val _userHypeRating = MutableStateFlow(100.0)
    val userHypeRating: StateFlow<Double> = _userHypeRating.asStateFlow()

    private var currentUserID: String = "anonymous"

    fun setCurrentUser(userID: String) {
        currentUserID = userID
    }

    // ✅ FIXED: Use EngagementConfig directly for clout calculations
    val cloutGivenToVideo: Int
        get() = 0 // Placeholder - tracked in VideoEngagementState if needed

    fun remainingCloutAllowance(userTier: UserTier): Int {
        val max = EngagementConfig.getMaxCloutPerUserPerVideo(userTier)
        return max - cloutGivenToVideo
    }

    fun hasReachedCloutCap(userTier: UserTier): Boolean {
        val max = EngagementConfig.getMaxCloutPerUserPerVideo(userTier)
        return cloutGivenToVideo >= max
    }

    fun isInstantMode(videoID: String): Boolean {
        val state = coordinator.getEngagementState(videoID, currentUserID)
        return state?.isInInstantMode() ?: true
    }

    fun isFirstEngagement(videoID: String): Boolean {
        val state = coordinator.getEngagementState(videoID, currentUserID)
        return (state?.totalEngagements ?: 0) == 0
    }

    fun onHypeTap(videoID: String, userTier: UserTier) {
        viewModelScope.launch {
            _isProcessing.value = true

            try {
                val stateBefore = coordinator.getEngagementState(videoID, currentUserID)
                val isFirst = (stateBefore?.totalEngagements ?: 0) == 0
                val engagementNumber = (stateBefore?.totalEngagements ?: 0) + 1

                coordinator.processHype(
                    videoID = videoID,
                    userID = currentUserID,
                    userTier = userTier
                )

                // ✅ FIXED: Use existing calculateCloutReward signature
                val cloutAwarded = EngagementCalculator.calculateCloutReward(userTier, engagementNumber)

                // ✅ FIXED: Use existing calculateVisualHypeIncrement signature (single param)
                val isFounderFirstTap = isFirst && (userTier == UserTier.FOUNDER || userTier == UserTier.CO_FOUNDER)
                val visualIncrement = EngagementCalculator.calculateVisualHypeIncrement(userTier)

                val isPremiumBoost = isFirst && EngagementConfig.hasFirstTapBonus(userTier)

                val animationType = when {
                    isFounderFirstTap -> EngagementAnimationType.FOUNDER_EXPLOSION
                    isPremiumBoost -> EngagementAnimationType.PREMIUM_BOOST
                    else -> EngagementAnimationType.STANDARD_HYPE
                }

                _lastEngagementFeedback.value = LastEngagementFeedback(
                    cloutAwarded = cloutAwarded,
                    visualHypeIncrement = visualIncrement,
                    animationType = animationType,
                    message = if (cloutAwarded > 0) "+$cloutAwarded clout!" else "Hyped!",
                    isFounderFirstTap = isFounderFirstTap
                )

                _currentVideoState.value = coordinator.getEngagementState(videoID, currentUserID)
                _tapProgress.value = _currentVideoState.value?.getHypeProgress() ?: 0.0

            } catch (e: Exception) {
                println("Hype tap error: ${e.message}")
            }

            _isProcessing.value = false
        }
    }

    fun onCoolTap(videoID: String, userTier: UserTier) {
        viewModelScope.launch {
            _isProcessing.value = true

            try {
                coordinator.processCool(
                    videoID = videoID,
                    userID = currentUserID,
                    userTier = userTier
                )

                _lastEngagementFeedback.value = LastEngagementFeedback(
                    cloutAwarded = 0,
                    visualHypeIncrement = 0,
                    animationType = EngagementAnimationType.STANDARD_COOL,
                    message = "Cooled",
                    isFounderFirstTap = false
                )

                _currentVideoState.value = coordinator.getEngagementState(videoID, currentUserID)
                _tapProgress.value = _currentVideoState.value?.getCoolProgress() ?: 0.0

            } catch (e: Exception) {
                println("Cool tap error: ${e.message}")
            }

            _isProcessing.value = false
        }
    }

    fun loadVideoState(videoID: String) {
        val state = coordinator.getEngagementState(videoID, currentUserID)
        _currentVideoState.value = state
        _tapProgress.value = state?.getHypeProgress() ?: 0.0
    }

    fun trackView(videoID: String) {
        viewModelScope.launch {
            try {
                println("👁️ VIEW: Video $videoID viewed by user $currentUserID")

                // Get current user data for viewer record
                val userData = try {
                    userService?.getBasicUserInfo(currentUserID)
                } catch (e: Exception) {
                    null
                }

                val userDataMap = mapOf<String, Any>(
                    "displayName" to (userData?.displayName ?: "User"),
                    "username" to (userData?.displayName ?: ""), // Use displayName as fallback
                    "profileImageURL" to (userData?.profileImageURL ?: ""),
                    "tier" to (userData?.tier?.name ?: "ROOKIE")
                )

                // Record view with user data
                videoService?.recordVideoView(videoID, currentUserID, userDataMap)
            } catch (e: Exception) {
                println("❌ VIEW TRACKING ERROR: ${e.message}")
            }
        }
    }

    fun clearFeedback() {
        _lastEngagementFeedback.value = null
    }
}