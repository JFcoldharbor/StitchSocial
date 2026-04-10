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

    // Update player when segment changes — in-place, no teardown
    LaunchedEffect(currentIndex, segments.size) {
        val seg = segments.getOrNull(currentIndex) ?: return@LaunchedEffect
        if (seg.videoURL.isEmpty()) return@LaunchedEffect
        exoPlayer.pause()
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(seg.videoURL)))
        exoPlayer.prepare()
        exoPlayer.seekTo(0)
        exoPlayer.play()
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

    // Overlay
    var showOverlay by remember { mutableStateOf(true) }
    var showThreadView by remember { mutableStateOf(false) }
    var threadTargetVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var overlayHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun showOverlayBriefly() {
        showOverlay = true
        overlayHideJob?.cancel()
        overlayHideJob = scope.launch { delay(5_000); showOverlay = false }
    }

    LaunchedEffect(collection.id) {
        viewModel.loadCollection(collection, userID, startingIndex.takeIf { it > 0 })
        viewModel.recordView()
    }
    LaunchedEffect(Unit) { showOverlayBriefly() }
    LaunchedEffect(currentIndex) { showOverlayBriefly() }

    // ExoPlayer listener — auto-advance on segment end
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (currentIndex < segmentCount - 1) {
                        viewModel.advanceToNext()
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
                                            currentOffset < -threshold || velocity < -300f -> {
                                                if (currentIndex < segmentCount - 1)
                                                    viewModel.advanceToNext()
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
                ) {
                    // ── Single video layer — translates with drag ─────
                    // Exactly one PlayerView alive at a time — no bleed
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
                    }

                    // ── Overlay — hidden while dragging ───────────────
                    currentSegment?.let { seg ->
                        AnimatedVisibility(
                            visible = showOverlay && !isDragging,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            ContextualVideoOverlay(
                                video = seg,
                                overlayContext = OverlayContext.HOME_FEED,
                                currentUserID = userID.takeIf { it.isNotEmpty() },
                                currentUserTier = currentUserTier,
                                engagementViewModel = engagementViewModel,
                                iconManager = iconManager,
                                followManager = followManager,
                                isVisible = showOverlay && !isDragging,
                                onAction = { action ->
                                    when (action) {
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

                // ── Top bar ───────────────────────────────────────────
                CollectionTopBar(
                    collection = collection,
                    segmentCount = segmentCount,
                    currentIndex = currentIndex,
                    onClose = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .zIndex(10f)
                )

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
// MARK: - Collection Top Bar
// ─────────────────────────────────────────────

@Composable
fun CollectionTopBar(
    collection: VideoCollection,
    segmentCount: Int,
    currentIndex: Int,
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
                        repeat(segmentCount) { idx ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (idx <= currentIndex) Color.White
                                        else Color.White.copy(alpha = 0.3f)
                                    )
                            )
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