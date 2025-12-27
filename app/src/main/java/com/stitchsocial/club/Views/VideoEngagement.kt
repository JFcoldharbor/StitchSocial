/*
 * VideoMetadataRow.kt - VIDEO STATS DISPLAY COMPONENT
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Extracted Video Stats Display Component
 * Dependencies: Compose UI
 * Features: Views, stitches, engagement stats with optional viewers tap (creator-only)
 *
 * EXACT PORT: VideoMetadataRow.swift
 */

package com.stitchsocial.club.Views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Video engagement data model
 */
data class VideoEngagement(
    val videoID: String,
    val creatorID: String,
    val hypeCount: Int,
    val coolCount: Int,
    val shareCount: Int,
    val replyCount: Int,
    val viewCount: Int,
    val lastEngagementAt: Long = System.currentTimeMillis()
) {
    val totalEngagements: Int
        get() = hypeCount + coolCount

    val engagementRatio: Double
        get() {
            val total = totalEngagements
            return if (total > 0) hypeCount.toDouble() / total.toDouble() else 0.5
        }
}

/**
 * Video metadata row showing views, stitches, and engagement stats
 *
 * @param engagement Video engagement data (null = loading state)
 * @param isUserVideo Whether this is the current user's video (enables tap on views)
 * @param onViewersTap Callback when views button is tapped (creator only)
 */
@Composable
fun VideoMetadataRow(
    engagement: VideoEngagement?,
    isUserVideo: Boolean,
    onViewersTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (engagement != null) {
            // Views count - TAPPABLE for creator only
            if (isUserVideo) {
                ViewsButton(
                    count = engagement.viewCount,
                    onClick = onViewersTap
                )
            } else {
                // Non-creator view (not tappable)
                ViewsStat(count = engagement.viewCount)
            }

            // Separator
            Separator()

            // Stitch count
            StitchesStat(count = engagement.replyCount)

        } else {
            // Loading state
            LoadingState()
        }
    }
}

/**
 * Tappable views button (for creators)
 */
@Composable
private fun ViewsButton(
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF9C27B0).copy(alpha = 0.2f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(10.dp)
        )

        Text(
            text = "${formatCount(count)} views",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

/**
 * Non-tappable views stat
 */
@Composable
private fun ViewsStat(count: Int) {
    StatItem(
        icon = Icons.Default.Visibility,
        text = "${formatCount(count)} views",
        tint = Color.White.copy(alpha = 0.7f)
    )
}

/**
 * Stitches stat
 */
@Composable
private fun StitchesStat(count: Int) {
    StatItem(
        icon = Icons.Default.ContentCut,
        text = "${formatCount(count)} stitches",
        tint = Color.Cyan.copy(alpha = 0.7f),
        textColor = Color.Cyan.copy(alpha = 0.9f)
    )
}

/**
 * Engagement stat (optional)
 */
@Composable
fun EngagementStat(count: Int) {
    StatItem(
        icon = Icons.Default.Favorite,
        text = formatCount(count),
        tint = Color.Red.copy(alpha = 0.7f),
        textColor = Color.Red.copy(alpha = 0.9f)
    )
}

/**
 * Generic stat item
 */
@Composable
private fun StatItem(
    icon: ImageVector,
    text: String,
    tint: Color,
    textColor: Color = Color.White.copy(alpha = 0.9f)
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(10.dp)
        )

        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

/**
 * Separator dot
 */
@Composable
private fun Separator() {
    Text(
        text = "•",
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.5f)
    )
}

/**
 * Loading state placeholder
 */
@Composable
private fun LoadingState() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(10.dp)
        )

        Text(
            text = "Loading...",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

/**
 * Format count for display (1K, 1.5M, etc.)
 */
private fun formatCount(count: Int): String {
    return when {
        count < 1000 -> count.toString()
        count < 1_000_000 -> {
            val formatted = count / 1000.0
            String.format("%.1fK", formatted).replace(".0", "")
        }
        count < 1_000_000_000 -> {
            val formatted = count / 1_000_000.0
            String.format("%.1fM", formatted).replace(".0", "")
        }
        else -> {
            val formatted = count / 1_000_000_000.0
            String.format("%.1fB", formatted).replace(".0", "")
        }
    }
}

/**
 * Extended metadata row with all stats
 */
@Composable
fun ExtendedVideoMetadataRow(
    engagement: VideoEngagement?,
    isUserVideo: Boolean,
    onViewersTap: () -> Unit,
    showEngagement: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (engagement != null) {
            // Views count
            if (isUserVideo) {
                ViewsButton(count = engagement.viewCount, onClick = onViewersTap)
            } else {
                ViewsStat(count = engagement.viewCount)
            }

            Separator()

            // Stitch count
            StitchesStat(count = engagement.replyCount)

            // Optional engagement count
            if (showEngagement && engagement.totalEngagements > 0) {
                Separator()
                EngagementStat(count = engagement.totalEngagements)
            }

        } else {
            LoadingState()
        }
    }
}