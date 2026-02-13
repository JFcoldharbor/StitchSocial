/*
 * VideoNavigationPeeks.kt - Edge Peek Navigation Indicators
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Port of iOS VideoNavigationPeeks.swift
 * Shows previous/next video thumbnails on screen edges
 */

package com.stitchsocial.club.views

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

import com.stitchsocial.club.foundation.CoreVideoMetadata

@Composable
fun VideoNavigationPeeks(
    allVideos: List<CoreVideoMetadata>,
    currentVideoIndex: Int,
    modifier: Modifier = Modifier
) {
    val previousVideo = if (currentVideoIndex > 0) allVideos[currentVideoIndex - 1] else null
    val nextVideo = if (currentVideoIndex < allVideos.size - 1) allVideos[currentVideoIndex + 1] else null

    // Heartbeat pulse animation (matches iOS)
    val infiniteTransition = rememberInfiniteTransition(label = "peek_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1600
                0.6f at 0
                1.0f at 100
                0.6f at 200
                1.0f at 300
                0.6f at 400
                0.6f at 1600
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Left peek — Previous video
        if (previousVideo != null) {
            NavigationPeekCard(
                video = previousVideo,
                isLeft = true,
                pulseAlpha = pulseAlpha,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
            )
        }

        // Right peek — Next video
        if (nextVideo != null) {
            NavigationPeekCard(
                video = nextVideo,
                isLeft = false,
                pulseAlpha = pulseAlpha,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            )
        }
    }
}

@Composable
private fun NavigationPeekCard(
    video: CoreVideoMetadata,
    isLeft: Boolean,
    pulseAlpha: Float,
    modifier: Modifier = Modifier
) {
    val borderColors = if (isLeft) {
        listOf(Color.Cyan.copy(alpha = 0.6f), Color(0xFF3366FF).copy(alpha = 0.4f))
    } else {
        listOf(Color(0xFFFF9500).copy(alpha = 0.6f), Color(0xFFFF375F).copy(alpha = 0.4f))
    }

    Box(
        modifier = modifier
            .width(45.dp)
            .height(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.7f),
                        Color(0xFF9966F2).copy(alpha = 0.2f)
                    )
                )
            )
    ) {
        // Thumbnail
        AsyncImage(
            model = video.thumbnailURL,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )

        // Gradient overlay for depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.1f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.3f)
                        )
                    )
                )
        )

        // Border glow (matches iOS gradient stroke)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(colors = borderColors),
                    shape = RoundedCornerShape(8.dp)
                )
        )

        // Direction indicator at bottom
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLeft) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                    Text(
                        text = "prev",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                } else {
                    Text(
                        text = "next",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}