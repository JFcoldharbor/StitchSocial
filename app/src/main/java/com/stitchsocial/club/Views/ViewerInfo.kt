/*
 * ViewersBottomSheet.kt - WHO VIEWED YOUR VIDEO
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * iOS-style bottom sheet showing list of viewers
 * Tap on view count to see who watched
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.stitchsocial.club.foundation.UserTier
import java.util.Date

/**
 * Data class for viewer info
 */
data class ViewerInfo(
    val userID: String,
    val displayName: String,
    val username: String,
    val profileImageURL: String?,
    val tier: UserTier,
    val viewedAt: Date,
    val isFollowing: Boolean = false
)

/**
 * Bottom sheet showing who viewed the video
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewersBottomSheet(
    isVisible: Boolean,
    videoID: String,
    viewCount: Int,
    viewers: List<ViewerInfo>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onViewerClick: (String) -> Unit,
    onFollowClick: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (isVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = Color(0xFF1A1A1A),
            contentColor = Color.White,
            dragHandle = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    )
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RemoveRedEye,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Views",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = formatViewCount(viewCount),
                            fontSize = 16.sp,
                            color = Color.Gray
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }

                HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))

                // Content
                if (isLoading) {
                    // Loading state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else if (viewers.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "No viewers yet",
                                fontSize = 16.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = "Share your video to get more views!",
                                fontSize = 13.sp,
                                color = Color.Gray.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    // Viewers list
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(viewers) { viewer ->
                            ViewerRow(
                                viewer = viewer,
                                onClick = { onViewerClick(viewer.userID) },
                                onFollowClick = { onFollowClick(viewer.userID) }
                            )
                        }

                        // Show message if there are more viewers
                        if (viewCount > viewers.size) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+${viewCount - viewers.size} more viewers",
                                        fontSize = 13.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerRow(
    viewer: ViewerInfo,
    onClick: () -> Unit,
    onFollowClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Profile image
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(viewer.profileImageURL ?: "")
                    .crossfade(true)
                    .build(),
                contentDescription = "Profile",
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Gray.copy(alpha = 0.3f)),
                contentScale = ContentScale.Crop
            )

            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = viewer.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )

                    // Tier badge
                    ViewerTierBadge(tier = viewer.tier)
                }

                Text(
                    text = "@${viewer.username}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                // Time ago
                Text(
                    text = viewerTimeAgo(viewer.viewedAt),
                    fontSize = 11.sp,
                    color = Color.Gray.copy(alpha = 0.7f)
                )
            }
        }

        // Follow button (if not already following)
        if (!viewer.isFollowing) {
            Button(
                onClick = onFollowClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF6B35)
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Follow",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            OutlinedButton(
                onClick = onFollowClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
            ) {
                Text(
                    text = "Following",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun ViewerTierBadge(tier: UserTier) {
    val text = when (tier) {
        UserTier.FOUNDER -> "👑"
        UserTier.CO_FOUNDER -> "🥈"
        UserTier.TOP_CREATOR -> "⭐"
        UserTier.LEGENDARY -> "🔥"
        UserTier.PARTNER -> "✓"
        UserTier.ELITE -> "💎"
        UserTier.AMBASSADOR -> "🌟"
        UserTier.INFLUENCER -> "📢"
        UserTier.VETERAN -> "🎖"
        UserTier.RISING -> "📈"
        UserTier.ROOKIE -> null
    }

    if (text != null) {
        Text(
            text = text,
            fontSize = 12.sp
        )
    }
}

private fun formatViewCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

private fun viewerTimeAgo(date: Date): String {
    val now = System.currentTimeMillis()
    val diff = now - date.time

    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    val weeks = days / 7

    return when {
        seconds < 60 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        weeks < 4 -> "${weeks}w ago"
        else -> "${days / 30}mo ago"
    }
}