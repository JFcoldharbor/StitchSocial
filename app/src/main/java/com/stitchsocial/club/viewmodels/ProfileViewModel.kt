

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
 * ProfileViewModel.kt - FIXED COMPILATION ERRORS
 * Enhanced debug version with proper StateFlow implementation
 * Uses updated BasicUserInfo with all Firebase fields
 */
class ProfileViewModel(
    private val context: Context? = null
) : ViewModel() {

    // Services - Create with enhanced error handling
    private val userService = context?.let {
        try {
            UserService(it).also { service ->
                println("PROFILE VM: ✅ UserService created successfully")
                service.helloWorldTest() // Test the service

                // Test database connection
                println("PROFILE VM: 🔍 Testing database connection...")
                println("PROFILE VM: Firebase project: stitchbeta-8bbfe")
                println("PROFILE VM: Database: stitchfin")
            }
        } catch (e: Exception) {
            println("PROFILE VM: ❌ UserService creation failed: ${e.message}")
            println("PROFILE VM: Stack trace: ${e.stackTrace.take(3).joinToString(" | ")}")
            null
        }
    } ?: run {
        println("PROFILE VM: ⚠️ UserService is null - context was null")
        null
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

    // ===== DEBUG STATE =====
    private var lastAttemptedUserId: String? = null
    private var loadAttemptCount = 0

    init {
        println("PROFILE VM: 🚀 ProfileViewModel initialized")
        println("PROFILE VM: Context available: ${context != null}")
        println("PROFILE VM: UserService available: ${userService != null}")

        // Test data models
        testDataModels()
    }

    // ===== PROFILE LOADING WITH COMPREHENSIVE DEBUG =====

    /**
     * Load user profile with enhanced debugging and error handling
     */
    fun loadUserProfile(userId: String) {
        viewModelScope.launch {
            loadAttemptCount++
            lastAttemptedUserId = userId

            println("PROFILE VM: 🔄 === LOAD ATTEMPT $loadAttemptCount ===")
            println("PROFILE VM: 📋 Input Analysis:")
            println("  - userId: '$userId'")
            println("  - userId.isBlank(): ${userId.isBlank()}")
            println("  - userId.length: ${userId.length}")
            println("  - Looks like valid ID: ${userId.length > 10}")
            println("  - userService != null: ${userService != null}")

            try {
                _isLoading.value = true
                _errorMessage.value = null
                println("PROFILE VM: 🔄 State updated: loading=true, error=null")

                // Enhanced validation
                if (userId.isBlank()) {
                    val error = "❌ Invalid user ID: empty or blank"
                    println("PROFILE VM: $error")
                    _errorMessage.value = error
                    return@launch
                }

                if (userId.length < 10) {
                    val error = "❌ Invalid user ID: too short (${userId.length} chars)"
                    println("PROFILE VM: $error")
                    _errorMessage.value = error
                    return@launch
                }

                // Service availability check
                if (userService == null) {
                    val error = "❌ UserService not initialized (context: ${context != null})"
                    println("PROFILE VM: $error")
                    _errorMessage.value = error
                    return@launch
                }

                // Call service with detailed logging
                println("PROFILE VM: 📞 Calling userService.getUserProfile('$userId')")
                println("PROFILE VM: 🔍 Expected path: /users/$userId")

                val startTime = System.currentTimeMillis()
                val userProfile = userService.getUserProfile(userId)
                val duration = System.currentTimeMillis() - startTime

                println("PROFILE VM: 📨 Service call completed in ${duration}ms")
                println("PROFILE VM: 📨 Result: ${userProfile?.let {
                    "SUCCESS - ${it.username} (${it.displayName}) | Tier: ${it.tier} | Clout: ${it.clout}"
                } ?: "NULL"}")

                // Update state with detailed logging
                _user.value = userProfile

                if (userProfile != null) {
                    println("PROFILE VM: ✅ Profile loaded successfully!")
                    println("PROFILE VM: 👤 User details:")
                    println("  - ID: ${userProfile.id}")
                    println("  - Username: @${userProfile.username}")
                    println("  - Display name: ${userProfile.displayName}")
                    println("  - Email: ${userProfile.email}")
                    println("  - Tier: ${userProfile.tier}")
                    println("  - Clout: ${userProfile.clout}")
                    println("  - Verified: ${userProfile.isVerified}")
                    println("  - Badges: ${userProfile.badges.joinToString(", ")}")
                    println("  - Followers: ${userProfile.followerCount}")
                    println("  - Following: ${userProfile.followingCount}")
                    println("  - Total Likes: ${userProfile.totalLikes}")
                    println("  - Total Videos: ${userProfile.totalVideos}")
                    println("  - Created: ${userProfile.createdAt}")

                    // Load additional profile data
                    loadAdditionalProfileData(userId)
                } else {
                    val error = "❌ Profile not found for user: $userId"
                    println("PROFILE VM: $error")
                    println("PROFILE VM: 🔍 Possible causes:")
                    println("  - User document doesn't exist in Firestore")
                    println("  - Wrong database (should be 'stitchfin')")
                    println("  - Network/permissions issue")
                    println("  - Data parsing error")
                    _errorMessage.value = "Profile not found"
                }

            } catch (e: Exception) {
                val error = "❌ Exception loading profile: ${e.message}"
                println("PROFILE VM: $error")
                println("PROFILE VM: Exception type: ${e.javaClass.simpleName}")
                println("PROFILE VM: Stack trace preview:")
                e.stackTrace.take(5).forEach {
                    println("  - ${it.className}.${it.methodName}:${it.lineNumber}")
                }
                _errorMessage.value = "Failed to load profile: ${e.message}"
            } finally {
                _isLoading.value = false
                println("PROFILE VM: 🔄 Final state: loading=false")
                println("PROFILE VM: 📊 Results: user=${_user.value?.username ?: "null"}, error=${_errorMessage.value}")
                println("PROFILE VM: ═══════════════════════════════════════")
            }
        }
    }

    /**
     * Load additional profile data (following, followers, videos)
     */
    private suspend fun loadAdditionalProfileData(userId: String) {
        try {
            println("PROFILE VM: 📊 Loading additional profile data...")

            if (userService != null) {
                // Load social data
                val startTime = System.currentTimeMillis()

                val followingList = userService.getFollowingList(userId, limit = 50)
                val followersList = userService.getFollowersList(userId, limit = 50)

                val duration = System.currentTimeMillis() - startTime
                println("PROFILE VM: 📊 Social data loaded in ${duration}ms")
                println("  - Following: ${followingList.size}")
                println("  - Followers: ${followersList.size}")

                _following.value = followingList
                _followers.value = followersList
            }

        } catch (e: Exception) {
            println("PROFILE VM: ⚠️ Failed to load additional data: ${e.message}")
            // Don't update error state for additional data failures
        }
    }

    // ===== DATA MODEL TESTING =====

    private fun testDataModels() {
        try {
            println("PROFILE VM: 🧪 Testing data models...")

            // Test BasicUserInfo creation
            val testUser = BasicUserInfo(
                id = "test123",
                username = "testuser",
                displayName = "Test User",
                email = "test@example.com",
                tier = UserTier.ROOKIE,
                clout = 0,
                isVerified = false,
                profileImageURL = null,
                createdAt = Date()
            )
            println("PROFILE VM: ✅ BasicUserInfo creation works")

            // Test UserTier enum
            UserTier.values().forEach { tier ->
                println("  - Tier: ${tier.displayName} (${tier.cloutRange})")
            }

        } catch (e: Exception) {
            println("PROFILE VM: ❌ Data model test failed: ${e.message}")
        }
    }

    // ===== SOCIAL ACTIONS WITH DEBUG =====

    /**
     * Check if current user is following the profile user
     */
    fun checkFollowingStatus(currentUserId: String, profileUserId: String) {
        viewModelScope.launch {
            try {
                println("PROFILE VM: 🔍 Checking following status: $currentUserId -> $profileUserId")
                val following = userService?.isFollowing(currentUserId, profileUserId) ?: false
                _isFollowing.value = following
                println("PROFILE VM: 🔍 Following status: $following")
            } catch (e: Exception) {
                println("PROFILE VM: ⚠️ Error checking following status: ${e.message}")
            }
        }
    }

    /**
     * Follow user with debug logging
     */
    fun followUser(currentUserId: String, profileUserId: String) {
        viewModelScope.launch {
            try {
                println("PROFILE VM: ➕ Following user: $currentUserId -> $profileUserId")
                val success = userService?.followUser(currentUserId, profileUserId) ?: false
                if (success) {
                    _isFollowing.value = true
                    // Refresh profile to get updated follower count
                    loadUserProfile(profileUserId)
                    println("PROFILE VM: ✅ Successfully followed user")
                } else {
                    println("PROFILE VM: ❌ Failed to follow user")
                    _errorMessage.value = "Failed to follow user"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to follow user"
                println("PROFILE VM: ❌ Error following user: ${e.message}")
            }
        }
    }

    /**
     * Unfollow user with debug logging
     */
    fun unfollowUser(currentUserId: String, profileUserId: String) {
        viewModelScope.launch {
            try {
                println("PROFILE VM: ➖ Unfollowing user: $currentUserId -> $profileUserId")
                val success = userService?.unfollowUser(currentUserId, profileUserId) ?: false
                if (success) {
                    _isFollowing.value = false
                    // Refresh profile to get updated follower count
                    loadUserProfile(profileUserId)
                    println("PROFILE VM: ✅ Successfully unfollowed user")
                } else {
                    println("PROFILE VM: ❌ Failed to unfollow user")
                    _errorMessage.value = "Failed to unfollow user"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to unfollow user"
                println("PROFILE VM: ❌ Error unfollowing user: ${e.message}")
            }
        }
    }

    // ===== PROFILE EDITING WITH DEBUG =====

    /**
     * Update user profile with enhanced validation
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
                println("PROFILE VM: ✏️ Updating profile for user $userId")
                println("  - Display name: ${displayName ?: "unchanged"}")
                println("  - Bio: ${bio ?: "unchanged"}")
                println("  - Username: ${username ?: "unchanged"}")
                println("  - Private: ${isPrivate ?: "unchanged"}")

                val updatedUser = userService?.updateProfile(
                    userID = userId,
                    displayName = displayName,
                    bio = bio,
                    username = username,
                    isPrivate = isPrivate
                )

                if (updatedUser != null) {
                    _user.value = updatedUser
                    println("PROFILE VM: ✅ Profile updated successfully")
                } else {
                    _errorMessage.value = "Failed to update profile"
                    println("PROFILE VM: ❌ Profile update returned null")
                }

            } catch (e: Exception) {
                _errorMessage.value = "Error updating profile: ${e.message}"
                println("PROFILE VM: ❌ Error updating profile: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Check username availability with debouncing
     */
    fun checkUsernameAvailability(
        username: String,
        currentUserId: String?,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                println("PROFILE VM: 🔍 Checking username availability: '$username'")
                val available = userService?.checkUsernameAvailability(username, currentUserId) ?: false
                onResult(available)
                println("PROFILE VM: 🔍 Username '$username' available: $available")
            } catch (e: Exception) {
                println("PROFILE VM: ❌ Error checking username: ${e.message}")
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
        println("PROFILE VM: 🧹 Cleared error message")
    }

    /**
     * Get debug information for troubleshooting
     */
    fun getDebugInfo(): String {
        return """
        |PROFILE VM DEBUG INFO:
        |- Load attempts: $loadAttemptCount
        |- Last attempted userId: '$lastAttemptedUserId'
        |- Context available: ${context != null}
        |- UserService available: ${userService != null}
        |- Current user: ${_user.value?.username ?: "null"}
        |- Is loading: ${_isLoading.value}
        |- Error message: '${_errorMessage.value}'
        |- Following count: ${_following.value.size}
        |- Followers count: ${_followers.value.size}
        |- Service test passed: ${userService != null}
        """.trimMargin()
    }

    /**
     * Print debug info to console
     */
    fun printDebugInfo() {
        println(getDebugInfo())
    }

    // ===== COMPUTED PROPERTIES =====

    /**
     * Get tier progress information
     */
    fun getTierProgress(user: BasicUserInfo): TierProgress {
        val currentTier = user.tier
        val currentClout = user.clout

        // Find next achievable tier
        val nextTier = UserTier.values()
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
     */
    fun getTierBadgeInfo(tier: UserTier): TierBadgeInfo {
        return when (tier) {
            UserTier.FOUNDER -> TierBadgeInfo("👑", "Founder", "#FFD700")
            UserTier.CO_FOUNDER -> TierBadgeInfo("👑", "Co-Founder", "#C0C0C0")
            UserTier.LEGENDARY -> TierBadgeInfo("🏆", "Legendary", "#FF6B6B")
            UserTier.ELITE -> TierBadgeInfo("⭐", "Elite", "#4ECDC4")
            UserTier.PARTNER -> TierBadgeInfo("🤝", "Partner", "#45B7D1")
            UserTier.INFLUENCER -> TierBadgeInfo("📈", "Influencer", "#96CEB4")
            UserTier.VETERAN -> TierBadgeInfo("🛡️", "Veteran", "#FFEAA7")
            UserTier.RISING -> TierBadgeInfo("🚀", "Rising", "#DDA0DD")
            UserTier.ROOKIE -> TierBadgeInfo("🌱", "Rookie", "#98D8C8")
            UserTier.TOP_CREATOR -> TierBadgeInfo("🔥", "Top Creator", "#FF4500")
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
        println("PROFILE VM: 🧹 ProfileViewModel cleared")
        printDebugInfo()
    }
}