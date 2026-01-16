/*
 * AnnouncementService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Announcement service for platform-wide mandatory content
 * Handles Firebase Firestore operations and announcement state management
 * UPDATED: Support for repeating announcements with frequency control
 *
 * Port from iOS Swift version
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import com.stitchsocial.club.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * Singleton service for managing platform announcements
 */
class AnnouncementService private constructor() {

    companion object {
        val shared: AnnouncementService by lazy { AnnouncementService() }

        // Database name for Stitch Social
        private const val DATABASE_NAME = "stitchfin"
    }

    // MARK: - Published State

    private val _pendingAnnouncements = MutableStateFlow<List<Announcement>>(emptyList())
    val pendingAnnouncements: StateFlow<List<Announcement>> = _pendingAnnouncements.asStateFlow()

    private val _currentAnnouncement = MutableStateFlow<Announcement?>(null)
    val currentAnnouncement: StateFlow<Announcement?> = _currentAnnouncement.asStateFlow()

    private val _isShowingAnnouncement = MutableStateFlow(false)
    val isShowingAnnouncement: StateFlow<Boolean> = _isShowingAnnouncement.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // MARK: - Firebase References

    private val db: FirebaseFirestore by lazy {
        println("🔥 FIRESTORE: Connecting to NAMED database: stitchfin")
        val firebaseApp = com.google.firebase.FirebaseApp.getInstance()
        com.google.firebase.firestore.FirebaseFirestore.getInstance(firebaseApp, "stitchfin").apply {
            println("🔥 FIRESTORE: App name = ${app.name}")
            println("🔥 FIRESTORE: Project ID = ${app.options.projectId}")
            println("🔥 FIRESTORE: Database = stitchfin (NAMED DATABASE - SAME AS iOS)")
        }
    }

    private val announcementsCollection by lazy {
        println("📁 COLLECTION: Accessing announcements collection")
        db.collection("announcements")
    }

    private val userStatusCollection by lazy {
        db.collection("user_announcement_status")
    }

    // MARK: - Authorized Creators

    private val authorizedCreatorEmails = setOf(
        "developers@stitchsocial.me",
        "james@stitchsocial.me"
    )

    init {
        println("📢 ANNOUNCEMENT SERVICE: Initialized with repeat support")
    }

    // MARK: - Fetch Announcements (with repeat logic)

    /**
     * Fetch pending announcements for a user
     */
    suspend fun fetchPendingAnnouncements(
        userId: String,
        userTier: String,
        accountAge: Int
    ): List<Announcement> {
        _isLoading.value = true
        println("📢 FETCH: Querying collection with: isActive = true")

        try {
            println("📢 FETCH: Looking for announcements for user $userId")
            println("📢 ========================================")
            println("📢 DIAGNOSTIC VERSION 3.0 - JAN 15 11:30AM")
            println("📢 ========================================")
            // ✅ DIAGNOSTIC: Check if ANY documents exist in collection (no filters)
            try {
                println("📢 DIAGNOSTIC: Checking if announcements collection has ANY documents...")
                val allDocsSnapshot = announcementsCollection.limit(10).get().await()
                println("📢 DIAGNOSTIC: Total documents in collection (any status): ${allDocsSnapshot.documents.size}")
                if (allDocsSnapshot.documents.isNotEmpty()) {
                    allDocsSnapshot.documents.forEachIndexed { index, doc ->
                        println("📢 DIAGNOSTIC: Doc $index: id=${doc.id}, isActive=${doc.get("isActive")}")
                    }
                }
            } catch (diagE: Exception) {
                println("📢 DIAGNOSTIC: ❌ Query failed: ${diagE.message}")
                diagE.printStackTrace()
            }


            val snapshot = announcementsCollection
                .whereEqualTo("isActive", true)
                .get()
                .await()

            println("📢 FETCH: Found ${snapshot.documents.size} active announcements")

            // ✅ DEBUG: Log each document found
            snapshot.documents.forEachIndexed { index, doc ->
                println("📢 FETCH: Document $index: id=${doc.id}, isActive=${doc.getBoolean("isActive")}, title=${doc.getString("title")}")
                println("📢 FETCH:   creatorEmail=${doc.getString("creatorEmail")}, targetAudience=${doc.get("targetAudience")}")
                println("📢 FETCH:   startDate=${doc.getTimestamp("startDate")}, endDate=${doc.getTimestamp("endDate")}")
            }

            val activeAnnouncements = mutableListOf<Announcement>()

            for (doc in snapshot.documents) {
                try {
                    println("📢 FETCH: Parsing document ${doc.id}...")
                    val announcement = doc.toObject<Announcement>() ?: continue
                    println("📢 FETCH: Successfully parsed announcement: " + announcement.title)

                    // Check if still active (not expired)
                    if (!announcement.isCurrentlyActive) {
                        println("📢 FETCH: ⏭️ Skipping '${announcement.title}' - not currently active")
                        continue
                    }

                    // Check if user is in target audience
                    if (!isUserInAudience(announcement.audienceEnum, userTier, accountAge, userId)) {
                        println("📢 FETCH: ⏭️ Skipping '${announcement.title}' - user not in target audience")
                        continue
                    }

                    // Get user's status for this announcement
                    val status = getUserStatus(userId, announcement.id)

                    // Check if user can see this announcement based on repeat rules
                    if (canShowAnnouncement(announcement, status)) {
                        println("📢 FETCH: ✅ Adding '${announcement.title}' to pending list")
                        activeAnnouncements.add(announcement)
                    } else {
                        println("📢 FETCH: ⏭️ Skipping '${announcement.title}' - repeat rules not met")
                    }

                } catch (e: Exception) {
                    println("📢 FETCH: ❌ Failed to decode announcement ${doc.id}: ${e.message}")
                }
            }

            // Sort by priority
            val sorted = activeAnnouncements.sortedBy { it.priorityEnum.sortOrder }
            _pendingAnnouncements.value = sorted

            println("📢 FETCH: Final pending count = ${sorted.size}")

            return sorted

        } catch (e: Exception) {
            println("📢 FETCH: ❌ Query FAILED: ${e.message}")
            throw e
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Can Show Announcement (Repeat Logic)

    private fun canShowAnnouncement(announcement: Announcement, status: UserAnnouncementStatus?): Boolean {
        val now = Date()

        // If no status, user has never seen it - show it
        if (status == null) {
            println("📢 REPEAT: No status - first time showing")
            return true
        }

        // Check if permanently dismissed
        if (status.permanentlyDismissed) {
            println("📢 REPEAT: Permanently dismissed - skip")
            return false
        }

        // Handle based on repeat mode
        return when (announcement.repeatModeEnum) {
            AnnouncementRepeatMode.ONCE -> {
                val canShow = status.completedAt == null
                println("📢 REPEAT [once]: completed=${status.completedAt != null}, canShow=$canShow")
                canShow
            }
            AnnouncementRepeatMode.DAILY -> canShowDaily(announcement, status, now)
            AnnouncementRepeatMode.SCHEDULED -> canShowScheduled(announcement, status, now)
            AnnouncementRepeatMode.PERSISTENT -> canShowPersistent(announcement, status, now)
        }
    }

    /**
     * Check if daily repeat announcement can be shown
     */
    private fun canShowDaily(
        announcement: Announcement,
        status: UserAnnouncementStatus,
        now: Date
    ): Boolean {
        // Check lifetime cap
        val maxTotal = announcement.maxTotalShows
        if (maxTotal != null && status.totalShowCount >= maxTotal) {
            println("📢 REPEAT [daily]: Lifetime cap reached (${status.totalShowCount}/$maxTotal)")
            return false
        }

        // Check if it's a new day
        val calendar = Calendar.getInstance()
        calendar.time = now
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val todayStart = calendar.time

        var showsTodayCount = status.showsToday

        // Reset daily count if it's a new day
        status.showsTodayDate?.let { lastDate ->
            val lastDateStart = Calendar.getInstance().apply {
                time = lastDate.toDate()
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time

            if (todayStart > lastDateStart) {
                showsTodayCount = 0 // New day, reset count
                println("📢 REPEAT [daily]: New day - resetting daily count")
            }
        }

        // Check daily limit
        if (showsTodayCount >= announcement.maxDailyShows) {
            println("📢 REPEAT [daily]: Daily limit reached ($showsTodayCount/${announcement.maxDailyShows})")
            return false
        }

        // Check minimum time between shows
        if (announcement.minHoursBetweenShows > 0) {
            status.lastShownAt?.let { lastShown ->
                val hoursSinceLastShow = (now.time - lastShown.toDate().time) / (1000.0 * 60 * 60)
                if (hoursSinceLastShow < announcement.minHoursBetweenShows) {
                    println("📢 REPEAT [daily]: Too soon - ${String.format("%.1f", hoursSinceLastShow)}h since last show (min: ${announcement.minHoursBetweenShows}h)")
                    return false
                }
            }
        }

        println("📢 REPEAT [daily]: ✅ Can show (today: $showsTodayCount/${announcement.maxDailyShows})")
        return true
    }

    /**
     * Check if scheduled repeat announcement can be shown
     */
    private fun canShowScheduled(
        announcement: Announcement,
        status: UserAnnouncementStatus,
        now: Date
    ): Boolean {
        // Check lifetime cap
        val maxTotal = announcement.maxTotalShows
        if (maxTotal != null && status.totalShowCount >= maxTotal) {
            println("📢 REPEAT [scheduled]: Lifetime cap reached")
            return false
        }

        // Check minimum time between shows
        status.lastShownAt?.let { lastShown ->
            val hoursSinceLastShow = (now.time - lastShown.toDate().time) / (1000.0 * 60 * 60)
            if (hoursSinceLastShow < announcement.minHoursBetweenShows) {
                println("📢 REPEAT [scheduled]: Too soon - ${String.format("%.1f", hoursSinceLastShow)}h < ${announcement.minHoursBetweenShows}h")
                return false
            }
        }

        println("📢 REPEAT [scheduled]: ✅ Can show")
        return true
    }

    /**
     * Check if persistent announcement can be shown
     */
    private fun canShowPersistent(
        announcement: Announcement,
        status: UserAnnouncementStatus,
        now: Date
    ): Boolean {
        // Persistent announcements always show, but respect daily/hourly limits

        // Check daily limit if set
        if (announcement.maxDailyShows > 0) {
            val calendar = Calendar.getInstance()
            calendar.time = now
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val todayStart = calendar.time

            var showsTodayCount = status.showsToday

            status.showsTodayDate?.let { lastDate ->
                val lastDateStart = Calendar.getInstance().apply {
                    time = lastDate.toDate()
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time

                if (todayStart > lastDateStart) {
                    showsTodayCount = 0
                }
            }

            if (showsTodayCount >= announcement.maxDailyShows) {
                println("📢 REPEAT [persistent]: Daily limit reached")
                return false
            }
        }

        // Check minimum time between shows
        if (announcement.minHoursBetweenShows > 0) {
            status.lastShownAt?.let { lastShown ->
                val hoursSinceLastShow = (now.time - lastShown.toDate().time) / (1000.0 * 60 * 60)
                if (hoursSinceLastShow < announcement.minHoursBetweenShows) {
                    println("📢 REPEAT [persistent]: Too soon")
                    return false
                }
            }
        }

        println("📢 REPEAT [persistent]: ✅ Can show")
        return true
    }

    /**
     * Check if user is in target audience
     */
    private fun isUserInAudience(
        audience: AnnouncementAudience,
        userTier: String,
        accountAge: Int,
        userId: String
    ): Boolean {
        return when (audience) {
            is AnnouncementAudience.All -> true
            is AnnouncementAudience.NewUsers -> accountAge <= audience.daysOld
            is AnnouncementAudience.TierAndAbove -> {
                val tierOrder = mapOf(
                    "rookie" to 0,
                    "regular" to 1,
                    "ambassador" to 2,
                    "topcreator" to 3,
                    "admin" to 4
                )
                val userTierOrder = tierOrder[userTier.lowercase()] ?: 0
                val minTierOrder = tierOrder[audience.tier.lowercase()] ?: 0
                userTierOrder >= minTierOrder
            }
            is AnnouncementAudience.TierOnly -> userTier.lowercase() == audience.tier.lowercase()
            is AnnouncementAudience.SpecificUsers -> audience.userIds.contains(userId)
        }
    }

    // MARK: - User Status Management

    /**
     * Get user's status for an announcement
     */
    suspend fun getUserStatus(userId: String, announcementId: String): UserAnnouncementStatus? {
        val statusId = "${userId}_${announcementId}"

        return try {
            val doc = userStatusCollection.document(statusId).get().await()
            if (doc.exists()) {
                doc.toObject<UserAnnouncementStatus>()
            } else {
                null
            }
        } catch (e: Exception) {
            println("📢 STATUS: Error getting status for $statusId: ${e.message}")
            null
        }
    }

    /**
     * Mark announcement as seen
     */
    suspend fun markAsSeen(userId: String, announcementId: String) {
        val statusId = "${userId}_${announcementId}"
        val now = Date()
        val calendar = Calendar.getInstance().apply { time = now }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val todayStart = calendar.time

        val existing = getUserStatus(userId, announcementId)

        if (existing != null) {
            // Update existing status
            var newShowsToday = existing.showsToday
            var showsTodayDate = existing.showsTodayDate?.toDate() ?: todayStart

            // Reset daily count if new day
            existing.showsTodayDate?.let { lastDate ->
                val lastDateStart = Calendar.getInstance().apply {
                    time = lastDate.toDate()
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time

                if (todayStart > lastDateStart) {
                    newShowsToday = 0
                    showsTodayDate = todayStart
                }
            }

            userStatusCollection.document(statusId).update(
                mapOf(
                    "totalShowCount" to FieldValue.increment(1),
                    "lastShownAt" to Timestamp(now),
                    "showsToday" to newShowsToday + 1,
                    "showsTodayDate" to Timestamp(showsTodayDate),
                    "showTimestamps" to FieldValue.arrayUnion(Timestamp(now))
                )
            ).await()

            println("📢 STATUS: Updated show count for $statusId")
        } else {
            // Create new status
            val status = UserAnnouncementStatus.create(userId, announcementId, now)
            userStatusCollection.document(statusId).set(status).await()
            println("📢 STATUS: Created new status for $statusId")
        }
    }

    /**
     * Mark announcement as completed
     */
    suspend fun markAsCompleted(userId: String, announcementId: String, watchedSeconds: Int) {
        val statusId = "${userId}_${announcementId}"
        val now = Date()
        val calendar = Calendar.getInstance().apply { time = now }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val todayStart = calendar.time

        val existing = getUserStatus(userId, announcementId)

        val updateData = mutableMapOf<String, Any>(
            "visibilityId" to statusId,
            "userId" to userId,
            "announcementId" to announcementId,
            "completedAt" to Timestamp(now),
            "watchedSeconds" to watchedSeconds,
            "lastShownAt" to Timestamp(now)
        )

        if (existing == null) {
            // First time - set initial values
            updateData["firstSeenAt"] = Timestamp(now)
            updateData["totalShowCount"] = 1
            updateData["showsToday"] = 1
            updateData["showsTodayDate"] = Timestamp(todayStart)
            updateData["showTimestamps"] = listOf(Timestamp(now))
            updateData["permanentlyDismissed"] = false
        } else {
            // Update existing - increment counts handled separately
            var newShowsToday = existing.showsToday
            existing.showsTodayDate?.let { lastDate ->
                val lastDateStart = Calendar.getInstance().apply {
                    time = lastDate.toDate()
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time

                if (todayStart > lastDateStart) {
                    newShowsToday = 0
                }
            }
            updateData["showsToday"] = newShowsToday + 1
            updateData["showsTodayDate"] = Timestamp(todayStart)
            updateData["totalShowCount"] = existing.totalShowCount + 1
            updateData["showTimestamps"] = existing.showTimestamps + Timestamp(now)
        }

        userStatusCollection.document(statusId).set(updateData, com.google.firebase.firestore.SetOptions.merge()).await()

        println("📢 STATUS: Marked as completed - $statusId")

        // Remove from pending list
        val updatedPending = _pendingAnnouncements.value.filter { it.id != announcementId }
        _pendingAnnouncements.value = updatedPending

        // Check if there are more announcements
        if (updatedPending.isEmpty()) {
            println("📢 STATUS: No more announcements, closing overlay")
            _isShowingAnnouncement.value = false
            _currentAnnouncement.value = null
        } else {
            println("📢 STATUS: ${updatedPending.size} more announcement(s) to show")
            showNextAnnouncementIfNeeded()
        }
    }

    /**
     * Permanently dismiss an announcement (won't show again)
     */
    suspend fun permanentlyDismiss(userId: String, announcementId: String) {
        val statusId = "${userId}_${announcementId}"

        userStatusCollection.document(statusId).set(
            mapOf(
                "visibilityId" to statusId,
                "userId" to userId,
                "announcementId" to announcementId,
                "permanentlyDismissed" to true,
                "dismissedAt" to Timestamp(Date())
            ),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()

        println("📢 STATUS: Permanently dismissed - $statusId")

        // Remove from pending list
        val updatedPending = _pendingAnnouncements.value.filter { it.id != announcementId }
        _pendingAnnouncements.value = updatedPending

        if (updatedPending.isEmpty()) {
            _isShowingAnnouncement.value = false
            _currentAnnouncement.value = null
        } else {
            showNextAnnouncementIfNeeded()
        }
    }

    /**
     * Regular dismiss (marks as completed for this session)
     */
    suspend fun dismissAnnouncement(userId: String, announcementId: String) {
        markAsCompleted(userId, announcementId, 0)
    }

    // MARK: - Display Logic

    /**
     * Show next pending announcement if available
     */
    fun showNextAnnouncementIfNeeded() {
        val pending = _pendingAnnouncements.value

        if (pending.isEmpty()) {
            println("📢 DISPLAY: No pending announcements")
            _isShowingAnnouncement.value = false
            _currentAnnouncement.value = null
            return
        }

        _currentAnnouncement.value = pending.first()
        _isShowingAnnouncement.value = true
        println("📢 DISPLAY: Showing announcement '${_currentAnnouncement.value?.title ?: "unknown"}'")
    }

    /**
     * Check and show announcements on app launch
     */
    suspend fun checkForCriticalAnnouncements(userId: String, userTier: String, accountAge: Int) {
        println("📢 CHECK: Starting announcement check for user $userId")

        try {
            val pending = fetchPendingAnnouncements(userId, userTier, accountAge)

            println("📢 CHECK: Found ${pending.size} pending announcements")

            if (pending.isNotEmpty()) {
                val first = pending.first()
                println("📢 CHECK: ✅ Will show '${first.title}' (repeat mode: ${first.repeatMode})")
                _currentAnnouncement.value = first
                _isShowingAnnouncement.value = true
            } else {
                println("📢 CHECK: No announcements to show")
            }
        } catch (e: Exception) {
            println("❌ CHECK: Error checking announcements: ${e.message}")
        }
    }

    // MARK: - Admin: Create Announcement

    /**
     * Create a new announcement (admin only)
     */
    suspend fun createAnnouncement(
        videoId: String,
        creatorEmail: String,
        creatorId: String,
        title: String,
        message: String? = null,
        priority: AnnouncementPriority = AnnouncementPriority.STANDARD,
        type: AnnouncementType = AnnouncementType.UPDATE,
        targetAudience: AnnouncementAudience = AnnouncementAudience.All,
        startDate: Date = Date(),
        endDate: Date? = null,
        minimumWatchSeconds: Int = 5,
        isDismissable: Boolean = true,
        requiresAcknowledgment: Boolean = false,
        repeatMode: AnnouncementRepeatMode = AnnouncementRepeatMode.ONCE,
        maxDailyShows: Int = 1,
        minHoursBetweenShows: Double = 0.0,
        maxTotalShows: Int? = null
    ): Announcement {
        println("📢 CREATE: Attempting to create announcement")
        println("📢 CREATE: Creator email = $creatorEmail")
        println("📢 CREATE: Repeat mode = ${repeatMode.value}")

        if (!authorizedCreatorEmails.contains(creatorEmail.lowercase())) {
            println("📢 CREATE: ❌ Unauthorized creator: $creatorEmail")
            throw AnnouncementError.UnauthorizedCreator
        }

        val announcement = Announcement.create(
            videoId = videoId,
            creatorId = creatorId,
            title = title,
            message = message,
            priority = priority,
            type = type,
            targetAudience = targetAudience,
            startDate = startDate,
            endDate = endDate,
            minimumWatchSeconds = minimumWatchSeconds,
            isDismissable = isDismissable,
            requiresAcknowledgment = requiresAcknowledgment,
            repeatMode = repeatMode,
            maxDailyShows = maxDailyShows,
            minHoursBetweenShows = minHoursBetweenShows,
            maxTotalShows = maxTotalShows
        )

        announcementsCollection.document(announcement.id).set(announcement).await()

        println("✅ ANNOUNCEMENT: Created '${title}' with id ${announcement.id}")
        println("✅ ANNOUNCEMENT: Repeat=${repeatMode.value}, MaxDaily=$maxDailyShows, MinHours=$minHoursBetweenShows")

        return announcement
    }

    /**
     * Deactivate an announcement
     */
    suspend fun deactivateAnnouncement(announcementId: String, creatorEmail: String) {
        if (!authorizedCreatorEmails.contains(creatorEmail.lowercase())) {
            throw AnnouncementError.UnauthorizedCreator
        }

        announcementsCollection.document(announcementId).update(
            mapOf(
                "isActive" to false,
                "updatedAt" to Timestamp(Date())
            )
        ).await()

        println("📕 Deactivated announcement: $announcementId")
    }

    /**
     * Get all announcements (for admin)
     */
    suspend fun getAllAnnouncements(): List<Announcement> {
        val snapshot = announcementsCollection
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .await()

        return snapshot.documents.mapNotNull { it.toObject<Announcement>() }
    }

    // MARK: - Analytics

    /**
     * Get view statistics for an announcement
     */
    suspend fun getAnnouncementStats(announcementId: String): AnnouncementStats {
        val snapshot = userStatusCollection
            .whereEqualTo("announcementId", announcementId)
            .get()
            .await()

        var totalViews = 0
        var uniqueViewers = 0
        var completedCount = 0
        var permanentDismissals = 0

        for (doc in snapshot.documents) {
            uniqueViewers += 1
            doc.toObject<UserAnnouncementStatus>()?.let { status ->
                totalViews += status.totalShowCount
                if (status.hasCompleted) completedCount += 1
                if (status.permanentlyDismissed) permanentDismissals += 1
            }
        }

        return AnnouncementStats(
            announcementId = announcementId,
            totalViews = totalViews,
            uniqueViewers = uniqueViewers,
            completedCount = completedCount,
            permanentDismissals = permanentDismissals
        )
    }

    /**
     * Hide announcement overlay (for internal use)
     */
    fun hideAnnouncement() {
        _isShowingAnnouncement.value = false
        _currentAnnouncement.value = null
    }
}

// MARK: - Announcement Video Helper

/**
 * Helper for creating announcements from videos
 */
object AnnouncementVideoHelper {

    private val authorizedEmails = setOf(
        "developers@stitchsocial.me",
        "james@stitchsocial.me"
    )

    fun canCreateAnnouncement(email: String): Boolean {
        return authorizedEmails.contains(email.lowercase())
    }

    /**
     * Create a one-time announcement (original behavior)
     */
    suspend fun createAnnouncementFromVideo(
        videoId: String,
        creatorEmail: String,
        creatorId: String,
        title: String,
        message: String? = null,
        priority: AnnouncementPriority = AnnouncementPriority.STANDARD,
        type: AnnouncementType = AnnouncementType.UPDATE,
        minimumWatchSeconds: Int = 5
    ): Announcement {
        return AnnouncementService.shared.createAnnouncement(
            videoId = videoId,
            creatorEmail = creatorEmail,
            creatorId = creatorId,
            title = title,
            message = message,
            priority = priority,
            type = type,
            targetAudience = AnnouncementAudience.All,
            minimumWatchSeconds = minimumWatchSeconds,
            repeatMode = AnnouncementRepeatMode.ONCE,
            maxDailyShows = 1,
            minHoursBetweenShows = 0.0,
            maxTotalShows = 1
        )
    }

    /**
     * Create a repeating event announcement
     * Perfect for events that are weeks/months away
     */
    suspend fun createEventAnnouncement(
        videoId: String,
        creatorEmail: String,
        creatorId: String,
        title: String,
        message: String? = null,
        eventDate: Date,
        maxTimesPerDay: Int = 2,
        minHoursBetween: Double = 6.0,
        minimumWatchSeconds: Int = 5
    ): Announcement {
        return AnnouncementService.shared.createAnnouncement(
            videoId = videoId,
            creatorEmail = creatorEmail,
            creatorId = creatorId,
            title = title,
            message = message,
            priority = AnnouncementPriority.HIGH,
            type = AnnouncementType.EVENT,
            targetAudience = AnnouncementAudience.All,
            startDate = Date(),
            endDate = eventDate,
            minimumWatchSeconds = minimumWatchSeconds,
            isDismissable = true,
            requiresAcknowledgment = false,
            repeatMode = AnnouncementRepeatMode.DAILY,
            maxDailyShows = maxTimesPerDay,
            minHoursBetweenShows = minHoursBetween,
            maxTotalShows = null // No lifetime cap - show until event
        )
    }
}