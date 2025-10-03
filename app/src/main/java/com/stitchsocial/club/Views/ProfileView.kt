/*
 * ProfileView.kt - EXACT KOTLIN MATCH FOR SWIFT PROFILEVIEW
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Complete Profile matching ProfileView.swift exactly
 * Dependencies: UserService, VideoServiceImpl, EditProfileView, ProfileVideoGrid
 * Features: Optimized profile header, tab bar, video grid, thread navigation
 *
 * ✅ MATCHES: Swift ProfileView.swift structure and organization exactly
 * ✅ USES: CoreVideoMetadata consistently across all components
 * ✅ INCLUDES: All Swift sections - header, bio, stats, tabs, video grid
 * ✅ FIXED: Scrolling constraints issue
 * ✅ ADDED: Automatic refresh on video upload completion
 */

package com.stitchsocial.club.views

import com.stitchsocial.club.foundation.BasicVideoInfo
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

// Animation imports for shimmer effect
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Brush

// EXACT SWIFT IMPORTS MATCH
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.AuthService // ✅ Added for Settings
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.NavigationEvent

import kotlin.collections.find

/**
 * ProfileView - EXACT MATCH FOR SWIFT PROFILEVIEW
 *
 * STRUCTURE MATCHES:
 * - Dependencies section
 * - UI State section
 * - Video Deletion State section
 * - Bio State section
 * - Main body with ZStack pattern
 * - Profile content with ScrollView
 * - Optimized profile header (4 sections)
 * - Tab bar section
 * - Video grid section
 * - All helper functions in same order
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileView(
    userID: String,
    navigationCoordinator: NavigationCoordinator? = null, // ADDED: For automatic refresh
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // MARK: - Dependencies (MATCHES SWIFT)
    val userService = remember { UserService(context) }
    val videoService = remember { VideoServiceImpl() }
    val authService = remember { AuthService() } // ✅ Added for Settings

    // MARK: - UI State (MATCHES SWIFT)
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var showStickyTabBar by remember { mutableStateOf(false) }
    var showingFollowingList by remember { mutableStateOf(false) }
    var showingFollowersList by remember { mutableStateOf(false) }
    var showingSettings by remember { mutableStateOf(false) } // ✅ Settings modal state
    var showingEditProfile by remember { mutableStateOf(false) }
    var showingVideoPlayer by remember { mutableStateOf(false) }
    var selectedVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var selectedVideoIndex by remember { mutableIntStateOf(0) }

    // MARK: - Video Deletion State (MATCHES SWIFT)
    var showingDeleteConfirmation by remember { mutableStateOf(false) }
    var videoToDelete by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var isDeletingVideo by remember { mutableStateOf(false) }

    // MARK: - Bio State (MATCHES SWIFT)
    var isShowingFullBio by remember { mutableStateOf(false) }

    // MARK: - Profile Data State
    var currentUser by remember { mutableStateOf<BasicUserInfo?>(null) }
    var userVideos by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingVideos by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // MARK: - Tab Configuration (MATCHES SWIFT)
    val tabTitles = listOf("Videos", "Threads", "Replies")
    val tabIcons = listOf("videocam", "forum", "reply")

    // Filter videos based on selected tab (MATCHES SWIFT LOGIC)
    val filteredVideos = when (selectedTab) {
        0 -> userVideos // All videos
        1 -> userVideos.filter { it.contentType == ContentType.THREAD }
        2 -> userVideos.filter { it.contentType == ContentType.CHILD }
        else -> userVideos
    }

    // MARK: - Data Loading Functions
    suspend fun loadProfile() {
        try {
            isLoading = true
            currentUser = userService.getUserProfile(userID)
        } catch (e: Exception) {
            errorMessage = "Failed to load profile: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    suspend fun loadVideos() {
        try {
            isLoadingVideos = true
            println("📱 PROFILE: Loading videos for user $userID")
            userVideos = videoService.getUserVideos(userID)
            println("📱 PROFILE: Loaded ${userVideos.size} videos")
        } catch (e: Exception) {
            println("❌ PROFILE: Failed to load videos: ${e.message}")
            // Handle error silently, keep empty list
        } finally {
            isLoadingVideos = false
        }
    }

    // ADDED: NavigationCoordinator Event Listening for Automatic Refresh
    LaunchedEffect(navigationCoordinator) {
        navigationCoordinator?.navigationEvents?.collect { event ->
            when (event) {
                is NavigationEvent.VideoUploadComplete -> {
                    println("🎉 PROFILE: Video upload completed - refreshing video list")
                    println("📹 PROFILE: New video ID: ${event.videoId}")

                    // Automatically refresh videos after successful upload
                    loadVideos()
                }
                is NavigationEvent.VideoUploadError -> {
                    println("❌ PROFILE: Video upload failed: ${event.error}")
                }
                else -> {
                    // Handle other navigation events if needed
                }
            }
        }
    }

    // Load data on composition
    LaunchedEffect(userID) {
        loadProfile()
        loadVideos()
    }

    // MARK: - Main Body (MATCHES SWIFT ZSTACK PATTERN) - FIXED SCROLLING
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Main Profile Content
        when {
            isLoading -> LoadingView()
            errorMessage != null -> ErrorView(error = errorMessage!!)
            currentUser != null -> ProfileContent(
                user = currentUser!!,
                videos = filteredVideos,
                selectedTab = selectedTab,
                tabTitles = tabTitles,
                tabIcons = tabIcons,
                isLoadingVideos = isLoadingVideos,
                isShowingFullBio = isShowingFullBio,
                onTabSelected = { selectedTab = it },
                onToggleBio = { isShowingFullBio = !isShowingFullBio },
                onEditProfile = { showingEditProfile = true },
                onSettingsClick = { showingSettings = true }, // ✅ Added Settings callback
                onVideoTap = { video, index, videos ->
                    selectedVideo = video
                    selectedVideoIndex = index
                    showingVideoPlayer = true
                },
                onVideoDelete = { video ->
                    videoToDelete = video
                    showingDeleteConfirmation = true
                }
            )
            else -> NoUserView()
        }

        // Settings Overlay - ✅ NEW (Full Screen on Top)
        if (showingSettings && currentUser != null) {
            // Capture current user to prevent recomposition issues
            val settingsUser = currentUser
            SettingsView(
                currentUser = settingsUser!!,
                authService = authService,
                onDismiss = { showingSettings = false },
                onSignOutSuccess = {
                    // Dismiss settings first, then auth state will handle navigation
                    scope.launch {
                        showingSettings = false
                        // Small delay to ensure modal dismisses before auth state changes
                        kotlinx.coroutines.delay(100)
                    }
                }
            )
        }
    }

    // MARK: - Sheet Modals (MATCHES SWIFT SHEET STRUCTURE)

    // Edit Profile Sheet
    if (showingEditProfile && currentUser != null) {
        EditProfileView(
            userID = userID,
            currentUserName = currentUser?.displayName ?: "",
            currentUsername = currentUser?.username ?: "",
            currentUserImage = currentUser?.profileImageURL,
            currentBio = currentUser?.bio ?: "",
            onCancel = { showingEditProfile = false },
            onSave = { displayName, bio, username, imageUri ->
                scope.launch {
                    try {
                        userService.updateProfileWithImage(
                            userID = userID,
                            displayName = displayName,
                            bio = bio,
                            imageUri = imageUri
                        )
                        loadProfile() // Reload after update
                        showingEditProfile = false
                    } catch (e: Exception) {
                        // Handle error
                    }
                }
            }
        )
    }

    // Full Screen Video Player
    if (showingVideoPlayer && selectedVideo != null) {
        VideoPlayerView(
            video = selectedVideo!!,
            onClose = {
                showingVideoPlayer = false
                selectedVideo = null
            },
            onNavigateToProfile = { creatorId ->
                // Handle profile navigation
                println("Navigate to profile: $creatorId")
            }
        )
    }

    // Delete Confirmation Alert
    if (showingDeleteConfirmation && videoToDelete != null) {
        AlertDialog(
            onDismissRequest = { showingDeleteConfirmation = false },
            title = { Text("Delete Video") },
            text = {
                Text("Are you sure you want to delete '${videoToDelete?.title}'? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // Perform delete
                            videoToDelete?.let { video ->
                                userVideos = userVideos.filter { it.id != video.id }
                            }
                            showingDeleteConfirmation = false
                            videoToDelete = null
                        }
                    }
                ) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showingDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// MARK: - Profile Content (MATCHES SWIFT profileContent) - FIXED: Single LazyColumn

@Composable
private fun ProfileContent(
    user: BasicUserInfo,
    videos: List<CoreVideoMetadata>,
    selectedTab: Int,
    tabTitles: List<String>,
    tabIcons: List<String>,
    isLoadingVideos: Boolean,
    isShowingFullBio: Boolean,
    onTabSelected: (Int) -> Unit,
    onToggleBio: () -> Unit,
    onEditProfile: () -> Unit,
    onSettingsClick: () -> Unit, // ✅ Added Settings callback
    onVideoTap: (CoreVideoMetadata, Int, List<CoreVideoMetadata>) -> Unit,
    onVideoDelete: (CoreVideoMetadata) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        // Profile Header
        item {
            OptimizedProfileHeader(
                user = user,
                isShowingFullBio = isShowingFullBio,
                onToggleBio = onToggleBio,
                onEditProfile = onEditProfile,
                onSettingsClick = onSettingsClick // ✅ Pass Settings callback
            )
        }

        // Tab Bar Section
        item {
            TabBarSection(
                tabTitles = tabTitles,
                tabIcons = tabIcons,
                selectedTab = selectedTab,
                videos = videos,
                allVideos = videos, // Pass all videos for counting
                onTabSelected = onTabSelected
            )
        }

        // Video Grid Section - Using ProfileVideoGrid component
        item {
            // Convert CoreVideoMetadata to BasicVideoInfo for ProfileVideoGrid compatibility
            val basicVideoInfoList = videos.map { video ->
                BasicVideoInfo(
                    id = video.id,
                    title = video.title,
                    videoURL = video.videoURL,
                    thumbnailURL = video.thumbnailURL,
                    duration = video.duration,
                    createdAt = video.createdAt,
                    contentType = video.contentType,
                    temperature = video.temperature
                )
            }

            ProfileVideoGrid(
                videos = basicVideoInfoList,
                selectedTab = selectedTab,
                tabTitles = tabTitles,
                isLoading = isLoadingVideos,
                isCurrentUserProfile = true,
                onVideoTap = { basicVideo, index, basicVideos ->
                    // Convert back to CoreVideoMetadata for callback
                    val originalVideo = videos.find { it.id == basicVideo.id }
                    originalVideo?.let {
                        onVideoTap(it, index, videos)
                    }
                },
                onVideoDelete = { basicVideo ->
                    // Convert back to CoreVideoMetadata for callback
                    val originalVideo = videos.find { it.id == basicVideo.id }
                    originalVideo?.let { onVideoDelete(it) }
                }
            )
        }
    }
}



// MARK: - Optimized Profile Header (MATCHES SWIFT 4-SECTION STRUCTURE)

@Composable
private fun OptimizedProfileHeader(
    user: BasicUserInfo,
    isShowingFullBio: Boolean,
    onToggleBio: () -> Unit,
    onEditProfile: () -> Unit,
    onSettingsClick: () -> Unit // ✅ Added Settings callback
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Section 0: Settings Button - ✅ NEW TOP RIGHT BUTTON
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Section 1: Profile Image & Basic Info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Enhanced Profile Image with Progress Ring
            Box(contentAlignment = Alignment.Center) {
                // Progress ring background
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .background(Color.Transparent, CircleShape)
                        .border(3.dp, Color.Gray.copy(alpha = 0.3f), CircleShape)
                )

                // Profile image
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(user.profileImageURL ?: "")
                        .crossfade(true)
                        .build(),
                    contentDescription = "Profile Picture",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.Gray.copy(alpha = 0.3f)),
                    contentScale = ContentScale.Crop
                )
            }

            // Name and verification info
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Name and verification
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = user.displayName,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (user.isVerified) {
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = "Verified",
                            tint = Color.Red,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Username
                Text(
                    text = "@${user.username}",
                    color = Color.Gray,
                    fontSize = 14.sp
                )

                // Tier badge
                TierBadge(tier = user.tier)
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        // Section 2: Bio (Full Width)
        if (shouldShowBio(user)) {
            BioSection(
                user = user,
                isShowingFullBio = isShowingFullBio,
                onToggleBio = onToggleBio,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        // Section 3: Hype Rating Bar (NEW)
        HypeRatingSection(
            user = user,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        // Section 4: Stats Row
        StatsRow(
            user = user,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        // Section 5: Action Buttons
        ActionButtonsRow(
            user = user,
            onEditProfile = onEditProfile,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

// MARK: - Hype Rating Section (MATCHES SWIFT)

@Composable
private fun HypeRatingSection(
    user: BasicUserInfo,
    modifier: Modifier = Modifier
) {
    val hypeRating = calculateHypeRating(user)
    val progress = (hypeRating / 100f).coerceIn(0f, 1f)

    // Shimmer animation
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -200f,
        targetValue = 200f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(12.dp)
            )
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Title and percentage row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.LocalFireDepartment,
                    contentDescription = "Hype",
                    tint = Color(0xFFFFA500), // Orange
                    modifier = Modifier.size(14.dp)
                )

                Text(
                    text = "Hype Rating",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = "${hypeRating.toInt()}%",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Progress bar with gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
        ) {
            // Background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Gray.copy(alpha = 0.2f),
                        RoundedCornerShape(6.dp)
                    )
            )

            // Progress fill with gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(12.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF00FF00), // Green
                                Color(0xFFFFFF00), // Yellow
                                Color(0xFFFFA500), // Orange
                                Color(0xFFFF0000), // Red
                                Color(0xFF800080)  // Purple
                            )
                        ),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clip(RoundedCornerShape(6.dp))
            ) {
                // Shimmer overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.6f),
                                    Color.Transparent
                                ),
                                startX = shimmerOffset - 100f,
                                endX = shimmerOffset + 100f
                            )
                        )
                )
            }
        }
    }
}

// MARK: - Hype Rating Calculation (MATCHES SWIFT)

private fun calculateHypeRating(user: BasicUserInfo): Float {
    // Base rating from tier
    val tierBaseRating = when (user.tier) {
        UserTier.FOUNDER, UserTier.CO_FOUNDER -> 63f
        UserTier.TOP_CREATOR -> 57f
        UserTier.PARTNER -> 50f
        UserTier.INFLUENCER -> 43f
        UserTier.RISING -> 33f
        UserTier.ROOKIE -> 23f
        else -> 17f
    }

    // Engagement score (based on clout)
    val engagementScore = (user.clout ?: 0) / 1000f * 10f // Scale clout to engagement points

    // Activity score (placeholder - could be based on recent video count)
    val activityScore = (user.videoCount ?: 0) / 10f * 5f

    // Clout bonus
    val cloutBonus = (user.clout ?: 0) / 10000f * 15f

    // Social bonus (followers/following ratio)
    val followers = (user.followerCount ?: 0).toFloat()
    val following = (user.followingCount ?: 1).toFloat() // Avoid division by zero
    val socialBonus = (followers / following).coerceAtMost(3f) * 3f

    // Verification bonus
    val verificationBonus = if (user.isVerified) 5f else 0f

    // Calculate final rating
    val baseRating = tierBaseRating
    val bonusPoints = engagementScore + activityScore + cloutBonus + socialBonus + verificationBonus
    val finalRating = (baseRating / 100f * 50f) + bonusPoints

    // Clamp between 0-100
    return finalRating.coerceIn(0f, 100f)
}

@Composable
private fun BioSection(
    user: BasicUserInfo,
    isShowingFullBio: Boolean,
    onToggleBio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bio = user.bio ?: ""
    if (bio.isNotEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = bio,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = if (isShowingFullBio) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )

            if (bio.length > 80) {
                Text(
                    text = if (isShowingFullBio) "Show less" else "Show more",
                    color = Color.Cyan,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onToggleBio() }
                )
            }
        }
    }
}

// MARK: - Stats Row (MATCHES SWIFT)

@Composable
private fun StatsRow(
    user: BasicUserInfo,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatItem(count = user.followerCount ?: 0, label = "Followers")
        StatItem(count = user.followingCount ?: 0, label = "Following")
        StatItem(count = user.videoCount ?: 0, label = "Videos")
        StatItem(count = user.clout ?: 0, label = "Hype")
    }
}

@Composable
private fun StatItem(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatCount(count),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}

// MARK: - Action Buttons Row (MATCHES SWIFT)

@Composable
private fun ActionButtonsRow(
    user: BasicUserInfo,
    onEditProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Edit Profile Button (for own profile)
        Button(
            onClick = onEditProfile,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Gray.copy(alpha = 0.8f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Edit Profile",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Message Button (placeholder)
        Button(
            onClick = { /* Future implementation */ },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Gray.copy(alpha = 0.8f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Message",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// MARK: - Tab Bar Section (MATCHES SWIFT)

@Composable
private fun TabBarSection(
    tabTitles: List<String>,
    tabIcons: List<String>,
    selectedTab: Int,
    videos: List<CoreVideoMetadata>,
    allVideos: List<CoreVideoMetadata>,
    onTabSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabTitles.forEachIndexed { index, title ->
            TabBarItem(
                index = index,
                title = title,
                icon = tabIcons.getOrNull(index) ?: "videocam",
                isSelected = selectedTab == index,
                count = getTabCount(index, allVideos),
                onTabSelected = onTabSelected
            )
        }
    }
}

@Composable
private fun TabBarItem(
    index: Int,
    title: String,
    icon: String,
    isSelected: Boolean,
    count: Int,
    onTabSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .clickable { onTabSelected(index) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Icon(
                imageVector = when(icon) {
                    "videocam" -> Icons.Default.VideoLibrary
                    "forum" -> Icons.Default.Forum
                    "reply" -> Icons.Default.Reply
                    else -> Icons.Default.VideoLibrary
                },
                contentDescription = title,
                tint = if (isSelected) Color.Cyan.copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.6f),
                modifier = Modifier.size(12.dp)
            )

            // Title
            Text(
                text = title,
                color = if (isSelected) Color.Cyan else Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            // Count
            Text(
                text = "($count)",
                color = if (isSelected) Color.Cyan.copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }

        // Underline indicator
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(2.dp)
                .background(
                    if (isSelected) Color.Cyan else Color.Transparent,
                    RoundedCornerShape(1.dp)
                )
        )
    }
}

// MARK: - Helper Components (MATCHES SWIFT)

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Color.Cyan)
    }
}

@Composable
private fun ErrorView(error: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Error: $error",
            color = Color.Red,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NoUserView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "User not found",
            color = Color.Gray,
            fontSize = 18.sp
        )
    }
}

@Composable
private fun TierBadge(tier: UserTier) {
    val (color, text) = when (tier) {
        UserTier.ROOKIE -> Color(0xFF808080) to "ROOKIE"
        UserTier.RISING -> Color(0xFF00FF00) to "RISING"
        UserTier.VETERAN -> Color(0xFF4169E1) to "VETERAN"
        UserTier.INFLUENCER -> Color(0xFF1E90FF) to "INFLUENCER"
        UserTier.ELITE -> Color(0xFF8A2BE2) to "ELITE"
        UserTier.PARTNER -> Color(0xFFFF1493) to "PARTNER"
        UserTier.LEGENDARY -> Color(0xFFFF4500) to "LEGENDARY"
        UserTier.TOP_CREATOR -> Color(0xFFFF8C00) to "TOP CREATOR"
        UserTier.FOUNDER -> Color(0xFFFFD700) to "FOUNDER"
        UserTier.CO_FOUNDER -> Color(0xFFDDC93B) to "CO-FOUNDER"
    }

    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// MARK: - Helper Functions (MATCHES SWIFT)

private fun shouldShowBio(user: BasicUserInfo): Boolean {
    return !user.bio.isNullOrEmpty()
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

private fun getTabCount(index: Int, videos: List<CoreVideoMetadata>): Int {
    return when (index) {
        0 -> videos.size // All videos
        1 -> videos.count { it.contentType == ContentType.THREAD }
        2 -> videos.count { it.contentType == ContentType.CHILD }
        else -> 0
    }
}