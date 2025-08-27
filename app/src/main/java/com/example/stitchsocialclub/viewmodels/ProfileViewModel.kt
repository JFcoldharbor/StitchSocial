/*
 * ProfileViewModel.kt
 * StitchSocial Android
 *
 * Layer 7: ViewModels - Profile State Management with Real UserService Integration
 * Dependencies: UserService (Layer 4), VideoService (Layer 4), NavigationCoordinator (Layer 6)
 * Features: Real user profile display, tier progression, badges, video grid, social stats
 *
 * BLUEPRINT: ProfileView.swift state management → StateFlow conversion
 */

package com.example.stitchsocialclub.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stitchsocialclub.coordination.NavigationCoordinator
import com.example.stitchsocialclub.services.UserService
import com.example.stitchsocialclub.services.VideoServiceImpl
import com.example.stitchsocialclub.foundation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

/**
 * Profile ViewModel with complete UserService integration
 * Manages real user profile display and interaction state
 */
class ProfileViewModel(
    private val userService: UserService = UserService(),
    private val videoService: VideoServiceImpl = VideoServiceImpl(),
    private val navigationCoordinator: NavigationCoordinator = NavigationCoordinator(),
    private val userID: String? = null // null = current user profile
) : ViewModel() {

    // MARK: - Core Profile State

    private val _currentUser = MutableStateFlow<UserService.BasicUserInfo?>(null)
    val currentUser: StateFlow<UserService.BasicUserInfo?> = _currentUser.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<StitchError?>(null)
    val lastError: StateFlow<StitchError?> = _lastError.asStateFlow()

    // MARK: - Video Grid State

    private val _userVideos = MutableStateFlow<List<UserService.ProfileVideoMetadata>>(emptyList())
    val userVideos: StateFlow<List<UserService.ProfileVideoMetadata>> = _userVideos.asStateFlow()

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()

    private val _tabVideos = MutableStateFlow<List<UserService.ProfileVideoMetadata>>(emptyList())
    val tabVideos: StateFlow<List<UserService.ProfileVideoMetadata>> = _tabVideos.asStateFlow()

    // MARK: - Social Stats State

    private val _followersList = MutableStateFlow<List<UserService.BasicUserInfo>>(emptyList())
    val followersList: StateFlow<List<UserService.BasicUserInfo>> = _followersList.asStateFlow()

    private val _followingList = MutableStateFlow<List<UserService.BasicUserInfo>>(emptyList())
    val followingList: StateFlow<List<UserService.BasicUserInfo>> = _followingList.asStateFlow()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private val _tierProgress = MutableStateFlow<UserService.TierProgress?>(null)
    val tierProgress: StateFlow<UserService.TierProgress?> = _tierProgress.asStateFlow()

    // MARK: - UI State

    private val _showingFollowersList = MutableStateFlow(false)
    val showingFollowersList: StateFlow<Boolean> = _showingFollowersList.asStateFlow()

    private val _showingFollowingList = MutableStateFlow(false)
    val showingFollowingList: StateFlow<Boolean> = _showingFollowingList.asStateFlow()

    private val _showingBadgeCollection = MutableStateFlow(false)
    val showingBadgeCollection: StateFlow<Boolean> = _showingBadgeCollection.asStateFlow()

    private val _showingVideoPlayer = MutableStateFlow(false)
    val showingVideoPlayer: StateFlow<Boolean> = _showingVideoPlayer.asStateFlow()

    private val _selectedVideoIndex = MutableStateFlow(0)
    val selectedVideoIndex: StateFlow<Int> = _selectedVideoIndex.asStateFlow()

    // MARK: - Animation State

    private val _hypeBarProgress = MutableStateFlow(0.0)
    val hypeBarProgress: StateFlow<Double> = _hypeBarProgress.asStateFlow()

    private val _shimmerOffset = MutableStateFlow(-300.0)
    val shimmerOffset: StateFlow<Double> = _shimmerOffset.asStateFlow()

    private val _progressRingProgress = MutableStateFlow(0.0)
    val progressRingProgress: StateFlow<Double> = _progressRingProgress.asStateFlow()

    private val _liquidWaveOffset = MutableStateFlow(0.0)
    val liquidWaveOffset: StateFlow<Double> = _liquidWaveOffset.asStateFlow()

    // MARK: - Initialization

    init {
        println("👤 PROFILE VM: Initializing with UserService integration")
        // Only load profile if userID is provided, otherwise wait for authentication
        if (userID != null) {
            viewModelScope.launch {
                loadProfile()
                startAnimations()
            }
        } else {
            println("👤 PROFILE VM: No userID provided - waiting for authentication")
            _isLoading.value = false
        }
    }

    // MARK: - Profile Loading with Real UserService

    /**
     * Load user profile data using real UserService
     * BLUEPRINT: ProfileView.loadProfile() with UserService integration
     */
    suspend fun loadProfile() {
        try {
            _isLoading.value = true
            _lastError.value = null

            val profileUserID = userID
            if (profileUserID == null) {
                throw StitchError.AuthenticationError("No user ID provided - user not authenticated")
            }

            println("👤 PROFILE VM: Loading profile for userID: $profileUserID")

            // Get user profile from UserService
            val userProfile = userService.getUserProfile(profileUserID)
            if (userProfile == null) {
                throw StitchError.NetworkError("User profile not found for ID: $profileUserID")
            }

            _currentUser.value = userProfile

            // Load associated data in parallel
            val loadUserVideosJob = viewModelScope.async { loadUserVideos(profileUserID) }
            val loadSocialStatsJob = viewModelScope.async { loadSocialStats(profileUserID) }
            val loadTierProgressJob = viewModelScope.async { loadTierProgress(profileUserID) }

            // Wait for all data to load
            loadUserVideosJob.await()
            loadSocialStatsJob.await()
            loadTierProgressJob.await()

            println("✅ PROFILE VM: Profile loaded successfully - ${userProfile.displayName}")

        } catch (error: Exception) {
            val stitchError = error as? StitchError ?: StitchError.NetworkError(error.message ?: "Unknown error")
            _lastError.value = stitchError
            println("❌ PROFILE VM: Profile load failed - ${stitchError.message}")
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Load user's videos using UserService
     * BLUEPRINT: ProfileView video filtering logic with real data
     */
    private suspend fun loadUserVideos(profileUserID: String) {
        try {
            println("🎬 PROFILE VM: Loading videos for user")

            val videos = userService.getUserVideos(profileUserID)
            _userVideos.value = videos
            updateTabVideos() // Update current tab's filtered videos

            println("✅ PROFILE VM: Loaded ${videos.size} videos")

        } catch (error: Exception) {
            println("❌ PROFILE VM: Failed to load videos - ${error.message}")
        }
    }

    /**
     * Load followers and following lists using UserService
     * BLUEPRINT: ProfileView.loadFollowers() and loadFollowing() with real data
     */
    private suspend fun loadSocialStats(profileUserID: String) {
        try {
            println("👥 PROFILE VM: Loading social stats")

            // Load followers and following in parallel
            val followersJob = viewModelScope.async { userService.getFollowersList(profileUserID) }
            val followingJob = viewModelScope.async { userService.getFollowingList(profileUserID) }

            val followers = followersJob.await()
            val following = followingJob.await()

            _followersList.value = followers
            _followingList.value = following

            // Check if current user is following this profile
            // TODO: Implement when AuthService provides current user context
            // val currentUserId = getCurrentUserId()
            // if (currentUserId != profileUserID) {
            //     _isFollowing.value = userService.isFollowing(currentUserId, profileUserID)
            // }

            println("✅ PROFILE VM: Loaded ${followers.size} followers, ${following.size} following")

        } catch (error: Exception) {
            println("❌ PROFILE VM: Failed to load social stats - ${error.message}")
        }
    }

    /**
     * Load tier progression data using UserService
     */
    private suspend fun loadTierProgress(profileUserID: String) {
        try {
            println("🏆 PROFILE VM: Loading tier progress")

            val progress = userService.getTierProgress(profileUserID)
            _tierProgress.value = progress

            println("✅ PROFILE VM: Loaded tier progress")

        } catch (error: Exception) {
            println("❌ PROFILE VM: Failed to load tier progress - ${error.message}")
        }
    }

    // MARK: - Tab Management

    /**
     * Switch between video tabs (Threads, Stitches, Replies)
     * BLUEPRINT: ProfileView tab switching logic
     */
    fun selectTab(index: Int) {
        _selectedTabIndex.value = index
        updateTabVideos()
        println("📂 PROFILE VM: Switched to tab $index")
    }

    /**
     * Update videos for current tab based on conversation depth
     * BLUEPRINT: ProfileView.getTabCount() filtering logic
     */
    private fun updateTabVideos() {
        val allVideos = _userVideos.value
        val currentTab = _selectedTabIndex.value

        val filteredVideos = when (currentTab) {
            0 -> allVideos.filter { it.conversationDepth == 0 } // Threads
            1 -> allVideos.filter { it.conversationDepth == 1 } // Stitches
            2 -> allVideos.filter { it.conversationDepth >= 2 } // Replies
            else -> emptyList()
        }

        _tabVideos.value = filteredVideos
        println("🎬 PROFILE VM: Tab $currentTab has ${filteredVideos.size} videos")
    }

    // MARK: - Social Actions with Real UserService

    /**
     * Follow or unfollow user using UserService
     * BLUEPRINT: ProfileView follow/unfollow logic with real Firebase operations
     */
    fun toggleFollow() {
        val user = _currentUser.value ?: return
        // For now, skip follow functionality if no authentication context
        // TODO: Implement when AuthService provides current user ID

        println("👤 PROFILE VM: Follow functionality requires authentication integration")
        _lastError.value = StitchError.AuthenticationError("Follow functionality requires user authentication")
    }

    // MARK: - Video Player Management

    /**
     * Show video player for selected video
     * BLUEPRINT: ProfileView video player presentation
     */
    fun showVideoPlayer(videoIndex: Int) {
        _selectedVideoIndex.value = videoIndex
        _showingVideoPlayer.value = true
        println("🎥 PROFILE VM: Showing video player for index $videoIndex")
    }

    fun dismissVideoPlayer() {
        _showingVideoPlayer.value = false
        println("🎥 PROFILE VM: Dismissed video player")
    }

    // MARK: - Modal Management

    fun showFollowersList() {
        _showingFollowersList.value = true
        viewModelScope.launch {
            val user = _currentUser.value ?: return@launch
            loadSocialStats(user.id)
        }
    }

    fun dismissFollowersList() {
        _showingFollowersList.value = false
    }

    fun showFollowingList() {
        _showingFollowingList.value = true
        viewModelScope.launch {
            val user = _currentUser.value ?: return@launch
            loadSocialStats(user.id)
        }
    }

    fun dismissFollowingList() {
        _showingFollowingList.value = false
    }

    fun showBadgeCollection() {
        _showingBadgeCollection.value = true
    }

    fun dismissBadgeCollection() {
        _showingBadgeCollection.value = false
    }

    // MARK: - Profile Calculations

    /**
     * Calculate hype percentage for progress display
     * BLUEPRINT: ProfileView.calculateHypePercentage() exact translation
     */
    fun calculateHypePercentage(): Int {
        val user = _currentUser.value ?: return 50
        val tierProgress = _tierProgress.value

        return if (tierProgress != null) {
            tierProgress.progressPercentage.toInt()
        } else {
            // Fallback calculation
            val clout = user.clout
            val tierMultiplier = when (user.tier) {
                UserService.UserTier.FOUNDER, UserService.UserTier.CO_FOUNDER -> 1.0
                UserService.UserTier.TOP_CREATOR -> 0.9
                UserService.UserTier.PARTNER -> 0.8
                UserService.UserTier.INFLUENCER -> 0.7
                UserService.UserTier.RISING -> 0.6
                UserService.UserTier.ROOKIE -> 0.5
                else -> 0.5
            }

            val basePercentage = minOf(100.0, (clout.toDouble() / 10000.0) * 100 * tierMultiplier)
            basePercentage.toInt()
        }
    }

    /**
     * Format clout number for display
     * BLUEPRINT: ProfileView.formatClout() exact translation
     */
    fun formatClout(): String {
        val user = _currentUser.value ?: return "0"
        val clout = user.clout

        return when {
            clout >= 1_000_000 -> String.format("%.1fM", clout.toDouble() / 1_000_000.0)
            clout >= 1_000 -> String.format("%.1fK", clout.toDouble() / 1_000.0)
            else -> clout.toString()
        }
    }

    /**
     * Get badges for current user
     * BLUEPRINT: ProfileView.getBadgesForUser() exact translation
     */
    fun getUserBadges(): List<ProfileBadgeInfo> {
        val user = _currentUser.value ?: return emptyList()
        val badges = mutableListOf<ProfileBadgeInfo>()

        if (user.isVerified) {
            badges.add(
                ProfileBadgeInfo(
                    id = "verified",
                    iconName = "checkmark",
                    colors = listOf("#00BFFF", "#0080FF"),
                    title = "Verified"
                )
            )
        }

        when (user.tier) {
            UserService.UserTier.FOUNDER, UserService.UserTier.CO_FOUNDER -> {
                badges.add(
                    ProfileBadgeInfo(
                        id = "founder",
                        iconName = "crown_fill",
                        colors = listOf("#FFD700", "#FFA500"),
                        title = "Founder"
                    )
                )
            }
            UserService.UserTier.TOP_CREATOR -> {
                badges.add(
                    ProfileBadgeInfo(
                        id = "top",
                        iconName = "star_fill",
                        colors = listOf("#0080FF", "#8A2BE2"),
                        title = "Top Creator"
                    )
                )
            }
            else -> { /* No special badges */ }
        }

        return badges
    }

    // MARK: - Refresh Operations

    /**
     * Refresh all profile data
     */
    fun refreshProfile() {
        viewModelScope.launch {
            loadProfile()
        }
    }

    /**
     * Refresh social stats only
     */
    fun refreshSocialStats() {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            loadSocialStats(user.id)
        }
    }

    // MARK: - Animation Management

    /**
     * Start profile animations
     * BLUEPRINT: ProfileAnimations.swift timing patterns
     */
    private fun startAnimations() {
        viewModelScope.launch {
            // Hype bar animation
            launch {
                delay(800) // ProfileAnimations.hypeBarFill delay
                val targetProgress = calculateHypePercentage() / 100.0
                animateValue(_hypeBarProgress, targetProgress, 1500)
            }

            // Progress ring animation
            launch {
                delay(500) // ProfileAnimations.progressRing delay
                animateValue(_progressRingProgress, 1.0, 2000)
            }

            // Shimmer effect (continuous)
            launch {
                while (true) {
                    animateValue(_shimmerOffset, 300.0, 2000)
                    _shimmerOffset.value = -300.0
                }
            }

            // Liquid wave animation (continuous)
            launch {
                while (true) {
                    animateValue(_liquidWaveOffset, 360.0, 2000)
                    _liquidWaveOffset.value = 0.0
                }
            }
        }
    }

    /**
     * Animate a StateFlow value over time
     */
    private suspend fun animateValue(stateFlow: MutableStateFlow<Double>, targetValue: Double, durationMs: Long) {
        val startValue = stateFlow.value
        val startTime = System.currentTimeMillis()

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            val progress = minOf(1.0, elapsed.toDouble() / durationMs)

            val currentValue = startValue + (targetValue - startValue) * progress
            stateFlow.value = currentValue

            if (progress >= 1.0) break
            delay(16) // ~60fps
        }
    }

    // MARK: - Helper Methods

    /**
     * Load profile with explicit user ID
     */
    fun loadProfileForUser(userId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _lastError.value = null

                println("👤 PROFILE VM: Loading profile for explicit userID: $userId")

                val userProfile = userService.getUserProfile(userId)
                if (userProfile == null) {
                    throw StitchError.NetworkError("User profile not found for ID: $userId")
                }

                _currentUser.value = userProfile

                // Load associated data in parallel
                val loadUserVideosJob = viewModelScope.async { loadUserVideos(userId) }
                val loadSocialStatsJob = viewModelScope.async { loadSocialStats(userId) }
                val loadTierProgressJob = viewModelScope.async { loadTierProgress(userId) }

                // Wait for all data to load
                loadUserVideosJob.await()
                loadSocialStatsJob.await()
                loadTierProgressJob.await()

                // Start animations after loading
                startAnimations()

                println("✅ PROFILE VM: Profile loaded successfully - ${userProfile.displayName}")

            } catch (error: Exception) {
                val stitchError = error as? StitchError ?: StitchError.NetworkError(error.message ?: "Unknown error")
                _lastError.value = stitchError
                println("❌ PROFILE VM: Profile load failed - ${stitchError.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _lastError.value = null
    }

    // MARK: - Cleanup

    override fun onCleared() {
        super.onCleared()
        println("🧹 PROFILE VM: Cleaning up resources")
    }
}

// MARK: - Supporting Data Classes

/**
 * Profile badge information for UI display
 */
data class ProfileBadgeInfo(
    val id: String,
    val iconName: String,
    val colors: List<String>, // Hex color codes
    val title: String
)