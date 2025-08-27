/*
 * DiscoveryViewModel.kt
 * StitchSocial Android
 *
 * Layer 7: ViewModels - Discovery Feed State Management
 * Dependencies: VideoService (Layer 4), AlgorithmicEngine (Layer 5)
 * Features: Discovery feed, search, trending content, algorithmic content discovery
 *
 * BLUEPRINT: DiscoveryPlaceholderViewModel.swift expanded with full discovery features
 */

package com.example.stitchsocialclub.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stitchsocialclub.engagement.AlgorithmicEngine
import com.example.stitchsocialclub.engagement.ContentScore
import com.example.stitchsocialclub.engagement.VideoRankingData
import com.example.stitchsocialclub.foundation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import kotlin.random.Random
import com.example.stitchsocialclub.services.VideoServiceImpl
/**
 * Discovery ViewModel managing discovery feed, search, and trending content
 * Expands Swift DiscoveryPlaceholderViewModel with full discovery functionality
 */
class DiscoveryViewModel(
    private val videoService: VideoServiceImpl
) : ViewModel() {

    // MARK: - Discovery Feed State

    private val _discoveryContent = MutableStateFlow<List<BasicVideoInfo>>(emptyList())
    val discoveryContent: StateFlow<List<BasicVideoInfo>> = _discoveryContent.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _hasMoreContent = MutableStateFlow(true)
    val hasMoreContent: StateFlow<Boolean> = _hasMoreContent.asStateFlow()

    // MARK: - Search State

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<BasicVideoInfo>>(emptyList())
    val searchResults: StateFlow<List<BasicVideoInfo>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    // MARK: - Trending Content State

    private val _trendingVideos = MutableStateFlow<List<BasicVideoInfo>>(emptyList())
    val trendingVideos: StateFlow<List<BasicVideoInfo>> = _trendingVideos.asStateFlow()

    private val _trendingTopics = MutableStateFlow<List<String>>(emptyList())
    val trendingTopics: StateFlow<List<String>> = _trendingTopics.asStateFlow()

    private val _trendingHashtags = MutableStateFlow<List<TrendingHashtag>>(emptyList())
    val trendingHashtags: StateFlow<List<TrendingHashtag>> = _trendingHashtags.asStateFlow()

    private val _viralContent = MutableStateFlow<List<BasicVideoInfo>>(emptyList())
    val viralContent: StateFlow<List<BasicVideoInfo>> = _viralContent.asStateFlow()

    // MARK: - Category and Filter State

    private val _selectedCategory = MutableStateFlow(DiscoveryCategory.FOR_YOU)
    val selectedCategory: StateFlow<DiscoveryCategory> = _selectedCategory.asStateFlow()

    private val _availableCategories = MutableStateFlow(DiscoveryCategory.values().toList())
    val availableCategories: StateFlow<List<DiscoveryCategory>> = _availableCategories.asStateFlow()

    private val _contentFilters = MutableStateFlow<Set<ContentFilter>>(emptySet())
    val contentFilters: StateFlow<Set<ContentFilter>> = _contentFilters.asStateFlow()

    // MARK: - Algorithm State

    private val _algorithmMetrics = MutableStateFlow<Map<String, Double>>(emptyMap())
    val algorithmMetrics: StateFlow<Map<String, Double>> = _algorithmMetrics.asStateFlow()

    private val _personalizedScores = MutableStateFlow<Map<String, ContentScore>>(emptyMap())
    val personalizedScores: StateFlow<Map<String, ContentScore>> = _personalizedScores.asStateFlow()

    private val _discoveryStats = MutableStateFlow(DiscoveryStats())
    val discoveryStats: StateFlow<DiscoveryStats> = _discoveryStats.asStateFlow()

    // MARK: - User Interaction State

    private val _viewedVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val viewedVideoIds: StateFlow<Set<String>> = _viewedVideoIds.asStateFlow()

    private val _likedVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val likedVideoIds: StateFlow<Set<String>> = _likedVideoIds.asStateFlow()

    private val _recommendationFeedback = MutableStateFlow<Map<String, RecommendationFeedback>>(emptyMap())
    val recommendationFeedback: StateFlow<Map<String, RecommendationFeedback>> = _recommendationFeedback.asStateFlow()

    // MARK: - Error and Status State

    private val _lastError = MutableStateFlow<StitchError?>(null)
    val lastError: StateFlow<StitchError?> = _lastError.asStateFlow()

    private val _discoveryMode = MutableStateFlow(DiscoveryMode.ALGORITHM)
    val discoveryMode: StateFlow<DiscoveryMode> = _discoveryMode.asStateFlow()

    // MARK: - Configuration

    private val defaultDiscoverySize = 20
    private val trendingRefreshInterval = 300000L // 5 minutes
    private val maxRecentSearches = 10
    private val algorithmVersion = "1.0"

    // Current user context (TODO: Get from AuthService)
    private var currentUserID: String = "discovery_user_123"
    private var currentUserTier: UserTier = UserTier.VETERAN
    private var followingCreatorIds: List<String> = emptyList()

    init {
        println("🔍 DISCOVERY VM: Initialized with algorithmic discovery")
        setupPeriodicTrendingRefresh()
        loadInitialDiscoveryContent()
    }

    // MARK: - Discovery Feed Loading

    /**
     * Load initial discovery content based on selected category
     * BLUEPRINT: Enhanced DiscoveryPlaceholderViewModel with real algorithms
     */
    fun loadInitialDiscovery() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _lastError.value = null

                println("🔍 DISCOVERY VM: Loading initial discovery content")

                when (_selectedCategory.value) {
                    DiscoveryCategory.FOR_YOU -> loadPersonalizedFeed()
                    DiscoveryCategory.TRENDING -> loadTrendingContent()
                    DiscoveryCategory.VIRAL -> loadViralContent()
                    DiscoveryCategory.FRESH -> loadFreshContent()
                    DiscoveryCategory.QUALITY -> loadQualityContent()
                }

                // Load trending data in parallel
                val trendingJob = async { loadTrendingTopics() }
                val hashtagsJob = async { loadTrendingHashtags() }

                trendingJob.await()
                hashtagsJob.await()

                updateDiscoveryStats()

                println("✅ DISCOVERY VM: Initial discovery content loaded")

            } catch (error: Exception) {
                handleError("Failed to load discovery content: ${error.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Refresh discovery feed
     */
    fun refreshDiscovery() {
        viewModelScope.launch {
            try {
                _isRefreshing.value = true
                _lastError.value = null

                println("🔄 DISCOVERY VM: Refreshing discovery feed")

                // Clear current content
                _discoveryContent.value = emptyList()
                _hasMoreContent.value = true

                // Reload based on current category
                loadInitialDiscovery()

                println("✅ DISCOVERY VM: Discovery feed refreshed")

            } catch (error: Exception) {
                handleError("Failed to refresh discovery: ${error.message}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Load more discovery content for pagination
     */
    fun loadMoreDiscovery() {
        if (!_hasMoreContent.value || _isLoading.value) return

        viewModelScope.launch {
            try {
                println("📄 DISCOVERY VM: Loading more discovery content")

                val currentContent = _discoveryContent.value
                val additionalContent = generateAlgorithmicContent(
                    excludeIds = currentContent.map { it.id }.toSet(),
                    limit = defaultDiscoverySize
                )

                if (additionalContent.isNotEmpty()) {
                    _discoveryContent.value = currentContent + additionalContent
                    println("✅ DISCOVERY VM: Loaded ${additionalContent.size} more videos")
                } else {
                    _hasMoreContent.value = false
                    println("🔍 DISCOVERY VM: No more content available")
                }

            } catch (error: Exception) {
                handleError("Failed to load more content: ${error.message}")
            }
        }
    }

    // MARK: - Category Management

    /**
     * Switch discovery category
     */
    fun selectCategory(category: DiscoveryCategory) {
        if (category != _selectedCategory.value) {
            _selectedCategory.value = category
            _discoveryContent.value = emptyList()
            _hasMoreContent.value = true

            println("📂 DISCOVERY VM: Selected category: ${category.displayName}")
            loadInitialDiscovery()
        }
    }

    // MARK: - Search Functionality

    /**
     * Perform search with query
     */
    fun search(query: String) {
        viewModelScope.launch {
            try {
                _searchQuery.value = query
                _isSearching.value = true

                if (query.isBlank()) {
                    _searchResults.value = emptyList()
                    return@launch
                }

                println("🔍 DISCOVERY VM: Searching for: '$query'")

                // Add to recent searches
                addToRecentSearches(query)

                // Perform search
                val results = performVideoSearch(query)
                _searchResults.value = results

                // Generate search suggestions
                updateSearchSuggestions(query)

                println("✅ DISCOVERY VM: Found ${results.size} search results")

            } catch (error: Exception) {
                handleError("Search failed: ${error.message}")
            } finally {
                _isSearching.value = false
            }
        }
    }

    /**
     * Clear search results
     */
    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searchSuggestions.value = emptyList()
    }

    /**
     * Add search query to recent searches
     */
    private fun addToRecentSearches(query: String) {
        val currentSearches = _recentSearches.value.toMutableList()
        currentSearches.remove(query) // Remove if already exists
        currentSearches.add(0, query) // Add to front

        // Limit to max recent searches
        if (currentSearches.size > maxRecentSearches) {
            currentSearches.removeAt(currentSearches.size - 1)
        }

        _recentSearches.value = currentSearches
    }

    // MARK: - Content Loading Methods

    /**
     * Load personalized "For You" feed using algorithms
     */
    private suspend fun loadPersonalizedFeed() {
        val personalizedContent = generateAlgorithmicContent(
            userTier = currentUserTier,
            followingIds = followingCreatorIds,
            limit = defaultDiscoverySize
        )
        _discoveryContent.value = personalizedContent
    }

    /**
     * Load trending content
     */
    private suspend fun loadTrendingContent() {
        val trendingContent = generateTrendingContent()
        _discoveryContent.value = trendingContent
        _trendingVideos.value = trendingContent
    }

    /**
     * Load viral content
     */
    private suspend fun loadViralContent() {
        val viralContent = generateViralContent()
        _discoveryContent.value = viralContent
        _viralContent.value = viralContent
    }

    /**
     * Load fresh content (recent uploads)
     */
    private suspend fun loadFreshContent() {
        val freshContent = generateFreshContent()
        _discoveryContent.value = freshContent
    }

    /**
     * Load quality content (high engagement)
     */
    private suspend fun loadQualityContent() {
        val qualityContent = generateQualityContent()
        _discoveryContent.value = qualityContent
    }

    // MARK: - Algorithm Integration

    /**
     * Generate algorithmic content using AlgorithmicEngine
     */
    private suspend fun generateAlgorithmicContent(
        userTier: UserTier = currentUserTier,
        followingIds: List<String> = followingCreatorIds,
        excludeIds: Set<String> = emptySet(),
        limit: Int = defaultDiscoverySize
    ): List<BasicVideoInfo> = withContext(Dispatchers.Default) {

        try {
            // Get candidate videos from VideoService
            val candidateVideos = generateMockContent("algorithmic", limit * 3) // Generate mock videos for now

            // Convert to ranking data
            val rankingData = candidateVideos.map { video ->
                VideoRankingData(
                    id = video.id,
                    creatorId = "creator_${video.id}", // TODO: Get real creator ID
                    creatorTier = UserTier.VETERAN, // TODO: Get real creator tier
                    hypeCount = Random.nextInt(0, 500),
                    coolCount = Random.nextInt(0, 50),
                    viewCount = Random.nextInt(100, 10000),
                    replyCount = Random.nextInt(0, 100),
                    shareCount = Random.nextInt(0, 200),
                    temperature = listOf("hot", "warm", "cool", "blazing").random(),
                    ageInHours = Random.nextDouble(0.1, 168.0), // 0.1 to 168 hours
                    conversationDepth = 0, // Discovery shows only threads
                    qualityScore = Random.nextDouble(0.3, 1.0),
                    engagementRatio = Random.nextDouble(0.1, 0.9),
                    velocityScore = Random.nextDouble(0.0, 100.0),
                    isPromoted = Random.nextBoolean()
                )
            }

            // Filter out excluded videos
            val filteredData = rankingData.filter { it.id !in excludeIds }

            // Calculate content scores using AlgorithmicEngine
            val scoredContent = filteredData.map { data ->
                val score = AlgorithmicEngine.calculateContentScore(
                    video = data,
                    userTier = userTier,
                    recentViewedIds = _viewedVideoIds.value.toList(),
                    followingCreatorIds = followingIds
                )
                data to score
            }

            // Store personalized scores
            val scoresMap = scoredContent.associate { (data, score) -> data.id to score }
            _personalizedScores.value = scoresMap

            // Sort by final score and take top results
            val topContent = scoredContent
                .sortedByDescending { (_, score) -> score.finalScore }
                .take(limit)
                .map { (data, _) ->
                    // Convert back to BasicVideoInfo
                    candidateVideos.first { it.id == data.id }
                }

            println("🤖 DISCOVERY VM: Generated ${topContent.size} algorithmic recommendations")
            return@withContext topContent

        } catch (error: Exception) {
            println("❌ DISCOVERY VM: Algorithm generation failed - ${error.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Generate trending content
     */
    private suspend fun generateTrendingContent(): List<BasicVideoInfo> {
        // TODO: Implement real trending algorithm
        return generateMockContent("trending", defaultDiscoverySize)
    }

    /**
     * Generate viral content
     */
    private suspend fun generateViralContent(): List<BasicVideoInfo> {
        // TODO: Implement real viral detection algorithm
        return generateMockContent("viral", defaultDiscoverySize)
    }

    /**
     * Generate fresh content
     */
    private suspend fun generateFreshContent(): List<BasicVideoInfo> {
        // TODO: Implement fresh content algorithm (recent uploads)
        return generateMockContent("fresh", defaultDiscoverySize)
    }

    /**
     * Generate quality content
     */
    private suspend fun generateQualityContent(): List<BasicVideoInfo> {
        // TODO: Implement quality scoring algorithm
        return generateMockContent("quality", defaultDiscoverySize)
    }

    // MARK: - Trending Data Management

    /**
     * Load trending topics
     */
    private suspend fun loadTrendingTopics() {
        val topics = generateTrendingTopics()
        _trendingTopics.value = topics
    }

    /**
     * Load trending hashtags
     */
    private suspend fun loadTrendingHashtags() {
        val hashtags = generateTrendingHashtags()
        _trendingHashtags.value = hashtags
    }

    /**
     * Setup periodic trending refresh
     */
    private fun setupPeriodicTrendingRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(trendingRefreshInterval)
                try {
                    loadTrendingTopics()
                    loadTrendingHashtags()
                    println("🔄 DISCOVERY VM: Trending data refreshed")
                } catch (error: Exception) {
                    println("⚠️ DISCOVERY VM: Trending refresh failed - ${error.message}")
                }
            }
        }
    }

    // MARK: - User Interaction Tracking

    /**
     * Track video view
     */
    fun trackVideoView(videoId: String) {
        val viewedIds = _viewedVideoIds.value.toMutableSet()
        viewedIds.add(videoId)
        _viewedVideoIds.value = viewedIds

        // Update algorithm metrics
        updateAlgorithmMetrics("video_view", videoId)
    }

    /**
     * Track video like
     */
    fun trackVideoLike(videoId: String) {
        val likedIds = _likedVideoIds.value.toMutableSet()
        likedIds.add(videoId)
        _likedVideoIds.value = likedIds

        // Positive feedback for recommendation
        provideFeedback(videoId, RecommendationFeedback.LIKE)
    }

    /**
     * Provide recommendation feedback
     */
    fun provideFeedback(videoId: String, feedback: RecommendationFeedback) {
        val feedbackMap = _recommendationFeedback.value.toMutableMap()
        feedbackMap[videoId] = feedback
        _recommendationFeedback.value = feedbackMap

        println("📊 DISCOVERY VM: Feedback for $videoId: ${feedback.name}")
    }

    // MARK: - Helper Methods

    /**
     * Perform video search
     */
    private suspend fun performVideoSearch(query: String): List<BasicVideoInfo> {
        return try {
            // TODO: Implement real search when VideoService has searchVideos method
            // videoService.searchVideos(query, limit = 50)

            // For now, generate mock search results
            generateMockSearchResults(query, 20)
        } catch (error: Exception) {
            println("❌ DISCOVERY VM: Search failed - ${error.message}")
            emptyList()
        }
    }

    /**
     * Update search suggestions
     */
    private fun updateSearchSuggestions(query: String) {
        // Generate suggestions based on query
        val suggestions = generateSearchSuggestions(query)
        _searchSuggestions.value = suggestions
    }

    /**
     * Generate trending topics (placeholder)
     */
    private fun generateTrendingTopics(): List<String> {
        return listOf(
            "AI Art", "Music Production", "Dance Challenge", "Tech Reviews",
            "Cooking Tips", "Fitness Motivation", "Travel Vlogs", "Comedy Skits"
        ).shuffled().take(6)
    }

    /**
     * Generate trending hashtags (placeholder)
     */
    private fun generateTrendingHashtags(): List<TrendingHashtag> {
        val hashtags = listOf("#viral", "#trending", "#fyp", "#discover", "#hot", "#new")
        return hashtags.mapIndexed { index, tag ->
            TrendingHashtag(
                tag = tag,
                videoCount = Random.nextInt(1000, 100000),
                rank = index + 1,
                growthRate = Random.nextDouble(0.1, 5.0)
            )
        }
    }

    /**
     * Generate search suggestions
     */
    private fun generateSearchSuggestions(query: String): List<String> {
        val baseSuggestions = listOf(
            "${query} tutorial", "${query} tips", "${query} review",
            "${query} challenge", "${query} compilation"
        )
        return baseSuggestions.take(3)
    }

    /**
     * Generate mock content for categories
     */
    private fun generateMockContent(category: String, limit: Int): List<BasicVideoInfo> {
        return (1..limit).map { index ->
            BasicVideoInfo(
                id = "${category}_video_$index",
                title = "$category Video $index",
                videoURL = "https://example.com/videos/${category}_$index.mp4",
                thumbnailURL = "https://example.com/thumbnails/${category}_$index.jpg",
                duration = Random.nextDouble(15.0, 180.0),
                createdAt = Date(System.currentTimeMillis() - Random.nextLong(0, 7 * 24 * 60 * 60 * 1000)),
                contentType = ContentType.THREAD,
                temperature = Temperature.WARM
            )
        }
    }

    /**
     * Generate mock search results
     */
    private fun generateMockSearchResults(query: String, limit: Int): List<BasicVideoInfo> {
        return (1..limit).map { index ->
            BasicVideoInfo(
                id = "search_${query}_$index",
                title = "$query Search Result $index",
                videoURL = "https://example.com/videos/search_${query}_$index.mp4",
                thumbnailURL = "https://example.com/thumbnails/search_${query}_$index.jpg",
                duration = Random.nextDouble(15.0, 180.0),
                createdAt = Date(System.currentTimeMillis() - Random.nextLong(0, 7 * 24 * 60 * 60 * 1000)),
                contentType = ContentType.THREAD,
                temperature = Temperature.WARM
            )
        }
    }

    /**
     * Update algorithm metrics
     */
    private fun updateAlgorithmMetrics(action: String, videoId: String) {
        val metrics = _algorithmMetrics.value.toMutableMap()
        val key = "${action}_count"
        metrics[key] = (metrics[key] ?: 0.0) + 1.0
        _algorithmMetrics.value = metrics
    }

    /**
     * Update discovery statistics
     */
    private fun updateDiscoveryStats() {
        val content = _discoveryContent.value
        val viewed = _viewedVideoIds.value
        val liked = _likedVideoIds.value

        val stats = DiscoveryStats(
            totalContentShown = content.size,
            viewedCount = viewed.size,
            likedCount = liked.size,
            searchCount = _recentSearches.value.size,
            categoryCount = DiscoveryCategory.values().size,
            algorithmVersion = algorithmVersion
        )

        _discoveryStats.value = stats
    }

    /**
     * Handle error state
     */
    private fun handleError(message: String) {
        _lastError.value = StitchError.NetworkError(message)
        println("❌ DISCOVERY VM: Error - $message")
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _lastError.value = null
    }

    /**
     * Load initial content when ViewModel is created
     */
    private fun loadInitialDiscoveryContent() {
        loadInitialDiscovery()
    }

    // MARK: - Cleanup

    override fun onCleared() {
        super.onCleared()
        println("🧹 DISCOVERY VM: Cleaning up resources")
    }
}

// MARK: - Supporting Types

/**
 * Discovery categories for content organization
 */
enum class DiscoveryCategory(val displayName: String) {
    FOR_YOU("For You"),
    TRENDING("Trending"),
    VIRAL("Viral"),
    FRESH("Fresh"),
    QUALITY("Quality")
}

/**
 * Content filters for discovery
 */
enum class ContentFilter(val displayName: String) {
    RECENT("Recent"),
    POPULAR("Popular"),
    FOLLOWING("Following"),
    LOCATION("Location"),
    LANGUAGE("Language")
}

/**
 * Discovery modes
 */
enum class DiscoveryMode(val displayName: String) {
    ALGORITHM("Algorithm"),
    MANUAL("Manual"),
    HYBRID("Hybrid")
}

/**
 * Recommendation feedback types
 */
enum class RecommendationFeedback {
    LIKE,
    DISLIKE,
    NOT_INTERESTED,
    INAPPROPRIATE,
    SEEN_BEFORE
}

/**
 * Trending hashtag data
 */
data class TrendingHashtag(
    val tag: String,
    val videoCount: Int,
    val rank: Int,
    val growthRate: Double
)

/**
 * Discovery statistics
 */
data class DiscoveryStats(
    val totalContentShown: Int = 0,
    val viewedCount: Int = 0,
    val likedCount: Int = 0,
    val searchCount: Int = 0,
    val categoryCount: Int = 0,
    val algorithmVersion: String = "1.0"
)