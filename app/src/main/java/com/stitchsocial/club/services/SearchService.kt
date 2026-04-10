/*
 * SearchService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services — Matches iOS SearchService.swift
 *
 * ✅ getSuggestedUsers: mutual-follow fan-out (sample 10) + trending fill
 * ✅ getTrendingHashtagModels: returns TrendingHashtag with VelocityTier
 * ✅ PARENT VIDEOS ONLY (conversationDepth == 0)
 *
 * CACHING: Callers (SearchViewModel) own TTL cache for suggestedUsers +
 *          trendingHashtags. This service is stateless — no double-caching.
 * BATCHING: fetchUsersByIDs batches in chunks of 10 (Firestore 'in' limit).
 *           getMutualFollowSuggestions samples first 10 follows to cap reads.
 */

package com.stitchsocial.club.services

import com.stitchsocial.club.foundation.BasicUserInfo
import com.google.firebase.Timestamp
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.Temperature
import com.stitchsocial.club.foundation.ContentType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.tasks.await
import java.util.Date


// MARK: - SearchService

class SearchService {

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val auth = FirebaseAuth.getInstance()
    private val hashtagService = HashtagService()

    companion object {
        private const val USERS_COL  = "users"
        private const val VIDEOS_COL = "videos"
        private const val FOLLOWS_COL = "follows"
        private const val DEFAULT_LIMIT = 50
    }

    // MARK: - User Search

    suspend fun searchUsers(query: String, limit: Int = DEFAULT_LIMIT): List<BasicUserInfo> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) getAllUsers(limit) else searchUsersByText(trimmed, limit)
    }

    private suspend fun getAllUsers(limit: Int): List<BasicUserInfo> {
        return try {
            val currentUID = auth.currentUser?.uid
            val snap = db.collection(USERS_COL)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get().await()
            val users = processUserDocs(snap.documents, currentUID)
                .sortedWith(compareByDescending<BasicUserInfo> { it.isVerified }.thenByDescending { it.clout })
            users.take(limit)
        } catch (e: Exception) {
            println("❌ SEARCH: getAllUsers failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun searchUsersByText(query: String, limit: Int): List<BasicUserInfo> {
        return try {
            val currentUID = auth.currentUser?.uid
            val lq = query.lowercase()
            val cq = query.replaceFirstChar { it.uppercase() }

            val snaps = listOf(
                db.collection(USERS_COL).whereGreaterThanOrEqualTo("username", lq).whereLessThan("username", lq + "\uf8ff").limit(limit.toLong()).get().await(),
                db.collection(USERS_COL).whereGreaterThanOrEqualTo("username", cq).whereLessThan("username", cq + "\uf8ff").limit(limit.toLong()).get().await(),
                db.collection(USERS_COL).whereGreaterThanOrEqualTo("displayName", cq).whereLessThan("displayName", cq + "\uf8ff").limit(limit.toLong()).get().await(),
                db.collection(USERS_COL).whereGreaterThanOrEqualTo("displayName", lq).whereLessThan("displayName", lq + "\uf8ff").limit(limit.toLong()).get().await()
            )

            val docs = snaps.flatMap { it.documents }.distinctBy { it.id }
            val users = processUserDocs(docs, currentUID)
                .filter { it.username.lowercase().contains(lq) || it.displayName.lowercase().contains(lq) }

            if (users.size > limit) users.subList(0, limit) else users
        } catch (e: Exception) {
            println("❌ SEARCH: searchUsersByText failed: ${e.message}")
            searchUsersFallback(query, limit)
        }
    }

    private suspend fun searchUsersFallback(query: String, limit: Int): List<BasicUserInfo> {
        return try {
            val lq = query.lowercase()
            val snap = db.collection(USERS_COL).orderBy("createdAt", Query.Direction.DESCENDING).limit(500).get().await()
            processUserDocs(snap.documents, auth.currentUser?.uid)
                .filter { it.username.lowercase().contains(lq) || it.displayName.lowercase().contains(lq) }
                .take(limit)
        } catch (e: Exception) { emptyList() }
    }

    // MARK: - Suggested Users (matches iOS getSuggestedUsers — mutual-follow + trending fill)
    // BATCHING: fan-out capped at first 10 follows; fetchUsersByIDs batches in 10s

    suspend fun getSuggestedUsers(limit: Int = 20): List<BasicUserInfo> {
        val currentUID = auth.currentUser?.uid
            ?: return getAllUsers(limit) // Not logged in — show popular

        val suggestions = mutableListOf<BasicUserInfo>()
        val seenIDs = mutableSetOf(currentUID)

        // 1. Get current user's following list
        val following = getFollowingIDs(currentUID)
        seenIDs.addAll(following)

        // 2. Mutual-follow PYMK
        val mutual = getMutualFollowSuggestions(currentUID, following, seenIDs, limit / 2)
        for (user in mutual) {
            if (!seenIDs.contains(user.id)) {
                suggestions.add(user)
                seenIDs.add(user.id)
            }
        }
        println("🤝 MUTUAL: Added ${mutual.size} mutual follow suggestions")

        // 3. Fill remaining with trending users
        if (suggestions.size < limit) {
            val trending = getTrendingUsers(seenIDs, limit - suggestions.size)
            for (user in trending) {
                if (!seenIDs.contains(user.id)) {
                    suggestions.add(user)
                    seenIDs.add(user.id)
                }
            }
            println("💡 TRENDING: Added ${trending.size} trending users")
        }

        println("✅ SUGGESTIONS: Returning ${suggestions.size} personalized suggestions")
        return suggestions
    }

    private suspend fun getFollowingIDs(userID: String): Set<String> {
        return try {
            val snap = db.collection(FOLLOWS_COL)
                .whereEqualTo("followerID", userID)
                .get().await()
            snap.documents.mapNotNull { it.getString("followingID") }.filter { it.isNotEmpty() }.toSet()
        } catch (e: Exception) {
            println("❌ SEARCH: getFollowingIDs failed: ${e.message}")
            emptySet()
        }
    }

    // Fan-out: sample first 10 follows to avoid excessive reads
    private suspend fun getMutualFollowSuggestions(
        currentUID: String,
        following: Set<String>,
        excludeIDs: Set<String>,
        limit: Int
    ): List<BasicUserInfo> {
        val candidateScores = mutableMapOf<String, Int>()
        val sample = following.take(10) // Cap at 10 to limit Firestore reads

        for (followedUID in sample) {
            try {
                val snap = db.collection(FOLLOWS_COL)
                    .whereEqualTo("followerID", followedUID)
                    .limit(50)
                    .get().await()

                for (doc in snap.documents) {
                    val suggestedID = doc.getString("followingID") ?: continue
                    if (suggestedID.isNotEmpty() && !excludeIDs.contains(suggestedID)) {
                        candidateScores[suggestedID] = (candidateScores[suggestedID] ?: 0) + 1
                    }
                }
            } catch (e: Exception) { /* skip this follow */ }
        }

        val topCandidates = candidateScores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

        println("🤝 MUTUAL: ${topCandidates.size} candidates from ${sample.size} sampled follows")
        return fetchUsersByIDs(topCandidates)
    }

    private suspend fun getTrendingUsers(excludeIDs: Set<String>, limit: Int): List<BasicUserInfo> {
        return try {
            val snap = db.collection(USERS_COL)
                .orderBy("clout", Query.Direction.DESCENDING)
                .limit((limit * 3).toLong())
                .get().await()
            processUserDocs(snap.documents, auth.currentUser?.uid)
                .filter { !excludeIDs.contains(it.id) }
                .take(limit)
        } catch (e: Exception) {
            println("❌ SEARCH: getTrendingUsers failed: ${e.message}")
            emptyList()
        }
    }

    // BATCHING: Firestore 'in' limit is 10 — chunked automatically
    private suspend fun fetchUsersByIDs(userIDs: List<String>): List<BasicUserInfo> {
        if (userIDs.isEmpty()) return emptyList()
        val result = mutableListOf<BasicUserInfo>()
        val currentUID = auth.currentUser?.uid

        userIDs.chunked(10).forEach { batch ->
            try {
                val snap = db.collection(USERS_COL)
                    .whereIn("__name__", batch)
                    .get().await()
                result.addAll(processUserDocs(snap.documents, currentUID))
            } catch (e: Exception) {
                println("❌ SEARCH: fetchUsersByIDs batch failed: ${e.message}")
            }
        }
        return result
    }

    // MARK: - Video Search (parent videos only)

    suspend fun searchVideos(query: String, limit: Int = 20): List<CoreVideoMetadata> {
        return try {
            val lq = query.lowercase()
            val snap = db.collection(VIDEOS_COL)
                .whereEqualTo("conversationDepth", 0)
                .whereGreaterThanOrEqualTo("title", lq)
                .whereLessThan("title", lq + "\uf8ff")
                .limit(limit.toLong())
                .get().await()
            processVideoDocs(snap.documents)
        } catch (e: Exception) {
            searchVideosFallback(query, limit)
        }
    }

    private suspend fun searchVideosFallback(query: String, limit: Int): List<CoreVideoMetadata> {
        return try {
            val lq = query.lowercase()
            val snap = db.collection(VIDEOS_COL)
                .whereGreaterThanOrEqualTo("title", lq)
                .whereLessThan("title", lq + "\uf8ff")
                .limit((limit * 2).toLong())
                .get().await()
            processVideoDocs(snap.documents).filter { it.conversationDepth == 0 }.take(limit)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun searchByHashtag(hashtag: String, limit: Int = DEFAULT_LIMIT): List<CoreVideoMetadata> {
        val clean = hashtag.removePrefix("#").lowercase()
        return try {
            val snap = db.collection(VIDEOS_COL)
                .whereArrayContains("hashtags", clean)
                .whereEqualTo("conversationDepth", 0)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get().await()
            processVideoDocs(snap.documents)
        } catch (e: Exception) {
            searchByHashtagFallback(hashtag, limit)
        }
    }

    private suspend fun searchByHashtagFallback(hashtag: String, limit: Int): List<CoreVideoMetadata> {
        return try {
            val clean = hashtag.removePrefix("#").lowercase()
            val snap = db.collection(VIDEOS_COL)
                .whereArrayContains("hashtags", clean)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit((limit * 2).toLong())
                .get().await()
            processVideoDocs(snap.documents).filter { it.conversationDepth == 0 }.take(limit)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun getRecentVideos(limit: Int = 20): List<CoreVideoMetadata> {
        return try {
            val snap = db.collection(VIDEOS_COL)
                .whereEqualTo("conversationDepth", 0)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get().await()
            processVideoDocs(snap.documents)
        } catch (e: Exception) {
            try {
                val snap = db.collection(VIDEOS_COL)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit((limit * 3).toLong())
                    .get().await()
                processVideoDocs(snap.documents).filter { it.conversationDepth == 0 }.take(limit)
            } catch (e2: Exception) { emptyList() }
        }
    }

    // MARK: - Trending Hashtags — delegates to HashtagService (owns TrendingHashtag model)

    suspend fun getTrendingHashtagModels(limit: Int = 15): List<TrendingHashtag> =
        hashtagService.loadTrendingHashtags(limit)

    suspend fun getTrendingHashtags(limit: Int = 20): List<String> =
        getTrendingHashtagModels(limit).map { it.tag }

    // MARK: - Document Processing

    private fun processUserDocs(docs: List<DocumentSnapshot>, currentUID: String?): List<BasicUserInfo> =
        docs.mapNotNull { BasicUserInfo.fromFirebaseDocument(it) }.filter { it.id != currentUID }

    private fun processVideoDocs(docs: List<DocumentSnapshot>): List<CoreVideoMetadata> {
        return docs.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            try {
                val temperature = try { Temperature.valueOf((data["temperature"] as? String ?: "WARM").uppercase()) }
                catch (e: Exception) { Temperature.WARM }
                val contentType = try { ContentType.valueOf((data["contentType"] as? String ?: "THREAD").uppercase()) }
                catch (e: Exception) { ContentType.THREAD }
                @Suppress("UNCHECKED_CAST")
                val hashtags = (data["hashtags"] as? List<String>)?.map { it.lowercase() } ?: emptyList()

                CoreVideoMetadata(
                    id = doc.id,
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
            } catch (e: Exception) {
                println("⚠️ SEARCH: Failed to process video ${doc.id}: ${e.message}")
                null
            }
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