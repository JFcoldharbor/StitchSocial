/*
 * VideoPlayer.kt - FIXED ENGAGEMENTVIEWMODEL CRASH
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Video Player with Engagement Integration
 * Dependencies: ContextualVideoOverlay, EngagementCoordinator
 *
 * ✅ FIXED: Removed broken viewModel() fallback
 * ✅ FIXED: engagementViewModel now REQUIRED parameter
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Foundation imports
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.InteractionType

// Engagement imports
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// MARK: - Main VideoPlayer Composable

@Composable
fun VideoPlayer(
    video: CoreVideoMetadata,
    currentUserID: String? = null,
    currentUserTier: UserTier = UserTier.ROOKIE,
    isFollowing: Boolean = false,
    engagementCoordinator: EngagementCoordinator? = null,
    engagementViewModel: EngagementViewModel,  // ✅ REQUIRED - no default
    iconManager: FloatingIconManager? = null,
    onClose: () -> Unit = {},
    onNavigateToProfile: (String) -> Unit = {},
    onEngagement: (InteractionType) -> Unit = {},
    onShare: () -> Unit = {},
    onStitchRecording: () -> Unit = {}
) {
    // ✅ FIXED: Use provided viewModel directly, create iconManager if needed
    val iconMgr = iconManager ?: remember { FloatingIconManager() }

    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var showControls by remember { mutableStateOf(false) }
    var showEngagementAnimation by remember { mutableStateOf(false) }
    var lastEngagementType by remember { mutableStateOf<InteractionType?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        showControls = !showControls
                    }
                )
            }
    ) {
        // Background video player
        VideoPlayerComposable(
            video = video,
            isActive = true,
            onEngagement = onEngagement,
            onVideoClick = { showControls = !showControls },
            modifier = Modifier.fillMaxSize()
        )

        // Contextual overlay with engagement buttons
        ContextualVideoOverlay(
            video = video,
            overlayContext = OverlayContext.THREAD_VIEW,
            currentUserID = currentUserID,
            threadVideo = null,
            isVisible = true,
            currentUserTier = currentUserTier,
            followManager = null,
            engagementViewModel = engagementViewModel,  // ✅ Use parameter
            iconManager = iconMgr,
            onAction = { action ->
                when (action) {
                    is OverlayAction.NavigateToProfile -> {
                        onNavigateToProfile(video.creatorID)
                    }
                    is OverlayAction.Engagement -> {
                        lastEngagementType = when (action.type) {
                            EngagementType.HYPE -> InteractionType.HYPE
                            EngagementType.COOL -> InteractionType.COOL
                            else -> null
                        }
                        lastEngagementType?.let { onEngagement(it) }
                    }
                    is OverlayAction.Share -> {
                        onShare()
                    }
                    is OverlayAction.StitchRecording -> {
                        onStitchRecording()
                    }
                    is OverlayAction.Follow -> {
                        println("Follow user: ${video.creatorID}")
                    }
                    is OverlayAction.Unfollow -> {
                        println("Unfollow user: ${video.creatorID}")
                    }
                    is OverlayAction.NavigateToThread -> {
                        println("Navigate to thread: ${video.threadID}")
                    }
                }
            }
        )

        // Close button
        if (showControls) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White
                )
            }
        }
    }
}