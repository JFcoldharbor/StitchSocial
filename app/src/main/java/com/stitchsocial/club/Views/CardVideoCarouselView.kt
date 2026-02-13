/*
 * CardVideoCarouselView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Card-based Video Carousel for Thread Conversations
 * Dependencies: VideoPlayerComposable, CoreVideoMetadata, ContextualVideoOverlay
 * Features: Swipeable video cards, conversation navigation, proper video playback
 *
 * MATCHES iOS CardVideoCarouselView.swift
 */

package com.stitchsocial.club.views

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// Foundation imports
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.UserTier

// View imports
import com.stitchsocial.club.views.VideoPlayerComposable
import com.stitchsocial.club.views.ContextualVideoOverlay
import com.stitchsocial.club.views.OverlayContext
import com.stitchsocial.club.views.OverlayAction

// ViewModel imports
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// Coordination imports
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.FollowManager

/**
 * CardVideoCarouselView - Swipeable video carousel for thread conversations
 *
 * Used when viewing a video that has replies - shows parent video + all reply videos
 * with a conversation navigation bar to filter by person
 *
 * @param videos All videos in this conversation (parent + children)
 * @param parentVideo The original parent video
 * @param startingIndex Which video to start on (default 0)
 * @param currentUserID Current user ID for engagement
 * @param currentUserTier Current user tier
 * @param directReplies Direct replies to parent for navigation bar
 * @param engagementCoordinator For hype/cool
 * @param engagementViewModel For UI state
 * @param iconManager For floating icons
 * @param followManager For follow state
 * @param onDismiss Callback when user closes
 * @param onSelectReply Callback when user taps a reply in nav bar
 */
@Composable
fun CardVideoCarouselView(
    videos: List<CoreVideoMetadata>,
    parentVideo: CoreVideoMetadata? = null,
    startingIndex: Int = 0,
    currentUserID: String? = null,
    currentUserTier: UserTier = UserTier.ROOKIE,
    directReplies: List<CoreVideoMetadata>? = null,
    engagementCoordinator: EngagementCoordinator? = null,
    engagementViewModel: EngagementViewModel? = null,
    iconManager: FloatingIconManager? = null,
    followManager: FollowManager? = null,
    onDismiss: () -> Unit,
    onSelectReply: ((CoreVideoMetadata) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // State for 3D carousel
    var currentPage by remember { mutableStateOf(startingIndex) }
    var hasAppeared by remember { mutableStateOf(false) }
    var currentConversationPartner by remember { mutableStateOf<String?>(null) }
    var offsetY by remember { mutableStateOf(0f) }
    var dragOffsetX by remember { mutableStateOf(0f) }

    val iconMgr = iconManager ?: remember { FloatingIconManager() }

    // Pause all videos when this view opens
    LaunchedEffect(Unit) {
        val intent = Intent("com.stitchsocial.club.PAUSE_ALL_VIDEOS")
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        delay(500) // Small delay before starting playback
        hasAppeared = true
    }

    // Get current video
    val currentVideo = videos.getOrNull(currentPage) ?: videos.first()

    // Check if current user is conversation participant
    val conversationParticipantIDs = remember(videos) {
        videos.map { it.creatorID }.toSet()
    }
    val isConversationParticipant = currentUserID?.let { conversationParticipantIDs.contains(it) } ?: false

    // Brand colors - Match iOS exactly
    val brandCyan = Color(0xFF00D9F2)       // RGB(0.0, 0.85, 0.95)
    val brandPurple = Color(0xFF9966F2)     // RGB(0.6, 0.4, 0.95)
    val brandPink = Color(0xFFF266B3)       // RGB(0.95, 0.4, 0.7)
    val brandCream = Color(0xFFFAF8F5)      // RGB(0.98, 0.97, 0.96)
    val brandDark = Color(0xFF191926)       // RGB(0.1, 0.1, 0.15)

    // Card dimensions - Match ThreadView
    val cardWidth = 280.dp
    val cardHeight = 480.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = offsetY
                alpha = 1f - ((-offsetY) / screenHeightPx).coerceIn(0f, 0.5f)
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (offsetY < -screenHeightPx * 0.25f) {
                            scope.launch {
                                onDismiss()
                            }
                        } else {
                            scope.launch {
                                val startValue = offsetY
                                animate(
                                    initialValue = startValue,
                                    targetValue = 0f,
                                    animationSpec = spring()
                                ) { value, _ ->
                                    offsetY = value
                                }
                            }
                        }
                    },
                    onVerticalDrag = { _, dragAmount ->
                        val resistance = if (dragAmount > 0) 0.3f else 1f
                        offsetY = (offsetY + dragAmount * resistance).coerceAtMost(0f)
                    }
                )
            }
    ) {
        // Marble background - Match iOS
        MarbleBackground(
            configuration = configuration,
            brandCyan = brandCyan,
            brandPurple = brandPurple,
            brandPink = brandPink,
            brandCream = brandCream
        )
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header with swipe indicator and context
            CarouselHeader(
                parentVideo = parentVideo,
                videoCount = videos.size
            )

            Spacer(modifier = Modifier.weight(1f))

            // 3D Card Carousel - Matches ThreadView + iOS design
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(550.dp), // Fixed height like ThreadView
                contentAlignment = Alignment.Center
            ) {
                // Card stack with 3D effect
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val threshold = 30.dp.toPx()
                                    if (kotlin.math.abs(dragOffsetX) > threshold) {
                                        if (dragOffsetX > 0 && currentPage > 0) {
                                            currentPage -= 1
                                        } else if (dragOffsetX < 0 && currentPage < videos.size - 1) {
                                            currentPage += 1
                                        }
                                    }
                                    dragOffsetX = 0f
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    dragOffsetX += dragAmount
                                }
                            )
                        }
                ) {
                    videos.forEachIndexed { index, video ->
                        val isActive = currentPage == index && hasAppeared
                        val distance = index - currentPage

                        CarouselDiscoveryCard(
                            video = video,
                            isActive = isActive,
                            distance = distance,
                            cardWidth = cardWidth,
                            cardHeight = cardHeight,
                            brandCyan = brandCyan,
                            brandPurple = brandPurple,
                            currentUserID = currentUserID,
                            currentUserTier = currentUserTier,
                            followManager = followManager,
                            engagementViewModel = engagementViewModel,
                            iconManager = iconMgr,
                            parentVideo = if (index > 0) parentVideo else null
                        )
                    }
                }

                // Close button overlay
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(44.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.6f),
                                    Color.Black.copy(alpha = 0.3f)
                                )
                            ),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom section - Navigation bar or page indicator
            if (!directReplies.isNullOrEmpty()) {
                ConversationNavigationBar(
                    parentVideo = videos.firstOrNull() ?: parentVideo!!,
                    directReplies = directReplies,
                    currentConversationPartner = currentConversationPartner,
                    brandCyan = brandCyan,
                    onSelectReply = { selectedReply: CoreVideoMetadata ->
                        currentConversationPartner = selectedReply.creatorID
                        onSelectReply?.invoke(selectedReply)
                    }
                )
            } else {
                // Page indicator dots
                if (videos.size > 1) {
                    PageIndicator(
                        currentPage = currentPage,
                        totalPages = videos.size,
                        brandPurple = brandPurple
                    )
                }
            }
        }
    }
}

// ===== SUB-COMPONENTS =====

/**
 * Header showing context and swipe indicator - Matches iOS
 */
@Composable
private fun CarouselHeader(
    parentVideo: CoreVideoMetadata?,
    videoCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Handle indicator - centered
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF191926).copy(alpha = 0.3f))
        )

        // Context information
        if (parentVideo != null) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Replies to ${parentVideo.creatorName}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF191926)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$videoCount replies",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF191926).copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Conversation Navigation Bar - matches iOS ConversationNavigationBar.swift
 * Glass background, reply thumbnails with message badges, selection scale
 */
@Composable
private fun ConversationNavigationBar(
    parentVideo: CoreVideoMetadata,
    directReplies: List<CoreVideoMetadata>,
    currentConversationPartner: String?,
    brandCyan: Color,
    onSelectReply: (CoreVideoMetadata) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.6f),
                        Color.Black.copy(alpha = 0.8f)
                    )
                )
            )
            .padding(vertical = 12.dp)
    ) {
        // Header
        Text(
            text = "Direct Replies (${directReplies.size})",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Scrollable thumbnails
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            items(directReplies.size) { index ->
                val reply = directReplies[index]
                ReplyThumbnailButton(
                    video = reply,
                    isSelected = currentConversationPartner == reply.creatorID,
                    messageCount = reply.replyCount,
                    brandCyan = brandCyan,
                    onClick = { onSelectReply(reply) }
                )
            }
        }
    }
}

/**
 * Reply thumbnail button with selection indicator and message badge
 * Matches iOS ReplyThumbnailButton exactly
 */
@Composable
private fun ReplyThumbnailButton(
    video: CoreVideoMetadata,
    isSelected: Boolean,
    messageCount: Int,
    brandCyan: Color,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1.0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "replyScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick)
    ) {
        // Thumbnail with border
        Box(
            modifier = Modifier
                .size(70.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) brandCyan else Color.White.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            if (video.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = video.thumbnailURL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Gray.copy(alpha = 0.3f))
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Creator name
        Text(
            text = video.creatorName,
            color = if (isSelected) brandCyan else Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(70.dp),
            textAlign = TextAlign.Center
        )

        // Message count badge
        if (messageCount > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$messageCount msg${if (messageCount == 1) "" else "s"}",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) brandCyan else Color(0xFF9966F2).copy(alpha = 0.8f)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Page indicator dots
 */
@Composable
private fun PageIndicator(
    currentPage: Int,
    totalPages: Int,
    brandPurple: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalPages.coerceAtMost(10)) { index ->
            val isActive = index == currentPage
            Box(
                modifier = Modifier
                    .size(if (isActive) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) brandPurple else Color(0xFF191926).copy(alpha = 0.3f)
                    )
            )
            if (index < totalPages - 1 && index < 9) {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

/**
 * CarouselDiscoveryCard - Individual card in 3D carousel
 * Matches iOS ThreadDiscoveryCard: edge-to-edge thumbnail, gradient overlay, metadata
 */
@Composable
private fun CarouselDiscoveryCard(
    video: CoreVideoMetadata,
    isActive: Boolean,
    distance: Int,
    cardWidth: Dp,
    cardHeight: Dp,
    brandCyan: Color,
    brandPurple: Color,
    currentUserID: String?,
    currentUserTier: UserTier,
    followManager: FollowManager?,
    engagementViewModel: EngagementViewModel?,
    iconManager: FloatingIconManager,
    parentVideo: CoreVideoMetadata?
) {
    val brandPink = Color(0xFFF266B3)
    val absDistance = kotlin.math.abs(distance)
    val isOrigin = parentVideo == null // First video (index 0) is parent

    val scale by animateFloatAsState(
        targetValue = when (absDistance) {
            0 -> 1f
            1 -> 0.88f
            else -> 0.75f
        },
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
        label = "scale"
    )

    val offsetX by animateDpAsState(
        targetValue = (distance * 112).dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
        label = "offsetX"
    )

    val opacity by animateFloatAsState(
        targetValue = when (absDistance) {
            0 -> 1f
            1 -> 0.85f
            else -> 0.5f
        },
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
        label = "opacity"
    )

    val cardZIndex = (100 - absDistance).toFloat()

    Box(
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .offset(x = offsetX)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = opacity
            }
            .zIndex(cardZIndex)
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
            .shadow(
                elevation = if (isActive) 20.dp else 10.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = if (isActive) brandPurple.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.2f)
            )
    ) {
        // Base: gradient fallback
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
                )
        )

        if (isActive) {
            // Active card - video player fills card
            VideoPlayerComposable(
                video = video,
                isActive = true,
                modifier = Modifier.fillMaxSize()
            )

            // Contextual overlay on active card
            ContextualVideoOverlay(
                video = video,
                overlayContext = OverlayContext.CAROUSEL,
                currentUserID = currentUserID,
                threadVideo = parentVideo,
                isVisible = true,
                currentUserTier = currentUserTier,
                followManager = followManager,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                onAction = { action: OverlayAction -> }
            )
        } else {
            // Inactive card - thumbnail fills entire card edge-to-edge
            if (video.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = video.thumbnailURL,
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
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

            // Bottom metadata: Title + Creator pill
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = video.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Creator pill with gradient
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
                    Text(
                        text = "@${video.creatorName}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ===== NEW COMPONENTS FOR iOS MATCHING =====

/**
 * Marble Background - Matches iOS design
 */
@Composable
private fun MarbleBackground(
    configuration: android.content.res.Configuration,
    brandCyan: Color,
    brandPurple: Color,
    brandPink: Color,
    brandCream: Color
) {
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // Base cream color
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brandCream)
        )

        // Cyan orb - top-left
        Box(
            modifier = Modifier
                .size(screenWidth * 1.2f)
                .offset(x = screenWidth * 0.2f - screenWidth * 0.6f, y = screenHeight * 0.15f - screenWidth * 0.6f)
                .background(brandCyan.copy(alpha = 0.4f), CircleShape)
                .blur(80.dp)
        )

        // Purple orb - right-middle
        Box(
            modifier = Modifier
                .size(screenWidth * 1.0f)
                .offset(x = screenWidth * 0.85f - screenWidth * 0.5f, y = screenHeight * 0.4f - screenWidth * 0.5f)
                .background(brandPurple.copy(alpha = 0.35f), CircleShape)
                .blur(90.dp)
        )

        // Pink orb - bottom-left
        Box(
            modifier = Modifier
                .size(screenWidth * 0.9f)
                .offset(x = screenWidth * 0.1f - screenWidth * 0.45f, y = screenHeight * 0.75f - screenWidth * 0.45f)
                .background(brandPink.copy(alpha = 0.3f), CircleShape)
                .blur(70.dp)
        )

        // Radial gradient overlay for depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.05f)),
                        center = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        radius = screenWidth.value
                    )
                )
        )
    }
}