/*
 * SpinOffsListSheet.kt - SPIN-OFFS LIST BOTTOM SHEET
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: UI Components - Spin-offs browser sheet
 * Dependencies: Compose UI, VideoService, CoreVideoMetadata
 * Features: Bottom sheet showing all threads that spun off from a video
 *
 * PURPOSE: Browse and navigate to spin-off threads
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.VideoServiceImpl
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.KeyboardArrowRight

/**
 * Bottom sheet showing all spin-off threads from a video
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpinOffsListSheet(
    sourceVideo: CoreVideoMetadata,
    videoService: VideoServiceImpl,
    onDismiss: () -> Unit,
    onSpinOffSelected: (CoreVideoMetadata) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var spinOffs by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load spin-offs when sheet opens
    LaunchedEffect(sourceVideo.id) {
        isLoading = true
        error = null
        try {
            val loadedSpinOffs = videoService.getSpinOffs(sourceVideo.id)
            spinOffs = loadedSpinOffs
        } catch (e: Exception) {
            error = "Failed to load spin-offs"
            println("❌ SPIN-OFFS SHEET: Error loading spin-offs - ${e.message}")
        } finally {
            isLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1C1C1E),  // Dark gray
        contentColor = Color.White,
        modifier = modifier
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
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Spin-offs",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${spinOffs.size} ${if (spinOffs.size == 1) "thread" else "threads"}",
                        fontSize = 14.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }

                TextButton(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    }
                ) {
                    Text(
                        text = "Done",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF007AFF)  // Blue
                    )
                }
            }

            // Content
            when {
                isLoading -> LoadingState()
                error != null -> ErrorState(error!!)
                spinOffs.isEmpty() -> EmptyState()
                else -> SpinOffsList(
                    spinOffs = spinOffs,
                    onSpinOffTap = { spinOff ->
                        scope.launch {
                            sheetState.hide()
                            onSpinOffSelected(spinOff)
                            onDismiss()
                        }
                    }
                )
            }
        }
    }
}

// MARK: - Content States

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = Color(0xFFFF9500)
        )
    }
}

@Composable
private fun ErrorState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "⚠️",
                fontSize = 40.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = Color(0xFFAAAAAA)
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AccountTree,
                contentDescription = "No spin-offs",
                tint = Color(0xFF666666),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Spin-offs Yet",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "When others create threads responding to this video, they'll appear here.",
                fontSize = 14.sp,
                color = Color(0xFFAAAAAA),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun SpinOffsList(
    spinOffs: List<CoreVideoMetadata>,
    onSpinOffTap: (CoreVideoMetadata) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(spinOffs) { spinOff ->
            SpinOffListItem(
                spinOff = spinOff,
                onTap = { onSpinOffTap(spinOff) }
            )
        }
    }
}

// MARK: - List Item

@Composable
private fun SpinOffListItem(
    spinOff: CoreVideoMetadata,
    onTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2C2C2E))
            .clickable(onClick = onTap)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(80.dp, 100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF3A3A3C))
                .border(
                    width = 1.dp,
                    color = Color(0xFF4A4A4C),
                    shape = RoundedCornerShape(8.dp)
                )
        ) {
            AsyncImage(
                model = spinOff.thumbnailURL,
                contentDescription = "Video thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Video info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Title
            Text(
                text = spinOff.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Creator
            Text(
                text = "@${spinOff.creatorName}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF007AFF),  // Blue
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Engagement stats
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Hype count
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🔥",
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatNumber(spinOff.hypeCount),
                        fontSize = 12.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }

                // View count
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "👁",
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatNumber(spinOff.viewCount),
                        fontSize = 12.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }

                // Time ago
                Text(
                    text = formatTimeAgo(spinOff.createdAt),
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
            }
        }

        // Chevron
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = "View thread",
            tint = Color(0xFF666666),
            modifier = Modifier.size(20.dp)
        )
    }
}

// MARK: - Helper Functions

private fun formatNumber(num: Int): String {
    return when {
        num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
        num >= 1_000 -> String.format("%.1fK", num / 1000.0)
        else -> num.toString()
    }
}

private fun formatTimeAgo(date: Date): String {
    val now = Date()
    val diff = now.time - date.time
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "${days}d"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "now"
    }
}