/*
 * NotificationViewModel.kt - COMPLETE WITH NAVIGATION EVENTS
 * StitchSocial Android
 *
 * Layer 7: ViewModels - Notification State Management
 * Dependencies: UserService, EngagementCoordinator, NavigationCoordinator, NotificationService, AuthService
 *
 * âœ… COMPLETE: Real Firebase notification loading
 * âœ… COMPLETE: Real-time listener integration
 * âœ… COMPLETE: Proper AuthService user ID retrieval
 * âœ… COMPLETE: Mark as read functionality
 * âœ… FIXED: Navigation events for profile and video navigation
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
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * Notification ViewModel with complete Firebase integration and navigation
 */
class NotificationViewModel(
    private val userService: UserService,
    private val engagementCoordinator: EngagementCoordinator,
    private val navigationCoordinator: NavigationCoordinator,
    private val videoService: VideoServiceImpl,
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

    // MARK: - Profile Image Cache

    private val _profileImages = MutableStateFlow<Map<String, String>>(emptyMap())
    val profileImages: StateFlow<Map<String, String>> = _profileImages.asStateFlow()

    // MARK: - Initialization

    init {
        Log.d(TAG, "ðŸ”” NOTIFICATION VM: Initializing with NotificationService integration")

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
            Log.d(TAG, "ðŸ”” NOTIFICATION VM: Loading notifications from Firebase...")

            val currentUserID = getCurrentUserId()
            if (currentUserID.isEmpty()) {
                Log.w(TAG, "âš ï¸ NOTIFICATION VM: No user ID available")
                _isLoading.value = false
                return
            }

            Log.d(TAG, "ðŸ”” NOTIFICATION VM: Loading for user: $currentUserID")

            // Load notifications from NotificationService
            val result = notificationService.loadNotifications(
                userID = currentUserID,
                limit = 50
            )

            Log.d(TAG, "ðŸ”” NOTIFICATION VM: Received ${result.notifications.size} notifications from service")

            // Convert to NotificationItems
            val notificationItems = result.notifications.map { firebaseNotification ->
                convertToNotificationItem(firebaseNotification)
            }

            _notifications.value = notificationItems
            _unreadCount.value = notificationItems.count { !it.isRead }

            // Apply current filter
            applyFilter(_selectedFilter.value)

            Log.d(TAG, "✅ NOTIFICATION VM: Loaded ${notificationItems.size} notifications (${_unreadCount.value} unread)")

            // Fetch profile images for all senders
            fetchProfileImages(notificationItems)

        } catch (error: Exception) {
            val stitchError = error as? StitchError ?: StitchError.NetworkError(error.message ?: "Unknown error")
            _lastError.value = stitchError
            Log.e(TAG, "âŒ NOTIFICATION VM: Failed to load notifications", error)
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
            Log.w(TAG, "âš ï¸ NOTIFICATION VM: Cannot start listener - no user ID")
            return
        }

        Log.d(TAG, "ðŸ“¡ NOTIFICATION VM: Starting real-time listener for user: $currentUserID")

        notificationService.startListening(
            userID = currentUserID,
            onNotificationsUpdated = { firebaseNotifications ->
                Log.d(TAG, "ðŸ”„ NOTIFICATION VM: Real-time update - ${firebaseNotifications.size} notifications")

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
            rawType = firebaseNotification.rawType,
            actionData = buildMap {
                put("userId", firebaseNotification.senderID)
                put("senderID", firebaseNotification.senderID)
                // Copy all payload data — toString() handles non-String values
                // (e.g. batchCount stored as Long in Firestore)
                firebaseNotification.payload.forEach { (key, value) ->
                    put(key, value.toString())
                }
            }
        )
    }

    /**
     * Map Firebase notification type to app notification type
     */
    private fun mapNotificationType(firebaseType: StitchNotificationType): NotificationType {
        return when (firebaseType) {
            StitchNotificationType.COLLAB_INVITE -> NotificationType.COLLAB_INVITE
            StitchNotificationType.COLLAB_ACCEPTED -> NotificationType.COLLAB_ACCEPTED
            StitchNotificationType.HYPE -> NotificationType.HYPE_RECEIVED
            StitchNotificationType.COOL -> NotificationType.COOL_RECEIVED
            StitchNotificationType.REPLY -> NotificationType.REPLY_RECEIVED
            StitchNotificationType.FOLLOW -> NotificationType.NEW_FOLLOWER
            StitchNotificationType.SHARE -> NotificationType.SHARE_RECEIVED
            StitchNotificationType.MILESTONE -> NotificationType.TAP_MILESTONE
            StitchNotificationType.TIER_UPGRADE -> NotificationType.TIER_UPGRADED
            StitchNotificationType.SYSTEM -> NotificationType.SYSTEM_UPDATE
            StitchNotificationType.QUESTION -> NotificationType.QUESTION_RECEIVED
            StitchNotificationType.TIP -> NotificationType.TIP_RECEIVED
            StitchNotificationType.MENTION -> NotificationType.MENTION
            StitchNotificationType.SPIN_OFF -> NotificationType.SPIN_OFF
            StitchNotificationType.GO_LIVE -> NotificationType.GO_LIVE
            StitchNotificationType.BADGE -> NotificationType.BADGE_EARNED
            StitchNotificationType.STREAK -> NotificationType.STREAK
            StitchNotificationType.SUBSCRIPTION -> NotificationType.SUBSCRIPTION
            StitchNotificationType.NEW_VIDEO -> NotificationType.NEW_VIDEO
            StitchNotificationType.RSVP -> NotificationType.RSVP
            StitchNotificationType.COMMUNITY_POST -> NotificationType.NEW_VIDEO
            StitchNotificationType.COMMUNITY_XP -> NotificationType.TAP_MILESTONE
        }
    }

    /**
     * Fetch profile images for notification senders
     */
    private fun fetchProfileImages(notifications: List<NotificationItem>) {
        val cached = _profileImages.value
        val senderIds = notifications.mapNotNull { notification ->
            (notification.actionData["senderID"] as? String)?.takeIf { it.isNotEmpty() }
        }.distinct().filter { !cached.containsKey(it) }

        if (senderIds.isEmpty()) return

        viewModelScope.launch {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance(
                com.google.firebase.FirebaseApp.getInstance(), "stitchfin"
            )
            val imageMap = _profileImages.value.toMutableMap()

            // Fetch in batches of 10 (Firestore whereIn limit)
            for (batch in senderIds.chunked(10)) {
                try {
                    val snapshot = db.collection("users")
                        .whereIn(com.google.firebase.firestore.FieldPath.documentId(), batch)
                        .get()
                        .await()

                    for (doc in snapshot.documents) {
                        val imageUrl = doc.getString("profileImageURL")
                        if (!imageUrl.isNullOrEmpty()) {
                            imageMap[doc.id] = imageUrl
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch profile images: ${e.message}")
                }
            }

            _profileImages.value = imageMap
            Log.d(TAG, "Fetched ${imageMap.size} profile images")
        }
    }

    // MARK: - Notification Actions

    /**
     * Handle a notification row tap.
     *
     * Hands the raw type + payload to [NotificationRouter] — the SAME hand-off a
     * tapped push uses — so the row and the push land in the same place by
     * construction. This used to run its own resolver ladder plus a threadID
     * lookup, and drifted from the push path more than once.
     */
    fun onNotificationTapped(notification: NotificationItem) {
        viewModelScope.launch {
            Log.d(TAG, "NOTIF tapped: ${notification.rawType} | keys: ${notification.actionData.keys}")
            markAsRead(notification.id)
            NotificationRouter.request(
                notification.rawType.ifBlank { notification.type.name.lowercase() },
                notification.actionData.mapValues { it.value.toString() }
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

            Log.d(TAG, "âœ… Marking notification as read: $notificationId")

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
            Log.e(TAG, "âŒ Failed to mark as read", error)
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

                Log.d(TAG, "âœ… Marking all notifications as read")

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

                Log.d(TAG, "âœ… All notifications marked as read")

            } catch (error: Exception) {
                Log.e(TAG, "âŒ Failed to mark all as read", error)
            }
        }
    }

    /**
     * Set notification filter
     */
    fun setFilter(filter: NotificationFilter) {
        _selectedFilter.value = filter
        applyFilter(filter)
        Log.d(TAG, "ðŸ” Filter set to ${filter.displayName}")
    }

    /**
     * Apply filter to notifications
     */
    private fun applyFilter(filter: NotificationFilter) {
        val notifications = _notifications.value

        val filtered = when (filter) {
            NotificationFilter.ALL -> notifications
            // Bucketed by an EXHAUSTIVE `when` (see NotificationType.bucket)
            // rather than three hand-maintained allow-lists.
            //
            // The lists were the reason a tip vanished under every tab except
            // All and Unread: a type absent from all three lists belongs to no
            // category, and nothing complains. With a `when`, adding a type
            // fails to compile until someone decides where it goes.
            NotificationFilter.ENGAGEMENT,
            NotificationFilter.SOCIAL,
            NotificationFilter.SYSTEM -> notifications.filter { it.type.bucket == filter }
            NotificationFilter.UNREAD -> notifications.filter { !it.isRead }
        }

        _filteredNotifications.value = filtered
        Log.d(TAG, "ðŸ” Filtered to ${filtered.size} notifications")
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
            Log.w(TAG, "âš ï¸ No authenticated user found")
        }
        return userId
    }

    // MARK: - Cleanup

    override fun onCleared() {
        super.onCleared()
        notificationService.stopListening()
        Log.d(TAG, "ðŸ§¹ NOTIFICATION VM: Cleaned up resources")
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
    val actionData: Map<String, Any> = emptyMap(),
    /** The stored type string, for routing (see NotificationDestination.resolve). */
    val rawType: String = ""
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
    SYSTEM_UPDATE,
    QUESTION_RECEIVED,
    // Was folded into HYPE_RECEIVED, discarding the distinction the backend
    // sends deliberately (engagementType on an "engagement" notification).
    COOL_RECEIVED,
    // TIP IS MONEY. It fell through to SYSTEM_UPDATE, so someone sending you
    // coins looked exactly like an app announcement.
    TIP_RECEIVED,
    MENTION,
    SPIN_OFF,
    GO_LIVE,
    BADGE_EARNED,
    STREAK,
    SUBSCRIPTION,
    NEW_VIDEO,
    RSVP,
    COLLAB_INVITE,
    COLLAB_ACCEPTED;

    val emoji: String
        get() = when (this) {
            COLLAB_INVITE, COLLAB_ACCEPTED -> "\uD83E\uDD1D"
            HYPE_RECEIVED -> "ðŸ”¥"
            REPLY_RECEIVED -> "ðŸ’¬"
            SHARE_RECEIVED -> "ðŸ“¤"
            NEW_FOLLOWER -> "ðŸ‘¤"
            FOLLOWING_VIDEO -> "ðŸŽ¬"
            TAP_MILESTONE -> "ðŸŽ¯"
            TIER_UPGRADED -> "â¬†ï¸"
            SYSTEM_UPDATE -> "â„¹ï¸"
            QUESTION_RECEIVED -> "❓"
            COOL_RECEIVED -> "\u2744\uFE0F"
            TIP_RECEIVED -> "\uD83E\uDE99"
            MENTION -> "@"
            SPIN_OFF -> "\uD83C\uDF00"
            GO_LIVE -> "\uD83D\uDD34"
            BADGE_EARNED -> "\uD83C\uDFC5"
            STREAK -> "\uD83D\uDD25"
            SUBSCRIPTION -> "\u2B50"
            NEW_VIDEO -> "\uD83C\uDFAC"
            RSVP -> "\uD83D\uDCC5"
        }
}

/**
 * Which filter tab a notification belongs under.
 *
 * Exhaustive on purpose: every type must name a bucket, so a new one can't
 * silently disappear from the tabs the way TIP did.
 */
val NotificationType.bucket: NotificationFilter
    get() = when (this) {
        // Someone reacted to, replied to, or paid for your work.
        NotificationType.HYPE_RECEIVED,
        NotificationType.COOL_RECEIVED,
        NotificationType.REPLY_RECEIVED,
        NotificationType.SHARE_RECEIVED,
        NotificationType.TAP_MILESTONE,
        NotificationType.MENTION,
        NotificationType.SPIN_OFF,
        NotificationType.QUESTION_RECEIVED,
        NotificationType.STREAK,
        NotificationType.TIP_RECEIVED,
        NotificationType.SUBSCRIPTION -> NotificationFilter.ENGAGEMENT

        // People you follow, doing things.
        NotificationType.NEW_FOLLOWER,
        NotificationType.FOLLOWING_VIDEO,
        NotificationType.NEW_VIDEO,
        NotificationType.GO_LIVE,
        NotificationType.COLLAB_INVITE,
        NotificationType.COLLAB_ACCEPTED,
        NotificationType.RSVP -> NotificationFilter.SOCIAL

        // The app talking to you about your account.
        NotificationType.TIER_UPGRADED,
        NotificationType.BADGE_EARNED,
        NotificationType.SYSTEM_UPDATE -> NotificationFilter.SYSTEM
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
