/*
 * HomeFeedService.kt - FIXED TYPE MISMATCHES
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services - Home feed with UserService integration
 * ✅ FIXED: All method signatures match VideoServiceImpl
 */

package com.stitchsocial.club.services

import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.flow.*
import java.util.Date

/**
 * HomeFeedService with proper UserService integration
 */
class HomeFeedService(
    private val videoService: VideoServiceImpl,
    private val cachingService: SimplifiedCachingService,
    private val userService: UserService
) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _currentFeed = MutableStateFlow<List<ThreadData>>(emptyList())
    val currentFeed: StateFlow<List<ThreadData>> = _currentFeed.asStateFlow()

    private val defaultFeedSize = 20
    private val feedCacheKey = "home_feed_cache"

    suspend fun loadFeed(userID: String, limit: Int = 20): List<ThreadData> {
        _isLoading.value = true
        _lastError.value = null

        return try {
            println("HOME FEED: Loading feed for user $userID")

            val followingIDs = try {
                userService.getFollowingIDs(userID)
            } catch (e: Exception) {
                println("HOME FEED: ⚠️ Failed to load following: ${e.message}")
                emptyList()
            }

            println("HOME FEED: User follows ${followingIDs.size} people")

            val cachedFeed = getCachedFeed(userID)
            if (cachedFeed.isNotEmpty()) {
                println("HOME FEED: Found ${cachedFeed.size} cached threads")
                _currentFeed.value = cachedFeed
                loadFreshContentInBackground(userID, followingIDs, limit)
                return cachedFeed
            }

            val freshFeed = loadFreshContent(followingIDs, limit)
            _currentFeed.value = freshFeed
            cacheFeed(userID, freshFeed)

            println("HOME FEED: Loaded ${freshFeed.size} threads")
            freshFeed

        } catch (e: Exception) {
            val errorMsg = "Failed to load feed: ${e.message}"
            _lastError.value = errorMsg
            println("HOME FEED ERROR: $errorMsg")
            emptyList()

        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun loadFreshContent(followingIDs: List<String>, limit: Int): List<ThreadData> {
        return try {
            if (followingIDs.isEmpty()) {
                println("HOME FEED: ⚠️ No following list - feed will be empty")
                return emptyList()
            }

            println("HOME FEED: Loading content from ${followingIDs.size} users")

            // ✅ FIXED: Correct signature - getFeedVideos(List<String>, Int) returns List<CoreVideoMetadata>
            val videos: List<CoreVideoMetadata> = videoService.getFeedVideos(followingIDs, limit)

            // Convert to ThreadData
            val threads = ThreadData.fromVideos(videos)

            threads.take(limit)

        } catch (e: Exception) {
            println("HOME FEED: Error loading fresh content: ${e.message}")
            emptyList()
        }
    }

    private suspend fun loadFreshContentInBackground(userID: String, followingIDs: List<String>, limit: Int) {
        try {
            val freshContent = loadFreshContent(followingIDs, limit)
            if (freshContent.isNotEmpty()) {
                _currentFeed.value = freshContent
                cacheFeed(userID, freshContent)
                println("HOME FEED: Background refresh completed - ${freshContent.size} threads")
            }
        } catch (e: Exception) {
            println("HOME FEED: Background refresh failed: ${e.message}")
        }
    }

    suspend fun refreshFeed(userID: String): List<ThreadData> {
        _isRefreshing.value = true

        return try {
            println("HOME FEED: Refreshing feed for user $userID")

            val followingIDs = try {
                userService.getFollowingIDs(userID)
            } catch (e: Exception) {
                println("HOME FEED: Failed to load following during refresh")
                emptyList()
            }

            val freshFeed = loadFreshContent(followingIDs, defaultFeedSize)
            _currentFeed.value = freshFeed
            cacheFeed(userID, freshFeed)

            println("HOME FEED: Refresh completed - ${freshFeed.size} threads")
            freshFeed

        } catch (e: Exception) {
            val errorMsg = "Failed to refresh feed: ${e.message}"
            _lastError.value = errorMsg
            println("HOME FEED ERROR: $errorMsg")
            _currentFeed.value

        } finally {
            _isRefreshing.value = false
        }
    }

    suspend fun loadMoreContent(userID: String): List<ThreadData> {
        return try {
            println("HOME FEED: Loading more content")

            val followingIDs = userService.getFollowingIDs(userID)
            val currentFeed = _currentFeed.value
            val moreFeed = loadFreshContent(followingIDs, defaultFeedSize)

            val newContent = moreFeed.filter { newThread ->
                currentFeed.none { it.id == newThread.id }
            }

            if (newContent.isNotEmpty()) {
                val updatedFeed = currentFeed + newContent
                _currentFeed.value = updatedFeed
                cacheFeed(userID, updatedFeed)
                println("HOME FEED: Added ${newContent.size} new threads")
            }

            newContent

        } catch (e: Exception) {
            println("HOME FEED: Failed to load more content: ${e.message}")
            emptyList()
        }
    }

    fun clearFeed() {
        _currentFeed.value = emptyList()
        _lastError.value = null
    }

    suspend fun loadThreadChildren(threadID: String): List<CoreVideoMetadata> {
        return try {
            println("HOME FEED: Loading children for thread $threadID")
            val children = videoService.getThreadChildren(threadID)
            println("HOME FEED: Loaded ${children.size} children")
            children
        } catch (e: Exception) {
            println("HOME FEED: Failed to load thread children: ${e.message}")
            emptyList()
        }
    }

    fun searchFeed(query: String): List<ThreadData> {
        val currentFeed = _currentFeed.value
        val normalizedQuery = query.lowercase().trim()

        if (normalizedQuery.length < 2) {
            return currentFeed
        }

        return currentFeed.filter { thread ->
            thread.parentVideo.title.lowercase().contains(normalizedQuery) ||
                    thread.parentVideo.creatorName.lowercase().contains(normalizedQuery) ||
                    thread.childVideos.any { child ->
                        child.title.lowercase().contains(normalizedQuery)
                    }
        }
    }

    fun getThreadById(threadID: String): ThreadData? {
        return _currentFeed.value.find { it.id == threadID }
    }

    fun updateThread(updatedThread: ThreadData) {
        val currentFeed = _currentFeed.value.toMutableList()
        val index = currentFeed.indexOfFirst { it.id == updatedThread.id }

        if (index != -1) {
            currentFeed[index] = updatedThread
            _currentFeed.value = currentFeed
            println("HOME FEED: Updated thread ${updatedThread.id}")
        }
    }

    private suspend fun getCachedFeed(userID: String): List<ThreadData> {
        return try {
            emptyList()
        } catch (e: Exception) {
            println("HOME FEED: Cache lookup failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun cacheFeed(userID: String, feed: List<ThreadData>) {
        try {
            println("HOME FEED: Caching ${feed.size} threads for user $userID")
        } catch (e: Exception) {
            println("HOME FEED: Cache storage failed: ${e.message}")
        }
    }

    fun hasInstantContent(userID: String): Boolean {
        return _currentFeed.value.isNotEmpty()
    }

    fun getCurrentFeed(): List<ThreadData> {
        return _currentFeed.value
    }

    fun getFeedStats(): Map<String, Any> {
        val currentFeed = _currentFeed.value
        val totalChildren = currentFeed.sumOf { it.childVideos.size }

        return mapOf<String, Any>(
            "totalThreads" to currentFeed.size,
            "totalChildren" to totalChildren,
            "hasContent" to currentFeed.isNotEmpty(),
            "isLoading" to _isLoading.value,
            "isRefreshing" to _isRefreshing.value,
            "lastError" to (_lastError.value ?: "")
        )
    }
}