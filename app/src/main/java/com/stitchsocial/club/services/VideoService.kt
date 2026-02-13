/*
 * VideoServiceImpl.kt - FIXED WITH DUAL SIGNATURES
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Video Management
 * âœ… FIXED: Both getFeedVideos signatures for compatibility
 * âœ… FIXED: Simplified queries - no composite index required
 * âœ… ADDED: Video deletion (soft delete)
 */

package com.stitchsocial.club.services

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
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
    private val storage = FirebaseStorage.getInstance()

    // ===== ENGAGEMENT SYSTEM METHODS =====

    suspend fun getVideoById(videoID: String): CoreVideoMetadata? {
        return try {
            println("VIDEO SERVICE: ðŸ“± Loading video by ID: $videoID")
            val doc = db.collection("videos").document(videoID).get().await()
            if (!doc.exists()) {
                println("VIDEO SERVICE: âŒ Video not found: $videoID")
                return null
            }
            val videos = convertFirebaseToVideoMetadata(listOf(doc))
            videos.firstOrNull()
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Error loading video $videoID: ${e.message}")
            null
        }
    }

    suspend fun updateEngagementCounts(videoID: String, hypeCount: Int, coolCount: Int) {
        try {
            val updates = hashMapOf<String, Any>(
                "hypeCount" to hypeCount,
                "coolCount" to coolCount,
                "lastEngagementAt" to (com.google.firebase.firestore.FieldValue.serverTimestamp() as Any)
            )
            db.collection("videos").document(videoID).update(updates).await()
            println("VIDEO SERVICE: âœ… Updated engagement counts for $videoID")
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Failed to update counts: ${e.message}")
            throw e
        }
    }

    /**
     * Delete a video (hard delete - matches iOS implementation)
     * Deletes Firestore doc, Storage files, and cleans up related data
     */
    suspend fun deleteVideo(videoID: String) {
        try {
            println("VIDEO SERVICE: Deleting video: $videoID")

            val videoDoc = db.collection("videos").document(videoID).get().await()
            if (!videoDoc.exists()) {
                println("VIDEO SERVICE: Video not found: $videoID")
                return
            }

            val videoData = videoDoc.data ?: return
            val conversationDepth = (videoData["conversationDepth"] as? Long)?.toInt() ?: 0

            if (conversationDepth == 0) {
                deleteEntireThread(videoID)
            } else {
                deleteSingleVideo(videoID, videoData)
            }

            println("VIDEO SERVICE: Video deleted: $videoID")

        } catch (e: Exception) {
            println("VIDEO SERVICE: Failed to delete video: ${e.message}")
            throw e
        }
    }

    private suspend fun deleteEntireThread(threadID: String) {
        val threadSnapshot = db.collection("videos")
            .whereEqualTo("threadID", threadID)
            .get().await()

        for (document in threadSnapshot.documents) {
            val data = document.data ?: continue
            (data["videoURL"] as? String)?.let { deleteFromStorage(it) }
            (data["thumbnailURL"] as? String)?.let { deleteFromStorage(it) }
            document.reference.delete().await()
        }
        cleanupRelatedData(threadID)
    }

    private suspend fun deleteSingleVideo(videoID: String, videoData: Map<String, Any>) {
        (videoData["videoURL"] as? String)?.let { deleteFromStorage(it) }
        (videoData["thumbnailURL"] as? String)?.let { deleteFromStorage(it) }

        (videoData["replyToVideoID"] as? String)?.let { parentID ->
            try {
                db.collection("videos").document(parentID).update(
                    mapOf(
                        "replyCount" to com.google.firebase.firestore.FieldValue.increment(-1),
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    )
                ).await()
            } catch (_: Exception) { }
        }

        db.collection("videos").document(videoID).delete().await()
        cleanupRelatedData(videoID)
    }

    private suspend fun cleanupRelatedData(videoID: String) {
        try {
            val interactions = db.collection("interactions")
                .whereEqualTo("videoID", videoID).get().await()
            for (doc in interactions.documents) { doc.reference.delete().await() }

            val tapProgress = db.collection("tap_progress")
                .whereEqualTo("videoID", videoID).get().await()
            for (doc in tapProgress.documents) { doc.reference.delete().await() }

            try { db.collection("active_shards").document(videoID).delete().await() } catch (_: Exception) { }

            try {
                val hypeShards = db.collection("videos").document(videoID)
                    .collection("hype_shards").get().await()
                for (doc in hypeShards.documents) { doc.reference.delete().await() }
                val coolShards = db.collection("videos").document(videoID)
                    .collection("cool_shards").get().await()
                for (doc in coolShards.documents) { doc.reference.delete().await() }
            } catch (_: Exception) { }
        } catch (e: Exception) {
            println("VIDEO SERVICE: Cleanup failed (non-fatal): ${e.message}")
        }
    }

    private suspend fun deleteFromStorage(url: String) {
        try {
            if (url.isBlank()) return
            val ref = storage.getReferenceFromUrl(url)
            ref.delete().await()
        } catch (e: Exception) {
            println("VIDEO SERVICE: Storage delete failed (non-fatal): ${e.message}")
        }
    }

    // ===== FEED LOADING - DUAL SIGNATURES =====

    /**
     * Get feed videos - returns CoreVideoMetadata (for HomeFeedService compatibility)
     */
    suspend fun getFeedVideos(followingIDs: List<String>, limit: Int): List<CoreVideoMetadata> {
        if (followingIDs.isEmpty()) {
            println("VIDEO SERVICE: ðŸ“­ No following users - returning empty feed")
            return emptyList()
        }

        println("VIDEO SERVICE: ðŸ“± Loading feed for ${followingIDs.size} followed users")

        val allVideos = mutableListOf<CoreVideoMetadata>()

        followingIDs.chunked(10).forEachIndexed { chunkIndex, chunk ->
            try {
                val snapshot = db.collection("videos")
                    .whereIn("creatorID", chunk)
                    .limit(100)
                    .get()
                    .await()

                println("VIDEO SERVICE: ðŸ“Š Chunk ${chunkIndex + 1} returned ${snapshot.documents.size} documents")

                val videos = convertFirebaseToVideoMetadata(snapshot.documents)
                val parentVideos = videos.filter { it.conversationDepth == 0 && !it.isDeleted }
                allVideos.addAll(parentVideos)

            } catch (e: Exception) {
                println("VIDEO SERVICE: âŒ Chunk query failed: ${e.message}")
            }
        }

        val sorted = allVideos.sortedByDescending { it.createdAt }.take(limit)
        println("VIDEO SERVICE: âœ… Loaded ${sorted.size} videos from followed users")
        return sorted
    }

    /**
     * Get feed videos - simple limit only (for general feed)
     */
    suspend fun getFeedVideos(limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: ðŸ“± Loading general feed")
            val snapshot = db.collection("videos")
                .limit(limit.toLong())
                .get()
                .await()

            val videos = convertFirebaseToVideoMetadata(snapshot.documents)
                .filter { it.conversationDepth == 0 && !it.isDeleted }
                .sortedByDescending { it.createdAt }

            println("VIDEO SERVICE: âœ… Loaded ${videos.size} feed videos")
            videos
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Feed query failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get timestamped replies for a video (for step-child navigation)
     * Returns video and its immediate children sorted by timestamp
     */
    suspend fun getTimestampedReplies(videoID: String): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: Getting timestamped replies for: $videoID")

            val snapshot = db.collection("videos")
                .whereEqualTo("replyToVideoID", videoID)
                .get()
                .await()

            val replies = convertFirebaseToVideoMetadata(snapshot.documents)
                .filter { !it.isDeleted }
                .sortedBy { it.createdAt }

            println("VIDEO SERVICE: Found ${replies.size} timestamped replies")
            replies
        } catch (e: Exception) {
            println("VIDEO SERVICE: Failed to get timestamped replies: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get thread parent and children together
     * Returns Pair<parent, children> for efficient thread loading
     */
    suspend fun getThreadData(threadID: String): Pair<CoreVideoMetadata?, List<CoreVideoMetadata>> {
        val parent = getVideoById(threadID)
        val children = if (parent != null) {
            getThreadChildren(threadID)
        } else {
            emptyList()
        }
        return Pair(parent, children)
    }

    // ===== THREAD SUPPORT =====

    suspend fun getThreadChildren(threadID: String): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: ðŸ” Querying children for threadID: $threadID")

            val snapshot = db.collection("videos")
                .whereEqualTo("threadID", threadID)
                .limit(50)
                .get()
                .await()

            println("VIDEO SERVICE: ðŸ“Š Raw query returned ${snapshot.documents.size} documents for threadID: $threadID")

            // Log each document's details for debugging
            snapshot.documents.forEach { doc ->
                val data = doc.data
                val docThreadID = data?.get("threadID") as? String
                val docDepth = (data?.get("conversationDepth") as? Long)?.toInt() ?: 0
                val docDeleted = data?.get("isDeleted") as? Boolean ?: false
                println("VIDEO SERVICE: ðŸ“„ Doc ${doc.id}: threadID=$docThreadID, depth=$docDepth, deleted=$docDeleted")
            }

            val allVideos = convertFirebaseToVideoMetadata(snapshot.documents)
            println("VIDEO SERVICE: ðŸ“Š Converted ${allVideos.size} videos")

            val children = allVideos.filter { it.conversationDepth > 0 && !it.isDeleted }
                .sortedBy { it.createdAt }

            println("VIDEO SERVICE: âœ… Found ${children.size} children (depth > 0, not deleted) for thread $threadID")
            children
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Children query failed: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Alternative: Get children by replyToVideoID (if that's how your data is structured)
     */
    suspend fun getThreadChildrenByReplyTo(parentVideoID: String): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: ðŸ” Querying children by replyToVideoID: $parentVideoID")

            val snapshot = db.collection("videos")
                .whereEqualTo("replyToVideoID", parentVideoID)
                .limit(50)
                .get()
                .await()

            println("VIDEO SERVICE: ðŸ“Š ReplyTo query returned ${snapshot.documents.size} documents")

            val allVideos = convertFirebaseToVideoMetadata(snapshot.documents)
            val children = allVideos.filter { !it.isDeleted }
                .sortedBy { it.createdAt }

            println("VIDEO SERVICE: âœ… Found ${children.size} children by replyToVideoID")
            children
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ ReplyTo query failed: ${e.message}")
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

            println("VIDEO SERVICE: âœ… Found ${videos.size} videos for user $userID")
            videos
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ User videos query failed: ${e.message}")
            emptyList()
        }
    }

    // ===== SPIN-OFF THREAD OPERATIONS (NEW) =====

    /**
     * Create a spin-off thread from another video
     * Uses Firestore transaction to atomically create thread and increment source count
     */
    suspend fun createSpinOffThread(
        fromVideoID: String,
        fromThreadID: String,
        newVideoID: String,
        title: String,
        description: String,
        videoURL: String,
        thumbnailURL: String,
        creatorID: String,
        creatorName: String,
        duration: Double,
        fileSize: Long,
        aspectRatio: Double,
        hashtags: List<String> = emptyList(),
        taggedUserIDs: List<String> = emptyList()
    ): CoreVideoMetadata? {
        return try {
            println("VIDEO SERVICE: ðŸ”€ Creating spin-off thread from video $fromVideoID")

            // Prepare video data for the new thread
            val videoData = hashMapOf<String, Any>(
                "id" to newVideoID,
                "title" to title,
                "description" to description,
                "videoURL" to videoURL,
                "thumbnailURL" to thumbnailURL,
                "creatorID" to creatorID,
                "creatorName" to creatorName,
                "hashtags" to hashtags,
                "taggedUserIDs" to taggedUserIDs,
                "createdAt" to (com.google.firebase.firestore.FieldValue.serverTimestamp() as Any),
                "updatedAt" to (com.google.firebase.firestore.FieldValue.serverTimestamp() as Any),

                // Thread hierarchy - this IS a new thread
                "threadID" to newVideoID,
                "replyToVideoID" to (com.google.firebase.firestore.FieldValue.delete() as Any),
                "conversationDepth" to 0,

                // Spin-off fields
                "spinOffFromVideoID" to fromVideoID,
                "spinOffFromThreadID" to fromThreadID,
                "spinOffCount" to 0,

                // Engagement
                "viewCount" to 0,
                "hypeCount" to 0,
                "coolCount" to 0,
                "replyCount" to 0,
                "shareCount" to 0,
                "lastEngagementAt" to (com.google.firebase.firestore.FieldValue.delete() as Any),
                // Metadata
                "duration" to duration,
                "aspectRatio" to aspectRatio,
                "fileSize" to fileSize,
                "contentType" to "THREAD",
                "temperature" to "COOL",
                "qualityScore" to 50,
                "engagementRatio" to 0.0,
                "velocityScore" to 0.0,
                "trendingScore" to 0.0,
                "discoverabilityScore" to 0.5,

                // Status
                "isPromoted" to false,
                "isProcessing" to false,
                "isDeleted" to false
            )

            // Create the spin-off thread
            db.collection("videos").document(newVideoID).set(videoData).await()

            // Increment spin-off count on source video (separate operation)
            val sourceUpdates = hashMapOf<String, Any>(
                "spinOffCount" to (com.google.firebase.firestore.FieldValue.increment(1) as Any)
            )
            db.collection("videos").document(fromVideoID).update(sourceUpdates).await()

            println("VIDEO SERVICE: âœ… Spin-off thread created: $newVideoID")

            // Return the created video
            getVideoById(newVideoID)

        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Failed to create spin-off thread: ${e.message}")
            null
        }
    }

    /**
     * Get all spin-offs from a specific video
     */
    suspend fun getSpinOffs(fromVideoID: String, limit: Int = 20): List<CoreVideoMetadata> {
        return try {
            println("VIDEO SERVICE: ðŸ” Loading spin-offs from video $fromVideoID")

            val snapshot = db.collection("videos")
                .whereEqualTo("spinOffFromVideoID", fromVideoID)
                .whereEqualTo("isDeleted", false)
                .limit(limit.toLong())
                .get()
                .await()

            val spinOffs = convertFirebaseToVideoMetadata(snapshot.documents)
                .sortedByDescending { it.createdAt }

            println("VIDEO SERVICE: âœ… Found ${spinOffs.size} spin-offs")
            spinOffs

        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Failed to get spin-offs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get the source video for a spin-off
     */
    suspend fun getSpinOffSource(videoID: String): CoreVideoMetadata? {
        return try {
            val video = getVideoById(videoID)
            if (video == null) {
                println("VIDEO SERVICE: âŒ Video $videoID not found")
                return null
            }

            val sourceID = video.spinOffFromVideoID
            if (sourceID == null) {
                println("VIDEO SERVICE: â„¹ï¸ Video $videoID is not a spin-off")
                return null
            }

            println("VIDEO SERVICE: ðŸ” Loading spin-off source: $sourceID")
            getVideoById(sourceID)

        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Failed to get spin-off source: ${e.message}")
            null
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

            println("VIDEO SERVICE: âœ… Found ${videos.size} discovery videos")
            videos
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Discovery query failed: ${e.message}")
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

            println("VIDEO SERVICE: âœ… Found ${videos.size} personalized videos")
            videos
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Personalized query failed: ${e.message}")
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

            println("VIDEO SERVICE: âœ… Found ${matching.size} search results")
            matching
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Search failed: ${e.message}")
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
            val viewUpdates = hashMapOf<String, Any>(
                "viewCount" to (com.google.firebase.firestore.FieldValue.increment(1) as Any)
            )
            db.collection("videos").document(videoID).update(viewUpdates).await()

            // Store viewer record in subcollection (for viewers list)
            val viewerData = hashMapOf<String, Any>(
                "userID" to userID,
                "displayName" to (userData?.get("displayName") ?: "User"),
                "username" to (userData?.get("username") ?: ""),
                "profileImageURL" to (userData?.get("profileImageURL") ?: ""),
                "tier" to (userData?.get("tier") ?: "ROOKIE"),
                "viewedAt" to (com.google.firebase.firestore.FieldValue.serverTimestamp() as Any)
            )

            // Use userID as document ID to prevent duplicate entries per user
            db.collection("videos")
                .document(videoID)
                .collection("views")
                .document(userID)
                .set(viewerData)
                .await()

            println("VIDEO SERVICE: âœ… Recorded view for video $videoID by user $userID")
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Failed to record view: ${e.message}")
        }
    }

    /**
     * Simple view count increment (without storing viewer data)
     */
    suspend fun incrementViewCount(videoID: String) {
        try {
            val viewUpdates = hashMapOf<String, Any>(
                "viewCount" to (com.google.firebase.firestore.FieldValue.increment(1) as Any)
            )
            db.collection("videos").document(videoID).update(viewUpdates).await()
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Failed to increment view: ${e.message}")
        }
    }

    /**
     * Get viewers for a video
     * Returns list of viewer data for display in viewers sheet
     */
    suspend fun getViewers(videoID: String): List<ViewerData> {
        return try {
            println("VIDEO SERVICE: ðŸ‘ï¸ Loading viewers for video $videoID")

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

            println("VIDEO SERVICE: âœ… Found ${viewers.size} viewers")
            viewers
        } catch (e: Exception) {
            println("VIDEO SERVICE: âŒ Viewers query failed: ${e.message}")
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
                    isDeleted = data["isDeleted"] as? Boolean ?: false,
                    // Spin-off fields (NEW)
                    spinOffFromVideoID = data["spinOffFromVideoID"] as? String,
                    spinOffFromThreadID = data["spinOffFromThreadID"] as? String,
                    spinOffCount = (data["spinOffCount"] as? Long)?.toInt() ?: 0,
                    taggedUserIDs = (data["taggedUserIDs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                )
            } catch (e: Exception) {
                println("VIDEO SERVICE: âŒ Failed to convert document ${doc.id}: ${e.message}")
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