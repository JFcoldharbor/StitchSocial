/*
 * FollowManager.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Centralized Follow/Unfollow Logic
 * Dependencies: UserService, Firebase Auth
 * Features: Optimistic UI updates, loading states, error handling
 * Used by: SearchView, DiscoveryView, ProfileView
 *
 * SIMPLIFIED: Works with your existing UserService structure
 */

package com.stitchsocial.club

import com.stitchsocial.club.services.UserService
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.stitchsocial.club.foundation.BasicUserInfo

/**
 * Centralized manager for all follow/unfollow operations across the app
 */
class FollowManager(private val context: Context) : ViewModel() {

    // MARK: - Dependencies
    private val userService = UserService(context)
    private val auth = FirebaseAuth.getInstance()

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

    init {
        println("🔗 FOLLOW MANAGER: Initialized")
    }

    // MARK: - Public Interface

    /**
     * Toggle follow state for a user with optimistic UI updates and error handling
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

            // Optimistic UI update (immediate visual feedback)
            _followingStates.value = _followingStates.value + (userID to newFollowingState)

            println("🔗 FOLLOW MANAGER: ${if (newFollowingState) "Following" else "Unfollowing"} user $userID")

            try {
                // Perform the actual follow/unfollow operation
                val success = if (newFollowingState) {
                    userService.followUser(currentUserID, userID)
                } else {
                    userService.unfollowUser(currentUserID, userID)
                }

                if (success) {
                    println("✅ FOLLOW MANAGER: Successfully ${if (newFollowingState) "followed" else "unfollowed"} user $userID")
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
            } finally {
                // Clear loading state
                _loadingStates.value = _loadingStates.value - userID
            }
        }
    }

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
     * Load follow states for multiple users at once
     */
    fun loadFollowStates(userIDs: List<String>) {
        userIDs.forEach { userID ->
            loadFollowState(userID)
        }
    }

    /**
     * Load follow states for users in a batch (more efficient for lists)
     */
    fun loadFollowStatesForUsers(users: List<BasicUserInfo>) {
        val userIDs = users.map { it.id }
        loadFollowStates(userIDs)
    }

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

    fun clearError() {
        _lastError.value = null
    }
}