/*
 * FollowManager.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Centralized Follow/Unfollow Logic with Unfollow Protection
 * Dependencies: UserService, Firebase Auth, SpecialUsersConfig
 * Features: Optimistic UI updates, haptic feedback, error handling, loading states, unfollow protection
 * Used by: SearchView, DiscoveryView, ProfileView
 *
 * COMPLETE PORT: Matches FollowManager.swift with all features
 */

package com.stitchsocial.club

import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.NotificationService
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.SpecialUsersConfig
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/**
 * Centralized manager for all follow/unfollow operations across the app with James Fortune protection
 */
class FollowManager(private val context: Context) : ViewModel() {

    // MARK: - Dependencies
    private val userService = UserService(context)
    private val notificationService = NotificationService()
    private val auth = FirebaseAuth.getInstance()
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // MARK: - Published State

    /** Follow states for all users: [userID: isFollowing] */
    private val _followingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val followingStates: StateFlow<Map<String, Boolean>> = _followingStates.asStateFlow()

    /** Loading states for users currently being followed/unfollowed */
    private val _loadingStates = MutableStateFlow<Set<String>>(emptySet())
    val loadingStates: StateFlow<Set<String>> = _loadingStates.asStateFlow()

    /** Last error that occurred during follow operations */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // MARK: - Completion Callbacks

    /** Callback for when follow state changes successfully */
    var onFollowStateChanged: ((String, Boolean) -> Unit)? = null

    /** Callback for when follow operation fails */
    var onFollowError: ((String, Exception) -> Unit)? = null

    init {
        println("🔗 FOLLOW MANAGER: Initialized with auto-follow protection")
    }

    // MARK: - Public Interface

    /**
     * Toggle follow state for a user with optimistic UI updates, error handling, and unfollow protection
     */
    fun toggleFollow(userID: String) {
        viewModelScope.launch {
            val currentUserID = auth.currentUser?.uid
            if (currentUserID == null) {
                println("❌ FOLLOW MANAGER: No current user ID")
                return@launch
            }

            if (currentUserID == userID) {
                println("❌ FOLLOW MANAGER: Cannot follow yourself")
                return@launch
            }

            // Start loading state
            _loadingStates.value = _loadingStates.value + userID

            // Get current state before optimistic update
            val wasFollowing = _followingStates.value[userID] ?: false
            val newFollowingState = !wasFollowing

            // CHECK FOR UNFOLLOW PROTECTION (James Fortune only)
            if (wasFollowing && !newFollowingState) {
                val isProtected = isProtectedFromUnfollow(userID)
                if (isProtected) {
                    println("🔒 FOLLOW MANAGER: Cannot unfollow protected account $userID")
                    _lastError.value = "This official account cannot be unfollowed"
                    _loadingStates.value = _loadingStates.value - userID

                    // Trigger haptic feedback for blocked action
                    triggerHapticFeedback()

                    return@launch
                }
            }

            // Optimistic UI update (immediate visual feedback)
            _followingStates.value = _followingStates.value + (userID to newFollowingState)

            // Haptic feedback for better UX
            triggerHapticFeedback()

            println("🔗 FOLLOW MANAGER: ${if (newFollowingState) "Following" else "Unfollowing"} user $userID")
            println("🔗 FOLLOW MANAGER: Optimistic state set to: $newFollowingState")

            try {
                // Perform the actual follow/unfollow operation
                val success = if (newFollowingState) {
                    userService.followUser(currentUserID, userID)
                } else {
                    userService.unfollowUser(currentUserID, userID)
                }

                if (success) {
                    println("✅ FOLLOW MANAGER: Successfully ${if (newFollowingState) "followed" else "unfollowed"} user $userID")


                    // Send follow notification via Cloud Function (matches iOS)
                    if (newFollowingState) {
                        try {
                            notificationService.sendFollowNotification(recipientID = userID)
                            println("FOLLOW MANAGER: Follow notification sent to $userID")
                        } catch (e: Exception) {
                            println("FOLLOW MANAGER: Follow notification failed (non-fatal) - ${e.message}")
                        }
                    }
                    // Notify completion callback
                    onFollowStateChanged?.invoke(userID, newFollowingState)

                    // Immediately refresh follow state to ensure UI consistency
                    refreshFollowState(userID)

                    println("✅ FOLLOW MANAGER: Follow state updated - $userID is now ${if (newFollowingState) "FOLLOWED" else "UNFOLLOWED"}")

                    // REFRESH FOLLOWER COUNTS AFTER SUCCESSFUL FOLLOW/UNFOLLOW
                    viewModelScope.launch {
                        try {
                            userService.refreshFollowerCounts(currentUserID)
                            userService.refreshFollowerCounts(userID)
                            println("✅ FOLLOW MANAGER: Refreshed follower counts after follow action")
                        } catch (e: Exception) {
                            println("⚠️ FOLLOW MANAGER: Failed to refresh counts: ${e.message}")
                        }
                    }

                    // Clear any previous errors
                    _lastError.value = null

                } else {
                    throw Exception("Follow operation returned false")
                }

            } catch (e: Exception) {
                // Revert optimistic UI update on error
                _followingStates.value = _followingStates.value + (userID to wasFollowing)

                val errorMessage = "Failed to ${if (newFollowingState) "follow" else "unfollow"} user: ${e.message}"
                _lastError.value = errorMessage

                println("❌ FOLLOW MANAGER: $errorMessage")

                // Notify error callback
                onFollowError?.invoke(userID, e)
            } finally {
                // Clear loading state
                _loadingStates.value = _loadingStates.value - userID
            }
        }
    }

    // MARK: - Unfollow Protection for Special Users

    /**
     * Check if user is protected from unfollowing (James Fortune only)
     */
    private suspend fun isProtectedFromUnfollow(userID: String): Boolean {
        return try {
            println("🔒 FOLLOW MANAGER: Checking unfollow protection for user $userID")

            // Get user email to check against protected accounts
            val userProfile = userService.getUserProfile(userID)
            val userEmail = userProfile?.email ?: ""
            val isProtected = SpecialUsersConfig.isProtectedFromUnfollow(userEmail)

            if (isProtected) {
                println("🔒 FOLLOW MANAGER: User $userID ($userEmail) IS PROTECTED from unfollowing")
            } else {
                println("✅ FOLLOW MANAGER: User $userID ($userEmail) can be unfollowed")
            }

            isProtected

        } catch (e: Exception) {
            println("⚠️ FOLLOW MANAGER: Could not check protection status for $userID: ${e.message}")
            // If we can't check, allow the unfollow (fail open)
            false
        }
    }

    /**
     * Public method to check if a user is protected from unfollowing
     */
    suspend fun isUserProtectedFromUnfollow(userID: String): Boolean {
        return isProtectedFromUnfollow(userID)
    }

    // MARK: - State Management

    /**
     * Check if currently following a user
     */
    fun isFollowing(userID: String): Boolean {
        return _followingStates.value[userID] ?: false
    }

    /**
     * Check if a follow operation is in progress for a user
     */
    fun isLoading(userID: String): Boolean {
        return _loadingStates.value.contains(userID)
    }

    /**
     * Load follow state for a specific user from the server
     */
    fun loadFollowState(userID: String) {
        viewModelScope.launch {
            val currentUserID = auth.currentUser?.uid ?: return@launch

            try {
                val isFollowing = userService.isFollowing(currentUserID, userID)
                _followingStates.value = _followingStates.value + (userID to isFollowing)
                println("🔗 FOLLOW MANAGER: Loaded follow state for $userID: $isFollowing")
            } catch (e: Exception) {
                _followingStates.value = _followingStates.value + (userID to false)
                println("❌ FOLLOW MANAGER: Failed to load follow state for $userID: $e")
            }
        }
    }

    /**
     * Load follow states for multiple users at once (parallel loading)
     */
    fun loadFollowStates(userIDs: List<String>) {
        viewModelScope.launch {
            val currentUserID = auth.currentUser?.uid ?: return@launch

            println("🔄 FOLLOW MANAGER: Loading follow states for ${userIDs.size} users from Firebase...")

            // Parallel loading using async/await
            val deferredResults = userIDs.map { userID ->
                async {
                    try {
                        val isFollowing = userService.isFollowing(currentUserID, userID)
                        _followingStates.value = _followingStates.value + (userID to isFollowing)
                        println("🔗 FOLLOW MANAGER: User $userID - Following: $isFollowing")
                    } catch (e: Exception) {
                        _followingStates.value = _followingStates.value + (userID to false)
                        println("❌ FOLLOW MANAGER: Failed to load state for $userID: ${e.message}")
                    }
                }
            }

            deferredResults.awaitAll()
            println("✅ FOLLOW MANAGER: Finished loading follow states for ${userIDs.size} users")
        }
    }

    /**
     * Refresh follow state for a user (force reload from server)
     */
    fun refreshFollowState(userID: String) {
        loadFollowState(userID)
    }

    /**
     * Clear all cached follow states
     */
    fun clearCache() {
        _followingStates.value = emptyMap()
        _loadingStates.value = emptySet()
        _lastError.value = null
        println("🔗 FOLLOW MANAGER: Cache cleared")
    }

    /**
     * Update follow state manually (useful for external updates)
     */
    fun updateFollowState(userID: String, isFollowing: Boolean) {
        _followingStates.value = _followingStates.value + (userID to isFollowing)
        println("🔗 FOLLOW MANAGER: Manually updated follow state for $userID: $isFollowing")
    }

    // MARK: - Batch Operations

    /**
     * Load follow states for users in a batch (more efficient for lists)
     */
    fun loadFollowStatesForUsers(users: List<BasicUserInfo>) {
        val userIDs = users.map { it.id }
        loadFollowStates(userIDs)
    }

    /**
     * Get follow state for multiple users
     */
    fun getFollowStates(userIDs: List<String>): Map<String, Boolean> {
        return userIDs.associateWith { userID ->
            _followingStates.value[userID] ?: false
        }
    }

    /**
     * Refresh follow states for multiple users (batch refresh)
     */
    fun refreshFollowStates(userIDs: List<String>) {
        viewModelScope.launch {
            println("🔄 FOLLOW MANAGER: Refreshing follow states for ${userIDs.size} users")

            val currentUserID = auth.currentUser?.uid
            if (currentUserID == null) {
                println("❌ FOLLOW MANAGER: No current user for refresh")
                return@launch
            }

            try {
                // Get fresh following data for current user
                val following = userService.getFollowing(currentUserID)
                val followingIDs = following.map { it.id }.toSet()

                // Update local follow states based on fresh data
                userIDs.forEach { userID ->
                    val isFollowing = followingIDs.contains(userID)
                    _followingStates.value = _followingStates.value + (userID to isFollowing)
                    println("🔄 FOLLOW MANAGER: Updated state for $userID: $isFollowing")
                }

                println("✅ FOLLOW MANAGER: Refreshed follow states for ${userIDs.size} users")

            } catch (e: Exception) {
                println("⚠️ FOLLOW MANAGER: Failed to refresh follow states: ${e.message}")
            }
        }
    }

    /**
     * Force refresh all cached follow states
     */
    fun refreshAllFollowStates() {
        val userIDsToRefresh = _followingStates.value.keys.toList()
        refreshFollowStates(userIDsToRefresh)
        println("🔄 FOLLOW MANAGER: Refreshing ALL cached follow states")
    }

    // MARK: - Statistics

    /**
     * Get total number of users being followed (from cache)
     */
    val totalFollowing: Int
        get() = _followingStates.value.values.count { it }

    /**
     * Get number of users currently being processed
     */
    val pendingOperations: Int
        get() = _loadingStates.value.size

    // MARK: - Private Helpers

    /**
     * Trigger haptic feedback for follow/unfollow actions
     */
    private fun triggerHapticFeedback() {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            println("⚠️ FOLLOW MANAGER: Haptic feedback not available: ${e.message}")
        }
    }

    // MARK: - Convenience Methods

    /**
     * Get follow button text for a user
     */
    fun getFollowButtonText(userID: String): String {
        return when {
            isLoading(userID) -> "Loading..."
            isFollowing(userID) -> "Following"
            else -> "Follow"
        }
    }

    /**
     * Get follow button colors for a user (foreground, background)
     */
    fun getFollowButtonColors(userID: String): Pair<Long, Long> {
        return if (isFollowing(userID)) {
            Pair(0xFF000000, 0xFFFFFFFF) // Black text, White background
        } else {
            Pair(0xFFFFFFFF, 0xFF00FFFF) // White text, Cyan background
        }
    }

    /**
     * Check if user can be unfollowed (not protected)
     */
    suspend fun canUnfollow(userID: String): Boolean {
        if (!isFollowing(userID)) {
            return true // Not following, so no need to unfollow
        }

        return !isProtectedFromUnfollow(userID)
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _lastError.value = null
    }

    // MARK: - Debug Helpers

    /**
     * Print current state for debugging
     */
    fun debugPrintState() {
        println("🔗 FOLLOW MANAGER DEBUG:")
        println("   Following states: ${_followingStates.value}")
        println("   Loading states: ${_loadingStates.value}")
        println("   Total following: $totalFollowing")
        println("   Pending operations: $pendingOperations")
        println("   Last error: ${_lastError.value ?: "none"}")
    }

    /**
     * Test follow manager functionality
     */
    fun helloWorldTest() {
        println("🔗 FOLLOW MANAGER: Hello World - Ready for complete follow management!")
        println("🔗 Features: Follow/Unfollow, Optimistic UI, James Fortune protection, Batch operations")
        println("🔗 Status: UserService integration, Haptic feedback, Error handling, State management")
    }
}