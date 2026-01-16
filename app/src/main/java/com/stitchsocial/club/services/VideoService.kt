/*
 * VideoServiceImpl.kt - FIXED WITH DUAL SIGNATURES
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Video Management
 * ✅ FIXED: Both getFeedVideos signatures for compatibility
 * ✅ FIXED: Simplified queries - no composite index required
 * ✅ ADDED: Video deletion (soft delete)
 */

package com.stitchsocial.club.services

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import kotlinx.coroutines.tasks.await
import java.util.*

// Foundation imports
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ThreadData
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.Temperature

/**
 * Data class for viewer information
 */
data class ViewerData(
    val userID: String,
    val displayName: String,
    val username: String,
    val profileImageURL: String?,
    val tier: String,
    val viewedAt: Date
)

/**
 * VideoService with FIXED following feed filtering
 */
class VideoServiceImpl {

    private val db = FirebaseFirestore.getInstance("stitchfin")

    // ===== ENGAGEMENT SYSTEM METHODS =====

    suspend fun getVideoById(videoID: String): CoreVideoMetadata? {
        return try {
            println("VIDEO SERVICE: 📱 Loading video by ID: $videoID")
            val doc = db.collection("videos").document(videoID).get().await()
            if (!doc.exists()) {
                println("VIDEO SERVICE: ❌ Video not found: $videoID")
                return null
            }
            val videos = convertFirebaseToVideoMetadata(listOf(doc))
            videos.firstOrNull()
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Error loading video $videoID: ${e.message}")
            null
        }
    }

    suspend fun updateEngagementCounts(videoID: String, hypeCount: Int, coolCount: Int) {
        try {
            val updates = hashMapOf<String, Any>(
                "hypeCount" to hypeCount,
                "coolCount" to coolCount,
                "lastEngagementAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            db.collection("videos").document(videoID).update(updates).await()
            println("VIDEO SERVICE: ✅ Updated engagement counts for $videoID")
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Failed to update counts: ${e.message}")
            throw e
        }
    }

    /**
     * Delete a video (soft delete - marks as deleted)
     */
    suspend fun deleteVideo(videoID: String) {
        try {
            println("VIDEO SERVICE: 🗑️ Deleting video: $videoID")

            // Soft delete - mark as deleted rather than removing from database
            val updates = hashMapOf<String, Any>(
                "isDeleted" to true,
                "deletedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            db.collection("videos").document(videoID).update(updates).await()
            println("VIDEO SERVICE: ✅ Video marked as deleted: $videoID")

        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Failed to delete video: ${e.message}")
            throw e
        }
    }

    // ===== FEED LOADING - DUAL SIGNATURES =====

    /**
     * Get feed videos - returns CoreVideoMetadata (for HomeFeedService compatibility)
     */
    suspend fun getFeedVideos(followingIDs: List<String>, limit: Int): List<CoreVideoMetadata> {
        if (followingIDs.isEmpty()) {
            println("VIDEO SERVICE: 📭 No following users - returning empty feed")
            return emptyList()
        }

        println("VIDEO SERVICE: 📱 Loading feed for ${followingIDs.size} followed users")

        val allVideos = mutableListOf<CoreVideoMetadata>()

        followingIDs.chunked(10).forEachIndexed { chunkIndex, chunk ->
            try {
                val snapshot = db.collection("videos")
                    .whereIn("creatorID", chunk)
                    .limit(100)
                    .get()
                    .await()

                println("VIDEO SERVICE: 📊 Chunk ${chunkIndex + 1} returned ${snapshot.documents.size} documents")

                val videos = convertFirebaseToVideoMetadata(snapshot.documents)
                val parentVideos = videos.filter { it.conversationDepth == 0 && !it.isDeleted }
                allVideos.addAll(parentVideos)

            } catch (e: Exception) {
                println("VIDEO SERVICE: ❌ Chunk query failed: ${e.message}")
            }
        }

        val sorted = allVideos.sortedByDescending { it.createdAt }.take(limit)
        println("VIDEO SERVICE: ✅ Loaded ${sorted.size} videos from followed users")
        return sorted
    }

    /**
     * Get feed videos - simple limit only (for general feed)
     */
    suspend fun getFeedVideos(limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: 📱 Loading general feed")
            val snapshot = db.collection("videos")
                .limit(limit.toLong())
                .get()
                .await()

            val videos = convertFirebaseToVideoMetadata(snapshot.documents)
                .filter { it.conversationDepth == 0 && !it.isDeleted }
                .sortedByDescending { it.createdAt }

            println("VIDEO SERVICE: ✅ Loaded ${videos.size} feed videos")
            videos
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Feed query failed: ${e.message}")
            emptyList()
        }
    }

    // ===== THREAD SUPPORT =====

    suspend fun getThreadChildren(threadID: String): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: 🔍 Querying children for threadID: $threadID")

            val snapshot = db.collection("videos")
                .whereEqualTo("threadID", threadID)
                .limit(50)
                .get()
                .await()

            println("VIDEO SERVICE: 📊 Raw query returned ${snapshot.documents.size} documents for threadID: $threadID")

            // Log each document's details for debugging
            snapshot.documents.forEach { doc ->
                val data = doc.data
                val docThreadID = data?.get("threadID") as? String
                val docDepth = (data?.get("conversationDepth") as? Long)?.toInt() ?: 0
                val docDeleted = data?.get("isDeleted") as? Boolean ?: false
                println("VIDEO SERVICE: 📄 Doc ${doc.id}: threadID=$docThreadID, depth=$docDepth, deleted=$docDeleted")
            }

            val allVideos = convertFirebaseToVideoMetadata(snapshot.documents)
            println("VIDEO SERVICE: 📊 Converted ${allVideos.size} videos")

            val children = allVideos.filter { it.conversationDepth > 0 && !it.isDeleted }
                .sortedBy { it.createdAt }

            println("VIDEO SERVICE: ✅ Found ${children.size} children (depth > 0, not deleted) for thread $threadID")
            children
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Children query failed: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Alternative: Get children by replyToVideoID (if that's how your data is structured)
     */
    suspend fun getThreadChildrenByReplyTo(parentVideoID: String): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: 🔍 Querying children by replyToVideoID: $parentVideoID")

            val snapshot = db.collection("videos")
                .whereEqualTo("replyToVideoID", parentVideoID)
                .limit(50)
                .get()
                .await()

            println("VIDEO SERVICE: 📊 ReplyTo query returned ${snapshot.documents.size} documents")

            val allVideos = convertFirebaseToVideoMetadata(snapshot.documents)
            val children = allVideos.filter { !it.isDeleted }
                .sortedBy { it.createdAt }

            println("VIDEO SERVICE: ✅ Found ${children.size} children by replyToVideoID")
            children
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ ReplyTo query failed: ${e.message}")
            emptyList()
        }
    }

    // ===== USER VIDEOS =====

    suspend fun getUserVideos(userID: String, limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            val snapshot = db.collection("videos")
                .whereEqualTo("creatorID", userID)
                .limit(limit.toLong())
                .get()
                .await()

            val videos = convertFirebaseToVideoMetadata(snapshot.documents)
                .filter { !it.isDeleted }
                .sortedByDescending { it.createdAt }

            println("VIDEO SERVICE: ✅ Found ${videos.size} videos for user $userID")
            videos
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ User videos query failed: ${e.message}")
            emptyList()
        }
    }

    // ===== DISCOVERY =====

    suspend fun getDiscoveryVideos(limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            val snapshot = db.collection("videos")
                .limit(limit.toLong())
                .get()
                .await()

            val videos = convertFirebaseToVideoMetadata(snapshot.documents)
                .filter { it.conversationDepth == 0 && !it.isDeleted }
                .sortedByDescending { it.trendingScore }

            println("VIDEO SERVICE: ✅ Found ${videos.size} discovery videos")
            videos
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Discovery query failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getDiscoveryVideos(category: String, limit: Int): List<CoreVideoMetadata> {
        return getDiscoveryVideos(limit)
    }

    suspend fun getPersonalizedVideos(userID: String, limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            val snapshot = db.collection("videos")
                .limit(limit.toLong())
                .get()
                .await()

            val videos = convertFirebaseToVideoMetadata(snapshot.documents)
                .filter { it.conversationDepth == 0 && !it.isDeleted }
                .sortedByDescending { it.engagementRatio }

            println("VIDEO SERVICE: ✅ Found ${videos.size} personalized videos")
            videos
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Personalized query failed: ${e.message}")
            emptyList()
        }
    }

    // ===== SEARCH =====

    suspend fun searchVideos(query: String, limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            if (query.length < 2) return emptyList()

            val snapshot = db.collection("videos")
                .limit(200)
                .get()
                .await()

            val allVideos = convertFirebaseToVideoMetadata(snapshot.documents)
            val queryLower = query.lowercase()

            val matching = allVideos.filter { video ->
                !video.isDeleted && (
                        video.title.lowercase().contains(queryLower) ||
                                video.description.lowercase().contains(queryLower) ||
                                video.creatorName.lowercase().contains(queryLower)
                        )
            }.take(limit)

            println("VIDEO SERVICE: ✅ Found ${matching.size} search results")
            matching
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Search failed: ${e.message}")
            emptyList()
        }
    }

    // ===== ANALYTICS =====

    /**
     * Record a video view - stores viewer data for the viewers list
     */
    suspend fun recordVideoView(videoID: String, userID: String, userData: Map<String, Any>? = null) {
        try {
            // Increment view count on video document
            db.collection("videos").document(videoID)
                .update("viewCount", com.google.firebase.firestore.FieldValue.increment(1))
                .await()

            // Store viewer record in subcollection (for viewers list)
            val viewerData = hashMapOf<String, Any>(
                "userID" to userID,
                "displayName" to (userData?.get("displayName") ?: "User"),
                "username" to (userData?.get("username") ?: ""),
                "profileImageURL" to (userData?.get("profileImageURL") ?: ""),
                "tier" to (userData?.get("tier") ?: "ROOKIE"),
                "viewedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            // Use userID as document ID to prevent duplicate entries per user
            db.collection("videos")
                .document(videoID)
                .collection("views")
                .document(userID)
                .set(viewerData)
                .await()

            println("VIDEO SERVICE: ✅ Recorded view for video $videoID by user $userID")
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Failed to record view: ${e.message}")
        }
    }

    /**
     * Simple view count increment (without storing viewer data)
     */
    suspend fun incrementViewCount(videoID: String) {
        try {
            db.collection("videos").document(videoID)
                .update("viewCount", com.google.firebase.firestore.FieldValue.increment(1))
                .await()
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Failed to increment view: ${e.message}")
        }
    }

    /**
     * Get viewers for a video
     * Returns list of viewer data for display in viewers sheet
     */
    suspend fun getViewers(videoID: String): List<ViewerData> {
        return try {
            println("VIDEO SERVICE: 👁️ Loading viewers for video $videoID")

            val snapshot = db.collection("videos")
                .document(videoID)
                .collection("views")
                .orderBy("viewedAt", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()

            val viewers = snapshot.documents.mapNotNull { doc ->
                try {
                    val data = doc.data ?: return@mapNotNull null
                    ViewerData(
                        userID = data["userID"] as? String ?: doc.id,
                        displayName = data["displayName"] as? String ?: "Unknown",
                        username = data["username"] as? String ?: "",
                        profileImageURL = data["profileImageURL"] as? String,
                        tier = data["tier"] as? String ?: "ROOKIE",
                        viewedAt = (data["viewedAt"] as? Timestamp)?.toDate() ?: Date()
                    )
                } catch (e: Exception) {
                    null
                }
            }

            println("VIDEO SERVICE: ✅ Found ${viewers.size} viewers")
            viewers
        } catch (e: Exception) {
            println("VIDEO SERVICE: ❌ Viewers query failed: ${e.message}")
            emptyList()
        }
    }

    // ===== FIREBASE CONVERSION =====

    private fun convertFirebaseToVideoMetadata(documents: List<DocumentSnapshot>): List<CoreVideoMetadata> {
        return documents.mapNotNull { doc ->
            try {
                val data = doc.data ?: return@mapNotNull null

                CoreVideoMetadata(
                    id = doc.id,
                    title = data["title"] as? String ?: "Untitled",
                    description = data["description"] as? String ?: "",
                    videoURL = data["videoURL"] as? String ?: "",
                    thumbnailURL = data["thumbnailURL"] as? String ?: "",
                    creatorID = data["creatorID"] as? String ?: "",
                    creatorName = data["creatorName"] as? String ?: "",
                    hashtags = (data["hashtags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
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
                    qualityScore = (data["qualityScore"] as? Number)?.toInt() ?: 50,
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

    private fun parseContentType(str: String?): ContentType {
        return when (str?.uppercase()) {
            "THREAD" -> ContentType.THREAD
            "CHILD" -> ContentType.CHILD
            "STEPCHILD" -> ContentType.STEPCHILD
            else -> ContentType.THREAD
        }
    }

    private fun parseTemperature(str: String?): Temperature {
        return when (str?.uppercase()) {
            "BLAZING" -> Temperature.BLAZING
            "HOT" -> Temperature.HOT
            "WARM" -> Temperature.WARM
            "COOL" -> Temperature.COOL
            "COLD" -> Temperature.COLD
            "FROZEN" -> Temperature.FROZEN
            else -> Temperature.WARM
        }
    }
}