/*
 * HomeFeedService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Following Feed with Deep Discovery
 * MATCHES iOS HomeFeedService.swift
 * Features: Deep time-based discovery, seen-video exclusion, session resume
 */

package com.stitchsocial.club.services

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.stitchsocial.club.AppConfig
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ThreadData
import com.stitchsocial.club.foundation.Temperature
import com.stitchsocial.club.foundation.ContentType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date

/**
 * Following-only feed service with deep discovery and view history
 * MATCHES iOS HomeFeedService
 */
class HomeFeedService(
    private val videoService: VideoServiceImpl,
    private val userService: UserService,
    private val context: Context? = null
) {

    // MARK: - View History

    private val viewHistory: FeedViewHistory?
        get() = context?.let { FeedViewHistory.getInstance(it) }

    // MARK: - Published State

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _feedStats = MutableStateFlow(FeedStats())
    val feedStats: StateFlow<FeedStats> = _feedStats

    private val _canResumeSession = MutableStateFlow(false)
    val canResumeSession: StateFlow<Boolean> = _canResumeSession

    // MARK: - Feed State

    private var currentFeed: MutableList<ThreadData> = mutableListOf()
    private var currentFeedVideoIDs: MutableSet<String> = mutableSetOf()
    private var lastDocument: DocumentSnapshot? = null
    private var hasMoreContent: Boolean = true

    // MARK: - Follower Rotation

    private var followerRotationIndex: Int = 0
    private var allFollowerIDs: List<String> = emptyList()

    // MARK: - Configuration

    private val defaultFeedSize = 40
    private val triggerLoadThreshold = 10
    private val maxCachedThreads = 300
    private val followersPerBatch = 15

    // MARK: - Caching

    private var cachedFollowingIDs: List<String> = emptyList()
    private var followingIDsCacheTime: Long = 0
    private val followingCacheExpiration = 5 * 60 * 1000L // 5 minutes

    init {
        _canResumeSession.value = viewHistory?.canResumeSession() ?: false
        println("ðŸ  HOME FEED: Initialized with view history tracking")
        viewHistory?.let { println(it.debugStatus()) }
    }

    // MARK: - Session Resume

    fun checkSessionResume(): Pair<Boolean, FeedPosition?> {
        val canResume = viewHistory?.canResumeSession() ?: false
        val position = viewHistory?.getSavedPosition()
        _canResumeSession.value = canResume
        return Pair(canResume, position)
    }

    fun getLastSessionThreadIDs(): List<String>? {
        return viewHistory?.getLastSessionThreadIDs()
    }

    suspend fun loadThreadsByIDs(threadIDs: List<String>): List<ThreadData> {
        val db = FirebaseFirestore.getInstance("stitchfin")
        val threads = mutableListOf<ThreadData>()

        // Process in chunks of 10 (Firestore limit for whereIn)
        threadIDs.chunked(10).forEach { chunk ->
            try {
                val snapshot = db.collection("videos")
                    .whereIn("threadID", chunk)
                    .whereEqualTo("conversationDepth", 0)
                    .get()
                    .await()

                for (document in snapshot.documents) {
                    createParentThreadFromDocument(document)?.let { threads.add(it) }
                }
            } catch (e: Exception) {
                println("âŒ HOME FEED: Error loading threads - ${e.message}")
            }
        }

        // Sort by original order
        val threadIDOrder = threadIDs.withIndex().associate { it.value to it.index }
        threads.sortBy { threadIDOrder[it.id] ?: Int.MAX_VALUE }

        return threads
    }

    // MARK: - Position Tracking

    fun saveCurrentPosition(itemIndex: Int, stitchIndex: Int, threadID: String?) {
        viewHistory?.saveFeedPosition(itemIndex, stitchIndex, threadID)
    }

    fun saveCurrentFeed(threads: List<ThreadData>) {
        viewHistory?.saveLastSessionFeed(threads)
    }

    fun clearSessionData() {
        viewHistory?.clearFeedPosition()
        viewHistory?.clearLastSession()
    }

    // MARK: - Video View Tracking

    fun markVideoSeen(videoID: String) {
        viewHistory?.markVideoSeen(videoID)
    }

    fun markVideosSeen(videoIDs: List<String>) {
        viewHistory?.markVideosSeen(videoIDs)
    }

    // MARK: - DEEP DISCOVERY - Primary Feed Loading

    suspend fun loadFeed(userID: String, limit: Int = 40): List<ThreadData> {
        // FAST PATH: Return cached feed immediately if available
        if (currentFeed.isNotEmpty()) {
            println("âš¡ INSTANT FEED: Returning ${currentFeed.size} cached threads")
            return currentFeed.toList()
        }

        // FAST LOAD: Get content quickly first, then optimize in background
        return loadFeedFast(userID, limit)
    }

    // Fast initial load - single query, no time splits
    private suspend fun loadFeedFast(userID: String, limit: Int): List<ThreadData> {
        _isLoading.value = true

        try {
            println("ðŸš€ FAST FEED: Loading feed quickly for user $userID")

            val followingIDs = getCachedFollowingIDs(userID)
            if (followingIDs.isEmpty()) {
                println("ðŸš€ FAST FEED: No following, loading discovery")
                return loadDiscoveryFeed(limit)
            }

            allFollowerIDs = followingIDs
            val recentlySeenIDs = viewHistory?.getRecentlySeenVideoIDs() ?: emptySet()

            // Single fast query - get recent content from followed users
            val db = FirebaseFirestore.getInstance("stitchfin")
            val threads = mutableListOf<ThreadData>()

            // Query in batches of 10 (Firestore 'in' limit)
            val batches = followingIDs.chunked(10)

            // Use parallel queries for speed
            coroutineScope {
                val deferredResults = batches.map { batch ->
                    async {
                        try {
                            db.collection("videos")
                                .whereIn("creatorID", batch)
                                .whereEqualTo("conversationDepth", 0)
                                .orderBy("createdAt", Query.Direction.DESCENDING)
                                .limit((limit / batches.size + 5).toLong())
                                .get()
                                .await()
                        } catch (e: Exception) {
                            println("âš ï¸ FAST FEED: Batch query failed - ${e.message}")
                            null
                        }
                    }
                }

                // Collect results
                deferredResults.forEach { deferred ->
                    deferred.await()?.documents?.forEach { doc ->
                        val videoID = doc.getString("id") ?: doc.id
                        if (videoID !in recentlySeenIDs) {
                            createParentThreadFromDocument(doc)?.let { threads.add(it) }
                        }
                    }
                }
            }

            // If no content from following, fallback to discovery
            if (threads.isEmpty()) {
                println("ðŸš€ FAST FEED: No content from following, using discovery")
                return loadDiscoveryFeed(limit)
            }

            // Shuffle for variety
            val shuffled = threads.shuffled().take(limit)

            // Update cache
            currentFeed.clear()
            currentFeed.addAll(shuffled)
            currentFeedVideoIDs.clear()
            currentFeedVideoIDs.addAll(shuffled.map { it.parentVideo.id })

            println("âœ… FAST FEED: Loaded ${shuffled.size} threads")
            return shuffled

        } catch (e: Exception) {
            println("âŒ FAST FEED ERROR: ${e.message}")
            return loadDiscoveryFeed(limit)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun loadFeedWithDeepDiscovery(userID: String, limit: Int = 40): List<ThreadData> {
        _isLoading.value = true

        try {
            println("ðŸ” DEEP DISCOVERY: Loading diverse feed for user $userID")

            val followingIDs = getCachedFollowingIDs(userID)
            if (followingIDs.isEmpty()) {
                println("ðŸ” DEEP DISCOVERY: No following found, loading discovery feed")
                return loadDiscoveryFeed(limit)
            }

            allFollowerIDs = followingIDs
            println("ðŸ” DEEP DISCOVERY: Found ${followingIDs.size} following users")

            val recentlySeenIDs = viewHistory?.getRecentlySeenVideoIDs() ?: emptySet()
            println("ðŸ” DEEP DISCOVERY: Excluding ${recentlySeenIDs.size} recently seen videos")

            val allThreads = mutableListOf<ThreadData>()

            // 40% recent content (last 24 hours)
            val recentThreads = getRecentContent(
                followingIDs = followingIDs,
                limit = (limit * 0.4).toInt(),
                excludeVideoIDs = recentlySeenIDs
            )
            allThreads.addAll(recentThreads)
            println("ðŸ” DEEP DISCOVERY: Loaded ${recentThreads.size} recent threads")

            // 30% medium-old content (1-7 days)
            val mediumOldThreads = getMediumOldContent(
                followingIDs = followingIDs,
                limit = (limit * 0.3).toInt(),
                excludeVideoIDs = recentlySeenIDs
            )
            allThreads.addAll(mediumOldThreads)
            println("ðŸ” DEEP DISCOVERY: Loaded ${mediumOldThreads.size} medium-old threads")

            // 20% older content (7-30 days)
            val olderThreads = getOlderContent(
                followingIDs = followingIDs,
                limit = (limit * 0.2).toInt(),
                excludeVideoIDs = recentlySeenIDs
            )
            allThreads.addAll(olderThreads)
            println("ðŸ” DEEP DISCOVERY: Loaded ${olderThreads.size} older threads")

            // 10% deep cuts (30+ days)
            val deepCutThreads = getDeepCutContent(
                followingIDs = followingIDs,
                limit = (limit * 0.1).toInt(),
                excludeVideoIDs = recentlySeenIDs
            )
            allThreads.addAll(deepCutThreads)
            println("ðŸ” DEEP DISCOVERY: Loaded ${deepCutThreads.size} deep cut threads")

            // Deduplicate and shuffle
            val dedupedThreads = deduplicateThreads(allThreads)
            val shuffledThreads = shuffleWithVariety(dedupedThreads)

            // FALLBACK: If deep discovery found nothing, use discovery feed
            if (shuffledThreads.isEmpty()) {
                println("ðŸ” DEEP DISCOVERY: No content from following, falling back to discovery")
                val discoveryThreads = loadDiscoveryFeed(limit)
                currentFeed.clear()
                currentFeed.addAll(discoveryThreads)
                currentFeedVideoIDs.clear()
                currentFeedVideoIDs.addAll(discoveryThreads.map { it.parentVideo.id })

                _feedStats.value = _feedStats.value.copy(
                    totalThreadsLoaded = currentFeed.size,
                    lastRefreshTime = Date()
                )

                println("âœ… DEEP DISCOVERY: Fallback loaded ${discoveryThreads.size} discovery threads")
                return discoveryThreads
            }

            // Update cache
            currentFeed.clear()
            currentFeed.addAll(shuffledThreads)
            currentFeedVideoIDs.clear()
            currentFeedVideoIDs.addAll(shuffledThreads.map { it.parentVideo.id })

            _feedStats.value = _feedStats.value.copy(
                totalThreadsLoaded = currentFeed.size,
                lastRefreshTime = Date()
            )

            println("âœ… DEEP DISCOVERY: Feed loaded with ${shuffledThreads.size} diverse threads")
            return shuffledThreads

        } catch (e: Exception) {
            println("âŒ DEEP DISCOVERY: Error - ${e.message}")
            e.printStackTrace()

            // Fallback to discovery on error
            println("ðŸ” DEEP DISCOVERY: Error occurred, falling back to discovery")
            return loadDiscoveryFeed(limit)
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Time-Based Content Loading

    private suspend fun getRecentContent(
        followingIDs: List<String>,
        limit: Int,
        excludeVideoIDs: Set<String>
    ): List<ThreadData> {
        val db = FirebaseFirestore.getInstance("stitchfin")
        val threads = mutableListOf<ThreadData>()

        val oneDayAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }.time

        // Get content from rotated subset of followers
        val batchFollowers = getRotatedFollowerBatch(followingIDs, followersPerBatch)

        for (chunk in batchFollowers.chunked(10)) {
            try {
                val snapshot = db.collection("videos")
                    .whereIn("creatorID", chunk)
                    .whereEqualTo("conversationDepth", 0)
                    .whereGreaterThan("createdAt", oneDayAgo)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit.toLong())
                    .get()
                    .await()

                for (document in snapshot.documents) {
                    val thread = createParentThreadFromDocument(document)
                    if (thread != null && !excludeVideoIDs.contains(thread.parentVideo.id)) {
                        threads.add(thread)
                    }
                }
            } catch (e: Exception) {
                println("âŒ RECENT CONTENT: Error - ${e.message}")
            }
        }

        return threads.take(limit)
    }

    private suspend fun getMediumOldContent(
        followingIDs: List<String>,
        limit: Int,
        excludeVideoIDs: Set<String>
    ): List<ThreadData> {
        val db = FirebaseFirestore.getInstance("stitchfin")
        val threads = mutableListOf<ThreadData>()

        val sevenDaysAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -7)
        }.time

        val oneDayAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }.time

        val batchFollowers = getRotatedFollowerBatch(followingIDs, followersPerBatch)

        for (chunk in batchFollowers.chunked(10)) {
            try {
                val snapshot = db.collection("videos")
                    .whereIn("creatorID", chunk)
                    .whereEqualTo("conversationDepth", 0)
                    .whereGreaterThan("createdAt", sevenDaysAgo)
                    .whereLessThan("createdAt", oneDayAgo)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit.toLong())
                    .get()
                    .await()

                for (document in snapshot.documents) {
                    val thread = createParentThreadFromDocument(document)
                    if (thread != null && !excludeVideoIDs.contains(thread.parentVideo.id)) {
                        threads.add(thread)
                    }
                }
            } catch (e: Exception) {
                println("âŒ MEDIUM OLD CONTENT: Error - ${e.message}")
            }
        }

        return threads.shuffled().take(limit)
    }

    private suspend fun getOlderContent(
        followingIDs: List<String>,
        limit: Int,
        excludeVideoIDs: Set<String>
    ): List<ThreadData> {
        val db = FirebaseFirestore.getInstance("stitchfin")
        val threads = mutableListOf<ThreadData>()

        val thirtyDaysAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -30)
        }.time

        val sevenDaysAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -7)
        }.time

        val batchFollowers = getRotatedFollowerBatch(followingIDs, followersPerBatch)

        for (chunk in batchFollowers.chunked(10)) {
            try {
                val snapshot = db.collection("videos")
                    .whereIn("creatorID", chunk)
                    .whereEqualTo("conversationDepth", 0)
                    .whereGreaterThan("createdAt", thirtyDaysAgo)
                    .whereLessThan("createdAt", sevenDaysAgo)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit.toLong())
                    .get()
                    .await()

                for (document in snapshot.documents) {
                    val thread = createParentThreadFromDocument(document)
                    if (thread != null && !excludeVideoIDs.contains(thread.parentVideo.id)) {
                        threads.add(thread)
                    }
                }
            } catch (e: Exception) {
                println("âŒ OLDER CONTENT: Error - ${e.message}")
            }
        }

        return threads.shuffled().take(limit)
    }

    private suspend fun getDeepCutContent(
        followingIDs: List<String>,
        limit: Int,
        excludeVideoIDs: Set<String>
    ): List<ThreadData> {
        val db = FirebaseFirestore.getInstance("stitchfin")
        val threads = mutableListOf<ThreadData>()

        val thirtyDaysAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -30)
        }.time

        val batchFollowers = getRotatedFollowerBatch(followingIDs, followersPerBatch)

        for (chunk in batchFollowers.chunked(10)) {
            try {
                val snapshot = db.collection("videos")
                    .whereIn("creatorID", chunk)
                    .whereEqualTo("conversationDepth", 0)
                    .whereLessThan("createdAt", thirtyDaysAgo)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit.toLong())
                    .get()
                    .await()

                for (document in snapshot.documents) {
                    val thread = createParentThreadFromDocument(document)
                    if (thread != null && !excludeVideoIDs.contains(thread.parentVideo.id)) {
                        threads.add(thread)
                    }
                }
            } catch (e: Exception) {
                println("âŒ DEEP CUT CONTENT: Error - ${e.message}")
            }
        }

        return threads.shuffled().take(limit)
    }

    // MARK: - Discovery Feed (for users not following anyone)

    private suspend fun loadDiscoveryFeed(limit: Int): List<ThreadData> {
        println("ðŸ” DISCOVERY FALLBACK: Using VideoServiceImpl")

        return try {
            // Use VideoServiceImpl which has working queries
            val videos = videoService.getDiscoveryVideos(limit)
            println("ðŸ” DISCOVERY FALLBACK: Got ${videos.size} videos from VideoServiceImpl")

            if (videos.isEmpty()) {
                println("ðŸ” DISCOVERY FALLBACK: No videos, trying personalized")
                val personalizedVideos = videoService.getPersonalizedVideos("", limit)
                println("ðŸ” DISCOVERY FALLBACK: Got ${personalizedVideos.size} personalized videos")
                return personalizedVideos.map { ThreadData.fromVideo(it) }
            }

            videos.map { ThreadData.fromVideo(it) }
        } catch (e: Exception) {
            println("âŒ DISCOVERY FALLBACK ERROR: ${e.message}")
            e.printStackTrace()

            // Last resort - direct Firebase query
            loadDiscoveryFeedDirect(limit)
        }
    }

    private suspend fun loadDiscoveryFeedDirect(limit: Int): List<ThreadData> {
        val db = FirebaseFirestore.getInstance("stitchfin")
        val threads = mutableListOf<ThreadData>()

        try {
            println("ðŸ” DISCOVERY DIRECT: Querying Firebase directly")

            // Simple query without composite index requirement
            val snapshot = db.collection("videos")
                .limit(limit.toLong())
                .get()
                .await()

            println("ðŸ” DISCOVERY DIRECT: Got ${snapshot.documents.size} documents")

            for (document in snapshot.documents) {
                val data = document.data ?: continue
                val depth = (data["conversationDepth"] as? Long)?.toInt() ?: 0
                if (depth == 0) {
                    createParentThreadFromDocument(document)?.let { threads.add(it) }
                }
            }

            println("ðŸ” DISCOVERY DIRECT: Filtered to ${threads.size} parent threads")
        } catch (e: Exception) {
            println("âŒ DISCOVERY DIRECT ERROR: ${e.message}")
            e.printStackTrace()
        }

        return threads
    }

    // MARK: - Thread Creation

    private fun createParentThreadFromDocument(document: DocumentSnapshot): ThreadData? {
        val data = document.data ?: return null

        val id = data["id"] as? String ?: document.id
        val title = data["title"] as? String ?: ""
        val videoURL = data["videoURL"] as? String ?: ""
        val thumbnailURL = data["thumbnailURL"] as? String ?: ""
        val creatorID = data["creatorID"] as? String ?: ""
        val creatorName = data["creatorName"] as? String ?: "Unknown"
        val createdAt = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()
        val threadID = data["threadID"] as? String ?: id
        val conversationDepth = (data["conversationDepth"] as? Long)?.toInt() ?: 0

        val viewCount = (data["viewCount"] as? Long)?.toInt() ?: 0
        val hypeCount = (data["hypeCount"] as? Long)?.toInt() ?: 0
        val coolCount = (data["coolCount"] as? Long)?.toInt() ?: 0
        val replyCount = (data["replyCount"] as? Long)?.toInt() ?: 0
        val shareCount = (data["shareCount"] as? Long)?.toInt() ?: 0

        val duration = (data["duration"] as? Double) ?: 0.0
        val aspectRatio = (data["aspectRatio"] as? Double) ?: (9.0 / 16.0)
        val fileSize = (data["fileSize"] as? Long)?.toInt() ?: 0

        val total = hypeCount + coolCount
        val engagementRatio = if (total > 0) hypeCount.toDouble() / total else 0.5

        val parentVideo = CoreVideoMetadata(
            id = id,
            title = title,
            description = data["description"] as? String ?: "",
            videoURL = videoURL,
            thumbnailURL = thumbnailURL,
            creatorID = creatorID,
            creatorName = creatorName,
            hashtags = emptyList(),
            createdAt = createdAt,
            threadID = threadID,
            replyToVideoID = null,
            conversationDepth = conversationDepth,
            viewCount = viewCount,
            hypeCount = hypeCount,
            coolCount = coolCount,
            replyCount = replyCount,
            shareCount = shareCount,
            lastEngagementAt = null,
            duration = duration,
            aspectRatio = aspectRatio,
            fileSize = fileSize.toLong(),
            contentType = ContentType.THREAD,
            temperature = Temperature.WARM,
            qualityScore = 50,
            engagementRatio = engagementRatio,
            velocityScore = 0.0,
            trendingScore = 0.0,
            discoverabilityScore = 0.5,
            isPromoted = false,
            isProcessing = false,
            isDeleted = false
        )

        return ThreadData(id = threadID, parentVideo = parentVideo, childVideos = emptyList())
    }

    // MARK: - Helper Methods

    private fun getRotatedFollowerBatch(followingIDs: List<String>, batchSize: Int): List<String> {
        if (followingIDs.isEmpty()) return emptyList()

        val startIndex = followerRotationIndex % followingIDs.size
        val batch = mutableListOf<String>()

        for (i in 0 until batchSize.coerceAtMost(followingIDs.size)) {
            val index = (startIndex + i) % followingIDs.size
            batch.add(followingIDs[index])
        }

        followerRotationIndex += batchSize
        return batch
    }

    private fun deduplicateThreads(threads: List<ThreadData>): List<ThreadData> {
        val seen = mutableSetOf<String>()
        return threads.filter { thread ->
            val isNew = !seen.contains(thread.id)
            if (isNew) seen.add(thread.id)
            isNew
        }
    }

    private fun shuffleWithVariety(threads: List<ThreadData>): List<ThreadData> {
        // Group by creator
        val byCreator = threads.groupBy { it.parentVideo.creatorID }
        val result = mutableListOf<ThreadData>()
        val used = mutableSetOf<String>()

        // Interleave creators for variety
        var addedThisRound = true
        while (addedThisRound && result.size < threads.size) {
            addedThisRound = false
            for ((_, creatorThreads) in byCreator) {
                val nextThread = creatorThreads.firstOrNull { !used.contains(it.id) }
                if (nextThread != null) {
                    result.add(nextThread)
                    used.add(nextThread.id)
                    addedThisRound = true
                }
            }
        }

        return result
    }

    suspend fun getCachedFollowingIDs(userID: String): List<String> {
        val now = System.currentTimeMillis()

        if (cachedFollowingIDs.isNotEmpty() &&
            (now - followingIDsCacheTime) < followingCacheExpiration) {
            return cachedFollowingIDs
        }

        return try {
            val followingIDs = userService.getFollowingIDs(userID)
            cachedFollowingIDs = followingIDs
            followingIDsCacheTime = now
            followingIDs
        } catch (e: Exception) {
            println("âŒ FOLLOWING CACHE: Error - ${e.message}")
            emptyList()
        }
    }

    fun getFollowingCount(): Int {
        return cachedFollowingIDs.size
    }

    // MARK: - Load More

    suspend fun loadMoreContent(userID: String): List<ThreadData> {
        if (!hasMoreContent || _isLoading.value) return currentFeed

        _isLoading.value = true

        try {
            val followingIDs = getCachedFollowingIDs(userID)
            if (followingIDs.isEmpty()) return currentFeed

            val exclusionSet = currentFeedVideoIDs.toSet() +
                    (viewHistory?.getRecentlySeenVideoIDs() ?: emptySet())

            val newThreads = mutableListOf<ThreadData>()

            // Load from various time ranges
            newThreads.addAll(getRecentContent(followingIDs, 5, exclusionSet))
            newThreads.addAll(getMediumOldContent(followingIDs, 5, exclusionSet))
            newThreads.addAll(getOlderContent(followingIDs, 5, exclusionSet))
            newThreads.addAll(getDeepCutContent(followingIDs, 5, exclusionSet))

            val dedupedNew = deduplicateThreads(newThreads)
            val shuffledNew = dedupedNew.shuffled()

            if (shuffledNew.isNotEmpty()) {
                currentFeed.addAll(shuffledNew)
                currentFeedVideoIDs.addAll(shuffledNew.map { it.parentVideo.id })

                _feedStats.value = _feedStats.value.copy(totalThreadsLoaded = currentFeed.size)

                // Trim if too large
                if (currentFeed.size > maxCachedThreads) {
                    val threadsToRemove = currentFeed.size - maxCachedThreads
                    repeat(threadsToRemove) {
                        val removed = currentFeed.removeAt(0)
                        currentFeedVideoIDs.remove(removed.parentVideo.id)
                    }
                }

                println("âœ… DEEP DISCOVERY: Added ${shuffledNew.size} diverse threads")
            } else {
                hasMoreContent = false
            }

            return currentFeed
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Thread Children

    suspend fun loadThreadChildren(threadID: String): List<CoreVideoMetadata> {
        val db = FirebaseFirestore.getInstance("stitchfin")
        val children = mutableListOf<CoreVideoMetadata>()

        try {
            val snapshot = db.collection("videos")
                .whereEqualTo("threadID", threadID)
                .whereGreaterThan("conversationDepth", 0)
                .orderBy("conversationDepth", Query.Direction.ASCENDING)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(50)
                .get()
                .await()

            for (document in snapshot.documents) {
                val data = document.data ?: continue

                val id = data["id"] as? String ?: document.id
                val title = data["title"] as? String ?: ""
                val videoURL = data["videoURL"] as? String ?: ""
                val thumbnailURL = data["thumbnailURL"] as? String ?: ""
                val creatorID = data["creatorID"] as? String ?: ""
                val creatorName = data["creatorName"] as? String ?: "Unknown"
                val createdAt = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()
                val conversationDepth = (data["conversationDepth"] as? Long)?.toInt() ?: 0
                val replyToVideoID = data["replyToVideoID"] as? String

                val viewCount = (data["viewCount"] as? Long)?.toInt() ?: 0
                val hypeCount = (data["hypeCount"] as? Long)?.toInt() ?: 0
                val coolCount = (data["coolCount"] as? Long)?.toInt() ?: 0
                val replyCount = (data["replyCount"] as? Long)?.toInt() ?: 0
                val shareCount = (data["shareCount"] as? Long)?.toInt() ?: 0

                val duration = (data["duration"] as? Double) ?: 0.0
                val aspectRatio = (data["aspectRatio"] as? Double) ?: (9.0 / 16.0)
                val fileSize = (data["fileSize"] as? Long)?.toInt() ?: 0

                val total = hypeCount + coolCount
                val engagementRatio = if (total > 0) hypeCount.toDouble() / total else 0.5

                val childVideo = CoreVideoMetadata(
                    id = id,
                    title = title,
                    description = data["description"] as? String ?: "",
                    videoURL = videoURL,
                    thumbnailURL = thumbnailURL,
                    creatorID = creatorID,
                    creatorName = creatorName,
                    hashtags = emptyList(),
                    createdAt = createdAt,
                    threadID = threadID,
                    replyToVideoID = replyToVideoID,
                    conversationDepth = conversationDepth,
                    viewCount = viewCount,
                    hypeCount = hypeCount,
                    coolCount = coolCount,
                    replyCount = replyCount,
                    shareCount = shareCount,
                    lastEngagementAt = null,
                    duration = duration,
                    aspectRatio = aspectRatio,
                    fileSize = fileSize.toLong(),
                    contentType = ContentType.CHILD,
                    temperature = Temperature.WARM,
                    qualityScore = 50,
                    engagementRatio = engagementRatio,
                    velocityScore = 0.0,
                    trendingScore = 0.0,
                    discoverabilityScore = 0.5,
                    isPromoted = false,
                    isProcessing = false,
                    isDeleted = false
                )

                children.add(childVideo)
            }

            println("âœ… HOME FEED: Loaded ${children.size} children for thread $threadID")
        } catch (e: Exception) {
            println("âŒ HOME FEED: Error loading children - ${e.message}")
        }

        return children
    }

    // MARK: - State Management

    fun getCurrentFeed(): List<ThreadData> = currentFeed.toList()

    fun shouldLoadMore(currentIndex: Int): Boolean {
        val remainingThreads = currentFeed.size - currentIndex
        return remainingThreads <= triggerLoadThreshold && hasMoreContent && !_isLoading.value
    }

    fun clearFeed() {
        currentFeed.clear()
        currentFeedVideoIDs.clear()
        lastDocument = null
        hasMoreContent = true
        followerRotationIndex = 0
        _feedStats.value = FeedStats()
    }

    fun clearFollowingCache() {
        cachedFollowingIDs = emptyList()
        followingIDsCacheTime = 0
    }
}

// MARK: - Feed Statistics

data class FeedStats(
    val totalThreadsLoaded: Int = 0,
    val lastRefreshTime: Date? = null,
    val refreshCount: Int = 0
) {
    val timeSinceRefresh: Long?
        get() = lastRefreshTime?.let { System.currentTimeMillis() - it.time }
}