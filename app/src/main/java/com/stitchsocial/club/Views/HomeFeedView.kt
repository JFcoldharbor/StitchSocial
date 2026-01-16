/*
 * HomeFeedView.kt - OPTION 3: Manual Horizontal Gestures
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Structure:
 * - VerticalPager for thread navigation (works well)
 * - Manual horizontal drag gestures for child navigation (replaces HorizontalPager)
 * - Animatable offset for smooth snapping
 * - Matches iOS gesture approach
 */

package com.stitchsocial.club.views

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.launch

// Services
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.HomeFeedService
import com.stitchsocial.club.services.FeedViewHistory
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.MainAppTab

// Foundation
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ThreadData

// Camera
import com.stitchsocial.club.camera.RecordingContextFactory

// ViewModels
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager
import com.stitchsocial.club.FollowManager
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

/**
 * HomeFeedView - VerticalPager + Manual Horizontal Gestures
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedView(
    userID: String,
    navigationCoordinator: NavigationCoordinator?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Services
    val videoService = remember { VideoServiceImpl() }
    val userService = remember { UserService(context) }
    val authService = remember { AuthService() }

    LaunchedEffect(Unit) {
        FeedViewHistory.initialize(context)
    }

    val feedService = remember {
        HomeFeedService(
            videoService = videoService,
            userService = userService,
            context = context
        )
    }

    val engagementViewModel = remember {
        EngagementViewModel(
            authService = authService,
            videoService = videoService,
            userService = userService
        ).also { it.setCurrentUser(userID) }
    }
    val iconManager = remember { FloatingIconManager() }
    val followManager = remember { FollowManager(context) }

    val pauseAllVideos: () -> Unit = {
        val intent = Intent("com.stitchsocial.club.PAUSE_ALL_VIDEOS")
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    // Feed state
    var baseThreads by remember { mutableStateOf<List<ThreadData>>(emptyList()) }
    val loadedChildren = remember { mutableStateMapOf<String, List<CoreVideoMetadata>>() }

    val threads by remember(baseThreads, loadedChildren.size) {
        derivedStateOf {
            baseThreads.map { thread ->
                val children = loadedChildren[thread.id]
                if (children != null && children.isNotEmpty()) {
                    thread.copy(childVideos = children)
                } else {
                    thread
                }
            }
        }
    }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var followingCount by remember { mutableStateOf(0) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var showingCreatorProfileID by remember { mutableStateOf<String?>(null) }

    val verticalPagerState = rememberPagerState(pageCount = { threads.size })

    // Pause when overlay shown
    LaunchedEffect(showingCreatorProfileID) {
        if (showingCreatorProfileID != null) pauseAllVideos()
    }

    // Load feed
    LaunchedEffect(userID) {
        try {
            isLoading = true
            errorMessage = null
            val feedThreads = feedService.loadFeed(userID, 40)
            followingCount = feedService.getFollowingCount()
            feedService.saveCurrentFeed(feedThreads)
            baseThreads = feedThreads
            println("✅ HOME FEED: Loaded ${feedThreads.size} threads")
        } catch (e: Exception) {
            println("🚨 HOME FEED: Error - ${e.message}")
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    // Lazy load children
    LaunchedEffect(verticalPagerState.currentPage) {
        val currentThread = baseThreads.getOrNull(verticalPagerState.currentPage)
        if (currentThread != null) {
            if (!loadedChildren.containsKey(currentThread.id) && currentThread.parentVideo.replyCount > 0) {
                println("🔄 LAZY LOAD: Loading children for ${currentThread.id}")
                try {
                    val children = feedService.loadThreadChildren(currentThread.id)
                    if (children.isNotEmpty()) {
                        loadedChildren[currentThread.id] = children
                        println("✅ LAZY LOAD: ${children.size} children loaded")
                    }
                } catch (e: Exception) {
                    println("❌ LAZY LOAD ERROR: ${e.message}")
                }
            }
            delay(3000)
            feedService.markVideoSeen(currentThread.parentVideo.id)
        }
        feedService.saveCurrentPosition(verticalPagerState.currentPage, 0, currentThread?.id)
    }

    // Load more
    LaunchedEffect(verticalPagerState.currentPage) {
        if (feedService.shouldLoadMore(verticalPagerState.currentPage) && !isLoadingMore && threads.isNotEmpty()) {
            isLoadingMore = true
            try {
                val moreThreads = feedService.loadMoreContent(userID)
                if (moreThreads.size > baseThreads.size) {
                    baseThreads = baseThreads + moreThreads.drop(baseThreads.size)
                }
            } catch (_: Exception) {}
            isLoadingMore = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            isLoading -> LoadingView()
            errorMessage != null -> ErrorView(errorMessage!!) {
                scope.launch {
                    isLoading = true
                    errorMessage = null
                    try {
                        feedService.clearFeed()
                        loadedChildren.clear()
                        baseThreads = feedService.loadFeed(userID, 40)
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                    isLoading = false
                }
            }
            threads.isEmpty() -> EmptyFeedView(followingCount > 0) {
                navigationCoordinator?.navigateToTab(MainAppTab.DISCOVERY)
            }
            else -> {
                val isOverlayShowing = showingCreatorProfileID != null

                VerticalPager(
                    state = verticalPagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { threads.getOrNull(it)?.id ?: "page_$it" }
                ) { page ->
                    val thread = threads[page]
                    val isActive = page == verticalPagerState.currentPage && !isOverlayShowing

                    ManualHorizontalThreadContainer(
                        thread = thread,
                        isActive = isActive,
                        userID = userID,
                        engagementViewModel = engagementViewModel,
                        iconManager = iconManager,
                        followManager = followManager,
                        navigationCoordinator = navigationCoordinator,
                        onCreatorProfileTap = { pauseAllVideos(); showingCreatorProfileID = it },
                        onStitchTap = { video ->
                            pauseAllVideos()
                            val isOwn = video.creatorID == userID
                            val ctx = if (isOwn) {
                                RecordingContextFactory.createContinueThread(
                                    video.threadID ?: video.id, video.creatorName, video.title
                                )
                            } else {
                                RecordingContextFactory.createStitchToThread(
                                    video.threadID ?: video.id, video.creatorName, video.title
                                )
                            }
                            navigationCoordinator?.showRecordingModal(ctx, video)
                        }
                    )
                }

                if (isLoadingMore) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }

        // Creator Profile Overlay
        showingCreatorProfileID?.let { profileID ->
            CreatorProfileView(
                userID = profileID,
                currentUserID = userID,
                navigationCoordinator = navigationCoordinator,
                onDismiss = { showingCreatorProfileID = null },
                onVideoTap = { }
            )
        }
    }
}

// =============================================================================
// MARK: - Manual Horizontal Thread Container
// =============================================================================

@Composable
private fun ManualHorizontalThreadContainer(
    thread: ThreadData,
    isActive: Boolean,
    userID: String,
    engagementViewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    followManager: FollowManager,
    navigationCoordinator: NavigationCoordinator?,
    onCreatorProfileTap: (String) -> Unit,
    onStitchTap: (CoreVideoMetadata) -> Unit
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

    // All videos in thread
    val allVideos = remember(thread.id, thread.childVideos) {
        listOf(thread.parentVideo) + thread.childVideos
    }
    val videoCount = allVideos.size

    // Current horizontal index
    var currentIndex by remember { mutableStateOf(0) }

    // Horizontal offset for animation
    val offsetX = remember { Animatable(0f) }

    // Drag state
    var isDragging by remember { mutableStateOf(false) }

    // Reset index when thread changes
    LaunchedEffect(thread.id) {
        currentIndex = 0
        offsetX.snapTo(0f)
    }

    // Current video based on index
    val currentVideo = allVideos.getOrNull(currentIndex) ?: thread.parentVideo
    val isOnParent = currentIndex == 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(videoCount, thread.id) {
                if (videoCount <= 1) return@pointerInput // No gesture needed for single video

                val velocityTracker = VelocityTracker()

                detectHorizontalDragGestures(
                    onDragStart = {
                        isDragging = true
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        isDragging = false

                        val velocity = velocityTracker.calculateVelocity().x
                        val currentOffset = offsetX.value

                        // Lower thresholds for easier swipe
                        val threshold = screenWidthPx * 0.2f  // 20% of screen
                        val velocityThreshold = 300f  // Lower velocity needed

                        scope.launch {
                            val targetIndex = when {
                                // Swipe left (next) - negative offset or velocity
                                currentOffset < -threshold || velocity < -velocityThreshold -> {
                                    (currentIndex + 1).coerceAtMost(videoCount - 1)
                                }
                                // Swipe right (prev) - positive offset or velocity
                                currentOffset > threshold || velocity > velocityThreshold -> {
                                    (currentIndex - 1).coerceAtLeast(0)
                                }
                                // Snap back
                                else -> currentIndex
                            }

                            currentIndex = targetIndex

                            // Smoother spring animation
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )

                            println("📱 SWIPE: Index now $currentIndex / ${videoCount - 1}")
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        scope.launch {
                            offsetX.animateTo(
                                0f,
                                spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(
                            change.uptimeMillis,
                            change.position
                        )

                        // Smoother drag with softer edge resistance
                        val resistance = when {
                            currentIndex == 0 && offsetX.value + dragAmount > 0 -> 0.4f
                            currentIndex == videoCount - 1 && offsetX.value + dragAmount < 0 -> 0.4f
                            else -> 1f
                        }

                        scope.launch {
                            offsetX.snapTo(offsetX.value + dragAmount * resistance)
                        }
                    }
                )
            }
    ) {
        // Video layer with offset
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX.value
                }
        ) {
            // Current video - KEY on video ID to force reload when changing
            key(currentVideo.id) {
                VideoPlayerComposable(
                    video = currentVideo,
                    isActive = isActive && !isDragging,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Overlay
            ContextualVideoOverlay(
                video = currentVideo,
                overlayContext = if (isOnParent) OverlayContext.HOME_FEED else OverlayContext.THREAD_VIEW,
                currentUserID = userID,
                threadVideo = if (!isOnParent) thread.parentVideo else null,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                followManager = followManager,
                navigationCoordinator = navigationCoordinator,
                onAction = { action ->
                    when (action) {
                        is OverlayAction.NavigateToProfile -> onCreatorProfileTap(action.userID)
                        is OverlayAction.StitchRecording -> onStitchTap(currentVideo)
                        else -> {}
                    }
                }
            )
        }

        // Peek of next/prev video during drag - USE THUMBNAILS (not video players to avoid conflicts)
        if (isDragging && videoCount > 1) {
            val dragOffset = offsetX.value

            // Next video peek (dragging left, offset negative)
            if (dragOffset < 0 && currentIndex < videoCount - 1) {
                val nextVideo = allVideos[currentIndex + 1]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = screenWidthPx + dragOffset
                        }
                        .background(Color.Black)
                ) {
                    // Thumbnail preview instead of full video player
                    VideoThumbnailPeek(
                        video = nextVideo,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Prev video peek (dragging right, offset positive)
            if (dragOffset > 0 && currentIndex > 0) {
                val prevVideo = allVideos[currentIndex - 1]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = -screenWidthPx + dragOffset
                        }
                        .background(Color.Black)
                ) {
                    // Thumbnail preview instead of full video player
                    VideoThumbnailPeek(
                        video = prevVideo,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Navigation dots only (overlay handles other indicators)
        if (videoCount > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(videoCount) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentIndex) 10.dp else 8.dp)
                            .background(
                                if (index == currentIndex) Color.White
                                else Color.White.copy(alpha = 0.5f),
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}

// =============================================================================
// MARK: - Helper Views
// =============================================================================

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Loading feed...", color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ErrorView(error: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Default.Error, "Error", tint = Color.Red, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("Failed to load feed", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(error, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                Text("Retry", color = Color.Black)
            }
        }
    }
}

@Composable
private fun EmptyFeedView(isFollowingAnyone: Boolean, onDiscoverClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Default.VideoLibrary, "No videos", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                if (isFollowingAnyone) "No videos from followed users yet"
                else "Follow users to see their videos here",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isFollowingAnyone) "The people you follow haven't posted yet"
                else "Start by discovering creators",
                color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDiscoverClick, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                Icon(Icons.Default.Explore, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Discover Videos", color = Color.Black)
            }
        }
    }
}

// =============================================================================
// MARK: - Video Thumbnail Peek (for drag preview - no ExoPlayer)
// =============================================================================

@Composable
private fun VideoThumbnailPeek(
    video: CoreVideoMetadata,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Thumbnail image
        AsyncImage(
            model = video.thumbnailURL,
            contentDescription = video.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Subtle loading overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
        )

        // Play icon hint
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(40.dp)
            )
        }
    }
}