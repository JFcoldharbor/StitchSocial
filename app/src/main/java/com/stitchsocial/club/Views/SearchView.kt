/*
 * SearchView.kt - SIMPLIFIED VERSION THAT WORKS WITH YOUR EXISTING STRUCTURE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Search Interface with Follow/Unfollow
 * Dependencies: SearchService, BasicUserInfo, FollowManager
 * Features: User search with follow buttons (simplified for your current structure)
 */

package com.stitchsocial.club


import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.SearchService
import com.stitchsocial.club.FollowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.collections.forEach

// MARK: - Simplified Search ViewModel

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

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private val _suggestedUsers = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val suggestedUsers: StateFlow<List<BasicUserInfo>> = _suggestedUsers.asStateFlow()

    private val _isLoadingSuggestions = MutableStateFlow(false)
    val isLoadingSuggestions: StateFlow<Boolean> = _isLoadingSuggestions.asStateFlow()

    init {
        // Auto-search with simple delay
        viewModelScope.launch {
            searchQuery.collect { query ->
                delay(300) // Simple debounce
                if (query.isNotBlank()) {
                    performSearch(query)
                } else {
                    clearResults()
                    loadSuggestions()
                }
            }
        }

        // Load initial suggestions
        loadSuggestions()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFollow(userID: String) {
        followManager.toggleFollow(userID)
    }

    fun isFollowing(userID: String): Boolean {
        return followManager.isFollowing(userID)
    }

    fun isFollowLoading(userID: String): Boolean {
        return followManager.isLoading(userID)
    }

    fun getFollowButtonText(userID: String): String {
        return followManager.getFollowButtonText(userID)
    }

    private fun performSearch(query: String) {
        viewModelScope.launch {
            _isSearching.value = true
            _hasSearched.value = true

            try {
                println("🔍 SEARCH VM: Searching for '$query'")

                // Search users only (simplified)
                val users = searchService.searchUsers(query, limit = 50)
                _userResults.value = users

                // Load follow states for found users
                followManager.loadFollowStatesForUsers(users)

                println("🔍 SEARCH VM: Found ${users.size} users")

            } catch (e: Exception) {
                println("❌ SEARCH VM: Search failed: ${e.message}")
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
                println("🔍 SEARCH VM: Loaded ${users.size} suggested users")
            } catch (e: Exception) {
                println("❌ SEARCH VM: Failed to load suggestions: ${e.message}")
            } finally {
                _isLoadingSuggestions.value = false
            }
        }
    }

    private fun clearResults() {
        _userResults.value = emptyList()
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

    // State
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val userResults by viewModel.userResults.collectAsState()
    val hasSearched by viewModel.hasSearched.collectAsState()
    val suggestedUsers by viewModel.suggestedUsers.collectAsState()
    val isLoadingSuggestions by viewModel.isLoadingSuggestions.collectAsState()

    // Get follow states from FollowManager
    val followingStates by followManager.followingStates.collectAsState()

    // Colors
    val backgroundColor = Color(0xFF1C1C1E)
    val cardBackground = Color(0xFF2C2C2E)
    val textColor = Color.White
    val secondaryTextColor = Color(0xFF8E8E93)
    val accentColor = Color(0xFF0A84FF)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Search Header
        SearchHeader(
            searchQuery = searchQuery,
            onSearchQueryChanged = viewModel::updateSearchQuery,
            onDismiss = onDismiss,
            isSearching = isSearching
        )

        // Content
        Box(modifier = Modifier.weight(1f)) {
            if (hasSearched) {
                // Search Results
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (userResults.isNotEmpty()) {
                        item {
                            Text(
                                "Users",
                                color = textColor,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        userResults.forEach { user ->
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
                    } else {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No users found",
                                    color = secondaryTextColor,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            } else {
                // User Suggestions
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            "Suggested Users",
                            color = textColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isLoadingSuggestions) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = accentColor)
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
        // Search Field
        Row(
            modifier = Modifier
                .weight(1f)
                .background(
                    Color(0xFF2C2C2E),
                    RoundedCornerShape(10.dp)
                )
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
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 16.sp
                ),
                singleLine = true,
                cursorBrush = SolidColor(Color(0xFF0A84FF)),
                decorationBox = { innerTextField ->
                    if (searchQuery.isEmpty()) {
                        Text(
                            "Search users",
                            color = Color(0xFF8E8E93),
                            fontSize = 16.sp
                        )
                    }
                    innerTextField()
                },
                modifier = Modifier.weight(1f)
            )

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

        // Cancel Button
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
            .background(
                Color(0xFF2C2C2E),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Profile Image
        AsyncImage(
            model = user.profileImageURL ?: "",
            contentDescription = "Profile Picture",
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.Gray),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        // User Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "@${user.username}",
                color = Color(0xFF8E8E93),
                fontSize = 14.sp
            )
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

        // Follow Button
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
    val (textColor, backgroundColor) = if (isFollowing) {
        Pair(Color.Black, Color.White)
    } else {
        Pair(Color.White, Color(0xFF0A84FF))
    }

    Button(
        onClick = onClick,
        enabled = !isLoading,
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = textColor
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .height(36.dp)
            .widthIn(min = 80.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = textColor,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}