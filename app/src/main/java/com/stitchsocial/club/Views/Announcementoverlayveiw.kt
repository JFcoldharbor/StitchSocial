/*
 * AnnouncementOverlayView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Full-screen announcement overlay that users must watch/acknowledge
 * Uses ExoPlayer for video playback
 * FEATURES:
 * - Video looping
 * - Hidden countdown (tracks in background)
 * - Creator pill with profile navigation
 * - Priority-based styling
 * - Continue/Acknowledge buttons
 *
 * Port from iOS Swift version
 */

package com.stitchsocial.club.views

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.stitchsocial.club.models.*
import com.stitchsocial.club.services.AnnouncementService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen announcement overlay view
 *
 * @param announcement The announcement to display
 * @param videoUrl URL of the announcement video
 * @param creatorName Name of the announcement creator
 * @param creatorProfileImageUrl Optional profile image URL
 * @param onComplete Callback when user completes/watches the announcement
 * @param onDismiss Callback when announcement is dismissed
 * @param onCreatorTap Callback when creator pill is tapped
 */
@Composable
fun AnnouncementOverlayView(
    announcement: Announcement,
    videoUrl: String?,
    creatorName: String = "Stitch Official",
    creatorProfileImageUrl: String? = null,
    onComplete: (watchedSeconds: Int) -> Unit,
    onDismiss: () -> Unit,
    onCreatorTap: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Video state
    var isLoadingVideo by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    // Watch tracking (hidden from UI)
    var watchedSeconds by remember { mutableIntStateOf(0) }
    var canDismiss by remember { mutableStateOf(false) }
    var hasAcknowledged by remember { mutableStateOf(false) }

    // Colors
    val priorityColor = when (announcement.priorityEnum) {
        AnnouncementPriority.CRITICAL -> Color.Red
        AnnouncementPriority.HIGH -> Color(0xFFFF9500) // Orange
        AnnouncementPriority.STANDARD -> Color(0xFF007AFF) // Blue
        AnnouncementPriority.LOW -> Color.Gray
    }

    // Initialize ExoPlayer
    DisposableEffect(videoUrl) {
        if (videoUrl != null) {
            val exoPlayer = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                prepare()

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                isLoadingVideo = false
                                isPlaying = true
                            }
                            Player.STATE_BUFFERING -> {
                                isLoadingVideo = true
                            }
                            Player.STATE_ENDED -> {
                                // Loop handled by REPEAT_MODE_ONE
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }
                })
            }
            player = exoPlayer
            isLoadingVideo = true
            loadError = null
            println("📢 ANNOUNCEMENT: ExoPlayer initialized for $videoUrl")
        } else {
            loadError = "No video URL provided"
            isLoadingVideo = false
        }

        onDispose {
            player?.release()
            player = null
            println("📢 ANNOUNCEMENT: ExoPlayer released")
        }
    }

    // Handle lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player?.pause()
                Lifecycle.Event.ON_RESUME -> player?.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Watch timer (hidden countdown)
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            watchedSeconds++

            // Check if minimum watch time reached
            if (watchedSeconds >= announcement.minimumWatchSeconds && !canDismiss) {
                canDismiss = true
                println("📢 ANNOUNCEMENT: Minimum watch time reached (${watchedSeconds}s)")
            }
        }
    }

    // Main UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Video player or loading/error state
        when {
            isLoadingVideo && loadError == null -> {
                LoadingView()
            }
            loadError != null -> {
                ErrorView(
                    message = loadError!!,
                    onSkip = onDismiss
                )
            }
            player != null -> {
                // Video player
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            // Toggle playback on tap
                            player?.let { p ->
                                if (p.isPlaying) p.pause() else p.play()
                            }
                        }
                )
            }
        }

        // Overlay Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top bar with badges
            AnnouncementHeader(
                announcement = announcement,
                priorityColor = priorityColor,
                modifier = Modifier.padding(top = 48.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Bottom controls
            AnnouncementFooter(
                announcement = announcement,
                creatorName = creatorName,
                creatorProfileImageUrl = creatorProfileImageUrl,
                canDismiss = canDismiss,
                hasAcknowledged = hasAcknowledged,
                onCreatorTap = { onCreatorTap?.invoke(announcement.creatorId) },
                onAcknowledge = {
                    hasAcknowledged = true
                    onComplete(watchedSeconds)
                },
                onContinue = {
                    onComplete(watchedSeconds)
                },
                modifier = Modifier.padding(bottom = 32.dp)
            )
        }
    }
}

// MARK: - Header Component

@Composable
private fun AnnouncementHeader(
    announcement: Announcement,
    priorityColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Official badge
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = "Official",
                    tint = Color.Cyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Official Announcement",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Type badge
        Surface(
            color = priorityColor,
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = getTypeIcon(announcement.typeEnum),
                    contentDescription = announcement.typeEnum.displayName,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = announcement.typeEnum.displayName.uppercase(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// MARK: - Footer Component

@Composable
private fun AnnouncementFooter(
    announcement: Announcement,
    creatorName: String,
    creatorProfileImageUrl: String?,
    canDismiss: Boolean,
    hasAcknowledged: Boolean,
    onCreatorTap: () -> Unit,
    onAcknowledge: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Creator Pill
        CreatorPillCompact(
            name = creatorName.ifEmpty { "Stitch Official" },
            profileImageUrl = creatorProfileImageUrl,
            onClick = onCreatorTap
        )

        // Title and message card
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = announcement.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                announcement.message?.takeIf { it.isNotEmpty() }?.let { message ->
                    Text(
                        text = message,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Action buttons (animate in when canDismiss)
        AnimatedVisibility(
            visible = canDismiss,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            when {
                announcement.requiresAcknowledgment && !hasAcknowledged -> {
                    // "I Understand" button for acknowledgment-required announcements
                    Button(
                        onClick = onAcknowledge,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Cyan
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            text = "I Understand",
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                announcement.isDismissable -> {
                    // "Continue" button for dismissable announcements
                    Button(
                        onClick = onContinue,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF007AFF),
                                        Color(0xFF5856D6)
                                    )
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                    ) {
                        Text(
                            text = "Continue",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Creator Pill (Compact version)

@Composable
private fun CreatorPillCompact(
    name: String,
    profileImageUrl: String?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Profile image placeholder
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color.Cyan, Color(0xFF007AFF))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "S",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "View Profile",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// MARK: - Loading View

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Loading announcement...",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }
    }
}

// MARK: - Error View

@Composable
private fun ErrorView(
    message: String,
    onSkip: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Error",
                tint = Color.Yellow,
                modifier = Modifier.size(50.dp)
            )
            Text(
                text = message,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            TextButton(
                onClick = onSkip,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White.copy(alpha = 0.6f)
                )
            ) {
                Text(
                    text = "Skip",
                    modifier = Modifier
                        .background(
                            Color.White.copy(alpha = 0.2f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

// MARK: - Helper Functions

private fun getTypeIcon(type: AnnouncementType): ImageVector {
    return when (type) {
        AnnouncementType.FEATURE -> Icons.Default.AutoAwesome
        AnnouncementType.UPDATE -> Icons.Default.SystemUpdate
        AnnouncementType.POLICY -> Icons.Default.Description
        AnnouncementType.EVENT -> Icons.Default.Star
        AnnouncementType.MAINTENANCE -> Icons.Default.Build
        AnnouncementType.PROMOTION -> Icons.Default.CardGiftcard
        AnnouncementType.COMMUNITY -> Icons.Default.Groups
        AnnouncementType.SAFETY -> Icons.Default.Security
    }
}

// MARK: - Wrapper for Integration

/**
 * Wrapper composable that integrates with AnnouncementService
 * Place this in your main app composition (e.g., MainActivity or MainView)
 */
@Composable
fun AnnouncementOverlayContainer(
    userId: String,
    onNavigateToProfile: ((String) -> Unit)? = null,
    videoUrlProvider: suspend (String) -> String? // Function to get video URL from videoId
) {
    val announcementService = remember { AnnouncementService.shared }

    val isShowing by announcementService.isShowingAnnouncement.collectAsState()
    val currentAnnouncement by announcementService.currentAnnouncement.collectAsState()

    var videoUrl by remember { mutableStateOf<String?>(null) }
    var creatorName by remember { mutableStateOf("Stitch Official") }

    // Load video URL when announcement changes
    LaunchedEffect(currentAnnouncement?.videoId) {
        currentAnnouncement?.let { announcement ->
            videoUrl = videoUrlProvider(announcement.videoId)
        }
    }

    if (isShowing && currentAnnouncement != null) {
        val announcement = currentAnnouncement!!

        AnnouncementOverlayView(
            announcement = announcement,
            videoUrl = videoUrl,
            creatorName = creatorName,
            creatorProfileImageUrl = null,
            onComplete = { watchedSeconds ->
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        announcementService.markAsCompleted(userId, announcement.id, watchedSeconds)
                        println("📢 OVERLAY: Completed announcement '${announcement.title}' after ${watchedSeconds}s")
                    } catch (e: Exception) {
                        println("📢 OVERLAY: Error completing announcement: ${e.message}")
                        announcementService.hideAnnouncement()
                    }
                }
            },
            onDismiss = {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        announcementService.dismissAnnouncement(userId, announcement.id)
                    } catch (e: Exception) {
                        println("📢 OVERLAY: Error dismissing announcement: ${e.message}")
                        announcementService.hideAnnouncement()
                    }
                }
            },
            onCreatorTap = onNavigateToProfile
        )
    }
}