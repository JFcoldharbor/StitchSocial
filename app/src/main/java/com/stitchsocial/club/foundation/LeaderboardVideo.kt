/*
 * LeaderboardVideo.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Hype Leaderboard Video Model
 * Port of: LeaderboardVideo from RecentUser.swift (LeaderboardModels)
 */

package com.stitchsocial.club.foundation

import com.google.firebase.Timestamp
import java.util.Date

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
    val netScore: Int get() = hypeCount - coolCount

    val temperatureEmoji: String get() = when (temperature.lowercase()) {
        "fire", "blazing" -> "\uD83D\uDD25"
        "hot" -> "\uD83C\uDF36\uFE0F"
        "warm" -> "\u2600\uFE0F"
        "neutral" -> "\u26A1"
        "cool" -> "\u2744\uFE0F"
        "cold", "frozen" -> "\uD83E\uDDCA"
        else -> "\uD83D\uDCCA"
    }

    companion object {
        fun fromFirestore(id: String, data: Map<String, Any>): LeaderboardVideo? {
            return try {
                LeaderboardVideo(
                    id = id,
                    title = data["title"] as? String ?: "",
                    creatorID = data["creatorID"] as? String ?: "",
                    creatorName = data["creatorName"] as? String ?: "",
                    thumbnailURL = data["thumbnailURL"] as? String,
                    hypeCount = (data["hypeCount"] as? Number)?.toInt() ?: 0,
                    coolCount = (data["coolCount"] as? Number)?.toInt() ?: 0,
                    temperature = data["temperature"] as? String ?: "neutral",
                    createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}