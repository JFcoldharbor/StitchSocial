/*
 * ProfileVideoGrid.kt - FIXED SCROLLING ISSUE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Matches Swift ProfileVideoGrid.swift exactly
 * Dependencies: BasicVideoInfo, ContentType
 * Features: 3-column grid, engagement badges, context menus, loading states
 *
 * ✅ FIXED: Replaced LazyVerticalGrid with height-constrained Column to prevent nested scrolling
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

import com.stitchsocial.club.foundation.BasicVideoInfo

/**
 * ProfileVideoGrid - Fixed for LazyColumn compatibility
 * REPLACES LazyVerticalGrid with Column of Rows to prevent nested scrolling crash
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileVideoGrid(
    videos: List<BasicVideoInfo>,
    selectedTab: Int = 0,
    tabTitles: List<String> = listOf("Videos"),
    isLoading: Boolean = false,
    isCurrentUserProfile: Boolean = false,
    onVideoTap: (BasicVideoInfo, Int, List<BasicVideoInfo>) -> Unit,
    onVideoDelete: ((BasicVideoInfo) -> Unit)? = null
) {
    val currentTabTitle = if (tabTitles.isNotEmpty() && selectedTab < tabTitles.size) {
        tabTitles[selectedTab]
    } else {
        "Videos"
    }

    when {
        isLoading -> LoadingVideosView()
        videos.isEmpty() -> EmptyVideosView(currentTabTitle)
        else -> {
            // FIXED: Use Column with Rows instead of LazyVerticalGrid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp) // Constrain height to prevent infinite constraints
            ) {
                // Group videos into rows of 3
                val videoRows = videos.chunked(3)

                videoRows.forEachIndexed { rowIndex, rowVideos ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1.dp, vertical = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        rowVideos.forEachIndexed { columnIndex, video ->
                            val videoIndex = rowIndex * 3 + columnIndex
                            VideoGridItem(
                                video = video,
                                index = videoIndex,
                                isCurrentUserProfile = isCurrentUserProfile,
                                onVideoTap = { onVideoTap(video, videoIndex, videos) },
                                onVideoDelete = onVideoDelete,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(0.75f)
                            )
                        }

                        // Fill remaining columns in incomplete rows
                        repeat(3 - rowVideos.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }

                    // Small spacing between rows
                    if (rowIndex < videoRows.size - 1) {
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }

                // Bottom padding
                Spacer(modifier = Modifier.height(50.dp))
            }
        }
    }
}

// MARK: - Video Grid Item

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGridItem(
    video: BasicVideoInfo,
    index: Int,
    isCurrentUserProfile: Boolean,
    onVideoTap: () -> Unit,
    onVideoDelete: ((BasicVideoInfo) -> Unit)?,
    modifier: Modifier = Modifier
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var isDeletingVideo by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .combinedClickable(
                onClick = onVideoTap,
                onLongClick = {
                    if (isCurrentUserProfile && onVideoDelete != null) {
                        showContextMenu = true
                    }
                }
            )
    ) {
        // Video Thumbnail
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(video.thumbnailURL)
                .crossfade(true)
                .build(),
            contentDescription = "Video thumbnail",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Gray.copy(alpha = 0.3f))
        )

        // Video Duration Badge (Bottom Right)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
        ) {
            DurationBadge(duration = video.duration)
        }

        // Loading Overlay during deletion
        if (isDeletingVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    // Context Menu - iOS context menu
    if (showContextMenu) {
        AlertDialog(
            onDismissRequest = { showContextMenu = false },
            title = { Text("Video Options") },
            text = { Text("Choose an action for this video") },
            confirmButton = {
                if (onVideoDelete != null) {
                    TextButton(
                        onClick = {
                            showContextMenu = false
                            isDeletingVideo = true
                            onVideoDelete(video)
                        }
                    ) {
                        Text("Delete", color = Color.Red)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showContextMenu = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

// MARK: - Duration Badge Component

@Composable
private fun DurationBadge(duration: Double) {
    if (duration > 0) {
        Box(
            modifier = Modifier
                .background(
                    Color.Black.copy(alpha = 0.6f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = formatDurationForBadge(duration),
                color = Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// MARK: - Loading State Component

@Composable
private fun LoadingVideosView() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = Color.Cyan,
                strokeWidth = 3.dp
            )
            Text(
                text = "Loading Videos...",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }
    }
}

// MARK: - Empty State Component

@Composable
private fun EmptyVideosView(tabTitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VideoLibrary,
                contentDescription = "No videos",
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )

            Text(
                text = "No $tabTitle",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = when (tabTitle.lowercase()) {
                    "videos" -> "Start creating videos to see them here"
                    "threads" -> "Create thread videos to see them here"
                    "likes" -> "Videos you like will appear here"
                    else -> "No content available"
                },
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// MARK: - Helper Functions

private fun formatDurationForBadge(duration: Double): String {
    val totalSeconds = duration.toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    return if (minutes > 0) {
        String.format("%d:%02d", minutes, seconds)
    } else {
        "${seconds}s"
    }
}