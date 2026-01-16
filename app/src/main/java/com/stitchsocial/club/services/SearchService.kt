/*
 * SearchService.kt - FIXED: PARENT VIDEOS ONLY IN DISCOVERY
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - User & Video Search with Hashtag Support
 *
 * ✅ FIXED: getRecentVideos now filters conversationDepth == 0 (parents only)
 * ✅ FIXED: searchVideos now filters conversationDepth == 0 (parents only)
 * ✅ FIXED: searchByHashtag now filters conversationDepth == 0 (parents only)
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.Temperature
import com.stitchsocial.club.foundation.ContentType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Android SearchService - exact port of iOS implementation with hashtag support
 * Layer 4: Services - Simple user/video discovery + hashtag search
 */
class SearchService {

    // Use stitchfin database
    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val USERS_COLLECTION = "users"
        private const val VIDEOS_COLLECTION = "videos"
        private const val DEFAULT_LIMIT = 50
    }

    // MARK: - Core Search Methods (iOS Port)

    /**
     * Search users - if query empty, show all users for browsing
     */
    suspend fun searchUsers(
        query: String,
        limit: Int = DEFAULT_LIMIT
    ): List<BasicUserInfo> {
        println("🔍 SEARCH: searchUsers called - query: '$query', limit: $limit")

        val trimmedQuery = query.trim()

        return if (trimmedQuery.isEmpty()) {
            getAllUsers(limit)
        } else {
            searchUsersByText(trimmedQuery, limit)
        }
    }

    /**
     * Get all users for browsing
     */
    private suspend fun getAllUsers(limit: Int = DEFAULT_LIMIT): List<BasicUserInfo> {
        return try {
            val currentUserID = auth.currentUser?.uid
            println("🔍 SEARCH: getAllUsers - currentUserID: $currentUserID")

            val snapshot = db.collection(USERS_COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val users = processUserDocuments(snapshot.documents, currentUserID)
            println("✅ SEARCH: getAllUsers loaded ${users.size} users")
            users

        } catch (e: Exception) {
            println("❌ SEARCH: getAllUsers failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search users by text
     */
    private suspend fun searchUsersByText(
        query: String,
        limit: Int
    ): List<BasicUserInfo> {
        return try {
            val currentUserID = auth.currentUser?.uid
            val lowercaseQuery = query.lowercase()

            println("🔍 SEARCH: searchUsersByText - query: '$lowercaseQuery'")

            val usernameSnapshot = db.collection(USERS_COLLECTION)
                .whereGreaterThanOrEqualTo("username", lowercaseQuery)
                .whereLessThan("username", lowercaseQuery + "\uf8ff")
                .limit((limit * 0.7).toLong())
                .get()
                .await()

            val displayNameSnapshot = db.collection(USERS_COLLECTION)
                .whereGreaterThanOrEqualTo("displayName", lowercaseQuery)
                .whereLessThan("displayName", lowercaseQuery + "\uf8ff")
                .limit((limit * 0.3).toLong())
                .get()
                .await()

            val allDocuments = (usernameSnapshot.documents + displayNameSnapshot.documents)
                .distinctBy { it.id }

            val users = processUserDocuments(allDocuments, currentUserID)
            println("✅ SEARCH: searchUsersByText found ${users.size} users")

            if (users.size > limit) users.subList(0, limit) else users

        } catch (e: Exception) {
            println("❌ SEARCH: searchUsersByText failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search videos by title - PARENT VIDEOS ONLY
     */
    suspend fun searchVideos(
        query: String,
        limit: Int = 20
    ): List<CoreVideoMetadata> {
        return try {
            val lowercaseQuery = query.lowercase()

            // ✅ FIX: Filter by conversationDepth == 0 (parent videos only)
            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereEqualTo("conversationDepth", 0)
                .whereGreaterThanOrEqualTo("title", lowercaseQuery)
                .whereLessThan("title", lowercaseQuery + "\uf8ff")
                .limit(limit.toLong())
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
            println("✅ SEARCH: searchVideos found ${videos.size} parent videos")
            videos

        } catch (e: Exception) {
            println("❌ SEARCH: searchVideos failed: ${e.message}")
            // Fallback: Query without conversationDepth filter, then filter in code
            searchVideosFallback(query, limit)
        }
    }

    /**
     * Fallback search if composite index doesn't exist
     */
    private suspend fun searchVideosFallback(
        query: String,
        limit: Int
    ): List<CoreVideoMetadata> {
        return try {
            val lowercaseQuery = query.lowercase()

            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereGreaterThanOrEqualTo("title", lowercaseQuery)
                .whereLessThan("title", lowercaseQuery + "\uf8ff")
                .limit((limit * 2).toLong()) // Get more to filter
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
                .filter { it.conversationDepth == 0 } // Filter parents only
                .take(limit)

            println("✅ SEARCH: searchVideosFallback found ${videos.size} parent videos")
            videos

        } catch (e: Exception) {
            println("❌ SEARCH: searchVideosFallback failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get recent videos for discovery - PARENT VIDEOS ONLY
     * ✅ FIXED: Now filters conversationDepth == 0
     */
    suspend fun getRecentVideos(limit: Int = 20): List<CoreVideoMetadata> {
        return try {
            println("🔍 SEARCH: getRecentVideos - loading parent videos only")

            // ✅ FIX: Filter by conversationDepth == 0 (parent videos only)
            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereEqualTo("conversationDepth", 0)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
            println("✅ SEARCH: getRecentVideos loaded ${videos.size} parent videos")
            videos

        } catch (e: Exception) {
            println("❌ SEARCH: getRecentVideos failed: ${e.message}")
            // Fallback: Query without filter, then filter in code
            getRecentVideosFallback(limit)
        }
    }

    /**
     * Fallback if composite index doesn't exist
     */
    private suspend fun getRecentVideosFallback(limit: Int): List<CoreVideoMetadata> {
        return try {
            println("🔍 SEARCH: getRecentVideosFallback - using code filter")

            val snapshot = db.collection(VIDEOS_COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit((limit * 3).toLong()) // Get more to filter
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
                .filter { it.conversationDepth == 0 } // Filter parents only
                .take(limit)

            println("✅ SEARCH: getRecentVideosFallback loaded ${videos.size} parent videos")
            videos

        } catch (e: Exception) {
            println("❌ SEARCH: getRecentVideosFallback failed: ${e.message}")
            emptyList()
        }
    }

    // MARK: - Hashtag Search Methods

    /**
     * Search videos by hashtag - PARENT VIDEOS ONLY
     */
    suspend fun searchByHashtag(hashtag: String, limit: Int = DEFAULT_LIMIT): List<CoreVideoMetadata> {
        return try {
            val cleanHashtag = hashtag.removePrefix("#").lowercase()
            println("🏷️ SEARCH: searchByHashtag - hashtag: '$cleanHashtag'")

            // ✅ FIX: Filter by conversationDepth == 0 (parent videos only)
            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereArrayContains("hashtags", cleanHashtag)
                .whereEqualTo("conversationDepth", 0)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
            println("✅ SEARCH: searchByHashtag found ${videos.size} parent videos for #$cleanHashtag")
            videos

        } catch (e: Exception) {
            println("❌ SEARCH: searchByHashtag failed: ${e.message}")
            // Fallback
            searchByHashtagFallback(hashtag, limit)
        }
    }

    /**
     * Fallback hashtag search
     */
    private suspend fun searchByHashtagFallback(hashtag: String, limit: Int): List<CoreVideoMetadata> {
        return try {
            val cleanHashtag = hashtag.removePrefix("#").lowercase()

            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereArrayContains("hashtags", cleanHashtag)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit((limit * 2).toLong())
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
                .filter { it.conversationDepth == 0 }
                .take(limit)

            println("✅ SEARCH: searchByHashtagFallback found ${videos.size} parent videos")
            videos

        } catch (e: Exception) {
            println("❌ SEARCH: searchByHashtagFallback failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get trending hashtags based on recent usage
     */
    suspend fun getTrendingHashtags(limit: Int = 20): List<String> {
        return try {
            println("📈 SEARCH: getTrendingHashtags - loading recent videos")

            val cutoffDate = Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000)
            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereGreaterThan("createdAt", Timestamp(cutoffDate))
                .whereEqualTo("conversationDepth", 0) // Only count from parent videos
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(500)
                .get()
                .await()

            val hashtagCounts = mutableMapOf<String, Int>()

            for (document in snapshot.documents) {
                @Suppress("UNCHECKED_CAST")
                val hashtags = document.get("hashtags") as? List<String> ?: continue
                hashtags.forEach { hashtag ->
                    val clean = hashtag.lowercase()
                    hashtagCounts[clean] = hashtagCounts.getOrDefault(clean, 0) + 1
                }
            }

            val trending = hashtagCounts.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }

            println("✅ SEARCH: getTrendingHashtags found ${trending.size} trending hashtags")
            trending

        } catch (e: Exception) {
            println("❌ SEARCH: getTrendingHashtags failed: ${e.message}")
            emptyList()
        }
    }

    // MARK: - Document Processing

    /**
     * Process user documents into BasicUserInfo list
     */
    private fun processUserDocuments(
        documents: List<DocumentSnapshot>,
        currentUserID: String?
    ): List<BasicUserInfo> {
        return documents.mapNotNull { doc ->
            BasicUserInfo.fromFirebaseDocument(doc)
        }.filter { it.id != currentUserID }
    }

    /**
     * Process video documents into CoreVideoMetadata list
     */
    private fun processVideoDocuments(documents: List<DocumentSnapshot>): List<CoreVideoMetadata> {
        val videos = mutableListOf<CoreVideoMetadata>()

        for (document in documents) {
            val data = document.data ?: continue

            try {
                val temperatureString = data["temperature"] as? String ?: "WARM"
                val temperature = try {
                    Temperature.valueOf(temperatureString.uppercase())
                } catch (e: IllegalArgumentException) {
                    Temperature.WARM
                }

                val contentTypeString = data["contentType"] as? String ?: "THREAD"
                val contentType = try {
                    ContentType.valueOf(contentTypeString.uppercase())
                } catch (e: IllegalArgumentException) {
                    ContentType.THREAD
                }

                @Suppress("UNCHECKED_CAST")
                val hashtagsRaw = data["hashtags"] as? List<String>
                val hashtags = hashtagsRaw?.map { it.lowercase() } ?: emptyList()

                val video = CoreVideoMetadata(
                    id = document.id,
                    title = data["title"] as? String ?: "",
                    description = data["description"] as? String ?: "",
                    videoURL = data["videoURL"] as? String ?: "",
                    thumbnailURL = data["thumbnailURL"] as? String ?: "",
                    creatorID = data["creatorID"] as? String ?: "",
                    creatorName = data["creatorName"] as? String ?: "",
                    createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
                    hashtags = hashtags,
                    threadID = data["threadID"] as? String,
                    replyToVideoID = data["replyToVideoID"] as? String,
                    conversationDepth = (data["conversationDepth"] as? Long)?.toInt() ?: 0,
                    viewCount = (data["viewCount"] as? Long)?.toInt() ?: 0,
                    hypeCount = (data["hypeCount"] as? Long)?.toInt() ?: 0,
                    coolCount = (data["coolCount"] as? Long)?.toInt() ?: 0,
                    replyCount = (data["replyCount"] as? Long)?.toInt() ?: 0,
                    shareCount = (data["shareCount"] as? Long)?.toInt() ?: 0,
                    lastEngagementAt = (data["lastEngagementAt"] as? Timestamp)?.toDate(),
                    duration = data["duration"] as? Double ?: 0.0,
                    aspectRatio = data["aspectRatio"] as? Double ?: (9.0 / 16.0),
                    fileSize = data["fileSize"] as? Long ?: 0L,
                    contentType = contentType,
                    temperature = temperature,
                    qualityScore = (data["qualityScore"] as? Long)?.toInt() ?: 50,
                    engagementRatio = data["engagementRatio"] as? Double ?: 0.5,
                    velocityScore = data["velocityScore"] as? Double ?: 0.0,
                    trendingScore = data["trendingScore"] as? Double ?: 0.0,
                    discoverabilityScore = data["discoverabilityScore"] as? Double ?: 0.5,
                    isPromoted = data["isPromoted"] as? Boolean ?: false,
                    isProcessing = data["isProcessing"] as? Boolean ?: false,
                    isDeleted = data["isDeleted"] as? Boolean ?: false
                )

                videos.add(video)

            } catch (e: Exception) {
                println("⚠️ SEARCH: Failed to process video document ${document.id}: ${e.message}")
            }
        }

        return videos.toList()
    }

    /**
     * Test method to verify service is working
     */
    suspend fun testSearchService() {
        println("🔍 SEARCH: Testing simple user discovery...")

        try {
            val users = getAllUsers(5)
            println("✅ SEARCH: Successfully loaded ${users.size} users")
        } catch (e: Exception) {
            println("❌ SEARCH: Test failed: ${e.message}")
        }
    }
}

// MARK: - Search Results Container

data class SearchResults(
    val users: List<BasicUserInfo> = emptyList(),
    val videos: List<CoreVideoMetadata> = emptyList(),
    val hashtags: List<String> = emptyList()
) {
    val totalResults: Int get() = users.size + videos.size + hashtags.size
    val hasResults: Boolean get() = totalResults > 0
}