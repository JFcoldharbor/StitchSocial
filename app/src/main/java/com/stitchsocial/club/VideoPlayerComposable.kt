/*
 * VideoPlayerComposable.kt - WITH PAUSE BROADCAST RECEIVER
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - ExoPlayer Compose Video Player
 * Dependencies: ExoPlayer, Foundation types, VideoManager
 * Features: TikTok-style autoplay, seamless looping, gesture controls, pause broadcast listener
 *
 * ✅ ADDED: Pause broadcast receiver for camera integration
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.stitchsocial.club.foundation.InteractionType
import com.stitchsocial.club.VideoManager

/**
 * ExoPlayer-powered video player for TikTok-style feed
 * ✅ Listens for pause broadcasts from stitch button
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerComposable(
    video: Any, // Generic type to accept any video metadata
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onEngagement: ((InteractionType) -> Unit)? = null,
    onVideoClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var showPlayButton by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }

    // Extract video properties using reflection
    val videoURL = getVideoProperty(video, "videoURL") ?: ""
    val videoTitle = getVideoProperty(video, "title") ?: "Unknown Video"
    val videoId = getVideoProperty(video, "id") ?: "unknown_id"

    // Listen for recording state from VideoManager
    val isRecordingActive by VideoManager.isRecordingActive.collectAsState()

    // Create ExoPlayer instance
    val exoPlayer = remember(videoURL) {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                // Configure for seamless looping
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = false

                try {
                    if (videoURL.isNotEmpty()) {
                        val mediaItem = MediaItem.fromUri(videoURL)
                        setMediaItem(mediaItem)
                        prepare()
                        Log.d("VIDEO_PLAYER", "📺 Loading video $videoId from $videoURL")
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

    // Recording state integration - pause when recording starts
    LaunchedEffect(isRecordingActive) {
        if (isRecordingActive) {
            Log.i("VIDEO_PLAYER", "🎥 Recording started - pausing video $videoId")
            exoPlayer.pause()
            exoPlayer.playWhenReady = false
        } else {
            Log.d("VIDEO_PLAYER", "🎬 Recording stopped - video $videoId can resume if active")
        }
    }

    // ✅ NEW: Listen for pause broadcast from stitch button
    DisposableEffect(videoId) {
        val pauseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    "com.stitchsocial.PAUSE_ALL_VIDEOS" -> {
                        Log.i("VIDEO_PLAYER", "📡 PAUSE BROADCAST RECEIVED for $videoId")
                        exoPlayer.pause()
                        exoPlayer.playWhenReady = false
                        isPlaying = false
                        showPlayButton = true
                    }
                }
            }
        }

        val filter = IntentFilter("com.stitchsocial.PAUSE_ALL_VIDEOS")
        LocalBroadcastManager.getInstance(context).registerReceiver(pauseReceiver, filter)
        Log.d("VIDEO_PLAYER", "📻 Registered pause receiver for $videoId")

        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(pauseReceiver)
            Log.d("VIDEO_PLAYER", "📻 Unregistered pause receiver for $videoId")
        }
    }

    // Video Manager control
    LaunchedEffect(isActive) {
        Log.d("VIDEO_PLAYER", "🎯 Video $videoId isActive changed to: $isActive")
        if (isActive && !isError && !isRecordingActive) {
            VideoManager.setActivePlayer(exoPlayer, videoId)
            exoPlayer.play()
            Log.i("VIDEO_PLAYER", "▶️ PLAYING $videoId (now active via VideoManager)")
        } else {
            exoPlayer.pause()
            exoPlayer.playWhenReady = false
            if (isRecordingActive) {
                Log.i("VIDEO_PLAYER", "🎥 PAUSED $videoId (recording active)")
            } else {
                Log.i("VIDEO_PLAYER", "⏸️ PAUSED $videoId (inactive)")
            }
        }
    }

    // Force pause when composable becomes inactive OR recording is active
    LaunchedEffect(isActive, isRecordingActive) {
        if (!isActive || isRecordingActive) {
            exoPlayer.pause()
            exoPlayer.playWhenReady = false
            val reason = if (isRecordingActive) "recording active" else "not active"
            Log.w("VIDEO_PLAYER", "🛑 FORCE PAUSE $videoId ($reason)")
        }
    }

    // Clean up on disposal
    DisposableEffect(Unit) {
        onDispose {
            Log.d("VIDEO_PLAYER", "🗑️ Disposing player for $videoId")
            VideoManager.clearPlayer(videoId)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
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
                    factory = { context ->
                        PlayerView(context).apply {
                            player = exoPlayer
                            useController = false
                            // ✅ REMOVED: setShowBuffering - no loading spinner!

                            // iPhone video fix
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            setKeepContentOnPlayerReset(true)

                            Log.d("VIDEO_PLAYER", "🎬 PlayerView configured: $videoId")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Gesture Detection Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val screenWidth = size.width
                                    when {
                                        offset.x < screenWidth * 0.25f -> {
                                            onEngagement?.invoke(InteractionType.COOL)
                                            Log.d("VIDEO_PLAYER", "❄️ Cool tap on $videoId")
                                        }
                                        offset.x > screenWidth * 0.75f -> {
                                            onEngagement?.invoke(InteractionType.HYPE)
                                            Log.d("VIDEO_PLAYER", "🔥 Hype tap on $videoId")
                                        }
                                        else -> {
                                            onVideoClick?.invoke()
                                        }
                                    }
                                }
                            )
                        }
                )

                // Play Button Overlay (only in fullscreen)
                if (showPlayButton && !isRecordingActive) {
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

                // Recording indicator overlay
                if (isRecordingActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(
                                Color.Red.copy(alpha = 0.8f),
                                CircleShape
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "🎥 REC",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
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