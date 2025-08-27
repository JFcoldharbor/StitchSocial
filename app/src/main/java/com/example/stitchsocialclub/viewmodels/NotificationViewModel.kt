/*
 * NotificationViewModel.kt
 * StitchSocial Android
 *
 * Layer 7: ViewModels - Notification State Management
 * Dependencies: UserService (Layer 4), EngagementCoordinator (Layer 6), NavigationCoordinator (Layer 6)
 * Features: Engagement notifications, tap progress alerts, milestone celebrations, follow notifications
 *
 * BLUEPRINT: Swift notification patterns from EngagementCoordinator.swift notification methods
 */

package com.example.stitchsocialclub.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stitchsocialclub.coordination.NavigationCoordinator
import com.example.stitchsocialclub.coordination.NavigationDestination
import com.example.stitchsocialclub.coordination.EngagementCoordinator
import com.example.stitchsocialclub.protocols.UserService
import com.example.stitchsocialclub.foundation.*
import com.example.stitchsocialclub.businesslogic.InteractionType
import com.example.stitchsocialclub.businesslogic.TapMilestone
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

    // MARK: - Real-time Milestone State

    private val _activeMilestones = MutableStateFlow<Map<String, TapMilestone>>(emptyMap())
    val activeMilestones: StateFlow<Map<String, TapMilestone>> = _activeMilestones.asStateFlow()

    private val _showingMilestoneAlert = MutableStateFlow<TapMilestone?>(null)
    val showingMilestoneAlert: StateFlow<TapMilestone?> = _showingMilestoneAlert.asStateFlow()

    private val _milestoneProgress = MutableStateFlow<Map<String, Double>>(emptyMap())
    val milestoneProgress: StateFlow<Map<String, Double>> = _milestoneProgress.asStateFlow()

    // MARK: - Engagement Notification State

    private val _engagementAlerts = MutableStateFlow<List<EngagementAlert>>(emptyList())
    val engagementAlerts: StateFlow<List<EngagementAlert>> = _engagementAlerts.asStateFlow()

    private val _showingEngagementAlert = MutableStateFlow<EngagementAlert?>(null)
    val showingEngagementAlert: StateFlow<EngagementAlert?> = _showingEngagementAlert.asStateFlow()

    // MARK: - Animation State

    private val _celebrationAnimations = MutableStateFlow<Map<String, CelebrationType>>(emptyMap())
    val celebrationAnimations: StateFlow<Map<String, CelebrationType>> = _celebrationAnimations.asStateFlow()

    // MARK: - Initialization

    init {
        viewModelScope.launch {
            loadNotifications()
            observeEngagementMilestones()
        }
    }

    // MARK: - Notification Loading

    /**
     * Load user notifications from server
     * BLUEPRINT: NotificationService.loadNotifications() patterns
     */
    suspend fun loadNotifications() {
        try {
            _isLoading.value = true
            _lastError.value = null

            println("🔔 NOTIFICATION VM: Loading notifications")

            // Get current user ID (TODO: from AuthService)
            val currentUserID = getCurrentUserId()

            // TODO: Implement actual getUserNotifications method
            val notificationsData = emptyList<Any>() // Placeholder
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
    }

    // MARK: - Notification Filtering

    /**
     * Apply notification filter
     */
    fun selectFilter(filter: NotificationFilter) {
        _selectedFilter.value = filter
        applyFilter(filter)
        println("🔍 NOTIFICATION VM: Applied filter: ${filter.displayName}")
    }

    /**
     * Filter notifications based on selected filter
     */
    private fun applyFilter(filter: NotificationFilter) {
        val allNotifications = _notifications.value

        val filtered = when (filter) {
            NotificationFilter.ALL -> allNotifications
            NotificationFilter.ENGAGEMENT -> allNotifications.filter {
                it.type in listOf(NotificationType.TAP_MILESTONE, NotificationType.HYPE_RECEIVED, NotificationType.REPLY_RECEIVED)
            }
            NotificationFilter.SOCIAL -> allNotifications.filter {
                it.type in listOf(NotificationType.NEW_FOLLOWER, NotificationType.FOLLOWING_VIDEO)
            }
            NotificationFilter.SYSTEM -> allNotifications.filter {
                it.type in listOf(NotificationType.TIER_UPGRADED, NotificationType.SYSTEM_UPDATE)
            }
            NotificationFilter.UNREAD -> allNotifications.filter { !it.isRead }
        }

        _filteredNotifications.value = filtered
    }

    // MARK: - Milestone Management

    /**
     * Show milestone alert with celebration
     * BLUEPRINT: EngagementCoordinator.sendProgressiveTapMilestone() UI patterns
     */
    private fun showMilestoneAlert(milestone: TapMilestone) {
        viewModelScope.launch {
            _showingMilestoneAlert.value = milestone

            // Create celebration animation
            val celebrationMap = _celebrationAnimations.value.toMutableMap()
            celebrationMap["milestone_${milestone.name}"] = CelebrationType.MILESTONE_REACHED
            _celebrationAnimations.value = celebrationMap

            // Create notification item for milestone
            val milestoneNotification = NotificationItem(
                id = "milestone_${System.currentTimeMillis()}",
                type = NotificationType.TAP_MILESTONE,
                title = "Milestone Reached! 🎯",
                message = milestone.displayName,
                timestamp = Date(),
                isRead = false,
                actionData = mapOf("milestone" to milestone.name)
            )

            // Add to notifications list
            val currentNotifications = _notifications.value.toMutableList()
            currentNotifications.add(0, milestoneNotification)
            _notifications.value = currentNotifications

            // Update unread count
            _unreadCount.value = _unreadCount.value + 1

            // Auto-dismiss after 3 seconds
            delay(3000)
            _showingMilestoneAlert.value = null

            // Remove celebration animation
            val updatedCelebrationMap = _celebrationAnimations.value.toMutableMap()
            updatedCelebrationMap.remove("milestone_${milestone.name}")
            _celebrationAnimations.value = updatedCelebrationMap

            println("🎯 NOTIFICATION VM: Milestone alert shown: ${milestone.displayName}")
        }
    }

    /**
     * Dismiss milestone alert
     */
    fun dismissMilestoneAlert() {
        _showingMilestoneAlert.value = null
        println("🎯 NOTIFICATION VM: Milestone alert dismissed")
    }

    // MARK: - Engagement Alerts

    /**
     * Show engagement alert (hype received, reply, etc.)
     * BLUEPRINT: Engagement notification patterns from EngagementCoordinator
     */
    fun showEngagementAlert(
        type: InteractionType,
        fromUser: String,
        videoTitle: String,
        cloutGain: Int = 0
    ) {
        viewModelScope.launch {
            val alert = EngagementAlert(
                id = "alert_${System.currentTimeMillis()}",
                type = type,
                fromUser = fromUser,
                videoTitle = videoTitle,
                cloutGain = cloutGain,
                timestamp = Date()
            )

            // Add to alerts list
            val currentAlerts = _engagementAlerts.value.toMutableList()
            currentAlerts.add(0, alert)
            _engagementAlerts.value = currentAlerts

            // Show alert overlay
            _showingEngagementAlert.value = alert

            // Create notification item
            val notificationItem = NotificationItem(
                id = "engagement_${System.currentTimeMillis()}",
                type = when (type) {
                    InteractionType.HYPE -> NotificationType.HYPE_RECEIVED
                    InteractionType.REPLY -> NotificationType.REPLY_RECEIVED
                    InteractionType.SHARE -> NotificationType.SHARE_RECEIVED
                    else -> NotificationType.SYSTEM_UPDATE
                },
                title = "${type.displayName} from @$fromUser",
                message = "\"$videoTitle\"" + if (cloutGain > 0) " (+$cloutGain clout)" else "",
                timestamp = Date(),
                isRead = false,
                actionData = mapOf(
                    "fromUser" to fromUser,
                    "videoTitle" to videoTitle,
                    "interactionType" to type.name
                )
            )

            // Add to notifications
            val currentNotifications = _notifications.value.toMutableList()
            currentNotifications.add(0, notificationItem)
            _notifications.value = currentNotifications
            _unreadCount.value = _unreadCount.value + 1

            // Auto-dismiss alert after 4 seconds
            delay(4000)
            _showingEngagementAlert.value = null

            println("🔥 NOTIFICATION VM: Engagement alert shown: ${type.displayName} from $fromUser")
        }
    }

    /**
     * Dismiss engagement alert
     */
    fun dismissEngagementAlert() {
        _showingEngagementAlert.value = null
        println("🔥 NOTIFICATION VM: Engagement alert dismissed")
    }

    // MARK: - Notification Actions

    /**
     * Mark notification as read
     */
    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            try {
                // TODO: Implement actual markNotificationAsRead method
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

                // Refresh filtered notifications
                applyFilter(_selectedFilter.value)

                println("✅ NOTIFICATION VM: Marked notification $notificationId as read")

            } catch (error: Exception) {
                println("❌ NOTIFICATION VM: Failed to mark notification as read - ${error.message}")
            }
        }
    }

    /**
     * Mark all notifications as read
     */
    fun markAllAsRead() {
        viewModelScope.launch {
            try {
                val currentUserID = getCurrentUserId()
                // TODO: Implement actual markAllNotificationsAsRead method
                // userService.markAllNotificationsAsRead(currentUserID)

                // Update local state
                val updatedNotifications = _notifications.value.map { notification ->
                    notification.copy(isRead = true)
                }

                _notifications.value = updatedNotifications
                _unreadCount.value = 0

                // Refresh filtered notifications
                applyFilter(_selectedFilter.value)

                println("✅ NOTIFICATION VM: Marked all notifications as read")

            } catch (error: Exception) {
                println("❌ NOTIFICATION VM: Failed to mark all notifications as read - ${error.message}")
            }
        }
    }

    /**
     * Handle notification tap action
     */
    fun handleNotificationTap(notification: NotificationItem) {
        viewModelScope.launch {
            // Mark as read
            markAsRead(notification.id)

            // Navigate based on notification type
            when (notification.type) {
                NotificationType.HYPE_RECEIVED, NotificationType.REPLY_RECEIVED -> {
                    // Navigate to video using deep link pattern
                    val videoId = notification.actionData["videoId"] as? String
                    if (videoId != null) {
                        navigationCoordinator.navigateTo(NavigationDestination.VideoDetail(videoId))
                    }
                }
                NotificationType.NEW_FOLLOWER -> {
                    // Navigate to user profile using deep link pattern
                    val userId = notification.actionData["fromUser"] as? String
                    if (userId != null) {
                        navigationCoordinator.navigateTo(NavigationDestination.UserProfile(userId))
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