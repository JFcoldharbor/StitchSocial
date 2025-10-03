/*
 * SearchService.kt - COMPLETE WITH HASHTAG SUPPORT
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - User & Video Search with Hashtag Support
 * Dependencies: Firebase Firestore, BasicUserInfo, CoreVideoMetadata
 * Features: User discovery, video search, hashtag search, trending hashtags
 * BLUEPRINT: SearchService.swift exact port + hashtag extensions
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

    // Use default database for consistency
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
     * EXACT iOS PORT: SearchService.swift searchUsers method
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
     * Get all users for browsing - SIMPLE QUERY, NO INDEXES
     * EXACT iOS PORT: SearchService.swift getAllUsers method
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
            emptyList<BasicUserInfo>()
        }
    }

    /**
     * Search users by text - comprehensive search
     * EXACT iOS PORT: SearchService.swift searchUsersByText method
     */
    private suspend fun searchUsersByText(
        query: String,
        limit: Int
    ): List<BasicUserInfo> {
        return try {
            val currentUserID = auth.currentUser?.uid
            val lowercaseQuery = query.lowercase()

            println("🔍 SEARCH: searchUsersByText - query: '$lowercaseQuery'")

            // Search by username (primary)
            val usernameSnapshot = db.collection(USERS_COLLECTION)
                .whereGreaterThanOrEqualTo("username", lowercaseQuery)
                .whereLessThan("username", lowercaseQuery + "\uf8ff")
                .limit((limit * 0.7).toLong()) // 70% for username matches
                .get()
                .await()

            // Search by display name (secondary)
            val displayNameSnapshot = db.collection(USERS_COLLECTION)
                .whereGreaterThanOrEqualTo("displayName", lowercaseQuery)
                .whereLessThan("displayName", lowercaseQuery + "\uf8ff")
                .limit((limit * 0.3).toLong()) // 30% for display name matches
                .get()
                .await()

            // Combine and deduplicate results
            val allDocuments = (usernameSnapshot.documents + displayNameSnapshot.documents)
                .distinctBy { it.id }

            val users = processUserDocuments(allDocuments, currentUserID)
            println("✅ SEARCH: searchUsersByText found ${users.size} users")

            // FIXED: Explicit type declaration and proper list handling
            val limitedUsers: List<BasicUserInfo> = if (users.size > limit) {
                users.subList(0, limit)
            } else {
                users
            }
            limitedUsers

        } catch (e: Exception) {
            println("❌ SEARCH: searchUsersByText failed: ${e.message}")
            emptyList<BasicUserInfo>()
        }
    }

    /**
     * Search videos by title - simple search
     * EXACT iOS PORT: SearchService.swift searchVideos method
     */
    suspend fun searchVideos(
        query: String,
        limit: Int = 20
    ): List<CoreVideoMetadata> {
        return try {
            val lowercaseQuery = query.lowercase()

            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereGreaterThanOrEqualTo("title", lowercaseQuery)
                .whereLessThan("title", lowercaseQuery + "\uf8ff")
                .limit(limit.toLong())
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
            println("✅ SEARCH: searchVideos found ${videos.size} videos")
            videos

        } catch (e: Exception) {
            println("❌ SEARCH: searchVideos failed: ${e.message}")
            emptyList<CoreVideoMetadata>()
        }
    }

    /**
     * Get recent videos for discovery
     * EXACT iOS PORT: SearchService.swift getRecentVideos method
     */
    suspend fun getRecentVideos(limit: Int = 20): List<CoreVideoMetadata> {
        return try {
            val snapshot = db.collection(VIDEOS_COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
            println("✅ SEARCH: getRecentVideos loaded ${videos.size} videos")
            videos

        } catch (e: Exception) {
            println("❌ SEARCH: getRecentVideos failed: ${e.message}")
            emptyList<CoreVideoMetadata>()
        }
    }

    // MARK: - NEW HASHTAG SEARCH METHODS

    /**
     * Search videos by hashtag
     * @param hashtag Hashtag to search for (with or without #)
     * @param limit Maximum number of results
     * @return List of videos containing the hashtag
     */
    suspend fun searchByHashtag(hashtag: String, limit: Int = DEFAULT_LIMIT): List<CoreVideoMetadata> {
        return try {
            val cleanHashtag = hashtag.removePrefix("#").lowercase()
            println("🏷️ SEARCH: searchByHashtag - hashtag: '$cleanHashtag'")

            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereArrayContains("hashtags", cleanHashtag)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
            println("✅ SEARCH: searchByHashtag found ${videos.size} videos for #$cleanHashtag")
            videos

        } catch (e: Exception) {
            println("❌ SEARCH: searchByHashtag failed: ${e.message}")
            emptyList<CoreVideoMetadata>()
        }
    }

    /**
     * Get trending hashtags based on recent usage
     * @param limit Maximum number of hashtags to return
     * @return List of trending hashtag strings
     */
    suspend fun getTrendingHashtags(limit: Int = 20): List<String> {
        return try {
            println("📈 SEARCH: getTrendingHashtags - loading recent videos")

            // Get recent videos to analyze hashtag frequency
            val cutoffDate = Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000) // Last 7 days
            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereGreaterThan("createdAt", Timestamp(cutoffDate))
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(500) // Analyze last 500 videos
                .get()
                .await()

            // Count hashtag frequency
            val hashtagCounts = mutableMapOf<String, Int>()

            for (document in snapshot.documents) {
                val hashtags = document.get("hashtags") as? List<*>
                hashtags?.forEach { hashtag ->
                    val cleanHashtag = hashtag.toString().lowercase()
                    hashtagCounts[cleanHashtag] = hashtagCounts.getOrDefault(cleanHashtag, 0) + 1
                }
            }

            // Sort by frequency and return top hashtags
            val trending = hashtagCounts.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }

            println("✅ SEARCH: getTrendingHashtags found ${trending.size} trending hashtags")
            trending

        } catch (e: Exception) {
            println("❌ SEARCH: getTrendingHashtags failed: ${e.message}")
            emptyList<String>()
        }
    }

    /**
     * Get hashtag suggestions based on partial input
     * @param partialHashtag Partial hashtag text to match
     * @param limit Maximum suggestions to return
     * @return List of suggested hashtag strings
     */
    suspend fun getHashtagSuggestions(partialHashtag: String, limit: Int = 10): List<String> {
        return try {
            val cleanPartial = partialHashtag.removePrefix("#").lowercase()
            println("💡 SEARCH: getHashtagSuggestions - partial: '$cleanPartial'")

            // Get trending hashtags first
            val trendingHashtags = getTrendingHashtags(50)

            // Filter trending hashtags that match the partial input
            val suggestions = trendingHashtags
                .filter { it.startsWith(cleanPartial) }
                .take(limit)

            println("✅ SEARCH: getHashtagSuggestions found ${suggestions.size} suggestions")
            suggestions

        } catch (e: Exception) {
            println("❌ SEARCH: getHashtagSuggestions failed: ${e.message}")
            emptyList<String>()
        }
    }

    /**
     * Search videos by hashtag with trending boost
     * @param hashtag Hashtag to search for
     * @param limit Maximum results
     * @return Videos sorted by relevance and trending score
     */
    suspend fun searchByHashtagTrending(hashtag: String, limit: Int = DEFAULT_LIMIT): List<CoreVideoMetadata> {
        return try {
            val cleanHashtag = hashtag.removePrefix("#").lowercase()
            println("🔥 SEARCH: searchByHashtagTrending - hashtag: '$cleanHashtag'")

            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereArrayContains("hashtags", cleanHashtag)
                .orderBy("trendingScore", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
            println("✅ SEARCH: searchByHashtagTrending found ${videos.size} trending videos for #$cleanHashtag")
            videos

        } catch (e: Exception) {
            println("❌ SEARCH: searchByHashtagTrending failed: ${e.message}")
            emptyList<CoreVideoMetadata>()
        }
    }

    // MARK: - Helper Methods (iOS Port)

    /**
     * Convert user documents to BasicUserInfo objects
     * EXACT iOS PORT: SearchService.swift processUserDocuments method
     */
    private fun processUserDocuments(
        documents: List<DocumentSnapshot>,
        currentUserID: String?
    ): List<BasicUserInfo> {

        val users = mutableListOf<BasicUserInfo>()

        for (document in documents) {
            // Skip current user
            if (currentUserID != null && document.id == currentUserID) {
                continue
            }

            // Use existing BasicUserInfo.fromFirebaseDocument method
            val user = BasicUserInfo.fromFirebaseDocument(document)
            if (user != null) {
                users.add(user)
            }
        }

        return users.toList()
    }

    /**
     * Convert video documents to CoreVideoMetadata objects with HASHTAG SUPPORT
     * EXACT iOS PORT: SearchService.swift processVideoDocuments method + hashtags
     */
    private fun processVideoDocuments(documents: List<DocumentSnapshot>): List<CoreVideoMetadata> {

        val videos = mutableListOf<CoreVideoMetadata>()

        for (document in documents) {
            val data = document.data ?: continue

            try {
                // Parse enum values safely
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

                // Parse hashtags array (NEW)
                val hashtagsRaw = data["hashtags"] as? List<*>
                val hashtags = hashtagsRaw?.mapNotNull { it?.toString()?.lowercase() } ?: emptyList()

                val video = CoreVideoMetadata(
                    id = document.id,
                    title = data["title"] as? String ?: "",
                    description = data["description"] as? String ?: "",
                    videoURL = data["videoURL"] as? String ?: "",
                    thumbnailURL = data["thumbnailURL"] as? String ?: "",
                    creatorID = data["creatorID"] as? String ?: "",
                    creatorName = data["creatorName"] as? String ?: "",
                    createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
                    hashtags = hashtags, // NEW: Include parsed hashtags
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
                    aspectRatio = data["aspectRatio"] as? Double ?: (9.0/16.0),
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
     * EXACT iOS PORT: SearchService.swift testSearchService method
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

/**
 * Search results container - exact iOS port with hashtag support
 */
data class SearchResults(
    val users: List<BasicUserInfo> = emptyList(),
    val videos: List<CoreVideoMetadata> = emptyList(),
    val hashtags: List<String> = emptyList() // NEW: Hashtag results
) {
    val totalResults: Int get() = users.size + videos.size + hashtags.size
    val hasResults: Boolean get() = totalResults > 0
}