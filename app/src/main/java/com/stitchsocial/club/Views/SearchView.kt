/*
 * SearchView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views — Matches iOS SearchTab.swift 1:1
 * Features: All/Users/Videos tabs, TrendingHashtag chips with velocity,
 *           recentUsers, hero empty state, HashtagView navigation,
 *           ModernUserRow with press animation
 *
 * CACHING: suggestedUsers + trendingHashtags cached via SearchCache (TTL 10 min).
 *          Avoids repeated getSuggestedUsers Firestore fan-out on every open.
 *          Cache is cleared in onCleared() to prevent stale follow states.
 */

package com.stitchsocial.club

import com.stitchsocial.club.ui.theme.AppTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.SearchService
import com.stitchsocial.club.services.TrendingHashtag
import com.stitchsocial.club.FollowManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.stitchsocial.club.BuildConfig

// MARK: - Search Tab (matches iOS: All / Users / Videos)

enum class SearchTab(val title: String) {
    ALL("All"),
    USERS("Users"),
    VIDEOS("Videos")
}

// MARK: - Search Cache (TTL-based, prevents repeated fan-out reads)

private object SearchCache {
    private const val TTL_MS = 10 * 60 * 1000L // 10 min — matches hashtag service cadence

    data class Entry<T>(val data: T, val timestamp: Long = System.currentTimeMillis()) {
        fun isValid() = System.currentTimeMillis() - timestamp < TTL_MS
    }

    var suggestedUsers: Entry<List<BasicUserInfo>>? = null
    var trendingHashtags: Entry<List<TrendingHashtag>>? = null
    var recentUserIDs: MutableList<String> = mutableListOf()

    fun clear() {
        suggestedUsers = null
        trendingHashtags = null
    }
}

// MARK: - Search ViewModel

class SearchViewModel(
    private val searchService: SearchService,
    val followManager: FollowManager
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _userResults = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val userResults: StateFlow<List<BasicUserInfo>> = _userResults.asStateFlow()

    private val _videoResults = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val videoResults: StateFlow<List<CoreVideoMetadata>> = _videoResults.asStateFlow()

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private val _suggestedUsers = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val suggestedUsers: StateFlow<List<BasicUserInfo>> = _suggestedUsers.asStateFlow()

    private val _trendingHashtags = MutableStateFlow<List<TrendingHashtag>>(emptyList())
    val trendingHashtags: StateFlow<List<TrendingHashtag>> = _trendingHashtags.asStateFlow()

    private val _recentUsers = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val recentUsers: StateFlow<List<BasicUserInfo>> = _recentUsers.asStateFlow()

    private val _isLoadingSuggestions = MutableStateFlow(false)
    val isLoadingSuggestions: StateFlow<Boolean> = _isLoadingSuggestions.asStateFlow()

    private val _isLoadingHashtags = MutableStateFlow(false)
    val isLoadingHashtags: StateFlow<Boolean> = _isLoadingHashtags.asStateFlow()

    private val _selectedTab = MutableStateFlow(SearchTab.ALL)
    val selectedTab: StateFlow<SearchTab> = _selectedTab.asStateFlow()

    init {
        // Debounced auto-search
        viewModelScope.launch {
            searchQuery.drop(1).collectLatest { query ->
                delay(300)
                if (query.isNotBlank()) performSearch(query) else clearResults()
            }
        }
        loadSuggestedUsers()
        loadTrendingHashtags()
    }

    fun updateSearchQuery(query: String) { _searchQuery.value = query }
    fun selectTab(tab: SearchTab) { _selectedTab.value = tab }

    fun addRecentUser(user: BasicUserInfo) {
        val ids = SearchCache.recentUserIDs
        ids.remove(user.id)
        ids.add(0, user.id)
        if (ids.size > 10) ids.subList(10, ids.size).clear()
        refreshRecentUsers()
    }

    fun clearRecentSearches() {
        SearchCache.recentUserIDs.clear()
        _recentUsers.value = emptyList()
    }

    private fun refreshRecentUsers() {
        val ids = SearchCache.recentUserIDs
        _recentUsers.value = _suggestedUsers.value
            .filter { it.id in ids }
            .sortedBy { ids.indexOf(it.id) }
    }

    fun toggleFollow(userID: String) { followManager.toggleFollow(userID) }
    fun isFollowing(userID: String) = followManager.isFollowing(userID)
    fun isFollowLoading(userID: String) = followManager.isLoading(userID)
    fun getFollowButtonText(userID: String) = followManager.getFollowButtonText(userID)

    private fun performSearch(query: String) {
        viewModelScope.launch {
            _isSearching.value = true
            _hasSearched.value = true
            try {
                val users = searchService.searchUsers(query, limit = 30)
                val videos = searchService.searchVideos(query, limit = 20)
                _userResults.value = users
                _videoResults.value = videos
                followManager.loadFollowStatesForUsers(users)
                if (BuildConfig.DEBUG) { println("🔍 SEARCH: query='$query' users=${users.size} videos=${videos.size}") }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("❌ SEARCH: Failed: ${e.message}") }
                clearResults()
            } finally {
                _isSearching.value = false
            }
        }
    }

    private fun loadSuggestedUsers() {
        // Cache hit — no Firestore read
        SearchCache.suggestedUsers?.takeIf { it.isValid() }?.let { cached ->
            _suggestedUsers.value = cached.data
            refreshRecentUsers()
            viewModelScope.launch { followManager.loadFollowStatesForUsers(cached.data) }
            return
        }
        viewModelScope.launch {
            _isLoadingSuggestions.value = true
            try {
                val users = searchService.getSuggestedUsers(limit = 20)
                _suggestedUsers.value = users
                SearchCache.suggestedUsers = SearchCache.Entry(users)
                refreshRecentUsers()
                followManager.loadFollowStatesForUsers(users)
                if (BuildConfig.DEBUG) { println("✅ SEARCH: Loaded ${users.size} personalized suggestions") }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("❌ SEARCH: Suggestions failed: ${e.message}") }
            } finally {
                _isLoadingSuggestions.value = false
            }
        }
    }

    private fun loadTrendingHashtags() {
        SearchCache.trendingHashtags?.takeIf { it.isValid() }?.let { cached ->
            _trendingHashtags.value = cached.data
            return
        }
        viewModelScope.launch {
            _isLoadingHashtags.value = true
            try {
                val hashtags = searchService.getTrendingHashtagModels(limit = 15)
                _trendingHashtags.value = hashtags
                SearchCache.trendingHashtags = SearchCache.Entry(hashtags)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("❌ SEARCH: Trending hashtags failed: ${e.message}") }
            } finally {
                _isLoadingHashtags.value = false
            }
        }
    }

    fun clearResults() {
        _userResults.value = emptyList()
        _videoResults.value = emptyList()
        _hasSearched.value = false
    }

    override fun onCleared() {
        super.onCleared()
        // Don't evict cache on ViewModel clear — keep for session
        // but invalidate follow states that may be stale
    }
}

// MARK: - Main Search View

@Composable
fun SearchView(
    followManager: FollowManager,
    onUserTapped: (BasicUserInfo) -> Unit = {},
    onVideoTapped: (CoreVideoMetadata) -> Unit = {},
    onHashtagTapped: (TrendingHashtag) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val viewModel = remember {
        SearchViewModel(SearchService(), followManager)
    }

    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val userResults by viewModel.userResults.collectAsState()
    val videoResults by viewModel.videoResults.collectAsState()
    val hasSearched by viewModel.hasSearched.collectAsState()
    val suggestedUsers by viewModel.suggestedUsers.collectAsState()
    val recentUsers by viewModel.recentUsers.collectAsState()
    val trendingHashtags by viewModel.trendingHashtags.collectAsState()
    val isLoadingSuggestions by viewModel.isLoadingSuggestions.collectAsState()
    val isLoadingHashtags by viewModel.isLoadingHashtags.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val followingStates by followManager.followingStates.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Was a hardcoded black gradient while the TEXT used theme tokens.
            // In light mode textPrimary is #1A1A1A, so the screen rendered
            // near-black text on a black background — unreadable, and the reason
            // search looked broken rather than merely off-brand.
            .background(AppTheme.colors.bg)
    ) {
        Column(Modifier.fillMaxSize()) {

            // Header — matches iOS xmark + centered "Search"
            SearchHeader(onDismiss = onDismiss)

            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChanged = viewModel::updateSearchQuery,
                isSearching = isSearching
            )

            // Tab selector (only when results exist)
            if (hasSearched) {
                TabSelector(
                    selectedTab = selectedTab,
                    onTabSelected = viewModel::selectTab
                )
            }

            // Content
            if (searchQuery.isEmpty() && !hasSearched) {
                EmptyState(
                    suggestedUsers = suggestedUsers,
                    recentUsers = recentUsers,
                    trendingHashtags = trendingHashtags,
                    isLoadingSuggestions = isLoadingSuggestions,
                    isLoadingHashtags = isLoadingHashtags,
                    followingStates = followingStates,
                    viewModel = viewModel,
                    onUserTapped = { user ->
                        viewModel.addRecentUser(user)
                        onUserTapped(user)
                    },
                    onHashtagTapped = onHashtagTapped
                )
            } else {
                ResultsSection(
                    isSearching = isSearching,
                    selectedTab = selectedTab,
                    userResults = userResults,
                    videoResults = videoResults,
                    hasSearched = hasSearched,
                    followingStates = followingStates,
                    viewModel = viewModel,
                    onUserTapped = { user ->
                        viewModel.addRecentUser(user)
                        onUserTapped(user)
                    },
                    onVideoTapped = onVideoTapped
                )
            }
        }
    }
}

// MARK: - Search Header

@Composable
private fun SearchHeader(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Search",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.textPrimary
        )
        // Close button — used to be Icons.Default.Search (the magnifying glass)
        // which was unrecognizable as a dismiss control. Now an actual X.
        Box(modifier = Modifier.align(Alignment.CenterStart)) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = AppTheme.colors.textPrimary,
                    modifier = Modifier
                        .size(36.dp)
                        .background(AppTheme.colors.hairline, CircleShape)
                        .padding(8.dp)
                )
            }
        }
    }
}

// MARK: - Search Bar (matches iOS glass pill style)

@Composable
private fun SearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    isSearching: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(
                color = AppTheme.colors.surface,
                shape = RoundedCornerShape(12.dp)
            )
            .border(1.dp, AppTheme.colors.hairline, RoundedCornerShape(12.dp))
            // 14dp vertical on top of the field's own height made the bar a
            // slab. 10 keeps the tap target while letting results start higher.
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search users...", color = AppTheme.colors.textSecondary, fontSize = 16.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 16.sp),
                singleLine = true,
                cursorBrush = SolidColor(Color.Cyan),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (isSearching) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Cyan, strokeWidth = 2.dp)
        } else if (query.isNotEmpty()) {
            TextButton(onClick = { onQueryChanged("") }, contentPadding = PaddingValues(0.dp)) {
                Text("✕", color = AppTheme.colors.textSecondary, fontSize = 18.sp)
            }
        }
    }
}

// MARK: - Tab Selector (matches iOS cyan underline style)

@Composable
private fun TabSelector(
    selectedTab: SearchTab,
    onTabSelected: (SearchTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        SearchTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(tab) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = tab.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) Color.Cyan else AppTheme.colors.textSecondary
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (isSelected) Color.Cyan else Color.Transparent)
                )
            }
        }
    }
}

// MARK: - Results Section

@Composable
private fun ResultsSection(
    isSearching: Boolean,
    selectedTab: SearchTab,
    userResults: List<BasicUserInfo>,
    videoResults: List<CoreVideoMetadata>,
    hasSearched: Boolean,
    followingStates: Map<String, Boolean>,
    viewModel: SearchViewModel,
    onUserTapped: (BasicUserInfo) -> Unit,
    onVideoTapped: (CoreVideoMetadata) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp)
    ) {
        if (isSearching) {
            item { LoadingView(Modifier.fillParentMaxSize()) }
            return@LazyColumn
        }

        val showUsers = selectedTab == SearchTab.ALL || selectedTab == SearchTab.USERS
        val showVideos = selectedTab == SearchTab.ALL || selectedTab == SearchTab.VIDEOS

        if (showUsers) {
            items(userResults, key = { it.id }) { user ->
                ModernUserRow(
                    user = user,
                    isFollowing = followingStates[user.id] ?: false,
                    isFollowLoading = viewModel.isFollowLoading(user.id),
                    followButtonText = viewModel.getFollowButtonText(user.id),
                    onTap = { onUserTapped(user) },
                    onFollowToggle = { viewModel.toggleFollow(user.id) }
                )
            }
        }

        if (showVideos) {
            items(videoResults, key = { it.id }) { video ->
                VideoSearchCard(video = video, onTap = { onVideoTapped(video) })
            }
        }

        if (hasSearched && userResults.isEmpty() && videoResults.isEmpty()) {
            item { NoResultsView(Modifier.fillParentMaxSize()) }
        }
    }
}

// MARK: - Empty State (matches iOS hero + recent + trending + suggestions)

@Composable
private fun EmptyState(
    suggestedUsers: List<BasicUserInfo>,
    recentUsers: List<BasicUserInfo>,
    trendingHashtags: List<TrendingHashtag>,
    isLoadingSuggestions: Boolean,
    isLoadingHashtags: Boolean,
    followingStates: Map<String, Boolean>,
    viewModel: SearchViewModel,
    onUserTapped: (BasicUserInfo) -> Unit,
    onHashtagTapped: (TrendingHashtag) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        // Hero
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color.Cyan.copy(alpha = 0.2f), Color(0xFF9B59B6).copy(alpha = 0.2f))
                                ),
                                CircleShape
                            )
                    )
                    Text("👥", fontSize = 56.sp)
                }
                Spacer(Modifier.height(16.dp))
                Text("Discover Amazing Creators", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text("Find people to follow and connect with", fontSize = 15.sp, color = AppTheme.colors.textSecondary)
            }
        }

        // Recent Users
        if (recentUsers.isNotEmpty()) {
            item {
                SectionHeader(icon = "🕐", title = "Recent", showClear = true, onClear = viewModel::clearRecentSearches)
            }
            items(recentUsers.take(5), key = { "recent_${it.id}" }) { user ->
                ModernUserRow(
                    user = user,
                    isFollowing = followingStates[user.id] ?: false,
                    isFollowLoading = viewModel.isFollowLoading(user.id),
                    followButtonText = viewModel.getFollowButtonText(user.id),
                    onTap = { onUserTapped(user) },
                    onFollowToggle = { viewModel.toggleFollow(user.id) }
                )
            }
        }

        // Trending Hashtags
        if (trendingHashtags.isNotEmpty() || isLoadingHashtags) {
            item {
                SectionHeader(icon = "#", title = "Trending Hashtags", iconColor = Color(0xFFFF2D55))
            }
            item {
                TrendingHashtagsRow(
                    hashtags = trendingHashtags,
                    isLoading = isLoadingHashtags,
                    onHashtagTapped = onHashtagTapped
                )
            }
        }

        // People You May Know
        item {
            SectionHeader(icon = "✨", title = "People You May Know", iconColor = Color.Cyan)
        }
        if (isLoadingSuggestions) {
            item { LoadingView() }
        } else {
            items(suggestedUsers, key = { "suggested_${it.id}" }) { user ->
                ModernUserRow(
                    user = user,
                    isFollowing = followingStates[user.id] ?: false,
                    isFollowLoading = viewModel.isFollowLoading(user.id),
                    followButtonText = viewModel.getFollowButtonText(user.id),
                    onTap = { onUserTapped(user) },
                    onFollowToggle = { viewModel.toggleFollow(user.id) }
                )
            }
        }
    }
}

// MARK: - Section Header

@Composable
private fun SectionHeader(
    icon: String,
    title: String,
    iconColor: Color = AppTheme.colors.textSecondary,
    showClear: Boolean = false,
    onClear: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 16.sp, color = iconColor)
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        if (showClear) {
            TextButton(onClick = onClear) {
                Text("Clear", color = AppTheme.colors.textSecondary, fontSize = 14.sp)
            }
        }
    }
}

// MARK: - Trending Hashtags Row (TrendingHashtagChip with velocity)

@Composable
private fun TrendingHashtagsRow(
    hashtags: List<TrendingHashtag>,
    isLoading: Boolean,
    onHashtagTapped: (TrendingHashtag) -> Unit
) {
    if (isLoading) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFFFF2D55), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Loading hashtags...", color = AppTheme.colors.textSecondary, fontSize = 13.sp)
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        hashtags.forEach { hashtag ->
            TrendingHashtagChip(hashtag = hashtag, onTap = { onHashtagTapped(hashtag) })
        }
    }
}

@Composable
private fun TrendingHashtagChip(hashtag: TrendingHashtag, onTap: () -> Unit) {
    Button(
        onClick = onTap,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.hairline),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(hashtag.velocityTier.emoji, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Text(hashtag.displayTag, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppTheme.colors.textPrimary)
    }
}

// MARK: - Modern User Row (matches iOS press animation + gradient avatar border)

@Composable
fun ModernUserRow(
    user: BasicUserInfo,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    followButtonText: String,
    onTap: () -> Unit,
    onFollowToggle: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(100),
        label = "press_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onTap)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .background(AppTheme.colors.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with gradient border
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        Brush.linearGradient(listOf(Color.Cyan, Color(0xFF9B59B6))),
                        CircleShape
                    )
                    .padding(2.dp)
            ) {
                AsyncImage(
                    model = user.profileImageURL,
                    contentDescription = user.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceStrong, CircleShape)
                )
            }

            Spacer(Modifier.width(14.dp))

            // User info
            Column(Modifier.weight(1f)) {
                Text(
                    text = user.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary
                )
                Text(
                    text = "@${user.username}",
                    fontSize = 14.sp,
                    color = AppTheme.colors.textSecondary
                )
            }

            // Follow button (hidden for self)
            Button(
                onClick = onFollowToggle,
                enabled = !isFollowLoading,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isFollowing) Color.Transparent else Color.Cyan,
                    // Explicit, not a theme token: this sits on a fixed cyan
                    // fill in BOTH themes, so it must not follow textPrimary.
                    contentColor = if (isFollowing) AppTheme.colors.textSecondary else Color(0xFF0B0B0D)
                ),
                border = if (isFollowing) BorderStroke(1.dp, AppTheme.colors.textSecondary) else null,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                if (isFollowLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text(followButtonText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// MARK: - Video Search Card (2-column grid item)

@Composable
private fun VideoSearchCard(video: CoreVideoMetadata, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(AppTheme.colors.surface, RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = video.thumbnailURL,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppTheme.colors.surfaceStrong)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(video.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppTheme.colors.textPrimary, maxLines = 2)
                Text(video.creatorName, fontSize = 13.sp, color = AppTheme.colors.textSecondary)
            }
        }
    }
}

// MARK: - State Views

@Composable
private fun LoadingView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(Color.Cyan.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Searching...", fontSize = 15.sp, color = AppTheme.colors.textSecondary)
    }
}

@Composable
private fun NoResultsView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(Color(0xFFFF9500).copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("🤷", fontSize = 48.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text("No Results", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text("Try a different search term", fontSize = 15.sp, color = AppTheme.colors.textSecondary)
    }
}