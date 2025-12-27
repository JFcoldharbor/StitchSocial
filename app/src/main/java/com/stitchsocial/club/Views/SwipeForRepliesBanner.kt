/*
 * SwipeForRepliesBanner.kt - SWIPE FOR REPLIES INDICATOR
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Reply awareness banner for parent videos
 * Dependencies: Compose UI
 * Features: Animated banner showing reply count and swipe direction
 *
 * STANDALONE COMPONENT - Use in existing overlays
 */

package com.stitchsocial.club.views.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Animated banner indicating swipe direction for replies
 * Shows "Swipe → for X replies" message
 *
 * @param replyCount Number of replies to show
 */
@Composable
fun SwipeForRepliesBanner(
    replyCount: Int,
    modifier: Modifier = Modifier
) {
    // Animation state
    val infiniteTransition: InfiniteTransition = rememberInfiniteTransition(label = "swipe_animation")

    // Pulse animation
    val pulse: Float by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Arrow slide animation
    val arrowOffset: Float by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrow_slide"
    )

    // Glow animation
    val glowAlpha: Float by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val replyText: String = if (replyCount == 1) "reply" else "replies"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.scale(pulse)
    ) {
        // Main banner
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.7f),
                            Color(0xFF9C27B0).copy(alpha = 0.3f)
                        )
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Scissors icon
            Icon(
                imageVector = Icons.Default.ContentCut,
                contentDescription = null,
                tint = Color.Cyan,
                modifier = Modifier.size(14.dp)
            )

            // Reply count
            Text(
                text = "$replyCount $replyText",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            // Animated arrow
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = Color.Cyan.copy(alpha = glowAlpha + 0.3f),
                modifier = Modifier
                    .size(14.dp)
                    .offset(x = arrowOffset.dp)
            )
        }

        // "Swipe" hint text
        Text(
            text = "Swipe →",
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * Compact version of the swipe banner (just icon and count)
 */
@Composable
fun CompactSwipeBanner(
    replyCount: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition: InfiniteTransition = rememberInfiniteTransition(label = "compact_animation")

    val pulse: Float by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "compact_pulse"
    )

    Row(
        modifier = modifier
            .scale(pulse)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.ContentCut,
            contentDescription = null,
            tint = Color.Cyan,
            modifier = Modifier.size(10.dp)
        )

        Text(
            text = "$replyCount",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Icon(
            imageVector = Icons.Default.ArrowForward,
            contentDescription = null,
            tint = Color.Cyan,
            modifier = Modifier.size(10.dp)
        )
    }
}

// Custom easing
private val EaseInOutSine: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
private val EaseInOutQuad: Easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)