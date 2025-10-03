package com.stitchsocial.club.foundation

import android.content.Context
import android.content.SharedPreferences
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Simplified high-performance caching system for Stitch Social
 * Layer 4: Core Services - Provides instant app startup and smooth UX
 *
 * SIMPLIFIED VERSION - Works with existing project structure
 * Uses BasicUserInfo instead of UserProfileData until migration is complete
 */
class SimplifiedCachingService(
    private val context: Context
) {

    // ===== CACHE CONFIGURATION =====

    companion object {
        // Memory cache limits
        private const val MAX_VIDEO_ENTRIES = 100
        private const val MAX_USER_ENTRIES = 200
        private const val MAX_THREAD_ENTRIES = 50

        // TTL settings (milliseconds)
        private const val VIDEO_TTL = 3600000L      // 1 hour
        private const val USER_TTL = 1800000L       // 30 minutes
        private const val THREAD_TTL = 600000L      // 10 minutes

        // Cache keys
        private const val PREFS_NAME = "stitch_cache"
    }

    // ===== CACHE PRIORITY SYSTEM =====

    enum class CachePriority(val score: Int) {
        LOW(1),
        NORMAL(2),
        HIGH(3),
        CRITICAL(4)
    }

    // ===== CACHE DATA STRUCTURES =====

    data class CacheEntry<T>(
        val data: T,
        val cachedAt: Date,
        val priority: CachePriority,
        val ttl: Long,
        var accessCount: Int = 0,
        var lastAccessed: Date = Date()
    ) {
        val isExpired: Boolean
            get() = (System.currentTimeMillis() - cachedAt.time) > ttl

        fun accessed(): CacheEntry<T> {
            accessCount++
            lastAccessed = Date()
            return this
        }
    }

    data class SimpleThreadData(
        val id: String,
        val parentVideo: BasicVideoInfo,
        val childVideos: List<BasicVideoInfo>,
        val totalEngagement: Int,
        val lastActivity: Date
    )

    // ===== CACHE STORAGE =====

    // Simple concurrent hash maps for thread safety
    private val videoCache = ConcurrentHashMap<String, CacheEntry<BasicVideoInfo>>()
    private val userCache = ConcurrentHashMap<String, CacheEntry<BasicUserInfo>>()
    private val threadCache = ConcurrentHashMap<String, CacheEntry<SimpleThreadData>>()

    // Persistent storage
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Thread safety
    private val cacheMutex = Mutex()

    // ===== METRICS =====

    data class CacheMetrics(
        val memoryHits: Long = 0,
        val memoryMisses: Long = 0,
        val evictions: Long = 0
    ) {
        val totalRequests: Long get() = memoryHits + memoryMisses
        val hitRate: Double get() = if (totalRequests > 0) memoryHits.toDouble() / totalRequests else 0.0
    }

    private var metrics = CacheMetrics()

    // ===== VIDEO CACHING =====

    /**
     * Cache video with priority-based storage
     */
    suspend fun cacheVideo(video: BasicVideoInfo, priority: CachePriority = CachePriority.NORMAL) {
        cacheMutex.withLock {
            try {
                val entry = CacheEntry(
                    data = video,
                    cachedAt = Date(),
                    priority = priority,
                    ttl = VIDEO_TTL
                )

                videoCache[video.id] = entry

                // Manage cache size
                if (videoCache.size > MAX_VIDEO_ENTRIES) {
                    evictOldestVideo()
                }

                println("CACHE SERVICE: Cached video ${video.id} with priority ${priority.name}")

            } catch (e: Exception) {
                println("CACHE SERVICE: Error caching video ${video.id}: ${e.message}")
            }
        }
    }

    /**
     * Retrieve cached video
     */
    suspend fun getCachedVideo(id: String): BasicVideoInfo? {
        return cacheMutex.withLock {
            try {
                val entry = videoCache[id]
                if (entry != null && !entry.isExpired) {
                    videoCache[id] = entry.accessed()
                    metrics = metrics.copy(memoryHits = metrics.memoryHits + 1)
                    return@withLock entry.data
                }

                // Remove expired entry
                if (entry?.isExpired == true) {
                    videoCache.remove(id)
                }

                metrics = metrics.copy(memoryMisses = metrics.memoryMisses + 1)
                null

            } catch (e: Exception) {
                println("CACHE SERVICE: Error retrieving video $id: ${e.message}")
                null
            }
        }
    }

    // ===== USER CACHING =====

    /**
     * Cache user profile with priority-based storage
     */
    suspend fun cacheUser(user: BasicUserInfo, priority: CachePriority = CachePriority.NORMAL) {
        cacheMutex.withLock {
            try {
                val entry = CacheEntry(
                    data = user,
                    cachedAt = Date(),
                    priority = priority,
                    ttl = USER_TTL
                )

                userCache[user.id] = entry

                // Manage cache size
                if (userCache.size > MAX_USER_ENTRIES) {
                    evictOldestUser()
                }

                println("CACHE SERVICE: Cached user ${user.id} (@${user.username}) with priority ${priority.name}")

            } catch (e: Exception) {
                println("CACHE SERVICE: Error caching user ${user.id}: ${e.message}")
            }
        }
    }

    /**
     * Retrieve cached user
     */
    suspend fun getCachedUser(id: String): BasicUserInfo? {
        return cacheMutex.withLock {
            try {
                val entry = userCache[id]
                if (entry != null && !entry.isExpired) {
                    userCache[id] = entry.accessed()
                    metrics = metrics.copy(memoryHits = metrics.memoryHits + 1)
                    return@withLock entry.data
                }

                // Remove expired entry
                if (entry?.isExpired == true) {
                    userCache.remove(id)
                }

                metrics = metrics.copy(memoryMisses = metrics.memoryMisses + 1)
                null

            } catch (e: Exception) {
                println("CACHE SERVICE: Error retrieving user $id: ${e.message}")
                null
            }
        }
    }

    // ===== THREAD CACHING =====

    /**
     * Cache complete thread data for feed performance
     */
    suspend fun cacheThread(thread: SimpleThreadData, priority: CachePriority = CachePriority.NORMAL) {
        cacheMutex.withLock {
            try {
                val entry = CacheEntry(
                    data = thread,
                    cachedAt = Date(),
                    priority = priority,
                    ttl = THREAD_TTL
                )

                threadCache[thread.id] = entry

                // Also cache individual videos in the thread
                cacheVideo(thread.parentVideo, priority)
                thread.childVideos.forEach { childVideo ->
                    cacheVideo(childVideo, priority)
                }

                // Manage cache size
                if (threadCache.size > MAX_THREAD_ENTRIES) {
                    evictOldestThread()
                }

                println("CACHE SERVICE: Cached thread ${thread.id} with ${thread.childVideos.size} children")

            } catch (e: Exception) {
                println("CACHE SERVICE: Error caching thread ${thread.id}: ${e.message}")
            }
        }
    }

    /**
     * Retrieve cached thread data
     */
    suspend fun getCachedThread(id: String): SimpleThreadData? {
        return cacheMutex.withLock {
            try {
                val entry = threadCache[id]
                if (entry != null && !entry.isExpired) {
                    threadCache[id] = entry.accessed()
                    metrics = metrics.copy(memoryHits = metrics.memoryHits + 1)
                    return@withLock entry.data
                }

                // Remove expired entry
                if (entry?.isExpired == true) {
                    threadCache.remove(id)
                }

                metrics = metrics.copy(memoryMisses = metrics.memoryMisses + 1)
                null

            } catch (e: Exception) {
                println("CACHE SERVICE: Error retrieving thread $id: ${e.message}")
                null
            }
        }
    }

    // ===== INSTANT FEED SUPPORT =====

    /**
     * CRITICAL: Check if instant feed content is available for user
     */
    fun hasInstantFeedContent(userID: String, minimumThreads: Int = 5): Boolean {
        return try {
            val threadCount = sharedPrefs.getInt("instant_feed_${userID}_count", 0)
            val lastCached = sharedPrefs.getLong("instant_feed_${userID}_timestamp", 0)
            val cacheAge = System.currentTimeMillis() - lastCached

            // Feed is "instant" if we have enough threads and cache is fresh (<10 minutes)
            val hasSufficientContent = threadCount >= minimumThreads
            val isFreshEnough = cacheAge < 600000L // 10 minutes

            hasSufficientContent && isFreshEnough

        } catch (e: Exception) {
            println("CACHE SERVICE: Error checking instant feed for $userID: ${e.message}")
            false
        }
    }

    /**
     * CRITICAL: Get instant feed for app startup
     */
    suspend fun getInstantFeed(userID: String, limit: Int = 20): List<SimpleThreadData>? {
        return try {
            if (!hasInstantFeedContent(userID, 3)) return null

            // Get cached thread IDs for this user's feed
            val threadIdsString = sharedPrefs.getString("instant_feed_${userID}_ids", null) ?: return null
            val threadIds = threadIdsString.split(",").take(limit)

            // Collect available threads from memory cache
            val availableThreads = mutableListOf<SimpleThreadData>()

            cacheMutex.withLock {
                for (threadId in threadIds) {
                    val cachedThread = threadCache[threadId]?.data
                    if (cachedThread != null) {
                        availableThreads.add(cachedThread)
                    }
                }
            }

            println("CACHE SERVICE: Instant feed for $userID - ${availableThreads.size}/${threadIds.size} threads available")

            if (availableThreads.size >= 3) availableThreads else null

        } catch (e: Exception) {
            println("CACHE SERVICE: Error loading instant feed for $userID: ${e.message}")
            null
        }
    }

    /**
     * Preload user's feed for next app startup
     */
    suspend fun preloadUserFeed(userID: String, threads: List<SimpleThreadData>) {
        try {
            val threadIds = threads.map { it.id }.joinToString(",")

            // Cache all threads with HIGH priority
            threads.forEach { thread ->
                cacheThread(thread, CachePriority.HIGH)
            }

            // Store feed metadata for instant startup
            sharedPrefs.edit()
                .putString("instant_feed_${userID}_ids", threadIds)
                .putInt("instant_feed_${userID}_count", threads.size)
                .putLong("instant_feed_${userID}_timestamp", System.currentTimeMillis())
                .apply()

            println("CACHE SERVICE: Preloaded ${threads.size} threads for user $userID instant startup")

        } catch (e: Exception) {
            println("CACHE SERVICE: Error preloading feed for $userID: ${e.message}")
        }
    }

    // ===== CACHE MANAGEMENT =====

    /**
     * Clear expired entries from all caches
     */
    suspend fun clearExpiredEntries() {
        cacheMutex.withLock {
            try {
                var removedCount = 0

                // Clear expired videos
                val expiredVideos = videoCache.filter { it.value.isExpired }.keys
                expiredVideos.forEach {
                    videoCache.remove(it)
                    removedCount++
                }

                // Clear expired users
                val expiredUsers = userCache.filter { it.value.isExpired }.keys
                expiredUsers.forEach {
                    userCache.remove(it)
                    removedCount++
                }

                // Clear expired threads
                val expiredThreads = threadCache.filter { it.value.isExpired }.keys
                expiredThreads.forEach {
                    threadCache.remove(it)
                    removedCount++
                }

                if (removedCount > 0) {
                    println("CACHE SERVICE: Cleared $removedCount expired entries")
                }

            } catch (e: Exception) {
                println("CACHE SERVICE: Error clearing expired entries: ${e.message}")
            }
        }
    }

    /**
     * Get cache status for debugging
     */
    fun getCacheStatus(): String {
        return """
            CACHE STATUS:
            - Videos: ${videoCache.size}/$MAX_VIDEO_ENTRIES
            - Users: ${userCache.size}/$MAX_USER_ENTRIES  
            - Threads: ${threadCache.size}/$MAX_THREAD_ENTRIES
            - Hit Rate: ${String.format("%.1f%%", metrics.hitRate * 100)}
            - Total Requests: ${metrics.totalRequests}
            - Evictions: ${metrics.evictions}
            """.trimIndent()
    }

    /**
     * Clear all caches
     */
    suspend fun clearAllCaches() {
        cacheMutex.withLock {
            videoCache.clear()
            userCache.clear()
            threadCache.clear()

            // Clear disk cache
            sharedPrefs.edit().clear().apply()

            metrics = CacheMetrics()

            println("CACHE SERVICE: All caches cleared")
        }
    }

    // ===== PRIVATE HELPER METHODS =====

    private fun evictOldestVideo() {
        try {
            // Find oldest non-critical entry
            val oldestEntry = videoCache.entries
                .filter { it.value.priority != CachePriority.CRITICAL }
                .minByOrNull { it.value.lastAccessed.time }

            oldestEntry?.let { entry ->
                videoCache.remove(entry.key)
                metrics = metrics.copy(evictions = metrics.evictions + 1)
                println("CACHE SERVICE: Evicted video ${entry.key}")
            }
        } catch (e: Exception) {
            println("CACHE SERVICE: Error evicting video: ${e.message}")
        }
    }

    private fun evictOldestUser() {
        try {
            val oldestEntry = userCache.entries
                .filter { it.value.priority != CachePriority.CRITICAL }
                .minByOrNull { it.value.lastAccessed.time }

            oldestEntry?.let { entry ->
                userCache.remove(entry.key)
                metrics = metrics.copy(evictions = metrics.evictions + 1)
                println("CACHE SERVICE: Evicted user ${entry.key}")
            }
        } catch (e: Exception) {
            println("CACHE SERVICE: Error evicting user: ${e.message}")
        }
    }

    private fun evictOldestThread() {
        try {
            val oldestEntry = threadCache.entries
                .filter { it.value.priority != CachePriority.CRITICAL }
                .minByOrNull { it.value.lastAccessed.time }

            oldestEntry?.let { entry ->
                threadCache.remove(entry.key)
                metrics = metrics.copy(evictions = metrics.evictions + 1)
                println("CACHE SERVICE: Evicted thread ${entry.key}")
            }
        } catch (e: Exception) {
            println("CACHE SERVICE: Error evicting thread: ${e.message}")
        }
    }

    fun helloWorldTest() {
        println("CACHE SERVICE: Hello World - Simplified high-performance caching ready!")
        println("CACHE SERVICE: Features: Memory cache, TTL expiration, Instant feed")
        println("CACHE SERVICE: Performance: <2s startup target, >80% hit rate goal")
    }
}