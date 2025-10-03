/*
 * VideoServiceImpl.kt - FIXED WITH PROPER FOUNDATION IMPORTS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Video Management
 * Dependencies: Firebase, Foundation Layer (CORRECTED IMPORTS)
 * Features: Feed loading, thread support, discovery videos, user videos
 *
 * FIXES APPLIED:
 * ✅ All foundation imports corrected to com.stitchsocial.club.foundation.*
 * ✅ Removed SimplifiedCachingService dependency
 * ✅ Fixed CoreVideoMetadata constructor usage
 * ✅ Fixed ThreadData usage and imports
 * ✅ Simplified dependencies to core functionality only
 */

package com.stitchsocial.club.services

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import kotlinx.coroutines.tasks.await
import java.util.*

// FIXED IMPORTS - Correct foundation package
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ThreadData
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.Temperature

/**
 * VideoService with correct foundation imports and simplified dependencies
 * No constructor parameters needed - matches MainActivity usage
 */
class VideoServiceImpl {

    // Use "stitchfin" database for consistency
    private val db = FirebaseFirestore.getInstance("stitchfin")

    // ===== MAIN FEED LOADING =====

    /**
     * Get feed videos as ThreadData for HomeFeedView
     */
    suspend fun getFeedVideos(followingIDs: List<String> = emptyList()): List<ThreadData> {
        return try {
            println("VIDEO SERVICE: 📱 Loading feed videos")

            // For now, get recent thread-level videos (conversationDepth = 0)
            val snapshot = db.collection("videos")
                .whereEqualTo("conversationDepth", 0) // Only parent threads
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(20)
                .get()
                .await()

            println("VIDEO SERVICE: 📊 Found ${snapshot.documents.size} thread documents")

            // Convert to ThreadData with child loading
            val threads = snapshot.documents.mapNotNull { doc ->
                try {
                    val parentVideo = convertFirebaseToVideoMetadata(listOf(doc)).firstOrNull()
                    if (parentVideo != null) {
                        // Load children for this thread
                        val children = getThreadChildren(parentVideo.id)
                        ThreadData(
                            id = parentVideo.id,
                            parentVideo = parentVideo,
                            childVideos = children
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    println("VIDEO SERVICE: ❌ Failed to convert thread ${doc.id}: ${e.message}")
                    null
                }
            }

            println("VIDEO SERVICE: ✅ Successfully converted ${threads.size} threads")
            return threads

        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Feed query failed: ${e.message}")
            return emptyList()
        }
    }

    // ===== THREAD CHILDREN SUPPORT =====

    /**
     * Get child videos for a specific thread
     */
    suspend fun getThreadChildren(threadID: String): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: 📄 Loading children for thread $threadID")

            val snapshot = db.collection("videos")
                .whereEqualTo("threadID", threadID)
                .whereEqualTo("conversationDepth", 1) // Only direct children
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .get()
                .await()

            println("VIDEO SERVICE: 📊 Found ${snapshot.documents.size} children for thread $threadID")

            val children = convertFirebaseToVideoMetadata(snapshot.documents)
            println("VIDEO SERVICE: ✅ Successfully loaded ${children.size} children for thread $threadID")
            return children

        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Failed to load children for thread $threadID: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Get all videos in a thread (parent + children) for full thread view
     */
    suspend fun getFullThread(threadID: String): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: 🧵 Loading full thread $threadID")

            val snapshot = db.collection("videos")
                .whereEqualTo("threadID", threadID)
                .orderBy("conversationDepth", Query.Direction.ASCENDING)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .get()
                .await()

            val allVideos = convertFirebaseToVideoMetadata(snapshot.documents)
            println("VIDEO SERVICE: ✅ Loaded full thread: ${allVideos.size} videos")
            return allVideos

        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Failed to load full thread $threadID: ${e.message}")
            return emptyList()
        }
    }

    // ===== USER VIDEOS FOR PROFILE =====

    /**
     * Get videos by specific user for profile view
     */
    suspend fun getUserVideos(userID: String, limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: 👤 Loading videos for user $userID")

            val snapshot = db.collection("videos")
                .whereEqualTo("creatorID", userID)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val videos = convertFirebaseToVideoMetadata(snapshot.documents)
            println("VIDEO SERVICE: ✅ Found ${videos.size} videos for user $userID")
            return videos

        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ User videos query failed: ${e.message}")
            return emptyList()
        }
    }

    // ===== DISCOVERY VIDEOS =====

    /**
     * Get discovery videos with category filtering
     */
    suspend fun getDiscoveryVideos(category: String = "all", limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: 🔍 Loading discovery videos for category: $category")

            val snapshot = if (category == "all") {
                db.collection("videos")
                    .whereEqualTo("conversationDepth", 0) // Only parent threads for discovery
                    .orderBy("trendingScore", Query.Direction.DESCENDING)
                    .limit(limit.toLong())
                    .get()
                    .await()
            } else {
                db.collection("videos")
                    .whereEqualTo("conversationDepth", 0)
                    .whereEqualTo("category", category)
                    .orderBy("trendingScore", Query.Direction.DESCENDING)
                    .limit(limit.toLong())
                    .get()
                    .await()
            }

            val videos = convertFirebaseToVideoMetadata(snapshot.documents)
            println("VIDEO SERVICE: ✅ Found ${videos.size} discovery videos")
            return videos

        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Discovery query failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Get personalized video recommendations
     */
    suspend fun getPersonalizedVideos(userID: String, limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: 🎯 Loading personalized videos for user $userID")

            // For now, get trending videos (can be enhanced with ML later)
            val snapshot = db.collection("videos")
                .whereEqualTo("conversationDepth", 0)
                .orderBy("engagementRatio", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val videos = convertFirebaseToVideoMetadata(snapshot.documents)
            println("VIDEO SERVICE: ✅ Found ${videos.size} personalized videos")
            return videos

        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Personalized query failed: ${e.message}")
            return emptyList()
        }
    }

    // ===== SEARCH FUNCTIONALITY =====

    /**
     * Search videos by title
     */
    suspend fun searchVideos(query: String, limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            if (query.length < 2) {
                println("VIDEO SERVICE: 🔍 Query too short: '$query'")
                return emptyList()
            }

            println("VIDEO SERVICE: 🔍 Searching videos for: '$query'")

            val snapshot = db.collection("videos")
                .whereGreaterThanOrEqualTo("title", query.lowercase())
                .whereLessThanOrEqualTo("title", query.lowercase() + "\uf8ff")
                .limit(limit.toLong())
                .get()
                .await()

            val videos = convertFirebaseToVideoMetadata(snapshot.documents)
            println("VIDEO SERVICE: ✅ Found ${videos.size} search results")
            return videos

        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Search query failed: ${e.message}")
            return emptyList()
        }
    }

    // ===== ANALYTICS =====

    /**
     * Record a video view for analytics
     */
    suspend fun recordVideoView(videoID: String, userID: String) {
        try {
            db.collection("videos").document(videoID)
                .update("viewCount", com.google.firebase.firestore.FieldValue.increment(1))
                .await()

            println("VIDEO SERVICE: 📊 Recorded view for video $videoID by user $userID")

        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Failed to record view: ${e.message}")
        }
    }

    // ===== FIREBASE DOCUMENT CONVERSION =====

    /**
     * Convert Firebase documents to CoreVideoMetadata
     * FIXED: Uses correct CoreVideoMetadata constructor from foundation
     */
    private fun convertFirebaseToVideoMetadata(documents: List<DocumentSnapshot>): List<CoreVideoMetadata> {
        return documents.mapNotNull { doc ->
            try {
                val data = doc.data ?: return@mapNotNull null

                CoreVideoMetadata(
                    id = doc.id,
                    title = data["title"] as? String ?: "Untitled",
                    videoURL = data["videoURL"] as? String ?: "",
                    thumbnailURL = data["thumbnailURL"] as? String ?: "",
                    creatorID = data["creatorID"] as? String ?: "",
                    creatorName = "", // Load via UserService separately
                    createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
                    threadID = data["threadID"] as? String,
                    replyToVideoID = data["replyToVideoID"] as? String,
                    conversationDepth = (data["conversationDepth"] as? Long)?.toInt() ?: 0,
                    viewCount = (data["viewCount"] as? Long)?.toInt() ?: 0,
                    hypeCount = (data["hypeCount"] as? Long)?.toInt() ?: 0,
                    coolCount = (data["coolCount"] as? Long)?.toInt() ?: 0,
                    replyCount = (data["replyCount"] as? Long)?.toInt() ?: 0,
                    shareCount = (data["shareCount"] as? Long)?.toInt() ?: 0,
                    lastEngagementAt = (data["lastEngagementAt"] as? Timestamp)?.toDate(),
                    duration = (data["duration"] as? Number)?.toDouble() ?: 30.0,
                    aspectRatio = (data["aspectRatio"] as? Number)?.toDouble() ?: (9.0/16.0),
                    fileSize = (data["fileSize"] as? Number)?.toLong() ?: 0L,
                    contentType = parseContentType(data["contentType"] as? String),
                    temperature = parseTemperature(data["temperature"] as? String),
                    qualityScore = (data["qualityScore"] as? Number)?.toInt() ?: 50,       // ✅ Correct
                    engagementRatio = (data["engagementRatio"] as? Number)?.toDouble() ?: 0.0,
                    velocityScore = (data["velocityScore"] as? Number)?.toDouble() ?: 0.0,
                    trendingScore = (data["trendingScore"] as? Number)?.toDouble() ?: 0.0,
                    discoverabilityScore = (data["discoverabilityScore"] as? Number)?.toDouble() ?: 0.5,
                    isPromoted = data["isPromoted"] as? Boolean ?: false,
                    isProcessing = data["isProcessing"] as? Boolean ?: false,
                    isDeleted = data["isDeleted"] as? Boolean ?: false
                )
            } catch (e: Exception) {
                println("VIDEO SERVICE: ❌ Failed to convert document ${doc.id}: ${e.message}")
                null
            }
        }
    }

    // ===== HELPER METHODS - FIXED PARSING =====

    /**
     * Parse content type from string
     * FIXED: Returns ContentType enum from foundation
     */
    private fun parseContentType(contentTypeString: String?): ContentType {
        return when (contentTypeString?.uppercase()) {
            "THREAD" -> ContentType.THREAD
            "CHILD" -> ContentType.CHILD
            "STEPCHILD" -> ContentType.STEPCHILD
            else -> ContentType.THREAD // Default to thread
        }
    }

    /**
     * Parse temperature from string to Temperature enum
     * FIXED: Returns Temperature enum from foundation
     */
    private fun parseTemperature(temperatureString: String?): Temperature {
        return when (temperatureString?.uppercase()) {
            "BLAZING" -> Temperature.BLAZING
            "HOT" -> Temperature.HOT
            "WARM" -> Temperature.WARM
            "COOL" -> Temperature.COOL
            "COLD" -> Temperature.COLD
            "FROZEN" -> Temperature.FROZEN
            else -> Temperature.WARM // Default to warm
        }
    }
}