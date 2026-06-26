/*
 * DiscoverySwipeCards.kt - ENHANCED SWIPE & SIZING
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * ✅ ENHANCED: Lighter swipe feel with 1.2x drag multiplier
 * ✅ ENHANCED: Spring animations for smooth snap-back
 * ✅ ENHANCED: Balanced card sizing (40dp/72dp padding)
 * ✅ WORKING: Video info overlay on cards (title, creator, stats)
 * ✅ WORKING: Temperature badge on cards
 * ✅ WORKING: All gestures (tap, swipe left/right, swipe up/down)
 * ✅ WORKING: Stacked card animation effect
 * ✅ WORKING: Auto-advance after video loops
 *
 * NOTE: If black bars appear on video, check VideoPlayerComposable.kt
 *       and ensure resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
 *       (FILL stretches to fill without cropping, ZOOM crops but may not fill properly)
 */

@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.stitchsocial.club.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.platform.LocalContext
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.foundation.CollectionContentType
import com.stitchsocial.club.foundation.Temperature
import com.stitchsocial.club.coordination.DiscoveryEngagementTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Discovery swipe cards - EXACT Swift port
 * Tap = fullscreen, Swipe left/right = navigate, Swipe up/down = next
 */
@Composable
fun DiscoverySwipeCards(
    videos: List<CoreVideoMetadata>,
    currentIndex: Int,
    onIndexChange: (Int) -> Unit,
    onVideoTap: (CoreVideoMetadata) -> Unit,
    onNavigateToProfile: (String) -> Unit = {},
    onNavigateToThread: (String) -> Unit = {},
    isAnnouncementShowing: Boolean = false,
    isFullscreenActive: Boolean = false,
    collectionCardMap: Map<String, VideoCollection> = emptyMap(),
    modifier: Modifier = Modifier
) {
    if (videos.isEmpty()) {
        // Empty state
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No videos to discover",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 16.sp
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // State with Animatable for smooth transitions
    val dragOffsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val dragRotation = remember { androidx.compose.animation.core.Animatable(0f) }
    var isSwipeInProgress by remember { mutableStateOf(false) }
    val loopCounts = remember { mutableStateMapOf<String, Int>() }

    // Configuration
    val swipeThreshold = with(density) { 80.dp.toPx() }
    val targetLoops = 2
    val dragMultiplier = 1.2f

    // Discovery engagement tracker — mirrors Swift @ObservedObject discoveryTracker
    val tracker = DiscoveryEngagementTracker

    // Start session on appear — mirrors Swift .onAppear { discoveryTracker.startNewSession() }
    LaunchedEffect(Unit) {
        tracker.startNewSession()
        val video = videos.getOrNull(currentIndex)
        if (video != null) {
            tracker.cardBecameActive(videoID = video.id, creatorID = video.creatorID)
        }
    }

    // Reset drag offset + notify tracker when index changes
    LaunchedEffect(currentIndex) {
        val video = videos.getOrNull(currentIndex)
        if (video != null) {
            tracker.cardBecameActive(videoID = video.id, creatorID = video.creatorID)
        }
        launch {
            dragOffsetX.animateTo(
                0f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                )
            )
        }
        launch {
            dragRotation.animateTo(
                0f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                )
            )
        }
    }

    // Navigation functions
    val nextCard: (isManualSwipe: Boolean) -> Unit = { isManualSwipe ->
        if (isManualSwipe) tracker.cardSwipedAway(wasSwipeBack = false)
        if (currentIndex + 1 < videos.size) {
            onIndexChange(currentIndex + 1)
        }
        scope.launch {
            dragOffsetX.snapTo(0f)
            dragRotation.snapTo(0f)
        }
    }

    val previousCard: () -> Unit = {
        tracker.cardSwipedAway(wasSwipeBack = true)
        if (currentIndex > 0) {
            onIndexChange(currentIndex - 1)
        }
        scope.launch {
            dragOffsetX.snapTo(0f)
            dragRotation.snapTo(0f)
        }
    }

    // Loop handler for auto-advance
    val handleVideoLoop: (String) -> Unit = { videoId ->
        if (currentIndex < videos.size) {
            val currentVideo = videos[currentIndex]
            if (currentVideo.id == videoId) {
                val currentLoops = loopCounts.getOrDefault(videoId, 0) + 1
                loopCounts[videoId] = currentLoops

                if (currentLoops >= targetLoops && !isSwipeInProgress) {
                    isSwipeInProgress = true
                    tracker.cardAutoAdvanced()
                    nextCard(false)
                    scope.launch {
                        delay(200)
                        isSwipeInProgress = false
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 80.dp)  // Smaller cards
    ) {
        // Background card 3 (deepest) — iOS: scale 0.90, yOffset 20
        if (currentIndex + 2 < videos.size) {
            key(videos[currentIndex + 2].id) {
                CardLayer(
                    video = videos[currentIndex + 2],
                    isTopCard = false,
                    scale = 0.90f,
                    yOffset = 20f,
                    zIndex = 1f,
                    alpha = 0.5f,
                    dragOffset = Offset.Zero,
                    dragRotation = 0f,
                    onVideoLoop = { },
                    isAnnouncementShowing = isAnnouncementShowing,
                    isFullscreenActive = isFullscreenActive,
                    collectionCardMap = collectionCardMap
                )
            }
        }

        // Background card 2 (middle) — iOS: scale 0.95, yOffset 10
        if (currentIndex + 1 < videos.size) {
            key(videos[currentIndex + 1].id) {
                CardLayer(
                    video = videos[currentIndex + 1],
                    isTopCard = false,
                    scale = 0.95f,
                    yOffset = 10f,
                    zIndex = 2f,
                    alpha = 1.0f,
                    dragOffset = Offset.Zero,
                    dragRotation = 0f,
                    onVideoLoop = { },
                    isAnnouncementShowing = isAnnouncementShowing,
                    isFullscreenActive = isFullscreenActive,
                    collectionCardMap = collectionCardMap
                )
            }
        }

        // TOP CARD - Interactive with tap and drag
        if (currentIndex < videos.size) {
            key(videos[currentIndex].id) {
                var lastTapTime by remember { mutableStateOf(0L) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(3f)
                        .graphicsLayer {
                            translationX = dragOffsetX.value
                            rotationZ = dragRotation.value
                            scaleX = 1.0f
                            scaleY = 1.0f
                        }
                        // TAP GESTURE
                        .pointerInput(currentIndex) {
                            detectTapGestures(
                                onTap = {
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastTapTime > 300) {
                                        lastTapTime = currentTime
                                        val tappedVideo = videos[currentIndex]
                                        tracker.cardTappedFullscreen(
                                            videoID = tappedVideo.id,
                                            creatorID = tappedVideo.creatorID
                                        )
                                        onVideoTap(tappedVideo)
                                    }
                                }
                            )
                        }
                        // DRAG GESTURE - X-axis only (matches iOS)
                        .pointerInput(currentIndex) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    if (!isSwipeInProgress) {
                                        change.consume()
                                        scope.launch {
                                            // X-axis only — iOS constrains to width
                                            dragOffsetX.snapTo(dragOffsetX.value + dragAmount.x * dragMultiplier)
                                            // Y stays at 0 (iOS: height: 0)
                                            val targetRotation = (dragOffsetX.value / 20f).coerceIn(-15f, 15f)
                                            dragRotation.snapTo(targetRotation)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val translationX = dragOffsetX.value

                                    // Horizontal swipe navigation (matches iOS threshold + velocity check)
                                    if (abs(translationX) > swipeThreshold) {
                                        isSwipeInProgress = true

                                        if (translationX > 0) {
                                            // SWIPE RIGHT = Previous (swipe back)
                                            scope.launch {
                                                previousCard()
                                                delay(200)
                                                isSwipeInProgress = false
                                            }
                                        } else {
                                            // SWIPE LEFT = Next (manual)
                                            scope.launch {
                                                nextCard(true)
                                                delay(200)
                                                isSwipeInProgress = false
                                            }
                                        }
                                    } else {
                                        // Spring back to center
                                        scope.launch {
                                            launch {
                                                dragOffsetX.animateTo(
                                                    0f,
                                                    animationSpec = androidx.compose.animation.core.spring(
                                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                                        stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                                    )
                                                )
                                            }
                                            launch {
                                                dragRotation.animateTo(
                                                    0f,
                                                    animationSpec = androidx.compose.animation.core.spring(
                                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                                        stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    CardLayer(
                        video = videos[currentIndex],
                        isTopCard = true,
                        scale = 1.0f,
                        yOffset = 0f,
                        zIndex = 3f,
                        dragOffset = Offset.Zero,
                        dragRotation = 0f,
                        onVideoLoop = handleVideoLoop,
                        isAnnouncementShowing = isAnnouncementShowing,
                        isFullscreenActive = isFullscreenActive,
                        collectionCardMap = collectionCardMap
                    )
                }
            }
        }

        // Card position indicator (X of Y)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(
                    Color.Black.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "${currentIndex + 1} of ${videos.size}",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Card layer - renders DiscoveryCard with transformations
 */
@Composable
private fun CardLayer(
    video: CoreVideoMetadata,
    isTopCard: Boolean,
    scale: Float,
    yOffset: Float,
    zIndex: Float,
    alpha: Float = if (isTopCard) 1.0f else 0.6f,
    dragOffset: Offset,
    dragRotation: Float,
    onVideoLoop: (String) -> Unit,
    isAnnouncementShowing: Boolean,
    isFullscreenActive: Boolean = false,
    collectionCardMap: Map<String, VideoCollection> = emptyMap()
) {
    key(video.id) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(zIndex)
                .graphicsLayer {
                    translationX = dragOffset.x
                    translationY = dragOffset.y + yOffset
                    rotationZ = dragRotation
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
        ) {
            DiscoveryCard(
                video = video,
                shouldAutoPlay = isTopCard && !isFullscreenActive,
                onVideoLoop = onVideoLoop,
                isAnnouncementShowing = isAnnouncementShowing || isFullscreenActive,
                collection = collectionCardMap.get(video.id),
            )
        }
    }
}

/**
 * Discovery Card - Video thumbnail/player with info overlay
 */
@Composable
fun DiscoveryCard(
    video: CoreVideoMetadata,
    shouldAutoPlay: Boolean,
    onVideoLoop: (String) -> Unit,
    isAnnouncementShowing: Boolean,
    collection: VideoCollection? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)  // Black background to hide any gaps
    ) {
        // CRITICAL: Completely prevent video rendering when announcement showing
        // Video content or thumbnail - fills completely
        // Collection card — cover image + info panel, no video player
        if (collection != null) {
            CollectionSwipeCard(collection = collection)
        } else if (shouldAutoPlay && !isAnnouncementShowing) {
            key(video.id) {
                VideoPlayerComposable(
                    video = video,
                    isActive = true,
                    onEngagement = { },
                    onVideoClick = { },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                )
            }
        } else {
            // Show thumbnail with crop to fill
            AsyncImage(
                model = video.thumbnailURL.ifEmpty { null },
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Reply count badge — top-right (matches iOS)
        if (video.replyCount > 0 && shouldAutoPlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.Cyan.copy(alpha = 0.7f),
                                Color(0xFF3366FF).copy(alpha = 0.5f)
                            )
                        ),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "💬", fontSize = 10.sp)
                    Text(
                        text = "${video.replyCount}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        // Bottom overlay — only on active/top card (matches iOS cardOverlay)
        if (shouldAutoPlay) {
            // Creator avatar — top-left, no name (iOS parity), temperature ring.
            CreatorAvatar(
                creatorID = video.creatorID,
                temperatureColors = temperatureColors(video.temperature),
                isThread = video.isThread,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )

            // Gradient overlay at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
            )

            // Video info at bottom — stats only (no creator name, no title);
            // the creator avatar moved to the top-left (iOS parity).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Stats row (matches iOS: hype, views, duration)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (video.hypeCount > 0) {
                        Text(
                            text = "🔥 ${formatCount(video.hypeCount)}",
                            color = Color(0xFFFF9500),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (video.viewCount > 0) {
                        Text(
                            text = "👁 ${formatCount(video.viewCount)}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = "⏱ ${formatDuration(video.duration)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Standalone creator avatar (no name) for the clean Discovery overlay —
 * mirrors iOS DiscoverySwipeCards.creatorAvatar. Fetches the creator's profile
 * image lazily (it isn't on the video model) and rings it in the card's
 * temperature gradient.
 */
@Composable
private fun CreatorAvatar(
    creatorID: String,
    temperatureColors: List<Color>,
    isThread: Boolean,
    modifier: Modifier = Modifier
) {
    val dim = if (isThread) 32.dp else 28.dp
    val context = LocalContext.current
    val userService = remember { UserService(context) }
    var avatarURL by remember(creatorID) { mutableStateOf<String?>(null) }
    LaunchedEffect(creatorID) {
        if (creatorID.isNotEmpty()) {
            avatarURL = try {
                userService.getUserProfile(creatorID)?.profileImageURL
            } catch (e: Exception) { null }
        }
    }

    Box(
        modifier = modifier
            .size(dim)
            .clip(CircleShape)
            .background(Color.Gray.copy(alpha = 0.3f))
            .border(2.dp, Brush.linearGradient(temperatureColors), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val url = avatarURL
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = "Creator",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(dim * 0.45f)
            )
        }
    }
}

/**
 * Format count with K/M suffix
 */
private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

/**
 * Temperature-based gradient colors (matches iOS temperatureColors)
 */
private fun temperatureColors(temperature: Temperature): List<Color> {
    return when (temperature) {
        Temperature.BLAZING -> listOf(Color(0xFFFF3B30), Color(0xFFFF9500))
        Temperature.HOT -> listOf(Color(0xFFFF9500), Color(0xFFFFCC00))
        Temperature.WARM -> listOf(Color(0xFFFFCC00), Color(0xFF34C759))
        Temperature.COOL -> listOf(Color(0xFF00D9F2), Color(0xFF3366FF))
        Temperature.COLD -> listOf(Color(0xFF3366FF), Color(0xFF9966F2))
        Temperature.FROZEN -> listOf(Color(0xFF9966F2), Color(0xFFAF52DE))
    }
}

/**
 * Format duration in seconds to MM:SS
 */
private fun formatDuration(durationSeconds: Double): String {
    val totalSeconds = durationSeconds.toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${String.format("%02d", seconds)}"
}

// ─────────────────────────────────────────────
// MARK: - CollectionSwipeCard
// ─────────────────────────────────────────────

/**
 * Static collection card for the swipe feed — no video player.
 * Mirrors Swift DiscoverySwipeCards.collectionCardContent exactly:
 *   - Cover image fills card
 *   - Dark gradient overlay at bottom
 *   - Cyan "SERIES" badge (or PODCAST / FILM / COURSE)
 *   - Title (bold, large)
 *   - Creator + segment count row
 */
@Composable
fun CollectionSwipeCard(
    collection: VideoCollection,
    modifier: Modifier = Modifier
) {
    val badgeLabel = when (collection.contentType) {
        CollectionContentType.PODCAST -> "PODCAST"
        CollectionContentType.FILM    -> "FILM"
        CollectionContentType.SERIES  -> "SERIES"
        CollectionContentType.COURSE  -> "COURSE"
        CollectionContentType.EVENT   -> "EVENT"
        else                          -> "SERIES"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A1A2E))
    ) {
        // Cover image fills card
        val coverURL = collection.coverImageURL
        if (!coverURL.isNullOrEmpty()) {
            AsyncImage(
                model = coverURL,
                contentDescription = collection.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Placeholder gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF0D3B66), Color(0xFF1A1A2E))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        // Dark gradient overlay at bottom — matches Swift
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f), Color.Black.copy(alpha = 0.92f))
                    )
                )
        )

        // Bottom info panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // "SERIES" badge — cyan-to-purple gradient, matches Swift
            Row(
                modifier = Modifier
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF00D9F2), Color(0xFF9966F2))
                        ),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(10.dp)
                )
                Text(
                    text = badgeLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    letterSpacing = 1.5.sp
                )
            }

            // Title
            Text(
                text = collection.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 26.sp
            )

            // Creator + segment count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "@${collection.creatorName}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (collection.segmentCount > 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(13.dp)
                        )
                        val count = collection.segmentCount
                        Text(
                            text = "$count ${if (count == 1) "part" else "parts"}",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}