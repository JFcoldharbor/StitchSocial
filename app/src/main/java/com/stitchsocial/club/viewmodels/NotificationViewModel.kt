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

    // MARK: - Navigation Events

    private val _navigationEvent = MutableSharedFlow<NotificationNavigationEvent>(
        replay = 1,
        extraBufferCapacity = 5
    )
    val navigationEvent: SharedFlow<NotificationNavigationEvent> = _navigationEvent.asSharedFlow()

    // MARK: - Profile Image Cache

    private val _profileImages = MutableStateFlow<Map<String, String>>(emptyMap())
    val profileImages: StateFlow<Map<String, String>> = _profileImages.asStateFlow()

    // MARK: - ThreadID Resolution Cache
    // Caches videoID → threadID lookups to avoid repeat Firestore reads
    // when user taps same notification multiple times
    private val threadIDCache = mutableMapOf<String, String>()

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
     * Handle notification tap with proper navigation
     * Matches iOS handleNotificationTap exactly:
     * 1. Follow -> profile
     * 2. Video-related -> try threadID from payload, fallback to video fetch
     * 3. Milestone -> video if available
     *
     * CACHING: threadID lookups cached in threadIDCache to avoid
     * repeat Firestore reads on re-taps of same notification.
     */
    fun onNotificationTapped(notification: NotificationItem) {
        viewModelScope.launch {
            Log.d(TAG, "NOTIF tapped: ${notification.type} | keys: ${notification.actionData.keys}")

            // Mark as read
            markAsRead(notification.id)

            // PAYLOAD-FIRST APPROACH: Check what data exists rather than relying
            // on type mapping (which may fall through to SYSTEM_UPDATE due to
            // StitchNotificationType enum mismatch with Firestore values).
            val videoId = notification.actionData["videoID"] as? String
                ?: notification.actionData["videoId"] as? String
            val hasVideo = !videoId.isNullOrEmpty()

            val senderUserId = notification.actionData["senderID"] as? String
                ?: notification.actionData["userId"] as? String
                ?: notification.actionData["senderId"] as? String

            // Determine intent from BOTH type AND payload
            val isFollowType = notification.type == NotificationType.NEW_FOLLOWER
                    || notification.actionData["engagementType"]?.toString() == "follow"
            val isTierUpgrade = notification.type == NotificationType.TIER_UPGRADED

            when {
                // Follow notification (no video) -> profile
                isFollowType && !hasVideo && senderUserId != null -> {
                    Log.d(TAG, "NAV -> Profile (follow): $senderUserId")
                    _navigationEvent.emit(NotificationNavigationEvent.NavigateToProfile(senderUserId))
                }

                // Has videoID -> navigate to thread (covers hype, cool, reply, share, newVideo, mention, milestone)
                hasVideo -> {
                    val threadId = resolveThreadID(videoId!!, notification.actionData)
                    Log.d(TAG, "NAV -> Thread: $threadId, target: $videoId")
                    _navigationEvent.emit(
                        NotificationNavigationEvent.NavigateToVideo(
                            videoId = videoId,
                            threadId = threadId
                        )
                    )
                }

                // Tier upgrade -> own profile
                isTierUpgrade -> {
                    val userId = getCurrentUserId()
                    if (userId.isNotEmpty()) {
                        Log.d(TAG, "NAV -> Profile (tier): $userId")
                        _navigationEvent.emit(NotificationNavigationEvent.NavigateToProfile(userId))
                    }
                }

                // Has sender but no video -> profile
                senderUserId != null -> {
                    Log.d(TAG, "NAV -> Profile (fallback): $senderUserId")
                    _navigationEvent.emit(NotificationNavigationEvent.NavigateToProfile(senderUserId))
                }

                else -> {
                    Log.w(TAG, "No navigation possible - no videoID or senderID in payload")
                }
            }
        }
    }

    /**
     * Resolve threadID for a video - matches iOS fallback logic:
     * 1. Check payload for threadID
     * 2. Check local cache
     * 3. Fetch video from Firestore to resolve threadID
     * 4. Fallback: use videoID as threadID
     *
     * CACHING: Results stored in threadIDCache (in-memory, cleared on ViewModel destroy)
     */
    private suspend fun resolveThreadID(videoId: String, actionData: Map<String, Any>): String {
        // 1. Try threadID from payload (Cloud Functions now include this)
        val payloadThreadId = actionData["threadID"] as? String
            ?: actionData["threadId"] as? String
        if (!payloadThreadId.isNullOrEmpty()) {
            threadIDCache[videoId] = payloadThreadId
            return payloadThreadId
        }

        // 2. Check local cache
        threadIDCache[videoId]?.let { return it }

        // 3. Fetch video from Firestore to resolve real threadID (matches iOS fallback)
        return try {
            val video = videoService.getVideoById(videoId)
            val resolvedThreadId = video?.threadID ?: video?.id ?: videoId
            threadIDCache[videoId] = resolvedThreadId
            Log.d(TAG, "Resolved threadID from Firestore: $videoId -> $resolvedThreadId")
            resolvedThreadId
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve threadID for $videoId, using fallback")
            videoId
        }
    }

    /**
     * Navigate to a specific user's profile
     */
    fun navigateToProfile(userId: String) {
        viewModelScope.launch {
            Log.d(TAG, "ðŸ“± Direct navigation to profile: $userId")
            _navigationEvent.emit(NotificationNavigationEvent.NavigateToProfile(userId))
        }
    }

    /**
     * Navigate to a specific video/thread
     */
    fun navigateToVideo(videoId: String, threadId: String? = null) {
        viewModelScope.launch {
            Log.d(TAG, "ðŸ“± Direct navigation to video: $videoId")
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
            HYPE_RECEIVED -> "ðŸ”¥"
            REPLY_RECEIVED -> "ðŸ’¬"
            SHARE_RECEIVED -> "ðŸ“¤"
            NEW_FOLLOWER -> "ðŸ‘¤"
            FOLLOWING_VIDEO -> "ðŸŽ¬"
            TAP_MILESTONE -> "ðŸŽ¯"
            TIER_UPGRADED -> "â¬†ï¸"
            SYSTEM_UPDATE -> "â„¹ï¸"
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