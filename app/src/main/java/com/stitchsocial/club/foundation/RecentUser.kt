/*
 * RecentUser.kt - RECENT USER MODEL FOR DISCOVERY
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Discovery Model for Just Joined section
 * Dependencies: None (Pure Kotlin data class)
 * Features: Recently joined user data for NotificationView discovery
 *
 * EXACT PORT: RecentUser from LeaderboardModels.swift
 */

package com.stitchsocial.club.foundation

import com.google.firebase.Timestamp
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Represents a recently joined user for the "Just Joined" discovery section
 * Used in NotificationView discovery area
 */
data class RecentUser(
    val id: String,
    val username: String,
    val displayName: String,
    val profileImageURL: String? = null,
    val joinedAt: Date,
    val isVerified: Boolean = false
) {
    /**
     * Format joined date as relative time (e.g., "2d ago", "5h ago")
     */
    fun formatJoinedDate(): String {
        val now = System.currentTimeMillis()
        val joinedTime = joinedAt.time
        val diffMillis = now - joinedTime

        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMillis)
        val days = TimeUnit.MILLISECONDS.toDays(diffMillis)

        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            days < 30 -> "${days / 7}w ago"
            else -> "${days / 30}mo ago"
        }
    }

    companion object {
        /**
         * Create RecentUser from Firestore document data
         */
        fun fromFirestore(id: String, data: Map<String, Any>): RecentUser? {
            return try {
                val username = data["username"] as? String ?: return null
                val displayName = data["displayName"] as? String ?: username
                val profileImageURL = data["profileImageURL"] as? String
                val isVerified = data["isVerified"] as? Boolean ?: false
                val joinedAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date()

                RecentUser(
                    id = id,
                    username = username,
                    displayName = displayName,
                    profileImageURL = profileImageURL,
                    joinedAt = joinedAt,
                    isVerified = isVerified
                )
            } catch (e: Exception) {
                println("❌ RecentUser.fromFirestore failed for $id: ${e.message}")
                null
            }
        }
    }
}