/*
 * HomeFeedView.kt - OPTIMIZED WITH AGGRESSIVE PRELOADING
 * STITCH SOCIAL - ANDROID KOTLIN
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
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.HomeFeedService
import com.stitchsocial.club.services.FeedViewHistory
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.MainAppTab
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ThreadData
import com.stitchsocial.club.camera.RecordingContextFactory
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager
import com.stitchsocial.club.FollowManager
import com.stitchsocial.club.services.SocialSignalService
import com.stitchsocial.club.foundation.SocialSignal
import com.stitchsocial.club.views.ProfileView

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedView(
    userID: String,
    navigationCoordinator: NavigationCoordinator?,
    isAnnouncementShowing: Boolean = false,
    onShowThreadView: (threadID: String, targetVideoID: String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val videoService = remember { VideoServiceImpl() }
    val userService = remember { UserService(context) }
    val authService = remember { AuthService() }

    LaunchedEffect(Unit) {
        FeedViewHistory.initialize(context)
    }

    val feedService = remember {
        HomeFeedService(videoService = videoService, userService = userService, context = context)
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

    // Social Signal / Megaphone state
    var socialSignals by remember { mutableStateOf<List<SocialSignal>>(emptyList()) }

    LaunchedEffect(showingCreatorProfileID) {
        if (showingCreatorProfileID != null) pauseAllVideos()
    }

    // Pause when announcement showing
    LaunchedEffect(isAnnouncementShowing) {
        if (isAnnouncementShowing) pauseAllVideos()
    }

    LaunchedEffect(userID) {
        try {
            isLoading = true
            errorMessage = null
            val feedThreads = feedService.loadFeed(userID, 40)
            followingCount = feedService.getFollowingCount()
            feedService.saveCurrentFeed(feedThreads)
            baseThreads = feedThreads
            println("✅ HOME FEED: Loaded ${feedThreads.size} threads")

            if (feedThreads.isNotEmpty()) {
                feedService.preloadChildrenAround(0, feedThreads)
            }

            // Load social signals (megaphone: videos hyped by people you follow)
            socialSignals = SocialSignalService.shared.loadActiveSignals(userID)
        } catch (e: Exception) {
            println("🚨 HOME FEED: Error - ${e.message}")
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when {
            isLoading -> LoadingView()
            errorMessage != null -> ErrorView(errorMessage!!) {
                scope.launch {
                    try {
                        isLoading = true
                        errorMessage = null
                        val feedThreads = feedService.loadFeed(userID, 40)
                        followingCount = feedService.getFollowingCount()
                        baseThreads = feedThreads
                    } catch (e: Exception) {
                        errorMessage = e.message
                    } finally {
                        isLoading = false
                    }
                }
            }
            threads.isEmpty() -> EmptyFeedView(followingCount > 0) {
                navigationCoordinator?.navigateToTab(MainAppTab.DISCOVERY)
            }
            else -> {
                // Build combined feed: threads + interleaved social signals
                val feedItems = remember(threads, socialSignals) {
                    val items = mutableListOf<FeedItem>()
                    threads.forEachIndexed { index, thread ->
                        items.add(FeedItem.VideoThread(thread))
                        // Insert a social signal card every 5 threads
                        val signalIndex = index / 5
                        if (index > 0 && index % 5 == 0 && signalIndex - 1 < socialSignals.size) {
                            items.add(FeedItem.Signal(socialSignals[signalIndex - 1]))
                        }
                    }
                    items.toList()
                }

                val combinedPagerState = rememberPagerState(pageCount = { feedItems.size })

                // Children loading, seen tracking, position save
                LaunchedEffect(combinedPagerState.currentPage) {
                    val item = feedItems.getOrNull(combinedPagerState.currentPage)

                    if (item is FeedItem.VideoThread) {
                        val thread = item.thread
                        val threadIndex = threads.indexOf(thread)

                        // Load children
                        val cachedChildren = feedService.getCachedChildren(thread.id)
                        if (cachedChildren != null) {
                            loadedChildren[thread.id] = cachedChildren
                        } else if (!loadedChildren.containsKey(thread.id) && thread.parentVideo.replyCount > 0) {
                            try {
                                val children = feedService.loadThreadChildren(thread.id)
                                if (children.isNotEmpty()) {
                                    loadedChildren[thread.id] = children
                                }
                            } catch (_: Exception) {}
                        }

                        // Preload children around current
                        if (threadIndex >= 0) {
                            feedService.preloadChildrenAround(threadIndex, baseThreads)
                            feedService.saveCurrentPosition(threadIndex, 0, thread.id)
                        }

                        // Mark seen after 3s
                        delay(3000)
                        feedService.markVideoSeen(thread.parentVideo.id)
                    }
                }

                // ENDLESS SCROLL — separate effect so delay(3000) doesn't block it
                LaunchedEffect(combinedPagerState.currentPage, feedItems.size) {
                    val remaining = feedItems.size - combinedPagerState.currentPage
                    if (remaining <= 10 && !isLoadingMore && baseThreads.isNotEmpty()) {
                        isLoadingMore = true

                        // Try to get fresh content
                        val beforeSize = feedService.getCurrentFeed().size
                        try { feedService.loadMoreContent(userID) } catch (_: Exception) {}
                        val afterSize = feedService.getCurrentFeed().size

                        if (afterSize > beforeSize) {
                            val newOnes = feedService.getCurrentFeed().drop(beforeSize)
                            baseThreads = baseThreads + newOnes
                            println("✅ FEED: Added ${newOnes.size} new threads")
                        } else {
                            // No new content — recycle for endless scroll
                            val recycled = baseThreads.toList().shuffled()
                            baseThreads = baseThreads + recycled
                            println("🔄 FEED: Recycled ${recycled.size} threads for endless scroll")
                        }

                        isLoadingMore = false
                    }
                }

                VerticalPager(state = combinedPagerState, modifier = Modifier.fillMaxSize()) { page ->
                    when (val item = feedItems[page]) {
                        is FeedItem.VideoThread -> {
                            val thread = item.thread
                            val isCurrentPage = combinedPagerState.currentPage == page
                            key(thread.id) {
                                ThreadVideoCard(
                                    thread = thread,
                                    userID = userID,
                                    isCurrentPage = isCurrentPage,
                                    engagementViewModel = engagementViewModel,
                                    iconManager = iconManager,
                                    followManager = followManager,
                                    navigationCoordinator = navigationCoordinator,
                                    pauseAllVideos = pauseAllVideos,
                                    onCreatorProfileTap = { creatorID ->
                                        showingCreatorProfileID = creatorID
                                        pauseAllVideos()
                                    },
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

                                        pauseAllVideos()

                                        navigationCoordinator?.showModal(
                                            ModalState.RECORDING,
                                            mapOf(
                                                "context" to ctx,
                                                "parentVideo" to video
                                            )
                                        )
                                    },
                                    onShowThreadView = onShowThreadView
                                )
                            }
                        }

                        is FeedItem.Signal -> {
                            val signal = item.signal
                            key("signal-${signal.id}") {
                                SocialSignalCardView(
                                    signal = signal,
                                    userID = userID,
                                    onTapToWatch = {
                                        // Record engagement — this is when view/watch time counts
                                        scope.launch {
                                            SocialSignalService.shared.recordEngagement(signal.id, userID)
                                        }
                                        // Navigate to the video fullscreen
                                        // TODO: Load video by signal.videoID and push to player
                                    },
                                    onAppeared = {
                                        // Track impression for 2-strike dismissal
                                        scope.launch {
                                            SocialSignalService.shared.recordImpression(signal.id, userID)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showingCreatorProfileID != null) {
            ProfileView(
                userID = showingCreatorProfileID!!,
                viewingUserID = userID,
                navigationCoordinator = navigationCoordinator,
                onDismiss = { showingCreatorProfileID = null },
            )
        }
    }
}

@Composable
private fun ThreadVideoCard(
    thread: ThreadData,
    userID: String,
    isCurrentPage: Boolean,
    engagementViewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    followManager: FollowManager,
    navigationCoordinator: NavigationCoordinator?,
    pauseAllVideos: () -> Unit,
    onCreatorProfileTap: (String) -> Unit,
    onStitchTap: (CoreVideoMetadata) -> Unit,
    onShowThreadView: (threadID: String, targetVideoID: String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    val allVideos = remember(thread.childVideos.size) {
        listOf(thread.parentVideo) + thread.childVideos
    }
    val videoCount = allVideos.size

    var currentIndex by remember { mutableStateOf(0) }
    val currentVideo = allVideos[currentIndex]
    val isOnParent = currentIndex == 0

    val offsetX = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Derive isActive from page visibility and drag state (both parent and children can play)
    val isActive = isCurrentPage && !isDragging

    val screenWidthPx = with(density) { screenWidth.toPx() }
    val dragThreshold = screenWidthPx * 0.3f
    val velocityTracker = remember { VelocityTracker() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(videoCount) {
                if (videoCount <= 1) return@pointerInput

                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        scope.launch {
                            val velocity = velocityTracker.calculateVelocity().x
                            val shouldSnap = kotlin.math.abs(offsetX.value) > dragThreshold || kotlin.math.abs(velocity) > 1000f

                            if (shouldSnap) {
                                val targetIndex = if (offsetX.value < 0) {
                                    (currentIndex + 1).coerceIn(0, videoCount - 1)
                                } else {
                                    (currentIndex - 1).coerceIn(0, videoCount - 1)
                                }

                                offsetX.animateTo(
                                    targetValue = if (targetIndex > currentIndex) -screenWidthPx
                                    else if (targetIndex < currentIndex) screenWidthPx
                                    else 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )

                                if (targetIndex != currentIndex) {
                                    currentIndex = targetIndex
                                }

                                offsetX.snapTo(0f)
                            } else {
                                offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                            }

                            isDragging = false
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            offsetX.animateTo(0f)
                            isDragging = false
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        val newOffset = (offsetX.value + dragAmount).coerceIn(
                            if (currentIndex == 0) -screenWidthPx else -screenWidthPx * 1.5f,
                            if (currentIndex == videoCount - 1) screenWidthPx else screenWidthPx * 1.5f
                        )
                        offsetX.snapTo(newOffset)
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value }
        ) {
            key(currentVideo.id) {
                VideoPlayerComposable(
                    video = currentVideo,
                    isActive = isActive,
                    modifier = Modifier.fillMaxSize()
                )
            }

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
                        is OverlayAction.NavigateToThread -> {
                            val threadID = currentVideo.threadID ?: currentVideo.id
                            onShowThreadView(threadID, currentVideo.id)
                        }
                        else -> {}
                    }
                }
            )
        }

        if (isDragging && videoCount > 1) {
            val dragOffset = offsetX.value

            if (dragOffset < 0 && currentIndex < videoCount - 1) {
                val nextVideo = allVideos[currentIndex + 1]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = screenWidthPx + dragOffset }
                        .background(Color.Black)
                ) {
                    VideoThumbnailPeek(video = nextVideo, modifier = Modifier.fillMaxSize())
                }
            }

            if (dragOffset > 0 && currentIndex > 0) {
                val prevVideo = allVideos[currentIndex - 1]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = -screenWidthPx + dragOffset }
                        .background(Color.Black)
                ) {
                    VideoThumbnailPeek(video = prevVideo, modifier = Modifier.fillMaxSize())
                }
            }
        }

        // Edge peek navigation (matches iOS VideoNavigationPeeks)
        if (videoCount > 1 && isCurrentPage) {
            VideoNavigationPeeks(
                allVideos = allVideos,
                currentVideoIndex = currentIndex
            )
        }

        // Stitch indicator — capsule bars (matches iOS stitchIndicator)
        if (videoCount > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(videoCount) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .background(
                                if (index == currentIndex) Color.White
                                else Color.White.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Loading feed...", color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ErrorView(error: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
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
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.VideoLibrary, "No videos", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                if (isFollowingAnyone) "No videos from followed users yet" else "Follow users to see their videos here",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isFollowingAnyone) "The people you follow haven't posted yet" else "Start by discovering creators",
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

// MARK: - Feed Item Types (Thread + Social Signal)

sealed class FeedItem {
    data class VideoThread(val thread: ThreadData) : FeedItem()
    data class Signal(val signal: SocialSignal) : FeedItem()
}

// MARK: - Social Signal Card (Megaphone: "X hyped this")

@Composable
private fun SocialSignalCardView(
    signal: SocialSignal,
    userID: String,
    onTapToWatch: () -> Unit,
    onAppeared: () -> Unit
) {
    // Track impression on appear
    LaunchedEffect(signal.id) { onAppeared() }

    val tierColor = when (signal.engagerTier) {
        "founder" -> Color(0xFFFFD700)
        "coFounder" -> Color(0xFFD9A5FF)
        "topCreator" -> Color(0xFFFF6B35)
        "legendary" -> Color(0xFF00DEFF)
        "ambassador" -> Color(0xFF00C759)
        "elite" -> Color(0xFF007AFF)
        "partner" -> Color(0xFF999999)
        else -> Color.White
    }

    val tierEmoji = when (signal.engagerTier) {
        "founder" -> "👑"
        "coFounder" -> "⭐"
        "topCreator" -> "🔥"
        "legendary" -> "💎"
        "ambassador" -> "🏆"
        "elite" -> "⚡"
        "partner" -> "🤝"
        else -> ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
        ) {
            // Megaphone banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        tierColor.copy(alpha = 0.15f),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Engager profile pic
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(tierColor.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (!signal.engagerProfileImageURL.isNullOrEmpty()) {
                        AsyncImage(
                            model = signal.engagerProfileImageURL,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            signal.engagerName.take(1).uppercase(),
                            color = tierColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tierEmoji, fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(signal.engagerName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(4.dp))
                        Text("hyped this", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                    Text("+${signal.hypeWeight} hype", color = tierColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.weight(1f))
            }

            // Video preview — tap to watch (ONLY this counts as a real view)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (!signal.videoThumbnailURL.isNullOrEmpty()) {
                    AsyncImage(
                        model = signal.videoThumbnailURL,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                // Play button overlay
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onTapToWatch,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = "Play",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    Text("Tap to watch", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }

                // Video title bar at bottom
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(signal.videoTitle, color = Color.White, fontSize = 13.sp, maxLines = 1)
                    Text("by @${signal.videoCreatorName}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
        }
    }
}