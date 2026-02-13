/*
 * StitchersListView.kt - FULL SCREEN FOLLOWERS/FOLLOWING/BLOCKED
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Standalone Stitchers/Following/Followers/Blocked Management
 * Dependencies: FollowManager, UserService
 * Features: Paginated lists, search, follow/unfollow, block/unblock, remove follower
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.FollowManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ===== TAB ENUM =====

enum class StitchersTab(val title: String) {
    FOLLOWERS("Followers"),
    FOLLOWING("Following"),
    BLOCKED("Blocked")
}

// ===== VIEW MODEL =====

class StitchersViewModel(
    private val userID: String,
    private val userService: UserService,
    private val followManager: FollowManager
) : ViewModel() {

    private val _followers = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val followers: StateFlow<List<BasicUserInfo>> = _followers.asStateFlow()

    private val _following = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val following: StateFlow<List<BasicUserInfo>> = _following.asStateFlow()

    private val _blockedUsers = MutableStateFlow<List<BasicUserInfo>>(emptyList())
    val blockedUsers: StateFlow<List<BasicUserInfo>> = _blockedUsers.asStateFlow()

    private val _isLoadingFollowers = MutableStateFlow(false)
    val isLoadingFollowers: StateFlow<Boolean> = _isLoadingFollowers.asStateFlow()

    private val _isLoadingFollowing = MutableStateFlow(false)
    val isLoadingFollowing: StateFlow<Boolean> = _isLoadingFollowing.asStateFlow()

    private val _isLoadingBlocked = MutableStateFlow(false)
    val isLoadingBlocked: StateFlow<Boolean> = _isLoadingBlocked.asStateFlow()

    private val _isLoadingMoreFollowers = MutableStateFlow(false)
    val isLoadingMoreFollowers: StateFlow<Boolean> = _isLoadingMoreFollowers.asStateFlow()

    private val _isLoadingMoreFollowing = MutableStateFlow(false)
    val isLoadingMoreFollowing: StateFlow<Boolean> = _isLoadingMoreFollowing.asStateFlow()

    private val _hasMoreFollowers = MutableStateFlow(true)
    val hasMoreFollowers: StateFlow<Boolean> = _hasMoreFollowers.asStateFlow()

    private val _hasMoreFollowing = MutableStateFlow(true)
    val hasMoreFollowing: StateFlow<Boolean> = _hasMoreFollowing.asStateFlow()

    private val _totalFollowers = MutableStateFlow(0)
    val totalFollowers: StateFlow<Int> = _totalFollowers.asStateFlow()

    private val _totalFollowing = MutableStateFlow(0)
    val totalFollowing: StateFlow<Int> = _totalFollowing.asStateFlow()

    private var allFollowers = listOf<BasicUserInfo>()
    private var allFollowing = listOf<BasicUserInfo>()
    private var displayedFollowerCount = 0
    private var displayedFollowingCount = 0
    private val batchSize = 30

    fun loadInitialData() {
        viewModelScope.launch {
            launch { loadFollowers() }
            launch { loadFollowing() }
            launch { loadBlockedUsers() }
        }
    }

    private suspend fun loadFollowers() {
        _isLoadingFollowers.value = true
        try {
            allFollowers = userService.getFollowers(userID)
            _totalFollowers.value = allFollowers.size
            displayedFollowerCount = 0
            val firstBatch = allFollowers.take(batchSize)
            _followers.value = firstBatch
            displayedFollowerCount = firstBatch.size
            _hasMoreFollowers.value = allFollowers.size > displayedFollowerCount
            followManager.loadFollowStatesForUsers(firstBatch)
        } catch (e: Exception) {
            println("❌ STITCHERS: Failed to load followers: ${e.message}")
        }
        _isLoadingFollowers.value = false
    }

    fun loadMoreFollowers() {
        if (_isLoadingMoreFollowers.value || !_hasMoreFollowers.value) return
        viewModelScope.launch {
            _isLoadingMoreFollowers.value = true
            try {
                val start = displayedFollowerCount
                val end = minOf(start + batchSize, allFollowers.size)
                val nextBatch = allFollowers.subList(start, end)
                _followers.value = _followers.value + nextBatch
                displayedFollowerCount += nextBatch.size
                _hasMoreFollowers.value = displayedFollowerCount < allFollowers.size
                followManager.loadFollowStatesForUsers(nextBatch)
            } catch (e: Exception) {
                println("❌ STITCHERS: Failed to load more followers: ${e.message}")
            }
            _isLoadingMoreFollowers.value = false
        }
    }

    private suspend fun loadFollowing() {
        _isLoadingFollowing.value = true
        try {
            allFollowing = userService.getFollowing(userID)
            _totalFollowing.value = allFollowing.size
            displayedFollowingCount = 0
            val firstBatch = allFollowing.take(batchSize)
            _following.value = firstBatch
            displayedFollowingCount = firstBatch.size
            _hasMoreFollowing.value = allFollowing.size > displayedFollowingCount
            followManager.loadFollowStatesForUsers(firstBatch)
        } catch (e: Exception) {
            println("❌ STITCHERS: Failed to load following: ${e.message}")
        }
        _isLoadingFollowing.value = false
    }

    fun loadMoreFollowing() {
        if (_isLoadingMoreFollowing.value || !_hasMoreFollowing.value) return
        viewModelScope.launch {
            _isLoadingMoreFollowing.value = true
            try {
                val start = displayedFollowingCount
                val end = minOf(start + batchSize, allFollowing.size)
                val nextBatch = allFollowing.subList(start, end)
                _following.value = _following.value + nextBatch
                displayedFollowingCount += nextBatch.size
                _hasMoreFollowing.value = displayedFollowingCount < allFollowing.size
                followManager.loadFollowStatesForUsers(nextBatch)
            } catch (e: Exception) {
                println("❌ STITCHERS: Failed to load more following: ${e.message}")
            }
            _isLoadingMoreFollowing.value = false
        }
    }

    private suspend fun loadBlockedUsers() {
        _isLoadingBlocked.value = true
        _blockedUsers.value = emptyList()
        _isLoadingBlocked.value = false
    }

    fun toggleFollow(targetUserID: String) {
        followManager.toggleFollow(targetUserID)
    }

    fun toggleBlock(targetUserID: String) {
        println("⚠️ STITCHERS: Block functionality not yet implemented")
    }

    fun removeFollower(followerID: String) {
        println("⚠️ STITCHERS: Remove follower functionality not yet implemented")
    }
}

// ===== MAIN VIEW =====

@Composable
fun StitchersListView(
    profileUserID: String,
    profileUsername: String,
    isOwnProfile: Boolean,
    followManager: FollowManager,
    onDismiss: () -> Unit,
    onUserTap: (String) -> Unit
) {
    val context = LocalContext.current
    val userService = remember { UserService(context) }
    val viewModel = remember { StitchersViewModel(profileUserID, userService, followManager) }

    var selectedTab by remember { mutableStateOf(StitchersTab.FOLLOWERS) }
    var searchText by remember { mutableStateOf("") }
    var showOptionsForUser by remember { mutableStateOf<BasicUserInfo?>(null) }

    val followers by viewModel.followers.collectAsState()
    val following by viewModel.following.collectAsState()
    val blockedUsers by viewModel.blockedUsers.collectAsState()
    val isLoadingFollowers by viewModel.isLoadingFollowers.collectAsState()
    val isLoadingFollowing by viewModel.isLoadingFollowing.collectAsState()
    val isLoadingBlocked by viewModel.isLoadingBlocked.collectAsState()
    val isLoadingMoreFollowers by viewModel.isLoadingMoreFollowers.collectAsState()
    val isLoadingMoreFollowing by viewModel.isLoadingMoreFollowing.collectAsState()
    val hasMoreFollowers by viewModel.hasMoreFollowers.collectAsState()
    val hasMoreFollowing by viewModel.hasMoreFollowing.collectAsState()
    val totalFollowers by viewModel.totalFollowers.collectAsState()
    val totalFollowing by viewModel.totalFollowing.collectAsState()
    val followingStates by followManager.followingStates.collectAsState()
    val loadingStates by followManager.loadingStates.collectAsState()

    val filteredFollowers = remember(followers, searchText) {
        if (searchText.isBlank()) followers
        else {
            val q = searchText.lowercase()
            followers.filter { it.username.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
        }
    }
    val filteredFollowing = remember(following, searchText) {
        if (searchText.isBlank()) following
        else {
            val q = searchText.lowercase()
            following.filter { it.username.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
        }
    }
    val filteredBlocked = remember(blockedUsers, searchText) {
        if (searchText.isBlank()) blockedUsers
        else {
            val q = searchText.lowercase()
            blockedUsers.filter { it.username.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
        }
    }

    LaunchedEffect(Unit) { viewModel.loadInitialData() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        // ── Top Bar ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color.Black)
        ) {
            // Close button - left aligned
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(44.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Username - centered
            Text(
                text = "@$profileUsername",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // ── Tab Bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            StitchersTabItem(
                title = "Followers",
                count = totalFollowers,
                isSelected = selectedTab == StitchersTab.FOLLOWERS,
                onClick = { selectedTab = StitchersTab.FOLLOWERS },
                modifier = Modifier.weight(1f)
            )
            StitchersTabItem(
                title = "Following",
                count = totalFollowing,
                isSelected = selectedTab == StitchersTab.FOLLOWING,
                onClick = { selectedTab = StitchersTab.FOLLOWING },
                modifier = Modifier.weight(1f)
            )
            if (isOwnProfile) {
                StitchersTabItem(
                    title = "Blocked",
                    count = blockedUsers.size,
                    isSelected = selectedTab == StitchersTab.BLOCKED,
                    onClick = { selectedTab = StitchersTab.BLOCKED },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── Search Bar (own profile only) ──
        if (isOwnProfile) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(Modifier.width(10.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        if (searchText.isEmpty()) {
                            Text("Search", color = Color.Gray, fontSize = 15.sp)
                        }
                        BasicTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            singleLine = true,
                            cursorBrush = SolidColor(Color.Cyan),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (searchText.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = "Clear",
                            tint = Color.Gray,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { searchText = "" }
                        )
                    }
                }
            }
        }

        // ── Tab Content ──
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                StitchersTab.FOLLOWERS -> {
                    when {
                        isLoadingFollowers && followers.isEmpty() -> CenteredLoading()
                        filteredFollowers.isEmpty() -> CenteredEmpty(
                            icon = Icons.Default.People,
                            title = if (searchText.isEmpty()) "No Followers Yet" else "No Results",
                            subtitle = if (searchText.isEmpty())
                                "When people follow ${if (isOwnProfile) "you" else "@$profileUsername"}, they'll appear here."
                            else "Try a different search term"
                        )
                        else -> UserList(
                            users = filteredFollowers,
                            listType = StitchersTab.FOLLOWERS,
                            isOwnProfile = isOwnProfile,
                            followingStates = followingStates,
                            loadingStates = loadingStates,
                            isLoadingMore = isLoadingMoreFollowers,
                            hasMore = hasMoreFollowers,
                            onLoadMore = { viewModel.loadMoreFollowers() },
                            onUserTap = onUserTap,
                            onFollow = { viewModel.toggleFollow(it) },
                            onMoreOptions = { showOptionsForUser = it }
                        )
                    }
                }
                StitchersTab.FOLLOWING -> {
                    when {
                        isLoadingFollowing && following.isEmpty() -> CenteredLoading()
                        filteredFollowing.isEmpty() -> CenteredEmpty(
                            icon = Icons.Default.PersonAdd,
                            title = if (searchText.isEmpty()) "Not Following Anyone" else "No Results",
                            subtitle = if (searchText.isEmpty())
                                "${if (isOwnProfile) "You're" else "@$profileUsername is"} not following anyone yet."
                            else "Try a different search term"
                        )
                        else -> UserList(
                            users = filteredFollowing,
                            listType = StitchersTab.FOLLOWING,
                            isOwnProfile = isOwnProfile,
                            followingStates = followingStates,
                            loadingStates = loadingStates,
                            isLoadingMore = isLoadingMoreFollowing,
                            hasMore = hasMoreFollowing,
                            onLoadMore = { viewModel.loadMoreFollowing() },
                            onUserTap = onUserTap,
                            onFollow = { viewModel.toggleFollow(it) },
                            onMoreOptions = { showOptionsForUser = it }
                        )
                    }
                }
                StitchersTab.BLOCKED -> {
                    when {
                        isLoadingBlocked && blockedUsers.isEmpty() -> CenteredLoading()
                        filteredBlocked.isEmpty() -> CenteredEmpty(
                            icon = Icons.Default.Block,
                            title = if (searchText.isEmpty()) "No Blocked Users" else "No Results",
                            subtitle = if (searchText.isEmpty())
                                "Users you block won't be able to see your content or interact with you."
                            else "Try a different search term"
                        )
                        else -> UserList(
                            users = filteredBlocked,
                            listType = StitchersTab.BLOCKED,
                            isOwnProfile = isOwnProfile,
                            followingStates = followingStates,
                            loadingStates = loadingStates,
                            isLoadingMore = false,
                            hasMore = false,
                            onLoadMore = {},
                            onUserTap = onUserTap,
                            onFollow = { viewModel.toggleBlock(it) },
                            onMoreOptions = { _ -> }
                        )
                    }
                }
            }
        }
    }

    // Options dialog
    showOptionsForUser?.let { user ->
        AlertDialog(
            onDismissRequest = { showOptionsForUser = null },
            title = { Text("Options for @${user.username}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    if (selectedTab == StitchersTab.FOLLOWERS) {
                        TextButton(
                            onClick = { viewModel.removeFollower(user.id); showOptionsForUser = null },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Remove Follower", color = Color.Red, fontSize = 15.sp)
                        }
                    }
                    TextButton(
                        onClick = { viewModel.toggleBlock(user.id); showOptionsForUser = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Block @${user.username}", color = Color.Red, fontSize = 15.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showOptionsForUser = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}

// ===== TAB ITEM =====

@Composable
private fun StitchersTabItem(
    title: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 12.dp)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) Color.White else Color.Gray
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "$count",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) Color.Cyan else Color.Gray.copy(alpha = 0.7f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (isSelected) Color.Cyan else Color.Transparent)
        )
    }
}

// ===== USER LIST =====

@Composable
private fun UserList(
    users: List<BasicUserInfo>,
    listType: StitchersTab,
    isOwnProfile: Boolean,
    followingStates: Map<String, Boolean>,
    loadingStates: Set<String>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onUserTap: (String) -> Unit,
    onFollow: (String) -> Unit,
    onMoreOptions: (BasicUserInfo) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(users, key = { _, user -> user.id }) { index, user ->
            UserRow(
                user = user,
                listType = listType,
                isOwnProfile = isOwnProfile,
                isFollowing = followingStates[user.id] ?: false,
                isLoading = loadingStates.contains(user.id),
                onTap = { onUserTap(user.id) },
                onFollow = { onFollow(user.id) },
                onMoreOptions = { onMoreOptions(user) }
            )

            if (index < users.size - 1) {
                HorizontalDivider(
                    color = Color.Gray.copy(alpha = 0.2f),
                    modifier = Modifier.padding(start = 76.dp)
                )
            }

            if (index >= users.size - 5 && hasMore && !isLoadingMore) {
                LaunchedEffect(index) { onLoadMore() }
            }
        }

        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

// ===== USER ROW =====

@Composable
private fun UserRow(
    user: BasicUserInfo,
    listType: StitchersTab,
    isOwnProfile: Boolean,
    isFollowing: Boolean,
    isLoading: Boolean,
    onTap: () -> Unit,
    onFollow: () -> Unit,
    onMoreOptions: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar — fixed 48dp — tappable for profile navigation
        AsyncImage(
            model = user.profileImageURL ?: "",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.3f))
                .clickable(onClick = onTap)
        )

        Spacer(Modifier.width(12.dp))

        // Name / username / tier — fills remaining space — tappable for profile navigation
        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .clickable(onClick = onTap)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.displayName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (user.isVerified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = "Verified",
                        tint = Color.Cyan,
                        modifier = Modifier.size(14.dp)
                    )
                }

                if (user.tier != UserTier.ROOKIE) {
                    Spacer(Modifier.width(4.dp))
                    StitchersTierBadge(tier = user.tier)
                }
            }

            Spacer(Modifier.height(2.dp))

            Text(
                text = "@${user.username}",
                fontSize = 13.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        // Action button — fixed intrinsic width
        when (listType) {
            StitchersTab.BLOCKED -> {
                Button(
                    onClick = onFollow,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Unblock", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
            else -> {
                Button(
                    onClick = onFollow,
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFollowing) Color.White else Color.Cyan,
                        contentColor = if (isFollowing) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = if (isFollowing) Color.Black else Color.White
                        )
                    } else {
                        if (isFollowing) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = if (isFollowing) "Following" else "Follow",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // More options — own profile only, non-blocked
        if (isOwnProfile && listType != StitchersTab.BLOCKED) {
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onMoreOptions,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ===== TIER BADGE =====

@Composable
private fun StitchersTierBadge(tier: UserTier) {
    val color = when (tier) {
        UserTier.LEGENDARY, UserTier.CO_FOUNDER, UserTier.FOUNDER -> Color.Yellow
        UserTier.ELITE, UserTier.PARTNER -> Color(0xFF9C27B0)
        UserTier.AMBASSADOR, UserTier.TOP_CREATOR -> Color.Cyan
        UserTier.INFLUENCER, UserTier.VETERAN -> Color(0xFF4A90E2)
        else -> Color.Gray
    }

    Text(
        text = tier.displayName,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black,
        modifier = Modifier
            .background(color, RoundedCornerShape(10.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

// ===== CENTERED LOADING =====

@Composable
private fun CenteredLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = Color.Cyan)
            Text("Loading...", fontSize = 14.sp, color = Color.Gray)
        }
    }
}

// ===== CENTERED EMPTY =====

@Composable
private fun CenteredEmpty(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.Gray.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}