package com.stitchsocial.club.live

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Viewer-side sheet for submitting a video comment to the live stream.
 *
 * RECORDING IS THE DEFAULT (2026-08-04). This shipped pick-only, which is the
 * wrong gesture for a live stream: a reply to something happening right now has
 * to be filmed right now, and iOS has always opened straight to a camera. The
 * CameraX recorder promised as "Phase 3b3" is now here, with the library picker
 * kept as the secondary path for a clip someone already has.
 *
 * Submission states (mirrors iOS `submissionState`):
 *  - IDLE: pick or cancel.
 *  - PREVIEW: clip selected, optional caption + priority.
 *  - UPLOADING: progress bar, no cancel (will land or fail).
 *  - DONE: brief success state then dismiss.
 *  - FAILED: error string, allow retry.
 */
@Composable
fun VideoCommentRecordSheet(
    userID: String,
    communityID: String,
    streamID: String,
    userLevel: Int,
    userUsername: String,
    userDisplayName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queueService = remember { StreamQueueService.getInstance() }

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    var isPriority by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<SubmissionState>(SubmissionState.Idle) }
    var progress by remember { mutableStateOf(0f) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) pickedUri = uri
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Send a video comment",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                    )
                    val maxSecs = VideoComment.maxClipSeconds(userLevel)
                    Text(
                        "Lv $userLevel · up to ${maxSecs}s clip",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            when (val s = state) {
                is SubmissionState.Idle -> {
                    if (pickedUri == null) {
                        ReplyCameraRecorder(
                            maxSeconds = VideoComment.maxClipSeconds(userLevel),
                            onRecorded = { pickedUri = it },
                            onPickInstead = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                        )
                    } else {
                        PreviewPanel(
                            uri = pickedUri!!,
                            caption = caption,
                            onCaptionChange = { caption = it },
                            isPriority = isPriority,
                            onPriorityToggle = { isPriority = it },
                            onPickAgain = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            onSubmit = {
                                val uri = pickedUri ?: return@PreviewPanel
                                state = SubmissionState.Uploading
                                progress = 0f
                                scope.launch {
                                    runCatching {
                                        val commentID = UUID.randomUUID().toString()
                                        val upload = StreamClipUploadService.uploadClip(
                                            context = context,
                                            localUri = uri,
                                            communityID = communityID,
                                            streamID = streamID,
                                            commentID = commentID,
                                            onProgress = { progress = it },
                                        )
                                        queueService.submitVideoComment(
                                            streamID = streamID,
                                            communityID = communityID,
                                            authorID = userID,
                                            authorUsername = userUsername,
                                            authorDisplayName = userDisplayName,
                                            authorLevel = userLevel,
                                            videoURL = upload.videoURL,
                                            thumbnailURL = upload.thumbnailURL,
                                            durationSeconds = upload.durationSeconds,
                                            caption = caption.trim(),
                                            isPriority = isPriority,
                                            priorityCoinsCost = if (isPriority) 10 else 0,
                                        )
                                    }.onSuccess {
                                        state = SubmissionState.Done
                                        kotlinx.coroutines.delay(900)
                                        onDismiss()
                                    }.onFailure { err ->
                                        state = SubmissionState.Failed(
                                            err.localizedMessage ?: "Upload failed"
                                        )
                                    }
                                }
                            },
                        )
                    }
                }

                is SubmissionState.Uploading -> {
                    UploadingPanel(progress = progress)
                }

                is SubmissionState.Done -> {
                    DonePanel()
                }

                is SubmissionState.Failed -> {
                    FailedPanel(
                        error = s.message,
                        onRetry = { state = SubmissionState.Idle },
                    )
                }
            }
        }
    }
}

private sealed interface SubmissionState {
    data object Idle : SubmissionState
    data object Uploading : SubmissionState
    data object Done : SubmissionState
    data class Failed(val message: String) : SubmissionState
}

// ── Sub-panels ──────────────────────────────────────────────────────────────

@Composable
private fun PreviewPanel(
    uri: Uri,
    caption: String,
    onCaptionChange: (String) -> Unit,
    isPriority: Boolean,
    onPriorityToggle: (Boolean) -> Unit,
    onPickAgain: () -> Unit,
    onSubmit: () -> Unit,
) {
    val context = LocalContext.current

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
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

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // 9:16 preview
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
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

        Spacer(Modifier.height(14.dp))

        // Caption input
        BasicTextField(
            value = caption,
            onValueChange = { if (it.length <= 80) onCaptionChange(it) },
            singleLine = false,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Cyan),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                        .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (caption.isEmpty()) {
                        Text(
                            "Add a caption (optional)",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 13.sp,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // Priority toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isPriority) Color.Yellow.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f),
                    RoundedCornerShape(12.dp),
                )
                .border(
                    0.5.dp,
                    if (isPriority) Color.Yellow.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "⚡ Priority queue",
                    color = if (isPriority) Color.Yellow else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Jumps ahead of regular clips. Costs 10 🪙",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                )
            }
            Switch(
                checked = isPriority,
                onCheckedChange = onPriorityToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Yellow,
                    checkedTrackColor = Color.Yellow.copy(alpha = 0.4f),
                ),
            )
        }

        Spacer(Modifier.height(16.dp))

        // Action row
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable { onPickAgain() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Pick another", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .weight(1.6f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Cyan)
                    .clickable { onSubmit() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Send, null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    Text("Send to queue", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun UploadingPanel(progress: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
        CircularProgressIndicator(color = Color.Cyan)
        Spacer(Modifier.height(14.dp))
        Text("Uploading…", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(0.7f).height(4.dp),
            color = Color.Cyan,
            trackColor = Color.White.copy(alpha = 0.15f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${(progress * 100).toInt()}%",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun DonePanel() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
        Text("✅", fontSize = 40.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sent to the queue!",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "The creator will see it shortly",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun FailedPanel(error: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
        Text("⚠️", fontSize = 40.sp)
        Spacer(Modifier.height(8.dp))
        Text("Upload failed", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            error,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Cyan)
                .clickable { onRetry() }
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text("Try again", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}

// ── In-app recorder ─────────────────────────────────────────────────────────

/**
 * CameraX recorder for a live-stream video reply — the Phase 3b3 follow-up the
 * header promised. Viewers could only ever attach a clip they'd already filmed,
 * which is the wrong gesture entirely: a reply to something happening live has
 * to be filmed now, and asking someone to leave the stream, open their camera
 * app and come back loses both the moment and the viewer.
 *
 * Produces a Uri and hands it to the SAME `pickedUri` the picker feeds, so
 * preview, caption, priority and upload are untouched below this point.
 *
 * The clip is hard-capped at the level's max length: recording auto-stops on the
 * timer. Trusting the viewer to stop in time would push the clamp downstream
 * into StreamQueueService, which truncates the RECORDED duration rather than the
 * file — so the queue would show 30s on a 90s clip.
 */
@Composable
private fun ReplyCameraRecorder(
    maxSeconds: Int,
    onRecorded: (Uri) -> Unit,
    onPickInstead: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted[android.Manifest.permission.CAMERA] == true &&
            granted[android.Manifest.permission.RECORD_AUDIO] == true
        hasPermission = ok
        permissionDenied = !ok
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO,
                )
            )
        }
    }

    var isRecording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0) }
    var useFrontCamera by remember { mutableStateOf(true) }
    var recording by remember { mutableStateOf<androidx.camera.video.Recording?>(null) }
    var videoCapture by remember {
        mutableStateOf<androidx.camera.core.UseCase?>(null)
    }
    val previewView = remember {
        androidx.camera.view.PreviewView(context).apply {
            scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Bind/rebind whenever the facing changes. Unbinding first matters: leaving
    // the old use cases bound silently keeps the previous lens alive and the
    // flip button appears to do nothing.
    LaunchedEffect(hasPermission, useFrontCamera) {
        if (!hasPermission) return@LaunchedEffect
        val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
        val preview = androidx.camera.core.Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val recorder = androidx.camera.video.Recorder.Builder()
            .setQualitySelector(
                androidx.camera.video.QualitySelector.from(
                    androidx.camera.video.Quality.HD,
                    androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(
                        androidx.camera.video.Quality.SD
                    )
                )
            )
            .build()
        val capture = androidx.camera.video.VideoCapture.withOutput(recorder)
        val selector = if (useFrontCamera) {
            androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
        }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            videoCapture = capture
        }.onFailure {
            if (BuildConfig.DEBUG) println("⚠️ REPLY CAM: bind failed — ${it.message}")
        }
    }

    // Elapsed timer + the hard stop at the level cap.
    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        elapsed = 0
        while (elapsed < maxSeconds) {
            delay(1_000)
            elapsed += 1
        }
        recording?.stop()
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
                .border(
                    1.5.dp,
                    if (isRecording) Color.Red.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(18.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (hasPermission) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    if (permissionDenied) {
                        "Camera and microphone access are off. Turn them on in Settings to record a reply."
                    } else {
                        "Waiting for camera…"
                    },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(24.dp),
                )
            }

            if (isRecording) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Red.copy(alpha = 0.75f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                    Text(
                        "0:${elapsed.toString().padStart(2, '0')} / 0:${maxSeconds.toString().padStart(2, '0')}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (hasPermission && !isRecording) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { useFrontCamera = !useFrontCamera },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        "Flip camera",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (isRecording) Color.Red else Color.White.copy(alpha = 0.14f))
                .border(3.dp, if (isRecording) Color.White else Color.Red, CircleShape)
                .clickable(enabled = hasPermission) {
                    if (isRecording) {
                        recording?.stop()
                        return@clickable
                    }
                    val capture = videoCapture as? androidx.camera.video.VideoCapture<*> ?: return@clickable
                    @Suppress("UNCHECKED_CAST")
                    val typed = capture as androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>
                    val file = java.io.File(
                        context.cacheDir,
                        "reply-${System.currentTimeMillis()}.mp4",
                    )
                    val options = androidx.camera.video.FileOutputOptions.Builder(file).build()
                    isRecording = true
                    recording = typed.output
                        .prepareRecording(context, options)
                        .withAudioEnabled()
                        .start(androidx.core.content.ContextCompat.getMainExecutor(context)) { event ->
                            if (event is androidx.camera.video.VideoRecordEvent.Finalize) {
                                isRecording = false
                                recording = null
                                if (event.hasError()) {
                                    if (BuildConfig.DEBUG) {
                                        println("⚠️ REPLY CAM: record error ${event.error}")
                                    }
                                } else {
                                    onRecorded(Uri.fromFile(file))
                                }
                            }
                        }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isRecording) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            if (isRecording) "Tap to stop" else "Tap to record · up to ${maxSeconds}s",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
        )

        if (!isRecording) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Upload a clip instead",
                color = Color.Cyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onPickInstead() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
