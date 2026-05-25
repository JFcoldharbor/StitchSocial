package com.stitchsocial.club.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Creator-side PiP overlay — adds tap-to-replay and long-press / fling-dismiss
 * gestures on top of the same visual treatment as the viewer's overlay.
 * Mirrors iOS `PiPOverlayContainer` with `anchor = .topTrailing`.
 *
 * Gestures:
 *  - Tap: rotates the playback token (calls onTapReplay).
 *  - Long-press: explicit dismiss.
 *  - Drag: reposition freely. Fling > 200pt = dismiss.
 */
@Composable
fun LiveStreamCreatorPipOverlay(
    videoURL: String,
    authorUsername: String,
    authorLevel: Int,
    playbackToken: String,
    onTapReplay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val flingDismissDistance = 200f

    var committedX by remember { mutableStateOf(0f) }
    var committedY by remember { mutableStateOf(0f) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }

    // Resolve cache hit synchronously so we don't double-build the ExoPlayer.
    // produceState's async resolve was causing remember(resolved) to fire
    // twice (initial=remote, then cached=local) — releasing the first
    // player mid-buffer + creating a second one. We just check the cache
    // up-front (a sync FS-exists call) and kick a background prefetch for
    // next time if it wasn't already on disk.
    val resolved = remember(videoURL) {
        StreamClipCache.cachedURL(videoURL) ?: videoURL
    }
    LaunchedEffect(videoURL) {
        StreamClipCache.prefetch(videoURL)
    }

    val player = remember(resolved) {
        Log.d("PiP", "📺 creating ExoPlayer for ${resolved.takeLast(60)}")
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("PiP", "❌ ${error.errorCodeName}: ${error.message}")
                }
                override fun onPlaybackStateChanged(state: Int) {
                    val label = when (state) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY (frame should render)"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "?"
                    }
                    Log.d("PiP", "📺 state=$label")
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    Log.d("PiP", "📺 video size=${videoSize.width}×${videoSize.height}")
                }
            })
            setMediaItem(MediaItem.fromUri(resolved))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }

    LaunchedEffect(playbackToken) {
        if (player.duration > 0) player.seekTo(0)
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        onDispose {
            player.stop()
            player.release()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (committedX + dragX).roundToInt(),
                        (committedY + dragY).roundToInt(),
                    )
                }
                .padding(end = 14.dp, top = 110.dp)
                .size(width = 150.dp, height = 210.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(2.5.dp, Color.Cyan, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTapReplay() },
                        onLongPress = { onDismiss() },
                    )
                }
                .pointerInput(Unit) {
                    var totalX = 0f
                    var totalY = 0f
                    detectDragGestures(
                        onDragStart = { totalX = 0f; totalY = 0f },
                        onDrag = { _, drag ->
                            dragX += drag.x
                            dragY += drag.y
                            totalX += drag.x
                            totalY += drag.y
                        },
                        onDragEnd = {
                            val magnitude = hypot(totalX, totalY)
                            if (magnitude > flingDismissDistance) {
                                onDismiss()
                            } else {
                                committedX += totalX
                                committedY += totalY
                            }
                            dragX = 0f
                            dragY = 0f
                        },
                    )
                },
        ) {
            // TextureView (not PlayerView) — PlayerView's default SurfaceView
            // creates a separate window layer that doesn't play nicely with
            // overlay z-ordering. TextureView renders inside the normal view
            // hierarchy so it composites correctly under the cyan border.
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { tv ->
                        player.setVideoTextureView(tv)
                    }
                },
                update = { tv ->
                    if (player.videoSize.width > 0) {
                        // Re-bind if the player was swapped (URL change).
                        player.setVideoTextureView(tv)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            Text(
                "ON AIR",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .background(Color.Red, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(7.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "@$authorUsername",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "Lv$authorLevel",
                    color = Color.Yellow,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}
