/*
 * Announcement.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Announcement system for platform-wide mandatory content
 * UPDATED: Support for repeating announcements with frequency control
 * - Announcements can repeat multiple times
 * - Max shows per day (e.g., 2 times daily)
 * - Min hours between shows (e.g., 6 hours apart)
 * - Lifetime max shows (optional cap)
 * - Perfect for event announcements that run for weeks/months
 *
 * Port from iOS Swift version
 */

package com.stitchsocial.club.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import java.util.*

// MARK: - Announcement Model

/**
 * Represents a platform announcement that users must see
 */
data class Announcement(
    val id: String = "",
    val videoId: String = "",
    val creatorId: String = "",
    val title: String = "",
    val message: String? = null,
    val priority: String = AnnouncementPriority.STANDARD.value,
    val type: String = AnnouncementType.UPDATE.value,
    val targetAudience: Any? = null,  // Can be String or Map - handle both formats
    val startDate: Timestamp = Timestamp.now(),
    val endDate: Timestamp? = null,
    val minimumWatchSeconds: Int = 5,
    val isDismissable: Boolean = true,
    val requiresAcknowledgment: Boolean = false,
    val createdAt: Timestamp = Timestamp.now(),
    val updatedAt: Timestamp = Timestamp.now(),
    val isActive: Boolean = true,

    // MARK: - Repeat/Frequency Settings

    /** How the announcement repeats */
    val repeatMode: String = AnnouncementRepeatMode.ONCE.value,

    /** Maximum times to show per day (e.g., 2) */
    val maxDailyShows: Int = 1,

    /** Minimum hours between shows (e.g., 6.0 = 6 hours apart) */
    val minHoursBetweenShows: Double = 0.0,

    /** Lifetime cap on total shows (null = unlimited until endDate) */
    val maxTotalShows: Int? = null
) {
    // MARK: - Computed Properties

    val priorityEnum: AnnouncementPriority
        get() = AnnouncementPriority.fromValue(priority)

    val typeEnum: AnnouncementType
        get() = AnnouncementType.fromValue(type)

    val repeatModeEnum: AnnouncementRepeatMode
        get() = AnnouncementRepeatMode.fromValue(repeatMode)

    val audienceEnum: AnnouncementAudience
        get() = when (targetAudience) {
            is String -> when (targetAudience.lowercase()) {
                "all" -> AnnouncementAudience.All
                else -> AnnouncementAudience.All
            }
            is Map<*, *> -> AnnouncementAudience.fromMap(targetAudience as Map<String, Any>)
            else -> AnnouncementAudience.All
        }

    val isCurrentlyActive: Boolean
        get() {
            val now = Date()
            val afterStart = now >= startDate.toDate()
            val beforeEnd = endDate == null || now <= endDate.toDate()
            return isActive && afterStart && beforeEnd
        }

    val isExpired: Boolean
        get() {
            val end = endDate ?: return false
            return Date() > end.toDate()
        }

    val isRepeating: Boolean
        get() = repeatModeEnum != AnnouncementRepeatMode.ONCE

    companion object {
        /**
         * Create an Announcement with all parameters (for convenience)
         */
        fun create(
            id: String = UUID.randomUUID().toString(),
            videoId: String,
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
            val now = Date()
            return Announcement(
                id = id,
                videoId = videoId,
                creatorId = creatorId,
                title = title,
                message = message,
                priority = priority.value,
                type = type.value,
                targetAudience = targetAudience.toMap(),
                startDate = Timestamp(startDate),
                endDate = endDate?.let { Timestamp(it) },
                minimumWatchSeconds = minimumWatchSeconds,
                isDismissable = isDismissable,
                requiresAcknowledgment = requiresAcknowledgment,
                createdAt = Timestamp(now),
                updatedAt = Timestamp(now),
                isActive = true,
                repeatMode = repeatMode.value,
                maxDailyShows = maxDailyShows,
                minHoursBetweenShows = minHoursBetweenShows,
                maxTotalShows = maxTotalShows
            )
        }
    }
}

// MARK: - Announcement Repeat Mode

enum class AnnouncementRepeatMode(val value: String) {
    /** Show only once, ever */
    ONCE("once"),

    /** Show daily (up to maxDailyShows per day) */
    DAILY("daily"),

    /** Show on specific schedule (respects minHoursBetweenShows) */
    SCHEDULED("scheduled"),

    /** Show until user explicitly stops it (for critical/ongoing events) */
    PERSISTENT("persistent");

    val displayName: String
        get() = when (this) {
            ONCE -> "One Time"
            DAILY -> "Daily"
            SCHEDULED -> "Scheduled"
            PERSISTENT -> "Persistent"
        }

    val description: String
        get() = when (this) {
            ONCE -> "Show once, then never again"
            DAILY -> "Show up to X times per day"
            SCHEDULED -> "Show with minimum time between views"
            PERSISTENT -> "Keep showing until event ends"
        }

    companion object {
        fun fromValue(value: String): AnnouncementRepeatMode {
            return entries.find { it.value == value } ?: ONCE
        }
    }
}

// MARK: - Announcement Priority

enum class AnnouncementPriority(val value: String) {
    CRITICAL("critical"),
    HIGH("high"),
    STANDARD("standard"),
    LOW("low");

    val displayName: String
        get() = when (this) {
            CRITICAL -> "Critical"
            HIGH -> "High Priority"
            STANDARD -> "Standard"
            LOW -> "Low Priority"
        }

    val sortOrder: Int
        get() = when (this) {
            CRITICAL -> 0
            HIGH -> 1
            STANDARD -> 2
            LOW -> 3
        }

    companion object {
        fun fromValue(value: String): AnnouncementPriority {
            return entries.find { it.value == value } ?: STANDARD
        }
    }
}

// MARK: - Announcement Type

enum class AnnouncementType(val value: String) {
    FEATURE("feature"),
    UPDATE("update"),
    POLICY("policy"),
    EVENT("event"),
    MAINTENANCE("maintenance"),
    PROMOTION("promotion"),
    COMMUNITY("community"),
    SAFETY("safety");

    val displayName: String
        get() = when (this) {
            FEATURE -> "New Feature"
            UPDATE -> "App Update"
            POLICY -> "Policy Update"
            EVENT -> "Special Event"
            MAINTENANCE -> "Maintenance"
            PROMOTION -> "Promotion"
            COMMUNITY -> "Community"
            SAFETY -> "Safety Alert"
        }

    /** Material Icon name */
    val icon: String
        get() = when (this) {
            FEATURE -> "auto_awesome"
            UPDATE -> "system_update"
            POLICY -> "description"
            EVENT -> "star"
            MAINTENANCE -> "build"
            PROMOTION -> "card_giftcard"
            COMMUNITY -> "groups"
            SAFETY -> "security"
        }

    companion object {
        fun fromValue(value: String): AnnouncementType {
            return entries.find { it.value == value } ?: UPDATE
        }
    }
}

// MARK: - Announcement Audience

sealed class AnnouncementAudience {
    data object All : AnnouncementAudience()
    data class NewUsers(val daysOld: Int) : AnnouncementAudience()
    data class TierAndAbove(val tier: String) : AnnouncementAudience()
    data class TierOnly(val tier: String) : AnnouncementAudience()
    data class SpecificUsers(val userIds: List<String>) : AnnouncementAudience()

    val displayName: String
        get() = when (this) {
            is All -> "All Users"
            is NewUsers -> "New Users (< $daysOld days)"
            is TierAndAbove -> "${tier}+ Users"
            is TierOnly -> "$tier Only"
            is SpecificUsers -> "${userIds.size} Specific Users"
        }

    fun toMap(): Map<String, Any> {
        return when (this) {
            is All -> mapOf("type" to "all")
            is NewUsers -> mapOf("type" to "newUsers", "daysOld" to daysOld)
            is TierAndAbove -> mapOf("type" to "tierAndAbove", "tier" to tier)
            is TierOnly -> mapOf("type" to "tierOnly", "tier" to tier)
            is SpecificUsers -> mapOf("type" to "specificUsers", "userIds" to userIds)
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any>): AnnouncementAudience {
            return when (map["type"] as? String) {
                "all" -> All
                "newUsers" -> NewUsers((map["daysOld"] as? Number)?.toInt() ?: 7)
                "tierAndAbove" -> TierAndAbove(map["tier"] as? String ?: "rookie")
                "tierOnly" -> TierOnly(map["tier"] as? String ?: "rookie")
                "specificUsers" -> SpecificUsers(map["userIds"] as? List<String> ?: emptyList())
                else -> All
            }
        }
    }
}

// MARK: - User Announcement Status

/**
 * Tracks a user's interaction with an announcement
 */
data class UserAnnouncementStatus(
    @DocumentId
    val visibilityId: String = "",
    val userId: String = "",
    val announcementId: String = "",
    val firstSeenAt: Timestamp? = null,
    val completedAt: Timestamp? = null,
    val acknowledgedAt: Timestamp? = null,
    val dismissedAt: Timestamp? = null,
    val watchedSeconds: Int? = null,
    val viewCount: Int? = null,

    // MARK: - Repeat Tracking Fields

    /** Total number of times shown to user */
    val totalShowCount: Int = 0,

    /** Last time the announcement was shown */
    val lastShownAt: Timestamp? = null,

    /** Number of times shown today (resets daily) */
    val showsToday: Int = 0,

    /** Date of the last "today" count (for daily reset) */
    val showsTodayDate: Timestamp? = null,

    /** Array of all show timestamps (for analytics) */
    val showTimestamps: List<Timestamp> = emptyList(),

    /** User has permanently dismissed (won't show again) */
    val permanentlyDismissed: Boolean = false
) {
    // MARK: - Computed Properties

    val hasCompleted: Boolean
        get() = completedAt != null

    val hasAcknowledged: Boolean
        get() = acknowledgedAt != null

    val hasDismissed: Boolean
        get() = dismissedAt != null

    val hasPermanentlyDismissed: Boolean
        get() = permanentlyDismissed

    companion object {
        fun create(
            userId: String,
            announcementId: String,
            firstSeenAt: Date = Date()
        ): UserAnnouncementStatus {
            val statusId = "${userId}_${announcementId}"
            val now = Timestamp(firstSeenAt)
            val calendar = Calendar.getInstance().apply { time = firstSeenAt }
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val todayStart = Timestamp(calendar.time)

            return UserAnnouncementStatus(
                visibilityId = statusId,
                userId = userId,
                announcementId = announcementId,
                firstSeenAt = now,
                viewCount = 1,
                totalShowCount = 1,
                lastShownAt = now,
                showsToday = 1,
                showsTodayDate = todayStart,
                showTimestamps = listOf(now),
                permanentlyDismissed = false
            )
        }
    }
}

// MARK: - Announcement Stats

data class AnnouncementStats(
    val announcementId: String,
    val totalViews: Int,
    val uniqueViewers: Int,
    val completedCount: Int,
    val permanentDismissals: Int
) {
    val averageViewsPerUser: Double
        get() = if (uniqueViewers > 0) totalViews.toDouble() / uniqueViewers else 0.0
}

// MARK: - Announcement Error

sealed class AnnouncementError : Exception() {
    data object UnauthorizedCreator : AnnouncementError() {
        private fun readResolve(): Any = UnauthorizedCreator
        override val message: String = "Only authorized accounts can create announcements"
    }

    data object AnnouncementNotFound : AnnouncementError() {
        private fun readResolve(): Any = AnnouncementNotFound
        override val message: String = "Announcement not found"
    }

    data object AlreadyCompleted : AnnouncementError() {
        private fun readResolve(): Any = AlreadyCompleted
        override val message: String = "Announcement already completed"
    }

    data object MinimumWatchTimeNotMet : AnnouncementError() {
        private fun readResolve(): Any = MinimumWatchTimeNotMet
        override val message: String = "Please watch the full announcement before dismissing"
    }
}