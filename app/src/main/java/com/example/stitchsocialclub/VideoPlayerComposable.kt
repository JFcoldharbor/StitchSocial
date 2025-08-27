/*
 * VideoPlayerComposable.kt - FIXED BACKGROUND PLAYBACK
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - ExoPlayer Compose Video Player
 * Dependencies: ExoPlayer, Foundation types
 * Features: TikTok-style autoplay, seamless looping, gesture controls
 * FIXED: Proper background video pause management
 */

package com.example.stitchsocialclub.views

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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.stitchsocialclub.foundation.InteractionType

/**
 * Global VideoManager object - ensures only one video plays at a time
 */
object VideoManager {
    private var currentActivePlayer: androidx.media3.exoplayer.ExoPlayer? = null
    private var currentActiveVideoId: String? = null

    fun setActivePlayer(player: androidx.media3.exoplayer.ExoPlayer, videoId: String) {
        android.util.Log.d("VIDEO_MANAGER", "🎯 Setting active player: $videoId")
        currentActivePlayer?.let { activePlayer ->
            if (activePlayer != player) {
                android.util.Log.d("VIDEO_MANAGER", "⏸️ Pausing previous player: $currentActiveVideoId")
                activePlayer.pause()
                activePlayer.playWhenReady = false
            }
        }
        currentActivePlayer = player
        currentActiveVideoId = videoId
    }

    fun pauseActivePlayer() {
        currentActivePlayer?.let { player ->
            android.util.Log.d("VIDEO_MANAGER", "⏸️ Pausing active player: $currentActiveVideoId")
            player.pause()
            player.playWhenReady = false
        }
    }

    fun resumeActivePlayer() {
        currentActivePlayer?.let { player ->
            android.util.Log.d("VIDEO_MANAGER", "▶️ Resuming active player: $currentActiveVideoId")
            player.play()
            player.playWhenReady = true
        }
    }

    fun clearPlayer(videoId: String) {
        if (currentActiveVideoId == videoId) {
            android.util.Log.d("VIDEO_MANAGER", "🗑️ Clearing active player: $videoId")
            currentActivePlayer = null
            currentActiveVideoId = null
        }
    }

    fun isVideoActive(videoId: String): Boolean = currentActiveVideoId == videoId
}

/**
 * ExoPlayer-powered video player for TikTok-style feed
 * FIXED: Proper background video pause management to prevent multiple videos playing
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

    // Create ExoPlayer instance
    val exoPlayer = remember(videoURL) {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                // Configure for seamless looping
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = false  // ✅ FIXED: Don't autoplay until active

                try {
                    if (videoURL.isNotEmpty()) {
                        val mediaItem = MediaItem.fromUri(videoURL)
                        setMediaItem(mediaItem)
                        prepare()
                        android.util.Log.d("VIDEO_PLAYER", "📺 Loading video $videoId from $videoURL")
                    } else {
                        isError = true
                        android.util.Log.w("VIDEO_PLAYER", "❌ No video URL for $videoId")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VIDEO_PLAYER", "❌ Error loading video $videoId - ${e.message}")
                    isError = true
                }

                // Add listener for play state
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                        showPlayButton = !playing && !isError
                        android.util.Log.d("VIDEO_PLAYER", "▶️ $videoId playing = $playing, isActive = $isActive")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        isError = true
                        showPlayButton = false
                        android.util.Log.e("VIDEO_PLAYER", "❌ Playback error for $videoId - ${error.message}")
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                android.util.Log.d("VIDEO_PLAYER", "✅ $videoId ready to play")
                                isError = false
                            }
                            Player.STATE_BUFFERING -> {
                                android.util.Log.d("VIDEO_PLAYER", "⏳ $videoId buffering")
                            }
                            Player.STATE_ENDED -> {
                                android.util.Log.d("VIDEO_PLAYER", "🔄 $videoId ended, looping")
                            }
                        }
                    }
                })
            }
    }

    // ✅ FIXED: Use VideoManager for global control
    LaunchedEffect(isActive) {
        android.util.Log.d("VIDEO_PLAYER", "🎯 Video $videoId isActive changed to: $isActive")
        if (isActive && !isError) {
            // Register this player as the active one (pauses others automatically)
            VideoManager.setActivePlayer(exoPlayer, videoId)
            exoPlayer.play()
            android.util.Log.i("VIDEO_PLAYER", "▶️ PLAYING $videoId (now active via VideoManager)")
        } else {
            // Just pause this player, VideoManager will handle active state
            exoPlayer.pause()
            exoPlayer.playWhenReady = false
            android.util.Log.i("VIDEO_PLAYER", "⏸️ PAUSED $videoId (inactive)")
        }
    }

    // ✅ FIXED: Force pause when composable becomes inactive
    LaunchedEffect(isActive) {
        if (!isActive) {
            // Immediately pause when not active
            exoPlayer.pause()
            exoPlayer.playWhenReady = false
            android.util.Log.w("VIDEO_PLAYER", "🛑 FORCE PAUSE $videoId (not active)")
        }
    }

    // ✅ FIXED: Clear from VideoManager on disposal
    DisposableEffect(Unit) {
        onDispose {
            android.util.Log.d("VIDEO_PLAYER", "🗑️ Disposing player for $videoId")
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
                // ExoPlayer View
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            player = exoPlayer
                            useController = false // Disable default controls
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
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
                                        // Left side tap - Cool interaction
                                        offset.x < screenWidth * 0.25f -> {
                                            onEngagement?.invoke(InteractionType.COOL)
                                            android.util.Log.d("VIDEO_PLAYER", "❄️ Cool tap on $videoId")
                                        }
                                        // Right side tap - Hype interaction
                                        offset.x > screenWidth * 0.75f -> {
                                            onEngagement?.invoke(InteractionType.HYPE)
                                            android.util.Log.d("VIDEO_PLAYER", "🔥 Hype tap on $videoId")
                                        }
                                        // Center tap - Play/pause toggle via VideoManager
                                        else -> {
                                            if (isActive) {
                                                if (VideoManager.isVideoActive(videoId)) {
                                                    if (isPlaying) {
                                                        VideoManager.pauseActivePlayer()
                                                        android.util.Log.d("VIDEO_PLAYER", "⏸️ Manual pause via VideoManager")
                                                    } else {
                                                        VideoManager.resumeActivePlayer()
                                                        android.util.Log.d("VIDEO_PLAYER", "▶️ Manual play via VideoManager")
                                                    }
                                                }
                                            }
                                            onVideoClick?.invoke()
                                        }
                                    }
                                }
                            )
                        }
                )

                // Play Button Overlay (when paused and active)
                if (showPlayButton && isActive) {
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

                // Video Info Overlay (bottom-left)
                VideoInfoOverlay(
                    video = video,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                )
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
        android.util.Log.w("VIDEO_PLAYER", "⚠️ Could not extract $propertyName: ${e.message}")
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

/**
 * Video information overlay
 */
@Composable
private fun VideoInfoOverlay(
    video: Any,
    modifier: Modifier = Modifier
) {
    val title = getVideoProperty(video, "title") ?: "Unknown Video"
    val creator = getVideoProperty(video, "creatorDisplayName") ?: "Unknown Creator"

    Column(modifier = modifier) {
        Text(
            text = "@$creator",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = title,
            color = Color.White,
            fontSize = 12.sp
        )
    }
}