/*
 * ProfileViewModel.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 7: ViewModels - Profile State Management
 * Dependencies: UserService, Foundation
 * Features: Profile loading, social actions, tier progression
 *
 * ✅ UPDATED: Added AMBASSADOR tier support
 * ✅ CLEANED: Removed debug print statements
 */

package com.stitchsocial.club.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.services.UserService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

/**
 * ProfileViewModel - Manages user profile state and actions
 */
class ProfileViewModel(
    private val context: Context? = null
) : ViewModel() {

    // Services
    private val userService: UserService? = context?.let {
        try {
            UserService(it)
        } catch (e: Exception) {
            null
        }
    }

    // ===== UI STATE =====

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _user = MutableStateFlow<BasicUserInfo?>(null)
    val user: StateFlow<BasicUserInfo?> = _user.asStateFlow()

    private val _userVideos = MutableStateFlow<List<BasicVideoInfo>>(emptyList())
    val userVideos: StateFlow<List<BasicVideoInfo>> = _userVideos.asStateFlow()

    private val _followers = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val followers: StateFlow<List<BasicUserInfo>> = _followers.asStateFlow()

    private val _following = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val following: StateFlow<List<BasicUserInfo>> = _following.asStateFlow()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ===== PROFILE LOADING =====

    /**
     * Load user profile
     */
    fun loadUserProfile(userId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                if (userId.isBlank()) {
                    _errorMessage.value = "Invalid user ID"
                    return@launch
                }

                if (userService == null) {
                    _errorMessage.value = "Service not initialized"
                    return@launch
                }

                val userProfile = userService.getUserProfile(userId)
                _user.value = userProfile

                if (userProfile != null) {
                    loadAdditionalProfileData(userId)
                } else {
                    _errorMessage.value = "Profile not found"
                }

            } catch (e: Exception) {
                _errorMessage.value = "Failed to load profile: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load additional profile data (following, followers)
     */
    private suspend fun loadAdditionalProfileData(userId: String) {
        try {
            if (userService != null) {
                val followingList = userService.getFollowingList(userId, limit = 50)
                val followersList = userService.getFollowersList(userId, limit = 50)
                _following.value = followingList
                _followers.value = followersList
            }
        } catch (e: Exception) {
            // Don't update error state for additional data failures
        }
    }

    // ===== SOCIAL ACTIONS =====

    /**
     * Check if current user is following the profile user
     */
    fun checkFollowingStatus(currentUserId: String, profileUserId: String) {
        viewModelScope.launch {
            try {
                val following = userService?.isFollowing(currentUserId, profileUserId) ?: false
                _isFollowing.value = following
            } catch (e: Exception) {
                // Silent failure for status check
            }
        }
    }

    /**
     * Follow user
     */
    fun followUser(currentUserId: String, profileUserId: String) {
        viewModelScope.launch {
            try {
                val success = userService?.followUser(currentUserId, profileUserId) ?: false
                if (success) {
                    _isFollowing.value = true
                    loadUserProfile(profileUserId)
                } else {
                    _errorMessage.value = "Failed to follow user"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to follow user"
            }
        }
    }

    /**
     * Unfollow user
     */
    fun unfollowUser(currentUserId: String, profileUserId: String) {
        viewModelScope.launch {
            try {
                val success = userService?.unfollowUser(currentUserId, profileUserId) ?: false
                if (success) {
                    _isFollowing.value = false
                    loadUserProfile(profileUserId)
                } else {
                    _errorMessage.value = "Failed to unfollow user"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to unfollow user"
            }
        }
    }

    // ===== PROFILE EDITING =====

    /**
     * Update user profile
     */
    fun updateProfile(
        userId: String,
        displayName: String?,
        bio: String?,
        username: String?,
        isPrivate: Boolean?
    ) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                val updatedUser = userService?.updateProfile(
                    userID = userId,
                    displayName = displayName,
                    bio = bio,
                    username = username,
                    isPrivate = isPrivate
                )

                if (updatedUser != null) {
                    _user.value = updatedUser
                } else {
                    _errorMessage.value = "Failed to update profile"
                }

            } catch (e: Exception) {
                _errorMessage.value = "Error updating profile: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Check username availability
     */
    fun checkUsernameAvailability(
        username: String,
        currentUserId: String?,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val available = userService?.checkUsernameAvailability(
                    username,
                    currentUserId ?: ""
                ) ?: false
                onResult(available)
            } catch (e: Exception) {
                onResult(false)
            }
        }
    }

    // ===== UTILITY METHODS =====

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }

    // ===== TIER PROGRESSION =====

    /**
     * Get tier progress information
     */
    fun getTierProgress(user: BasicUserInfo): TierProgress {
        val currentTier = user.tier
        val currentClout = user.clout

        // Find next achievable tier
        val nextTier = UserTier.values()
            .filter { it.isAchievableTier }
            .firstOrNull { tier ->
                tier.cloutRange.first > currentClout
            }

        val cloutNeeded = nextTier?.cloutRange?.first?.minus(currentClout) ?: 0

        val progressPercentage = if (nextTier != null) {
            val tierStart = currentTier.cloutRange.first
            val tierEnd = nextTier.cloutRange.first
            val progress = currentClout - tierStart
            val range = tierEnd - tierStart
            if (range > 0) (progress.toDouble() / range.toDouble()) * 100.0 else 100.0
        } else 100.0

        return TierProgress(
            currentTier = currentTier,
            currentClout = currentClout,
            nextTier = nextTier,
            cloutNeeded = cloutNeeded,
            progressPercentage = progressPercentage
        )
    }

    /**
     * Get tier badge info for UI display
     * ✅ UPDATED: Added AMBASSADOR tier
     */
    fun getTierBadgeInfo(tier: UserTier): TierBadgeInfo {
        return when (tier) {
            UserTier.FOUNDER -> TierBadgeInfo("👑", "Founder", "#FFD700")
            UserTier.CO_FOUNDER -> TierBadgeInfo("👑", "Co-Founder", "#C0C0C0")
            UserTier.TOP_CREATOR -> TierBadgeInfo("🔥", "Top Creator", "#FF4500")
            UserTier.LEGENDARY -> TierBadgeInfo("🏆", "Legendary", "#FF6B6B")
            UserTier.PARTNER -> TierBadgeInfo("🤝", "Partner", "#45B7D1")
            UserTier.ELITE -> TierBadgeInfo("⭐", "Elite", "#4ECDC4")
            UserTier.AMBASSADOR -> TierBadgeInfo("🌟", "Ambassador", "#9B59B6")
            UserTier.INFLUENCER -> TierBadgeInfo("📈", "Influencer", "#96CEB4")
            UserTier.VETERAN -> TierBadgeInfo("🛡️", "Veteran", "#FFEAA7")
            UserTier.RISING -> TierBadgeInfo("🚀", "Rising", "#DDA0DD")
            UserTier.ROOKIE -> TierBadgeInfo("🌱", "Rookie", "#98D8C8")
            UserTier.BUSINESS -> TierBadgeInfo("🏢", "Business", "#00BCD4")
        }
    }

    // ===== DATA CLASSES =====

    data class TierProgress(
        val currentTier: UserTier,
        val currentClout: Int,
        val nextTier: UserTier?,
        val cloutNeeded: Int,
        val progressPercentage: Double
    )

    data class TierBadgeInfo(
        val emoji: String,
        val displayName: String,
        val colorHex: String
    )

    override fun onCleared() {
        super.onCleared()
    }
}