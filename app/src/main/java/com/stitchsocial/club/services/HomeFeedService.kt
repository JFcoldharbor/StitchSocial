/*
 * HomeFeedService.kt - Deep Discovery Feed Service
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * FULL iOS PARITY: HomeFeedService.swift
 * Features:
 *   - Deep time-based discovery (recent 40%, medium 30%, older 20%, deep cuts 10%)
 *   - Seen-video exclusion via FeedViewHistory
 *   - Follower rotation (ensures all followed users get coverage)
 *   - Deduplication across time tiers
 *   - Load more with exclusion of already-loaded videos
 *   - Session resume support
 *   - Children preloading around current position
 */

package com.stitchsocial.club.services

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.stitchsocial.club.firebase.FirebaseSchema
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ThreadData
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.Temperature
import kotlinx.coroutines.tasks.await
import java.util.Date

class HomeFeedService(
    private val videoService: VideoServiceImpl,
    private val userService: UserService,
    private val context: Context
) {
    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val viewHistory: FeedViewHistory? get() = FeedViewHistory.shared

    // Feed state
    private var currentFeed: MutableList<ThreadData> = mutableListOf()
    private val currentFeedVideoIDs: MutableSet<String> = mutableSetOf()
    private var hasMoreContent: Boolean = true
    private var isLoading: Boolean = false

    // Follower rotation
    private var followerRotationIndex: Int = 0
    private var allFollowerIDs: List<String> = emptyList()

    // Configuration (matches iOS)
    private val defaultFeedSize = 40
    private val triggerLoadThreshold = 10
    private val maxCachedThreads = 300
    private val followersPerBatch = 15

    // Following cache
    private var cachedFollowingIDs: List<String> = emptyList()
    private var followingIDsCacheTime: Long = 0
    private val followingCacheExpiration = 300_000L // 5 minutes

    // Children cache for preloading
    private val childrenCache: MutableMap<String, List<CoreVideoMetadata>> = mutableMapOf()

    // MARK: - Primary Feed Loading (Deep Discovery)

    suspend fun loadFeed(userID: String, limit: Int = 40): List<ThreadData> {
        return loadFeedWithDeepDiscovery(userID, limit)
    }

    private suspend fun loadFeedWithDeepDiscovery(userID: String, limit: Int = 40): List<ThreadData> {
        isLoading = true

        try {
            println("🔍 DEEP DISCOVERY: Loading diverse feed for user $userID")

            val followingIDs = getCachedFollowingIDs(userID)
            if (followingIDs.isEmpty()) {
                println("🔍 DEEP DISCOVERY: No following found")
                return emptyList()
            }

            allFollowerIDs = followingIDs
            println("🔍 DEEP DISCOVERY: Found ${followingIDs.size} following users")

            val recentlySeenIDs = viewHistory?.getRecentlySeenVideoIDs() ?: emptySet()
            println("🔍 DEEP DISCOVERY: Excluding ${recentlySeenIDs.size} recently seen videos")

            val allThreads = mutableListOf<ThreadData>()

            // 40% recent (last 7 days)
            val recentThreads = getRecentContent(followingIDs, (limit * 0.4).toInt(), recentlySeenIDs)
            allThreads.addAll(recentThreads)
            println("🔍 DEEP DISCOVERY: Loaded ${recentThreads.size} recent threads")

            // 30% medium-old (7-30 days)
            val mediumOldThreads = getMediumOldContent(followingIDs, (limit * 0.3).toInt(), recentlySeenIDs)
            allThreads.addAll(mediumOldThreads)
            println("🔍 DEEP DISCOVERY: Loaded ${mediumOldThreads.size} medium-old threads")

            // 20% older (30-90 days)
            val olderThreads = getOlderContent(followingIDs, (limit * 0.2).toInt(), recentlySeenIDs)
            allThreads.addAll(olderThreads)
            println("🔍 DEEP DISCOVERY: Loaded ${olderThreads.size} older threads")

            // 10% deep cuts (90-365 days)
            val deepCutThreads = getDeepCutContent(followingIDs, (limit * 0.1).toInt(), recentlySeenIDs)
            allThreads.addAll(deepCutThreads)
            println("🔍 DEEP DISCOVERY: Loaded ${deepCutThreads.size} deep cut threads")

            val dedupedThreads = deduplicateThreads(allThreads)
            val shuffledThreads = dedupedThreads.shuffled()

            currentFeed = shuffledThreads.toMutableList()
            currentFeedVideoIDs.clear()
            currentFeedVideoIDs.addAll(shuffledThreads.map { it.parentVideo.id })

            println("✅ DEEP DISCOVERY: Loaded ${shuffledThreads.size} total threads with diverse time range")
            return shuffledThreads

        } finally {
            isLoading = false
        }
    }

    // MARK: - Time-Based Content Loading

    private suspend fun getRecentContent(
        followingIDs: List<String>, limit: Int, excludeVideoIDs: Set<String>
    ): List<ThreadData> {
        val sevenDaysAgo = Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        return getContentInTimeRange(followingIDs, sevenDaysAgo, Date(), limit, excludeVideoIDs)
    }

    private suspend fun getMediumOldContent(
        followingIDs: List<String>, limit: Int, excludeVideoIDs: Set<String>
    ): List<ThreadData> {
        val thirtyDaysAgo = Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        val sevenDaysAgo = Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        return getContentInTimeRange(followingIDs, thirtyDaysAgo, sevenDaysAgo, limit, excludeVideoIDs)
    }

    private suspend fun getOlderContent(
        followingIDs: List<String>, limit: Int, excludeVideoIDs: Set<String>
    ): List<ThreadData> {
        val ninetyDaysAgo = Date(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
        val thirtyDaysAgo = Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        return getContentInTimeRange(followingIDs, ninetyDaysAgo, thirtyDaysAgo, limit, excludeVideoIDs)
    }

    private suspend fun getDeepCutContent(
        followingIDs: List<String>, limit: Int, excludeVideoIDs: Set<String>
    ): List<ThreadData> {
        val yearAgo = Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000)
        val ninetyDaysAgo = Date(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
        return getContentInTimeRange(followingIDs, yearAgo, ninetyDaysAgo, limit, excludeVideoIDs)
    }

    private suspend fun getContentInTimeRange(
        followingIDs: List<String>,
        startDate: Date,
        endDate: Date,
        limit: Int,
        excludeVideoIDs: Set<String>
    ): List<ThreadData> {
        val sampledFollowers = getRotatingFollowerBatch(followingIDs)
        if (sampledFollowers.isEmpty()) return emptyList()

        val fetchLimit = maxOf(limit * 3, 30).toLong()

        return try {
            val snapshot = db.collection(FirebaseSchema.Collections.VIDEOS)
                .whereIn(FirebaseSchema.VideoDocument.CREATOR_ID, sampledFollowers)
                .whereEqualTo(FirebaseSchema.VideoDocument.CONVERSATION_DEPTH, 0)
                .whereGreaterThanOrEqualTo(FirebaseSchema.VideoDocument.CREATED_AT, Timestamp(startDate))
                .whereLessThan(FirebaseSchema.VideoDocument.CREATED_AT, Timestamp(endDate))
                .orderBy(FirebaseSchema.VideoDocument.CREATED_AT, Query.Direction.DESCENDING)
                .limit(fetchLimit)
                .get()
                .await()

            val threads = mutableListOf<ThreadData>()
            for (document in snapshot.documents) {
                val videoID = document.getString(FirebaseSchema.VideoDocument.ID) ?: document.id
                if (excludeVideoIDs.contains(videoID)) continue
                if (currentFeedVideoIDs.contains(videoID)) continue

                val thread = createThreadFromDocument(document)
                if (thread != null) {
                    threads.add(thread)
                    if (threads.size >= limit) break
                }
            }
            threads
        } catch (e: Exception) {
            println("⚠️ DEEP DISCOVERY: Time range query failed — ${e.message}")
            emptyList()
        }
    }

    // MARK: - Follower Rotation (matches iOS)

    private fun getRotatingFollowerBatch(followingIDs: List<String>): List<String> {
        if (followingIDs.isEmpty()) return emptyList()

        // Firestore 'in' queries limited to 30 items
        val batchSize = minOf(followersPerBatch, 30)

        if (followingIDs.size <= batchSize) return followingIDs

        val shuffled = followingIDs.shuffled()
        val startIndex = followerRotationIndex % shuffled.size

        val batch = mutableListOf<String>()
        for (i in 0 until batchSize) {
            val index = (startIndex + i) % shuffled.size
            batch.add(shuffled[index])
        }

        followerRotationIndex += batchSize
        println("🔄 FOLLOWER ROTATION: Using batch starting at $startIndex")
        return batch
    }

    // MARK: - Deduplication

    private fun deduplicateThreads(threads: List<ThreadData>): List<ThreadData> {
        val seen = mutableSetOf<String>()
        return threads.filter { thread ->
            if (seen.contains(thread.parentVideo.id)) {
                false
            } else {
                seen.add(thread.parentVideo.id)
                true
            }
        }
    }

    // MARK: - Load More Content

    suspend fun loadMoreContent(userID: String): List<ThreadData> {
        if (isLoading) return currentFeed
        isLoading = true

        try {
            val recentlySeenIDs = viewHistory?.getRecentlySeenVideoIDs() ?: emptySet()
            val exclusionSet = recentlySeenIDs.toMutableSet()
            exclusionSet.addAll(currentFeedVideoIDs)

            val followingIDs = getCachedFollowingIDs(userID)
            val newThreads = mutableListOf<ThreadData>()

            newThreads.addAll(getRecentContent(followingIDs, 15, exclusionSet))
            newThreads.addAll(getMediumOldContent(followingIDs, 10, exclusionSet))
            newThreads.addAll(getOlderContent(followingIDs, 10, exclusionSet))
            newThreads.addAll(getDeepCutContent(followingIDs, 5, exclusionSet))

            // Deduplicate and shuffle
            val seen = mutableSetOf<String>()
            val shuffledNew = newThreads.shuffled().filter { thread ->
                if (seen.contains(thread.id)) {
                    false
                } else {
                    seen.add(thread.id)
                    true
                }
            }

            if (shuffledNew.isNotEmpty()) {
                currentFeed.addAll(shuffledNew)
                currentFeedVideoIDs.addAll(shuffledNew.map { it.parentVideo.id })

                // Trim if over max
                if (currentFeed.size > maxCachedThreads) {
                    val toRemove = currentFeed.size - maxCachedThreads
                    val removed = currentFeed.take(toRemove)
                    currentFeed = currentFeed.drop(toRemove).toMutableList()
                    removed.forEach { currentFeedVideoIDs.remove(it.parentVideo.id) }
                }

                println("✅ DEEP DISCOVERY: Added ${shuffledNew.size} diverse threads")
            } else {
                // No new content — recycle existing feed shuffled
                // Never dead-end. Endless scroll.
                recycleExistingFeed()
            }

            return currentFeed
        } finally {
            isLoading = false
        }
    }

    // MARK: - Endless Scroll Recycle

    /**
     * When no new content is available, shuffle and re-append existing feed.
     * Ensures the user never hits a dead-end.
     * Clears seen exclusion set so recycled content can re-enter discovery queries next time.
     */
    private fun recycleExistingFeed() {
        if (currentFeed.isEmpty()) return

        val recycled = currentFeed.shuffled()
        currentFeed.addAll(recycled)

        // Clear the exclusion set so next loadMore can find "new" old content
        currentFeedVideoIDs.clear()
        currentFeedVideoIDs.addAll(currentFeed.map { it.parentVideo.id })

        // Reset follower rotation to get different creator batches
        followerRotationIndex = 0

        println("🔄 ENDLESS SCROLL: Recycled ${recycled.size} threads, reset rotation")
    }

    // MARK: - Thread Creation from Document

    private fun createThreadFromDocument(document: DocumentSnapshot): ThreadData? {
        val data = document.data ?: return null

        try {
            val id = data[FirebaseSchema.VideoDocument.ID] as? String ?: document.id
            val title = data[FirebaseSchema.VideoDocument.TITLE] as? String ?: ""
            val description = data[FirebaseSchema.VideoDocument.DESCRIPTION] as? String ?: ""
            val videoURL = data[FirebaseSchema.VideoDocument.VIDEO_URL] as? String ?: ""
            val thumbnailURL = data[FirebaseSchema.VideoDocument.THUMBNAIL_URL] as? String ?: ""
            val creatorID = data[FirebaseSchema.VideoDocument.CREATOR_ID] as? String ?: ""
            val creatorName = data[FirebaseSchema.VideoDocument.CREATOR_NAME] as? String ?: "Unknown"
            val createdAt = (data[FirebaseSchema.VideoDocument.CREATED_AT] as? Timestamp)?.toDate() ?: Date()
            val threadID = data[FirebaseSchema.VideoDocument.THREAD_ID] as? String ?: id
            val conversationDepth = (data[FirebaseSchema.VideoDocument.CONVERSATION_DEPTH] as? Long)?.toInt() ?: 0

            val viewCount = (data[FirebaseSchema.VideoDocument.VIEW_COUNT] as? Long)?.toInt() ?: 0
            val hypeCount = (data[FirebaseSchema.VideoDocument.HYPE_COUNT] as? Long)?.toInt() ?: 0
            val coolCount = (data[FirebaseSchema.VideoDocument.COOL_COUNT] as? Long)?.toInt() ?: 0
            val replyCount = (data[FirebaseSchema.VideoDocument.REPLY_COUNT] as? Long)?.toInt() ?: 0
            val shareCount = (data[FirebaseSchema.VideoDocument.SHARE_COUNT] as? Long)?.toInt() ?: 0
            val lastEngagementAt = (data[FirebaseSchema.VideoDocument.LAST_ENGAGEMENT_AT] as? Timestamp)?.toDate()

            val duration = data[FirebaseSchema.VideoDocument.DURATION] as? Double ?: 0.0
            val aspectRatio = data[FirebaseSchema.VideoDocument.ASPECT_RATIO] as? Double ?: (9.0 / 16.0)
            val fileSize = data[FirebaseSchema.VideoDocument.FILE_SIZE] as? Long ?: 0L

            @Suppress("UNCHECKED_CAST")
            val hashtags = (data[FirebaseSchema.VideoDocument.HASHTAGS] as? List<String>)?.map { it.lowercase() } ?: emptyList()

            val temperatureStr = data[FirebaseSchema.VideoDocument.TEMPERATURE] as? String ?: "WARM"
            val temperature = try { Temperature.valueOf(temperatureStr.uppercase()) } catch (_: Exception) { Temperature.WARM }

            val contentTypeStr = data[FirebaseSchema.VideoDocument.CONTENT_TYPE] as? String ?: "THREAD"
            val contentType = try { ContentType.valueOf(contentTypeStr.uppercase()) } catch (_: Exception) { ContentType.THREAD }

            val total = hypeCount + coolCount
            val engagementRatio = if (total > 0) hypeCount.toDouble() / total else 0.5

            val parentVideo = CoreVideoMetadata(
                id = id,
                title = title,
                description = description,
                videoURL = videoURL,
                thumbnailURL = thumbnailURL,
                creatorID = creatorID,
                creatorName = creatorName,
                createdAt = createdAt,
                hashtags = hashtags,
                threadID = threadID,
                replyToVideoID = data[FirebaseSchema.VideoDocument.REPLY_TO_VIDEO_ID] as? String,
                conversationDepth = conversationDepth,
                viewCount = viewCount,
                hypeCount = hypeCount,
                coolCount = coolCount,
                replyCount = replyCount,
                shareCount = shareCount,
                lastEngagementAt = lastEngagementAt,
                duration = duration,
                aspectRatio = aspectRatio,
                fileSize = fileSize,
                contentType = contentType,
                temperature = temperature,
                qualityScore = (data[FirebaseSchema.VideoDocument.QUALITY_SCORE] as? Long)?.toInt() ?: 50,
                engagementRatio = engagementRatio,
                velocityScore = data[FirebaseSchema.VideoDocument.VELOCITY_SCORE] as? Double ?: 0.0,
                trendingScore = data[FirebaseSchema.VideoDocument.TRENDING_SCORE] as? Double ?: 0.0,
                discoverabilityScore = data[FirebaseSchema.VideoDocument.DISCOVERABILITY_SCORE] as? Double ?: 0.5,
                isPromoted = data[FirebaseSchema.VideoDocument.IS_PROMOTED] as? Boolean ?: false,
                isProcessing = data["isProcessing"] as? Boolean ?: false,
                isDeleted = data["isDeleted"] as? Boolean ?: false
            )

            return ThreadData(id = threadID, parentVideo = parentVideo, childVideos = emptyList())

        } catch (e: Exception) {
            println("⚠️ HOME FEED: Failed to parse document ${document.id} — ${e.message}")
            return null
        }
    }

    // MARK: - Load Thread Children

    suspend fun loadThreadChildren(threadID: String): List<CoreVideoMetadata> {
        return try {
            val snapshot = db.collection(FirebaseSchema.Collections.VIDEOS)
                .whereEqualTo(FirebaseSchema.VideoDocument.THREAD_ID, threadID)
                .whereGreaterThan(FirebaseSchema.VideoDocument.CONVERSATION_DEPTH, 0)
                .orderBy(FirebaseSchema.VideoDocument.CONVERSATION_DEPTH, Query.Direction.ASCENDING)
                .orderBy(FirebaseSchema.VideoDocument.CREATED_AT, Query.Direction.ASCENDING)
                .limit(50)
                .get()
                .await()

            val children = snapshot.documents.mapNotNull { doc ->
                val thread = createThreadFromDocument(doc)
                thread?.parentVideo // Reuse the same parser, extract video
            }

            // Cache for preloading
            childrenCache[threadID] = children
            println("✅ HOME FEED: Loaded ${children.size} children for thread $threadID")
            children
        } catch (e: Exception) {
            println("❌ HOME FEED: Failed to load children for $threadID — ${e.message}")
            emptyList()
        }
    }

    // MARK: - Children Preloading

    fun getCachedChildren(threadID: String): List<CoreVideoMetadata>? {
        return childrenCache[threadID]
    }

    suspend fun preloadChildrenAround(currentIndex: Int, threads: List<ThreadData>) {
        val range = maxOf(0, currentIndex - 1)..minOf(threads.size - 1, currentIndex + 2)
        for (i in range) {
            val thread = threads[i]
            if (!childrenCache.containsKey(thread.id) && thread.parentVideo.replyCount > 0) {
                try {
                    loadThreadChildren(thread.id)
                } catch (_: Exception) {}
            }
        }
    }

    // MARK: - Following IDs with Caching

    private suspend fun getCachedFollowingIDs(userID: String): List<String> {
        val now = System.currentTimeMillis()
        if (cachedFollowingIDs.isNotEmpty() && (now - followingIDsCacheTime) < followingCacheExpiration) {
            return cachedFollowingIDs
        }

        val followingIDs = userService.getFollowingIDs(userID)
        cachedFollowingIDs = followingIDs
        followingIDsCacheTime = now
        return followingIDs
    }

    fun getFollowingCount(): Int = cachedFollowingIDs.size

    // MARK: - Feed State

    fun shouldLoadMore(currentIndex: Int): Boolean {
        val remaining = currentFeed.size - currentIndex
        return remaining <= triggerLoadThreshold && !isLoading
    }

    fun getCurrentFeed(): List<ThreadData> = currentFeed.toList()

    fun clearFeed() {
        currentFeed.clear()
        currentFeedVideoIDs.clear()
        hasMoreContent = true
        followerRotationIndex = 0
        childrenCache.clear()
    }

    // MARK: - View History Tracking

    fun markVideoSeen(videoID: String) {
        viewHistory?.markVideoSeen(videoID)
    }

    fun saveCurrentPosition(itemIndex: Int, stitchIndex: Int, threadID: String?) {
        viewHistory?.saveFeedPosition(itemIndex, stitchIndex, threadID)
    }

    fun saveCurrentFeed(threads: List<ThreadData>) {
        viewHistory?.saveLastSessionFeed(threads)
    }
}