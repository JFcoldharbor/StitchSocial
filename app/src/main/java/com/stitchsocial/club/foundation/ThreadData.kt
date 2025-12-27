/*
 * ThreadData.kt - FIXED FOR COREVIDEOMETADATA COMPATIBILITY
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Thread data structure and feed service
 * ✅ FIXED: All method signatures match VideoServiceImpl
 */
package com.stitchsocial.club.foundation

import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.UserService
import java.util.Date

/**
 * ThreadData with child video support (MATCHES SWIFT)
 */
data class ThreadData(
    val id: String,
    val parentVideo: CoreVideoMetadata,
    var childVideos: List<CoreVideoMetadata> = emptyList(),
    var isChildrenLoaded: Boolean = false,
    val createdAt: Date = Date(),
    val lastEngagementAt: Date = Date()
) {
    val allVideos: List<CoreVideoMetadata>
        get() = listOf(parentVideo) + childVideos

    val totalVideoCount: Int
        get() = 1 + childVideos.size

    val hasChildren: Boolean
        get() = childVideos.isNotEmpty()

    companion object {
        fun fromVideo(video: CoreVideoMetadata): ThreadData {
            return ThreadData(
                id = video.id,
                parentVideo = video,
                childVideos = emptyList(),
                isChildrenLoaded = false,
                createdAt = video.createdAt,
                lastEngagementAt = video.lastEngagementAt ?: video.createdAt
            )
        }

        fun fromVideos(videos: List<CoreVideoMetadata>): List<ThreadData> {
            return videos.map { fromVideo(it) }
        }
    }
}

/**
 * Enhanced feed service with child video loading AND DISCOVERY METHODS
 * ✅ FIXED: All method calls match VideoServiceImpl signatures
 */
class HybridHomeFeedService(
    private val videoService: VideoServiceImpl,
    private val userService: UserService
) {
    private var cachedFollowingIDs: List<String> = emptyList()
    private var followingCacheTime: Long = 0
    private val followingCacheExpiration = 5 * 60 * 1000L

    private val threadCache = mutableMapOf<String, ThreadData>()
    private val feedCache = mutableListOf<ThreadData>()

    // MARK: - DISCOVERY METHODS

    suspend fun getAllDiscoveryVideos(limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            println("🔍 DISCOVERY: Loading all discovery videos")
            val videos = videoService.getDiscoveryVideos(limit)
            println("🔍 DISCOVERY: Found ${videos.size} discovery videos")
            videos
        } catch (e: Exception) {
            println("❌ DISCOVERY ERROR: ${e.message}")
            emptyList()
        }
    }

    suspend fun getTrendingVideos(limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            println("🔥 TRENDING: Loading trending videos")
            val videos = videoService.getDiscoveryVideos(limit)
            println("🔥 TRENDING: Found ${videos.size} trending videos")
            videos
        } catch (e: Exception) {
            println("❌ TRENDING ERROR: ${e.message}")
            emptyList()
        }
    }

    suspend fun getViralVideos(limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            println("💥 VIRAL: Loading viral videos")
            val videos = videoService.getPersonalizedVideos("", limit)
            println("💥 VIRAL: Found ${videos.size} viral videos")
            videos
        } catch (e: Exception) {
            println("❌ VIRAL ERROR: ${e.message}")
            emptyList()
        }
    }

    suspend fun getFreshVideos(limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            println("🌱 FRESH: Loading fresh videos")
            val videos = videoService.getFeedVideos(limit)
            println("🌱 FRESH: Found ${videos.size} fresh videos")
            videos
        } catch (e: Exception) {
            println("❌ FRESH ERROR: ${e.message}")
            emptyList()
        }
    }

    suspend fun getQualityVideos(limit: Int = 50): List<CoreVideoMetadata> {
        return try {
            println("⭐ QUALITY: Loading quality videos")
            val videos = videoService.getDiscoveryVideos(limit)
            println("⭐ QUALITY: Found ${videos.size} quality videos")
            videos
        } catch (e: Exception) {
            println("❌ QUALITY ERROR: ${e.message}")
            emptyList()
        }
    }

    // MARK: - FOLLOWING CACHE

    suspend fun getCachedFollowingIDs(userID: String): List<String> {
        val now = System.currentTimeMillis()

        if (cachedFollowingIDs.isNotEmpty() &&
            (now - followingCacheTime) < followingCacheExpiration) {
            return cachedFollowingIDs
        }

        return try {
            val followingIDs = userService.getFollowingIDs(userID)
            cachedFollowingIDs = followingIDs
            followingCacheTime = now
            println("📱 FOLLOWING CACHE: Loaded ${followingIDs.size} following IDs")
            followingIDs
        } catch (e: Exception) {
            println("❌ FOLLOWING ERROR: ${e.message}")
            emptyList()
        }
    }

    // MARK: - FEED LOADING

    suspend fun loadFeedThreads(userID: String, limit: Int = 20): List<ThreadData> {
        return try {
            println("🎬 FEED LOADING: Starting feed load for user $userID")

            if (feedCache.isNotEmpty()) {
                println("⚡ INSTANT FEED: Returning ${feedCache.size} cached threads")
                return feedCache.take(limit)
            }

            val freshThreads = loadFreshThreads(userID, limit)
            feedCache.clear()
            feedCache.addAll(freshThreads)

            println("✅ FEED LOADED: ${freshThreads.size} threads")
            freshThreads

        } catch (e: Exception) {
            println("❌ FEED LOADING ERROR: ${e.message}")
            emptyList()
        }
    }

    private suspend fun loadFreshThreads(userID: String, limit: Int): List<ThreadData> {
        val followingIDs = getCachedFollowingIDs(userID)

        return if (followingIDs.isNotEmpty()) {
            loadFollowingBasedFeed(followingIDs, limit)
        } else {
            loadTrendingFeed(limit)
        }
    }

    private suspend fun loadFollowingBasedFeed(followingIDs: List<String>, limit: Int): List<ThreadData> {
        return try {
            println("👥 FOLLOWING FEED: Loading from ${followingIDs.size} users")

            // ✅ FIXED: Correct signature - getFeedVideos(List<String>, Int)
            val feedVideos = videoService.getFeedVideos(followingIDs, limit)

            val threads = feedVideos.map { video ->
                ThreadData.fromVideo(video)
            }

            println("👥 FOLLOWING FEED: Created ${threads.size} threads")
            threads

        } catch (e: Exception) {
            println("❌ FOLLOWING FEED ERROR: ${e.message}")
            emptyList()
        }
    }

    private suspend fun loadTrendingFeed(limit: Int): List<ThreadData> {
        return try {
            println("🔥 TRENDING FEED: Loading trending content")

            // ✅ FIXED: Correct signature - getDiscoveryVideos(Int)
            val trendingVideos = videoService.getDiscoveryVideos(limit)

            val threads = trendingVideos.map { video ->
                ThreadData.fromVideo(video)
            }

            println("🔥 TRENDING FEED: Created ${threads.size} threads")
            threads

        } catch (e: Exception) {
            println("❌ TRENDING FEED ERROR: ${e.message}")
            emptyList()
        }
    }

    suspend fun loadThreadChildren(threadID: String): List<CoreVideoMetadata> {
        return try {
            println("🔄 LOADING CHILDREN: For thread $threadID")

            threadCache[threadID]?.let { cachedThread ->
                if (cachedThread.isChildrenLoaded) {
                    println("⚡ CACHED CHILDREN: Returning ${cachedThread.childVideos.size}")
                    return cachedThread.childVideos
                }
            }

            val children = videoService.getThreadChildren(threadID)

            threadCache[threadID]?.let { existingThread ->
                threadCache[threadID] = existingThread.copy(
                    childVideos = children,
                    isChildrenLoaded = true
                )
            }

            println("✅ CHILDREN LOADED: ${children.size}")
            children

        } catch (e: Exception) {
            println("❌ CHILDREN LOADING ERROR: ${e.message}")
            emptyList()
        }
    }

    suspend fun preloadThreadChildren(threadID: String) {
        if (threadCache[threadID]?.isChildrenLoaded != true) {
            loadThreadChildren(threadID)
        }
    }

    fun clearCaches() {
        threadCache.clear()
        feedCache.clear()
        cachedFollowingIDs = emptyList()
        followingCacheTime = 0
        println("🧹 CACHE CLEARED")
    }

    fun getInstantFeed(limit: Int = 20): List<ThreadData>? {
        return if (feedCache.isNotEmpty()) feedCache.take(limit) else null
    }
}