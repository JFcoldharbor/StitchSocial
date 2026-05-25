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
import com.stitchsocial.club.FollowManager

// ViewModel imports
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// View imports
import com.stitchsocial.club.views.VideoPlayerComposable
import com.stitchsocial.club.views.ContextualVideoOverlay
import com.stitchsocial.club.views.OverlayContext
import com.stitchsocial.club.views.OverlayAction
import com.stitchsocial.club.views.EngagementType
import com.stitchsocial.club.BuildConfig

// MARK: - VideoInfo Data Class

data class VideoInfo(
    val id: String,
    val title: String,
    val videoUrl: String,
    val thumbnailUrl: String,
    val duration: Long = 30000L,
    val creatorID: String = "unknown",
    val creatorName: String = "Unknown User",
    val threadID: String? = null,
    val conversationDepth: Int = 0,
    val replyCount: Int = 0
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
    followManager: FollowManager? = null,
    onShowThreadView: ((threadID: String, targetVideoID: String?) -> Unit)? = null,
    // Tells the contextual overlay where it's mounted so canReply / button
    // visibility logic resolves correctly. Default HOME_FEED for backwards
    // compat with existing call sites; ProfileView should pass PROFILE_OWN
    // or PROFILE_OTHER explicitly.
    overlayContext: OverlayContext = OverlayContext.HOME_FEED,
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
                if (BuildConfig.DEBUG) { println("VIDEO: ${interactionType.name} on ${video.title}") }
            },
            onVideoClick = {
                showControls = !showControls
            },
            modifier = Modifier.fillMaxSize()
        )

        // ContextualVideoOverlay with all required parameters
        ContextualVideoOverlay(
            video = convertToMetadata(video),
            overlayContext = overlayContext,
            currentUserID = currentUserID,
            threadVideo = null,
            isVisible = true,
            currentUserTier = currentUserTier,
            followManager = followManager,
            engagementViewModel = viewModel,
            iconManager = iconMgr,
            onAction = { action ->
                if (BuildConfig.DEBUG) { println("FULLSCREEN: action received: $action") }
                when (action) {
                    is OverlayAction.NavigateToProfile -> {
                        // Route through the navigation coordinator's modal
                        // system so MainActivity can switch to USER_PROFILE.
                        // We dismiss this player so the new profile is on top.
                        if (BuildConfig.DEBUG) { println("FULLSCREEN: Navigate to profile ${video.creatorID}") }
                        navigationCoordinator?.showModal(
                            ModalState.USER_PROFILE,
                            mapOf("userID" to video.creatorID)
                        )
                        onDismiss()
                    }
                    is OverlayAction.NavigateToThread -> {
                        val threadID = video.threadID ?: video.id
                        // ThreadView lives at the MainActivity level via the
                        // isShowingThreadView boolean (not the ModalState
                        // system), so the only way in is the onShowThreadView
                        // callback. Callers (ProfileView) MUST pass a real
                        // handler — MainActivity now does this since the
                        // recent fix.
                        if (onShowThreadView != null) {
                            onShowThreadView.invoke(threadID, video.id)
                        } else {
                            if (BuildConfig.DEBUG) { println("FULLSCREEN: ⚠️ Navigate to thread but no onShowThreadView handler wired — caller forgot to pass it") }
                        }
                    }
                    is OverlayAction.Follow -> {
                        if (BuildConfig.DEBUG) { println("FULLSCREEN: Follow user ${video.creatorID}") }
                        followManager?.toggleFollow(video.creatorID)
                            ?: println("FULLSCREEN: ⚠️ followManager is null — Follow ignored")
                    }
                    is OverlayAction.Unfollow -> {
                        if (BuildConfig.DEBUG) { println("FULLSCREEN: Unfollow user ${video.creatorID}") }
                        followManager?.toggleFollow(video.creatorID)
                            ?: println("FULLSCREEN: ⚠️ followManager is null — Unfollow ignored")
                    }
                    is OverlayAction.Engagement -> {
                        // INTENTIONAL NO-OP. ContextualVideoOverlay processes
                        // hype/cool internally via the engagementViewModel we
                        // already passed in (same pattern as HomeFeedView,
                        // line 464-473). Calling processHype/processCool here
                        // again would DOUBLE-PROCESS each tap — combined with
                        // the FOUNDER tier 20x visual multiplier this caused
                        // the "20x maxed" symptom you saw on profile videos.
                        // Leave it to the overlay; do not duplicate.
                    }
                    is OverlayAction.Share -> {
                        if (BuildConfig.DEBUG) { println("FULLSCREEN: Share video ${video.id}") }
                    }
                    is OverlayAction.StitchRecording -> {
                        val isOwn = video.creatorID == currentUserID
                        // Use the thread root's ID, not this video's id. When
                        // the user opens a REPLY from a profile grid (depth>=1)
                        // and taps Stitch, we need to attach the new clip under
                        // the original thread, not under the reply itself.
                        // HomeFeedView uses the same `threadID ?: id` pattern.
                        val threadParentID = video.threadID ?: video.id
                        val ctx = if (isOwn) {
                            RecordingContextFactory.createContinueThread(
                                threadParentID, video.creatorName, video.title
                            )
                        } else {
                            RecordingContextFactory.createStitchToThread(
                                threadParentID, video.creatorName, video.title
                            )
                        }
                        if (BuildConfig.DEBUG) { println("FULLSCREEN: Stitch from profile-launched video — threadParent=$threadParentID, isOwn=$isOwn") }
                        navigationCoordinator?.showModal(
                            ModalState.RECORDING,
                            mapOf(
                                "context" to ctx,
                                "parentVideo" to convertToMetadata(video)
                            )
                        )
                        // CRITICAL: dismiss the fullscreen player so the
                        // recording modal (which lives one layer up in
                        // MainActivity) is actually visible. Without this,
                        // showModal updates state and PAUSE_ALL_VIDEOS fires
                        // (video freezes) but the recording UI is hidden
                        // behind this player. Same pattern as onShowThreadView
                        // in the parent (ProfileView).
                        onDismiss()
                    }
                    is OverlayAction.Report -> {
                        // TODO: present ReportSheet from the parent navigator —
                        // FullscreenVideoPlayer doesn't own a sheet stack today.
                        if (BuildConfig.DEBUG) {
                            println("🚩 REPORT: ${action.targetType}/${action.targetID}")
                        }
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
        threadID = video.threadID,
        replyToVideoID = null,
        conversationDepth = video.conversationDepth,
        viewCount = 0,
        hypeCount = 0,
        coolCount = 0,
        replyCount = video.replyCount,
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