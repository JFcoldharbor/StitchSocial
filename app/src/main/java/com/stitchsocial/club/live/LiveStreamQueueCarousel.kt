package com.stitchsocial.club.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Floating queue strip mirroring iOS `queueOverlay` carousel. Background-less
 * horizontal cards anchored under the top HUD. No heavy bottom sheet — cards
 * float over the camera feed.
 *
 * Tap pending card → preview opens (vet first then accept).
 * Tap used card → instant replay (already vetted).
 * Long-press → reject confirm.
 * Tap ✕ in header → hides the strip.
 */
@Composable
fun LiveStreamQueueCarousel(
    pending: List<VideoComment>,
    displayed: List<VideoComment>,
    onPreview: (VideoComment) -> Unit,
    onReplay: (VideoComment) -> Unit,
    onReject: (VideoComment) -> Unit,
    onClose: () -> Unit,
    onTestPip: (() -> Unit)? = null,
) {
    val all = displayed + pending
    val usedIDs = displayed.map { it.id }.toSet()

    var pendingRejectCard by remember { mutableStateOf<VideoComment?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 70.dp),
    ) {
        QueueHeaderChip(
            pendingCount = pending.size,
            usedCount = displayed.size,
            onClose = onClose,
            onTestPip = onTestPip,
        )

        Spacer(Modifier.height(8.dp))

        if (all.isEmpty()) {
            QueueEmptyChip()
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                items(all, key = { it.id }) { comment ->
                    val isUsed = usedIDs.contains(comment.id)
                    CarouselCard(
                        comment = comment,
                        isUsed = isUsed,
                        onTap = {
                            if (isUsed) onReplay(comment) else onPreview(comment)
                        },
                        onLongPress = {
                            if (!isUsed) pendingRejectCard = comment
                        },
                    )
                }
            }
        }
    }

    pendingRejectCard?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingRejectCard = null },
            title = { Text("Reject this video?") },
            text = { Text("The viewer won't be notified. They can still send another.") },
            confirmButton = {
                TextButton(onClick = {
                    onReject(card)
                    pendingRejectCard = null
                }) { Text("Reject", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRejectCard = null }) { Text("Cancel") }
            },
            containerColor = Color(0xFF161929),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun QueueHeaderChip(
    pendingCount: Int,
    usedCount: Int,
    onClose: () -> Unit,
    onTestPip: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("📹 Queue", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)

        Text(
            text = pendingCount.toString(),
            color = Color(0xFFFF4F8B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .background(Color(0xFFFF4F8B).copy(alpha = 0.30f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )

        if (usedCount > 0) {
            Text(
                text = "$usedCount used",
                color = Color.Green,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .background(Color.Green.copy(alpha = 0.30f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        onTestPip?.let { tester ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFA855F7).copy(alpha = 0.35f))
                    .clickable { tester() },
                contentAlignment = Alignment.Center,
            ) {
                Text("🧪", fontSize = 13.sp)
            }
        }

        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun QueueEmptyChip() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            "No clips yet — wait for viewers to drop one",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/**
 * Compact 88×118 tile per clip. No surrounding container — just the thumbnail
 * with overlay badges. Tap → onTap, long-press → onLongPress.
 */
@Composable
private fun CarouselCard(
    comment: VideoComment,
    isUsed: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val borderColor = when {
        isUsed -> Color.Green.copy(alpha = 0.55f)
        comment.isPriority -> Color.Yellow.copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.25f)
    }

    Box(
        modifier = Modifier
            .size(width = 88.dp, height = 118.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .pointerInput(comment.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                )
            },
    ) {
        // Thumbnail layer
        if (!comment.thumbnailURL.isNullOrEmpty()) {
            AsyncImage(
                model = comment.thumbnailURL,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A20)))
        }

        // Used overlay
        if (isUsed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.Green, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
        } else {
            Icon(
                Icons.Default.PlayArrow,
                null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }

        // Priority badge (top-left)
        if (comment.isPriority && !isUsed) {
            Text(
                "⚡",
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Yellow.copy(alpha = 0.9f), CircleShape)
                    .padding(4.dp),
            )
        }

        // Duration (bottom-right)
        Text(
            text = "0:%02d".format(comment.durationSeconds),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )

        // Username chip (bottom-left)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "@${comment.authorUsername}",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
            Text(
                "Lv${comment.authorLevel}",
                color = Color.Yellow,
                fontSize = 7.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}
