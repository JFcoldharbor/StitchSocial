/*
 * DiscoveryService.kt - DISCOVERY AND LEADERBOARD DATA SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Discovery Data Fetching
 * Dependencies: Firebase Firestore
 * Features: Recent users query, hype leaderboard query
 *
 * ✅ FIXED: Uses "stitchfin" database (was using default)
 * ✅ FIXED: Simplified queries to avoid composite index requirements
 */

package com.stitchsocial.club.services

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import com.stitchsocial.club.foundation.RecentUser
import com.stitchsocial.club.foundation.LeaderboardVideo
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date

/**
 * Service for fetching discovery and leaderboard data
 * Provides recent users and top videos for NotificationView
 */
class DiscoveryService(private val context: Context) {

    // ✅ CRITICAL FIX: Use named "stitchfin" database
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance("stitchfin")

    companion object {
        private const val TAG = "DiscoveryService"
    }

    /**
     * Get recently joined users
     */
    suspend fun getRecentUsers(limit: Int = 20): List<RecentUser> {
        Log.d(TAG, "🆕 DISCOVERY: Fetching recent users (limit: $limit)")

        return try {
            // Calculate 30 days ago
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -30)
            val cutoffDate = calendar.time

            Log.d(TAG, "🆕 DISCOVERY: Querying stitchfin/users collection...")

            // Simple query - just order by creation date
            val snapshot = db.collection("users")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit((limit * 2).toLong())
                .get()
                .await()

            Log.d(TAG, "🆕 DISCOVERY: Query returned ${snapshot.documents.size} documents")

            if (snapshot.documents.isEmpty()) {
                Log.w(TAG, "⚠️ DISCOVERY: No user documents found")
                return emptyList()
            }

            val recentUsers = snapshot.documents.mapNotNull { doc ->
                try {
                    val data = doc.data ?: return@mapNotNull null

                    val username = data["username"] as? String ?: return@mapNotNull null
                    val displayName = data["displayName"] as? String ?: username
                    val profileImageURL = data["profileImageURL"] as? String
                    val isPrivate = data["isPrivate"] as? Boolean ?: false
                    val isVerified = data["isVerified"] as? Boolean ?: false
                    val createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date()

                    // Skip private accounts
                    if (isPrivate) return@mapNotNull null

                    // Filter by date
                    if (createdAt.before(cutoffDate)) return@mapNotNull null

                    val user = RecentUser(
                        id = doc.id,
                        username = username,
                        displayName = displayName,
                        profileImageURL = profileImageURL,
                        joinedAt = createdAt,
                        isVerified = isVerified
                    )

                    Log.d(TAG, "🆕 Found user: ${user.username}")
                    user
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to parse user ${doc.id}: ${e.message}")
                    null
                }
            }.take(limit)

            Log.d(TAG, "✅ DISCOVERY: Returning ${recentUsers.size} recent users")
            recentUsers

        } catch (e: Exception) {
            Log.e(TAG, "❌ DISCOVERY: Failed to fetch recent users: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get hype leaderboard (top videos by hype count)
     */
    suspend fun getHypeLeaderboard(limit: Int = 10): List<LeaderboardVideo> {
        Log.d(TAG, "🔥 DISCOVERY: Fetching hype leaderboard (limit: $limit)")

        return try {
            Log.d(TAG, "🔥 DISCOVERY: Querying stitchfin/videos collection...")

            // Simple query - order by hype count
            val snapshot = db.collection("videos")
                .orderBy("hypeCount", Query.Direction.DESCENDING)
                .limit((limit * 3).toLong())
                .get()
                .await()

            Log.d(TAG, "🔥 DISCOVERY: Query returned ${snapshot.documents.size} documents")

            if (snapshot.documents.isEmpty()) {
                Log.w(TAG, "⚠️ DISCOVERY: No video documents found")
                return emptyList()
            }

            val leaderboardVideos = snapshot.documents.mapNotNull { doc ->
                try {
                    val data = doc.data ?: return@mapNotNull null

                    val title = data["title"] as? String ?: return@mapNotNull null
                    val creatorID = data["creatorID"] as? String ?: return@mapNotNull null
                    val creatorName = data["creatorName"] as? String ?: "Unknown"
                    val thumbnailURL = data["thumbnailURL"] as? String
                    val hypeCount = (data["hypeCount"] as? Long)?.toInt() ?: 0
                    val coolCount = (data["coolCount"] as? Long)?.toInt() ?: 0
                    val temperature = data["temperature"] as? String ?: "cool"
                    val isDeleted = data["isDeleted"] as? Boolean ?: false
                    val createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date()

                    // Skip deleted videos
                    if (isDeleted) return@mapNotNull null

                    val video = LeaderboardVideo(
                        id = doc.id,
                        title = title,
                        creatorID = creatorID,
                        creatorName = creatorName,
                        thumbnailURL = thumbnailURL,
                        hypeCount = hypeCount,
                        coolCount = coolCount,
                        temperature = temperature,
                        createdAt = createdAt
                    )

                    Log.d(TAG, "🎬 Found video: '${video.title}' - ${video.hypeCount} hypes")
                    video
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to parse video ${doc.id}: ${e.message}")
                    null
                }
            }
                .sortedByDescending { it.hypeCount }
                .take(limit)

            Log.d(TAG, "✅ DISCOVERY: Returning ${leaderboardVideos.size} leaderboard videos")
            leaderboardVideos

        } catch (e: Exception) {
            Log.e(TAG, "❌ DISCOVERY: Failed to fetch leaderboard: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Refresh both recent users and leaderboard
     */
    suspend fun refreshDiscoveryData(
        userLimit: Int = 20,
        leaderboardLimit: Int = 10
    ): Pair<List<RecentUser>, List<LeaderboardVideo>> {
        Log.d(TAG, "🔄 DISCOVERY: Refreshing from stitchfin database...")

        return try {
            val users = getRecentUsers(userLimit)
            val videos = getHypeLeaderboard(leaderboardLimit)

            Log.d(TAG, "✅ DISCOVERY: Complete - ${users.size} users, ${videos.size} videos")
            Pair(users, videos)

        } catch (e: Exception) {
            Log.e(TAG, "❌ DISCOVERY: Refresh failed: ${e.message}", e)
            Pair(emptyList(), emptyList())
        }
    }
}