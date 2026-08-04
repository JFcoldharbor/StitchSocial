package com.stitchsocial.club.live

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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether a creator's live screen is currently on screen, tracked OUTSIDE
 * composition on purpose.
 *
 * When the screen leaves and re-enters composition every `remember` is
 * re-initialised, so a presence flag stored in one can't tell "I came back"
 * from "I'm gone" — the coroutine doing the teardown would be holding the old
 * instance. This survives the churn, and the teardown scope with it: a scope
 * from `rememberCoroutineScope()` is cancelled the instant the screen leaves,
 * which is the worst possible moment to be ending a stream.
 */
private object CreatorScreenPresence {
    private val onScreen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun enter(creatorID: String) { onScreen[creatorID] = true }
    fun leave(creatorID: String) { onScreen[creatorID] = false }
    fun isOnScreen(creatorID: String) = onScreen[creatorID] == true
}

/**
 * Creator-side live stream screen. Minimum-viable Phase 3a — supports:
 *  - Tier selection (default Spark) → starts the Firestore stream doc + flips
 *    the community's `isCreatorLive` flag.
 *  - Camera preview via Agora local SurfaceView (broadcaster role).
 *  - Live chat (read + send), same subcollection as viewers see.
 *  - End stream button + confirm.
 *  - Mic mute toggle + camera flip.
 *
 * NOT in this chunk (Phase 3b/c follow):
 *  - Video comment queue carousel + accept/reject + PiP broadcast
 *  - Tips/hypes (StreamCoinService)
 *  - Completion records / XP rollup
 *  - Duration progress bar
 */
@Composable
fun LiveStreamCreatorScreen(
    creatorID: String,
    creatorUsername: String,
    creatorDisplayName: String,
    tier: StreamDurationTier = StreamDurationTier.SPARK,
    /** Composed in GoLivePromptSheet before this screen is shown. */
    goLiveMessage: String = "",
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val streamService = remember { LiveStreamService.getInstance() }
    val chatService = remember { StreamChatService.getInstance() }
    val agoraService = remember { AgoraStreamService.getInstance() }
    val queueService = remember { StreamQueueService.getInstance() }
    val coinService = remember { StreamCoinService.getInstance() }

    val activeStream by streamService.activeStream.collectAsState()
    val elapsedSeconds by streamService.elapsedSeconds.collectAsState()
    val viewerCount by streamService.viewerCount.collectAsState()
    val messages by chatService.messages.collectAsState()
    val pendingComments by queueService.pendingComments.collectAsState()
    val displayedComments by queueService.displayedComments.collectAsState()
    val activePiP by queueService.activePiP.collectAsState()
    val pipPlaybackToken by queueService.pipPlaybackToken.collectAsState()
    val lastHypeAlert by coinService.lastHypeAlert.collectAsState()

    var isStarting by remember { mutableStateOf(true) }
    var startError by remember { mutableStateOf<String?>(null) }
    var showingEndConfirm by remember { mutableStateOf(false) }
    var chatDraft by remember { mutableStateOf("") }
    var micMuted by remember { mutableStateOf(false) }
    var showingQueue by remember { mutableStateOf(false) }
    var previewingComment by remember { mutableStateOf<VideoComment?>(null) }
    val scope = rememberCoroutineScope()

    // Local camera preview SurfaceView — owned outside Compose so Agora can
    // paint onto it across recompositions.
    val localSurface = remember {
        agoraService.initEngine(context)
        agoraService.createRendererView(context)
    }

    // ── Lifecycle: start stream → join Agora → listen to chat ──────────────

    // Guarded against re-running. LaunchedEffect(Unit) restarts if this
    // composable leaves and re-enters composition, and each restart used to
    // create a SECOND stream — production has stream docs written in pairs
    // milliseconds apart. The service refuses duplicates now too; this stops
    // the request being made at all.
    var startAttempted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (startAttempted) return@LaunchedEffect
        startAttempted = true

        agoraService.initEngine(context)
        agoraService.setupLocalVideo(localSurface)

        val stream = streamService.startStream(
            creatorID = creatorID,
            creatorUsername = creatorUsername,
            creatorDisplayName = creatorDisplayName,
            tier = tier,
            goLiveMessage = goLiveMessage,
        )
        if (stream == null) {
            startError = "Failed to start stream. Check your connection and try again."
            isStarting = false
            return@LaunchedEffect
        }

        agoraService.joinAsBroadcaster(channelName = stream.id)
        chatService.listen(communityID = creatorID, streamID = stream.id)
        queueService.listenToQueue(communityID = creatorID, streamID = stream.id)
        coinService.listenForHypes(communityID = creatorID, streamID = stream.id)
        isStarting = false
    }

    // Defense-in-depth: any dismissal path (back gesture, app kill, parent swap)
    // ends the stream so we don't leave a ghost.
    //
    // FIX 2026-08-04 — "any dismissal path" included one that isn't a dismissal.
    // `LaunchedEffect(Unit)` restarts when this screen leaves and re-enters
    // composition, which means onDispose fires on a screen that is coming straight
    // back. The iOS device log for a single go-live shows exactly that shape, and
    // there the teardown force-ended the creator ONE SECOND after joining Agora.
    // A real dismissal never comes back, so wait, then look.
    //
    // The old `scope.launch` compounded it: `rememberCoroutineScope()` is cancelled
    // the moment the screen leaves composition, so on a genuine dismissal the
    // teardown was racing its own cancellation and could leave the stream live
    // with nothing left to end it.
    DisposableEffect(Unit) {
        CreatorScreenPresence.enter(creatorID)
        onDispose {
            CreatorScreenPresence.leave(creatorID)
            CreatorScreenPresence.scope.launch {
                delay(700)
                if (CreatorScreenPresence.isOnScreen(creatorID)) {
                    if (BuildConfig.DEBUG) {
                        println("↩️ CREATOR SCREEN: $creatorID came back — skipping teardown")
                    }
                    return@launch
                }
                queueService.onStreamEnd()
                coinService.onStreamEnd()
                streamService.endStream(creatorID = creatorID)
                agoraService.leaveChannel()
                chatService.cleanup()
                StreamClipCache.purge()
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) chatListState.animateScrollToItem(messages.size - 1)
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Fullscreen camera preview
        AndroidView(
            factory = { localSurface },
            modifier = Modifier.fillMaxSize(),
        )

        // Starting placeholder
        if (isStarting) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Starting ${tier.displayName} stream…",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${tier.emoji} ${tier.durationSeconds / 60} min max",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // Start error
        startError?.let { error ->
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text("⚠️", fontSize = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.opacity(0.12f))
                            .clickable { onDismiss() }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Text("Close", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Overlays
        Column(modifier = Modifier.fillMaxSize().statusBarsPaddingFallback()) {
            CreatorTopOverlay(
                elapsedSeconds = elapsedSeconds,
                viewerCount = viewerCount,
                tier = tier,
            )

            Spacer(Modifier.weight(1f))

            // Chat list (bottom-left)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
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
                            CreatorChatRow(msg)
                        }
                    }
                }
                Spacer(Modifier.weight(0.2f))
                // Right side controls — flip cam, mute, queue. End lives in
                // the bottom input bar (matches iOS creatorInputBar).
                CreatorRightSideControls(
                    micMuted = micMuted,
                    queueCount = pendingComments.size,
                    onToggleMic = {
                        micMuted = !micMuted
                        agoraService.muteLocalAudio(micMuted)
                    },
                    onFlipCamera = { agoraService.switchCamera() },
                    onToggleQueue = { showingQueue = !showingQueue },
                )
            }

            // Bottom input — chat field + send (when text) + End pill button
            CreatorChatInput(
                draft = chatDraft,
                onChange = { chatDraft = it },
                onSend = {
                    val body = chatDraft
                    val streamID = activeStream?.id ?: return@CreatorChatInput
                    if (body.isBlank()) return@CreatorChatInput
                    chatDraft = ""
                    scope.launch {
                        chatService.send(
                            communityID = creatorID,
                            streamID = streamID,
                            authorID = creatorID,
                            authorUsername = creatorUsername,
                            authorDisplayName = creatorDisplayName,
                            authorLevel = 0,
                            isCreator = true,
                            body = body,
                        )
                    }
                },
                onEnd = { showingEndConfirm = true },
            )
        }

        // Queue carousel — floating strip at top, auto-closes on accept/replay
        if (showingQueue) {
            LiveStreamQueueCarousel(
                pending = pendingComments,
                displayed = displayedComments,
                onPreview = { comment ->
                    previewingComment = comment
                    showingQueue = false
                },
                onReplay = { comment ->
                    scope.launch {
                        queueService.replayUsedComment(comment)
                        showingQueue = false
                    }
                },
                onReject = { comment ->
                    val streamID = activeStream?.id ?: return@LiveStreamQueueCarousel
                    scope.launch {
                        queueService.rejectComment(
                            commentID = comment.id,
                            communityID = creatorID,
                            streamID = streamID,
                        )
                    }
                },
                onClose = { showingQueue = false },
                onTestPip = if (com.stitchsocial.club.BuildConfig.DEBUG) {
                    { injectDebugPip(scope, queueService, creatorID, activeStream?.id) }
                } else null,
            )
        }

        // Preview sheet — Accept goes through queueService.acceptComment.
        previewingComment?.let { comment ->
            VideoCommentPreviewSheet(
                comment = comment,
                onAccept = {
                    val streamID = activeStream?.id ?: return@VideoCommentPreviewSheet
                    scope.launch {
                        queueService.acceptComment(
                            commentID = comment.id,
                            communityID = creatorID,
                            streamID = streamID,
                        )
                    }
                    previewingComment = null
                },
                onReject = {
                    val streamID = activeStream?.id ?: return@VideoCommentPreviewSheet
                    scope.launch {
                        queueService.rejectComment(
                            commentID = comment.id,
                            communityID = creatorID,
                            streamID = streamID,
                        )
                    }
                    previewingComment = null
                },
                onClose = { previewingComment = null },
            )
        }

        // PiP overlay — the broadcasting clip. Floats on top of everything.
        activePiP?.let { pip ->
            LiveStreamCreatorPipOverlay(
                videoURL = pip.videoURL,
                authorUsername = pip.authorUsername,
                authorLevel = pip.authorLevel,
                playbackToken = pipPlaybackToken,
                onTapReplay = { scope.launch { queueService.replayPiP() } },
                onDismiss = { scope.launch { queueService.dismissPiP() } },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Hype storm alerts — same overlay viewers see, slides in when ANY
        // viewer sends a hype.
        HypeStormAlert(
            event = lastHypeAlert,
            modifier = Modifier.fillMaxSize(),
        )

        // End confirm
        if (showingEndConfirm) {
            EndStreamConfirmDialog(
                onCancel = { showingEndConfirm = false },
                onConfirm = {
                    showingEndConfirm = false
                    scope.launch {
                        streamService.endStream(creatorID = creatorID)
                        agoraService.leaveChannel()
                        onDismiss()
                    }
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top overlay — same glass capsule treatment as viewer/iOS revamp.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CreatorTopOverlay(
    elapsedSeconds: Int,
    viewerCount: Int,
    tier: StreamDurationTier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.background(Color.Red).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(modifier = Modifier.size(7.dp).background(Color.White, CircleShape))
                Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
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

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Visibility, null, tint = Color.White, modifier = Modifier.size(11.dp))
            Text(
                text = formatCount(viewerCount),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
            )
            Text("· ${tier.emoji}", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun CreatorChatRow(msg: StreamChatMessage) {
    // Creator rows get a crown + cyan-heavy treatment to stand out — matches
    // iOS creatorChatRow styling.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
            maxLines = 2,
        )
    }
}

/**
 * Right-side cluster — mirrors iOS `rightSideControls`. Simpler than the
 * viewer cluster: no labels under icons, just 3 glass circles stacked. End
 * stream lives in the bottom input bar, not here.
 */
@Composable
private fun CreatorRightSideControls(
    micMuted: Boolean,
    queueCount: Int,
    onToggleMic: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleQueue: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.End,
        modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
    ) {
        glassCircleButton(
            icon = Icons.Default.Cameraswitch,
            onClick = onFlipCamera,
        )
        glassCircleButton(
            icon = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
            tint = if (micMuted) Color.Red else Color.White,
            background = if (micMuted) Color.Red.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.4f),
            onClick = onToggleMic,
        )
        // Queue with pink count badge
        Box {
            glassCircleButton(
                icon = Icons.Default.VideoLibrary,
                onClick = onToggleQueue,
            )
            if (queueCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF4F8B)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (queueCount > 99) "99+" else queueCount.toString(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun glassCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = Color.White,
    background: Color = Color.Black.copy(alpha = 0.4f),
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun CreatorChatInput(
    draft: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onEnd: () -> Unit,
) {
    val hasText = draft.trim().isNotEmpty()

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Chat pill — same dark-glass styling as iOS creatorInputBar
        BasicTextField(
            value = draft,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Cyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(19.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(19.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            "Reply to chat…",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )

        // Cyan up-arrow circle — only visible when there's text (matches iOS)
        if (hasText) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Cyan)
                    .clickable { onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ArrowUpward, "Send", tint = Color.Black, modifier = Modifier.size(16.dp))
            }
        }

        // End stream pill — red, X icon + label
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Red.copy(alpha = 0.85f))
                .clickable { onEnd() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
            Text("End", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EndStreamConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(enabled = false) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF161929))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("End Stream?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Viewers will be disconnected immediately.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.opacity(0.12f))
                        .clickable { onCancel() }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text("Keep Streaming", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Red)
                        .clickable { onConfirm() }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text("End", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }
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

// Compose doesn't expose `Color.opacity()` natively — define an extension
// so the code reads identically on both platforms.
private fun Color.opacity(amount: Float): Color = this.copy(alpha = amount)

/// DEBUG-only — drops a fake clip into the displayed bucket + activates the
/// PiP using a public Big Buck Bunny URL. Lets you verify the carousel +
/// PiP layout without a second device submitting a real video.
private fun injectDebugPip(
    scope: kotlinx.coroutines.CoroutineScope,
    queueService: StreamQueueService,
    creatorID: String,
    streamID: String?,
) {
    val sid = streamID ?: return
    val fake = VideoComment(
        id = java.util.UUID.randomUUID().toString(),
        streamID = sid,
        communityID = creatorID,
        authorID = "debug-user",
        authorUsername = "tester",
        authorDisplayName = "PiP Tester",
        authorLevel = 99,
        videoURL = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4",
        thumbnailURL = null,
        durationSeconds = 10,
        caption = "Debug clip — verify PiP layout",
        isPriority = false,
        priorityCoinsCost = 0,
        status = VideoCommentStatus.DISPLAYED,
        submittedAt = com.google.firebase.Timestamp.now(),
        reviewedAt = null,
    )
    scope.launch {
        queueService.replayUsedComment(fake)
    }
}

@Composable
private fun Modifier.statusBarsPaddingFallback(): Modifier {
    return this.then(
        Modifier.windowInsetsPadding(
            androidx.compose.foundation.layout.WindowInsets.statusBars
        )
    )
}
