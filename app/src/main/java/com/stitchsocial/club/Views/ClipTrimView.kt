/*
 * ClipTrimView.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Per-clip trim editor for the Thread Collage feature. Mirrors iOS
 * ClipTrimView.swift with the post-fix semantics:
 *
 *   • User-trimmed durations are written back through
 *     ThreadCollageService.applyTrim — the service preserves them across
 *     subsequent clip add/remove.
 *   • Real source duration is read from the loaded ExoPlayer media. If
 *     CoreVideoMetadata.duration was 0 (Firestore field missing), the
 *     trim handles are re-anchored to the real range so the user can
 *     actually drag more than 2 seconds.
 *   • Two-second minimum window (matches CollageConfiguration.minimumClipDuration).
 *
 * Phase 2 / Series of 5: this view writes back trim state; the selection
 * view (Phase 3) reads it.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.stitchsocial.club.services.CollageClip
import com.stitchsocial.club.services.ThreadCollageService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_DURATION_SECONDS = 2.0

/**
 * Trim editor for a single collage clip. The caller supplies the clip
 * (by id) and the service. On Done, this view calls
 * `service.applyTrim(clipID, trimStart, allocatedDuration)`. On
 * Remove (responses only), the caller's onRemove handler fires and the
 * view dismisses.
 */
@Composable
fun ClipTrimView(
    clip: CollageClip,
    service: ThreadCollageService,
    onDone: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // Observe the live clip so we render the most recent originalDuration
    // (the service writes it back when the real asset duration arrives).
    val selectedClips by service.selectedClips.collectAsState()
    val liveClip = selectedClips.firstOrNull { it.id == clip.id } ?: clip

    // ── Player state ────────────────────────────────────────────────
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(liveClip.videoMetadata.videoURL))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = false
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(0.0) }

    // ── Trim state. Start with whatever the clip currently has; gets
    //    re-anchored to the real range when the player reports duration.
    var trimStart by remember { mutableStateOf(liveClip.trimStart) }
    var trimEnd by remember {
        mutableStateOf(
            (liveClip.trimStart + liveClip.allocatedDuration)
                .coerceAtMost(liveClip.originalDuration.coerceAtLeast(MIN_DURATION_SECONDS))
        )
    }
    // We track the source duration locally so the slider track has an
    // accurate full-length even before the service-side write completes.
    var sourceDuration by remember { mutableStateOf(liveClip.originalDuration) }

    // ── Probe real duration when the player reports it ──────────────
    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val durMs = exoPlayer.duration
                    if (durMs > 0) {
                        val durSec = durMs / 1000.0
                        if (durSec > 0.1) {
                            if (sourceDuration <= 0.1) {
                                // First time we learn the real duration —
                                // open the window to the full clip so the
                                // user can actually drag handles around.
                                sourceDuration = durSec
                                if (trimEnd <= 0.1) {
                                    trimStart = liveClip.trimStart.coerceAtLeast(0.0)
                                    val seed = if (liveClip.allocatedDuration > 0.0)
                                        liveClip.allocatedDuration else durSec
                                    trimEnd = (trimStart + seed).coerceAtMost(durSec)
                                }
                                // Reflect back to the service so the
                                // selection view's badge is accurate.
                                service.updateOriginalDuration(liveClip.id, durSec)
                            } else if (sourceDuration < durSec) {
                                // Stored duration was stale; trust the
                                // player and widen the range.
                                sourceDuration = durSec
                                service.updateOriginalDuration(liveClip.id, durSec)
                            }
                        }
                    }
                }
            }
        }
        exoPlayer.addListener(listener)

        // Position observer for the playhead + loop within trim range.
        while (true) {
            val posMs = exoPlayer.currentPosition
            currentTime = posMs / 1000.0
            if (currentTime >= trimEnd && trimEnd > trimStart) {
                exoPlayer.seekTo((trimStart * 1000).toLong())
            }
            delay(50)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    // ── UI ───────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            ClipTrimHeader(
                isMainClip = liveClip.isMainClip,
                creatorName = liveClip.videoMetadata.creatorName,
                onClose = onDone,
                onRemove = onRemove,
            )

            // Video preview
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (isPlaying) {
                            exoPlayer.pause()
                            isPlaying = false
                        } else {
                            if (currentTime >= trimEnd - 0.1) {
                                exoPlayer.seekTo((trimStart * 1000).toLong())
                            }
                            exoPlayer.play()
                            isPlaying = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (!isPlaying) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = "Play",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            // Trim controls
            TrimControls(
                trimStart = trimStart,
                trimEnd = trimEnd,
                sourceDuration = sourceDuration.coerceAtLeast(0.1),
                currentTime = currentTime,
                isPlaying = isPlaying,
                onStartChange = { newStart ->
                    trimStart = newStart.coerceIn(0.0, trimEnd - MIN_DURATION_SECONDS)
                    exoPlayer.seekTo((trimStart * 1000).toLong())
                },
                onEndChange = { newEnd ->
                    trimEnd = newEnd
                        .coerceAtMost(sourceDuration)
                        .coerceAtLeast(trimStart + MIN_DURATION_SECONDS)
                    exoPlayer.seekTo(((trimEnd - 0.5).coerceAtLeast(trimStart) * 1000).toLong())
                },
            )

            // Bottom bar
            BottomBar(
                durationLabel = formatTime(trimEnd - trimStart),
                onPreview = {
                    exoPlayer.seekTo((trimStart * 1000).toLong())
                    exoPlayer.play()
                    isPlaying = true
                },
                onDone = {
                    service.applyTrim(
                        clipID = liveClip.id,
                        trimStart = trimStart,
                        allocatedDuration = (trimEnd - trimStart).coerceAtLeast(0.0),
                    )
                    onDone()
                },
            )
        }
    }
}

// ── Header ──────────────────────────────────────────────────────────────

@Composable
private fun ClipTrimHeader(
    isMainClip: Boolean,
    creatorName: String,
    onClose: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isMainClip) "Main Video" else "Trim Clip",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            if (creatorName.isNotEmpty()) {
                Text(
                    text = "@$creatorName",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (onRemove != null && !isMainClip) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Red.copy(alpha = 0.15f))
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = Color.Red.copy(alpha = 0.85f)
                )
            }
        } else {
            Spacer(modifier = Modifier.size(40.dp))
        }
    }
}

// ── Trim Scrubber ──────────────────────────────────────────────────────

private const val HANDLE_WIDTH_DP = 14
private const val TRACK_HEIGHT_DP = 56

@Composable
private fun TrimControls(
    trimStart: Double,
    trimEnd: Double,
    sourceDuration: Double,
    currentTime: Double,
    isPlaying: Boolean,
    onStartChange: (Double) -> Unit,
    onEndChange: (Double) -> Unit,
) {
    val density = LocalDensity.current
    val trackHeightDp = TRACK_HEIGHT_DP.dp
    val handleWidthDp = HANDLE_WIDTH_DP.dp

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Time labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(trimStart),
                color = Color.Cyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "Using ${formatTime(trimEnd - trimStart)} of ${formatTime(sourceDuration)}",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
            Text(
                text = formatTime(trimEnd),
                color = Color.Cyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Scrubber track. We measure the laid-out width with onSizeChanged
        // so drag-pixels-to-seconds math is accurate at any screen size.
        var trackWidthPx by remember { mutableStateOf(1f) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeightDp + 10.dp)
                .pointerInput(sourceDuration) { /* parent intercepts nothing */ }
        ) {
            // Layout-measured width
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            )

            val startFraction = (trimStart / sourceDuration).toFloat().coerceIn(0f, 1f)
            val endFraction = (trimEnd / sourceDuration).toFloat().coerceIn(0f, 1f)
            val playheadFraction = (currentTime / sourceDuration).toFloat().coerceIn(0f, 1f)

            val startXDp = with(density) { (startFraction * trackWidthPx).toDp() }
            val endXDp = with(density) { (endFraction * trackWidthPx).toDp() }
            val playheadXDp = with(density) { (playheadFraction * trackWidthPx).toDp() }

            // Background track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeightDp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.1f))
            )

            // Dimmed-left region
            Box(
                modifier = Modifier
                    .width(startXDp)
                    .height(trackHeightDp)
                    .background(Color.Black.copy(alpha = 0.6f))
            )

            // Dimmed-right region
            val rightWidthDp = with(density) {
                ((1f - endFraction) * trackWidthPx).coerceAtLeast(0f).toDp()
            }
            Box(
                modifier = Modifier
                    .offset(x = endXDp)
                    .width(rightWidthDp)
                    .height(trackHeightDp)
                    .background(Color.Black.copy(alpha = 0.6f))
            )

            // Selected-region border
            Box(
                modifier = Modifier
                    .offset(x = startXDp)
                    .width((endXDp - startXDp).coerceAtLeast(handleWidthDp * 2))
                    .height(trackHeightDp)
                    .border(
                        width = 2.dp,
                        color = Color.Cyan,
                        shape = RoundedCornerShape(4.dp)
                    )
            )

            // Playhead
            if (isPlaying || currentTime > trimStart) {
                Box(
                    modifier = Modifier
                        .offset(x = playheadXDp - 1.dp)
                        .width(2.dp)
                        .height(trackHeightDp + 10.dp)
                        .background(Color.White)
                )
            }

            // Left handle (trim start)
            DraggableHandle(
                xOffset = startXDp - handleWidthDp / 2,
                onDrag = { deltaPx ->
                    val deltaSec = (deltaPx / trackWidthPx) * sourceDuration
                    onStartChange(trimStart + deltaSec)
                },
            )

            // Right handle (trim end)
            DraggableHandle(
                xOffset = endXDp - handleWidthDp / 2,
                onDrag = { deltaPx ->
                    val deltaSec = (deltaPx / trackWidthPx) * sourceDuration
                    onEndChange(trimEnd + deltaSec)
                },
            )
        }
    }
}

@Composable
private fun DraggableHandle(
    xOffset: androidx.compose.ui.unit.Dp,
    onDrag: (Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .offset(x = xOffset)
            .size(width = HANDLE_WIDTH_DP.dp, height = (TRACK_HEIGHT_DP + 8).dp)
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(3.dp))
            .clip(RoundedCornerShape(3.dp))
            .background(Color.Cyan)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Grip lines
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 1.dp)
                        .background(Color.White.copy(alpha = 0.8f))
                )
            }
        }
    }
}

// ── Bottom Bar ─────────────────────────────────────────────────────────

@Composable
private fun BottomBar(
    durationLabel: String,
    onPreview: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPreview,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.15f)
            ),
            shape = androidx.compose.foundation.shape.CircleShape,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Preview",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = durationLabel,
            color = Color.Cyan,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan),
            shape = androidx.compose.foundation.shape.CircleShape,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Done",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────

private fun formatTime(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return "0.0"
    val mins = (seconds / 60).toInt()
    val secs = (seconds % 60).toInt()
    val tenths = ((seconds % 1) * 10).toInt()
    return if (mins > 0) {
        String.format("%d:%02d.%d", mins, secs, tenths)
    } else {
        String.format("%d.%d", secs, tenths)
    }
}

