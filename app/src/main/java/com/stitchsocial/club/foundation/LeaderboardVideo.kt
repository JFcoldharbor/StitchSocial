/*
 * LeaderboardVideo.kt - HYPE LEADERBOARD VIDEO MODEL
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Discovery Model for Top Videos
 * Dependencies: None (Pure Kotlin data class)
 * Features: Top performing video data for leaderboard section
 *
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
         */
        fun fromFirestore(id: String, data: Map<String, Any>): LeaderboardVideo? {
            val title = data["title"] as? String ?: return null
            val creatorID = data["creatorID"] as? String ?: return null
            val creatorName = data["creatorName"] as? String ?: return null
            val thumbnailURL = data["thumbnailURL"] as? String
            val hypeCount = (data["hypeCount"] as? Long)?.toInt() ?: 0
            val coolCount = (data["coolCount"] as? Long)?.toInt() ?: 0
            val temperature = data["temperature"] as? String ?: "cool"
            val createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: return null

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