/*
 * VideoThumbnailView.kt - IMPROVED THUMBNAIL LOADING
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Lightweight Video Thumbnail for Grid Display
 * Dependencies: BasicVideoInfo (Layer 1), Coil
 * Features: AsyncImage loading, engagement badges, temperature indicator, loading/error states
 *
 * ✅ FIXED: Better fallback logic for missing thumbnails
 * ✅ FIXED: Debug logging for thumbnail URLs
 * ✅ FIXED: Generate thumbnail from video URL if no thumbnail exists
 */

package com.stitchsocial.club

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.stitchsocial.club.foundation.BasicVideoInfo
import com.stitchsocial.club.foundation.Temperature

/**
 * VideoThumbnailView - Improved thumbnail loading with fallbacks
 * Shows video thumbnail, or placeholder if not available
 */
@Composable
fun VideoThumbnailView(
    video: BasicVideoInfo,
    showEngagementBadge: Boolean = true,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine best image source with fallback
    // Priority: thumbnailURL -> videoURL frame -> placeholder
    val thumbnailUrl = video.thumbnailURL?.trim()
    val videoUrl = video.videoURL.trim()

    // Check if we have a valid thumbnail URL
    val hasValidThumbnail = !thumbnailUrl.isNullOrEmpty() &&
            thumbnailUrl != "null" &&
            (thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://"))

    val imageUrl = when {
        hasValidThumbnail -> thumbnailUrl
        videoUrl.isNotEmpty() -> videoUrl // Coil can extract frame from video
        else -> null
    }

    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onTap)
    ) {
        if (imageUrl != null) {
            // Load thumbnail with Coil
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Video thumbnail for ${video.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            ) {
                when (val state = painter.state) {
                    is AsyncImagePainter.State.Loading -> {
                        LoadingView()
                    }
                    is AsyncImagePainter.State.Error -> {
                        // Log error for debugging
                        LaunchedEffect(Unit) {
                            println("THUMBNAIL ERROR: Failed to load - $imageUrl")
                            println("THUMBNAIL ERROR: ${state.result.throwable.message}")
                        }
                        PlaceholderView()
                    }
                    is AsyncImagePainter.State.Success -> {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        PlaceholderView()
                    }
                }
            }
        } else {
            // No image source available - show placeholder
            PlaceholderView()
        }

        // Gradient overlay at bottom for badges
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
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

        // Overlay Badges
        OverlayBadges(
            video = video,
            showEngagementBadge = showEngagementBadge
        )
    }
}

// MARK: - Overlay Badges

@Composable
private fun OverlayBadges(
    video: BasicVideoInfo,
    showEngagementBadge: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Temperature badge (top-left)
        if (video.temperature != Temperature.WARM) {
            TemperatureBadge(
                temperature = video.temperature,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            )
        }

        // Duration badge (bottom-right)
        if (video.duration > 0) {
            DurationBadge(
                duration = video.duration,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            )
        }

        // Engagement badge (bottom-left)
        if (showEngagementBadge && (video.hypeCount > 0 || video.coolCount > 0)) {
            EngagementBadge(
                hypeCount = video.hypeCount,
                coolCount = video.coolCount,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            )
        }
    }
}

// MARK: - Temperature Badge

@Composable
private fun TemperatureBadge(
    temperature: Temperature,
    modifier: Modifier = Modifier
) {
    val (icon, color) = when (temperature) {
        Temperature.BLAZING -> Icons.Default.LocalFireDepartment to Color(0xFFFF4500)
        Temperature.HOT -> Icons.Default.Whatshot to Color(0xFFFF6B35)
        Temperature.WARM -> Icons.Default.WbSunny to Color(0xFFFFD700)
        Temperature.COOL -> Icons.Default.AcUnit to Color(0xFF00BFFF)
        Temperature.COLD -> Icons.Default.SevereCold to Color(0xFF4169E1)
        Temperature.FROZEN -> Icons.Default.SevereCold to Color(0xFF1E90FF)
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = temperature.name,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
    }
}

// MARK: - Duration Badge

@Composable
private fun DurationBadge(
    duration: Double,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = formatDuration(duration),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// MARK: - Engagement Badge

@Composable
private fun EngagementBadge(
    hypeCount: Int,
    coolCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hypeCount > 0) {
            Icon(
                imageVector = Icons.Default.Whatshot,
                contentDescription = "Hypes",
                tint = Color(0xFFFF6B35),
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = formatCount(hypeCount),
                color = Color.White,
                fontSize = 9.sp
            )
        }

        if (coolCount > 0) {
            if (hypeCount > 0) {
                Spacer(modifier = Modifier.width(2.dp))
            }
            Icon(
                imageVector = Icons.Default.AcUnit,
                contentDescription = "Cools",
                tint = Color(0xFF00BFFF),
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = formatCount(coolCount),
                color = Color.White,
                fontSize = 9.sp
            )
        }
    }
}

// MARK: - Loading View

@Composable
private fun LoadingView() {
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

// MARK: - Placeholder View (for missing thumbnails)

@Composable
private fun PlaceholderView() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2C2C2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayCircleOutline,
                contentDescription = "Video placeholder",
                tint = Color.Gray.copy(alpha = 0.6f),
                modifier = Modifier.size(40.dp)
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