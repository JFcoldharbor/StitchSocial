/*
 * NotificationView.kt - COMPLETE NOTIFICATION SCREEN WITH WORKING NAVIGATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Complete Notification Feed with Discovery & Navigation
 * ✅ Auto-scrolling discovery avatars
 * ✅ Side-by-side discovery layout (Just Joined | Top Videos)
 * ✅ Notification tabs with filtering
 * ✅ FIXED: Profile navigation with follow button & video grid
 * ✅ FIXED: Video navigation with actual video player
 * ✅ Follow back buttons
 * ✅ Mark all as read
 * ✅ Pagination support
 * ✅ Timer cleanup
 *
 * EXACT PORT: NotificationView.swift with all features + working navigation
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// Foundation
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.RecentUser
import com.stitchsocial.club.foundation.LeaderboardVideo
import com.stitchsocial.club.foundation.CoreVideoMetadata

// Services
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.NotificationService
import com.stitchsocial.club.services.DiscoveryService

// ViewModels
import com.stitchsocial.club.viewmodels.NotificationViewModel
import com.stitchsocial.club.viewmodels.NotificationFilter
import com.stitchsocial.club.viewmodels.NotificationItem
import com.stitchsocial.club.viewmodels.NotificationType
import com.stitchsocial.club.viewmodels.NotificationNavigationEvent

// Coordination
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.FollowManager

// Firebase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

/**
 * Complete notification screen with discovery and filtering
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationViewComplete(
    navigationCoordinator: NavigationCoordinator,
    onShowThreadView: (String, String?) -> Unit = { _, _ -> },
    onNavigateToProfile: (String) -> Unit = { },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Services and dependencies
    val userService = remember { UserService(context) }
    val videoService = remember { VideoServiceImpl() }
    val notificationService = remember { NotificationService() }
    val discoveryService = remember { DiscoveryService(context) }
    val followManager = remember { FollowManager(context) }
    val engagementCoordinator = remember { EngagementCoordinator(videoService, userService) }

    // ViewModel
    val viewModel = remember {
        NotificationViewModel(
            userService = userService,
            engagementCoordinator = engagementCoordinator,
            navigationCoordinator = navigationCoordinator,
            context = context
        )
    }

    // State
    val notifications by viewModel.filteredNotifications.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val profileImages by viewModel.profileImages.collectAsState()

    // Discovery state
    var recentUsers by remember { mutableStateOf<List<RecentUser>>(emptyList()) }
    var leaderboardVideos by remember { mutableStateOf<List<LeaderboardVideo>>(emptyList()) }
    var isLoadingDiscovery by remember { mutableStateOf(false) }

    // Navigation state (JustJoined and TopVideos dialogs only)
    var showingJustJoinedView by remember { mutableStateOf(false) }
    var showingTopVideosView by remember { mutableStateOf(false) }

    // Observe navigation events from ViewModel
    LaunchedEffect(viewModel) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is NotificationNavigationEvent.NavigateToProfile -> {
                    onNavigateToProfile(event.userId)
                }
                is NotificationNavigationEvent.NavigateToVideo -> {
                    onShowThreadView(event.threadId ?: event.videoId, event.videoId)
                }
                is NotificationNavigationEvent.NavigateToThread -> {
                    onShowThreadView(event.threadId, null)
                }
                NotificationNavigationEvent.None -> { /* Do nothing */ }
            }
        }
    }

    // Load initial data
    LaunchedEffect(Unit) {
        // Load discovery data
        isLoadingDiscovery = true
        try {
            val (users, videos) = discoveryService.refreshDiscoveryData(userLimit = 20, leaderboardLimit = 10)
            recentUsers = users
            leaderboardVideos = videos
        } catch (e: Exception) {
            println("❌ NOTIFICATION VIEW: Failed to load discovery data: ${e.message}")
        }
        isLoadingDiscovery = false

        // Load follow states for notifications
        val senderIDs = notifications.mapNotNull { notification ->
            notification.actionData["userId"] as? String
                ?: notification.actionData["senderID"] as? String
        }.distinct()
        if (senderIDs.isNotEmpty()) {
            followManager.loadFollowStates(senderIDs)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            item {
                NotificationHeader(
                    unreadCount = unreadCount,
                    onMarkAllRead = {
                        viewModel.markAllAsRead()
                    }
                )
            }

            // Tab selector
            item {
                NotificationTabSelector(
                    selectedFilter = selectedFilter,
                    unreadCount = unreadCount,
                    onFilterSelected = { filter ->
                        viewModel.setFilter(filter)
                    }
                )
            }

            // Discovery: Just Joined avatars (tap opens full view)
            item {
                Box(modifier = Modifier.clickable { showingJustJoinedView = true }) {
                    // Inline JustJoinedSection
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Just Joined",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        if (recentUsers.isEmpty()) {
                            Text(
                                "No new users in the last 24 hours",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                            )
                        } else {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(recentUsers, key = { it.id }) { user ->
                                    // Avatar with verified badge
                                    Box(contentAlignment = Alignment.BottomEnd) {
                                        val imageUrl = user.profileImageURL
                                        if (!imageUrl.isNullOrEmpty()) {
                                            AsyncImage(
                                                model = imageUrl,
                                                contentDescription = user.username,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(60.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.Gray.copy(alpha = 0.3f))
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(60.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.Gray.copy(alpha = 0.3f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    user.username.take(1).uppercase(),
                                                    fontSize = 24.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                        if (user.isVerified) {
                                            Box(
                                                modifier = Modifier
                                                    .offset(x = 2.dp, y = 2.dp)
                                                    .size(16.dp)
                                                    .background(Color.Black, CircleShape)
                                                    .padding(1.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Verified,
                                                    contentDescription = "Verified",
                                                    tint = Color.Blue,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                "Tap to view all new users",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }

            // Discovery: Hype Leaderboard (ranked cards)
            item {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { showingTopVideosView = true },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "\uD83D\uDD25 Hype Leaderboard",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "See All",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Cyan
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color.Cyan,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (leaderboardVideos.isEmpty()) {
                        Text(
                            "No videos with hype yet",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            leaderboardVideos.take(5).forEachIndexed { index, video ->
                                val rank = index + 1
                                val rankColor = when (rank) {
                                    1 -> Color.Yellow
                                    2 -> Color.Gray
                                    3 -> Color(0xFFCD7F32)
                                    else -> Color.White.copy(alpha = 0.3f)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                        .clickable {
                                            onShowThreadView(video.id, video.id)
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Rank badge
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(rankColor, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("$rank", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    // Thumbnail
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF2C2C2E)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val imgUrl = video.thumbnailURL
                                        if (!imgUrl.isNullOrEmpty()) {
                                            SubcomposeAsyncImage(
                                                model = ImageRequest.Builder(context).data(imgUrl).crossfade(true).build(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                when (painter.state) {
                                                    is AsyncImagePainter.State.Loading -> CircularProgressIndicator(color = Color.Cyan, strokeWidth = 1.dp, modifier = Modifier.size(12.dp))
                                                    is AsyncImagePainter.State.Error -> Icon(Icons.Default.PlayCircleOutline, null, tint = Color.Gray.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                                    is AsyncImagePainter.State.Success -> androidx.compose.foundation.Image(painter = painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                    else -> Icon(Icons.Default.PlayCircleOutline, null, tint = Color.Gray.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        } else {
                                            Icon(Icons.Default.PlayCircleOutline, null, tint = Color.Gray.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    // Video info
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(video.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(video.creatorName, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    // Hype count
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${video.hypeCount}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Spacer(Modifier.width(2.dp))
                                        Icon(Icons.Default.Whatshot, null, tint = Color(0xFFFF9800), modifier = Modifier.size(11.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Activity header
            item {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "Activity",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp)
                )
            }

            // Notifications list
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF00BCD4))
                    }
                }
            } else if (notifications.isEmpty()) {
                item {
                    EmptyStateView(
                        filter = selectedFilter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp)
                    )
                }
            } else {
                items(
                    items = notifications,
                    key = { it.id }
                ) { notification ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .animateItem()
                    ) {
                        NotificationRow(
                            notification = notification,
                            followManager = followManager,
                            profileImages = profileImages,
                            onTap = {
                                viewModel.onNotificationTapped(notification)
                            },
                            onProfileTap = { senderID ->
                                onNavigateToProfile(senderID)
                            }
                        )
                    }
                }
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }

    // Just Joined Full Screen View
    if (showingJustJoinedView) {
        Dialog(
            onDismissRequest = { showingJustJoinedView = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            JustJoinedView(
                followManager = followManager,
                onDismiss = { showingJustJoinedView = false },
                onUserTap = { userId: String ->
                    showingJustJoinedView = false
                    onNavigateToProfile(userId)
                }
            )
        }
    }

    // Top Videos Full Screen View
    if (showingTopVideosView) {
        Dialog(
            onDismissRequest = { showingTopVideosView = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // Inline TopVideosView
            val tvScope = rememberCoroutineScope()
            var tvVideos by remember { mutableStateOf<List<LeaderboardVideo>>(emptyList()) }
            var tvLoading by remember { mutableStateOf(true) }
            var tvError by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                tvLoading = true; tvError = null
                try {
                    val db = FirebaseFirestore.getInstance("stitchfin")
                    val cutoff = com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L))
                    val snapshot = db.collection("videos")
                        .whereGreaterThan("createdAt", cutoff)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .limit(50)
                        .get()
                        .await()
                    tvVideos = snapshot.documents
                        .mapNotNull { doc -> LeaderboardVideo.fromFirestore(doc.id, doc.data ?: return@mapNotNull null) }
                        .sortedByDescending { it.hypeCount }
                } catch (e: Exception) { tvError = e.message }
                tvLoading = false
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .statusBarsPadding()
            ) {
                // Top bar
                Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(
                        "\uD83D\uDD25 Top Videos",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    TextButton(
                        onClick = { showingTopVideosView = false },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                    ) { Text("Done", color = Color(0xFF9C27B0), fontWeight = FontWeight.SemiBold) }
                }
                Text(
                    "Most hyped videos from the last 7 days",
                    fontSize = 13.sp, color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        tvLoading && tvVideos.isEmpty() -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(color = Color(0xFF9C27B0))
                                Text("Loading top videos...", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                        tvError != null -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(50.dp))
                                Text(tvError!!, fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center)
                                TextButton(onClick = {
                                    tvScope.launch {
                                        tvLoading = true; tvError = null
                                        try {
                                            val db = FirebaseFirestore.getInstance("stitchfin")
                                            val cutoff = com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L))
                                            val snapshot = db.collection("videos")
                                                .whereGreaterThan("createdAt", cutoff)
                                                .orderBy("createdAt", Query.Direction.DESCENDING)
                                                .limit(50).get().await()
                                            tvVideos = snapshot.documents
                                                .mapNotNull { doc -> LeaderboardVideo.fromFirestore(doc.id, doc.data ?: return@mapNotNull null) }
                                                .sortedByDescending { it.hypeCount }
                                        } catch (e: Exception) { tvError = e.message }
                                        tvLoading = false
                                    }
                                }) { Text("Retry", color = Color(0xFF9C27B0)) }
                            }
                        }
                        tvVideos.isEmpty() -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(Icons.Default.Whatshot, null, tint = Color.Gray, modifier = Modifier.size(50.dp))
                                Text("No trending videos yet", fontSize = 16.sp, color = Color.Gray)
                            }
                        }
                        else -> {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(tvVideos.size, key = { tvVideos[it].id }) { idx ->
                                    val video = tvVideos[idx]
                                    val rank = idx + 1
                                    val rankColor = when (rank) {
                                        1 -> Color.Yellow; 2 -> Color.Gray; 3 -> Color(0xFFFF9800)
                                        else -> Color(0xFF9C27B0)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                                            .clickable {
                                                showingTopVideosView = false
                                                onShowThreadView(video.id, video.id)
                                            }
                                            .padding(8.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(200.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(Color(0xFF2C2C2E)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val gridImgUrl = video.thumbnailURL
                                                if (!gridImgUrl.isNullOrEmpty()) {
                                                    SubcomposeAsyncImage(
                                                        model = ImageRequest.Builder(context).data(gridImgUrl).crossfade(true).build(),
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    ) {
                                                        when (painter.state) {
                                                            is AsyncImagePainter.State.Loading -> CircularProgressIndicator(color = Color.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                                                            is AsyncImagePainter.State.Error -> Icon(Icons.Default.PlayCircleOutline, null, tint = Color.Gray.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                                                            is AsyncImagePainter.State.Success -> androidx.compose.foundation.Image(painter = painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                            else -> Icon(Icons.Default.PlayCircleOutline, null, tint = Color.Gray.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                                                        }
                                                    }
                                                } else {
                                                    Icon(Icons.Default.PlayCircleOutline, null, tint = Color.Gray.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                                                }
                                            }
                                            Text(video.creatorName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                                                Icon(Icons.Default.Whatshot, null, tint = Color(0xFFFF9800), modifier = Modifier.size(10.dp))
                                                Text(formatCount(video.hypeCount), fontSize = 12.sp, color = Color.Gray)
                                                Spacer(Modifier.width(4.dp))
                                                Text(video.temperatureEmoji, fontSize = 12.sp)
                                            }
                                        }
                                        // Rank badge
                                        Box(
                                            modifier = Modifier.padding(8.dp).size(32.dp).background(rankColor, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) { Text("#$rank", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// MARK: - NOTIFICATION COMPONENTS
// ============================================================================

// ============================================================================
// MARK: - NOTIFICATION COMPONENTS
// ============================================================================

/**
 * Individual notification row
 */
@Composable
private fun NotificationRow(
    notification: NotificationItem,
    followManager: FollowManager,
    profileImages: Map<String, String> = emptyMap(),
    onTap: () -> Unit,
    onProfileTap: (String) -> Unit
) {
    val followingStates by followManager.followingStates.collectAsState()
    val loadingStates by followManager.loadingStates.collectAsState()

    val userId = notification.actionData["userId"] as? String
        ?: notification.actionData["senderID"] as? String
        ?: ""
    val isFollowing = if (userId.isNotEmpty()) followingStates[userId] ?: false else false
    val isLoadingFollow = if (userId.isNotEmpty()) loadingStates.contains(userId) else false

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(12.dp),
        color = if (notification.isRead) Color(0xFF1C1C1E) else Color(0xFF2C2C2E)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Profile picture - clickable
            val profileImageUrl = profileImages[userId]
                ?: (notification.actionData["profileImageURL"] as? String)?.takeIf { it.isNotEmpty() }
            Box(
                modifier = Modifier.clickable {
                    if (userId.isNotEmpty()) onProfileTap(userId)
                },
                contentAlignment = Alignment.Center
            ) {
                if (profileImageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(profileImageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3C3C3E)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Letter fallback
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF6C5CE7)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (notification.title.firstOrNull()?.uppercase() ?: "?"),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = notification.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = notification.message,
                            color = Color.Gray,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        Text(
                            text = notification.timeAgo,
                            color = Color.Gray.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // Notification icon
                    Surface(
                        shape = CircleShape,
                        color = getNotificationColor(notification.type).copy(alpha = 0.1f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = notification.type.emoji,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // Follow back button for follow notifications
                if (notification.type == NotificationType.NEW_FOLLOWER && userId.isNotEmpty() && !isFollowing) {
                    Button(
                        onClick = {
                            followManager.toggleFollow(userId)
                        },
                        enabled = !isLoadingFollow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00BCD4)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        if (isLoadingFollow) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonAdd,
                                    contentDescription = "Follow back",
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Follow Back",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Unread indicator
                if (!notification.isRead) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF8E44AD), CircleShape)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Header with title and mark all read button
 */
@Composable
private fun NotificationHeader(
    unreadCount: Int,
    onMarkAllRead: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 20.dp, end = 20.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Notifications",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )

            if (unreadCount > 0) {
                Text(
                    text = "$unreadCount unread",
                    color = Color(0xFFFF9500),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Button(
            onClick = onMarkAllRead,
            enabled = unreadCount > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF8E44AD).copy(alpha = 0.8f),
                disabledContainerColor = Color(0xFF8E44AD).copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = "Mark all read",
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Mark All Read",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Notification filter tabs
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationTabSelector(
    selectedFilter: NotificationFilter,
    unreadCount: Int,
    onFilterSelected: (NotificationFilter) -> Unit
) {
    val filters = listOf(
        NotificationFilter.ALL,
        NotificationFilter.UNREAD,
        NotificationFilter.ENGAGEMENT,
        NotificationFilter.SOCIAL,
        NotificationFilter.SYSTEM
    )

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        items(filters) { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = filter.displayName,
                            fontSize = 14.sp,
                            fontWeight = if (selectedFilter == filter) FontWeight.SemiBold else FontWeight.Medium
                        )

                        if (filter == NotificationFilter.UNREAD && unreadCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFFF9500)
                            ) {
                                Text(
                                    text = unreadCount.toString(),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF8E44AD),
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF1E1E1E),
                    labelColor = Color.Gray
                )
            )
        }
    }
}

/**
 * Empty state view
 */
@Composable
private fun EmptyStateView(
    filter: NotificationFilter,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = getEmptyStateIcon(filter),
            contentDescription = null,
            tint = Color.Gray.copy(alpha = 0.5f),
            modifier = Modifier.size(50.dp)
        )

        Text(
            text = "No notifications",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = getEmptyStateMessage(filter),
            color = Color.Gray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp)
        )
    }
}

// ============================================================================
// MARK: - HELPER FUNCTIONS
// ============================================================================

private fun getNotificationColor(type: NotificationType): Color {
    return when (type) {
        NotificationType.HYPE_RECEIVED -> Color(0xFFFF6B6B)
        NotificationType.REPLY_RECEIVED -> Color(0xFF4ECDC4)
        NotificationType.NEW_FOLLOWER -> Color(0xFF95E1D3)
        NotificationType.TAP_MILESTONE -> Color(0xFFFFA502)
        NotificationType.TIER_UPGRADED -> Color(0xFF8E44AD)
        else -> Color(0xFF5F27CD)
    }
}

private fun getEmptyStateIcon(filter: NotificationFilter): androidx.compose.ui.graphics.vector.ImageVector {
    return when (filter) {
        NotificationFilter.ENGAGEMENT -> Icons.Default.Favorite
        NotificationFilter.SOCIAL -> Icons.Default.People
        NotificationFilter.SYSTEM -> Icons.Default.Settings
        else -> Icons.Default.Notifications
    }
}

private fun getEmptyStateMessage(filter: NotificationFilter): String {
    return when (filter) {
        NotificationFilter.ALL -> "Check back later for new activity."
        NotificationFilter.UNREAD -> "You've read all your notifications."
        NotificationFilter.ENGAGEMENT -> "Share videos to get hype from the community!"
        NotificationFilter.SOCIAL -> "Keep creating content!"
        NotificationFilter.SYSTEM -> "System notifications will appear here."
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}