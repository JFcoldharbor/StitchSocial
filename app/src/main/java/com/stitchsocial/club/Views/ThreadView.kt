/*
 * ThreadView.kt - REWRITTEN
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Thread Visualization Screen
 * Dependencies: VideoService, CoreVideoMetadata
 *
 * REDESIGNED: Proper separation between cards and panel, no overlap issues
 *
 * IMPORTANT: This view should be displayed WITHOUT the CustomDippedTabBar
 * The navigation layer must hide the tab bar when ThreadView is active.
 * This matches iOS behavior where the tab bar is hidden in thread view.
 *
 * ThreadView uses Surface with z-index to ensure it overlays all other content
 * and prevents bleed-through from discovery/fullscreen views.
 *
 * Usage example in navigation:
 * ```kotlin
 * var showTabBar by remember { mutableStateOf(true) }
 * var showThreadView by remember { mutableStateOf(false) }
 *
 * Box {
 *     // Main content
 *     YourMainContent()
 *
 *     // Tab bar (conditionally visible)
 *     if (showTabBar) {
 *         CustomDippedTabBar(...)
 *     }
 *
 *     // ThreadView automatically hides tab bar
 *     if (showThreadView) {
 *         ThreadView(
 *             threadID = "...",
 *             videoService = videoService,
 *             onTabBarVisibilityChange = { visible -> showTabBar = visible },
 *             onDismiss = { showThreadView = false }
 *         )
 *     }
 * }
 * ```
 */

package com.stitchsocial.club.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.Intent
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// Foundation imports
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.ui.components.ThreadDepthBadge
import com.stitchsocial.club.ui.components.Thread3DInfoPanel
import com.stitchsocial.club.views.CardVideoCarouselView
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.FollowManager

/**
 * ThreadView - Redesigned with proper spacing
 *
 * Layout structure:
 * - TopBar (close button)
 * - Card carousel (550dp container with 320dp cards)
 * - 20dp spacer for separation
 * - Compact 3D info panel (140dp height)
 * - Tab bar space (100dp)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadView(
    threadID: String,
    videoService: VideoServiceImpl,
    targetVideoID: String? = null,
    currentUserID: String? = null,
    currentUserTier: UserTier = UserTier.ROOKIE,
    engagementCoordinator: EngagementCoordinator? = null,
    engagementViewModel: EngagementViewModel? = null,
    iconManager: FloatingIconManager? = null,
    followManager: FollowManager? = null,
    onTabBarVisibilityChange: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit,
    onVideoTap: (CoreVideoMetadata) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Services
    val createdVideoService = remember { videoService }
    val userService = remember { UserService(context) }

    val viewModel = remember {
        engagementViewModel ?: EngagementViewModel(
            coordinator = engagementCoordinator ?: EngagementCoordinator(
                videoService = createdVideoService,
                userService = userService
            ),
            videoService = createdVideoService,
            userService = userService
        )
    }

    val iconMgr = iconManager ?: remember { FloatingIconManager() }

    // Hide tab bar when ThreadView appears, show when dismissed
    DisposableEffect(Unit) {
        onTabBarVisibilityChange?.invoke(false) // Hide tab bar
        onDispose {
            onTabBarVisibilityChange?.invoke(true) // Show tab bar on cleanup
        }
    }

    // Pause all videos when ThreadView opens
    LaunchedEffect(Unit) {
        val intent = Intent("com.stitchsocial.club.PAUSE_ALL_VIDEOS")
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    // State
    var parentVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var childVideos by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showFullscreen by remember { mutableStateOf(false) }
    var selectedVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var showCarousel by remember { mutableStateOf(false) }
    var carouselVideos by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var directReplies by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var isPanelExpanded by remember { mutableStateOf(false) }

    // Pagination (20 children per page)
    var currentPage by remember { mutableStateOf(0) }
    val childrenPerPage = 20
    val totalPages = if (childVideos.isEmpty()) 1 else (childVideos.size + childrenPerPage - 1) / childrenPerPage

    val paginatedChildren = remember(childVideos, currentPage) {
        val startIndex = currentPage * childrenPerPage
        val endIndex = minOf(startIndex + childrenPerPage, childVideos.size)
        if (startIndex < childVideos.size) {
            childVideos.subList(startIndex, endIndex)
        } else {
            emptyList()
        }
    }

    val allVisibleVideos = remember(parentVideo, paginatedChildren) {
        if (parentVideo != null) {
            listOf(parentVideo!!) + paginatedChildren
        } else {
            emptyList()
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { allVisibleVideos.size }
    )

    // Brand colors
    val brandCyan = Color(0xFF00D9F2)
    val brandPurple = Color(0xFF9966F2)
    val brandPink = Color(0xFFF24E99)
    val brandMint = Color(0xFF66F2CC)
    val brandCream = Color(0xFFFAF8F5)
    val brandDark = Color(0xFF191926)

    // Helper: Navigate to a stepchild (depth 2+) by walking up the reply chain
    // Matches iOS navigateToStepchild exactly
    suspend fun navigateToStepchild(targetID: String) {
        try {
            val targetVideo = videoService.getVideoById(targetID) ?: return
            var currentVideo = targetVideo

            // Walk up until we find the depth-1 ancestor
            while (currentVideo.conversationDepth > 1 && currentVideo.replyToVideoID != null) {
                currentVideo = videoService.getVideoById(currentVideo.replyToVideoID!!) ?: return
            }

            // Must be depth 1 to proceed
            if (currentVideo.conversationDepth != 1) return

            // Load that depth-1 video's replies (the conversation)
            val stepchildren = videoService.getTimestampedReplies(currentVideo.id)
            val conversationVideos = listOf(currentVideo) + stepchildren

            // Verify target is in this conversation
            if (conversationVideos.none { it.id == targetID }) return

            // Open carousel focused on the target
            selectedVideo = targetVideo
            carouselVideos = conversationVideos
            delay(100)
            showCarousel = true
        } catch (_: Exception) { }
    }

    // Helper: Open video matching iOS openVideo() logic
    // Parent → fullscreen solo, Child → carousel with depth-2 replies
    fun openVideo(video: CoreVideoMetadata) {
        selectedVideo = video
        val isParent = video.id == parentVideo?.id

        if (isParent) {
            // Parent opens fullscreen solo (matches iOS showFullscreen)
            scope.launch {
                delay(100)
                showFullscreen = true
            }
        } else {
            // Child → fetch its depth-2 replies → open carousel
            scope.launch {
                try {
                    val allReplies = videoService.getTimestampedReplies(video.id)
                    val depth2Replies = allReplies.filter {
                        it.conversationDepth == video.conversationDepth + 1
                    }
                    carouselVideos = listOf(video)
                    directReplies = depth2Replies
                    delay(100)
                    showCarousel = true
                } catch (_: Exception) {
                    carouselVideos = listOf(video)
                    directReplies = emptyList()
                    showCarousel = true
                }
            }
        }
        onVideoTap(video)
    }

    // Load thread data - matches iOS loadThreadData() branching
    LaunchedEffect(threadID) {
        try {
            isLoading = true
            errorMessage = null

            // First, fetch the target video to check if it's a reply
            val targetVideo = videoService.getVideoById(threadID)

            if (targetVideo == null) {
                errorMessage = "Thread not found"
                isLoading = false
                return@LaunchedEffect
            }

            val isReply = targetVideo.replyToVideoID != null || targetVideo.conversationDepth > 0

            if (isReply) {
                // Target IS a reply — treat it as parent, load its replies as children
                val stepchildren = videoService.getTimestampedReplies(threadID)
                parentVideo = targetVideo
                childVideos = stepchildren
                isLoading = false
            } else {
                // Target is a root thread — load full thread data
                val (parent, children) = videoService.getThreadData(threadID)

                if (parent == null) {
                    errorMessage = "Thread not found"
                    isLoading = false
                    return@LaunchedEffect
                }

                parentVideo = parent
                childVideos = children.filter { it.conversationDepth == 1 }
                isLoading = false
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load thread: ${e.message}"
            isLoading = false
        }
    }

    // Handle targetVideoID navigation AFTER data is loaded
    // Separate LaunchedEffect to run when allVisibleVideos is populated
    LaunchedEffect(allVisibleVideos, targetVideoID) {
        if (targetVideoID == null || allVisibleVideos.isEmpty()) return@LaunchedEffect

        // Case 1: Target is the parent
        if (targetVideoID == parentVideo?.id) {
            currentPage = 0
            pagerState.scrollToPage(0)
            return@LaunchedEffect
        }

        // Case 2: Target is a direct child (depth 1)
        val childIndex = childVideos.indexOfFirst { it.id == targetVideoID }
        if (childIndex >= 0) {
            val targetPage = childIndex / childrenPerPage
            val indexOnPage = childIndex % childrenPerPage
            currentPage = targetPage
            // +1 because parent is at index 0
            pagerState.scrollToPage(indexOnPage + 1)
            return@LaunchedEffect
        }

        // Case 3: Target is a stepchild (depth 2+) — open its conversation
        navigateToStepchild(targetVideoID)
    }

    // Main UI
    Surface(
        modifier = modifier
            .fillMaxSize()
            .zIndex(1000f),
        color = Color.Black
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Animated marble background
            MarbleBackground()

            // Main content with panel at bottom
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Content Column (TopBar + Cards only)
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Top bar with counter
                    ThreadTopBar(
                        currentIndex = if (allVisibleVideos.isNotEmpty()) pagerState.currentPage else 0,
                        totalCount = allVisibleVideos.size,
                        brandPurple = brandPurple,
                        onClose = onDismiss
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Card carousel section - FIXED HEIGHT CONTAINER
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(550.dp)
                    ) {
                        when {
                            isLoading -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center),
                                    color = brandCyan
                                )
                            }
                            errorMessage != null -> {
                                ErrorView(
                                    message = errorMessage!!,
                                    onRetry = {
                                        // Reset and reload
                                        errorMessage = null
                                        isLoading = true
                                        parentVideo = null
                                        childVideos = emptyList()
                                        scope.launch {
                                            try {
                                                val retryVideo = videoService.getVideoById(threadID)
                                                if (retryVideo == null) {
                                                    errorMessage = "Thread not found"
                                                    isLoading = false
                                                    return@launch
                                                }
                                                val isReply = retryVideo.replyToVideoID != null || retryVideo.conversationDepth > 0
                                                if (isReply) {
                                                    val stepchildren = videoService.getTimestampedReplies(threadID)
                                                    parentVideo = retryVideo
                                                    childVideos = stepchildren
                                                } else {
                                                    val (parent, children) = videoService.getThreadData(threadID)
                                                    if (parent == null) {
                                                        errorMessage = "Thread not found"
                                                    } else {
                                                        parentVideo = parent
                                                        childVideos = children.filter { it.conversationDepth == 1 }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                errorMessage = "Failed to load thread: ${e.message}"
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                            else -> {
                                // Card carousel
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 60.dp),
                                    pageSpacing = 16.dp
                                ) { page ->
                                    val video = allVisibleVideos[page]
                                    val isActive = pagerState.currentPage == page

                                    ThreadCard(
                                        video = video,
                                        isActive = isActive,
                                        isOrigin = video.id == parentVideo?.id,
                                        brandCyan = brandCyan,
                                        brandPurple = brandPurple,
                                        onTap = { openVideo(video) }
                                    )
                                }

                                // Navigation arrows
                                NavigationArrows(
                                    pagerState = pagerState,
                                    totalVideos = allVisibleVideos.size,
                                    brandDark = brandDark,
                                    onPrevious = {
                                        scope.launch {
                                            if (pagerState.currentPage > 0) {
                                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                            }
                                        }
                                    },
                                    onNext = {
                                        scope.launch {
                                            if (pagerState.currentPage < allVisibleVideos.size - 1) {
                                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Thread 3D Info Panel - Anchored to bottom
                if (parentVideo != null && !showCarousel && !showFullscreen) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        Thread3DInfoPanel(
                            parentVideo = parentVideo!!,
                            childVideos = paginatedChildren,
                            selectedVideo = if (pagerState.currentPage < allVisibleVideos.size) {
                                allVisibleVideos[pagerState.currentPage]
                            } else null,
                            isExpanded = isPanelExpanded,
                            onExpandChange = { isPanelExpanded = it },
                            onClose = onDismiss,
                            onVideoTap = { video -> openVideo(video) }
                        )
                    }
                }
            }

            // Page navigation indicator (if multiple pages) - above panel
            if (totalPages > 1 && parentVideo != null && !showCarousel && !showFullscreen) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (isPanelExpanded) 250.dp else 170.dp)
                ) {
                    PageNavigation(
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageChange = { currentPage = it },
                        brandCyan = brandCyan
                    )
                }
            }
        }

        // CardVideoCarouselView overlay - highest z-index
        if (showCarousel && carouselVideos.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2000f) // Higher than ThreadView to overlay it
                    .background(Color.Black)
            ) {
                CardVideoCarouselView(
                    videos = carouselVideos,
                    parentVideo = parentVideo,
                    startingIndex = selectedVideo?.let { selected ->
                        carouselVideos.indexOfFirst { it.id == selected.id }
                    } ?: 0,
                    currentUserID = currentUserID,
                    currentUserTier = currentUserTier,
                    directReplies = directReplies.ifEmpty { null },
                    engagementCoordinator = engagementCoordinator,
                    engagementViewModel = viewModel,
                    iconManager = iconMgr,
                    followManager = followManager,
                    onDismiss = {
                        showCarousel = false
                        carouselVideos = emptyList()
                        directReplies = emptyList()
                        selectedVideo = null
                    },
                    onSelectReply = { selectedReply ->
                        scope.launch {
                            try {
                                val childVideo = selectedVideo ?: carouselVideos.firstOrNull()
                                if (childVideo != null) {
                                    val allReplies = videoService.getTimestampedReplies(childVideo.id)
                                    val conversationPartnerID = selectedReply.creatorID
                                    val conversationMessages = allReplies.filter { message ->
                                        message.creatorID == conversationPartnerID ||
                                                message.creatorID == currentUserID
                                    }
                                    carouselVideos = listOf(childVideo) + conversationMessages
                                }
                            } catch (e: Exception) {
                                // Keep current state
                            }
                        }
                    }
                )
            }
        }

        // Fullscreen video overlay for parent (matches iOS showFullscreen)
        if (showFullscreen && selectedVideo != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2000f)
                    .background(Color.Black)
            ) {
                CardVideoCarouselView(
                    videos = listOf(selectedVideo!!),
                    parentVideo = parentVideo,
                    startingIndex = 0,
                    currentUserID = currentUserID,
                    currentUserTier = currentUserTier,
                    directReplies = null,
                    engagementCoordinator = engagementCoordinator,
                    engagementViewModel = viewModel,
                    iconManager = iconMgr,
                    followManager = followManager,
                    onDismiss = {
                        showFullscreen = false
                        selectedVideo = null
                    },
                    onSelectReply = { }
                )
            }
        }
    }
}

// ===== SUB-COMPONENTS =====

/**
 * Animated marble background
 */
@Composable
private fun MarbleBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "marble")

    val offset1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset1"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAF8F5)) // Cream base
    ) {
        // Flowing gradient orbs
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF00D9F2).copy(alpha = 0.3f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.2f, size.height * 0.2f + offset1 * 0.1f)
                ),
                radius = size.width * 0.6f,
                center = Offset(size.width * 0.2f, size.height * 0.2f + offset1 * 0.1f)
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF9966F2).copy(alpha = 0.3f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.8f, size.height * 0.5f - offset1 * 0.08f)
                ),
                radius = size.width * 0.5f,
                center = Offset(size.width * 0.8f, size.height * 0.5f - offset1 * 0.08f)
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFF24E99).copy(alpha = 0.2f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.7f + offset1 * 0.05f)
                ),
                radius = size.width * 0.4f,
                center = Offset(size.width * 0.5f, size.height * 0.7f + offset1 * 0.05f)
            )
        }
    }
}

/**
 * Top bar with close button and "X of Y" counter (matches iOS topBar)
 */
@Composable
private fun ThreadTopBar(
    currentIndex: Int,
    totalCount: Int,
    brandPurple: Color,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 60.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Close button
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(44.dp)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Counter pill (matches iOS "X of Y" with swipe hints)
        if (totalCount > 0) {
            Column(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${currentIndex + 1}",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "of",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "$totalCount",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = if (currentIndex > 0) brandPurple else Color.White.copy(alpha = 0.2f)
                    )
                    Text(
                        text = "swipe",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = if (currentIndex < totalCount - 1) brandPurple else Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Balance spacer (same width as close button)
        Spacer(modifier = Modifier.size(44.dp))
    }
}

/**
 * Individual thread card with metadata overlay
 * Matches iOS ThreadDiscoveryCard: thumbnail fills card, title + creator at bottom
 */
@Composable
private fun ThreadCard(
    video: CoreVideoMetadata,
    isActive: Boolean,
    isOrigin: Boolean,
    brandCyan: Color,
    brandPurple: Color,
    onTap: () -> Unit
) {
    val brandPink = Color(0xFFF266B3)
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.85f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .width(280.dp)
            .height(480.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = if (isActive) 2.dp else 1.dp,
                brush = Brush.linearGradient(
                    colors = if (isActive)
                        listOf(brandCyan.copy(alpha = 0.6f), brandPurple.copy(alpha = 0.6f))
                    else
                        listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.1f))
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(onClick = onTap),
        contentAlignment = Alignment.BottomStart
    ) {
        // Thumbnail fills entire card
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            brandPurple.copy(alpha = 0.4f),
                            brandCyan.copy(alpha = 0.3f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (video.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = video.thumbnailURL,
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // Gradient overlay at bottom for text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
        )

        // Bottom overlay: Title + Creator pill
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Depth badge
            ThreadDepthBadge(
                depth = video.conversationDepth,
                modifier = Modifier
            )

            // Title
            Text(
                text = video.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Creator pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = if (isOrigin)
                                listOf(brandPurple, brandPink)
                            else
                                listOf(brandCyan, brandPurple)
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = video.creatorName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Navigation arrows
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavigationArrows(
    pagerState: androidx.compose.foundation.pager.PagerState,
    totalVideos: Int,
    brandDark: Color,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = pagerState.currentPage > 0
        ) {
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = "Previous",
                tint = if (pagerState.currentPage > 0) {
                    brandDark.copy(alpha = 0.7f)
                } else {
                    brandDark.copy(alpha = 0.2f)
                },
                modifier = Modifier.size(48.dp)
            )
        }

        IconButton(
            onClick = onNext,
            enabled = pagerState.currentPage < totalVideos - 1
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Next",
                tint = if (pagerState.currentPage < totalVideos - 1) {
                    brandDark.copy(alpha = 0.7f)
                } else {
                    brandDark.copy(alpha = 0.2f)
                },
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

/**
 * Page navigation for large threads
 */
@Composable
private fun PageNavigation(
    currentPage: Int,
    totalPages: Int,
    onPageChange: (Int) -> Unit,
    brandCyan: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = { if (currentPage > 0) onPageChange(currentPage - 1) },
            enabled = currentPage > 0
        ) {
            Text(
                text = "← Prev",
                color = if (currentPage > 0) brandCyan else Color.Gray,
                fontSize = 12.sp
            )
        }

        Text(
            text = "Page ${currentPage + 1}/$totalPages",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        TextButton(
            onClick = { if (currentPage < totalPages - 1) onPageChange(currentPage + 1) },
            enabled = currentPage < totalPages - 1
        ) {
            Text(
                text = "Next →",
                color = if (currentPage < totalPages - 1) brandCyan else Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Error view with retry (matches iOS errorView)
 */
@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Error",
            tint = Color(0xFFFF9500),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Failed to load",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 40.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF9966F2)
            )
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Retry", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

// Utility function
private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}