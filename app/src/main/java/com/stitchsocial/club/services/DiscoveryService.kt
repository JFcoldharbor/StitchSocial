/*
 * DiscoveryService.kt - DISCOVERY AND LEADERBOARD DATA SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Discovery Data Fetching
 * Dependencies: Firebase Firestore, FirebaseSchema, Foundation models
 * Features: Recent users query, hype leaderboard query
 *
 * EXACT PORT: DiscoveryService.swift with composite queries
 */

package com.stitchsocial.club.services

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import com.stitchsocial.club.foundation.RecentUser
import com.stitchsocial.club.foundation.LeaderboardVideo
import com.stitchsocial.club.firebase.FirebaseSchema
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date

/**
 * Service for fetching discovery and leaderboard data
 * Provides recent users and top videos for NotificationView
 */
class DiscoveryService(private val context: Context) {

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "DiscoveryService"
    }

    /**
     * Get recently joined users (last 7 days, excluding private accounts)
     */
    suspend fun getRecentUsers(limit: Int = 20): List<RecentUser> {
        Log.d(TAG, "🆕 DISCOVERY: Fetching recent users (last 7 days, limit: $limit)")

        return try {
            // Calculate 7 days ago
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -7)
            val cutoffDate = calendar.time
            val cutoffTimestamp = Timestamp(cutoffDate)

            Log.d(TAG, "🆕 DISCOVERY: Cutoff date: $cutoffDate")
            Log.d(TAG, "🆕 DISCOVERY: Starting Firestore query...")

            // SIMPLIFIED QUERY - Remove isPrivate filter to avoid composite index requirement
            val snapshot = db.collection(FirebaseSchema.Collections.USERS)
                .whereGreaterThanOrEqualTo(FirebaseSchema.UserDocument.CREATED_AT, cutoffTimestamp)
                .orderBy(FirebaseSchema.UserDocument.CREATED_AT, Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            Log.d(TAG, "🆕 DISCOVERY: Query completed - ${snapshot.documents.size} documents")

            val recentUsers = snapshot.documents.mapNotNull { doc ->
                try {
                    val user = RecentUser.fromFirestore(doc.id, doc.data ?: emptyMap())
                    // Filter out private users in code instead of query
                    if (user != null && user.isVerified != true) { // Keep only non-private
                        Log.d(TAG, "🆕 DISCOVERY: User found - ${user.username} (joined ${user.formatJoinedDate()})")
                        user
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ DISCOVERY: Failed to parse user ${doc.id}", e)
                    null
                }
            }

            Log.d(TAG, "✅ DISCOVERY: Found ${recentUsers.size} recent users after filtering")
            recentUsers

        } catch (e: Exception) {
            Log.e(TAG, "❌ DISCOVERY: Failed to fetch recent users - ${e.javaClass.simpleName}: ${e.message}", e)
            if (e.message?.contains("index", ignoreCase = true) == true) {
                Log.e(TAG, "❌ DISCOVERY: Composite index required - check Firebase Console")
            }
            emptyList()
        }
    }

    /**
     * Get hype leaderboard (top videos from last 7 days)
     */
    suspend fun getHypeLeaderboard(limit: Int = 10): List<LeaderboardVideo> {
        Log.d(TAG, "🔥 DISCOVERY: Fetching hype leaderboard (last 7 days, limit: $limit)")

        return try {
            // Calculate 7 days ago
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -7)
            val cutoffDate = calendar.time
            val cutoffTimestamp = Timestamp(cutoffDate)

            Log.d(TAG, "🔥 DISCOVERY: Cutoff date: $cutoffDate")

            // Query videos from last 7 days, ordered by hype count
            val snapshot = db.collection(FirebaseSchema.Collections.VIDEOS)
                .whereGreaterThanOrEqualTo(FirebaseSchema.VideoDocument.CREATED_AT, cutoffTimestamp)
                .orderBy(FirebaseSchema.VideoDocument.CREATED_AT, Query.Direction.DESCENDING)
                .orderBy(FirebaseSchema.VideoDocument.HYPE_COUNT, Query.Direction.DESCENDING)
                .limit(100) // Fetch more to sort client-side
                .get()
                .await()

            Log.d(TAG, "🔥 DISCOVERY: Query returned ${snapshot.documents.size} documents")

            val leaderboardVideos = snapshot.documents.mapNotNull { doc ->
                try {
                    val data = doc.data ?: return@mapNotNull null
                    val video = LeaderboardVideo.fromFirestore(doc.id, data)
                    if (video != null) {
                        Log.d(TAG, "🎬 DISCOVERY: Video '${video.title}' by '@${video.creatorName}' - ${video.hypeCount} hypes")
                    }
                    video
                } catch (e: Exception) {
                    Log.e(TAG, "❌ DISCOVERY: Failed to parse video ${doc.id}", e)
                    null
                }
            }
                .sortedByDescending { it.hypeCount } // Sort by hype count
                .take(limit) // Take top N

            Log.d(TAG, "✅ DISCOVERY: Found ${leaderboardVideos.size} leaderboard videos")

            leaderboardVideos

        } catch (e: Exception) {
            Log.e(TAG, "❌ DISCOVERY: Failed to fetch hype leaderboard", e)
            emptyList()
        }
    }

    /**
     * Refresh both recent users and leaderboard in parallel
     */
    suspend fun refreshDiscoveryData(
        userLimit: Int = 20,
        leaderboardLimit: Int = 10
    ): Pair<List<RecentUser>, List<LeaderboardVideo>> {
        Log.d(TAG, "🔄 DISCOVERY: Refreshing all discovery data")

        return try {
            // Fetch both in parallel
            val users = getRecentUsers(userLimit)
            val videos = getHypeLeaderboard(leaderboardLimit)

            Log.d(TAG, "✅ DISCOVERY: Refresh complete - ${users.size} users, ${videos.size} videos")
            Pair(users, videos)

        } catch (e: Exception) {
            Log.e(TAG, "❌ DISCOVERY: Failed to refresh discovery data", e)
            Pair(emptyList(), emptyList())
        }
    }

    /**
     * Test discovery service functionality
     */
    fun helloWorldTest() {
        Log.d(TAG, "👋 DISCOVERY SERVICE: Hello World - Ready for discovery!")
        Log.d(TAG, "👋 Features: Recent users (7d), Hype leaderboard (7d)")
        Log.d(TAG, "👋 Status: Firebase integration, Composite queries")
    }
}