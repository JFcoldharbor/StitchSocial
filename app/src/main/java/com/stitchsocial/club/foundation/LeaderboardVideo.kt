/*
 * LeaderboardVideo.kt - HYPE LEADERBOARD VIDEO MODEL
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Discovery Model for Top Videos
 * Dependencies: None (Pure Kotlin data class)
 * Features: Top performing video data for leaderboard section
 *
 * ✅ FIXED: More lenient fromFirestore parsing (doesn't fail on missing createdAt)
 * EXACT PORT: LeaderboardVideo.swift (LeaderboardModels.swift)
 */

package com.stitchsocial.club.foundation

import com.google.firebase.Timestamp
import java.util.Date

/**
 * Represents a video in the hype leaderboard
 * Used for "Top Videos" discovery section in NotificationView
 */
data class LeaderboardVideo(
    val id: String,
    val title: String,
    val creatorID: String,
    val creatorName: String,
    val thumbnailURL: String? = null,
    val hypeCount: Int,
    val coolCount: Int,
    val temperature: String,
    val createdAt: Date
) {
    companion object {
        /**
         * Create LeaderboardVideo from Firestore document data
         * ✅ FIXED: More lenient - uses defaults instead of returning null
         */
        fun fromFirestore(id: String, data: Map<String, Any>): LeaderboardVideo? {
            // Only require title and creatorID - everything else has defaults
            val title = data["title"] as? String
            val creatorID = data["creatorID"] as? String

            // If we don't have title or creatorID, skip this video
            if (title.isNullOrBlank() || creatorID.isNullOrBlank()) {
                println("⚠️ LeaderboardVideo.fromFirestore: Missing title or creatorID for $id")
                return null
            }

            val creatorName = data["creatorName"] as? String ?: "Unknown"
            val thumbnailURL = data["thumbnailURL"] as? String
            val hypeCount = (data["hypeCount"] as? Long)?.toInt()
                ?: (data["hypeCount"] as? Int)
                ?: 0
            val coolCount = (data["coolCount"] as? Long)?.toInt()
                ?: (data["coolCount"] as? Int)
                ?: 0
            val temperature = data["temperature"] as? String ?: "cool"

            // Handle createdAt more flexibly
            val createdAt = when (val timestamp = data["createdAt"]) {
                is Timestamp -> timestamp.toDate()
                is Date -> timestamp
                is Long -> Date(timestamp)
                else -> Date() // Default to now if missing
            }

            return LeaderboardVideo(
                id = id,
                title = title,
                creatorID = creatorID,
                creatorName = creatorName,
                thumbnailURL = thumbnailURL,
                hypeCount = hypeCount,
                coolCount = coolCount,
                temperature = temperature,
                createdAt = createdAt
            )
        }
    }

    /**
     * Calculate net score (hype - cool)
     */
    val netScore: Int
        get() = hypeCount - coolCount

    /**
     * Get temperature emoji
     */
    val temperatureEmoji: String
        get() = when (temperature.lowercase()) {
            "frozen" -> "❄️"
            "cold" -> "🧊"
            "cool" -> "😎"
            "warm" -> "🌡️"
            "hot" -> "🔥"
            "blazing" -> "🚀"
            else -> "😎"
        }

    /**
     * Format time ago for display
     */
    fun formatTimeAgo(): String {
        val now = Date()
        val intervalSeconds = (now.time - createdAt.time) / 1000

        return when {
            intervalSeconds < 60 -> "Just now"
            intervalSeconds < 3600 -> "${intervalSeconds / 60}m ago"
            intervalSeconds < 86400 -> "${intervalSeconds / 3600}h ago"
            else -> "${intervalSeconds / 86400}d ago"
        }
    }
}