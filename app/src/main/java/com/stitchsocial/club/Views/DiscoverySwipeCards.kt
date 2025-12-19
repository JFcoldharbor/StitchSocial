/*
 * DiscoverySwipeCards.kt - STANDALONE SWIPE CARDS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * âœ… FIXED: Added missing DiscoveryCard composable
 * âœ… WORKING: All gestures (tap, swipe left/right, swipe up/down)
 */

@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.stitchsocial.club.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CoreVideoMetadata
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
    if (videos.isEmpty()) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // State
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragRotation by remember { mutableStateOf(0f) }
    var isSwipeInProgress by remember { mutableStateOf(false) }
    val loopCounts = remember { mutableStateMapOf<String, Int>() }

    // Configuration
    val swipeThreshold = with(density) { 80.dp.toPx() }
    val targetLoops = 2

    // Reset drag offset when index changes
    LaunchedEffect(currentIndex) {
        dragOffset = Offset.Zero
        dragRotation = 0f
    }

    // Navigation functions
    val nextCard: () -> Unit = {
        if (currentIndex + 1 < videos.size) {
            onIndexChange(currentIndex + 1)
        }
        dragOffset = Offset.Zero
        dragRotation = 0f
    }

    val previousCard: () -> Unit = {
        if (currentIndex > 0) {
            onIndexChange(currentIndex - 1)
        }
        dragOffset = Offset.Zero
        dragRotation = 0f
    }

    // Loop handler
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
            .padding(horizontal = 60.dp, vertical = 80.dp)
    ) {
        // Background cards (non-interactive)
        if (currentIndex + 2 < videos.size) {
            key(videos[currentIndex + 2].id) {
                CardLayer(
                    video = videos[currentIndex + 2],
                    isTopCard = false,
                    scale = 0.90f,
                    yOffset = 20f,
                    zIndex = 1f,
                    dragOffset = Offset.Zero,
                    dragRotation = 0f,
                    onVideoLoop = { }
                )
            }
        }

        if (currentIndex + 1 < videos.size) {
            key(videos[currentIndex + 1].id) {
                CardLayer(
                    video = videos[currentIndex + 1],
                    isTopCard = false,
                    scale = 0.95f,
                    yOffset = 10f,
                    zIndex = 2f,
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
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            rotationZ = dragRotation
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
                        // DRAG GESTURE
                        .pointerInput(currentIndex) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    if (!isSwipeInProgress) {
                                        change.consume()
                                        dragOffset += dragAmount
                                        dragRotation = (dragOffset.x / 20f).coerceIn(-15f, 15f)
                                    }
                                },
                                onDragEnd = {
                                    val translation = dragOffset
                                    val distance = sqrt(
                                        translation.x.pow(2) + translation.y.pow(2)
                                    )

                                    val isHorizontalSwipe = abs(translation.x) > abs(translation.y)

                                    if (isHorizontalSwipe) {
                                        // HORIZONTAL SWIPE = Navigation
                                        if (abs(translation.x) > swipeThreshold) {
                                            isSwipeInProgress = true

                                            if (translation.x > 0) {
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
                                            dragOffset = Offset.Zero
                                            dragRotation = 0f
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
                                            dragOffset = Offset.Zero
                                            dragRotation = 0f
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
    }
}

/**
 * Card layer - renders DiscoveryCard
 */
@Composable
private fun CardLayer(
    video: CoreVideoMetadata,
    isTopCard: Boolean,
    scale: Float,
    yOffset: Float,
    zIndex: Float,
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
                    alpha = if (isTopCard) 1.0f else 0.7f
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
 * Discovery Card - Video thumbnail or player
 */
@Composable
fun DiscoveryCard(
    video: CoreVideoMetadata,
    shouldAutoPlay: Boolean,
    onVideoLoop: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Color.Black)
    ) {
        if (shouldAutoPlay) {
            // Play video
            VideoPlayerComposable(
                video = video,
                isActive = true,
                onEngagement = { },
                onVideoClick = { },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Show thumbnail
            AsyncImage(
                model = video.thumbnailURL.ifEmpty { "https://via.placeholder.com/300x533" },
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}