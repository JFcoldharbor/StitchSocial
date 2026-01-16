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

import android.net.Uri
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
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
import com.stitchsocial.club.services.AuthService

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

    // Discovery state
    var recentUsers by remember { mutableStateOf<List<RecentUser>>(emptyList()) }
    var leaderboardVideos by remember { mutableStateOf<List<LeaderboardVideo>>(emptyList()) }
    var isLoadingDiscovery by remember { mutableStateOf(false) }
    var currentAvatarIndex by remember { mutableStateOf(0) }

    // Navigation state
    var selectedUserID by remember { mutableStateOf<String?>(null) }
    var showingProfile by remember { mutableStateOf(false) }
    var selectedVideoID by remember { mutableStateOf<String?>(null) }
    var selectedThreadID by remember { mutableStateOf<String?>(null) }
    var showingVideoThread by remember { mutableStateOf(false) }

    // Observe navigation events from ViewModel
    LaunchedEffect(viewModel) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is NotificationNavigationEvent.NavigateToProfile -> {
                    selectedUserID = event.userId
                    showingProfile = true
                }
                is NotificationNavigationEvent.NavigateToVideo -> {
                    selectedVideoID = event.videoId
                    selectedThreadID = event.threadId
                    showingVideoThread = true
                }
                is NotificationNavigationEvent.NavigateToThread -> {
                    selectedThreadID = event.threadId
                    selectedVideoID = event.threadId
                    showingVideoThread = true
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

    // Auto-scroll timer with cleanup
    DisposableEffect(recentUsers) {
        val job = scope.launch {
            while (true) {
                delay(3000)
                if (recentUsers.isNotEmpty()) {
                    currentAvatarIndex = (currentAvatarIndex + 1) % recentUsers.size
                }
            }
        }

        onDispose {
            job.cancel()
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

            // Discovery section - Side by side layout
            item {
                DiscoverySideBySide(
                    recentUsers = recentUsers,
                    leaderboardVideos = leaderboardVideos,
                    currentAvatarIndex = currentAvatarIndex,
                    isLoading = isLoadingDiscovery,
                    onUserTapped = { userId ->
                        selectedUserID = userId
                        showingProfile = true
                    },
                    onVideoTapped = { videoId ->
                        selectedVideoID = videoId
                        selectedThreadID = videoId
                        showingVideoThread = true
                    }
                )
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
                            onTap = {
                                viewModel.onNotificationTapped(notification)
                            },
                            onProfileTap = { senderID ->
                                selectedUserID = senderID
                                showingProfile = true
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

    // Profile Modal - ENHANCED
    if (showingProfile && selectedUserID != null) {
        EnhancedProfileModal(
            userId = selectedUserID!!,
            followManager = followManager,
            onDismiss = {
                showingProfile = false
                selectedUserID = null
            },
            onVideoTap = { videoId ->
                // Navigate to video from profile
                showingProfile = false
                selectedUserID = null
                selectedVideoID = videoId
                selectedThreadID = videoId
                showingVideoThread = true
            }
        )
    }

    // Video/Thread Modal - ENHANCED
    if (showingVideoThread && (selectedVideoID != null || selectedThreadID != null)) {
        EnhancedVideoModal(
            videoId = selectedVideoID,
            threadId = selectedThreadID ?: selectedVideoID,
            onDismiss = {
                showingVideoThread = false
                selectedVideoID = null
                selectedThreadID = null
            },
            onCreatorTap = { creatorId ->
                // Navigate to creator profile from video
                showingVideoThread = false
                selectedVideoID = null
                selectedThreadID = null
                selectedUserID = creatorId
                showingProfile = true
            }
        )
    }
}

// ============================================================================
// MARK: - ENHANCED PROFILE MODAL
// ============================================================================

/**
 * Enhanced profile modal with follow button, stats, and video grid
 */
@Composable
private fun EnhancedProfileModal(
    userId: String,
    followManager: FollowManager,
    onDismiss: () -> Unit,
    onVideoTap: (String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            EnhancedProfileContent(
                userId = userId,
                followManager = followManager,
                onDismiss = onDismiss,
                onVideoTap = onVideoTap
            )
        }
    }
}

@Composable
private fun EnhancedProfileContent(
    userId: String,
    followManager: FollowManager,
    onDismiss: () -> Unit,
    onVideoTap: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userService = remember { UserService(context) }

    var user by remember { mutableStateOf<BasicUserInfo?>(null) }
    var userVideos by remember { mutableStateOf<List<VideoGridItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingVideos by remember { mutableStateOf(false) }

    // Follow state
    val followingStates by followManager.followingStates.collectAsState()
    val loadingStates by followManager.loadingStates.collectAsState()
    val isFollowing = followingStates[userId] ?: false
    val isLoadingFollow = loadingStates.contains(userId)

    // Check if this is current user's profile
    val authService = remember { AuthService() }
    val currentUserId = authService.currentUser.value?.uid ?: ""
    val isOwnProfile = userId == currentUserId

    // Load user profile
    LaunchedEffect(userId) {
        isLoading = true
        try {
            user = userService.getUserProfile(userId)
            // Load follow state
            followManager.loadFollowStates(listOf(userId))
        } catch (e: Exception) {
            println("❌ Failed to load user: ${e.message}")
        }
        isLoading = false

        // Load user videos
        isLoadingVideos = true
        try {
            val db = FirebaseFirestore.getInstance("stitchfin")
            val videoDocs = db.collection("videos")
                .whereEqualTo("creatorID", userId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(12)
                .get()
                .await()

            userVideos = videoDocs.documents.mapNotNull { doc ->
                VideoGridItem(
                    id = doc.id,
                    thumbnailUrl = doc.getString("thumbnailURL"),
                    title = doc.getString("title") ?: "",
                    hypeCount = doc.getLong("hypeCount")?.toInt() ?: 0
                )
            }
        } catch (e: Exception) {
            println("❌ Failed to load user videos: ${e.message}")
        }
        isLoadingVideos = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
    ) {
        // Close button row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 100.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF00BCD4))
            }
        } else if (user != null) {
            // Profile header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Profile image
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(user!!.profileImageURL?.ifEmpty { null })
                        .crossfade(true)
                        .build(),
                    contentDescription = user!!.username,
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2C2C2E)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Display name
                Text(
                    text = user!!.displayName,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                // Username
                Text(
                    text = "@${user!!.username}",
                    color = Color.Gray,
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Tier badge
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF00BCD4).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = user!!.tier.displayName,
                        color = Color(0xFF00BCD4),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatColumn(count = user!!.followerCount, label = "Followers")
                    StatColumn(count = user!!.followingCount, label = "Following")
                    StatColumn(count = user!!.clout, label = "Clout")
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Follow button (only show if not own profile)
                if (!isOwnProfile) {
                    Button(
                        onClick = {
                            scope.launch {
                                followManager.toggleFollow(userId)
                            }
                        },
                        enabled = !isLoadingFollow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFollowing) Color(0xFF3C3C3E) else Color(0xFF00BCD4)
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (isLoadingFollow) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isFollowing) Icons.Default.Check else Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (isFollowing) "Following" else "Follow",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Bio
                if (!user!!.bio.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = user!!.bio!!,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Videos section header
            Text(
                text = "Videos",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )

            // Video grid
            if (isLoadingVideos) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF00BCD4))
                }
            } else if (userVideos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No videos yet",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                // Video grid (3 columns)
                val rows = userVideos.chunked(3)
                rows.forEach { rowVideos ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowVideos.forEach { video ->
                            VideoGridTile(
                                video = video,
                                modifier = Modifier.weight(1f),
                                onClick = { onVideoTap(video.id) }
                            )
                        }
                        // Fill empty slots
                        repeat(3 - rowVideos.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 100.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "User not found",
                    color = Color.Gray,
                    fontSize = 16.sp
                )
            }
        }
    }
}

// ============================================================================
// MARK: - ENHANCED VIDEO MODAL
// ============================================================================

/**
 * Enhanced video modal with actual video player
 */
@Composable
private fun EnhancedVideoModal(
    videoId: String?,
    threadId: String?,
    onDismiss: () -> Unit,
    onCreatorTap: (String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            EnhancedVideoContent(
                videoId = videoId,
                threadId = threadId,
                onDismiss = onDismiss,
                onCreatorTap = onCreatorTap
            )
        }
    }
}

@Composable
private fun EnhancedVideoContent(
    videoId: String?,
    threadId: String?,
    onDismiss: () -> Unit,
    onCreatorTap: (String) -> Unit
) {
    val context = LocalContext.current

    // Video data state
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var videoTitle by remember { mutableStateOf<String?>(null) }
    var creatorId by remember { mutableStateOf<String?>(null) }
    var creatorName by remember { mutableStateOf<String?>(null) }
    var creatorImageUrl by remember { mutableStateOf<String?>(null) }
    var hypeCount by remember { mutableStateOf(0) }
    var coolCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Load video data
    LaunchedEffect(videoId) {
        isLoading = true
        loadError = null
        try {
            if (videoId != null) {
                val db = FirebaseFirestore.getInstance("stitchfin")
                val videoDoc = db.collection("videos").document(videoId).get().await()
                if (videoDoc.exists()) {
                    videoUrl = videoDoc.getString("videoURL")
                    videoTitle = videoDoc.getString("title")
                    creatorId = videoDoc.getString("creatorID")
                    creatorName = videoDoc.getString("creatorName")
                    creatorImageUrl = videoDoc.getString("creatorProfileImageURL")
                    hypeCount = videoDoc.getLong("hypeCount")?.toInt() ?: 0
                    coolCount = videoDoc.getLong("coolCount")?.toInt() ?: 0

                    if (videoUrl.isNullOrEmpty()) {
                        loadError = "Video URL not available"
                    }
                } else {
                    loadError = "Video not found"
                }
            }
        } catch (e: Exception) {
            println("❌ Failed to load video: ${e.message}")
            loadError = "Failed to load video"
        }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF00BCD4))
            }
        } else if (loadError != null || videoUrl.isNullOrEmpty()) {
            // Error state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = loadError ?: "Video unavailable",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Video player
            VideoPlayerView(
                videoUrl = videoUrl!!,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Overlay UI
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar with close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Title
                if (!videoTitle.isNullOrEmpty()) {
                    Text(
                        text = videoTitle!!,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                    )
                }

                Spacer(modifier = Modifier.size(48.dp)) // Balance
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom info bar
            if (!isLoading && loadError == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                            )
                        )
                        .padding(16.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Creator info (clickable)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                creatorId?.let { onCreatorTap(it) }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(creatorImageUrl?.ifEmpty { null })
                                .crossfade(true)
                                .build(),
                            contentDescription = "Creator",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2C2C2E)),
                            contentScale = ContentScale.Crop
                        )

                        Column {
                            Text(
                                text = "@${creatorName ?: "unknown"}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Tap to view profile",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Engagement stats
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "🔥",
                                fontSize = 20.sp
                            )
                            Text(
                                text = formatCount(hypeCount),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "❄️",
                                fontSize = 20.sp
                            )
                            Text(
                                text = formatCount(coolCount),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Video player using ExoPlayer
 */
@Composable
private fun VideoPlayerView(
    videoUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    // Set video source
    LaunchedEffect(videoUrl) {
        val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        modifier = modifier
    )
}

// ============================================================================
// MARK: - HELPER COMPOSABLES
// ============================================================================

data class VideoGridItem(
    val id: String,
    val thumbnailUrl: String?,
    val title: String,
    val hypeCount: Int
)

@Composable
private fun VideoGridTile(
    video: VideoGridItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2C2C2E))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(video.thumbnailUrl?.ifEmpty { null })
                .crossfade(true)
                .build(),
            contentDescription = video.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Hype count overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = "🔥", fontSize = 10.sp)
                Text(
                    text = formatCount(video.hypeCount),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StatColumn(
    count: Int,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = formatCount(count),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}

// ============================================================================
// MARK: - DISCOVERY SECTION
// ============================================================================

/**
 * Side-by-side discovery layout: Just Joined | Top Videos
 */
@Composable
private fun DiscoverySideBySide(
    recentUsers: List<RecentUser>,
    leaderboardVideos: List<LeaderboardVideo>,
    currentAvatarIndex: Int,
    isLoading: Boolean,
    onUserTapped: (String) -> Unit,
    onVideoTapped: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Just Joined section
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Just Joined",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF00FF00).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "New",
                        color = Color(0xFF00FF00),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF00BCD4),
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else if (recentUsers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No new users yet",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(200.dp)
                ) {
                    recentUsers.take(3).forEachIndexed { index, user ->
                        val isHighlighted = index == (currentAvatarIndex % maxOf(recentUsers.size, 1))
                        UserRow(
                            user = user,
                            isHighlighted = isHighlighted,
                            onClick = { onUserTapped(user.id) }
                        )
                    }
                }
            }
        }

        // Top Videos section
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Top Videos",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "7d",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF00BCD4),
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else if (leaderboardVideos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No videos yet",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(200.dp)
                ) {
                    leaderboardVideos.take(3).forEachIndexed { index, video ->
                        VideoRow(
                            video = video,
                            rank = index + 1,
                            onClick = { onVideoTapped(video.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * User row for Just Joined section
 */
@Composable
private fun UserRow(
    user: RecentUser,
    isHighlighted: Boolean,
    onClick: () -> Unit
) {
    val scale = if (isHighlighted) 1.0f else 0.95f
    val alpha = if (isHighlighted) 1.0f else 0.6f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(user.profileImageURL?.ifEmpty { null })
                    .crossfade(true)
                    .build(),
                contentDescription = user.username,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2C2C2E)),
                contentScale = ContentScale.Crop
            )

            if (isHighlighted) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(2.dp, Color(0xFF00BCD4), CircleShape)
                )
            }
        }

        Column {
            Text(
                text = user.username,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Joined ${user.formatJoinedDate()}",
                color = Color.Gray,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Video row for Top Videos section
 */
@Composable
private fun VideoRow(
    video: LeaderboardVideo,
    rank: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank badge
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(getRankColor(rank), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = rank.toString(),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "@${video.creatorName}",
                color = Color.Gray,
                fontSize = 10.sp
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = video.temperatureEmoji,
                fontSize = 14.sp
            )
            Text(
                text = video.hypeCount.toString(),
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

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
            val profileImageUrl = (notification.actionData["profileImageURL"] as? String) ?: ""
            Box(
                modifier = Modifier.clickable {
                    if (userId.isNotEmpty()) onProfileTap(userId)
                }
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(profileImageUrl.ifEmpty { null })
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3C3C3E)),
                    contentScale = ContentScale.Crop
                )
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

private fun getRankColor(rank: Int): Color {
    return when (rank) {
        1 -> Color(0xFFFFD700) // Gold
        2 -> Color(0xFFC0C0C0) // Silver
        3 -> Color(0xFFCD7F32) // Bronze
        else -> Color(0xFF8E44AD)
    }
}

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