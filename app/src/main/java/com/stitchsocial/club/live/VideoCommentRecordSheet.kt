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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
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
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Viewer-side sheet for submitting a video comment to the live stream. Phase
 * 3b2 ships with PhotosPicker (gallery) as the primary input — matches the
 * iOS DEBUG fallback used during testing AND works in production on real
 * Android devices since most viewers will already have clips on their phone.
 *
 * Full CameraX recording is a follow-up (Phase 3b3) — adds a recorder use case,
 * permission gating, and an actual camera preview. Pick-only is enough to
 * close the loop end-to-end.
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
                        EmptyPickerPanel(
                            onPick = {
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
private fun EmptyPickerPanel(onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
            .clickable { onPick() }
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.PhotoLibrary,
            null,
            tint = Color.Cyan,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text("Pick a video", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Choose a clip from your library",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
        )
    }
}

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
