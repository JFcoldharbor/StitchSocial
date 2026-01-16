/*
 * NotificationViewModel.kt - COMPLETE WITH NAVIGATION EVENTS
 * StitchSocial Android
 *
 * Layer 7: ViewModels - Notification State Management
 * Dependencies: UserService, EngagementCoordinator, NavigationCoordinator, NotificationService, AuthService
 *
 * ✅ COMPLETE: Real Firebase notification loading
 * ✅ COMPLETE: Real-time listener integration
 * ✅ COMPLETE: Proper AuthService user ID retrieval
 * ✅ COMPLETE: Mark as read functionality
 * ✅ FIXED: Navigation events for profile and video navigation
 */

package com.stitchsocial.club.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.NotificationService
import com.stitchsocial.club.services.StitchNotification
import com.stitchsocial.club.services.StitchNotificationType
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

/**
 * Navigation events emitted by NotificationViewModel
 */
sealed class NotificationNavigationEvent {
    data class NavigateToProfile(val userId: String) : NotificationNavigationEvent()
    data class NavigateToVideo(val videoId: String, val threadId: String? = null) : NotificationNavigationEvent()
    data class NavigateToThread(val threadId: String) : NotificationNavigationEvent()
    object None : NotificationNavigationEvent()
}

/**
 * Notification ViewModel with complete Firebase integration and navigation
 */
class NotificationViewModel(
    private val userService: UserService,
    private val engagementCoordinator: EngagementCoordinator,
    private val navigationCoordinator: NavigationCoordinator,
    private val context: Context
) : ViewModel() {

    // Services
    private val notificationService = NotificationService()
    private val authService = AuthService()

    companion object {
        private const val TAG = "NotificationViewModel"
    }

    // MARK: - Core Notification State

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<StitchError?>(null)
    val lastError: StateFlow<StitchError?> = _lastError.asStateFlow()

    // MARK: - Filter and Display State

    private val _selectedFilter = MutableStateFlow(NotificationFilter.ALL)
    val selectedFilter: StateFlow<NotificationFilter> = _selectedFilter.asStateFlow()

    private val _filteredNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val filteredNotifications: StateFlow<List<NotificationItem>> = _filteredNotifications.asStateFlow()

    // MARK: - Navigation Events

    private val _navigationEvent = MutableSharedFlow<NotificationNavigationEvent>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val navigationEvent: SharedFlow<NotificationNavigationEvent> = _navigationEvent.asSharedFlow()

    // MARK: - Initialization

    init {
        Log.d(TAG, "🔔 NOTIFICATION VM: Initializing with NotificationService integration")

        // Load initial notifications
        viewModelScope.launch {
            delay(500) // Small delay to ensure auth is ready
            loadNotifications()
            startRealtimeListener()
        }
    }

    // MARK: - Notification Loading

    /**
     * Load notifications from Firebase via NotificationService
     */
    private suspend fun loadNotifications() {
        try {
            _isLoading.value = true
            Log.d(TAG, "🔔 NOTIFICATION VM: Loading notifications from Firebase...")

            val currentUserID = getCurrentUserId()
            if (currentUserID.isEmpty()) {
                Log.w(TAG, "⚠️ NOTIFICATION VM: No user ID available")
                _isLoading.value = false
                return
            }

            Log.d(TAG, "🔔 NOTIFICATION VM: Loading for user: $currentUserID")

            // Load notifications from NotificationService
            val result = notificationService.loadNotifications(
                userID = currentUserID,
                limit = 50
            )

            Log.d(TAG, "🔔 NOTIFICATION VM: Received ${result.notifications.size} notifications from service")

            // Convert to NotificationItems
            val notificationItems = result.notifications.map { firebaseNotification ->
                convertToNotificationItem(firebaseNotification)
            }

            _notifications.value = notificationItems
            _unreadCount.value = notificationItems.count { !it.isRead }

            // Apply current filter
            applyFilter(_selectedFilter.value)

            Log.d(TAG, "✅ NOTIFICATION VM: Loaded ${notificationItems.size} notifications (${_unreadCount.value} unread)")

        } catch (error: Exception) {
            val stitchError = error as? StitchError ?: StitchError.NetworkError(error.message ?: "Unknown error")
            _lastError.value = stitchError
            Log.e(TAG, "❌ NOTIFICATION VM: Failed to load notifications", error)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Start real-time listener for new notifications
     */
    private fun startRealtimeListener() {
        val currentUserID = getCurrentUserId()
        if (currentUserID.isEmpty()) {
            Log.w(TAG, "⚠️ NOTIFICATION VM: Cannot start listener - no user ID")
            return
        }

        Log.d(TAG, "📡 NOTIFICATION VM: Starting real-time listener for user: $currentUserID")

        notificationService.startListening(
            userID = currentUserID,
            onNotificationsUpdated = { firebaseNotifications ->
                Log.d(TAG, "🔄 NOTIFICATION VM: Real-time update - ${firebaseNotifications.size} notifications")

                val notificationItems = firebaseNotifications.map { convertToNotificationItem(it) }
                _notifications.value = notificationItems
                _unreadCount.value = notificationItems.count { !it.isRead }
                applyFilter(_selectedFilter.value)
            }
        )
    }

    /**
     * Convert Firebase notification to NotificationItem
     */
    private fun convertToNotificationItem(firebaseNotification: StitchNotification): NotificationItem {
        return NotificationItem(
            id = firebaseNotification.id,
            type = mapNotificationType(firebaseNotification.type),
            title = firebaseNotification.title,
            message = firebaseNotification.message,
            timestamp = firebaseNotification.createdAt,
            isRead = firebaseNotification.isRead,
            actionData = buildMap {
                put("userId", firebaseNotification.senderID)
                put("senderID", firebaseNotification.senderID)
                // Copy all payload data
                firebaseNotification.payload.forEach { (key, value) ->
                    put(key, value)
                }
            }
        )
    }

    /**
     * Map Firebase notification type to app notification type
     */
    private fun mapNotificationType(firebaseType: StitchNotificationType): NotificationType {
        return when (firebaseType) {
            StitchNotificationType.HYPE -> NotificationType.HYPE_RECEIVED
            StitchNotificationType.COOL -> NotificationType.HYPE_RECEIVED
            StitchNotificationType.REPLY -> NotificationType.REPLY_RECEIVED
            StitchNotificationType.FOLLOW -> NotificationType.NEW_FOLLOWER
            StitchNotificationType.SHARE -> NotificationType.SHARE_RECEIVED
            StitchNotificationType.MILESTONE -> NotificationType.TAP_MILESTONE
            StitchNotificationType.TIER_UPGRADE -> NotificationType.TIER_UPGRADED
            StitchNotificationType.SYSTEM -> NotificationType.SYSTEM_UPDATE
            else -> NotificationType.SYSTEM_UPDATE
        }
    }

    // MARK: - Notification Actions

    /**
     * Handle notification tap with proper navigation
     */
    fun onNotificationTapped(notification: NotificationItem) {
        viewModelScope.launch {
            Log.d(TAG, "📱 Notification tapped: ${notification.title}")
            Log.d(TAG, "📱 Action data: ${notification.actionData}")

            // Mark as read
            markAsRead(notification.id)

            // Handle navigation based on notification type
            when (notification.type) {
                NotificationType.HYPE_RECEIVED,
                NotificationType.REPLY_RECEIVED,
                NotificationType.SHARE_RECEIVED -> {
                    // Try to get videoID from payload
                    val videoId = notification.actionData["videoID"] as? String
                        ?: notification.actionData["videoId"] as? String
                    val threadId = notification.actionData["threadID"] as? String
                        ?: notification.actionData["threadId"] as? String

                    if (videoId != null) {
                        Log.d(TAG, "📱 Navigating to video: $videoId (thread: $threadId)")
                        _navigationEvent.emit(
                            NotificationNavigationEvent.NavigateToVideo(
                                videoId = videoId,
                                threadId = threadId ?: videoId
                            )
                        )
                    } else {
                        Log.w(TAG, "⚠️ No videoID in notification payload: ${notification.actionData}")
                    }
                }

                NotificationType.NEW_FOLLOWER,
                NotificationType.FOLLOWING_VIDEO -> {
                    // Get sender ID for profile navigation
                    val userId = notification.actionData["userId"] as? String
                        ?: notification.actionData["senderID"] as? String
                        ?: notification.actionData["senderId"] as? String

                    if (userId != null) {
                        Log.d(TAG, "📱 Navigating to profile: $userId")
                        _navigationEvent.emit(
                            NotificationNavigationEvent.NavigateToProfile(userId)
                        )
                    } else {
                        Log.w(TAG, "⚠️ No userId in notification payload: ${notification.actionData}")
                    }
                }

                NotificationType.TAP_MILESTONE -> {
                    val milestone = notification.actionData["milestone"] as? String
                    val videoId = notification.actionData["videoID"] as? String
                        ?: notification.actionData["videoId"] as? String

                    Log.d(TAG, "🎯 Celebrating milestone: $milestone")

                    // Navigate to video if available
                    if (videoId != null) {
                        _navigationEvent.emit(
                            NotificationNavigationEvent.NavigateToVideo(
                                videoId = videoId,
                                threadId = videoId
                            )
                        )
                    }
                }

                NotificationType.TIER_UPGRADED -> {
                    // Could navigate to profile or show celebration
                    val userId = getCurrentUserId()
                    if (userId.isNotEmpty()) {
                        _navigationEvent.emit(
                            NotificationNavigationEvent.NavigateToProfile(userId)
                        )
                    }
                }

                else -> {
                    Log.d(TAG, "📱 Notification type: ${notification.type} - no specific navigation")
                }
            }
        }
    }

    /**
     * Navigate to a specific user's profile
     */
    fun navigateToProfile(userId: String) {
        viewModelScope.launch {
            Log.d(TAG, "📱 Direct navigation to profile: $userId")
            _navigationEvent.emit(NotificationNavigationEvent.NavigateToProfile(userId))
        }
    }

    /**
     * Navigate to a specific video/thread
     */
    fun navigateToVideo(videoId: String, threadId: String? = null) {
        viewModelScope.launch {
            Log.d(TAG, "📱 Direct navigation to video: $videoId")
            _navigationEvent.emit(
                NotificationNavigationEvent.NavigateToVideo(
                    videoId = videoId,
                    threadId = threadId ?: videoId
                )
            )
        }
    }

    /**
     * Mark notification as read in Firebase
     */
    suspend fun markAsRead(notificationId: String) {
        try {
            val currentUserID = getCurrentUserId()
            if (currentUserID.isEmpty()) return

            Log.d(TAG, "✅ Marking notification as read: $notificationId")

            // Update Firebase
            notificationService.markAsRead(currentUserID, notificationId)

            // Update local state
            val updatedNotifications = _notifications.value.map { notification ->
                if (notification.id == notificationId) {
                    notification.copy(isRead = true)
                } else {
                    notification
                }
            }

            _notifications.value = updatedNotifications
            _unreadCount.value = updatedNotifications.count { !it.isRead }

            // Reapply filter
            applyFilter(_selectedFilter.value)

        } catch (error: Exception) {
            Log.e(TAG, "❌ Failed to mark as read", error)
        }
    }

    /**
     * Mark all notifications as read
     */
    fun markAllAsRead() {
        viewModelScope.launch {
            try {
                val currentUserID = getCurrentUserId()
                if (currentUserID.isEmpty()) return@launch

                Log.d(TAG, "✅ Marking all notifications as read")

                // Update Firebase
                notificationService.markAllAsRead(currentUserID)

                // Update local state
                val updatedNotifications = _notifications.value.map { notification ->
                    notification.copy(isRead = true)
                }

                _notifications.value = updatedNotifications
                _unreadCount.value = 0

                // Reapply filter
                applyFilter(_selectedFilter.value)

                Log.d(TAG, "✅ All notifications marked as read")

            } catch (error: Exception) {
                Log.e(TAG, "❌ Failed to mark all as read", error)
            }
        }
    }

    /**
     * Set notification filter
     */
    fun setFilter(filter: NotificationFilter) {
        _selectedFilter.value = filter
        applyFilter(filter)
        Log.d(TAG, "🔍 Filter set to ${filter.displayName}")
    }

    /**
     * Apply filter to notifications
     */
    private fun applyFilter(filter: NotificationFilter) {
        val notifications = _notifications.value

        val filtered = when (filter) {
            NotificationFilter.ALL -> notifications
            NotificationFilter.ENGAGEMENT -> notifications.filter {
                it.type in listOf(
                    NotificationType.HYPE_RECEIVED,
                    NotificationType.REPLY_RECEIVED,
                    NotificationType.SHARE_RECEIVED,
                    NotificationType.TAP_MILESTONE
                )
            }
            NotificationFilter.SOCIAL -> notifications.filter {
                it.type in listOf(
                    NotificationType.NEW_FOLLOWER,
                    NotificationType.FOLLOWING_VIDEO
                )
            }
            NotificationFilter.SYSTEM -> notifications.filter {
                it.type in listOf(
                    NotificationType.TIER_UPGRADED,
                    NotificationType.SYSTEM_UPDATE
                )
            }
            NotificationFilter.UNREAD -> notifications.filter { !it.isRead }
        }

        _filteredNotifications.value = filtered
        Log.d(TAG, "🔍 Filtered to ${filtered.size} notifications")
    }

    /**
     * Refresh notifications from server
     */
    fun refreshNotifications() {
        viewModelScope.launch {
            loadNotifications()
        }
    }

    // MARK: - Helper Methods

    /**
     * Get current user ID from AuthService
     */
    private fun getCurrentUserId(): String {
        val userId = authService.currentUser.value?.uid ?: ""
        if (userId.isEmpty()) {
            Log.w(TAG, "⚠️ No authenticated user found")
        }
        return userId
    }

    // MARK: - Cleanup

    override fun onCleared() {
        super.onCleared()
        notificationService.stopListening()
        Log.d(TAG, "🧹 NOTIFICATION VM: Cleaned up resources")
    }
}

// MARK: - Supporting Data Classes

data class NotificationItem(
    val id: String,
    val type: NotificationType,
    val title: String,
    val message: String,
    val timestamp: Date,
    val isRead: Boolean,
    val actionData: Map<String, Any> = emptyMap()
) {
    val timeAgo: String
        get() {
            val diff = System.currentTimeMillis() - timestamp.time
            val minutes = diff / 60000
            val hours = minutes / 60
            val days = hours / 24

            return when {
                minutes < 1 -> "Just now"
                minutes < 60 -> "${minutes}m"
                hours < 24 -> "${hours}h"
                days < 7 -> "${days}d"
                else -> "${days / 7}w"
            }
        }
}

enum class NotificationType {
    HYPE_RECEIVED,
    REPLY_RECEIVED,
    SHARE_RECEIVED,
    NEW_FOLLOWER,
    FOLLOWING_VIDEO,
    TAP_MILESTONE,
    TIER_UPGRADED,
    SYSTEM_UPDATE;

    val emoji: String
        get() = when (this) {
            HYPE_RECEIVED -> "🔥"
            REPLY_RECEIVED -> "💬"
            SHARE_RECEIVED -> "📤"
            NEW_FOLLOWER -> "👤"
            FOLLOWING_VIDEO -> "🎬"
            TAP_MILESTONE -> "🎯"
            TIER_UPGRADED -> "⬆️"
            SYSTEM_UPDATE -> "ℹ️"
        }
}

enum class NotificationFilter {
    ALL,
    ENGAGEMENT,
    SOCIAL,
    SYSTEM,
    UNREAD;

    val displayName: String
        get() = when (this) {
            ALL -> "All"
            ENGAGEMENT -> "Engagement"
            SOCIAL -> "Social"
            SYSTEM -> "System"
            UNREAD -> "Unread"
        }
}