/*
 * FullScreenVideoActivity.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Full Screen Video Player with Contextual Overlay
 * Dependencies: ExoPlayer, VideoManager, CoreVideoMetadata
 * Features: Full screen video playback, contextual overlays, swipe to dismiss
 *
 * Matches: FullscreenVideoView.swift functionality
 */

package com.stitchsocial.club.views

import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.Temperature
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Date

/**
 * Full Screen Video Activity
 * Handles immersive video playback with contextual overlays
 */
class FullScreenVideoActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_VIDEO_TITLE = "video_title"
        const val EXTRA_CREATOR_NAME = "creator_name"
        const val EXTRA_THUMBNAIL_URL = "thumbnail_url"
        const val EXTRA_CREATOR_ID = "creator_id"
        const val EXTRA_DURATION = "duration"

        fun createIntent(
            context: Activity,
            video: CoreVideoMetadata,
            creatorName: String = video.creatorName
        ): Intent {
            return Intent(context, FullScreenVideoActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_ID, video.id)
                putExtra(EXTRA_VIDEO_URL, video.videoURL)
                putExtra(EXTRA_VIDEO_TITLE, video.title)
                putExtra(EXTRA_CREATOR_NAME, creatorName)
                putExtra(EXTRA_THUMBNAIL_URL, video.thumbnailURL)
                putExtra(EXTRA_CREATOR_ID, video.creatorID)
                putExtra(EXTRA_DURATION, video.duration)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract video data from intent
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        val title = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Unknown Video"
        val creatorName = intent.getStringExtra(EXTRA_CREATOR_NAME) ?: "Unknown Creator"
        val thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL) ?: ""
        val creatorId = intent.getStringExtra(EXTRA_CREATOR_ID) ?: ""
        val duration = intent.getDoubleExtra(EXTRA_DURATION, 30.0)

        // Create CoreVideoMetadata object
        val video = CoreVideoMetadata(
            id = videoId,
            title = title,
            videoURL = videoUrl,
            thumbnailURL = thumbnailUrl,
            creatorID = creatorId,
            creatorName = creatorName,
            createdAt = Date(),
            threadID = null,
            replyToVideoID = null,
            conversationDepth = 0,
            viewCount = 0,
            hypeCount = 0,
            coolCount = 0,
            replyCount = 0,
            shareCount = 0,
            lastEngagementAt = null,
            duration = duration,
            aspectRatio = 9.0/16.0,
            fileSize = 0L,
            contentType = ContentType.THREAD,
            temperature = Temperature.WARM,
            qualityScore = 50,
            engagementRatio = 0.0,
            velocityScore = 0.0,
            trendingScore = 0.0,
            discoverabilityScore = 0.5,
            isPromoted = false,
            isProcessing = false,
            isDeleted = false
        )

        setContent {
            FullScreenVideoView(
                video = video,
                creatorName = creatorName,
                onDismiss = { finish() }
            )
        }
    }
}

/**
 * Full Screen Video Composable View
 */
@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoView(
    video: CoreVideoMetadata,
    creatorName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }

    // Initialize ExoPlayer
    LaunchedEffect(video.videoURL) {
        println("FULLSCREEN: Setting up player for ${video.title}")

        val player = ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(video.videoURL)
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()

            // Listen for player state changes
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isLoading = playbackState == Player.STATE_BUFFERING
                    isPlaying = playbackState == Player.STATE_READY && playWhenReady

                    if (playbackState == Player.STATE_READY) {
                        println("FULLSCREEN: Video ready to play")
                    }
                }
            })
        }

        exoPlayer = player
    }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    // Cleanup player
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer?.release()
            println("FULLSCREEN: Player released")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        // Implement swipe to dismiss if needed
                    }
                ) { _, _ ->
                    // Handle drag gestures
                }
            }
            .clickable {
                showControls = !showControls
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                }
            }
    ) {
        // Video Player
        exoPlayer?.let { player ->
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Loading Indicator
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // Top Controls (Always visible)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close Button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White
                )
            }

            // Video Info
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
            ) {
                Text(
                    text = video.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "by $creatorName",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }

            // Share Button
            IconButton(
                onClick = {
                    // Implement share functionality
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color.White
                )
            }
        }

        // Bottom Controls (Show/Hide with tap)
        if (showControls) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Color.Black.copy(alpha = 0.5f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous Button
                IconButton(onClick = { /* Previous video */ }) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White
                    )
                }

                // Play/Pause Button
                IconButton(
                    onClick = {
                        exoPlayer?.let { player ->
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                player.play()
                            }
                        }
                    }
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Next Button
                IconButton(onClick = { /* Next video */ }) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White
                    )
                }
            }
        }

        // Engagement Controls (Right side)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hype Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = { /* Handle hype */ },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "Hype",
                        tint = Color.Red
                    )
                }
                Text(
                    text = "${video.hypeCount}",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            // Cool Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = { /* Handle cool */ },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        Icons.Default.ThumbDown,
                        contentDescription = "Cool",
                        tint = Color.Blue
                    )
                }
                Text(
                    text = "${video.coolCount}",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            // Reply Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = { /* Handle reply */ },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        Icons.Default.Reply,
                        contentDescription = "Reply",
                        tint = Color.White
                    )
                }
                Text(
                    text = "${video.replyCount}",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}