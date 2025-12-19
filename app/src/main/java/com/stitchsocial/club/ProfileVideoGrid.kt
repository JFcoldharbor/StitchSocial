/*
 * ProfileVideoGrid.kt - WITH DEBUG LOGGING
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Matches Swift ProfileVideoGrid.swift exactly
 * Dependencies: BasicVideoInfo, VideoThumbnailView, ContentType
 * Features: 3-column grid, engagement badges, context menus, loading states
 *
 * ✅ UPDATED: Now uses VideoThumbnailView component for proper thumbnail display
 * ✅ FIXED: Replaced LazyVerticalGrid with height-constrained Column
 * ✅ DEBUG: Added logging to diagnose thumbnail issues
 */

package com.stitchsocial.club

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.stitchsocial.club.foundation.BasicVideoInfo
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.Temperature
import java.util.Date

/**
 * ProfileVideoGrid - Fixed for LazyColumn compatibility + VideoThumbnailView
 * Uses Column of Rows to prevent nested scrolling crash
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileVideoGrid(
    videos: List<BasicVideoInfo>,
    selectedTab: Int = 0,
    tabTitles: List<String> = listOf("Videos"),
    isLoading: Boolean = false,
    isCurrentUserProfile: Boolean = false,
    onVideoTap: (BasicVideoInfo, Int, List<BasicVideoInfo>) -> Unit = { _, _, _ -> },
    onVideoDelete: ((BasicVideoInfo) -> Unit)? = null
) {
    val currentTabTitle = if (tabTitles.isNotEmpty() && selectedTab < tabTitles.size) {
        tabTitles[selectedTab]
    } else {
        "Videos"
    }

    // DEBUG: Log video count and URLs
    LaunchedEffect(videos) {
        println("📱 PROFILE GRID: Received ${videos.size} videos")
        videos.forEachIndexed { index, video ->
            println("  📹 Video $index: ${video.id}")
            println("     Title: ${video.title}")
            println("     ThumbnailURL: '${video.thumbnailURL}'")
            println("     VideoURL: '${video.videoURL}'")
        }
    }

    when {
        isLoading -> LoadingVideosView()
        videos.isEmpty() -> EmptyVideosView(currentTabTitle)
        else -> {
            // Use Column with Rows instead of LazyVerticalGrid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
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
                                onVideoTap = {
                                    println("🔹 PROFILE GRID: Video tapped - ${video.title}")
                                    onVideoTap(video, videoIndex, videos)
                                },
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
        // Use inline thumbnail view with debugging
        VideoThumbnailContent(
            video = video,
            onTap = onVideoTap
        )

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

    // Context Menu
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

// MARK: - Inline Thumbnail Content (for debugging)

@Composable
private fun VideoThumbnailContent(
    video: BasicVideoInfo,
    onTap: () -> Unit
) {
    val context = LocalContext.current

    // Determine best image source with fallback
    val thumbnailUrl = video.thumbnailURL?.trim()
    val videoUrl = video.videoURL.trim()

    // Check if we have a valid thumbnail URL (must be http/https)
    val hasValidThumbnail = !thumbnailUrl.isNullOrEmpty() &&
            thumbnailUrl != "null" &&
            thumbnailUrl.length > 10 &&
            (thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://"))

    // Only use thumbnail URL - video frame extraction requires coil-video dependency
    val imageUrl = if (hasValidThumbnail) thumbnailUrl else null

    // Debug logging
    LaunchedEffect(video.id) {
        println("🖼️ THUMBNAIL DEBUG: Video ${video.id}")
        println("   title: ${video.title}")
        println("   thumbnailURL raw: '${video.thumbnailURL}'")
        println("   videoURL: '${video.videoURL}'")
        println("   hasValidThumbnail: $hasValidThumbnail")
        println("   imageUrl to load: '$imageUrl'")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onTap)
    ) {
        if (imageUrl != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Video thumbnail for ${video.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            ) {
                when (val state = painter.state) {
                    is AsyncImagePainter.State.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF2C2C2E)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color.Cyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    is AsyncImagePainter.State.Error -> {
                        LaunchedEffect(Unit) {
                            println("❌ THUMBNAIL ERROR: Failed to load - $imageUrl")
                            println("❌ THUMBNAIL ERROR: ${state.result.throwable.message}")
                        }
                        PlaceholderThumbnail(video.title)
                    }
                    is AsyncImagePainter.State.Success -> {
                        LaunchedEffect(Unit) {
                            println("✅ THUMBNAIL SUCCESS: Loaded - $imageUrl")
                        }
                        Image(
                            painter = painter,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        PlaceholderThumbnail(video.title)
                    }
                }
            }
        } else {
            // No valid URL - show placeholder
            PlaceholderThumbnail(video.title)
        }

        // Bottom gradient overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        )

        // Engagement badges
        if (video.hypeCount > 0 || video.coolCount > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (video.hypeCount > 0) {
                    Icon(
                        imageVector = Icons.Default.Whatshot,
                        contentDescription = "Hypes",
                        tint = Color(0xFFFF6B35),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = formatCount(video.hypeCount),
                        color = Color.White,
                        fontSize = 9.sp
                    )
                }

                if (video.coolCount > 0) {
                    Icon(
                        imageVector = Icons.Default.AcUnit,
                        contentDescription = "Cools",
                        tint = Color(0xFF00BFFF),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = formatCount(video.coolCount),
                        color = Color.White,
                        fontSize = 9.sp
                    )
                }
            }
        }

        // Duration badge
        if (video.duration > 0) {
            Text(
                text = formatDuration(video.duration),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PlaceholderThumbnail(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2C2C2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayCircleOutline,
                contentDescription = "Video placeholder",
                tint = Color.Gray.copy(alpha = 0.6f),
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = title.take(15) + if (title.length > 15) "..." else "",
                color = Color.Gray,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
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

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

private fun formatDuration(duration: Double): String {
    val totalSeconds = duration.toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    return if (minutes > 0) {
        String.format("%d:%02d", minutes, seconds)
    } else {
        String.format("0:%02d", seconds)
    }
}

/**
 * Extension function to convert BasicVideoInfo to CoreVideoMetadata
 * Required for FullScreenVideoActivity integration
 */
private fun BasicVideoInfo.toCoreVideoMetadata(): CoreVideoMetadata {
    return CoreVideoMetadata(
        id = this.id,
        title = this.title,
        videoURL = this.videoURL,
        thumbnailURL = this.thumbnailURL ?: "",
        creatorID = this.creatorID,
        creatorName = this.creatorName,
        createdAt = this.createdAt,
        threadID = null,
        replyToVideoID = null,
        conversationDepth = 0,
        viewCount = this.viewCount,
        hypeCount = this.hypeCount,
        coolCount = this.coolCount,
        replyCount = 0,
        shareCount = 0,
        lastEngagementAt = null,
        duration = this.duration,
        aspectRatio = 9.0 / 16.0,
        fileSize = 0L,
        contentType = this.contentType,
        temperature = this.temperature,
        qualityScore = 50,
        engagementRatio = 0.0,
        velocityScore = 0.0,
        trendingScore = 0.0,
        discoverabilityScore = 0.5,
        isPromoted = false,
        isProcessing = false,
        isDeleted = false
    )
}