/*
 * SearchView.kt - COMPLETE SEARCH WITH HASHTAGS + VIDEOS + USERS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Search Interface with Follow/Unfollow
 * Dependencies: SearchService, BasicUserInfo, FollowManager
 * Features: User search, video search, hashtag search, trending hashtags
 *
 * ✅ UPDATED: Added hashtag search + video results + trending hashtags
 * ✅ UPDATED: Tabbed results (Users / Videos / Hashtags)
 * ✅ UPDATED: Trending hashtags shown when no query
 */

package com.stitchsocial.club


import com.stitchsocial.club.foundation.BasicUserInfo
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.SearchService
import com.stitchsocial.club.FollowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// MARK: - Search Tab

enum class SearchTab(val title: String) {
    USERS("Users"),
    VIDEOS("Videos"),
    HASHTAGS("Hashtags")
}

// MARK: - Search ViewModel

class SearchViewModel(
    private val searchService: SearchService,
    private val followManager: FollowManager
) : ViewModel() {

    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _userResults = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val userResults: StateFlow<List<BasicUserInfo>> = _userResults.asStateFlow()

    private val _videoResults = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val videoResults: StateFlow<List<CoreVideoMetadata>> = _videoResults.asStateFlow()

    private val _hashtagResults = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val hashtagResults: StateFlow<List<CoreVideoMetadata>> = _hashtagResults.asStateFlow()

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private val _suggestedUsers = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val suggestedUsers: StateFlow<List<BasicUserInfo>> = _suggestedUsers.asStateFlow()

    private val _trendingHashtags = MutableStateFlow<List<String>>(emptyList())
    val trendingHashtags: StateFlow<List<String>> = _trendingHashtags.asStateFlow()

    private val _isLoadingSuggestions = MutableStateFlow(false)
    val isLoadingSuggestions: StateFlow<Boolean> = _isLoadingSuggestions.asStateFlow()

    private val _selectedTab = MutableStateFlow(SearchTab.USERS)
    val selectedTab: StateFlow<SearchTab> = _selectedTab.asStateFlow()

    init {
        // Auto-search with debounce
        viewModelScope.launch {
            searchQuery.collect { query ->
                delay(300)
                if (query.isNotBlank()) {
                    performSearch(query)
                } else {
                    clearResults()
                }
            }
        }

        // Load initial data
        loadSuggestions()
        loadTrendingHashtags()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectTab(tab: SearchTab) {
        _selectedTab.value = tab
    }

    fun searchByHashtag(hashtag: String) {
        _searchQuery.value = "#$hashtag"
        _selectedTab.value = SearchTab.HASHTAGS
    }

    fun toggleFollow(userID: String) {
        followManager.toggleFollow(userID)
    }

    fun isFollowing(userID: String): Boolean = followManager.isFollowing(userID)
    fun isFollowLoading(userID: String): Boolean = followManager.isLoading(userID)
    fun getFollowButtonText(userID: String): String = followManager.getFollowButtonText(userID)

    private fun performSearch(query: String) {
        viewModelScope.launch {
            _isSearching.value = true
            _hasSearched.value = true

            try {
                val isHashtagSearch = query.startsWith("#")
                val cleanQuery = query.removePrefix("#").trim()

                if (isHashtagSearch) {
                    // Hashtag search
                    val videos = searchService.searchByHashtag(cleanQuery, limit = 50)
                    _hashtagResults.value = videos
                    _selectedTab.value = SearchTab.HASHTAGS
                } else {
                    // Parallel user + video search
                    val users = searchService.searchUsers(cleanQuery, limit = 50)
                    val videos = searchService.searchVideos(cleanQuery, limit = 30)

                    _userResults.value = users
                    _videoResults.value = videos
                    followManager.loadFollowStatesForUsers(users)
                }

                println("🔍 SEARCH: query='$query' users=${_userResults.value.size} videos=${_videoResults.value.size} hashtag=${_hashtagResults.value.size}")

            } catch (e: Exception) {
                println("❌ SEARCH: Failed: ${e.message}")
                clearResults()
            } finally {
                _isSearching.value = false
            }
        }
    }

    private fun loadSuggestions() {
        viewModelScope.launch {
            _isLoadingSuggestions.value = true
            try {
                val users = searchService.searchUsers("", limit = 20)
                _suggestedUsers.value = users
                followManager.loadFollowStatesForUsers(users)
            } catch (e: Exception) {
                println("❌ SEARCH: Suggestions failed: ${e.message}")
            } finally {
                _isLoadingSuggestions.value = false
            }
        }
    }

    private fun loadTrendingHashtags() {
        viewModelScope.launch {
            try {
                val trending = searchService.getTrendingHashtags(limit = 20)
                _trendingHashtags.value = trending
            } catch (e: Exception) {
                println("❌ SEARCH: Trending hashtags failed: ${e.message}")
            }
        }
    }

    private fun clearResults() {
        _userResults.value = emptyList()
        _videoResults.value = emptyList()
        _hashtagResults.value = emptyList()
        _hasSearched.value = false
    }
}

// MARK: - Main Search View

@Composable
fun SearchView(
    followManager: FollowManager,
    onUserTapped: (BasicUserInfo) -> Unit = {},
    onVideoTapped: (CoreVideoMetadata) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val viewModel = remember {
        SearchViewModel(
            searchService = SearchService(),
            followManager = followManager
        )
    }

    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val userResults by viewModel.userResults.collectAsState()
    val videoResults by viewModel.videoResults.collectAsState()
    val hashtagResults by viewModel.hashtagResults.collectAsState()
    val hasSearched by viewModel.hasSearched.collectAsState()
    val suggestedUsers by viewModel.suggestedUsers.collectAsState()
    val trendingHashtags by viewModel.trendingHashtags.collectAsState()
    val isLoadingSuggestions by viewModel.isLoadingSuggestions.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val followingStates by followManager.followingStates.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1C1E))
    ) {
        // Search Header
        SearchHeader(
            searchQuery = searchQuery,
            onSearchQueryChanged = viewModel::updateSearchQuery,
            onDismiss = onDismiss,
            isSearching = isSearching
        )

        // Content
        if (hasSearched) {
            // Tab bar
            SearchTabBar(
                selectedTab = selectedTab,
                userCount = userResults.size,
                videoCount = videoResults.size + hashtagResults.size,
                onTabSelected = viewModel::selectTab
            )

            // Tab content
            when (selectedTab) {
                SearchTab.USERS -> UserResultsList(
                    users = userResults,
                    followingStates = followingStates,
                    viewModel = viewModel,
                    onUserTapped = onUserTapped
                )
                SearchTab.VIDEOS -> VideoResultsList(
                    videos = videoResults,
                    onVideoTapped = onVideoTapped
                )
                SearchTab.HASHTAGS -> VideoResultsList(
                    videos = hashtagResults,
                    onVideoTapped = onVideoTapped
                )
            }
        } else {
            // Discovery: Trending hashtags + suggested users
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Trending Hashtags
                if (trendingHashtags.isNotEmpty()) {
                    item {
                        Text("Trending Hashtags", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }

                    item {
                        TrendingHashtagsRow(
                            hashtags = trendingHashtags,
                            onHashtagTapped = { viewModel.searchByHashtag(it) }
                        )
                    }
                }

                // Suggested Users
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Suggested Users", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }

                if (isLoadingSuggestions) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF0A84FF))
                        }
                    }
                } else {
                    suggestedUsers.forEach { user ->
                        item {
                            UserSearchItem(
                                user = user,
                                isFollowing = followingStates[user.id] ?: false,
                                isFollowLoading = viewModel.isFollowLoading(user.id),
                                followButtonText = viewModel.getFollowButtonText(user.id),
                                onUserTapped = { onUserTapped(user) },
                                onFollowToggle = { viewModel.toggleFollow(user.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Search Tab Bar

@Composable
private fun SearchTabBar(
    selectedTab: SearchTab,
    userCount: Int,
    videoCount: Int,
    onTabSelected: (SearchTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SearchTab.entries.forEach { tab ->
            val count = when (tab) {
                SearchTab.USERS -> userCount
                SearchTab.VIDEOS -> videoCount
                SearchTab.HASHTAGS -> videoCount
            }
            val isSelected = tab == selectedTab

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(tab) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) Color(0xFF0A84FF) else Color(0xFF2C2C2E)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = tab.title,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color.White else Color.Gray
                    )
                    if (count > 0) {
                        Text(
                            text = "$count",
                            fontSize = 12.sp,
                            color = if (isSelected) Color.White.copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Trending Hashtags Row

@Composable
private fun TrendingHashtagsRow(
    hashtags: List<String>,
    onHashtagTapped: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        hashtags.forEach { hashtag ->
            Surface(
                modifier = Modifier.clickable { onHashtagTapped(hashtag) },
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0A84FF).copy(alpha = 0.15f)
            ) {
                Text(
                    text = "#$hashtag",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF0A84FF)
                )
            }
        }
    }
}

// MARK: - User Results List

@Composable
private fun UserResultsList(
    users: List<BasicUserInfo>,
    followingStates: Map<String, Boolean>,
    viewModel: SearchViewModel,
    onUserTapped: (BasicUserInfo) -> Unit
) {
    if (users.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No users found", color = Color(0xFF8E8E93), fontSize = 16.sp)
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            users.forEach { user ->
                item {
                    UserSearchItem(
                        user = user,
                        isFollowing = followingStates[user.id] ?: false,
                        isFollowLoading = viewModel.isFollowLoading(user.id),
                        followButtonText = viewModel.getFollowButtonText(user.id),
                        onUserTapped = { onUserTapped(user) },
                        onFollowToggle = { viewModel.toggleFollow(user.id) }
                    )
                }
            }
        }
    }
}

// MARK: - Video Results List

@Composable
private fun VideoResultsList(
    videos: List<CoreVideoMetadata>,
    onVideoTapped: (CoreVideoMetadata) -> Unit
) {
    if (videos.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No videos found", color = Color(0xFF8E8E93), fontSize = 16.sp)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            videos.forEach { video ->
                item {
                    VideoSearchCard(video = video, onTap = { onVideoTapped(video) })
                }
            }
        }
    }
}

// MARK: - Video Search Card

@Composable
private fun VideoSearchCard(
    video: CoreVideoMetadata,
    onTap: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clickable { onTap() },
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF2C2C2E)
    ) {
        Box {
            // Thumbnail
            AsyncImage(
                model = video.thumbnailURL,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
            )

            // Bottom overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            ) {
                Text(
                    text = video.title.ifBlank { "Untitled" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🔥 ${video.hypeCount}", fontSize = 11.sp, color = Color.Gray)
                    Text("👁 ${video.viewCount}", fontSize = 11.sp, color = Color.Gray)
                }

                if (video.hashtags.isNotEmpty()) {
                    Text(
                        text = video.hashtags.take(2).joinToString(" ") { "#$it" },
                        fontSize = 10.sp,
                        color = Color(0xFF0A84FF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// MARK: - Search Header

@Composable
private fun SearchHeader(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    isSearching: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search",
                tint = Color(0xFF8E8E93),
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                singleLine = true,
                cursorBrush = SolidColor(Color(0xFF0A84FF)),
                decorationBox = { innerTextField ->
                    if (searchQuery.isEmpty()) {
                        Text("Search users, videos, #hashtags", color = Color(0xFF8E8E93), fontSize = 16.sp)
                    }
                    innerTextField()
                },
                modifier = Modifier.weight(1f)
            )

            if (searchQuery.isNotEmpty()) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear",
                    tint = Color(0xFF8E8E93),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onSearchQueryChanged("") }
                )
            }

            if (isSearching) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color(0xFF0A84FF),
                    strokeWidth = 2.dp
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        TextButton(onClick = onDismiss) {
            Text("Cancel", color = Color(0xFF0A84FF))
        }
    }
}

// MARK: - User Search Item

@Composable
private fun UserSearchItem(
    user: BasicUserInfo,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    followButtonText: String,
    onUserTapped: () -> Unit,
    onFollowToggle: () -> Unit
) {
    val view = LocalView.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserTapped() }
            .background(Color(0xFF2C2C2E), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = user.profileImageURL ?: "",
            contentDescription = "Profile",
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.Gray),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(user.displayName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("@${user.username}", color = Color(0xFF8E8E93), fontSize = 14.sp)
            if (user.bio.isNotBlank()) {
                Text(
                    text = user.bio,
                    color = Color(0xFF8E8E93),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        FollowButton(
            text = followButtonText,
            isFollowing = isFollowing,
            isLoading = isFollowLoading,
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onFollowToggle()
            }
        )
    }
}

// MARK: - Follow Button

@Composable
private fun FollowButton(
    text: String,
    isFollowing: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val (textColor, bgColor) = if (isFollowing) {
        Pair(Color.Black, Color.White)
    } else {
        Pair(Color.White, Color(0xFF0A84FF))
    }

    Button(
        onClick = onClick,
        enabled = !isLoading,
        colors = ButtonDefaults.buttonColors(containerColor = bgColor, contentColor = textColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .height(36.dp)
            .widthIn(min = 80.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = textColor, strokeWidth = 2.dp)
        } else {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}