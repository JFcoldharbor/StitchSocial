/*
 * ReactionCameraView.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Mirrors iOS ReactionCameraView (Camera Features/ReactionSession.swift).
 *
 * R1 SCOPE — UI shell only. No recording, no compositing. The user can:
 *   • Pick a layout (50/50, 70/30, 30/70, PiP)
 *   • Import a source video into the content zone
 *   • Live-preview their camera in the camera zone
 *   • Tap record (no-op stub for now)
 *
 * R2 will add camera recording + handoff. R3 will add the compositor that
 * merges camera + source into a split-screen MP4. R4+ adds pause/scrub
 * and stitch-context auto-fill.
 */

package com.stitchsocial.club.views

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stitchsocial.club.ReactionCompositor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

// MARK: - Layout

enum class ReactionLayout(val displayName: String) {
    SPLIT_50_50("50/50"),
    SPLIT_70_30("70/30"),
    SPLIT_30_70("30/70"),
    PIP("PiP");

    /** (top fraction, bottom fraction). PiP is special-cased in the canvas. */
    val split: Pair<Float, Float>
        get() = when (this) {
            SPLIT_50_50 -> 0.5f to 0.5f
            SPLIT_70_30 -> 0.7f to 0.3f
            SPLIT_30_70 -> 0.3f to 0.7f
            PIP -> 1.0f to 1.0f
        }
}

// MARK: - Content Zone State

private sealed class ContentZone {
    object Empty : ContentZone()
    data class Video(val uri: Uri) : ContentZone()
}

// MARK: - Public Composable

@Composable
fun ReactionCameraView(
    onCancel: () -> Unit,
    onComplete: (Uri) -> Unit,
    initialSourceUri: Uri? = null,  // for stitch-context auto-fill in R5
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ───── State ─────────────────────────────────────────────────────────
    var layout by remember { mutableStateOf(ReactionLayout.SPLIT_50_50) }
    var cameraIsTop by remember { mutableStateOf(true) } // false = swap zones
    var contentZone by remember {
        mutableStateOf<ContentZone>(
            if (initialSourceUri != null) ContentZone.Video(initialSourceUri) else ContentZone.Empty
        )
    }
    var showLayoutPicker by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_FRONT_CAMERA) }
    // R5: mute toggle for source audio in the merged output. On by default
    // (matches Stitch/Duet expectations); user can mute via the speaker
    // icon in the top bar. Independent of the live preview mute
    // (sourcePlayer.volume = 0f) which stays muted to avoid mic feedback.
    var keepSourceAudio by remember { mutableStateOf(true) }

    // ───── Camera plumbing (hoisted so the record button can drive it) ───
    // PreviewView is created once and reused — re-creating it across
    // recompositions would tear down the live camera surface.
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var currentRecordingFile by remember { mutableStateOf<File?>(null) }

    // Compositing state — flips on after camera finalize, off when the
    // composite Uri is handed up. UI shows a blocking "merging…" overlay
    // during this window so the user doesn't tap stop again.
    var isCompositing by remember { mutableStateOf(false) }
    var compositingProgress by remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    // ───── Source playback (R4) ──────────────────────────────────────────
    // Hoisted so the record + pause + scrub controls can drive it. Lives
    // for the life of the screen, re-prepared whenever the picked Uri
    // changes. While idle (not recording) the user can scrub to set a
    // start offset; during recording, the player is play/paused in sync
    // with the camera Recording so the merged output stays time-aligned.
    val sourceUri = (contentZone as? ContentZone.Video)?.uri
    val sourcePlayer = remember(sourceUri) {
        sourceUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_OFF
                // Source plays audibly so the user can react to what they
                // hear. Headphones recommended to avoid mic re-capture; the
                // top-bar speaker icon mutes if needed.
                volume = 1f
                prepare()
                playWhenReady = false
            }
        }
    }
    DisposableEffect(sourcePlayer) {
        onDispose { sourcePlayer?.release() }
    }
    var sourceDurationMs by remember(sourcePlayer) { mutableLongStateOf(0L) }
    var sourceStartMs by remember(sourcePlayer) { mutableLongStateOf(0L) }
    var isPaused by remember { mutableStateOf(false) }

    // Poll duration once it's known. ExoPlayer.duration returns C.TIME_UNSET
    // until prepared; LaunchedEffect retries until we get a real value.
    LaunchedEffect(sourcePlayer) {
        val p = sourcePlayer ?: return@LaunchedEffect
        while (true) {
            val d = p.duration
            if (d > 0) { sourceDurationMs = d; break }
            kotlinx.coroutines.delay(100)
        }
    }

    // Keep the live preview's volume in sync with the merged-output toggle
    // so muting via the speaker icon kills both preview audio and the
    // composited audio track.
    LaunchedEffect(sourcePlayer, keepSourceAudio) {
        sourcePlayer?.volume = if (keepSourceAudio) 1f else 0f
    }

    // Bind / re-bind camera every time the cameraSelector flips (front↔back).
    // Same Preview surface (previewView), fresh VideoCapture each time —
    // CameraX requires the use cases to be (re)bound atomically.
    LaunchedEffect(cameraSelector) {
        try {
            // ProcessCameraProvider.getInstance(...).get() is a blocking
            // ListenableFuture await — push it to IO so we don't stall
            // the main dispatcher. The actual bindToLifecycle call must
            // run back on main, which the LaunchedEffect provides
            // implicitly once we return from withContext.
            val cameraProvider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val newVideoCapture = VideoCapture.withOutput(recorder)
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                newVideoCapture
            )
            videoCapture = newVideoCapture
            println("🎬 REACTION: camera bound (selector=$cameraSelector)")
        } catch (e: Exception) {
            println("🎬 REACTION: camera bind failed — ${e.message}")
        }
    }

    // ───── Recording control ─────────────────────────────────────────────
    val startRecording: () -> Unit = start@{
        val capture = videoCapture ?: run {
            println("🎬 REACTION: cannot start — videoCapture is null")
            return@start
        }
        val outFile = File(context.cacheDir, "reaction_camera_${UUID.randomUUID()}.mp4")
        currentRecordingFile = outFile
        val outputOptions = FileOutputOptions.Builder(outFile).build()
        try {
            activeRecording = capture.output
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            println("🎬 REACTION: recording started → ${outFile.name}")
                            // Kick the source from the scrubbed offset.
                            sourcePlayer?.let { p ->
                                p.seekTo(sourceStartMs)
                                p.playWhenReady = true
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!event.hasError()) {
                                println("🎬 REACTION: recording finalized → ${outFile.absolutePath} (${outFile.length() / 1024} KB)")
                                // R3: if a source video was picked, run the
                                // compositor to produce a split-screen MP4.
                                // If no source, just hand the camera file up
                                // (camera-only output).
                                val zone = contentZone
                                if (zone is ContentZone.Video) {
                                    isCompositing = true
                                    compositingProgress = 0f
                                    coroutineScope.launch {
                                        try {
                                            val composite = ReactionCompositor.composite(
                                                context = context,
                                                cameraUri = Uri.fromFile(outFile),
                                                sourceUri = zone.uri,
                                                layout = layout,
                                                cameraIsTop = cameraIsTop,
                                                sourceStartMs = sourceStartMs,
                                                keepSourceAudio = keepSourceAudio,
                                                onProgress = { p -> compositingProgress = p }
                                            )
                                            outFile.delete()  // camera intermediate no longer needed
                                            isCompositing = false
                                            onComplete(composite)
                                        } catch (e: Exception) {
                                            println("🎬 REACTION: composite failed → falling back to camera-only — ${e.message}")
                                            isCompositing = false
                                            onComplete(Uri.fromFile(outFile))
                                        }
                                    }
                                } else {
                                    onComplete(Uri.fromFile(outFile))
                                }
                            } else {
                                println("🎬 REACTION: recording failed — ${event.error}")
                                outFile.delete()
                            }
                            activeRecording = null
                            currentRecordingFile = null
                            isRecording = false
                            isPaused = false
                            sourcePlayer?.playWhenReady = false
                        }
                    }
                }
            isRecording = true
        } catch (e: SecurityException) {
            // Audio permission missing — record silently. R5 may add a
            // pre-flight permission check; for R2 we just fall back.
            println("🎬 REACTION: audio permission missing, recording without audio — ${e.message}")
            try {
                activeRecording = capture.output
                    .prepareRecording(context, outputOptions)
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            if (!event.hasError()) onComplete(Uri.fromFile(outFile))
                            else outFile.delete()
                            activeRecording = null
                            currentRecordingFile = null
                            isRecording = false
                        }
                    }
                isRecording = true
            } catch (e2: Exception) {
                println("🎬 REACTION: recording start failed — ${e2.message}")
            }
        } catch (e: Exception) {
            println("🎬 REACTION: recording start failed — ${e.message}")
        }
    }

    val stopRecording: () -> Unit = {
        activeRecording?.stop()
        // isRecording flips to false in the Finalize callback so the user
        // sees "saving…" between stop tap and file ready.
    }

    val togglePause: () -> Unit = {
        val rec = activeRecording
        if (rec != null) {
            if (isPaused) {
                try { rec.resume() } catch (_: Exception) {}
                sourcePlayer?.playWhenReady = true
                isPaused = false
            } else {
                try { rec.pause() } catch (_: Exception) {}
                sourcePlayer?.playWhenReady = false
                isPaused = true
            }
        }
    }

    // ───── Source video picker ────────────────────────────────────────────
    val sourceVideoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            contentZone = ContentZone.Video(uri)
            println("🎬 REACTION: source video picked: $uri")
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Split canvas ─────────────────────────────────────────────────
        SplitCanvas(
            layout = layout,
            cameraIsTop = cameraIsTop,
            contentZone = contentZone,
            sourcePlayer = sourcePlayer,
            previewView = previewView,
            onContentTap = { sourceVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            onSwap = { cameraIsTop = !cameraIsTop }
        )

        // ── Top bar ──────────────────────────────────────────────────────
        ReactionTopBar(
            layout = layout,
            keepSourceAudio = keepSourceAudio,
            hasSource = sourceUri != null,
            onCancel = onCancel,
            onLayoutTap = { showLayoutPicker = !showLayoutPicker },
            onToggleSourceAudio = { keepSourceAudio = !keepSourceAudio },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
        )

        // ── Source scrubber + pause/play (R4) ────────────────────────────
        // Sits just above the bottom controls. While idle, the slider sets
        // the source start offset for the next take. While recording, the
        // pause toggle pauses both the camera Recording and source player
        // together, keeping the merged output time-aligned. The slider
        // hides during recording — scrubbing mid-record is a future
        // R5 feature.
        if (sourcePlayer != null) {
            ReactionSourceControls(
                durationMs = sourceDurationMs,
                startMs = sourceStartMs,
                onScrub = { ms ->
                    sourceStartMs = ms
                    sourcePlayer.seekTo(ms)
                },
                isRecording = isRecording,
                isPaused = isPaused,
                onTogglePause = togglePause,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 130.dp)
            )
        }

        // ── Bottom controls ──────────────────────────────────────────────
        ReactionBottomControls(
            isRecording = isRecording,
            hasContent = contentZone is ContentZone.Video,
            onImport = { sourceVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            onRecord = {
                if (isRecording) stopRecording() else startRecording()
            },
            onFlipCamera = {
                if (isRecording) return@ReactionBottomControls
                cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        )

        // ── Layout picker sheet ──────────────────────────────────────────
        AnimatedVisibility(
            visible = showLayoutPicker,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LayoutPickerSheet(
                current = layout,
                onPick = { picked ->
                    layout = picked
                    showLayoutPicker = false
                },
                onDismiss = { showLayoutPicker = false }
            )
        }

        // ── Compositing overlay ──────────────────────────────────────────
        // Shown while ReactionCompositor is running. Blocks all interaction
        // (the Box swallows taps). Progress bar reflects Transformer's
        // 0–100 progress where available.
        if (isCompositing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { /* swallow */ },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { compositingProgress },
                        modifier = Modifier.width(220.dp),
                        color = Color.Cyan
                    )
                    Text(
                        text = "Merging reaction…",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${(compositingProgress * 100).toInt()}%",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ───── Split Canvas ──────────────────────────────────────────────────────

@Composable
private fun SplitCanvas(
    layout: ReactionLayout,
    cameraIsTop: Boolean,
    contentZone: ContentZone,
    sourcePlayer: ExoPlayer?,
    previewView: PreviewView,
    onContentTap: () -> Unit,
    onSwap: () -> Unit
) {
    if (layout == ReactionLayout.PIP) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background zone (full screen)
            Box(modifier = Modifier.fillMaxSize()) {
                if (cameraIsTop) {
                    ContentZoneView(zone = contentZone, sourcePlayer = sourcePlayer, onTap = onContentTap, modifier = Modifier.fillMaxSize())
                } else {
                    CameraZoneView(previewView = previewView, modifier = Modifier.fillMaxSize())
                }
            }
            // PiP bubble
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 110.dp)
                    .size(width = 110.dp, height = 150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                if (cameraIsTop) {
                    CameraZoneView(previewView = previewView, modifier = Modifier.fillMaxSize())
                } else {
                    ContentZoneView(zone = contentZone, sourcePlayer = sourcePlayer, onTap = onContentTap, modifier = Modifier.fillMaxSize())
                }
            }
            // Swap button at center
            SwapButton(
                onSwap = onSwap,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    } else {
        // Split layout — vertical column with two zones
        val (topFrac, _) = layout.split
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(topFrac)
            ) {
                if (cameraIsTop) {
                    CameraZoneView(previewView = previewView, modifier = Modifier.fillMaxSize())
                } else {
                    ContentZoneView(zone = contentZone, sourcePlayer = sourcePlayer, onTap = onContentTap, modifier = Modifier.fillMaxSize())
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black)
            ) {
                SwapButton(
                    onSwap = onSwap,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f - topFrac)
            ) {
                if (cameraIsTop) {
                    ContentZoneView(zone = contentZone, sourcePlayer = sourcePlayer, onTap = onContentTap, modifier = Modifier.fillMaxSize())
                } else {
                    CameraZoneView(previewView = previewView, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

// ───── Camera Zone ───────────────────────────────────────────────────────
//
// Just hosts the parent-owned PreviewView. The actual camera binding +
// VideoCapture lifecycle is managed in ReactionCameraView so the record
// button can drive it directly. AndroidView's factory returns the same
// instance every time, so the live surface persists across recompositions.

@Composable
private fun CameraZoneView(
    previewView: PreviewView,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { previewView }
    )
}

// ───── Content Zone ──────────────────────────────────────────────────────

@Composable
private fun ContentZoneView(
    zone: ContentZone,
    sourcePlayer: ExoPlayer?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (zone) {
        is ContentZone.Empty -> {
            Box(
                modifier = modifier
                    .background(Color(0xFF1A1A1A))
                    .clickable { onTap() },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddPhotoAlternate,
                        contentDescription = "Add video",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "Tap to add a video",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        is ContentZone.Video -> {
            if (sourcePlayer != null) {
                ReactionContentPlayer(player = sourcePlayer, modifier = modifier)
            } else {
                Box(modifier = modifier.background(Color.Black))
            }
        }
    }
}

@Composable
private fun ReactionContentPlayer(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player }
    )
}

// ───── Swap Button ───────────────────────────────────────────────────────

@Composable
private fun SwapButton(onSwap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable { onSwap() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.SwapVert,
            contentDescription = "Swap zones",
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ───── Top Bar ───────────────────────────────────────────────────────────

@Composable
private fun ReactionTopBar(
    layout: ReactionLayout,
    keepSourceAudio: Boolean,
    hasSource: Boolean,
    onCancel: () -> Unit,
    onLayoutTap: () -> Unit,
    onToggleSourceAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cancel
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable { onCancel() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, "Close", tint = Color.White, modifier = Modifier.size(18.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Source audio mute (only meaningful when there's a source video).
            if (hasSource) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { onToggleSourceAudio() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (keepSourceAudio) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        contentDescription = if (keepSourceAudio) "Source audio on" else "Source audio off",
                        tint = if (keepSourceAudio) Color.White else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Layout pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { onLayoutTap() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (layout == ReactionLayout.PIP) Icons.Filled.PictureInPicture else Icons.Filled.VerticalSplit,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = layout.displayName,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ───── Bottom Controls ───────────────────────────────────────────────────

@Composable
private fun ReactionBottomControls(
    isRecording: Boolean,
    hasContent: Boolean,
    onImport: () -> Unit,
    onRecord: () -> Unit,
    onFlipCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Import
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.clickable(enabled = !isRecording) { onImport() }
        ) {
            Icon(
                imageVector = Icons.Filled.AddPhotoAlternate,
                contentDescription = "Import",
                tint = if (isRecording) Color.White.copy(alpha = 0.4f) else Color.White,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "Import",
                color = Color.White.copy(alpha = if (isRecording) 0.4f else 0.7f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Record button (R1 stub — toggles state only)
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .clickable { onRecord() },
            contentAlignment = Alignment.Center
        ) {
            // Outer ring
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
            )
            // Inner shape — red square when recording, red circle when idle
            Box(
                modifier = Modifier
                    .size(if (isRecording) 28.dp else 56.dp)
                    .clip(if (isRecording) RoundedCornerShape(6.dp) else CircleShape)
                    .background(Color.Red)
            )
            // Outer outline ring (always visible)
            androidx.compose.foundation.Canvas(modifier = Modifier.size(72.dp)) {
                drawCircle(
                    color = Color.White,
                    radius = size.minDimension / 2f - 2.dp.toPx(),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                )
            }
        }

        // Flip camera
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.clickable(enabled = !isRecording) { onFlipCamera() }
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Flip",
                tint = if (isRecording) Color.White.copy(alpha = 0.4f) else Color.White,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "Flip",
                color = Color.White.copy(alpha = if (isRecording) 0.4f else 0.7f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ───── Layout Picker Sheet ───────────────────────────────────────────────

@Composable
private fun LayoutPickerSheet(
    current: ReactionLayout,
    onPick: (ReactionLayout) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFF1A1A1A).copy(alpha = 0.96f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* swallow taps */ }
                .padding(top = 10.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Layout",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ReactionLayout.values().forEach { layout ->
                    LayoutOption(
                        layout = layout,
                        isSelected = layout == current,
                        onClick = { onPick(layout) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutOption(
    layout: ReactionLayout,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isSelected) Color.White
                    else Color.White.copy(alpha = 0.15f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (layout == ReactionLayout.PIP) Icons.Filled.PictureInPicture else Icons.Filled.VerticalSplit,
                contentDescription = layout.displayName,
                tint = if (isSelected) Color.Black else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = layout.displayName,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ───── Source scrubber + pause toggle (R4) ───────────────────────────────

@Composable
private fun ReactionSourceControls(
    durationMs: Long,
    startMs: Long,
    onScrub: (Long) -> Unit,
    isRecording: Boolean,
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Pause/resume — only visible during recording. Idle state hides it
        // since there's nothing to pause yet.
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { onTogglePause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (isPaused) "Resume" else "Pause",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Scrubber — sets the source-start offset. Disabled during recording
        // since mid-record scrubbing isn't supported in this iteration.
        if (durationMs > 0L) {
            Slider(
                value = startMs.toFloat().coerceAtMost(durationMs.toFloat()),
                onValueChange = { v -> if (!isRecording) onScrub(v.toLong()) },
                valueRange = 0f..durationMs.toFloat(),
                enabled = !isRecording,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
