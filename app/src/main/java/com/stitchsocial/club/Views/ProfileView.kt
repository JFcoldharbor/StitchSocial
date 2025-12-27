/*
 * ProfileView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - User Profile with Followers/Following
 * ✅ Hype Meter with 5-color gradient
 * ✅ Tier gradient borders
 * ✅ Clickable stats (Stitchers shows followers)
 * ✅ FollowManager integration
 * ✅ Video player modal
 * ✅ UPDATED: Uses StitchersFollowingSheet component
 * ✅ UPDATED: Added AMBASSADOR tier support
 */

package com.stitchsocial.club.views

import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.vector.ImageVector
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

// Foundation
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.UserTier

// Services
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.AuthService

// Coordination
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.NavigationDestination
import com.stitchsocial.club.coordination.NavigationEvent
import com.stitchsocial.club.coordination.EngagementCoordinator

// ViewModels
import com.stitchsocial.club.viewmodels.EngagementViewModel

// Views
import com.stitchsocial.club.ProfileVideoGrid
import com.stitchsocial.club.views.VideoPlayer

// MARK: - Helper Functions

/**
 * Get tier gradient colors for UI display
 * ✅ UPDATED: Added AMBASSADOR tier
 */
private fun getTierColors(tier: UserTier): List<Color> {
    return when(tier) {
        UserTier.ROOKIE -> listOf(Color(0xFF808080))
        UserTier.RISING -> listOf(Color(0xFF4A90E2), Color(0xFF64B5F6))
        UserTier.VETERAN -> listOf(Color(0xFF50C878), Color(0xFF81C784))
        UserTier.INFLUENCER -> listOf(Color(0xFFFFD700), Color(0xFFFDD835))
        UserTier.AMBASSADOR -> listOf(Color(0xFF9B59B6), Color(0xFFAB47BC))  // ✅ ADDED
        UserTier.ELITE -> listOf(Color(0xFF9B59B6), Color(0xFFBA68C8))
        UserTier.PARTNER -> listOf(Color(0xFFE74C3C), Color(0xFFEF5350))
        UserTier.LEGENDARY -> listOf(Color(0xFFFF6B35), Color(0xFFFF8A65))
        UserTier.TOP_CREATOR -> listOf(Color(0xFFFFD700), Color(0xFFFFA726))
        UserTier.FOUNDER -> listOf(Color(0xFFFFD700), Color(0xFFFF6B35), Color(0xFFE91E63))
        UserTier.CO_FOUNDER -> listOf(Color(0xFFFFD700), Color(0xFFFF6B35))
    }
}

/**
 * Get tier icon for UI display
 * ✅ UPDATED: Added AMBASSADOR tier
 */
private fun getTierIcon(tier: UserTier): ImageVector {
    return when(tier) {
        UserTier.ROOKIE -> Icons.Default.Star
        UserTier.RISING -> Icons.Default.TrendingUp
        UserTier.VETERAN -> Icons.Default.Shield
        UserTier.INFLUENCER -> Icons.Default.Star
        UserTier.AMBASSADOR -> Icons.Default.Public  // ✅ ADDED (globe icon)
        UserTier.ELITE -> Icons.Default.Diamond
        UserTier.PARTNER -> Icons.Default.Handshake
        UserTier.LEGENDARY -> Icons.Default.EmojiEvents
        UserTier.TOP_CREATOR -> Icons.Default.WorkspacePremium
        UserTier.FOUNDER -> Icons.Default.Verified
        UserTier.CO_FOUNDER -> Icons.Default.Verified
    }
}

private fun calculateHypeLevel(user: BasicUserInfo): Float {
    val total = user.totalHypesReceived + user.totalCoolsReceived
    if (total == 0) return 50f
    return (user.totalHypesReceived.toFloat() / total * 100)
}

private fun formatLargeNumber(num: Int): String {
    return when {
        num >= 1_000_000 -> "%.1fM".format(num / 1_000_000.0).replace(".0", "")
        num >= 1_000 -> "%.1fK".format(num / 1_000.0).replace(".0", "")
        else -> num.toString()
    }
}

private fun getBioText(user: BasicUserInfo): String {
    return user.bio?.trim() ?: ""
}

// MARK: - Main ProfileView

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileView(
    userID: String,
    navigationCoordinator: NavigationCoordinator? = null,
    engagementCoordinator: EngagementCoordinator? = null,
    engagementViewModel: EngagementViewModel? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Services
    val userService = remember { UserService(context) }
    val videoService = remember { VideoServiceImpl() }
    val authService = remember { AuthService() }

    // ViewModel
    val viewModel = engagementViewModel ?: remember {
        EngagementViewModel(authService, videoService, userService)
    }

    // Profile State
    var currentUser by remember { mutableStateOf<BasicUserInfo?>(null) }
    var userVideos by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingVideos by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // UI State
    var isShowingFullBio by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var showingEditProfile by remember { mutableStateOf(false) }
    var showingVideoPlayer by remember { mutableStateOf(false) }
    var showingSettings by remember { mutableStateOf(false) }
    var selectedVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var videoToDelete by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var showingDeleteConfirmation by remember { mutableStateOf(false) }

    // Stitchers/Following Sheet State
    var showStitchersSheet by remember { mutableStateOf(false) }

    val scrollState = rememberLazyListState()
    val tabTitles = listOf("Threads", "Stitches", "Replies")

    // Filtered videos based on selected tab
    val filteredVideos = remember(userVideos, selectedTab) {
        when (selectedTab) {
            0 -> userVideos.filter { it.contentType == ContentType.THREAD }
            1 -> userVideos.filter { it.contentType == ContentType.CHILD }
            2 -> userVideos.filter { it.replyToVideoID != null }
            else -> userVideos
        }
    }

    // Load user profile
    suspend fun loadUser() {
        isLoading = true
        errorMessage = null
        try {
            currentUser = userService.getUserProfile(userID)
            if (currentUser == null) {
                errorMessage = "User not found"
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load profile"
        } finally {
            isLoading = false
        }
    }

    // Load user videos
    suspend fun loadVideos() {
        isLoadingVideos = true
        try {
            userVideos = videoService.getUserVideos(userID, limit = 50)
        } catch (e: Exception) {
            // Silent failure for videos
        } finally {
            isLoadingVideos = false
        }
    }

    // Initial load
    LaunchedEffect(userID) {
        loadUser()
        loadVideos()
    }

    // Main content
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            isLoading -> LoadingView()
            errorMessage != null -> ErrorView(errorMessage!!)
            currentUser == null -> NoUserView()
            currentUser != null -> {
                LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize()) {
                    // Header
                    item {
                        ProfileHeader(
                            user = currentUser!!,
                            isShowingFullBio = isShowingFullBio,
                            onToggleBio = { isShowingFullBio = !isShowingFullBio },
                            onEditProfile = { showingEditProfile = true },
                            onSettingsClick = { showingSettings = true },
                            onFollowersClick = { showStitchersSheet = true }
                        )
                    }

                    // Tabs
                    item {
                        TabBar(
                            selectedTab = selectedTab,
                            tabTitles = tabTitles,
                            tabCounts = listOf(
                                userVideos.count { it.contentType == ContentType.THREAD },
                                userVideos.count { it.contentType == ContentType.CHILD },
                                userVideos.count { it.replyToVideoID != null }
                            ),
                            onTabSelected = { selectedTab = it }
                        )
                    }

                    // Grid
                    item {
                        ProfileVideoGrid(
                            videos = filteredVideos.map { it.toBasicVideoInfo() },
                            selectedTab = selectedTab,
                            tabTitles = tabTitles,
                            isLoading = isLoadingVideos,
                            isCurrentUserProfile = true,
                            onVideoTap = { basicVideo, _, _ ->
                                userVideos.find { it.id == basicVideo.id }?.let {
                                    selectedVideo = it
                                    showingVideoPlayer = true
                                }
                            },
                            onVideoDelete = { basicVideo ->
                                userVideos.find { it.id == basicVideo.id }?.let {
                                    videoToDelete = it
                                    showingDeleteConfirmation = true
                                }
                            }
                        )
                    }

                    // Bottom spacing
                    item {
                        Spacer(modifier = Modifier.height(100.dp))
                    }
                }
            }
        }
    }

    // Edit Profile Sheet
    if (showingEditProfile && currentUser != null) {
        EditProfileView(
            userID = userID,
            currentUserName = currentUser!!.displayName,
            currentUsername = currentUser!!.username,
            currentUserImage = currentUser!!.profileImageURL,
            currentBio = currentUser!!.bio ?: "",
            onSave = { displayName, bio, username, imageUri ->
                scope.launch {
                    userService.updateProfile(
                        userID = userID,
                        displayName = displayName,
                        bio = bio,
                        username = username
                    )
                    loadUser()
                    showingEditProfile = false
                }
            },
            onCancel = { showingEditProfile = false }
        )
    }

    // Settings Sheet
    if (showingSettings && currentUser != null) {
        SettingsView(
            currentUser = currentUser!!,
            authService = authService,
            onDismiss = { showingSettings = false },
            onSignOutSuccess = {
                showingSettings = false
            }
        )
    }

    // Video Player Modal
    if (showingVideoPlayer && selectedVideo != null) {
        VideoPlayer(
            video = selectedVideo!!,
            currentUserID = userID,
            currentUserTier = currentUser?.tier ?: UserTier.ROOKIE,
            engagementCoordinator = engagementCoordinator,
            engagementViewModel = viewModel,
            onClose = {
                showingVideoPlayer = false
                selectedVideo = null
            },
            onNavigateToProfile = { },
            onEngagement = { scope.launch { loadVideos() } },
            onShare = { },
            onStitchRecording = { }
        )
    }

    // Delete Confirmation Dialog
    if (showingDeleteConfirmation && videoToDelete != null) {
        AlertDialog(
            onDismissRequest = { showingDeleteConfirmation = false },
            title = { Text("Delete Video", color = Color.White) },
            text = { Text("Are you sure you want to delete this video?", color = Color.Gray) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        videoToDelete?.let { video ->
                            // Remove from local list (actual deletion would need backend implementation)
                            userVideos = userVideos.filter { it.id != video.id }
                        }
                        showingDeleteConfirmation = false
                        videoToDelete = null
                    }
                }) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showingDeleteConfirmation = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }

    // Stitchers/Following Sheet - using reusable component
    StitchersFollowingSheet(
        userID = userID,
        isVisible = showStitchersSheet,
        onDismiss = { showStitchersSheet = false },
        onUserTap = { tappedUserID: String ->
            navigationCoordinator?.navigateTo(NavigationDestination.UserProfile(tappedUserID))
        }
    )
}

// MARK: - Components

@Composable
private fun ProfileHeader(
    user: BasicUserInfo,
    isShowingFullBio: Boolean,
    onToggleBio: () -> Unit,
    onEditProfile: () -> Unit,
    onSettingsClick: () -> Unit,
    onFollowersClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp)
    ) {
        // Top row with settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, null, tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Profile image with tier border
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            ProfileImage(user = user)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Username and verification
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = user.displayName,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (user.isVerified) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.Default.Verified,
                    contentDescription = "Verified",
                    tint = Color.Red,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Text(
            text = "@${user.username}",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Tier Badge
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            TierBadge(tier = user.tier)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bio
        BioSection(
            user = user,
            isShowingFullBio = isShowingFullBio,
            onToggleBio = onToggleBio
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Hype Meter
        HypeMeter(user = user)

        Spacer(modifier = Modifier.height(16.dp))

        // Stats
        Stats(user = user, onFollowersClick = onFollowersClick)

        Spacer(modifier = Modifier.height(16.dp))

        // Edit Profile Button
        Button(
            onClick = onEditProfile,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Gray.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Edit Profile", color = Color.White)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ProfileImage(user: BasicUserInfo) {
    val tierColors = getTierColors(user.tier)

    Box(
        modifier = Modifier.size(100.dp),
        contentAlignment = Alignment.Center
    ) {
        // Tier gradient border
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    brush = if (tierColors.size > 1) {
                        Brush.linearGradient(tierColors)
                    } else {
                        Brush.linearGradient(listOf(tierColors[0], tierColors[0]))
                    }
                )
        )

        // Profile image
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(user.profileImageURL ?: "")
                .crossfade(true)
                .build(),
            contentDescription = "Profile",
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.3f)),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun TierBadge(tier: UserTier) {
    val tierColors = getTierColors(tier)
    val tierIcon = getTierIcon(tier)

    Row(
        modifier = Modifier
            .background(
                brush = if (tierColors.size > 1) {
                    Brush.horizontalGradient(tierColors)
                } else {
                    Brush.horizontalGradient(listOf(tierColors[0], tierColors[0]))
                },
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = tierIcon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = tier.displayName,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BioSection(
    user: BasicUserInfo,
    isShowingFullBio: Boolean,
    onToggleBio: () -> Unit
) {
    val bioText = getBioText(user)
    if (bioText.isEmpty()) return

    val shouldTruncate = bioText.length > 100

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        if (shouldTruncate && !isShowingFullBio) {
            Text(bioText.take(100) + "...", color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
            Text("Show more", color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onToggleBio() })
        } else {
            Text(bioText, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
            if (shouldTruncate && isShowingFullBio) {
                Text("Show less", color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onToggleBio() })
            }
        }
    }
}

@Composable
private fun HypeMeter(user: BasicUserInfo) {
    val hypeRating = calculateHypeLevel(user)
    val progress = hypeRating / 100f
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Whatshot, null, tint = Color(0xFFFF9800), modifier = Modifier.size(18.dp))
                Text("Hype Rating", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("${hypeRating.toInt()}%", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Box(modifier = Modifier.fillMaxWidth().height(12.dp)) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(6.dp)))
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(
                    Brush.horizontalGradient(listOf(
                        Color(0xFF4CAF50), Color(0xFFFFEB3B), Color(0xFFFF9800), Color(0xFFF44336), Color(0xFF9C27B0)
                    )),
                    RoundedCornerShape(6.dp)
                ))
        }
    }
}

@Composable
private fun Stats(user: BasicUserInfo, onFollowersClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatItem(user.videoCount ?: 0, "Videos") { }
        StatItem(user.followerCount ?: 0, "Stitchers", onFollowersClick)
        StatItem(user.clout ?: 0, "Clout") { }
    }
}

@Composable
private fun StatItem(count: Int, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(formatLargeNumber(count), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = if (label == "Stitchers") Color.Cyan else Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun TabBar(
    selectedTab: Int,
    tabTitles: List<String>,
    tabCounts: List<Int>,
    onTabSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabTitles.forEachIndexed { index, title ->
            val isSelected = selectedTab == index
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "$title (${tabCounts.getOrNull(index) ?: 0})",
                    color = if (isSelected) Color.Cyan else Color.Gray,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(2.dp)
                            .background(Color.Cyan, RoundedCornerShape(1.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        CircularProgressIndicator(color = Color.Cyan)
    }
}

@Composable
private fun ErrorView(error: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Error, null, tint = Color.Red, modifier = Modifier.size(48.dp))
            Text("Error loading profile", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(error, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp))
        }
    }
}

@Composable
private fun NoUserView() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text("User not found", color = Color.Gray, fontSize = 18.sp)
    }
}

// MARK: - Extensions

private fun CoreVideoMetadata.toBasicVideoInfo(): com.stitchsocial.club.foundation.BasicVideoInfo {
    return com.stitchsocial.club.foundation.BasicVideoInfo(
        id = this.id,
        title = this.title,
        creatorName = this.creatorName,
        creatorID = this.creatorID,
        thumbnailURL = this.thumbnailURL,
        videoURL = this.videoURL,
        duration = this.duration,
        hypeCount = this.hypeCount,
        coolCount = this.coolCount,
        viewCount = this.viewCount,
        createdAt = this.createdAt,
        contentType = this.contentType,
        temperature = this.temperature
    )
}