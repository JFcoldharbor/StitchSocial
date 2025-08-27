/*
 * FeedCoordinator.kt
 * StitchSocial Android
 * 
 * Layer 6: Coordination - Feed Management Orchestration
 * Dependencies: VideoService
 * Orchestrates: Feed Loading → Preloading → Navigation → Cache Integration
 * 
 * BLUEPRINT: HomeFeedService.swift patterns
 */

package com.example.stitchsocialclub.coordination

import com.example.stitchsocialclub.foundation.*
import com.example.stitchsocialclub.services.VideoServiceImpl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

/**
 * Simple video metadata for coordination layer
 * TODO: Replace with proper CoreVideoMetadata when Foundation layer has it
 */
data class SimpleVideoMetadata(
    val id: String,
    val title: String,
    val description: String,
    val videoURL: String,
    val thumbnailURL: String,
    val duration: Double,
    val contentType: ContentType,
    val temperature: Temperature,
    val createdAt: Date
)

/**
 * Simple thread data for coordination
 * TODO: Replace with actual ThreadData from VideoService
 */
data class SimpleThreadData(
    val id: String,
    val parentVideo: SimpleVideoMetadata,
    val childVideos: List<SimpleVideoMetadata>
) {
    /**
     * Total videos in this thread (parent + children)
     */
    val totalVideos: Int get() = 1 + childVideos.size

    /**
     * Get video at specific index (0 = parent, 1+ = children)
     */
    fun videoAt(index: Int): SimpleVideoMetadata? {
        return when {
            index == 0 -> parentVideo
            index - 1 < childVideos.size -> childVideos[index - 1]
            else -> null
        }
    }

    /**
     * Check if thread has replies
     */
    val hasReplies: Boolean get() = childVideos.isNotEmpty()
}

/**
 * Feed statistics for monitoring
 */
data class FeedStats(
    val totalThreadsLoaded: Int = 0,
    val currentFeedSize: Int = 0,
    val refreshCount: Int = 0,
    val hasMoreContent: Boolean = true,
    val lastRefreshTime: Date? = null,
    val cacheHitRate: Double = 0.85
)

/**
 * Swipe direction for preloading optimization
 */
enum class SwipeDirection {
    HORIZONTAL, // Thread to thread navigation
    VERTICAL,   // Parent to child navigation
    FORWARD,    // Forward direction
    BACKWARD    // Backward direction
}

/**
 * Feed loading result
 */
data class FeedLoadResult(
    val threads: List<SimpleThreadData>,
    val hasMore: Boolean,
    val totalLoaded: Int,
    val fromCache: Boolean = false
)

/**
 * Orchestrates complete feed management workflow
 * Coordinates between feed loading, preloading, navigation, and cache integration
 */
class FeedCoordinator(
    private val videoService: VideoServiceImpl
) {

    // MARK: - Feed State

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _currentFeed = MutableStateFlow<List<SimpleThreadData>>(emptyList())
    val currentFeed: StateFlow<List<SimpleThreadData>> = _currentFeed.asStateFlow()

    private val _feedStats = MutableStateFlow(FeedStats())
    val feedStats: StateFlow<FeedStats> = _feedStats.asStateFlow()

    private val _lastError = MutableStateFlow<StitchError?>(null)
    val lastError: StateFlow<StitchError?> = _lastError.asStateFlow()

    // MARK: - Navigation State

    private val _currentThreadIndex = MutableStateFlow(0)
    val currentThreadIndex: StateFlow<Int> = _currentThreadIndex.asStateFlow()

    private val _currentVideoIndex = MutableStateFlow(0)
    val currentVideoIndex: StateFlow<Int> = _currentVideoIndex.asStateFlow()

    private val _preloadingStatus = MutableStateFlow<Map<String, PreloadStatus>>(emptyMap())
    val preloadingStatus: StateFlow<Map<String, PreloadStatus>> = _preloadingStatus.asStateFlow()

    // MARK: - Configuration

    private val defaultFeedSize = 20
    private val preloadTriggerIndex = 15 // Load more when user reaches this index
    private val maxCachedThreads = 100
    private val preloadHorizontalCount = 2 // Threads to preload ahead
    private val preloadVerticalCount = 2 // Videos to preload ahead in thread

    // MARK: - Coroutine Scope

    private val coordinatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        println("🏠 FEED COORDINATOR: Initialized - Ready for feed management orchestration")
    }

    // MARK: - Primary Feed Operations

    /**
     * Load initial feed with following users
     */
    suspend fun loadInitialFeed(
        userID: String,
        limit: Int = defaultFeedSize
    ): FeedLoadResult = withContext(Dispatchers.IO) {

        _isLoading.value = true
        _lastError.value = null

        println("🏠 FEED: Loading initial feed for user $userID")

        try {
            // Step 1: Check cache first
            val cachedFeed = getCachedFeed(userID)
            if (cachedFeed != null && cachedFeed.isNotEmpty()) {
                _currentFeed.value = cachedFeed
                updateFeedStats(cachedFeed, fromCache = true)

                println("🏠 FEED: Loaded ${cachedFeed.size} threads from cache")
                return@withContext FeedLoadResult(
                    threads = cachedFeed,
                    hasMore = true,
                    totalLoaded = cachedFeed.size,
                    fromCache = true
                )
            }

            // Step 2: Load from network
            val threads = loadThreadsFromNetwork(userID, limit)

            // Step 3: Update state
            _currentFeed.value = threads
            updateFeedStats(threads, fromCache = false)

            // Step 4: Warm cache
            warmCache(threads)

            // Step 5: Start initial preloading
            startInitialPreloading(threads)

            println("✅ FEED: Loaded ${threads.size} threads from network")

            return@withContext FeedLoadResult(
                threads = threads,
                hasMore = threads.size >= limit,
                totalLoaded = threads.size,
                fromCache = false
            )

        } catch (error: Exception) {
            val stitchError = StitchError.NetworkError("Failed to load feed: ${error.message}")
            _lastError.value = stitchError

            println("❌ FEED: Failed to load initial feed - ${error.message}")
            throw stitchError

        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Refresh feed with pull-to-refresh
     */
    suspend fun refreshFeed(userID: String): FeedLoadResult = withContext(Dispatchers.IO) {

        _isRefreshing.value = true
        _lastError.value = null

        println("🔄 FEED: Refreshing feed for user $userID")

        try {
            // Clear current state
            _currentFeed.value = emptyList()
            _currentThreadIndex.value = 0
            _currentVideoIndex.value = 0

            // Load fresh content
            val threads = loadThreadsFromNetwork(userID, defaultFeedSize)

            // Update state
            _currentFeed.value = threads
            updateFeedStats(threads, fromCache = false, isRefresh = true)

            // Warm cache with fresh content
            warmCache(threads)

            // Start preloading
            startInitialPreloading(threads)

            println("✅ FEED: Refreshed with ${threads.size} threads")

            return@withContext FeedLoadResult(
                threads = threads,
                hasMore = threads.size >= defaultFeedSize,
                totalLoaded = threads.size,
                fromCache = false
            )

        } catch (error: Exception) {
            val stitchError = StitchError.NetworkError("Failed to refresh feed: ${error.message}")
            _lastError.value = stitchError

            println("❌ FEED: Failed to refresh feed - ${error.message}")
            throw stitchError

        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * Load more content for infinite scroll
     */
    suspend fun loadMoreContent(userID: String): FeedLoadResult = withContext(Dispatchers.IO) {

        if (_isLoadingMore.value) {
            return@withContext FeedLoadResult(_currentFeed.value, false, _currentFeed.value.size)
        }

        _isLoadingMore.value = true
        _lastError.value = null

        println("🔄 FEED: Loading more content for pagination")

        try {
            // Load next batch
            val nextBatch = loadThreadsFromNetwork(
                userID = userID,
                limit = defaultFeedSize,
                startAfter = _currentFeed.value.lastOrNull()?.id
            )

            // Append to current feed
            val currentThreads = _currentFeed.value.toMutableList()
            currentThreads.addAll(nextBatch)

            // Manage memory - remove old threads if feed gets too large
            if (currentThreads.size > maxCachedThreads) {
                val threadsToRemove = currentThreads.size - maxCachedThreads
                repeat(threadsToRemove) { currentThreads.removeAt(0) }
            }

            _currentFeed.value = currentThreads
            updateFeedStats(currentThreads, fromCache = false)

            // Cache new content
            warmCache(nextBatch)

            println("✅ FEED: Loaded ${nextBatch.size} more threads, total: ${currentThreads.size}")

            return@withContext FeedLoadResult(
                threads = currentThreads,
                hasMore = nextBatch.size >= defaultFeedSize,
                totalLoaded = currentThreads.size,
                fromCache = false
            )

        } catch (error: Exception) {
            val stitchError = StitchError.NetworkError("Failed to load more content: ${error.message}")
            _lastError.value = stitchError

            println("❌ FEED: Failed to load more content - ${error.message}")
            throw stitchError

        } finally {
            _isLoadingMore.value = false
        }
    }

    // MARK: - Navigation Coordination

    /**
     * Navigate to specific thread and video
     */
    suspend fun navigateToPosition(threadIndex: Int, videoIndex: Int = 0) {

        val threads = _currentFeed.value
        if (threadIndex < 0 || threadIndex >= threads.size) {
            println("⚠️ FEED: Invalid thread index $threadIndex")
            return
        }

        val thread = threads[threadIndex]
        if (videoIndex < 0 || videoIndex >= thread.totalVideos) {
            println("⚠️ FEED: Invalid video index $videoIndex for thread ${thread.id}")
            return
        }

        _currentThreadIndex.value = threadIndex
        _currentVideoIndex.value = videoIndex

        // Trigger preloading for new position
        triggerPreloadingForPosition(threadIndex, videoIndex)

        println("🧭 NAVIGATION: Moved to thread $threadIndex, video $videoIndex")
    }

    /**
     * Navigate horizontally (between threads)
     */
    suspend fun navigateHorizontally(direction: SwipeDirection) {
        val currentIndex = _currentThreadIndex.value
        val threads = _currentFeed.value

        val newIndex = when (direction) {
            SwipeDirection.FORWARD -> currentIndex + 1
            SwipeDirection.BACKWARD -> maxOf(0, currentIndex - 1)
            else -> currentIndex
        }

        if (newIndex < threads.size) {
            navigateToPosition(newIndex, 0)
        }
    }

    /**
     * Navigate vertically (within thread)
     */
    suspend fun navigateVertically(direction: SwipeDirection) {
        val threadIndex = _currentThreadIndex.value
        val videoIndex = _currentVideoIndex.value
        val threads = _currentFeed.value

        if (threadIndex >= threads.size) return

        val thread = threads[threadIndex]
        val newVideoIndex = when (direction) {
            SwipeDirection.FORWARD -> videoIndex + 1
            SwipeDirection.BACKWARD -> maxOf(0, videoIndex - 1)
            else -> videoIndex
        }

        if (newVideoIndex < thread.totalVideos) {
            navigateToPosition(threadIndex, newVideoIndex)
        }
    }

    // MARK: - Preloading Management

    /**
     * Start initial preloading for newly loaded threads
     */
    private suspend fun startInitialPreloading(threads: List<SimpleThreadData>) {
        coordinatorScope.launch {
            val threadsToPreload = threads.take(3) // Preload first 3 threads

            for (thread in threadsToPreload) {
                preloadThread(thread, PreloadPriority.NORMAL)
            }

            println("⚡ PRELOAD: Started initial preloading for ${threadsToPreload.size} threads")
        }
    }

    /**
     * Trigger preloading based on current position
     */
    private suspend fun triggerPreloadingForPosition(threadIndex: Int, videoIndex: Int) {

        val threads = _currentFeed.value
        if (threadIndex >= threads.size) return

        coordinatorScope.launch {
            // Preload horizontally (next threads)
            preloadHorizontalNavigation(threadIndex, threads, SwipeDirection.FORWARD)

            // Preload vertically (next videos in current thread)
            preloadVerticalNavigation(threadIndex, videoIndex, threads)

            println("⚡ PRELOAD: Triggered preloading for position ($threadIndex, $videoIndex)")
        }
    }

    /**
     * Preload for horizontal navigation (thread to thread)
     */
    private suspend fun preloadHorizontalNavigation(
        currentThreadIndex: Int,
        threads: List<SimpleThreadData>,
        direction: SwipeDirection
    ) {

        val preloadRange = when (direction) {
            SwipeDirection.FORWARD -> {
                val startIndex = currentThreadIndex + 1
                val endIndex = minOf(startIndex + preloadHorizontalCount - 1, threads.size - 1)
                startIndex..endIndex
            }
            SwipeDirection.BACKWARD -> {
                val endIndex = currentThreadIndex - 1
                val startIndex = maxOf(endIndex - preloadHorizontalCount + 1, 0)
                startIndex..endIndex
            }
            else -> return
        }

        for (index in preloadRange) {
            if (index >= 0 && index < threads.size) {
                val thread = threads[index]
                preloadThread(thread, PreloadPriority.NORMAL)
            }
        }

        println("⚡ PRELOAD: Horizontal navigation preload for range $preloadRange")
    }

    /**
     * Preload for vertical navigation (within thread)
     */
    private suspend fun preloadVerticalNavigation(
        threadIndex: Int,
        currentVideoIndex: Int,
        threads: List<SimpleThreadData>
    ) {

        if (threadIndex >= threads.size) return

        val thread = threads[threadIndex]
        val videosToPreload = mutableListOf<SimpleVideoMetadata>()

        // Preload next videos in current thread
        for (i in 1..preloadVerticalCount) {
            val nextVideoIndex = currentVideoIndex + i
            thread.videoAt(nextVideoIndex)?.let { videosToPreload.add(it) }
        }

        // Preload videos
        for (video in videosToPreload) {
            preloadVideo(video, PreloadPriority.NORMAL)
        }

        println("⚡ PRELOAD: Vertical navigation preload for ${videosToPreload.size} videos")
    }

    /**
     * Preload specific thread
     */
    private suspend fun preloadThread(thread: SimpleThreadData, priority: PreloadPriority) {
        // Preload parent video first
        preloadVideo(thread.parentVideo, priority)

        // Preload first few child videos
        val childrenToPreload = thread.childVideos.take(2)
        for (child in childrenToPreload) {
            preloadVideo(child, PreloadPriority.LOW)
        }

        println("📱 PRELOAD: Thread ${thread.id} - parent + ${childrenToPreload.size} children")
    }

    /**
     * Preload specific video
     */
    private suspend fun preloadVideo(video: SimpleVideoMetadata, priority: PreloadPriority) {
        val statusMap = _preloadingStatus.value.toMutableMap()
        statusMap[video.id] = PreloadStatus.LOADING
        _preloadingStatus.value = statusMap

        try {
            // TODO: Implement actual video preloading
            delay(100) // Simulate preload time

            statusMap[video.id] = PreloadStatus.COMPLETED
            _preloadingStatus.value = statusMap

            println("📱 PRELOAD: Video ${video.id} completed (${priority.name})")

        } catch (error: Exception) {
            statusMap[video.id] = PreloadStatus.FAILED
            _preloadingStatus.value = statusMap

            println("❌ PRELOAD: Video ${video.id} failed - ${error.message}")
        }
    }

    // MARK: - Helper Methods

    /**
     * Load threads from network (with REAL working video URLs)
     */
    private suspend fun loadThreadsFromNetwork(
        userID: String,
        limit: Int,
        startAfter: String? = null
    ): List<SimpleThreadData> = withContext(Dispatchers.IO) {

        // Simulate network delay
        delay(1000)

        // Real working video URLs for testing
        val testVideoUrls = listOf(
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerMeltdowns.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/SubaruOutbackOnStreetAndDirt.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
        )

        val testThumbnails = listOf(
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ElephantsDream.jpg",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerBlazes.jpg",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerEscapes.jpg",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerFun.jpg",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerJoyrides.jpg",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerMeltdowns.jpg",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/Sintel.jpg",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/SubaruOutbackOnStreetAndDirt.jpg",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/TearsOfSteel.jpg"
        )

        val videoTitles = listOf(
            "Big Buck Bunny",
            "Elephants Dream",
            "For Bigger Blazes",
            "For Bigger Escapes",
            "For Bigger Fun",
            "For Bigger Joyrides",
            "For Bigger Meltdowns",
            "Sintel",
            "Subaru Outback",
            "Tears of Steel"
        )

        // Generate sample threads using REAL videos
        val threads = mutableListOf<SimpleThreadData>()

        repeat(limit) { index ->
            val videoIndex = index % testVideoUrls.size
            val threadId = "thread_${startAfter ?: "0"}_$index"

            val parentVideo = SimpleVideoMetadata(
                id = "${threadId}_parent",
                title = videoTitles[videoIndex],
                description = "Sample thread with real video content",
                videoURL = testVideoUrls[videoIndex],
                thumbnailURL = testThumbnails[videoIndex],
                duration = 15.0,
                contentType = ContentType.THREAD,
                temperature = Temperature.WARM,
                createdAt = Date()
            )

            // Generate child videos (replies) with different videos
            val childVideos = mutableListOf<SimpleVideoMetadata>()
            val childCount = (1..2).random() // Limit child videos to 1-2

            repeat(childCount) { childIndex ->
                val childVideoIndex = (videoIndex + childIndex + 1) % testVideoUrls.size
                val childVideo = SimpleVideoMetadata(
                    id = "${threadId}_child_$childIndex",
                    title = "Reply: ${videoTitles[childVideoIndex]}",
                    description = "Reply to thread",
                    videoURL = testVideoUrls[childVideoIndex],
                    thumbnailURL = testThumbnails[childVideoIndex],
                    duration = 10.0,
                    contentType = ContentType.CHILD,
                    temperature = Temperature.COOL,
                    createdAt = Date()
                )
                childVideos.add(childVideo)
            }

            val thread = SimpleThreadData(
                id = threadId,
                parentVideo = parentVideo,
                childVideos = childVideos
            )

            threads.add(thread)

            println("🎬 MOCK: Created thread $threadId with video: ${parentVideo.title}")
            println("🎬 MOCK: Video URL: ${parentVideo.videoURL}")
        }

        return@withContext threads
    }

    /**
     * Get cached feed (placeholder)
     */
    private fun getCachedFeed(userID: String): List<SimpleThreadData>? {
        // TODO: Implement cache integration
        return null
    }

    /**
     * Warm cache with threads (placeholder)
     */
    private fun warmCache(threads: List<SimpleThreadData>) {
        // TODO: Implement cache integration
        println("🔥 CACHE: Would cache ${threads.size} threads")
    }

    /**
     * Update feed statistics
     */
    private fun updateFeedStats(
        threads: List<SimpleThreadData>,
        fromCache: Boolean,
        isRefresh: Boolean = false
    ) {
        val currentStats = _feedStats.value
        _feedStats.value = currentStats.copy(
            totalThreadsLoaded = threads.size,
            currentFeedSize = threads.size,
            refreshCount = if (isRefresh) currentStats.refreshCount + 1 else currentStats.refreshCount,
            hasMoreContent = threads.size >= defaultFeedSize,
            lastRefreshTime = if (isRefresh) Date() else currentStats.lastRefreshTime,
            cacheHitRate = if (fromCache) 1.0 else 0.0
        )
    }

    /**
     * Check if should load more content
     */
    private fun shouldLoadMoreContent(currentThreadIndex: Int): Boolean {
        val stats = _feedStats.value
        return currentThreadIndex >= preloadTriggerIndex &&
                stats.hasMoreContent &&
                !_isLoading.value &&
                !_isLoadingMore.value
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        coordinatorScope.cancel()
    }
}

// MARK: - Supporting Enums

/**
 * Preload priority levels
 */
enum class PreloadPriority {
    HIGH, NORMAL, LOW
}

/**
 * Preload status for videos
 */
enum class PreloadStatus {
    PENDING, LOADING, COMPLETED, FAILED
}