/*
 * RecentUser.kt - RECENTLY JOINED USER MODEL
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Discovery Model for New Users
 * Dependencies: None (Pure Kotlin data class)
 * Features: Recently joined user data for "Just Joined" section
 *
 * EXACT PORT: RecentUser.swift (LeaderboardModels.swift)
 */

package com.stitchsocial.club.foundation

import com.google.firebase.Timestamp
import java.util.Date

/**
 * Represents a recently joined user for "Just Joined" discovery section
 * Used in NotificationView and discovery features
 */
data class RecentUser(
    val id: String,
    val username: String,
    val displayName: String,
    val profileImageURL: String? = null,
    val joinedAt: Date,
    val isVerified: Boolean = false
) {
    companion object {
        /**
         * Create RecentUser from Firestore document data
         */
        fun fromFirestore(id: String, data: Map<String, Any>): RecentUser? {
            val username = data["username"] as? String ?: return null
            val displayName = data["displayName"] as? String ?: return null
            val profileImageURL = data["profileImageURL"] as? String
            val createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: return null
            val isVerified = data["isVerified"] as? Boolean ?: false

            return RecentUser(
                id = id,
                username = username,
                displayName = displayName,
                profileImageURL = profileImageURL,
                joinedAt = createdAt,
                isVerified = isVerified
            )
        }
    }

    /**
     * Format joined date for display (e.g., "today", "yesterday", "3d ago")
     */
    fun formatJoinedDate(): String {
        val now = Date()
        val intervalSeconds = (now.time - joinedAt.time) / 1000
        val days = (intervalSeconds / 86400).toInt()

        return when {
            days == 0 -> "today"
            days == 1 -> "yesterday"
            else -> "${days}d ago"
        }
    }
}