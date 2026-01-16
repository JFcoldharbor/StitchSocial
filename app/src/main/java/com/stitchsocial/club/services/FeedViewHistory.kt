/*
 * FeedViewHistory.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Feed View History & Position Tracking
 * Uses JSONObject/JSONArray (no Gson dependency)
 */

package com.stitchsocial.club.services

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import com.stitchsocial.club.foundation.ThreadData
import java.util.Date

/**
 * Tracks viewed videos and feed position for personalized content delivery
 */
class FeedViewHistory private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: FeedViewHistory? = null

        fun getInstance(context: Context): FeedViewHistory {
            return instance ?: synchronized(this) {
                instance ?: FeedViewHistory(context.applicationContext).also { instance = it }
            }
        }

        var shared: FeedViewHistory? = null
            private set

        fun initialize(context: Context) {
            shared = getInstance(context)
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("feed_history", Context.MODE_PRIVATE)

    private val SEEN_VIDEO_IDS_KEY = "feed_seen_video_ids"
    private val FEED_POSITION_KEY = "feed_position"
    private val LAST_SESSION_FEED_KEY = "feed_last_session"
    private val LAST_SESSION_TIMESTAMP_KEY = "feed_last_session_timestamp"
    private val VIEW_HISTORY_TIMESTAMPS_KEY = "feed_view_timestamps"

    private val maxSeenVideoIDs = 500
    private val resumeWindowHours = 24.0
    private val recentlySeenHours = 4.0

    init {
        cleanupOldHistory()
    }

    // MARK: - Seen Video Tracking

    fun markVideoSeen(videoID: String) {
        val seenIDs = getSeenVideoIDs().toMutableList()
        val timestamps = getViewTimestamps().toMutableMap()

        if (!seenIDs.contains(videoID)) {
            seenIDs.add(videoID)
            timestamps[videoID] = System.currentTimeMillis() / 1000.0

            if (seenIDs.size > maxSeenVideoIDs) {
                val oldestID = seenIDs.removeAt(0)
                timestamps.remove(oldestID)
            }

            saveSeenVideoIDs(seenIDs)
            saveViewTimestamps(timestamps)
        }
    }

    fun markVideosSeen(videoIDs: List<String>) {
        val seenIDs = getSeenVideoIDs().toMutableList()
        val timestamps = getViewTimestamps().toMutableMap()
        val now = System.currentTimeMillis() / 1000.0

        for (videoID in videoIDs) {
            if (!seenIDs.contains(videoID)) {
                seenIDs.add(videoID)
                timestamps[videoID] = now
            }
        }

        while (seenIDs.size > maxSeenVideoIDs) {
            val oldestID = seenIDs.removeAt(0)
            timestamps.remove(oldestID)
        }

        saveSeenVideoIDs(seenIDs)
        saveViewTimestamps(timestamps)
    }

    fun wasVideoSeen(videoID: String): Boolean {
        return getSeenVideoIDs().contains(videoID)
    }

    fun wasVideoRecentlySeen(videoID: String): Boolean {
        val timestamps = getViewTimestamps()
        val timestamp = timestamps[videoID] ?: return false
        val ageHours = (System.currentTimeMillis() / 1000.0 - timestamp) / 3600.0
        return ageHours < recentlySeenHours
    }

    fun getSeenVideoIDs(): List<String> {
        val json = prefs.getString(SEEN_VIDEO_IDS_KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getRecentlySeenVideoIDs(): Set<String> {
        val timestamps = getViewTimestamps()
        val now = System.currentTimeMillis() / 1000.0
        val cutoff = now - (recentlySeenHours * 3600.0)
        return timestamps.filter { it.value > cutoff }.keys
    }

    fun seenVideoCount(): Int = getSeenVideoIDs().size

    fun filterUnseenVideos(videoIDs: List<String>): List<String> {
        val seenSet = getSeenVideoIDs().toSet()
        return videoIDs.filter { !seenSet.contains(it) }
    }

    fun filterThreads(threads: List<ThreadData>, excludeRecentlySeen: Boolean = true): List<ThreadData> {
        val exclusionSet = if (excludeRecentlySeen) getRecentlySeenVideoIDs() else getSeenVideoIDs().toSet()
        return threads.filter { !exclusionSet.contains(it.parentVideo.id) }
    }

    // MARK: - Feed Position

    fun saveFeedPosition(itemIndex: Int, stitchIndex: Int, threadID: String?) {
        try {
            val json = JSONObject().apply {
                put("itemIndex", itemIndex)
                put("stitchIndex", stitchIndex)
                put("threadID", threadID ?: JSONObject.NULL)
                put("savedAt", System.currentTimeMillis())
            }
            prefs.edit().putString(FEED_POSITION_KEY, json.toString()).apply()
            println("📍 FEED HISTORY: Saved position - item $itemIndex, stitch $stitchIndex")
        } catch (e: Exception) {
            println("❌ FEED HISTORY: Failed to save position - ${e.message}")
        }
    }

    fun getSavedPosition(): FeedPosition? {
        val json = prefs.getString(FEED_POSITION_KEY, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val savedAt = obj.getLong("savedAt")
            val ageHours = (System.currentTimeMillis() - savedAt) / (1000.0 * 3600.0)
            if (ageHours > resumeWindowHours) {
                clearFeedPosition()
                return null
            }
            FeedPosition(
                itemIndex = obj.getInt("itemIndex"),
                stitchIndex = obj.getInt("stitchIndex"),
                threadID = if (obj.isNull("threadID")) null else obj.getString("threadID"),
                savedAt = Date(savedAt)
            )
        } catch (e: Exception) {
            println("❌ FEED HISTORY: Failed to decode position - ${e.message}")
            null
        }
    }

    fun clearFeedPosition() {
        prefs.edit().remove(FEED_POSITION_KEY).apply()
    }

    // MARK: - Last Session Feed

    fun saveLastSessionFeed(threads: List<ThreadData>) {
        try {
            val array = JSONArray()
            threads.take(50).forEach { thread ->
                val obj = JSONObject().apply {
                    put("id", thread.id)
                    put("parentVideoID", thread.parentVideo.id)
                    put("creatorID", thread.parentVideo.creatorID)
                }
                array.put(obj)
            }
            prefs.edit()
                .putString(LAST_SESSION_FEED_KEY, array.toString())
                .putLong(LAST_SESSION_TIMESTAMP_KEY, System.currentTimeMillis())
                .apply()
            println("💾 FEED HISTORY: Saved ${threads.size.coerceAtMost(50)} threads")
        } catch (e: Exception) {
            println("❌ FEED HISTORY: Failed to save session feed - ${e.message}")
        }
    }

    fun getLastSessionThreadIDs(): List<String>? {
        val timestamp = prefs.getLong(LAST_SESSION_TIMESTAMP_KEY, 0)
        if (timestamp == 0L) return null

        val ageHours = (System.currentTimeMillis() - timestamp) / (1000.0 * 3600.0)
        if (ageHours > resumeWindowHours) {
            clearLastSession()
            return null
        }

        val json = prefs.getString(LAST_SESSION_FEED_KEY, null) ?: return null
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getJSONObject(it).getString("id") }
        } catch (e: Exception) {
            println("❌ FEED HISTORY: Failed to decode session feed - ${e.message}")
            null
        }
    }

    fun canResumeSession(): Boolean {
        return getSavedPosition() != null && getLastSessionThreadIDs() != null
    }

    fun clearLastSession() {
        prefs.edit()
            .remove(LAST_SESSION_FEED_KEY)
            .remove(LAST_SESSION_TIMESTAMP_KEY)
            .apply()
    }

    // MARK: - Private Helpers

    private fun saveSeenVideoIDs(ids: List<String>) {
        val array = JSONArray(ids)
        prefs.edit().putString(SEEN_VIDEO_IDS_KEY, array.toString()).apply()
    }

    private fun getViewTimestamps(): Map<String, Double> {
        val json = prefs.getString(VIEW_HISTORY_TIMESTAMPS_KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, Double>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.getDouble(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveViewTimestamps(timestamps: Map<String, Double>) {
        val obj = JSONObject()
        timestamps.forEach { (key, value) -> obj.put(key, value) }
        prefs.edit().putString(VIEW_HISTORY_TIMESTAMPS_KEY, obj.toString()).apply()
    }

    private fun cleanupOldHistory() {
        val timestamps = getViewTimestamps().toMutableMap()
        val cutoff = System.currentTimeMillis() / 1000.0 - (72 * 3600.0) // 72 hours

        val oldKeys = timestamps.filter { it.value < cutoff }.keys.toList()
        for (key in oldKeys) {
            timestamps.remove(key)
        }

        if (oldKeys.isNotEmpty()) {
            saveViewTimestamps(timestamps)
            val seenIDs = getSeenVideoIDs().toMutableList()
            seenIDs.removeAll(oldKeys.toSet())
            saveSeenVideoIDs(seenIDs)
            println("🧹 FEED HISTORY: Cleaned ${oldKeys.size} old entries")
        }
    }

    fun clearAllHistory() {
        prefs.edit()
            .remove(SEEN_VIDEO_IDS_KEY)
            .remove(FEED_POSITION_KEY)
            .remove(LAST_SESSION_FEED_KEY)
            .remove(LAST_SESSION_TIMESTAMP_KEY)
            .remove(VIEW_HISTORY_TIMESTAMPS_KEY)
            .apply()
        println("🗑️ FEED HISTORY: Cleared all history")
    }

    fun debugStatus(): String {
        return """
            📊 Feed History Status:
            - Seen videos: ${seenVideoCount()}
            - Recently seen: ${getRecentlySeenVideoIDs().size}
            - Has saved position: ${getSavedPosition() != null}
            - Can resume: ${canResumeSession()}
        """.trimIndent()
    }
}

// MARK: - Supporting Types

data class FeedPosition(
    val itemIndex: Int,
    val stitchIndex: Int,
    val threadID: String?,
    val savedAt: Date
)

data class SessionThread(
    val id: String,
    val parentVideoID: String,
    val creatorID: String
)