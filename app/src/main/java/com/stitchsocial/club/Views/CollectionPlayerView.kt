/*
 * CollectionPlayerView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 6: Views — Collection Segment Player
 *
 * ARCHITECTURE: Single ExoPlayer instance, updated in-place when segment changes.
 * Mirrors DiscoveryView fullscreen pattern exactly:
 *   - Animatable(0f) offsetX on outer Box with detectHorizontalDragGestures
 *   - VelocityTracker for fling, spring snap-back
 *   - key(segment.id) replaced with in-place setMediaItem() → NO player teardown
 *   - translationX on the video Box, overlay separate above
 *
 * WHY NOT HorizontalPager:
 *   - beyondViewportPageCount=1 pre-renders adjacent PlayerViews with RESIZE_MODE_ZOOM
 *   - RESIZE_MODE_ZOOM expands video surface beyond view bounds → bleed on adjacent page
 *   - DiscoveryView avoids this by never having two PlayerViews alive simultaneously
 *
 * WHY NOT key(segment.id):
 *   - key() destroys and recreates ExoPlayer → 2s buffering gap on every swipe
 *   - Instead: update setMediaItem() on the existing player → seamless transition
 *
 * SINGLE PLAYER MODEL:
 *   - One ExoPlayer created once, lives for the duration of CollectionPlayerView
 *   - On segment change: pause → setMediaItem → prepare → seek(0) → play
 *   - REPEAT_MODE_OFF fires STATE_ENDED → auto-advance to next segment
 */

package com.stitchsocial.club.views

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.SpecialUsersConfig
import com.stitchsocial.club.ads.CollectionAdBreakController
import com.stitchsocial.club.ads.CollectionAdBreakOverlay
import com.google.android.gms.ads.nativead.NativeAd
import com.stitchsocial.club.services.CollectionService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.viewmodels.CollectionPlayerViewModel
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager
import com.stitchsocial.club.FollowManager
import com.stitchsocial.club.ui.screens.ThreadView

// ─────────────────────────────────────────────
// MARK: - CollectionPlayerView
// ─────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun CollectionPlayerView(
    collection: VideoCollection,
    startingIndex: Int = 0,
    userID: String = "",
    currentUserTier: UserTier = UserTier.ROOKIE,
    videoService: VideoServiceImpl? = null,
    authService: AuthService? = null,
    engagementViewModel: EngagementViewModel? = null,
    iconManager: FloatingIconManager? = null,
    followManager: FollowManager? = null,
    /**
     * Invoked when the user taps the Reply button on a segment overlay.
     * Caller should launch the recording modal with [segment] as the parent
     * (mirrors the StitchRecording handler used for HomeFeed/Profile videos).
     */
    onReplyToSegment: ((segment: CoreVideoMetadata) -> Unit)? = null,
    onDismiss: () -> Unit,
    viewModel: CollectionPlayerViewModel = viewModel(
        key = collection.id,
        factory = CollectionPlayerViewModelFactory(CollectionService())
    )
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

    val segments by viewModel.segments.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val paywallHit by viewModel.paywallHit.collectAsState()
    val error by viewModel.error.collectAsState()
    val segmentCount = segments.size
    val currentSegment = segments.getOrNull(currentIndex)

    // ── Single ExoPlayer for the entire session ───────────────────
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }
    }

    /** Where the next part starts. Usually 0; a skip that crosses a part
     *  boundary leaves the matching offset here (see skip()). */
    var pendingStartMs by remember { mutableLongStateOf(0L) }

    // ── Part-to-part ad break (see ads/CollectionAdBreak.kt) ──────
    val adBreaks = remember(collection.id) { CollectionAdBreakController() }
    /** The break on screen and the part it precedes. That part only becomes
     *  current when the break ends. */
    var adBreak by remember { mutableStateOf<Pair<NativeAd, Int>?>(null) }
    DisposableEffect(adBreaks) { onDispose { adBreaks.release() } }

    /** Every forward move goes through here: a break point with an ad in hand
     *  shows the ad first; otherwise the move just happens. */
    fun goNext() {
        val next = currentIndex + 1
        if (next >= segmentCount || adBreak != null) return
        // The paywall comes first; an ad in front of a locked part plays to nothing.
        if (!viewModel.isLocked(next)) {
            adBreaks.take(next)?.let { ad ->
                exoPlayer.pause()
                adBreak = ad to next
                return
            }
        }
        viewModel.advanceToNext()
    }

    // Update player when segment changes — in-place, no teardown
    LaunchedEffect(currentIndex, segments.size) {
        val seg = segments.getOrNull(currentIndex) ?: return@LaunchedEffect
        val playURL = seg.playbackURL
        if (playURL.isEmpty()) return@LaunchedEffect
        exoPlayer.pause()
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(playURL)))
        exoPlayer.prepare()
        exoPlayer.seekTo(pendingStartMs)
        pendingStartMs = 0L
        exoPlayer.play()
    }

    // How far through the current part, for the progress bars. Held as State
    // and read only inside CollectionTopBar, so its updates recompose the bar
    // and not this whole screen.
    val partProgress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(exoPlayer) {
        while (true) {
            val dur = exoPlayer.duration
            partProgress.floatValue =
                if (dur > 0) (exoPlayer.currentPosition.toFloat() / dur).coerceIn(0f, 1f) else 0f
            delay(200)
        }
    }

    // Release on dispose
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // ── Swipe state — exact DiscoveryView pattern ─────────────────
    val offsetX = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Double-tap skip indicator (iOS parity)
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(seekFeedback?.tick) {
        if (seekFeedback == null) return@LaunchedEffect
        delay(700)
        seekFeedback = null
    }

    /**
     * Skip 10s of the EPISODE and say what happened. An episode is one
     * recording cut into parts, so across a boundary the skip carries on into
     * the neighbouring part at the matching point instead of restarting it.
     * Mirrors iOS CollectionPlayerView.
     */
    fun skip(back: Boolean) {
        val pos = exoPlayer.currentPosition
        val dur = exoPlayer.duration
        if (dur <= 0) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val target = pos + if (back) -10_000L else 10_000L
        var seconds = 0
        val label = when {
            target >= dur - 500 -> {
                if (currentIndex < segmentCount - 1) {
                    pendingStartMs = (target - dur).coerceAtLeast(0L)
                    goNext(); "Part ${currentIndex + 2}"
                } else "End"
            }
            target < 0 -> {
                if (currentIndex == 0) {
                    exoPlayer.seekTo(0); "Start"
                } else {
                    // `target` seconds before the end of the previous part — but
                    // at least a second before it, or it would end at once and
                    // bounce straight back here.
                    val prevMs = ((segments.getOrNull(currentIndex - 1)?.duration ?: 0.0) * 1000).toLong()
                    pendingStartMs = if (prevMs > 1_000) (prevMs + target).coerceIn(0L, prevMs - 1_000) else 0L
                    viewModel.advanceToPrevious(); "Part $currentIndex"
                }
            }
            else -> {
                exoPlayer.seekTo(target)
                // Rapid skips the same way add up into one indicator: 10s, 20s.
                val carried = seekFeedback?.takeIf { it.back == back }?.seconds ?: 0
                seconds = carried + 10
                "${seconds}s"
            }
        }
        seekFeedback = SeekFeedback(back = back, label = label, seconds = seconds,
                                    tick = (seekFeedback?.tick ?: 0) + 1)
    }

    // Overlay state
    var showThreadView by remember { mutableStateOf(false) }
    var threadTargetVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }

    LaunchedEffect(collection.id) {
        viewModel.loadCollection(collection, userID, startingIndex.takeIf { it > 0 })
        viewModel.recordView()

        // Resolve the paywall inputs. Both default to the LOCKED side, so a slow
        // or failed lookup withholds paid content rather than giving it away.
        viewModel.setCurrentUserEmail(
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email
        )
        // A free show frees every episode in it.
        collection.showId?.takeIf { it.isNotBlank() }?.let { showId ->
            if (runCatching { com.stitchsocial.club.services.ShowService.shared.getShow(showId)?.isFree }
                    .getOrNull() == true) viewModel.setShowFree(true)
        }
        viewModel.setSubscribed(
            runCatching {
                com.stitchsocial.club.services.SubscriptionService.shared
                    .isSubscribed(userID, collection.creatorID)
            }.getOrDefault(false)
        )

        adBreaks.configure(
            collection = collection,
            viewerID = userID,
            viewerTier = currentUserTier,
            viewerIsFounder = SpecialUsersConfig.detectSpecialUser(
                com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email.orEmpty()
            )?.isFounder == true
        )
        adBreaks.prefetch(context, viewModel.currentIndex.value, viewModel.segments.value.size)
    }
    LaunchedEffect(currentIndex, segmentCount) {
        adBreaks.prefetch(context, currentIndex, segmentCount)
    }
    LaunchedEffect(currentIndex) {
        // Pull fresh hype/cool/view/reply for the active segment so the overlay
        // doesn't sit on whatever was cached at collection load (iOS parity).
        viewModel.refreshSegmentCounts(currentIndex)
    }

    // ExoPlayer listener — auto-advance on segment end.
    //
    // The listener is created once, so it must read the CURRENT index and
    // count. It used to capture them at first composition — before the parts
    // had loaded, when the count was 0 — so `currentIndex < segmentCount - 1`
    // was false for ever and no part ever advanced on its own.
    val liveIndex by rememberUpdatedState(currentIndex)
    val liveCount by rememberUpdatedState(segmentCount)
    val liveGoNext by rememberUpdatedState(::goNext)
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (liveIndex < liveCount - 1) {
                        liveGoNext()
                    } else if (liveCount > 0) {
                        viewModel.markCollectionCompleted()
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // ── Root ──────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        when {
            isLoading -> CircularProgressIndicator(
                color = Color.White, strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center).size(40.dp)
            )

            error != null -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Warning, null, tint = Color.Red, modifier = Modifier.size(48.dp))
                Text("Failed to load", color = Color.White, fontWeight = FontWeight.Bold)
                Text(error!!, color = Color.Gray, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewModel.loadCollection(collection, userID) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)
                    ) { Text("Retry", color = Color.Black) }
                    OutlinedButton(onClick = onDismiss) { Text("Close", color = Color.White) }
                }
            }

            segments.isEmpty() -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(Icons.Default.VideocamOff, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                Text("No segments available", color = Color.White, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onDismiss) { Text("Close", color = Color.White) }
            }

            else -> {
                // ── Swipe container — DiscoveryView pattern ───────────
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(segmentCount, currentIndex) {
                            if (segmentCount <= 1) return@pointerInput
                            val velocityTracker = VelocityTracker()

                            detectHorizontalDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    exoPlayer.pause()
                                    velocityTracker.resetTracking()
                                },
                                onDragEnd = {
                                    isDragging = false
                                    val velocity = velocityTracker.calculateVelocity().x
                                    val currentOffset = offsetX.value
                                    val threshold = screenWidthPx * 0.2f

                                    scope.launch {
                                        when {
                                            // The break owns the screen until it ends.
                                            adBreak != null -> {}
                                            currentOffset < -threshold || velocity < -300f -> {
                                                if (currentIndex < segmentCount - 1)
                                                    goNext()
                                            }
                                            currentOffset > threshold || velocity > 300f -> {
                                                if (currentIndex > 0)
                                                    viewModel.advanceToPrevious()
                                            }
                                        }
                                        offsetX.animateTo(0f, spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessLow
                                        ))
                                    }
                                },
                                onDragCancel = {
                                    isDragging = false
                                    scope.launch {
                                        offsetX.animateTo(0f, spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessLow
                                        ))
                                    }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    val resistance = when {
                                        currentIndex == 0 && offsetX.value + dragAmount > 0 -> 0.4f
                                        currentIndex == segmentCount - 1 && offsetX.value + dragAmount < 0 -> 0.4f
                                        else -> 1f
                                    }
                                    scope.launch {
                                        offsetX.snapTo(offsetX.value + dragAmount * resistance)
                                    }
                                }
                            )
                        }
                        // Double-tap an edge to skip 10s: left 30% back, right 30%
                        // forward. Taps the overlay's buttons consume never get
                        // here, so hype/cool/reply are unaffected.
                        .pointerInput(segmentCount, currentIndex) {
                            detectTapGestures(onDoubleTap = { offset ->
                                if (adBreak != null) return@detectTapGestures
                                when {
                                    offset.x < size.width * 0.30f -> skip(back = true)
                                    offset.x > size.width * 0.70f -> skip(back = false)
                                }
                            })
                        }
                ) {
                    // ── Video + overlay — same Box, overlay on top (HomeFeed pattern)
                    // Exactly one PlayerView alive at a time — no bleed.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = offsetX.value }
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    setKeepContentOnPlayerReset(true)
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    layoutParams = android.view.ViewGroup.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            update = { it.player = exoPlayer },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Overlay sits as a sibling above the player — same shape
                        // HomeFeedView uses. No AnimatedVisibility / no auto-hide; the
                        // earlier wrapper was breaking pointer routing so taps on the
                        // hype/cool/reply buttons never landed.
                        currentSegment?.let { seg ->
                            ContextualVideoOverlay(
                                video = seg,
                                overlayContext = OverlayContext.COLLECTION,
                                currentUserID = userID.takeIf { it.isNotEmpty() },
                                currentUserTier = currentUserTier,
                                engagementViewModel = engagementViewModel,
                                iconManager = iconManager,
                                followManager = followManager,
                                isVisible = !isDragging,
                                onAction = { action ->
                                    when (action) {
                                        // Reply button on collection overlay is wired
                                        // to StitchRecording — open recording modal
                                        // via the caller's handler.
                                        is OverlayAction.StitchRecording -> {
                                            onReplyToSegment?.invoke(seg)
                                        }
                                        // Reply-count tap (in the bottom section) still
                                        // goes to ThreadView so users can browse existing
                                        // replies without opening the camera.
                                        is OverlayAction.NavigateToThread -> {
                                            threadTargetVideo = seg
                                            showThreadView = true
                                        }
                                        else -> {}
                                    }
                                }
                            )
                        }
                    }
                }

                // ── Skip indicator ─────────────────────────────────────
                SeekFeedbackOverlay(
                    feedback = seekFeedback,
                    modifier = Modifier.fillMaxSize().zIndex(5f)
                )

                // ── Top bar ───────────────────────────────────────────
                CollectionTopBar(
                    collection = collection,
                    segmentCount = segmentCount,
                    currentIndex = currentIndex,
                    partProgress = partProgress,
                    onClose = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .zIndex(10f)
                )

                // ── Ad break — over everything but the thread sheet ───
                adBreak?.let { (ad, next) ->
                    CollectionAdBreakOverlay(
                        ad = ad,
                        nextPartNumber = next + 1,
                        onFinish = {
                            adBreak = null
                            viewModel.advanceToNext()
                        },
                        modifier = Modifier.fillMaxSize().zIndex(50f)
                    )
                }

                // ── Paywall ───────────────────────────────────────────
                // Playback stopping with no explanation reads as a broken
                // player, so say what happened and what unlocks it.
                if (paywallHit) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissPaywall() },
                        title = { Text("Subscriber content") },
                        text = {
                            Text(
                                "You've watched the free preview of \"${collection.title}\". " +
                                "Subscribe to ${collection.creatorName} to keep watching."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.dismissPaywall() }) { Text("Got it") }
                        }
                    )
                }

                // ── ThreadDetailSheet ─────────────────────────────────
                if (showThreadView) {
                    threadTargetVideo?.let { seg ->
                        videoService?.let { vs ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black)
                                    .zIndex(100f)
                            ) {
                                ThreadView(
                                    threadID = seg.threadID ?: seg.id,
                                    videoService = vs,
                                    targetVideoID = seg.id,
                                    currentUserID = userID.takeIf { it.isNotEmpty() },
                                    onTabBarVisibilityChange = {},
                                    onDismiss = {
                                        showThreadView = false
                                        threadTargetVideo = null
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Skip indicator
// ─────────────────────────────────────────────

/** What the double-tap skip indicator shows. [tick] changes on every skip. */
data class SeekFeedback(val back: Boolean, val label: String, val seconds: Int, val tick: Int)

/**
 * A soft half-circle on the tapped side with the direction and what happened
 * ("10s", "Start", "Part 6"). Purely visual — draws nothing that takes touches.
 */
@Composable
fun SeekFeedbackOverlay(feedback: SeekFeedback?, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(feedback) }
    if (feedback != null) last = feedback
    val shown = last ?: return

    BoxWithConstraints(modifier = modifier) {
        val w = maxWidth
        AnimatedVisibility(
            visible = feedback != null,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(if (shown.back) Alignment.CenterStart else Alignment.CenterEnd)
                        .width(w * 0.42f)
                        .fillMaxHeight(0.9f)
                        .clip(
                            if (shown.back) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                            else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                        )
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                           verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            if (shown.back) Icons.Default.FastRewind else Icons.Default.FastForward,
                            contentDescription = null, tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(shown.label, color = Color.White, fontSize = 13.sp,
                             fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Collection Top Bar
// ─────────────────────────────────────────────

@Composable
fun CollectionTopBar(
    collection: VideoCollection,
    segmentCount: Int,
    currentIndex: Int,
    /** 0–1 through the current part; read here so only the bar recomposes. */
    partProgress: State<Float>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }

            Spacer(Modifier.weight(1f))

            if (segmentCount > 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.widthIn(max = 200.dp)
                    ) {
                        // Full before the current part, filling through it, dim after.
                        val fraction by partProgress
                        repeat(segmentCount) { idx ->
                            val fill = when {
                                idx < currentIndex -> 1f
                                idx > currentIndex -> 0f
                                else -> fraction
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.3f))
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fill)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        collection.title,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Part ${currentIndex + 1} of $segmentCount",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(36.dp))
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - ViewModel Factory
// ─────────────────────────────────────────────

class CollectionPlayerViewModelFactory(
    private val collectionService: CollectionService
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return CollectionPlayerViewModel(collectionService) as T
    }
}