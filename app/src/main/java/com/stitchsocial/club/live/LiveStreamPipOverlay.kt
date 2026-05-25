package com.stitchsocial.club.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
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
import kotlin.math.roundToInt

/**
 * Floating PiP card mirroring iOS `PiPOverlayContainer`. Renders the
 * currently-broadcasting video comment in a 150×210 corner card with the
 * `ON AIR` pill + author chip. Drag to reposition (no fling-to-dismiss on
 * viewer side — only the creator can take the clip off-air).
 *
 * Uses ExoPlayer (media3) for playback. `playbackToken` changes drive
 * seek-to-zero so a creator-initiated replay restarts the viewer's player
 * in sync. URL changes destroy + recreate the player (different MediaItem +
 * .id() equivalent).
 */
@Composable
fun LiveStreamPipOverlay(
    videoURL: String,
    authorUsername: String,
    authorLevel: Int,
    playbackToken: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Offset state — viewer can drag the card around the screen.
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Resolve cache-aware URL (local file if cached, else remote). Synchronous
    // to avoid double-building the player — see LiveStreamCreatorPipOverlay.kt
    // for the rationale.
    val resolved = remember(videoURL) {
        StreamClipCache.cachedURL(videoURL) ?: videoURL
    }
    LaunchedEffect(videoURL) {
        StreamClipCache.prefetch(videoURL)
    }

    // ExoPlayer lifecycle keyed on the URL — recreate when URL changes.
    val player = remember(resolved) {
        Log.d("PiP", "📺 viewer player for ${resolved.takeLast(60)}")
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("PiP", "❌ viewer ${error.errorCodeName}: ${error.message}")
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) Log.d("PiP", "📺 viewer READY")
                }
            })
            setMediaItem(MediaItem.fromUri(resolved))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }

    // Token change → seek to zero (replay sync with creator).
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

    Box(modifier = modifier, contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .padding(end = 14.dp, bottom = 130.dp)
                .size(width = 150.dp, height = 210.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(2.5.dp, Color.Cyan, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                },
        ) {
            // Video surface — TextureView (not PlayerView) so it composites
            // correctly under the floating PiP border.
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { tv ->
                        player.setVideoTextureView(tv)
                    }
                },
                update = { tv -> player.setVideoTextureView(tv) },
                modifier = Modifier.fillMaxSize(),
            )

            // ON AIR pill (top-left)
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

            // Author tag (bottom-left)
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
