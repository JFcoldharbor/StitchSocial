/*
 * FloatingBubbleNotification.kt - SMART REPLY AWARENESS NOTIFICATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Smart Reply Awareness Notification (COMPACT VERSION)
 * Dependencies: Compose UI, Animations
 * Features: Small, subtle notifications for parent videos with replies
 *
 * STANDALONE COMPONENT - Use in existing video players
 */

package com.stitchsocial.club.views.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Configuration for reply notification
 */
data class ReplyNotificationConfig(
    val videoDuration: Long,        // Duration in milliseconds
    val hasReplies: Boolean,
    val replyCount: Int,
    val currentStitchIndex: Int
) {
    /**
     * Only show on parent videos (index 0) with replies
     */
    val shouldShow: Boolean
        get() = currentStitchIndex == 0 && hasReplies && replyCount > 0

    /**
     * Calculate when to show notification (70% through parent video)
     */
    val showTriggerTimeMs: Long
        get() = (videoDuration * 0.7).toLong()
}

/**
 * Floating bubble notification for stitch awareness
 * Shows swipe instructions instead of auto-navigating
 *
 * @param config Notification configuration
 * @param currentVideoPositionMs Current playback position in milliseconds
 * @param onDismiss Callback when notification is dismissed
 */
@Composable
fun FloatingBubbleNotification(
    config: ReplyNotificationConfig,
    currentVideoPositionMs: Long,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isVisible: Boolean by remember { mutableStateOf(false) }
    var showingSwipeMessage: Boolean by remember { mutableStateOf(false) }
    var hasTriggered: Boolean by remember { mutableStateOf(false) }

    // Animation states
    val scale: Animatable<Float, AnimationVector1D> = remember { Animatable(0.8f) }
    val alpha: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) }

    // Check if we should show notification
    LaunchedEffect(currentVideoPositionMs, config.shouldShow) {
        if (config.shouldShow &&
            currentVideoPositionMs >= config.showTriggerTimeMs &&
            !hasTriggered) {
            hasTriggered = true
            isVisible = true
            showingSwipeMessage = false

            // Phase 1: Animate in
            launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
            launch { alpha.animateTo(1f, tween(400)) }

            // Phase 2: After 1.5 seconds, change to swipe message
            delay(1500)
            showingSwipeMessage = true

            // Phase 3: Auto-hide after 5 seconds total
            delay(3500)

            // Animate out
            launch { scale.animateTo(0.9f, tween(300)) }
            launch { alpha.animateTo(0f, tween(300)) }

            delay(300)
            isVisible = false
            onDismiss()
        }
    }

    // Reset when video changes
    LaunchedEffect(config.videoDuration) {
        hasTriggered = false
        isVisible = false
        showingSwipeMessage = false
    }

    if (!isVisible) return

    val screenWidth: Int = LocalConfiguration.current.screenWidthDp
    val screenHeight: Int = LocalConfiguration.current.screenHeightDp

    Box(
        modifier = modifier
            .fillMaxSize()
            .wrapContentSize(align = Alignment.TopEnd)
            .padding(
                top = (screenHeight * 0.22).dp,
                end = (screenWidth * 0.18).dp
            )
    ) {
        BubbleContent(
            showingSwipeMessage = showingSwipeMessage,
            replyCount = config.replyCount,
            scaleValue = scale.value,
            alphaValue = alpha.value
        )
    }
}

/**
 * Bubble content view
 */
@Composable
private fun BubbleContent(
    showingSwipeMessage: Boolean,
    replyCount: Int,
    scaleValue: Float,
    alphaValue: Float
) {
    val replyText: String = if (replyCount == 1) "reply" else "replies"
    val message: String = if (!showingSwipeMessage) {
        "Incoming stitch"
    } else {
        "Swipe → for $replyCount $replyText"
    }

    val borderColors: List<Color> = if (showingSwipeMessage) {
        listOf(Color.Cyan, Color(0xFF9C27B0), Color(0xFFE91E63))
    } else {
        listOf(Color(0xFFFF8C00), Color(0xFFE91E63), Color(0xFF9C27B0))
    }

    val iconColor: Color = if (showingSwipeMessage) Color.Cyan else Color(0xFFFF8C00)

    Row(
        modifier = Modifier
            .scale(scaleValue)
            .graphicsLayer { this.alpha = alphaValue }
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.8f),
                        Color(0xFF9C27B0).copy(alpha = 0.3f)
                    )
                ),
                shape = RoundedCornerShape(50)
            )
            .padding(1.5.dp)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.9f),
                        Color(0xFF9C27B0).copy(alpha = 0.4f)
                    )
                ),
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Icon(
            imageVector = Icons.Default.ContentCut,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(12.dp)
        )

        // Message
        Text(
            text = message,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        // Arrow (only for swipe message)
        if (showingSwipeMessage) {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = Color(0xFFFF8C00),
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

/**
 * Factory methods for creating notifications
 */
object FloatingBubbleNotificationFactory {

    /**
     * Create notification for parent video with replies
     */
    fun parentVideoWithReplies(
        videoDurationMs: Long,
        replyCount: Int,
        currentStitchIndex: Int
    ): ReplyNotificationConfig {
        return ReplyNotificationConfig(
            videoDuration = videoDurationMs,
            hasReplies = replyCount > 0,
            replyCount = replyCount,
            currentStitchIndex = currentStitchIndex
        )
    }

    /**
     * Create notification from thread data
     */
    fun fromThreadData(
        parentVideoDurationMs: Long,
        childVideoCount: Int,
        currentStitchIndex: Int
    ): ReplyNotificationConfig {
        return ReplyNotificationConfig(
            videoDuration = parentVideoDurationMs,
            hasReplies = childVideoCount > 0,
            replyCount = childVideoCount,
            currentStitchIndex = currentStitchIndex
        )
    }
}

/**
 * Stateful wrapper that manages notification lifecycle
 */
@Composable
fun ReplyAwarenessNotification(
    videoDurationMs: Long,
    currentPositionMs: Long,
    replyCount: Int,
    currentStitchIndex: Int,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val config: ReplyNotificationConfig = remember(videoDurationMs, replyCount, currentStitchIndex) {
        FloatingBubbleNotificationFactory.parentVideoWithReplies(
            videoDurationMs = videoDurationMs,
            replyCount = replyCount,
            currentStitchIndex = currentStitchIndex
        )
    }

    FloatingBubbleNotification(
        config = config,
        currentVideoPositionMs = currentPositionMs,
        onDismiss = onDismiss,
        modifier = modifier
    )
}