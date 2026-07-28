/*
 * CommunityPostDetailView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Community post detail: play the video, stitch to it, delete it.
 *
 * Port of iOS 89ceba4 (PostDetailView). Android previously had no community post
 * detail at all — CommunityDetailV2View tracked `selectedPost` but never
 * presented anything, so tapping a thread did nothing. This adds all three:
 *
 *  - Play:   CommunityClipPlayer, an inline looping ExoPlayer, renders
 *            post.videoURL at the top of the post; also used for video replies.
 *  - Stitch: opens the full app recorder via CommunityClipRouter; the finished
 *            clip uploads through CommunityPostUploadService and is written as a
 *            video reply (CommunityReply.videoURL).
 *  - Delete: post author or community creator gets a trash button -> confirm ->
 *            CommunityFeedService.deletePost -> dismiss.
 *
 * Text replies still work unchanged; video replies render with the inline player
 * plus a "Stitch" tag.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.stitchsocial.club.community.CommunityClipRouter
import com.stitchsocial.club.community.CommunityFeedService
import com.stitchsocial.club.community.CommunityMembership
import com.stitchsocial.club.community.CommunityPost
import com.stitchsocial.club.community.CommunityPostUploadService
import com.stitchsocial.club.community.CommunityReply
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

private object PD {
    val bg = Color(0xFF0D0B18)
    val card = Color.White.copy(alpha = 0.04f)
    val cardBorder = Color.White.copy(alpha = 0.07f)
    val pink = Color(0xFFF0245F)
    val cyan = Color(0xFF22D3EE)
    val txt = Color(0xFFF1F5F9)
    val txt2 = Color(0xFF94A3B8)
    val txt3 = Color(0xFF64748B)
}

@Composable
fun CommunityPostDetailView(
    userID: String,
    communityID: String,
    post: CommunityPost,
    membership: CommunityMembership?,
    /** True when [userID] owns the channel — channel owners can delete any post. */
    isCommunityCreator: Boolean,
    onDismiss: () -> Unit,
    onDeleted: (CommunityPost) -> Unit,
    /** Opens the real app recorder scoped to this post (a stitch/video reply). */
    onRecordClip: (communityID: String, postID: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feed = remember { CommunityFeedService.shared }

    var replies by remember { mutableStateOf<List<CommunityReply>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var draft by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var stitchProgress by remember { mutableStateOf<Float?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val canDelete = post.authorID == userID || isCommunityCreator

    LaunchedEffect(post.id) {
        isLoading = true
        runCatching { feed.fetchReplies(postID = post.id, communityID = communityID, refresh = true) }
            .onSuccess { replies = it }
        isLoading = false
    }

    // Stitch: collect the clip the app recorder handed back for THIS post, upload
    // it, and write it as a video reply (iOS parity, 89ceba4).
    val finishedClip by CommunityClipRouter.finishedClip.collectAsState()
    LaunchedEffect(finishedClip) {
        val clip = finishedClip ?: return@LaunchedEffect
        if (clip.target.communityID != communityID || clip.target.postID != post.id) return@LaunchedEffect
        CommunityClipRouter.consume()

        stitchProgress = 0f
        errorText = null
        runCatching {
            val replyID = UUID.randomUUID().toString()
            val upload = CommunityPostUploadService.uploadPostVideo(
                context = context,
                localUri = android.net.Uri.parse(
                    if (clip.videoPath.startsWith("content://") || clip.videoPath.startsWith("file://")) {
                        clip.videoPath
                    } else {
                        "file://${clip.videoPath}"
                    }
                ),
                communityID = communityID,
                postID = replyID,
                onProgress = { stitchProgress = it },
            )
            feed.createReply(
                postID = post.id,
                communityID = communityID,
                authorID = userID,
                authorUsername = membership?.username ?: "user",
                authorDisplayName = membership?.displayName ?: "User",
                authorLevel = membership?.level ?: 0,
                isCreatorReply = isCommunityCreator,
                body = "",
                replyID = replyID,
                videoURL = upload.videoURL,
                videoThumbnailURL = upload.thumbnailURL,
                videoDurationSeconds = upload.durationSeconds,
            )
        }.onSuccess { reply ->
            com.stitchsocial.club.services.AnalyticsService.stitchCreated(communityID)
            replies = replies + reply
            stitchProgress = null
        }.onFailure { err ->
            stitchProgress = null
            errorText = err.localizedMessage ?: "Couldn't post that stitch"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PD.bg)
            // Absorb stray taps so they don't reach the channel behind this view.
            .pointerInput(Unit) { detectTapGestures(onTap = { }) }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable { onDismiss() },
                )
                Spacer(Modifier.width(12.dp))
                Text("Thread", color = PD.txt, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (canDelete) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable(enabled = !isDeleting) { confirmingDelete = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(color = PD.pink, modifier = Modifier.size(14.dp))
                        } else {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete post",
                                tint = PD.pink,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            ) {
                item {
                    // The post itself — video first (iOS parity: the thread video
                    // never played before; now it does).
                    post.videoURL?.let { url ->
                        CommunityClipPlayer(
                            url = url,
                            autoPlay = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(9f / 16f)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonogramTile(post.authorDisplayName, size = 30)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "@${post.authorUsername}",
                            color = PD.txt,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(shortAge(post.createdAt), color = PD.txt3, fontSize = 10.5.sp)
                    }

                    if (post.body.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            post.body,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${post.hypeCount} hype · ${replies.size} ${if (replies.size == 1) "reply" else "replies"}",
                        color = PD.txt2,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )

                    Spacer(Modifier.height(14.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(PD.cardBorder))
                    Spacer(Modifier.height(12.dp))
                }

                if (stitchProgress != null) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Text("Posting your stitch…", color = PD.txt2, fontSize = 11.sp)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (stitchProgress ?: 0f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = PD.pink,
                                trackColor = Color.White.copy(alpha = 0.12f),
                            )
                        }
                    }
                }

                errorText?.let { msg ->
                    item {
                        Text(
                            msg,
                            color = PD.pink,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                }

                if (isLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = PD.cyan, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (replies.isEmpty()) {
                    item {
                        Text(
                            "No replies yet — stitch a video or say something.",
                            color = PD.txt3,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }

                items(replies, key = { it.id }) { reply ->
                    ReplyRow(reply)
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            // Composer — text reply + Stitch (video reply)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.03f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Stitch: same recorder as the main + button, scoped to this post.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(PD.pink)
                        .clickable(enabled = stitchProgress == null) {
                            onRecordClip(communityID, post.id)
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Default.Videocam, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Text("Stitch", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                BasicTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 500) draft = it },
                    textStyle = TextStyle(color = PD.txt, fontSize = 13.sp),
                    cursorBrush = SolidColor(PD.cyan),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                                .border(0.5.dp, PD.cardBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            if (draft.isEmpty()) {
                                Text("Add a reply", color = PD.txt3, fontSize = 13.sp)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )

                val canSend = draft.isNotBlank() && !isSending
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (canSend) PD.cyan else Color.White.copy(alpha = 0.08f))
                        .clickable(enabled = canSend) {
                            val body = draft.trim()
                            isSending = true
                            scope.launch {
                                runCatching {
                                    feed.createReply(
                                        postID = post.id,
                                        communityID = communityID,
                                        authorID = userID,
                                        authorUsername = membership?.username ?: "user",
                                        authorDisplayName = membership?.displayName ?: "User",
                                        authorLevel = membership?.level ?: 0,
                                        isCreatorReply = isCommunityCreator,
                                        body = body,
                                    )
                                }.onSuccess { reply ->
                                    replies = replies + reply
                                    draft = ""
                                }.onFailure { err ->
                                    errorText = err.localizedMessage ?: "Couldn't post that reply"
                                }
                                isSending = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send reply",
                        tint = if (canSend) Color.Black else PD.txt3,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (confirmingDelete) {
            DeleteConfirmSheet(
                onCancel = { confirmingDelete = false },
                onConfirm = {
                    confirmingDelete = false
                    isDeleting = true
                    scope.launch {
                        runCatching {
                            feed.deletePost(
                                postID = post.id,
                                communityID = communityID,
                                authorID = post.authorID,
                            )
                        }.onSuccess {
                            isDeleting = false
                            onDeleted(post)
                            onDismiss()
                        }.onFailure { err ->
                            isDeleting = false
                            errorText = err.localizedMessage ?: "Couldn't delete that post"
                        }
                    }
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reply row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReplyRow(reply: CommunityReply) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonogramTile(reply.authorDisplayName, size = 22)
            Spacer(Modifier.width(7.dp))
            Text(
                "@${reply.authorUsername}",
                color = PD.txt,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text(shortAge(reply.createdAt), color = PD.txt3, fontSize = 10.sp)
            if (reply.videoURL != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "Stitch",
                    color = PD.pink,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(PD.pink.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        reply.videoURL?.let { url ->
            Spacer(Modifier.height(8.dp))
            CommunityClipPlayer(
                url = url,
                autoPlay = false,
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }

        if (reply.body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                reply.body,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 29.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Inline looping player (post video + video replies)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CommunityClipPlayer(
    url: String,
    autoPlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var playing by remember(url) { mutableStateOf(autoPlay) }

    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = autoPlay
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.stop(); player.release() }
    }
    LaunchedEffect(playing) { player.playWhenReady = playing }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable { playing = !playing },
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
        if (!playing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bits
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeleteConfirmSheet(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onCancel() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1A1432))
                .border(0.5.dp, PD.cardBorder, RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = { }) }
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Delete this post?", color = PD.txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "The video and its replies come down for everyone in the channel.",
                color = PD.txt2,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable { onCancel() }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Keep it", color = PD.txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PD.pink)
                        .clickable { onConfirm() }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Delete", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun MonogramTile(name: String, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(PD.card)
            .border(0.5.dp, PD.cardBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            color = PD.txt2,
            fontSize = (size * 0.42f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** "3m" / "5h" / "2d" — same compact age the thread rows use. */
private fun shortAge(date: Date): String {
    val seconds = ((System.currentTimeMillis() - date.time) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "now"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h"
        else -> "${seconds / 86_400}d"
    }
}
