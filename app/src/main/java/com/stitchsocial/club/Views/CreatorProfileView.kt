/*
 * CreatorProfileView.kt - EXTERNAL USER PROFILE DISPLAY
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - External User Profile Display (View-Only)
 * Dependencies: UserService, FollowManager, VideoService
 * Features: Follow/unfollow, share, report, video grid
 * PORTED FROM: iOS CreatorProfileView.swift
 */

package com.stitchsocial.club.views

import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

// Foundation imports
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.FollowManager
import androidx.compose.runtime.collectAsState

/**
 * External user profile view - displays another user's profile
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorProfileView(
    userID: String,
    currentUserID: String? = null,
    navigationCoordinator: NavigationCoordinator? = null,
    onDismiss: () -> Unit = {},
    onVideoTap: ((CoreVideoMetadata) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Services
    val userService = remember { UserService(context) }
    val videoService = remember { VideoServiceImpl() }
    val followManager = remember { FollowManager(context) }

    // Observe follow states from FollowManager
    val followingStates by followManager.followingStates.collectAsState()
    val loadingStates by followManager.loadingStates.collectAsState()

    // State
    var user by remember { mutableStateOf<BasicUserInfo?>(null) }
    var userVideos by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var showingFullBio by remember { mutableStateOf(false) }
    var showStitchersSheet by remember { mutableStateOf(false) }

    // Stats
    var stitchersCount by remember { mutableStateOf(0) }
    var cloutCount by remember { mutableStateOf(0) }

    // Derived follow state from FollowManager
    val isFollowing = followingStates[userID] ?: false
    val isFollowLoading = loadingStates.contains(userID)

    // Check if this is own profile
    val isOwnProfile = currentUserID != null && userID == currentUserID

    // Load user profile
    LaunchedEffect(userID) {
        try {
            isLoading = true
            errorMessage = null

            println("👤 CREATOR PROFILE: Loading profile for $userID")

            // Load user data
            val loadedUser: BasicUserInfo? = userService.getUserProfile(userID) as? BasicUserInfo
            if (loadedUser != null) {
                user = loadedUser
                println("✅ CREATOR PROFILE: Loaded user ${loadedUser.username}")

                // Load follow state via FollowManager
                followManager.loadFollowState(userID)

                // Load stitchers (followers) count
                val stitchers = userService.getFollowers(userID)
                stitchersCount = stitchers.size

                // Get clout from user profile
                cloutCount = loadedUser.clout

                // Load user videos
                val videos = videoService.getUserVideos(userID, limit = 30)
                userVideos = videos
                println("📹 CREATOR PROFILE: Loaded ${videos.size} videos")
            } else {
                errorMessage = "User not found"
            }
        } catch (e: Exception) {
            println("🚨 CREATOR PROFILE: Error - ${e.message}")
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    // Update follower count when follow state changes
    LaunchedEffect(isFollowing) {
        // This will update the UI when follow state changes
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            isLoading -> LoadingView()
            errorMessage != null -> ErrorView(error = errorMessage!!, onRetry = {
                scope.launch {
                    isLoading = true
                    errorMessage = null
                }
            })
            user != null -> ProfileContent(
                user = user!!,
                userVideos = userVideos,
                stitchersCount = stitchersCount,
                cloutCount = cloutCount,
                isFollowing = isFollowing,
                isFollowLoading = isFollowLoading,
                isOwnProfile = isOwnProfile,
                selectedTab = selectedTab,
                showingFullBio = showingFullBio,
                onTabSelected = { tab: Int -> selectedTab = tab },
                onBioToggle = { showingFullBio = !showingFullBio },
                onFollowToggle = {
                    // Use FollowManager to toggle follow
                    followManager.toggleFollow(userID)
                },
                onShareProfile = {
                    val shareText = "Check out @${user!!.username} on StitchSocial!\n\nstitch://profile/$userID"
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Profile"))
                },
                onStitchersClick = { showStitchersSheet = true },
                onVideoTap = onVideoTap,
                onDismiss = onDismiss
            )
        }

        // Stitchers/Following Sheet
        StitchersFollowingSheet(
            userID = userID,
            isVisible = showStitchersSheet,
            onDismiss = { showStitchersSheet = false },
            onUserTap = { tappedUserID: String ->
                // Navigate to tapped user's profile
                println("👤 Tapped user: $tappedUserID")
                // TODO: Navigate to their CreatorProfileView
            }
        )
    }
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
            Text("Loading profile...", color = Color.Gray, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ErrorView(error: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PersonOff,
                contentDescription = "Error",
                tint = Color.Gray,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "Profile could not be loaded",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = error,
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)
            ) {
                Text("Try Again", color = Color.Black)
            }
        }
    }
}

@Composable
private fun ProfileContent(
    user: BasicUserInfo,
    userVideos: List<CoreVideoMetadata>,
    stitchersCount: Int,
    cloutCount: Int,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    isOwnProfile: Boolean,
    selectedTab: Int,
    showingFullBio: Boolean,
    onTabSelected: (Int) -> Unit,
    onBioToggle: () -> Unit,
    onFollowToggle: () -> Unit,
    onShareProfile: () -> Unit,
    onStitchersClick: () -> Unit,
    onVideoTap: ((CoreVideoMetadata) -> Unit)?,
    onDismiss: () -> Unit
) {
    // Separate videos into threads (parents) and stitches (children)
    val threads = userVideos.filter { it.conversationDepth == 0 }
    val stitches = userVideos.filter { it.conversationDepth > 0 }
    val displayVideos = if (selectedTab == 0) threads else stitches

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Top Navigation Bar
        TopNavigationBar(
            onBackClick = onDismiss,
            onShareClick = onShareProfile
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Profile Header
        ProfileHeader(
            user = user,
            stitchersCount = stitchersCount,
            videosCount = userVideos.size,
            cloutCount = cloutCount,
            showingFullBio = showingFullBio,
            onBioToggle = onBioToggle,
            onStitchersClick = onStitchersClick
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Action Buttons
        if (!isOwnProfile) {
            ActionButtonsRow(
                isFollowing = isFollowing,
                isFollowLoading = isFollowLoading,
                onFollowToggle = onFollowToggle,
                onShareClick = onShareProfile
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Tab Bar - Threads (parents) and Stitches (children)
        TabBarSection(
            selectedTab = selectedTab,
            threadsCount = threads.size,
            stitchesCount = stitches.size,
            onTabSelected = onTabSelected
        )

        // Video Grid - shows threads or stitches based on tab
        VideoGridSection(
            videos = displayVideos,
            onVideoTap = onVideoTap
        )
    }
}

@Composable
private fun TopNavigationBar(
    onBackClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Share button
            IconButton(
                onClick = onShareClick,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color.White
                )
            }

            // More options
            IconButton(
                onClick = { /* TODO: Show options menu */ },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    user: BasicUserInfo,
    stitchersCount: Int,
    videosCount: Int,
    cloutCount: Int,
    showingFullBio: Boolean,
    onBioToggle: () -> Unit,
    onStitchersClick: () -> Unit
) {
    // Get display name - use username if displayName not available
    val displayName = user.username

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        // Profile image and basic info row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Enhanced profile image with hype ring
            EnhancedProfileImage(user = user)

            // Name, username, tier
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Name + verification
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = displayName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (user.isVerified) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Verified",
                            tint = Color.Red,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Username
                Text(
                    text = "@${user.username}",
                    fontSize = 14.sp,
                    color = Color.Gray
                )

                // Tier badge
                TierBadge(tier = user.tier)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bio section
        val bio = user.bio ?: ""
        if (bio.isNotEmpty()) {
            BioSection(
                bio = bio,
                isExpanded = showingFullBio,
                onToggle = onBioToggle
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Hype meter
        HypeMeterSection(user = user)

        Spacer(modifier = Modifier.height(16.dp))

        // Stats row
        StatsRow(
            stitchersCount = stitchersCount,
            videosCount = videosCount,
            cloutCount = cloutCount,
            onStitchersClick = onStitchersClick
        )
    }
}

@Composable
private fun EnhancedProfileImage(user: BasicUserInfo) {
    val tierColors = getTierColors(user.tier)
    val hypeLevel = calculateHypeLevel(user)

    Box(
        modifier = Modifier.size(90.dp),
        contentAlignment = Alignment.Center
    ) {
        // Background ring
        Canvas(modifier = Modifier.size(90.dp)) {
            drawCircle(
                color = Color.Gray.copy(alpha = 0.3f),
                radius = size.minDimension / 2,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
            )
        }

        // Hype progress ring
        Canvas(modifier = Modifier.size(90.dp)) {
            drawArc(
                brush = Brush.linearGradient(tierColors),
                startAngle = -90f,
                sweepAngle = (hypeLevel / 100f) * 360f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
        }

        // Profile image
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            if (!user.profileImageURL.isNullOrEmpty()) {
                AsyncImage(
                    model = user.profileImageURL,
                    contentDescription = "Profile",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

@Composable
private fun TierBadge(tier: UserTier) {
    val tierColors = getTierColors(tier)

    Row(
        modifier = Modifier
            .background(
                brush = Brush.linearGradient(tierColors.map { it.copy(alpha = 0.3f) }),
                shape = RoundedCornerShape(10.dp)
            )
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = getTierIcon(tier),
            contentDescription = null,
            tint = tierColors.first(),
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = tier.displayName,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

@Composable
private fun BioSection(
    bio: String,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Column {
        Text(
            text = bio,
            fontSize = 14.sp,
            color = Color.White,
            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )

        if (bio.length > 80) {
            Text(
                text = if (isExpanded) "Show less" else "Show more",
                fontSize = 12.sp,
                color = Color.Cyan,
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun HypeMeterSection(user: BasicUserInfo) {
    val hypeRating = calculateHypeLevel(user)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    tint = Color(0xFFFF8C00),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Hype Rating",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Text(
                text = "${hypeRating.toInt()}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Gray.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(hypeRating / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color(0xFFFF8C00), Color(0xFFFF4500), Color(0xFFFF0000))
                        )
                    )
            )
        }
    }
}

@Composable
private fun StatsRow(
    stitchersCount: Int,
    videosCount: Int,
    cloutCount: Int,
    onStitchersClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Stitchers - clickable to open sheet
        StatItem(
            title = "Stitchers",
            count = stitchersCount,
            onClick = onStitchersClick
        )
        StatItem(title = "Videos", count = videosCount)
        StatItem(title = "Clout", count = cloutCount)
    }
}

@Composable
private fun StatItem(
    title: String,
    count: Int,
    onClick: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }
    ) {
        Text(
            text = formatCount(count),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = title,
            fontSize = 12.sp,
            color = if (onClick != null) Color.Cyan else Color.Gray
        )
    }
}

@Composable
private fun ActionButtonsRow(
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    onFollowToggle: () -> Unit,
    onShareClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Follow/Following button
        Button(
            onClick = onFollowToggle,
            enabled = !isFollowLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isFollowing) Color.Gray.copy(alpha = 0.3f) else Color.Cyan
            ),
            modifier = Modifier.weight(1f)
        ) {
            if (isFollowLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = if (isFollowing) Color.Gray else Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = if (isFollowing) Icons.Default.Check else Icons.Default.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isFollowing) "Following" else "Follow",
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFollowing) Color.Gray else Color.Black
                )
            }
        }

        // Share button
        OutlinedButton(
            onClick = onShareClick,
            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share", color = Color.White)
        }
    }
}

@Composable
private fun TabBarSection(
    selectedTab: Int,
    threadsCount: Int,
    stitchesCount: Int,
    onTabSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(top = 10.dp)
    ) {
        TabButton(
            title = "Threads",
            count = threadsCount,
            isSelected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            modifier = Modifier.weight(1f)
        )
        TabButton(
            title = "Stitches",
            count = stitchesCount,
            isSelected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            modifier = Modifier.weight(1f)
        )
    }

    Divider(color = Color.Gray.copy(alpha = 0.3f))
}

@Composable
private fun TabButton(
    title: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) Color.White else Color.Gray
            )
            Text(
                text = "($count)",
                fontSize = 14.sp,
                color = Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (isSelected) Color.Cyan else Color.Transparent)
        )
    }
}

@Composable
private fun VideoGridSection(
    videos: List<CoreVideoMetadata>,
    onVideoTap: ((CoreVideoMetadata) -> Unit)?
) {
    if (videos.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("No videos yet", color = Color.Gray, fontSize = 14.sp)
            }
        }
    } else {
        // Video grid - 3 columns
        val rows: List<List<CoreVideoMetadata>> = videos.chunked(3)
        Column {
            rows.forEach { rowVideos: List<CoreVideoMetadata> ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    rowVideos.forEach { video: CoreVideoMetadata ->
                        VideoThumbnailItem(
                            video = video,
                            onClick = { onVideoTap?.invoke(video) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Fill empty spaces
                    repeat(3 - rowVideos.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun VideoThumbnailItem(
    video: CoreVideoMetadata,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Gray.copy(alpha = 0.2f))
            .clickable(onClick = onClick)
    ) {
        if (!video.thumbnailURL.isNullOrEmpty()) {
            AsyncImage(
                model = video.thumbnailURL,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // View count overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
                Text(
                    text = formatCount(video.viewCount),
                    fontSize = 10.sp,
                    color = Color.White
                )
            }
        }
    }
}

// MARK: - Helper Functions

private fun getTierColors(tier: UserTier): List<Color> {
    return when (tier) {
        UserTier.FOUNDER -> listOf(Color(0xFFFF00FF), Color(0xFFFFD700), Color(0xFFFF8C00))
        UserTier.CO_FOUNDER -> listOf(Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF))
        UserTier.TOP_CREATOR -> listOf(Color(0xFF0000FF), Color(0xFF00FFFF), Color.White)
        UserTier.LEGENDARY -> listOf(Color(0xFFFF0000), Color(0xFFFF8C00), Color(0xFFFFD700))
        UserTier.PARTNER -> listOf(Color(0xFF00FF00), Color(0xFF98FB98), Color(0xFF00FFFF))
        UserTier.ELITE -> listOf(Color(0xFFFF00FF), Color(0xFFFF69B4), Color(0xFFFF0000))
        UserTier.AMBASSADOR -> listOf(Color(0xFF9B59B6), Color(0xFFFF00FF), Color(0xFF00FFFF))
        UserTier.INFLUENCER -> listOf(Color(0xFFFF8C00), Color(0xFFFF0000), Color(0xFFFF69B4))
        UserTier.VETERAN -> listOf(Color(0xFF0000FF), Color(0xFF00FFFF), Color(0xFF98FB98))
        UserTier.RISING -> listOf(Color(0xFF00FF00), Color(0xFFFFD700), Color(0xFFFF8C00))
        UserTier.ROOKIE -> listOf(Color(0xFFFF8C00), Color(0xFFFF0000), Color(0xFFFFD700))
    }
}

private fun getTierIcon(tier: UserTier): androidx.compose.ui.graphics.vector.ImageVector {
    return when (tier) {
        UserTier.FOUNDER, UserTier.CO_FOUNDER -> Icons.Default.Star
        UserTier.TOP_CREATOR, UserTier.LEGENDARY -> Icons.Default.EmojiEvents
        UserTier.PARTNER, UserTier.ELITE -> Icons.Default.Diamond
        UserTier.AMBASSADOR, UserTier.INFLUENCER -> Icons.Default.Bolt
        UserTier.VETERAN, UserTier.RISING -> Icons.Default.TrendingUp
        UserTier.ROOKIE -> Icons.Default.FiberNew
    }
}

private fun calculateHypeLevel(user: BasicUserInfo): Float {
    // Calculate hype level based on tier
    val baseLevel = 50f
    val tierBonus = when (user.tier) {
        UserTier.FOUNDER -> 50f
        UserTier.CO_FOUNDER -> 45f
        UserTier.TOP_CREATOR -> 40f
        UserTier.LEGENDARY -> 35f
        UserTier.PARTNER -> 30f
        UserTier.ELITE -> 25f
        UserTier.AMBASSADOR -> 20f
        UserTier.INFLUENCER -> 15f
        UserTier.VETERAN -> 10f
        UserTier.RISING -> 5f
        UserTier.ROOKIE -> 0f
    }
    return (baseLevel + tierBonus).coerceIn(0f, 100f)
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0).replace(".0", "")
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0).replace(".0", "")
        else -> count.toString()
    }
}