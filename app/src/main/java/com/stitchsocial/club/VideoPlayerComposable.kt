/*
 * VideoPlayerComposable.kt - WITH PAUSE AND RESUME BROADCAST RECEIVERS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - ExoPlayer Compose Video Player
 * Dependencies: ExoPlayer, Foundation types, VideoManager
 * Features: TikTok-style autoplay, seamless looping, gesture controls, pause/resume broadcast listeners
 *
 * ✅ ADDED: Pause broadcast receiver for camera integration
 * ✅ ADDED: Resume broadcast receiver - videos resume after camera modal closes
 * ✅ REMOVED: Loading/buffering indicator for smoother experience
 * ✅ FIXED: iPhone video stretching with proper resize mode
 * ✅ FIXED: Proper background video pause management
 * ✅ ADDED: Recording state integration for automatic video cleanup
 */

package com.stitchsocial.club.views

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.stitchsocial.club.foundation.InteractionType
import com.stitchsocial.club.VideoManager

/**
 * ExoPlayer-powered video player for TikTok-style feed
 * ✅ Listens for pause broadcasts from stitch button
 * ✅ SIMPLIFIED: Removed isRecordingActive overkill - uses broadcast only
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerComposable(
    video: Any, // Generic type to accept any video metadata
    isActive: Boolean,
    modifier: Modifier = Modifier,
    muted: Boolean = false,   // grid previews play silent
    managed: Boolean = true,  // false = don't register with VideoManager (allows concurrent players)
    onEngagement: ((InteractionType) -> Unit)? = null,
    onVideoClick: (() -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,  // Callback for swipe up to exit
    /**
     * Fired the first time this player actually starts producing frames.
     *
     * Callers that cover the surface with a poster during the prepare-and-buffer
     * gap need to know when to drop it. A timer can't: too short and you get the
     * black flash back, too long and a still frame sits on top of a playing
     * video. Optional, so every existing call site is unaffected.
     */
    onPlaybackStarted: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var showPlayButton by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }

    // Extract video properties. Prefer the model's computed playbackURL
    // (live HLS -> faststart MP4 -> legacy videoURL) — that's a computed getter
    // reflection over declaredFields can't see, so cast to the model first and
    // only fall back to reflection for non-CoreVideoMetadata callers.
    val videoModel = video as? com.stitchsocial.club.foundation.CoreVideoMetadata

    // A CACHED MP4 BEATS A LIVE HLS STREAM.
    //
    // playbackURL prefers HLS, and prefetching an HLS URL only ever caches the
    // .m3u8 manifest — the segments live behind it, so the disk cache has been
    // doing nothing for the surfaces that matter. When the faststart MP4 for
    // this video is already on disk, play THAT: it starts from local bytes with
    // no network round trip, which is the buffer gap the poster was covering.
    //
    // HLS still wins when nothing is cached — adaptive bitrate is the better
    // choice over a cold network.
    val cachedMp4 = videoModel?.mp4URL?.takeIf {
        it.isNotEmpty() && com.stitchsocial.club.services.VideoDiskCache.isCached(it)
    }
    val remoteURL = cachedMp4
        ?: videoModel?.playbackURL
        ?: getVideoProperty(video, "videoURL") ?: ""
    val videoTitle = getVideoProperty(video, "title") ?: "Unknown Video"
    val videoId = getVideoProperty(video, "id") ?: "unknown_id"

    // Optimistic playback: if this doc was just uploaded and its local file is
    // still cached, play it instantly, then swap to HLS once the readiness poller
    // reports the master is live (iOS parity — project_stitch_cdn_integration).
    val localPath = remember(videoId) { com.stitchsocial.club.services.LocalVideoCache.localPath(videoId) }
    var currentURL by remember(videoId) { mutableStateOf(localPath ?: remoteURL) }
    val hlsSwapURL = videoModel?.hlsURL

    // Create ExoPlayer instance
    val exoPlayer = remember(currentURL) {
        // Use CacheDataSource if VideoDiskCache is initialised — falls back to default
        val playerBuilder = try {
            val cacheFactory = com.stitchsocial.club.services.VideoDiskCache.buildCacheDataSourceFactory()
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory)
                )
        } catch (_: Exception) {
            ExoPlayer.Builder(context)
        }

        // Short-form tuning — the single biggest lever against "buffers too much".
        // The feed player had NO LoadControl, so it used ExoPlayer defaults: wait
        // ~2.5s of buffer before playback starts and keep up to 50s buffered. For
        // TikTok-style vertical clips that reads as constant buffering. Start after
        // ~0.3s buffered and cap the buffer so we don't over-fetch. (GlobalVideoPlayer
        // already tunes this; the feed player never did.)
        val shortFormLoadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2_000,
                /* maxBufferMs = */ 20_000,
                /* bufferForPlaybackMs = */ 300,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        playerBuilder.setLoadControl(shortFormLoadControl).build().apply {
            // Configure for seamless looping
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false

            try {
                if (currentURL.isNotEmpty()) {
                    val mediaItem = MediaItem.fromUri(currentURL)
                    setMediaItem(mediaItem)
                    // Pick up where this video was left, if anywhere.
                    val resumeAt = com.stitchsocial.club.services.VideoPositionMemory.get(videoId)
                    if (resumeAt > 0) seekTo(resumeAt)
                    prepare()
                    Log.d("VIDEO_PLAYER", "📺 Loading video $videoId from $currentURL")
                } else {
                    isError = true
                    Log.w("VIDEO_PLAYER", "⚠️ No video URL for $videoId")
                }
            } catch (e: Exception) {
                Log.e("VIDEO_PLAYER", "❌ Error loading video $videoId - ${e.message}")
                isError = true
            }

            // Add listener for play state
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    if (playing) onPlaybackStarted?.invoke()
                    isPlaying = playing
                    showPlayButton = !playing && !isError && !isActive
                    Log.d("VIDEO_PLAYER", "▶️ $videoId playing = $playing, isActive = $isActive")
                }

                override fun onPlayerError(error: PlaybackException) {
                    isError = true
                    showPlayButton = false
                    Log.e("VIDEO_PLAYER", "❌ Playback error for $videoId - ${error.message}")
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            Log.d("VIDEO_PLAYER", "✅ $videoId ready to play")
                            isError = false
                        }
                        Player.STATE_BUFFERING -> {
                            Log.d("VIDEO_PLAYER", "⏳ $videoId buffering")
                        }
                        Player.STATE_ENDED -> {
                            Log.d("VIDEO_PLAYER", "🔄 $videoId ended, looping")
                        }
                    }
                }
            })
        }
    }

    // ✅ Track if this video was paused by broadcast (not by normal navigation)
    var wasPausedByBroadcast by remember { mutableStateOf(false) }

    // ✅ Listen for pause AND resume broadcasts from camera/recording modals
    DisposableEffect(videoId) {
        val videoControlReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    "com.stitchsocial.club.PAUSE_ALL_VIDEOS" -> {
                        Log.i("VIDEO_PLAYER", "📡 PAUSE BROADCAST RECEIVED for $videoId")
                        wasPausedByBroadcast = true
                        exoPlayer.pause()
                        exoPlayer.playWhenReady = false
                        isPlaying = false
                        showPlayButton = false  // Don't show play button - will auto-resume
                    }
                    "com.stitchsocial.club.RESUME_ALL_VIDEOS" -> {
                        Log.i("VIDEO_PLAYER", "📡 RESUME BROADCAST RECEIVED for $videoId")
                        // Only resume if we were paused by broadcast AND this is the active video
                        if (wasPausedByBroadcast && isActive && !isError) {
                            Log.i("VIDEO_PLAYER", "📡 RESUMING $videoId after broadcast")
                            wasPausedByBroadcast = false
                            exoPlayer.playWhenReady = true
                            exoPlayer.play()
                            isPlaying = true
                            showPlayButton = false
                        } else {
                            Log.d("VIDEO_PLAYER", "📡 RESUME SKIPPED for $videoId (wasPausedByBroadcast=$wasPausedByBroadcast, isActive=$isActive, isError=$isError)")
                            wasPausedByBroadcast = false  // Reset either way
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.stitchsocial.club.PAUSE_ALL_VIDEOS")
            addAction("com.stitchsocial.club.RESUME_ALL_VIDEOS")
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(videoControlReceiver, filter)
        Log.d("VIDEO_PLAYER", "📻 Registered video control receiver for $videoId")

        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(videoControlReceiver)
            Log.d("VIDEO_PLAYER", "📻 Unregistered video control receiver for $videoId")
        }
    }

    // Swap optimistic local file -> HLS when the readiness poller publishes this
    // doc. Only runs when we actually started from a local file AND the model
    // carries an HLS master to upgrade to; otherwise this is a no-op.
    LaunchedEffect(videoId) {
        if (localPath != null && !hlsSwapURL.isNullOrEmpty()) {
            val swapURL = hlsSwapURL
            com.stitchsocial.club.services.HlsReadinessPoller.published.collect { publishedId ->
                if (publishedId == videoId) {
                    Log.i("VIDEO_PLAYER", "🔀 HLS live for $videoId — swapping optimistic local -> HLS")
                    currentURL = swapURL
                    com.stitchsocial.club.services.LocalVideoCache.clear(videoId)
                }
            }
        }
    }

    // Video Manager control - play when active
    LaunchedEffect(isActive) {
        Log.d("VIDEO_PLAYER", "🎯 Video $videoId isActive changed to: $isActive")
        if (isActive && !isError) {
            if (managed) VideoManager.setActivePlayer(exoPlayer, videoId)
            exoPlayer.volume = if (muted) 0f else 1f
            exoPlayer.play()
            Log.i("VIDEO_PLAYER", "▶️ PLAYING $videoId (now active via VideoManager)")
        } else {
            exoPlayer.pause()
            exoPlayer.playWhenReady = false
            Log.i("VIDEO_PLAYER", "⏸️ PAUSED $videoId (inactive)")
        }
    }

    // Clean up on disposal
    DisposableEffect(Unit) {
        onDispose {
            Log.d("VIDEO_PLAYER", "🗑️ Disposing player for $videoId")
            // Remember where this video was BEFORE the player goes away, so
            // coming back resumes instead of restarting (iOS parity). Read it
            // before stop(), which resets the position to zero.
            runCatching {
                com.stitchsocial.club.services.VideoPositionMemory.save(
                    videoId, exoPlayer.currentPosition, exoPlayer.duration
                )
            }
            VideoManager.clearPlayer(videoId)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                // Add swipe up gesture if callback provided
                if (onSwipeUp != null) {
                    Modifier.pointerInput(Unit) {
                        val velocityTracker = VelocityTracker()

                        detectVerticalDragGestures(
                            onDragStart = {
                                velocityTracker.resetTracking()
                            },
                            onDragEnd = {
                                val velocity = velocityTracker.calculateVelocity().y

                                // Swipe up to exit (negative velocity)
                                if (velocity < -500f) {
                                    onSwipeUp()
                                }
                            },
                            onDragCancel = {},
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                velocityTracker.addPosition(
                                    change.uptimeMillis,
                                    change.position
                                )
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        when {
            isError -> {
                // Error state - show placeholder
                ErrorVideoPlaceholder(
                    videoTitle = videoTitle,
                    videoId = videoId,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                // ExoPlayer View - NO BUFFERING INDICATOR
                AndroidView(
                    factory = { ctx ->
                        // SurfaceView (PlayerView's default), NOT TextureView.
                        //
                        // TextureView was introduced when the horizontal swipe was
                        // a Pager, because a SurfaceView won't clip to a scrolling
                        // page. That pager is gone — HomeFeed's model shows one
                        // player and thumbnails either side — so the reason is
                        // gone with it, and TextureView's cost isn't free:
                        // it composites every frame through the GPU like an
                        // ordinary view, which is a real source of playback jank.
                        //
                        // A SurfaceView still won't let anything draw BEHIND it,
                        // but the poster is drawn ON TOP, which works either way.
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false

                            // CRITICAL: Set layout params to MATCH_PARENT for fullscreen
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            // ZOOM mode fills screen by cropping, not stretching (no distortion)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            setKeepContentOnPlayerReset(true)

                            // Black background to prevent any bleed-through
                            setBackgroundColor(android.graphics.Color.BLACK)
                            setShutterBackgroundColor(android.graphics.Color.BLACK)

                            Log.d("VIDEO_PLAYER", "PlayerView configured: $videoId")
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { playerView ->
                        playerView.player = exoPlayer
                    }
                )

                // NOTE: Gesture detection removed - handled by ContextualVideoOverlay
                // This allows HorizontalPager swipes to work properly

                // Play Button Overlay (only in fullscreen)
                if (showPlayButton) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Extract property from video object safely using reflection
 */
private fun getVideoProperty(video: Any, propertyName: String): String? {
    return try {
        val field = video::class.java.declaredFields.find { it.name == propertyName }
        field?.let {
            it.isAccessible = true
            it.get(video)?.toString()
        }
    } catch (e: Exception) {
        Log.w("VIDEO_PLAYER", "⚠️ Could not extract $propertyName: ${e.message}")
        null
    }
}

/**
 * Error placeholder when video fails to load
 */
@Composable
private fun ErrorVideoPlaceholder(
    videoTitle: String,
    videoId: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "❌ Video Error",
                color = Color.Red,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = videoTitle,
                color = Color.White,
                fontSize = 14.sp
            )
            Text(
                text = "ID: $videoId",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}