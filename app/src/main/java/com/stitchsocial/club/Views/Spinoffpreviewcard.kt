/*
 * SpinOffPreviewCard.kt - SPIN-OFF PREVIEW CARD
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: UI Components - Spin-off source video preview
 * Dependencies: Compose UI, VideoInfo from RecordingContext
 * Features: Shows source video context when creating spin-off threads
 *
 * PURPOSE: Display context card in thread composer showing the video being responded to
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stitchsocial.club.camera.VideoInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SubdirectoryArrowLeft
import androidx.compose.material.icons.filled.PlayArrow

/**
 * Preview card showing source video when creating a spin-off
 * Displayed at top of ThreadComposer when context is RecordingContext.SpinOffFrom
 */
@Composable
fun SpinOffPreviewCard(
    videoInfo: VideoInfo,
    thumbnailURL: String = "",
    onViewOriginal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1C1C1E)  // Dark gray
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header with icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SubdirectoryArrowLeft,
                    contentDescription = "Spin-off",
                    tint = Color(0xFFFF9500),  // Amber/Orange
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Responding to",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF9500)  // Amber/Orange
                )
            }

            // Video preview row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2C2C2E))
                        .border(
                            width = 1.dp,
                            color = Color(0xFF3A3A3C),
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    if (thumbnailURL.isNotEmpty()) {
                        AsyncImage(
                            model = thumbnailURL,
                            contentDescription = "Video thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Placeholder
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Video",
                            tint = Color.Gray,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Video info
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Creator name
                    Text(
                        text = "@${videoInfo.creatorName}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Video title
                    Text(
                        text = videoInfo.title,
                        fontSize = 13.sp,
                        color = Color(0xFFAAAAAA),  // Light gray
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // View original button
                TextButton(
                    onClick = onViewOriginal,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFFFF9500)
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "View",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Compact version for smaller contexts
 */
@Composable
fun SpinOffPreviewCardCompact(
    creatorName: String,
    videoTitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFF1C1C1E),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.SubdirectoryArrowLeft,
            contentDescription = "Spin-off",
            tint = Color(0xFFFF9500),
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Responding to @$creatorName",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFFF9500),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = videoTitle,
                fontSize = 11.sp,
                color = Color(0xFFAAAAAA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}