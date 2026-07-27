/*
 * CommunityVideoComposerSheet.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Video-thread composer for community posts
 *
 * Replaces the text-only ComposePostSheet on the community detail FAB. Picks
 * a video from the user's library, uploads it to community-posts Storage,
 * writes a CommunityPost with postType=VIDEO_CLIP. Matches iOS V2 intent —
 * community threads are video-first.
 *
 * Submission states (mirrors VideoCommentRecordSheet for the live queue):
 *   - Idle: empty picker
 *   - Preview: clip selected, optional caption
 *   - Uploading: linear progress bar
 *   - Done: success blip, auto-dismiss
 *   - Failed: error message + Retry
 */

package com.stitchsocial.club.views

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.community.CommunityMembership
import com.stitchsocial.club.community.CommunityPost
import com.stitchsocial.club.community.CommunityPostType
import com.stitchsocial.club.community.CommunityPostUploadService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

private object VC {
    val bg = Color(0xFF0F0B1E)
    val card = Color(0xFF1A1432)
    val cardBorder = Color.White.copy(alpha = 0.10f)
    val cyan = Color(0xFF00D4FF)
    val purple = Color(0xFF8B5CF6)
    val pink = Color(0xFFF0245F)
    val red = Color(0xFFEF4444)
    val txt = Color(0xFFF1F5F9)
    val txt2 = Color(0xFF94A3B8)
    val txt3 = Color(0xFF64748B)
}

@Composable
fun CommunityVideoComposerSheet(
    userID: String,
    communityID: String,
    membership: CommunityMembership?,
    isCreator: Boolean,
    onPosted: (CommunityPost) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { FirebaseFirestore.getInstance("stitchfin") }

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<Submit>(Submit.Idle) }
    var progress by remember { mutableStateOf(0f) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) pickedUri = uri }

    // In-app RECORD (iOS parity, 4a71f71): Android had no in-app recorder
    // (VideoCommentRecordSheet is pick-only), so record via the system camera
    // (CaptureVideo → a MediaStore Uri) and feed the result into the same upload
    // flow as a picked clip. Full CameraX capture is a follow-up.
    var captureTargetUri by remember { mutableStateOf<Uri?>(null) }
    val recorder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo(),
    ) { success -> if (success) captureTargetUri?.let { pickedUri = it } }
    val startRecording = {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "community_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        val target = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        captureTargetUri = target
        target?.let { recorder.launch(it) }
        Unit
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Start a video thread", color = VC.txt, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    Text(
                        "Threads in this community are video-first. Pick a clip to start one.",
                        color = VC.txt2,
                        fontSize = 11.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(enabled = state !is Submit.Uploading) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            when (val s = state) {
                is Submit.Idle -> {
                    if (pickedUri == null) {
                        EmptyPanel(
                            onRecord = startRecording,
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
                            onPickAgain = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            onSubmit = {
                                val uri = pickedUri ?: return@PreviewPanel
                                state = Submit.Uploading
                                progress = 0f
                                scope.launch {
                                    runCatching {
                                        val postID = UUID.randomUUID().toString()
                                        val upload = CommunityPostUploadService.uploadPostVideo(
                                            context = context,
                                            localUri = uri,
                                            communityID = communityID,
                                            postID = postID,
                                            onProgress = { progress = it },
                                        )

                                        val post = CommunityPost(
                                            id = postID,
                                            communityID = communityID,
                                            authorID = userID,
                                            authorUsername = membership?.username ?: "user",
                                            authorDisplayName = membership?.displayName ?: "User",
                                            authorLevel = membership?.level ?: 0,
                                            isCreatorPost = isCreator,
                                            postType = CommunityPostType.VIDEO_CLIP,
                                            body = caption.trim(),
                                            videoURL = upload.videoURL,
                                            videoThumbnailURL = upload.thumbnailURL,
                                            videoDurationSeconds = upload.durationSeconds,
                                            createdAt = Date(),
                                        )
                                        db.collection("communities").document(communityID)
                                            .collection("posts").document(postID)
                                            .set(post.toFirestore())
                                            .await()
                                        post
                                    }.onSuccess { post ->
                                        state = Submit.Done
                                        onPosted(post)
                                        delay(900)
                                        onDismiss()
                                    }.onFailure { err ->
                                        state = Submit.Failed(err.localizedMessage ?: "Upload failed")
                                    }
                                }
                            },
                        )
                    }
                }
                is Submit.Uploading -> UploadingPanel(progress)
                is Submit.Done -> DonePanel()
                is Submit.Failed -> FailedPanel(s.message) { state = Submit.Idle }
            }
        }
    }
}

private sealed interface Submit {
    data object Idle : Submit
    data object Uploading : Submit
    data object Done : Submit
    data class Failed(val message: String) : Submit
}

@Composable
private fun EmptyPanel(onRecord: () -> Unit, onPick: () -> Unit) {
    // Two options (iOS parity): Record a video / Pick a clip.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmptyOptionCard(
            icon = Icons.Default.Videocam,
            title = "Record",
            subtitle = "Shoot a new clip",
            tint = VC.pink,
            modifier = Modifier.weight(1f),
            onTap = onRecord,
        )
        EmptyOptionCard(
            icon = Icons.Default.PhotoLibrary,
            title = "Pick a clip",
            subtitle = "From your library",
            tint = VC.cyan,
            modifier = Modifier.weight(1f),
            onTap = onPick,
        )
    }
}

@Composable
private fun EmptyOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(VC.card)
            .border(0.5.dp, VC.cardBorder, RoundedCornerShape(18.dp))
            .clickable { onTap() }
            .padding(vertical = 28.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, color = VC.txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = VC.txt2, fontSize = 11.sp)
    }
}

@Composable
private fun PreviewPanel(
    uri: Uri,
    caption: String,
    onCaptionChange: (String) -> Unit,
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
        onDispose { player.stop(); player.release() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(18.dp))
                .border(2.dp, VC.cardBorder, RoundedCornerShape(18.dp)),
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

        BasicTextField(
            value = caption,
            onValueChange = { if (it.length <= 200) onCaptionChange(it) },
            singleLine = false,
            textStyle = TextStyle(color = VC.txt, fontSize = 13.sp),
            cursorBrush = SolidColor(VC.cyan),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(VC.card, RoundedCornerShape(12.dp))
                        .border(0.5.dp, VC.cardBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (caption.isEmpty()) {
                        Text("Add a caption (optional)", color = VC.txt3, fontSize = 13.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

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
                Text("Pick another", color = VC.txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .weight(1.6f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(VC.cyan)
                    .clickable { onSubmit() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Send, null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    Text("Post thread", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun UploadingPanel(progress: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
        CircularProgressIndicator(color = VC.cyan)
        Spacer(Modifier.height(14.dp))
        Text("Uploading…", color = VC.txt, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(0.7f).height(4.dp),
            color = VC.cyan,
            trackColor = Color.White.copy(alpha = 0.15f),
        )
        Spacer(Modifier.height(8.dp))
        Text("${(progress * 100).toInt()}%", color = VC.txt2, fontSize = 11.sp)
    }
}

@Composable
private fun DonePanel() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
        Text("✅", fontSize = 40.sp)
        Spacer(Modifier.height(8.dp))
        Text("Thread posted", color = VC.txt, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun FailedPanel(error: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
        Text("⚠️", fontSize = 40.sp)
        Spacer(Modifier.height(8.dp))
        Text("Upload failed", color = VC.txt, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(error, color = VC.txt2, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(VC.cyan)
                .clickable { onRetry() }
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text("Try again", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}
