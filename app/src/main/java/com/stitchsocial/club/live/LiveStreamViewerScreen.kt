package com.stitchsocial.club.live

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Viewer-side live stream screen. Mirrors iOS `LiveStreamViewerView` — same
 * top overlay layout (glass capsules), same chat-bottom-left, same PiP
 * overlay anchored bottom-right.
 *
 * Args mirror the iOS init params so the call site is symmetric. The screen
 * owns its own service connections (`LiveStreamService`, `StreamChatService`,
 * `AgoraStreamService`, `StreamClipCache`) — caller doesn't need to wire them.
 */
@Composable
fun LiveStreamViewerScreen(
    userID: String,
    communityID: String,
    streamID: String,
    userLevel: Int,
    userUsername: String,
    userDisplayName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val streamService = remember { LiveStreamService.getInstance() }
    val chatService = remember { StreamChatService.getInstance() }
    val agoraService = remember { AgoraStreamService.getInstance() }
    val coinService = remember { StreamCoinService.getInstance() }

    val isStreaming by streamService.isStreaming.collectAsState()
    val viewerCount by streamService.viewerCount.collectAsState()
    val elapsedSeconds by streamService.elapsedSeconds.collectAsState()
    val pipState by streamService.pipState.collectAsState()
    val messages by chatService.messages.collectAsState()
    val remoteJoined by agoraService.remoteUserJoined.collectAsState()
    val remoteUid by agoraService.remoteUid.collectAsState()
    // From the STREAM DOC, so every viewer sees every hype — not just their own.
    val lastHypeAlert by streamService.hypeAlert.collectAsState()

    // SurfaceView lives outside Compose because Agora paints onto it. We
    // remember it once per channel so re-composition doesn't recreate the
    // canvas mid-stream.
    val remoteSurface = remember(streamID) {
        agoraService.initEngine(context)
        agoraService.createRendererView(context)
    }

    var chatDraft by remember { mutableStateOf("") }
    var showingRecordSheet by remember { mutableStateOf(false) }
    var showingHypePicker by remember { mutableStateOf(false) }
    // Free hype rapid-tap state — mirrors iOS pattern. Local display count
    // pulses + a flushable buffer that batches atomic increments to the
    // stream doc every 5s instead of writing per tap.
    var freeHypeCount by remember { mutableStateOf(0) }
    var pendingHypeTaps by remember { mutableStateOf(0) }
    var hypeFlushScheduled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Gate the video-comment submit button by the same level requirement as
    // iOS — Lv 5+ can submit. Lower-level viewers see a chat-only UI.
    // Ungated in the official community — see CommunityGateOverrides. Checked
    // here AND in StreamQueueService: if only one knows, the button enables and
    // the submit throws.
    val canSubmitVideo =
        com.stitchsocial.club.community.CommunityGateOverrides
            .liveVideoRepliesUngated(communityID) ||
        userLevel >= VideoComment.MINIMUM_LEVEL

    // Pre-flight: bail to a "Stream ended" state if the stream doc says the
    // session is over (or doesn't exist). Handles ghost streams where the
    // community doc lingered with `isCreatorLive=true` after a crash.
    var preflightFailed by remember { mutableStateOf(false) }

    // ── Lifecycle: join + leave ─────────────────────────────────────────────

    LaunchedEffect(streamID) {
        com.stitchsocial.club.services.AnalyticsService.liveStreamJoined(streamID)
        runCatching {
            // Verify the stream is actually live before paying the Agora init
            // tax. Stream may be ghost (community flag stuck on after a crash)
            // OR may have ended between the WATCH tap and this composable
            // mounting.
            val activeStreams = LiveStreamService.getInstance()
                .fetchActiveStream(communityID)
            if (activeStreams == null || activeStreams.id != streamID) {
                android.util.Log.w("LiveViewer", "stream $streamID is not live — bailing")
                preflightFailed = true
                return@runCatching
            }

            streamService.listenToStream(creatorID = communityID, streamID = streamID)
            chatService.listen(communityID = communityID, streamID = streamID)
            coinService.listenForHypes(communityID = communityID, streamID = streamID)
            agoraService.initEngine(context)
            agoraService.joinAsViewer(channelName = streamID)

            // Increment the stream doc's viewerCount + write to viewers
            // subcollection so the creator's HUD shows the real count.
            streamService.viewerJoined(
                creatorID = communityID,
                streamID = streamID,
                userID = userID,
            )

            // Drop a join message into chat with the viewer's real username.
            // Reads as "@username joined" — not the old spammy "@System".
            chatService.send(
                communityID = communityID,
                streamID = streamID,
                authorID = userID,
                authorUsername = userUsername.ifBlank { "guest" },
                authorDisplayName = userDisplayName.ifBlank { "Guest" },
                authorLevel = userLevel,
                isCreator = false,
                body = "joined",
            )
        }.onFailure { err ->
            android.util.Log.e("LiveViewer", "mount failed: ${err.localizedMessage}", err)
            preflightFailed = true
        }
    }

    // Auto-dismiss when preflight fails so we don't strand the user on a
    // dead screen. Brief delay so the user sees the "Stream ended" message.
    LaunchedEffect(preflightFailed) {
        if (preflightFailed) {
            kotlinx.coroutines.delay(1200)
            onDismiss()
        }
    }

    LaunchedEffect(remoteUid) {
        if (remoteUid != 0) {
            agoraService.setupRemoteVideo(remoteSurface, remoteUid)
        }
    }

    // KEYED ON streamID, not Unit. If the creator ends one stream and starts
    // another while this screen is composed, streamID changes and the join
    // effect re-runs — but with Unit this teardown never fired, so the Agora
    // engine was still in the OLD channel and the new join failed. That's a
    // viewer who can never get back in until the app restarts.
    DisposableEffect(streamID) {
        onDispose {
            // Flush any pending free-hype taps before tearing down — last
            // chance to credit the creator's stream with this viewer's spam.
            if (pendingHypeTaps > 0) {
                val taps = pendingHypeTaps
                pendingHypeTaps = 0
                kotlinx.coroutines.GlobalScope.launch {
                    runCatching {
                        com.google.firebase.firestore.FirebaseFirestore
                            .getInstance("stitchfin")
                            .document("communities/$communityID/streams/$streamID")
                            .update(
                                "hypeCount",
                                com.google.firebase.firestore.FieldValue.increment(taps.toLong())
                            )
                    }
                }
            }
            // Decrement viewerCount so the creator's HUD reflects the
            // leave. Fire-and-forget — we're tearing down anyway.
            kotlinx.coroutines.GlobalScope.launch {
                streamService.viewerLeft(
                    creatorID = communityID,
                    streamID = streamID,
                    userID = userID,
                )
            }
            agoraService.leaveChannel()
            chatService.cleanup()
            coinService.onStreamEnd()
            streamService.removeStreamListener()
            StreamClipCache.purge()
        }
    }

    // Auto-scroll chat to bottom when new messages arrive.
    val chatListState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            chatListState.animateScrollToItem(messages.size - 1)
        }
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Remote video (full screen). Agora paints onto the SurfaceView.
        AndroidView(
            factory = { remoteSurface },
            modifier = Modifier.fillMaxSize(),
        )

        // Preflight failed — show a friendly "Stream ended" and exit. This
        // covers ghost streams that the community doc still advertises as
        // live, and also the race where the creator ended between WATCH tap
        // and the viewer mounting.
        if (preflightFailed) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📡", fontSize = 36.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Stream ended",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        "Heading back…",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                }
            }
            return@Box
        }

        // Connecting placeholder until remote broadcaster shows up.
        if (!remoteJoined) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (isStreaming) "Connecting to stream…" else "Stream ended",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // All overlays float on top.
        Column(modifier = Modifier.fillMaxSize().statusBarsPaddingFallback()) {

            // Top: LIVE + timer (left) | viewer count (right). Close moved
            // to the right-side cluster to match iOS.
            TopOverlay(
                elapsedSeconds = elapsedSeconds,
                viewerCount = viewerCount,
            )

            Spacer(Modifier.weight(1f))

            // Chat (bottom-left ~60% width) + right-side action cluster
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.6f)
                        .padding(start = 12.dp, bottom = 8.dp)
                        .heightIn(max = 240.dp),
                ) {
                    LazyColumn(
                        state = chatListState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(messages.takeLast(8), key = { it.id }) { msg ->
                            ChatRow(msg)
                        }
                    }
                }

                Spacer(Modifier.weight(0.1f))

                ViewerRightSideButtons(
                    freeHypeCount = freeHypeCount,
                    canSubmitVideo = canSubmitVideo,
                    onFreeHype = {
                        freeHypeCount += 1
                        pendingHypeTaps += 1
                        if (!hypeFlushScheduled) {
                            hypeFlushScheduled = true
                            scope.launch {
                                kotlinx.coroutines.delay(5_000)
                                val taps = pendingHypeTaps
                                pendingHypeTaps = 0
                                hypeFlushScheduled = false
                                if (taps > 0) {
                                    runCatching {
                                        com.google.firebase.firestore.FirebaseFirestore
                                            .getInstance("stitchfin")
                                            .document("communities/$communityID/streams/$streamID")
                                            .update(
                                                "hypeCount",
                                                com.google.firebase.firestore.FieldValue.increment(taps.toLong())
                                            )
                                            .await()
                                    }
                                    // One chat line per FLUSH, never per tap —
                                    // the taps are already batched precisely so a
                                    // spam-tapper doesn't spam the room.
                                    runCatching {
                                        chatService.sendFreeHypeAnnouncement(
                                            communityID = communityID,
                                            streamID = streamID,
                                            username = userUsername,
                                            count = taps,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onOpenGifts = { showingHypePicker = true },
                    onOpenReply = { showingRecordSheet = true },
                    onClose = onDismiss,
                    modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
                )
            }

            // Bottom input — chat-only, matches iOS viewerBottomInputBar.
            ChatInput(
                draft = chatDraft,
                onChange = { chatDraft = it },
                onSendChat = {
                    val body = chatDraft
                    if (body.isBlank()) return@ChatInput
                    chatDraft = ""
                    scope.launch {
                        chatService.send(
                            communityID = communityID,
                            streamID = streamID,
                            authorID = userID,
                            authorUsername = userUsername,
                            authorDisplayName = userDisplayName,
                            authorLevel = userLevel,
                            isCreator = false,
                            body = body,
                        )
                    }
                },
            )
        }

        // Hype storm alert — slides in when ANY viewer sends a hype.
        // Suppressed while recording, along with the PiP below.
        if (!showingRecordSheet) {
            HypeStormAlert(
                event = lastHypeAlert,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // PiP overlay — appears when the creator broadcasts a queued clip.
        //
        // HIDDEN WHILE RECORDING A REPLY. Two reasons, and the second is the one
        // that matters: this used to be declared AFTER the record sheet in this
        // Box, so it painted straight over the recorder — the viewer filmed
        // themselves behind someone else's clip. And because the overlay stayed
        // in the tree its player kept going, so the PiP's audio went down the
        // microphone and into the reply. Taking it out of composition tears the
        // player down, which stops both.
        if (!showingRecordSheet) {
            pipState?.let { pip ->
                LiveStreamPipOverlay(
                    videoURL = pip.videoURL,
                    authorUsername = pip.authorUsername,
                    authorLevel = pip.authorLevel,
                    playbackToken = pip.playbackToken,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Hype picker sheet — bottom-anchored grid of hype types.
        if (showingHypePicker) {
            HypePickerSheet(
                streamID = streamID,
                communityID = communityID,
                senderID = userID,
                senderUsername = userUsername,
                senderLevel = userLevel,
                onDismiss = { showingHypePicker = false },
            )
        }

        // Video comment record sheet — fullscreen overlay when the viewer taps
        // the 📹 button. Disabled below Lv 5 (gated by canSubmitVideo).
        // Declared LAST so nothing in this Box can draw over the camera.
        if (showingRecordSheet) {
            VideoCommentRecordSheet(
                userID = userID,
                communityID = communityID,
                streamID = streamID,
                userLevel = userLevel,
                userUsername = userUsername,
                userDisplayName = userDisplayName,
                onDismiss = { showingRecordSheet = false },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top overlay — glass capsule clusters, mirrors iOS revamp.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopOverlay(
    elapsedSeconds: Int,
    viewerCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LIVE + timer cluster
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .background(Color.Red)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(Color.White, CircleShape),
                )
                Text(
                    "LIVE",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Text(
                text = formatElapsed(elapsedSeconds),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        // Stats — viewer count. Close moved to right-side button cluster.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(11.dp),
            )
            Text(
                text = formatCount(viewerCount),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun ChatRow(msg: StreamChatMessage) {
    // Paid gifts and hype bursts are announcements, not conversation — they get
    // their own colour so they read at a glance in a moving chat. Matches iOS.
    if (msg.isGift || msg.isFreeHype) {
        val tint = if (msg.isGift) Color(0xFFFFC107) else Color(0xFFFF8A3D)
        Text(
            text = msg.body,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        return
    }

    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (msg.isCreator) {
            Text("👑", fontSize = 9.sp)
        }
        Text(
            text = "@${msg.authorUsername}",
            fontSize = 11.sp,
            fontWeight = if (msg.isCreator) FontWeight.Black else FontWeight.Bold,
            color = if (msg.isCreator) Color.Cyan else Color.White,
        )
        Text(
            text = msg.body,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ChatInput(
    draft: String,
    onChange: (String) -> Unit,
    onSendChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Cyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSendChat() }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            "Reply to chat…",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 14.sp,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Cyan)
                .clickable { onSendChat() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Send",
                tint = Color.Black,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatElapsed(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatCount(count: Int): String =
    if (count >= 1000) "%.1fK".format(count / 1000.0) else count.toString()

// Status-bar inset shim — wraps `windowInsetsPadding(WindowInsets.statusBars)`
// but falls back gracefully if the call site doesn't have the import.
@Composable
private fun Modifier.statusBarsPaddingFallback(): Modifier {
    return this.then(
        Modifier.windowInsetsPadding(
            androidx.compose.foundation.layout.WindowInsets.statusBars
        )
    )
}
