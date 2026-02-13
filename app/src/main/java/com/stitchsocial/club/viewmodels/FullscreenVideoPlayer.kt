/*
 * FullscreenVideoPlayer.kt - FIXED BOOLEAN TYPE MISMATCH
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * ✅ FIXED: processHype/processCool return Unit, not Boolean
 * ✅ FIXED: Removed incorrect Boolean assignment from engagement calls
 */

package com.stitchsocial.club.views

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

// Foundation imports
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.UserTier

// Coordination imports
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.camera.RecordingContextFactory

// ViewModel imports
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// View imports
import com.stitchsocial.club.views.VideoPlayerComposable
import com.stitchsocial.club.views.ContextualVideoOverlay
import com.stitchsocial.club.views.OverlayContext
import com.stitchsocial.club.views.OverlayAction
import com.stitchsocial.club.views.EngagementType

// MARK: - VideoInfo Data Class

data class VideoInfo(
    val id: String,
    val title: String,
    val videoUrl: String,
    val thumbnailUrl: String,
    val duration: Long = 30000L,
    val creatorID: String = "unknown",
    val creatorName: String = "Unknown User"
)

// MARK: - Main Composable

@Composable
fun FullscreenVideoPlayer(
    video: VideoInfo,
    onDismiss: () -> Unit,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    currentUserID: String? = null,
    currentUserTier: UserTier = UserTier.ROOKIE,
    engagementCoordinator: EngagementCoordinator? = null,
    engagementViewModel: EngagementViewModel? = null,
    iconManager: FloatingIconManager? = null,
    navigationCoordinator: NavigationCoordinator? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // Create instances if not provided
    val viewModel = engagementViewModel ?: viewModel<EngagementViewModel>()
    val iconMgr = iconManager ?: remember { FloatingIconManager() }

    var showControls by remember { mutableStateOf(true) }

    // CRITICAL: Fullscreen containment Box
    // This Box ensures complete edge-to-edge coverage and blocks all parent content
    // Required to prevent bleed-through from Discovery/Home feed videos
    Box(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth() // Explicit full width - prevents edge bleed-through
            .background(Color.Black)
    ) {
        // ACTUAL VIDEO PLAYER
        VideoPlayerComposable(
            video = convertToMetadata(video),
            isActive = true,
            onEngagement = { interactionType ->
                println("VIDEO: ${interactionType.name} on ${video.title}")
            },
            onVideoClick = {
                showControls = !showControls
            },
            modifier = Modifier.fillMaxSize()
        )

        // ContextualVideoOverlay with all required parameters
        ContextualVideoOverlay(
            video = convertToMetadata(video),
            overlayContext = OverlayContext.HOME_FEED,
            currentUserID = currentUserID,
            threadVideo = null,
            isVisible = true,
            currentUserTier = currentUserTier,
            followManager = null,
            engagementViewModel = viewModel,
            iconManager = iconMgr,
            onAction = { action ->
                when (action) {
                    is OverlayAction.NavigateToProfile -> {
                        println("FULLSCREEN: Navigate to profile ${video.creatorID}")
                    }
                    is OverlayAction.NavigateToThread -> {
                        println("FULLSCREEN: Navigate to thread")
                    }
                    is OverlayAction.Follow -> {
                        println("FULLSCREEN: Follow user ${video.creatorID}")
                    }
                    is OverlayAction.Unfollow -> {
                        println("FULLSCREEN: Unfollow user ${video.creatorID}")
                    }
                    is OverlayAction.Engagement -> {
                        if (engagementCoordinator != null && currentUserID != null) {
                            scope.launch {
                                // ✅ FIXED: processHype/processCool return Unit, not Boolean
                                // Just call them without expecting a return value
                                when (action.type) {
                                    EngagementType.HYPE -> {
                                        println("🔥 FULLSCREEN: Processing HYPE")
                                        engagementCoordinator.processHype(
                                            videoID = video.id,
                                            userID = currentUserID,
                                            userTier = currentUserTier
                                        )
                                        println("✅ FULLSCREEN: Hype engagement complete!")
                                    }
                                    EngagementType.COOL -> {
                                        println("❄️ FULLSCREEN: Processing COOL")
                                        engagementCoordinator.processCool(
                                            videoID = video.id,
                                            userID = currentUserID,
                                            userTier = currentUserTier
                                        )
                                        println("✅ FULLSCREEN: Cool engagement complete!")
                                    }
                                    else -> {
                                        println("⚠️ FULLSCREEN: Unknown engagement type")
                                    }
                                }
                            }
                        }
                    }
                    is OverlayAction.Share -> {
                        println("FULLSCREEN: Share video ${video.id}")
                    }
                    is OverlayAction.StitchRecording -> {
                        val isOwn = video.creatorID == currentUserID
                        val ctx = if (isOwn) {
                            RecordingContextFactory.createContinueThread(
                                video.id, video.creatorName, video.title
                            )
                        } else {
                            RecordingContextFactory.createStitchToThread(
                                video.id, video.creatorName, video.title
                            )
                        }
                        navigationCoordinator?.showModal(
                            ModalState.RECORDING,
                            mapOf(
                                "context" to ctx,
                                "parentVideo" to convertToMetadata(video)
                            )
                        )
                    }
                }
            }
        )

        // Minimal close button only
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }
    }
}

// MARK: - Helper Functions

/**
 * Convert VideoInfo to CoreVideoMetadata using ACTUAL video data
 */
private fun convertToMetadata(video: VideoInfo): CoreVideoMetadata {
    return CoreVideoMetadata(
        id = video.id,
        title = video.title,
        description = "",
        videoURL = video.videoUrl,
        thumbnailURL = video.thumbnailUrl,
        creatorID = video.creatorID,
        creatorName = video.creatorName,
        hashtags = emptyList(),
        createdAt = java.util.Date(),
        threadID = null,
        replyToVideoID = null,
        conversationDepth = 0,
        viewCount = 0,
        hypeCount = 0,
        coolCount = 0,
        replyCount = 0,
        shareCount = 0,
        lastEngagementAt = null,
        duration = video.duration / 1000.0,
        aspectRatio = 9.0 / 16.0,
        fileSize = 0L,
        contentType = com.stitchsocial.club.foundation.ContentType.THREAD,
        temperature = com.stitchsocial.club.foundation.Temperature.WARM,
        qualityScore = 50,
        engagementRatio = 0.0,
        velocityScore = 0.0,
        trendingScore = 0.0,
        discoverabilityScore = 0.5,
        isPromoted = false,
        isProcessing = false,
        isDeleted = false
    )
}