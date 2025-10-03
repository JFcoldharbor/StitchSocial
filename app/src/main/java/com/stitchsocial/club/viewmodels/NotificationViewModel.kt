/*
 * NotificationViewModel.kt - FIXED NAVIGATION METHODS
 * StitchSocial Android
 *
 * Layer 7: ViewModels - Notification State Management
 * Dependencies: UserService (Layer 4), EngagementCoordinator (Layer 6), NavigationCoordinator (Layer 6)
 * Features: Engagement notifications, tap progress alerts, milestone celebrations, follow notifications
 *
 * FIXES:
 * ✅ Fixed navigationCoordinator.navigateTo() calls - replaced with showModal()
 * ✅ Proper modal navigation for user profiles and video details
 * ✅ Correct NavigationCoordinator method usage
 *
 * BLUEPRINT: Swift notification patterns from EngagementCoordinator.swift notification methods
 */

package com.stitchsocial.club.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.NavigationDestination
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.protocols.UserService
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.engagement.InteractionType
import com.stitchsocial.club.engagement.TapMilestone
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

/**
 * Notification ViewModel managing engagement alerts and user notifications
 * Translates Swift notification patterns to StateFlow architecture
 */
class NotificationViewModel(
    private val userService: UserService,
    private val engagementCoordinator: EngagementCoordinator,
    private val navigationCoordinator: NavigationCoordinator
) : ViewModel() {

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

    // MARK: - Milestone and Engagement State

    private val _activeMilestones = MutableStateFlow<Map<String, TapMilestone>>(emptyMap())
    val activeMilestones: StateFlow<Map<String, TapMilestone>> = _activeMilestones.asStateFlow()

    private val _milestoneProgress = MutableStateFlow<Map<String, Double>>(emptyMap())
    val milestoneProgress: StateFlow<Map<String, Double>> = _milestoneProgress.asStateFlow()

    private val _celebrationType = MutableStateFlow<CelebrationType?>(null)
    val celebrationType: StateFlow<CelebrationType?> = _celebrationType.asStateFlow()

    private val _showingCelebration = MutableStateFlow(false)
    val showingCelebration: StateFlow<Boolean> = _showingCelebration.asStateFlow()

    // MARK: - Real-time Engagement Alerts

    private val _pendingAlert = MutableStateFlow<EngagementAlert?>(null)
    val pendingAlert: StateFlow<EngagementAlert?> = _pendingAlert.asStateFlow()

    private val _alertQueue = MutableStateFlow<List<EngagementAlert>>(emptyList())
    val alertQueue: StateFlow<List<EngagementAlert>> = _alertQueue.asStateFlow()

    // MARK: - Initialization

    init {
        println("🔔 NOTIFICATION VM: Initializing with engagement coordinator integration")

        // Start observing milestones and engagement
        observeEngagementMilestones()

        // Load initial notifications
        viewModelScope.launch {
            loadNotifications()
        }
    }

    // MARK: - Notification Loading

    /**
     * Load notifications from server
     * BLUEPRINT: Swift NotificationManager.loadNotifications pattern
     */
    private suspend fun loadNotifications() {
        try {
            _isLoading.value = true
            println("🔔 NOTIFICATION VM: Loading notifications...")

            val currentUserID = getCurrentUserId()

            // TODO: Implement actual notification loading
            // val notificationsData = userService.getUserNotifications(currentUserID) as? List<*>
            //     ?: throw StitchError.NetworkError("Failed to load notifications")

            // Convert to NotificationItem objects
            val notificationItems = emptyList<NotificationItem>() // TODO: Implement conversion
            // val notificationItems = notificationsData.mapNotNull { data ->
            //     NotificationItem.fromFirebaseData(data)
            // }.sortedByDescending { it.timestamp }

            _notifications.value = notificationItems
            _unreadCount.value = notificationItems.count { !it.isRead }

            // Apply current filter
            applyFilter(_selectedFilter.value)

            println("✅ NOTIFICATION VM: Loaded ${notificationItems.size} notifications (${_unreadCount.value} unread)")

        } catch (error: Exception) {
            val stitchError = error as? StitchError ?: StitchError.NetworkError(error.message ?: "Unknown error")
            _lastError.value = stitchError
            println("❌ NOTIFICATION VM: Failed to load notifications - ${stitchError.message}")
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Observe engagement milestones from EngagementCoordinator
     * BLUEPRINT: EngagementCoordinator milestone observation patterns
     */
    private fun observeEngagementMilestones() {
        viewModelScope.launch {
            // Observe milestone completions
            engagementCoordinator.showingMilestone.collect { milestonesMap ->
                _activeMilestones.value = milestonesMap

                // Show alerts for new milestones
                milestonesMap.values.firstOrNull()?.let { milestone ->
                    showMilestoneAlert(milestone)
                }
            }
        }

        viewModelScope.launch {
            // Observe tap progress
            engagementCoordinator.tapProgress.collect { progressMap ->
                _milestoneProgress.value = progressMap
            }
        }

        println("🔔 NOTIFICATION VM: Started observing engagement milestones")
    }

    // MARK: - Notification Actions

    /**
     * Handle notification tap with proper navigation
     * FIXED: Uses correct NavigationCoordinator methods
     */
    fun onNotificationTapped(notification: NotificationItem) {
        viewModelScope.launch {
            // Mark as read
            markAsRead(notification.id)

            // Handle navigation based on notification type
            when (notification.type) {
                NotificationType.HYPE_RECEIVED,
                NotificationType.REPLY_RECEIVED,
                NotificationType.SHARE_RECEIVED -> {
                    // Navigate to video
                    val videoId = notification.actionData["videoId"] as? String
                    if (videoId != null) {
                        // ✅ FIXED: Use showModal instead of navigateTo
                        navigationCoordinator.showModal(
                            modal = ModalState.VIDEO_PLAYER,
                            data = mapOf("videoId" to videoId)
                        )
                    }
                }
                NotificationType.NEW_FOLLOWER,
                NotificationType.FOLLOWING_VIDEO -> {
                    // Navigate to user profile
                    val userId = notification.actionData["userId"] as? String
                    if (userId != null) {
                        // ✅ FIXED: Use showModal instead of navigateTo
                        navigationCoordinator.showModal(
                            modal = ModalState.USER_PROFILE,
                            data = mapOf("userID" to userId)
                        )
                    }
                }
                NotificationType.TAP_MILESTONE -> {
                    // Show milestone celebration
                    val milestone = notification.actionData["milestone"] as? String
                    println("🎯 Celebrating milestone: $milestone")
                }
                else -> {
                    println("📱 Notification tapped: ${notification.title}")
                }
            }
        }
    }

    /**
     * Mark notification as read
     */
    private suspend fun markAsRead(notificationId: String) {
        try {
            // TODO: Implement server call
            // userService.markNotificationAsRead(notificationId)

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

            // Reapply filter to update filtered notifications
            applyFilter(_selectedFilter.value)

            println("✅ NOTIFICATION VM: Marked notification $notificationId as read")

        } catch (error: Exception) {
            println("❌ NOTIFICATION VM: Failed to mark as read - ${error.message}")
        }
    }

    /**
     * Set notification filter
     */
    fun setFilter(filter: NotificationFilter) {
        _selectedFilter.value = filter
        applyFilter(filter)
        println("🔍 NOTIFICATION VM: Filter set to ${filter.displayName}")
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
    }

    /**
     * Clear all notifications
     */
    fun clearAllNotifications() {
        viewModelScope.launch {
            try {
                val currentUserID = getCurrentUserId()
                // TODO: Implement actual clearAllNotifications method
                // userService.clearAllNotifications(currentUserID)

                _notifications.value = emptyList()
                _filteredNotifications.value = emptyList()
                _unreadCount.value = 0

                println("🗑️ NOTIFICATION VM: Cleared all notifications")

            } catch (error: Exception) {
                println("❌ NOTIFICATION VM: Failed to clear notifications - ${error.message}")
            }
        }
    }

    // MARK: - Milestone Alert Management

    /**
     * Show milestone celebration alert
     */
    private fun showMilestoneAlert(milestone: TapMilestone) {
        _celebrationType.value = CelebrationType.MILESTONE_REACHED
        _showingCelebration.value = true

        // Auto-hide after celebration duration
        viewModelScope.launch {
            delay(CelebrationType.MILESTONE_REACHED.duration)
            hideCelebration()
        }

        println("🎯 NOTIFICATION VM: Showing milestone alert - ${milestone.displayName}")
    }

    /**
     * Hide celebration
     */
    fun hideCelebration() {
        _showingCelebration.value = false
        _celebrationType.value = null
    }

    /**
     * Show engagement alert
     */
    fun showEngagementAlert(alert: EngagementAlert) {
        // Add to queue if already showing an alert
        if (_pendingAlert.value != null) {
            val currentQueue = _alertQueue.value.toMutableList()
            currentQueue.add(alert)
            _alertQueue.value = currentQueue
        } else {
            _pendingAlert.value = alert

            // Auto-hide after 3 seconds
            viewModelScope.launch {
                delay(3000L)
                hideEngagementAlert()
            }
        }

        println("⚡ NOTIFICATION VM: Showing engagement alert - ${alert.type.displayName} from ${alert.fromUser}")
    }

    /**
     * Hide current engagement alert and show next in queue
     */
    fun hideEngagementAlert() {
        _pendingAlert.value = null

        // Show next alert in queue
        val queue = _alertQueue.value
        if (queue.isNotEmpty()) {
            val nextAlert = queue.first()
            val remainingQueue = queue.drop(1)

            _alertQueue.value = remainingQueue
            showEngagementAlert(nextAlert)
        }
    }

    // MARK: - Helper Methods

    /**
     * Get current user ID (placeholder - should come from AuthService)
     */
    private fun getCurrentUserId(): String {
        return "current_user_id" // TODO: Get from AuthService
    }

    /**
     * Refresh notifications from server
     */
    fun refreshNotifications() {
        viewModelScope.launch {
            loadNotifications()
        }
    }

    // MARK: - Cleanup

    override fun onCleared() {
        super.onCleared()
        println("🧹 NOTIFICATION VM: Cleaning up resources")
    }
}

// MARK: - Supporting Data Classes

/**
 * Notification item for display
 */
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

    companion object {
        fun fromFirebaseData(data: Any?): NotificationItem? {
            // TODO: Implement Firebase data conversion
            return null
        }
    }
}

/**
 * Notification types
 */
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

/**
 * Notification filter options
 */
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

/**
 * Engagement alert for real-time display
 */
data class EngagementAlert(
    val id: String,
    val type: InteractionType,
    val fromUser: String,
    val videoTitle: String,
    val cloutGain: Int,
    val timestamp: Date
)

/**
 * Celebration animation types
 */
enum class CelebrationType {
    MILESTONE_REACHED,
    TIER_UPGRADED,
    FIRST_HYPE,
    VIRAL_VIDEO;

    val duration: Long
        get() = when (this) {
            MILESTONE_REACHED -> 3000L
            TIER_UPGRADED -> 4000L
            FIRST_HYPE -> 2000L
            VIRAL_VIDEO -> 5000L
        }
}