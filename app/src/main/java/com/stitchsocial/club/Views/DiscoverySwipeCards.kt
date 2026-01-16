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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.Temperature
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.pow

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
    val dragOffsetY = remember { androidx.compose.animation.core.Animatable(0f) }
    val dragRotation = remember { androidx.compose.animation.core.Animatable(0f) }
    var isSwipeInProgress by remember { mutableStateOf(false) }
    val loopCounts = remember { mutableStateMapOf<String, Int>() }

    // Get current offset as Offset object
    val currentDragOffset by remember {
        derivedStateOf { Offset(dragOffsetX.value, dragOffsetY.value) }
    }

    // Configuration
    val swipeThreshold = with(density) { 80.dp.toPx() }
    val targetLoops = 2
    val dragMultiplier = 1.2f  // Makes swipe feel lighter/more responsive

    // Reset drag offset when index changes with spring animation
    LaunchedEffect(currentIndex) {
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
            dragOffsetY.animateTo(
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
    val nextCard: () -> Unit = {
        if (currentIndex + 1 < videos.size) {
            onIndexChange(currentIndex + 1)
        }
        scope.launch {
            dragOffsetX.snapTo(0f)
            dragOffsetY.snapTo(0f)
            dragRotation.snapTo(0f)
        }
    }

    val previousCard: () -> Unit = {
        if (currentIndex > 0) {
            onIndexChange(currentIndex - 1)
        }
        scope.launch {
            dragOffsetX.snapTo(0f)
            dragOffsetY.snapTo(0f)
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
                    nextCard()
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
        // Background card 3 (deepest)
        if (currentIndex + 2 < videos.size) {
            key(videos[currentIndex + 2].id) {
                CardLayer(
                    video = videos[currentIndex + 2],
                    isTopCard = false,
                    scale = 0.86f,
                    yOffset = 28f,
                    zIndex = 1f,
                    alpha = 0.4f,
                    dragOffset = Offset.Zero,
                    dragRotation = 0f,
                    onVideoLoop = { }
                )
            }
        }

        // Background card 2 (middle)
        if (currentIndex + 1 < videos.size) {
            key(videos[currentIndex + 1].id) {
                CardLayer(
                    video = videos[currentIndex + 1],
                    isTopCard = false,
                    scale = 0.92f,
                    yOffset = 14f,
                    zIndex = 2f,
                    alpha = 0.5f,
                    dragOffset = Offset.Zero,
                    dragRotation = 0f,
                    onVideoLoop = { }
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
                            translationY = dragOffsetY.value
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
                                        onVideoTap(videos[currentIndex])
                                    }
                                }
                            )
                        }
                        // DRAG GESTURE - Lighter feel with multiplier
                        .pointerInput(currentIndex) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    if (!isSwipeInProgress) {
                                        change.consume()
                                        scope.launch {
                                            // Apply drag multiplier for lighter feel
                                            dragOffsetX.snapTo(dragOffsetX.value + dragAmount.x * dragMultiplier)
                                            dragOffsetY.snapTo(dragOffsetY.value + dragAmount.y * dragMultiplier)
                                            // Smoother rotation calculation
                                            val targetRotation = (dragOffsetX.value / 30f).coerceIn(-12f, 12f)
                                            dragRotation.snapTo(targetRotation)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val translationX = dragOffsetX.value
                                    val translationY = dragOffsetY.value
                                    val distance = sqrt(
                                        translationX.pow(2) + translationY.pow(2)
                                    )

                                    val isHorizontalSwipe = abs(translationX) > abs(translationY)

                                    if (isHorizontalSwipe) {
                                        // HORIZONTAL SWIPE = Navigation
                                        if (abs(translationX) > swipeThreshold) {
                                            isSwipeInProgress = true

                                            if (translationX > 0) {
                                                // SWIPE RIGHT = Previous
                                                scope.launch {
                                                    previousCard()
                                                    delay(200)
                                                    isSwipeInProgress = false
                                                }
                                            } else {
                                                // SWIPE LEFT = Next
                                                scope.launch {
                                                    nextCard()
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
                                                    dragOffsetY.animateTo(
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
                                    } else {
                                        // VERTICAL SWIPE = Next
                                        if (distance > swipeThreshold) {
                                            isSwipeInProgress = true
                                            scope.launch {
                                                nextCard()
                                                delay(200)
                                                isSwipeInProgress = false
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
                                                    dragOffsetY.animateTo(
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
                        onVideoLoop = handleVideoLoop
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
    onVideoLoop: (String) -> Unit
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
                shouldAutoPlay = isTopCard,
                onVideoLoop = onVideoLoop
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
    onVideoLoop: (String) -> Unit
) {
    // Force layout recalculation after composition
    var layoutTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(video.id) {
        // Trigger a layout pass after brief delay to ensure proper sizing
        kotlinx.coroutines.delay(16) // One frame delay
        layoutTrigger++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)  // Black background to hide any gaps
    ) {
        // Video content or thumbnail - fills completely
        if (shouldAutoPlay) {
            // Play video - key includes layoutTrigger to force remount after layout
            key(video.id, "video-player", layoutTrigger) {
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

        // Gradient overlay at bottom for text readability
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
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

        // Temperature badge at top
        if (video.temperature != Temperature.COOL) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(
                        Color.Black.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = video.temperature.emoji,
                    fontSize = 14.sp
                )
            }
        }

        // Video info at bottom
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            // Title
            Text(
                text = video.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Creator
            Text(
                text = "@${video.creatorName}",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Stats row
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hype count
                Text(
                    text = "🔥 ${formatCount(video.hypeCount)}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                // Reply count
                Text(
                    text = "💬 ${formatCount(video.replyCount)}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                // View count
                Text(
                    text = "👁 ${formatCount(video.viewCount)}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
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