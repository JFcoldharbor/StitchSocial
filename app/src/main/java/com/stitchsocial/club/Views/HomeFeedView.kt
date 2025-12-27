/*
 * HomeFeedView.kt - FIXED ALL COMPILATION ERRORS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Home Feed with FOLLOWING CONTENT ONLY
 * ✅ FIXED: All parameter names match actual composable signatures
 */

package com.stitchsocial.club.views

import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.MainAppTab
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

// Foundation imports
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ThreadData
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.UserTier

// Camera imports
import com.stitchsocial.club.camera.RecordingContext
import com.stitchsocial.club.camera.RecordingContextFactory

// ViewModel imports
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager
import com.stitchsocial.club.FollowManager

/**
 * HomeFeedView with FOLLOWING feed ONLY
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedView(
    userID: String,
    navigationCoordinator: NavigationCoordinator?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoService = remember { VideoServiceImpl() }
    val userService = remember { UserService(context) }
    val authService = remember { AuthService() }

    val engagementViewModel = remember {
        EngagementViewModel(
            authService = authService,
            videoService = videoService,
            userService = userService
        ).also {
            it.setCurrentUser(userID)
        }
    }
    val iconManager = remember { FloatingIconManager() }
    val followManager = remember { FollowManager(context) }

    // Helper to pause all videos
    val pauseAllVideos: () -> Unit = {
        val intent = Intent("com.stitchsocial.club.PAUSE_ALL_VIDEOS")
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        println("📹 PAUSE ALL VIDEOS broadcast sent")
    }

    var threads by remember { mutableStateOf<List<ThreadData>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var followingCount by remember { mutableStateOf(0) }

    // Creator profile state - when set, shows CreatorProfileView
    var showingCreatorProfileID by remember { mutableStateOf<String?>(null) }

    val verticalPagerState = rememberPagerState(pageCount = { threads.size })
    val scope = rememberCoroutineScope()

    // Pause videos when creator profile overlay is shown
    LaunchedEffect(showingCreatorProfileID) {
        if (showingCreatorProfileID != null) {
            pauseAllVideos()
        }
    }

    LaunchedEffect(userID) {
        try {
            isLoading = true
            errorMessage = null

            println("🏠 HOME FEED: Loading feed for user $userID")

            val following = userService.getFollowing(userID)
            followingCount = following.size
            println("👥 HOME FEED: User follows ${following.size} users")

            val feedThreads: List<ThreadData> = if (following.isNotEmpty()) {
                val followingIDs = following.map { it.id }
                println("📱 HOME FEED: Loading feed for ${followingIDs.size} followed users")

                // Get parent videos
                val parentVideos: List<CoreVideoMetadata> = videoService.getFeedVideos(followingIDs, 50)
                println("📊 HOME FEED: VideoService returned ${parentVideos.size} parent videos")

                // Convert to ThreadData and load children for each
                parentVideos.map { parent ->
                    val threadID = parent.threadID ?: parent.id
                    println("🧵 HOME FEED: Loading children for thread $threadID (parent.id=${parent.id})")

                    // Try primary method (by threadID)
                    var children = try {
                        videoService.getThreadChildren(threadID)
                    } catch (e: Exception) {
                        println("⚠️ HOME FEED: Failed to load children by threadID: ${e.message}")
                        emptyList()
                    }

                    // If no children found, try by replyToVideoID
                    if (children.isEmpty()) {
                        println("🔄 HOME FEED: No children by threadID, trying replyToVideoID...")
                        children = try {
                            videoService.getThreadChildrenByReplyTo(parent.id)
                        } catch (e: Exception) {
                            println("⚠️ HOME FEED: Failed to load children by replyTo: ${e.message}")
                            emptyList()
                        }
                    }

                    println("📊 HOME FEED: Final ${children.size} children for thread $threadID")

                    ThreadData(
                        id = threadID,
                        parentVideo = parent,
                        childVideos = children,
                        isChildrenLoaded = true,
                        createdAt = parent.createdAt,
                        lastEngagementAt = parent.lastEngagementAt ?: parent.createdAt
                    )
                }
            } else {
                println("📭 HOME FEED: No following - empty feed")
                emptyList()
            }

            println("✅ HOME FEED: Loaded ${feedThreads.size} threads with children")
            threads = feedThreads

        } catch (e: Exception) {
            println("🚨 HOME FEED: Error - ${e.message}")
            e.printStackTrace()
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            isLoading -> LoadingView()

            errorMessage != null -> ErrorView(
                error = errorMessage!!,
                onRetry = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null
                    }
                }
            )

            threads.isEmpty() -> EmptyFeedView(
                isFollowingAnyone = followingCount > 0,
                onDiscoverClick = {
                    navigationCoordinator?.navigateToTab(MainAppTab.DISCOVERY)
                }
            )

            else -> {
                // Check if any overlay is showing - pause videos when true
                val isOverlayShowing = showingCreatorProfileID != null

                VerticalPager(
                    state = verticalPagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val thread = threads[page]
                    // Video should only be active if it's the current page AND no overlay is showing
                    val isActive = page == verticalPagerState.currentPage && !isOverlayShowing

                    ThreadContainer(
                        thread = thread,
                        isActive = isActive,
                        userID = userID,
                        engagementViewModel = engagementViewModel,
                        iconManager = iconManager,
                        followManager = followManager,
                        navigationCoordinator = navigationCoordinator,
                        onCreatorProfileTap = { creatorID ->
                            pauseAllVideos()
                            showingCreatorProfileID = creatorID
                        },
                        onStitchTap = { video ->
                            pauseAllVideos()
                            // Use NavigationCoordinator modal system for proper flow
                            val isOwnVideo = video.creatorID == userID
                            val recordingContext = if (isOwnVideo) {
                                RecordingContextFactory.createContinueThread(
                                    threadId = video.threadID ?: video.id,
                                    creatorName = video.creatorName,
                                    title = video.title
                                )
                            } else {
                                RecordingContextFactory.createStitchToThread(
                                    threadId = video.threadID ?: video.id,
                                    creatorName = video.creatorName,
                                    title = video.title
                                )
                            }
                            navigationCoordinator?.showRecordingModal(recordingContext, video)
                        }
                    )
                }
            }
        }

        // Creator Profile Full Screen Overlay
        showingCreatorProfileID?.let { profileUserID ->
            CreatorProfileView(
                userID = profileUserID,
                currentUserID = userID,
                navigationCoordinator = navigationCoordinator,
                onDismiss = { showingCreatorProfileID = null },
                onVideoTap = { video ->
                    // TODO: Handle video tap from profile
                    println("🎬 Tapped video: ${video.id}")
                }
            )
        }

        // NOTE: CameraView is now handled by ModalOverlay in MainActivity
        // The navigationCoordinator.showRecordingModal() call above triggers it
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadContainer(
    thread: ThreadData,
    isActive: Boolean,
    userID: String,
    engagementViewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    followManager: FollowManager,
    navigationCoordinator: NavigationCoordinator?,
    onCreatorProfileTap: (String) -> Unit = {},
    onStitchTap: (CoreVideoMetadata) -> Unit = {}
) {
    val allVideos = remember(thread) {
        listOf(thread.parentVideo) + thread.childVideos
    }
    val hasChildren = thread.childVideos.isNotEmpty()
    val parentVideo = thread.parentVideo  // Store reference to parent for stitch display

    val horizontalPagerState = rememberPagerState(pageCount = { allVideos.size })
    val currentVideoIndex = horizontalPagerState.currentPage
    val currentVideo = allVideos.getOrNull(currentVideoIndex) ?: thread.parentVideo
    val isOnParent = currentVideoIndex == 0

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = horizontalPagerState,
            modifier = Modifier.fillMaxSize()
        ) { videoIndex ->
            val video = allVideos[videoIndex]
            val isVideoActive = isActive && videoIndex == currentVideoIndex
            val isChildVideo = videoIndex > 0  // Not the parent

            Box(modifier = Modifier.fillMaxSize()) {
                // Video player
                VideoPlayerComposable(
                    video = video,
                    isActive = isVideoActive,
                    modifier = Modifier.fillMaxSize()
                )

                // Contextual overlay - pass parent as threadVideo for children
                ContextualVideoOverlay(
                    video = video,
                    overlayContext = if (isOnParent) OverlayContext.HOME_FEED else OverlayContext.THREAD_VIEW,
                    currentUserID = userID,
                    threadVideo = if (isChildVideo) parentVideo else null,  // ✅ Pass parent for stitch display
                    engagementViewModel = engagementViewModel,
                    iconManager = iconManager,
                    followManager = followManager,
                    navigationCoordinator = navigationCoordinator,
                    onAction = { action ->
                        when (action) {
                            is OverlayAction.NavigateToProfile -> {
                                println("👤 Navigate to profile: ${action.userID}")
                                // Show CreatorProfileView via callback
                                onCreatorProfileTap(action.userID)
                            }
                            is OverlayAction.Engagement -> {
                                println("⚡ Engagement: ${action.type}")
                            }
                            is OverlayAction.NavigateToThread -> {
                                println("🧵 Navigate to thread view")
                            }
                            is OverlayAction.StitchRecording -> {
                                println("🎬 STITCH RECORDING - Opening camera for video ${video.id}")
                                onStitchTap(video)
                            }
                            else -> {}
                        }
                    }
                )
            }
        }

        // Navigation dots (only if multiple videos)
        if (allVideos.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                allVideos.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentVideoIndex) 10.dp else 8.dp)
                            .background(
                                color = if (index == currentVideoIndex) Color.White
                                else Color.White.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                    )
                }
            }
        }

        // Back arrow when viewing children
        if (!isOnParent) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = "Back to parent",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
            Text("Loading feed...", color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ErrorView(error: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Default.Error, "Error", tint = Color.Red, modifier = Modifier.size(64.dp))
            Text("Failed to load feed", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(error, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center)
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                Text("Retry", color = Color.Black)
            }
        }
    }
}

@Composable
private fun EmptyFeedView(isFollowingAnyone: Boolean, onDiscoverClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Default.VideoLibrary, "No videos", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(80.dp))
            Text(
                text = if (isFollowingAnyone) "No videos from followed users yet" else "Follow users to see their videos here",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (isFollowingAnyone) "The people you follow haven't posted yet" else "Start by discovering creators",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Button(onClick = onDiscoverClick, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                Icon(Icons.Default.Explore, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Discover Videos", color = Color.Black)
            }
        }
    }
}