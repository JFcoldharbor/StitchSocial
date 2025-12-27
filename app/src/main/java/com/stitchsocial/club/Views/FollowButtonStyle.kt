/*
 * FollowButton.kt - FOLLOW/UNFOLLOW BUTTON COMPONENT
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Reusable follow button with loading state
 * Dependencies: Compose UI
 * Features: Follow/unfollow toggle, loading indicator, multiple styles
 *
 * STANDALONE COMPONENT - Use in existing overlays
 */

package com.stitchsocial.club.views.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Follow button style variants
 */
enum class FollowButtonStyle {
    STANDARD,    // Full button with text
    COMPACT,     // Smaller, icon-focused
    MINIMAL,     // Just icon
    PILL         // Rounded pill shape
}

/**
 * Reusable follow/unfollow button
 *
 * @param userID The user ID to follow/unfollow
 * @param isFollowing Current follow state
 * @param onToggle Callback when button is pressed
 * @param isHidden Whether to hide the button (e.g., for own profile)
 * @param style Button style variant
 * @param isLoading Whether an action is in progress
 */
@Composable
fun FollowButton(
    userID: String,
    isFollowing: Boolean,
    onToggle: () -> Unit,
    isHidden: Boolean = false,
    style: FollowButtonStyle = FollowButtonStyle.STANDARD,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (isHidden) return

    // Animated colors
    val backgroundColor: Color by animateColorAsState(
        targetValue = if (isFollowing) Color.Gray.copy(alpha = 0.3f) else Color.Cyan,
        animationSpec = tween(200),
        label = "bg_color"
    )

    val textColor: Color by animateColorAsState(
        targetValue = if (isFollowing) Color.Gray else Color.White,
        animationSpec = tween(200),
        label = "text_color"
    )

    when (style) {
        FollowButtonStyle.STANDARD -> StandardFollowButton(
            isFollowing = isFollowing,
            isLoading = isLoading,
            backgroundColor = backgroundColor,
            textColor = textColor,
            onToggle = onToggle,
            modifier = modifier
        )

        FollowButtonStyle.COMPACT -> CompactFollowButton(
            isFollowing = isFollowing,
            isLoading = isLoading,
            backgroundColor = backgroundColor,
            textColor = textColor,
            onToggle = onToggle,
            modifier = modifier
        )

        FollowButtonStyle.MINIMAL -> MinimalFollowButton(
            isFollowing = isFollowing,
            isLoading = isLoading,
            textColor = textColor,
            onToggle = onToggle,
            modifier = modifier
        )

        FollowButtonStyle.PILL -> PillFollowButton(
            isFollowing = isFollowing,
            isLoading = isLoading,
            backgroundColor = backgroundColor,
            textColor = textColor,
            onToggle = onToggle,
            modifier = modifier
        )
    }
}

@Composable
private fun StandardFollowButton(
    isFollowing: Boolean,
    isLoading: Boolean,
    backgroundColor: Color,
    textColor: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(25.dp))
            .background(backgroundColor)
            .clickable(enabled = !isLoading) { onToggle() }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = textColor,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = if (isFollowing) Icons.Default.Check else Icons.Default.PersonAdd,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
        }

        Text(
            text = if (isFollowing) "Following" else "Follow",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

@Composable
private fun CompactFollowButton(
    isFollowing: Boolean,
    isLoading: Boolean,
    backgroundColor: Color,
    textColor: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(enabled = !isLoading) { onToggle() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                color = textColor,
                strokeWidth = 1.dp
            )
        } else {
            Icon(
                imageVector = if (isFollowing) Icons.Default.Check else Icons.Default.PersonAdd,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(10.dp)
            )
        }

        Text(
            text = if (isFollowing) "Following" else "Follow",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

@Composable
private fun MinimalFollowButton(
    isFollowing: Boolean,
    isLoading: Boolean,
    textColor: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor: Color = if (isFollowing) Color.Gray else Color.Cyan

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(50)
            )
            .clickable(enabled = !isLoading) { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = textColor,
                strokeWidth = 1.5.dp
            )
        } else {
            val iconTint: Color = if (isFollowing) Color.Gray else Color.Cyan
            Icon(
                imageVector = if (isFollowing) Icons.Default.Check else Icons.Default.PersonAdd,
                contentDescription = if (isFollowing) "Unfollow" else "Follow",
                tint = iconTint,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun PillFollowButton(
    isFollowing: Boolean,
    isLoading: Boolean,
    backgroundColor: Color,
    textColor: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .clickable(enabled = !isLoading) { onToggle() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = textColor,
                strokeWidth = 1.5.dp
            )
        } else {
            Icon(
                imageVector = if (isFollowing) Icons.Default.Check else Icons.Default.PersonAdd,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(14.dp)
            )

            Text(
                text = if (isFollowing) "Following" else "Follow",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}