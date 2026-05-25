package com.stitchsocial.club.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Creator-only preview before broadcasting a queued clip. Shows the full
 * 9:16 video with author info and Accept / Reject buttons. Mirrors iOS
 * `VideoPreviewSheet`. The video auto-loops while the sheet is up.
 *
 * Lifecycle: caller controls visibility via `comment != null`. Releases the
 * ExoPlayer in `DisposableEffect.onDispose`.
 */
@Composable
fun VideoCommentPreviewSheet(
    comment: VideoComment,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val resolvedURL = remember(comment.videoURL) {
        StreamClipCache.cachedURL(comment.videoURL) ?: comment.videoURL
    }

    val player = remember(resolvedURL) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(resolvedURL))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.stop()
            player.release()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "@${comment.authorUsername}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            "Lv ${comment.authorLevel}",
                            color = Color.Yellow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .background(Color.Yellow.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                        if (comment.isPriority) {
                            Text(
                                "⚡ PRIORITY",
                                color = Color.Yellow,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .background(Color.Yellow.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        "Preview before broadcasting",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            // Video player — 9:16 aspect ratio
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(18.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (comment.caption.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "\"${comment.caption}\"",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "${comment.durationSeconds}s · ${comment.videoURL.substringAfterLast('/').take(20)}",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(20.dp))

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Red.copy(alpha = 0.15f))
                        .border(1.dp, Color.Red.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clickable { onReject() }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text("✕ Reject", color = Color.Red, fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Cyan)
                        .clickable { onAccept() }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text("▶ Play Live", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}
