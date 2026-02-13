/*
 * VideoPlayer.kt - FULLSCREEN PLAYER MATCHING HOMEFEED STRUCTURE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * ✅ Uses HorizontalPager for child navigation (like ThreadContainer)
 * ✅ Pauses video when ProfileView is shown
 * ✅ Swipe up gesture to exit fullscreen
 * ✅ Navigation dots for thread position
 * ✅ Loads children on-demand
 * ✅ Accepts modifier parameter for proper fullscreen sizing
 */

package com.stitchsocial.club.views

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Foundation imports
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.InteractionType

// Service imports
import com.stitchsocial.club.services.HomeFeedService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.UserService

// Engagement imports
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// Follow Manager
import com.stitchsocial.club.FollowManager
import com.stitchsocial.club.camera.RecordingContextFactory

// MARK: - Main VideoPlayer Composable

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoPlayer(
    video: CoreVideoMetadata,
    currentUserID: String? = null,
    currentUserTier: UserTier = UserTier.ROOKIE,
    isFollowing: Boolean = false,
    engagementCoordinator: EngagementCoordinator? = null,
    engagementViewModel: EngagementViewModel,
    iconManager: FloatingIconManager? = null,
    followManager: FollowManager? = null,
    navigationCoordinator: NavigationCoordinator? = null,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    onNavigateToProfile: (String) -> Unit = {},
    onEngagement: (InteractionType) -> Unit = {},
    onShare: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val iconMgr = iconManager ?: remember { FloatingIconManager() }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // ✅ Create services with correct parameters
    val videoService = remember { VideoServiceImpl() }
    val userService = remember { UserService(context) }
    val feedService = remember { HomeFeedService(videoService, userService, context) }

    // Pause helper
    val pauseAllVideos: () -> Unit = {
        val intent = Intent("com.stitchsocial.club.PAUSE_ALL_VIDEOS")
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    // ✅ Load children for this video
    var childVideos by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var isLoadingChildren by remember { mutableStateOf(true) }

    // ✅ Profile navigation state - video pauses when this is set
    var showingCreatorProfileID by remember { mutableStateOf<String?>(null) }

    // Load children on mount
    LaunchedEffect(video.id) {
        isLoadingChildren = true
        try {
            if (video.replyCount > 0) {
                val children = feedService.loadThreadChildren(video.id)
                childVideos = children
                println("📹 VideoPlayer: Loaded ${children.size} children for ${video.id}")
            }
        } catch (e: Exception) {
            println("❌ VideoPlayer: Failed to load children: ${e.message}")
        }
        isLoadingChildren = false
    }

    // ✅ All videos = parent + children (like ThreadContainer)
    val allVideos = remember(video, childVideos) {
        listOf(video) + childVideos
    }

    // ✅ HorizontalPager for swiping through videos (like ThreadContainer)
    val horizontalPagerState = rememberPagerState(pageCount = { allVideos.size })
    val currentVideoIndex = horizontalPagerState.currentPage
    val currentVideo = allVideos.getOrNull(currentVideoIndex) ?: video
    val isOnParent = currentVideoIndex == 0

    // ✅ Check if overlay is showing - PAUSE videos when true
    val isOverlayShowing = showingCreatorProfileID != null

    // ✅ SWIPE UP TO EXIT
    val offsetY = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth() // Explicit full width - prevents edge bleed-through
            .background(Color.Black)
            .graphicsLayer {
                translationY = offsetY.value
                alpha = 1f - ((-offsetY.value) / screenHeightPx).coerceIn(0f, 0.5f)
            }
            .pointerInput(Unit) {
                val velocityTracker = VelocityTracker()

                detectVerticalDragGestures(
                    onDragStart = {
                        isDragging = true
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        isDragging = false

                        val velocity = velocityTracker.calculateVelocity().y
                        val currentOffset = offsetY.value
                        val dismissThreshold = screenHeightPx * 0.25f
                        val velocityThreshold = -800f

                        scope.launch {
                            if (currentOffset < -dismissThreshold || velocity < velocityThreshold) {
                                offsetY.animateTo(
                                    targetValue = -screenHeightPx,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                                onClose()
                            } else {
                                offsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        scope.launch { offsetY.animateTo(0f, spring()) }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val resistance = if (dragAmount > 0) 0.3f else 1f
                        scope.launch { offsetY.snapTo(offsetY.value + dragAmount * resistance) }
                    }
                )
            }
    ) {
        // ✅ HORIZONTAL PAGER for parent + children (matching ThreadContainer pattern)
        if (allVideos.size > 1) {
            HorizontalPager(
                state = horizontalPagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { index -> allVideos.getOrNull(index)?.id ?: index.toString() }
            ) { videoIndex ->
                val pagerVideo = allVideos[videoIndex]
                // ✅ PAUSE when overlay showing OR dragging
                val isVideoActive = !isDragging && !isOverlayShowing && videoIndex == currentVideoIndex
                val isChildVideo = videoIndex > 0

                Box(modifier = Modifier.fillMaxSize()) {
                    VideoPlayerComposable(
                        video = pagerVideo,
                        isActive = isVideoActive,
                        modifier = Modifier.fillMaxSize()
                    )

                    ContextualVideoOverlay(
                        video = pagerVideo,
                        overlayContext = if (isOnParent) OverlayContext.HOME_FEED else OverlayContext.THREAD_VIEW,
                        currentUserID = currentUserID,
                        threadVideo = if (isChildVideo) video else null,
                        isVisible = true,
                        currentUserTier = currentUserTier,
                        followManager = followManager,
                        engagementViewModel = engagementViewModel,
                        iconManager = iconMgr,
                        onAction = { action ->
                            when (action) {
                                is OverlayAction.NavigateToProfile -> {
                                    println("🧑 VideoPlayer: Navigate to profile ${action.userID}")
                                    pauseAllVideos()
                                    showingCreatorProfileID = action.userID
                                }
                                is OverlayAction.Engagement -> {
                                    val interactionType = when (action.type) {
                                        EngagementType.HYPE -> InteractionType.HYPE
                                        EngagementType.COOL -> InteractionType.COOL
                                        else -> null
                                    }
                                    interactionType?.let { onEngagement(it) }
                                }
                                is OverlayAction.Share -> onShare()
                                is OverlayAction.StitchRecording -> {
                                    println("🎬 VIDEOPLAYER: StitchRecording action")
                                    val isOwn = video.creatorID == currentUserID
                                    val ctx = if (isOwn) {
                                        RecordingContextFactory.createContinueThread(
                                            video.threadID ?: video.id,
                                            video.creatorName,
                                            video.title
                                        )
                                    } else {
                                        RecordingContextFactory.createStitchToThread(
                                            video.threadID ?: video.id,
                                            video.creatorName,
                                            video.title
                                        )
                                    }
                                    println("🎬 VIDEOPLAYER: Context created, showing modal")
                                    navigationCoordinator?.showModal(
                                        ModalState.RECORDING,
                                        mapOf(
                                            "context" to ctx,
                                            "parentVideo" to video
                                        )
                                    )
                                }
                                is OverlayAction.Follow -> println("➕ Follow user: ${pagerVideo.creatorID}")
                                is OverlayAction.Unfollow -> println("➖ Unfollow user: ${pagerVideo.creatorID}")
                                is OverlayAction.NavigateToThread -> println("🧵 Navigate to thread: ${pagerVideo.threadID}")
                            }
                        }
                    )
                }
            }
        } else {
            // Single video - no pager needed
            // ✅ PAUSE when overlay showing OR dragging
            val isVideoActive = !isDragging && !isOverlayShowing

            Box(modifier = Modifier.fillMaxSize()) {
                VideoPlayerComposable(
                    video = video,
                    isActive = isVideoActive,
                    modifier = Modifier.fillMaxSize()
                )

                ContextualVideoOverlay(
                    video = video,
                    overlayContext = OverlayContext.HOME_FEED,
                    currentUserID = currentUserID,
                    threadVideo = null,
                    isVisible = true,
                    currentUserTier = currentUserTier,
                    followManager = followManager,
                    engagementViewModel = engagementViewModel,
                    iconManager = iconMgr,
                    onAction = { action ->
                        when (action) {
                            is OverlayAction.NavigateToProfile -> {
                                println("🧑 VideoPlayer: Navigate to profile ${action.userID}")
                                pauseAllVideos()
                                showingCreatorProfileID = action.userID
                            }
                            is OverlayAction.Engagement -> {
                                val interactionType = when (action.type) {
                                    EngagementType.HYPE -> InteractionType.HYPE
                                    EngagementType.COOL -> InteractionType.COOL
                                    else -> null
                                }
                                interactionType?.let { onEngagement(it) }
                            }
                            is OverlayAction.Share -> onShare()
                            is OverlayAction.StitchRecording -> {
                                println("🎬 VIDEOPLAYER: StitchRecording action")
                                val isOwn = video.creatorID == currentUserID
                                val ctx = if (isOwn) {
                                    RecordingContextFactory.createContinueThread(
                                        video.threadID ?: video.id,
                                        video.creatorName,
                                        video.title
                                    )
                                } else {
                                    RecordingContextFactory.createStitchToThread(
                                        video.threadID ?: video.id,
                                        video.creatorName,
                                        video.title
                                    )
                                }
                                println("🎬 VIDEOPLAYER: Context created, showing modal")
                                navigationCoordinator?.showModal(
                                    ModalState.RECORDING,
                                    mapOf(
                                        "context" to ctx,
                                        "parentVideo" to video
                                    )
                                )
                            }
                            is OverlayAction.Follow -> println("➕ Follow user: ${video.creatorID}")
                            is OverlayAction.Unfollow -> println("➖ Unfollow user: ${video.creatorID}")
                            is OverlayAction.NavigateToThread -> println("🧵 Navigate to thread: ${video.threadID}")
                        }
                    }
                )
            }
        }

        // ✅ Close button - always visible
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }

        // ✅ Navigation dots (if multiple videos)
        if (allVideos.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allVideos.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentVideoIndex) 8.dp else 6.dp)
                            .background(
                                if (index == currentVideoIndex) Color.Cyan else Color.White.copy(alpha = 0.5f),
                                CircleShape
                            )
                    )
                }
            }
        }

        // ✅ Loading indicator for children
        if (isLoadingChildren && video.replyCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.Cyan,
                    strokeWidth = 2.dp
                )
            }
        }

        // ✅ Swipe hint
        var showSwipeHint by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            delay(3000)
            showSwipeHint = false
        }

        AnimatedVisibility(
            visible = showSwipeHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Swipe up to close",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }
    }

    // ✅ Creator Profile Overlay - video is PAUSED while this is showing
    showingCreatorProfileID?.let { profileUserID ->
        ProfileView(
            userID = profileUserID,
            viewingUserID = currentUserID,
            navigationCoordinator = null,
            onDismiss = { showingCreatorProfileID = null },
        )
    }
}