/*
 * SearchServicesProtocol.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 2: Protocols - Search Service Contracts
 * Dependencies: Layer 1 (Foundation) ONLY
 * Features: User search, video search, suggestions, recent searches
 *
 * BLUEPRINT: Define search contracts for implementation
 */

package com.stitchsocial.club.protocols

import com.stitchsocial.club.foundation.*

/**
 * Search service protocol defining all search operations
 * Layer 2: Can only import Foundation layer
 */
interface SearchServiceProtocol {

    // MARK: - User Search

    /**
     * Search users by username or display name
     */
    suspend fun searchUsers(
        query: String,
        limit: Int = 20,
        excludeUserID: String? = null
    ): List<BasicUserInfo>

    /**
     * Get user search suggestions based on partial input
     */
    suspend fun getUserSearchSuggestions(query: String): List<String>

    /**
     * Search users with advanced filters
     */
    suspend fun searchUsersWithFilters(
        query: String,
        filters: UserSearchFilters,
        limit: Int = 20
    ): List<BasicUserInfo>

    // MARK: - Video Search

    /**
     * Search videos by title, description, or hashtags
     */
    suspend fun searchVideos(
        query: String,
        limit: Int = 50,
        excludeVideoIDs: List<String> = emptyList()
    ): List<BasicVideoInfo>

    /**
     * Get video search suggestions
     */
    suspend fun getVideoSearchSuggestions(query: String): List<String>

    /**
     * Search videos with advanced filters
     */
    suspend fun searchVideosWithFilters(
        query: String,
        filters: VideoSearchFilters,
        limit: Int = 50
    ): List<BasicVideoInfo>

    // MARK: - Trending & Discovery

    /**
     * Get trending search terms
     */
    suspend fun getTrendingSearchTerms(limit: Int = 10): List<String>

    /**
     * Get suggested users for discovery
     */
    suspend fun getSuggestedUsers(
        forUserID: String,
        limit: Int = 20
    ): List<BasicUserInfo>

    /**
     * Get suggested videos for discovery
     */
    suspend fun getSuggestedVideos(
        forUserID: String,
        limit: Int = 30
    ): List<BasicVideoInfo>

    // MARK: - Search History

    /**
     * Add search query to recent searches
     */
    suspend fun addToRecentSearches(query: String, searchType: SearchType)

    /**
     * Get recent search queries
     */
    suspend fun getRecentSearches(limit: Int = 10): List<RecentSearch>

    /**
     * Clear all recent searches
     */
    suspend fun clearRecentSearches()

    /**
     * Remove specific search from recent searches
     */
    suspend fun removeFromRecentSearches(query: String)

    // MARK: - Search Analytics

    /**
     * Track search analytics
     */
    suspend fun trackSearchAnalytics(
        query: String,
        searchType: SearchType,
        resultsCount: Int,
        userID: String? = null
    )
}

// MARK: - Supporting Data Classes

/**
 * User search filters
 */
data class UserSearchFilters(
    val tier: UserTier? = null,
    val isVerified: Boolean? = null,
    val minFollowers: Int? = null,
    val maxFollowers: Int? = null,
    val location: String? = null,
    val hasRecentActivity: Boolean? = null
)

/**
 * Video search filters
 */
data class VideoSearchFilters(
    val contentType: ContentType? = null,
    val temperature: Temperature? = null,
    val minDuration: Double? = null,
    val maxDuration: Double? = null,
    val dateRange: DateRange? = null,
    val minEngagement: Int? = null,
    val hasReplies: Boolean? = null,
    val creatorTier: UserTier? = null
)

/**
 * Date range for filtering
 */
data class DateRange(
    val startDate: java.util.Date,
    val endDate: java.util.Date
)

/**
 * Search type enumeration
 */
enum class SearchType(val displayName: String) {
    USERS("Users"),
    VIDEOS("Videos"),
    HASHTAGS("Hashtags"),
    MIXED("Mixed")
}

/**
 * Recent search entry
 */
data class RecentSearch(
    val query: String,
    val searchType: SearchType,
    val timestamp: java.util.Date,
    val resultsCount: Int
)

/**
 * Search result wrapper
 */
data class SearchResult<T>(
    val results: List<T>,
    val totalCount: Int,
    val query: String,
    val searchType: SearchType,
    val executionTimeMs: Long
)

/**
 * Search suggestion with metadata
 */
data class SearchSuggestion(
    val text: String,
    val type: SearchSuggestionType,
    val popularity: Int = 0,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Search suggestion types
 */
enum class SearchSuggestionType(val displayName: String) {
    USER("User"),
    VIDEO_TITLE("Video Title"),
    HASHTAG("Hashtag"),
    TRENDING("Trending"),
    RECENT("Recent"),
    AUTOCOMPLETE("Autocomplete")
}