/*
 * DiscoveryViewModel.kt - FIXED TO USE HYBRIDHOMEFEEDSERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 7: ViewModels - Discovery with Real Firebase Data
 * Dependencies: HybridHomeFeedService, CoreVideoMetadata from foundation
 * Features: Real Firebase content using existing discovery methods
 *
 * ✅ FIXED: Uses HybridHomeFeedService instead of VideoServiceImpl
 * ✅ FIXED: Uses CoreVideoMetadata instead of BasicVideoInfo
 * ✅ FIXED: All method calls now exist and work
 */
package com.stitchsocial.club.viewsmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Discovery categories matching Swift
 */
enum class DiscoveryCategory(val displayName: String) {
    FOR_YOU("For You"),
    TRENDING("Trending"),
    VIRAL("Viral"),
    FRESH("Fresh"),
    QUALITY("Quality")
}

/**
 * DiscoveryViewModel using HybridHomeFeedService - FIXED
 */
class DiscoveryViewModel(
    private val feedService: HybridHomeFeedService  // FIXED: Use HybridHomeFeedService
) : ViewModel() {

    // MARK: - Discovery Feed State
    private val _discoveryContent = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())  // FIXED: CoreVideoMetadata
    val discoveryContent: StateFlow<List<CoreVideoMetadata>> = _discoveryContent.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedCategory = MutableStateFlow(DiscoveryCategory.FOR_YOU)
    val selectedCategory: StateFlow<DiscoveryCategory> = _selectedCategory.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // MARK: - Discovery Loading

    /**
     * Load initial discovery content from Firebase
     */
    fun loadInitialDiscovery() {
        viewModelScope.launch {
            if (_isLoading.value) return@launch

            _isLoading.value = true
            _error.value = null

            try {
                println("DISCOVERY VM: Loading real content for ${_selectedCategory.value.displayName}")

                val content = loadContentForCategory(_selectedCategory.value)
                _discoveryContent.value = content

                println("DISCOVERY VM: ✅ Loaded ${content.size} real videos from Firebase")

                if (content.isEmpty()) {
                    _error.value = "No videos found for ${_selectedCategory.value.displayName}"
                    println("DISCOVERY VM: ⚠️ No content found - check Firebase database")
                }

            } catch (e: Exception) {
                _error.value = "Failed to load content: ${e.message}"
                println("DISCOVERY VM: ❌ Error loading content: ${e.message}")
                _discoveryContent.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Select discovery category and reload content
     */
    fun selectCategory(category: DiscoveryCategory) {
        if (category != _selectedCategory.value) {
            _selectedCategory.value = category
            _discoveryContent.value = emptyList()
            _error.value = null

            println("DISCOVERY VM: Selected category: ${category.displayName}")
            loadInitialDiscovery()
        }
    }

    /**
     * Retry loading content
     */
    fun retryLoading() {
        loadInitialDiscovery()
    }

    /**
     * Track video view for analytics
     */
    fun trackVideoView(videoID: String) {
        println("DISCOVERY VM: 📊 Video viewed: $videoID")
        // TODO: Track analytics to Firebase
    }

    // MARK: - Real Firebase Content Loading

    /**
     * Load content based on category from real Firebase data
     * FIXED: Uses HybridHomeFeedService methods that actually exist
     */
    private suspend fun loadContentForCategory(category: DiscoveryCategory): List<CoreVideoMetadata> {
        return when (category) {
            DiscoveryCategory.FOR_YOU -> loadForYouContent()
            DiscoveryCategory.TRENDING -> loadTrendingContent()
            DiscoveryCategory.VIRAL -> loadViralContent()
            DiscoveryCategory.FRESH -> loadFreshContent()
            DiscoveryCategory.QUALITY -> loadQualityContent()
        }
    }

    /**
     * Load For You content from Firebase - FIXED
     */
    private suspend fun loadForYouContent(): List<CoreVideoMetadata> {
        return try {
            println("DISCOVERY VM: 🎯 Loading For You content from Firebase")

            // FIXED: Use HybridHomeFeedService method that exists
            val videos = feedService.getAllDiscoveryVideos(20)

            println("DISCOVERY VM: ✅ Found ${videos.size} For You videos")
            return videos

        } catch (e: Exception) {
            println("DISCOVERY VM: ❌ For You content failed: ${e.message}")
            throw e
        }
    }

    /**
     * Load trending content from Firebase - FIXED
     */
    private suspend fun loadTrendingContent(): List<CoreVideoMetadata> {
        return try {
            println("DISCOVERY VM: 🔥 Loading trending content from Firebase")

            // FIXED: Use HybridHomeFeedService method that exists
            val videos = feedService.getTrendingVideos(20)

            println("DISCOVERY VM: ✅ Found ${videos.size} trending videos")
            return videos

        } catch (e: Exception) {
            println("DISCOVERY VM: ❌ Trending content failed: ${e.message}")
            throw e
        }
    }

    /**
     * Load viral content from Firebase - FIXED
     */
    private suspend fun loadViralContent(): List<CoreVideoMetadata> {
        return try {
            println("DISCOVERY VM: 🚀 Loading viral content from Firebase")

            // FIXED: Use HybridHomeFeedService method that exists
            val videos = feedService.getViralVideos(20)

            println("DISCOVERY VM: ✅ Found ${videos.size} viral videos")
            return videos

        } catch (e: Exception) {
            println("DISCOVERY VM: ❌ Viral content failed: ${e.message}")
            throw e
        }
    }

    /**
     * Load fresh content from Firebase - FIXED
     */
    private suspend fun loadFreshContent(): List<CoreVideoMetadata> {
        return try {
            println("DISCOVERY VM: 🌱 Loading fresh content from Firebase")

            // FIXED: Use HybridHomeFeedService method that exists
            val videos = feedService.getFreshVideos(20)

            println("DISCOVERY VM: ✅ Found ${videos.size} fresh videos")
            return videos

        } catch (e: Exception) {
            println("DISCOVERY VM: ❌ Fresh content failed: ${e.message}")
            throw e
        }
    }

    /**
     * Load quality content from Firebase - FIXED
     */
    private suspend fun loadQualityContent(): List<CoreVideoMetadata> {
        return try {
            println("DISCOVERY VM: ⭐ Loading quality content from Firebase")

            // FIXED: Use HybridHomeFeedService method that exists
            val videos = feedService.getQualityVideos(20)

            println("DISCOVERY VM: ✅ Found ${videos.size} quality videos")
            return videos

        } catch (e: Exception) {
            println("DISCOVERY VM: ❌ Quality content failed: ${e.message}")
            throw e
        }
    }

    override fun onCleared() {
        super.onCleared()
        println("DISCOVERY VM: 🧹 Cleaning up resources")
    }
}