/*
 * ThreadNavigationState.kt - FIXED TO USE EXISTING THREADDATA
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 6: Coordination - Thread Navigation System using existing foundation types
 * Dependencies: Foundation types only (ThreadData, CoreVideoMetadata)
 * Features: Horizontal thread navigation, visual indicators, gesture detection
 *
 * ✅ FIXED: Uses existing ThreadData from foundation package
 * ✅ FIXED: Uses ThreadData.allVideos property that exists
 * ✅ FIXED: ContextualVideoOverlay parameter name (overlayContext)
 * ✅ FIXED: No conflicting function definitions
 */

package com.stitchsocial.club

import com.stitchsocial.club.foundation.UserTier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.launch

// Foundation imports - using EXISTING types
import com.stitchsocial.club.foundation.ThreadData
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.views.VideoPlayerComposable
import com.stitchsocial.club.views.ContextualVideoOverlay
import com.stitchsocial.club.views.OverlayContext
import com.stitchsocial.club.views.OverlayAction
import com.stitchsocial.club.coordination.EngagementCoordinator

// MARK: - Navigation State Management

/**
 * Complete navigation state for thread system
 * FIXED: Uses existing ThreadData structure
 */
data class ThreadNavigationState(
    val currentThread: ThreadData,
    val allVideosInThread: List<CoreVideoMetadata>,
    val currentVideoIndex: Int,
    val totalVideosInThread: Int,
    val isOnParent: Boolean,
    val canNavigateLeft: Boolean,
    val canNavigateRight: Boolean,
    val threadPosition: String // "Thread", "Stitch 1", "Stitch 2", etc.
) {
    /**
     * Get current video being displayed
     */
    val currentVideo: CoreVideoMetadata = allVideosInThread[currentVideoIndex]
}

/**
 * Navigation direction for gesture detection
 */
enum class NavigationDirection {
    HORIZONTAL_LEFT,
    HORIZONTAL_RIGHT,
    VERTICAL_UP,
    VERTICAL_DOWN,
    NONE
}

/**
 * Thread navigation controller with state management
 * FIXED: Uses existing ThreadData structure
 */
class ThreadNavigationController(
    private val threads: List<ThreadData>,
    private val currentThreadIndex: Int,
    private val onThreadChange: (Int) -> Unit = {},
    private val onChildChange: (Int) -> Unit = {},
    private val onVideoChange: (CoreVideoMetadata) -> Unit = {}
) {

    fun getCurrentNavigationState(currentChildIndex: Int): ThreadNavigationState {
        val thread = threads[currentThreadIndex]
        val allVideos = thread.allVideos // Uses existing ThreadData.allVideos property

        return ThreadNavigationState(
            currentThread = thread,
            allVideosInThread = allVideos,
            currentVideoIndex = currentChildIndex,
            totalVideosInThread = allVideos.size,
            isOnParent = currentChildIndex == 0,
            canNavigateLeft = currentChildIndex > 0,
            canNavigateRight = currentChildIndex < allVideos.size - 1,
            threadPosition = when {
                currentChildIndex == 0 -> "Thread"
                currentChildIndex == 1 -> "Stitch"
                else -> "Stitch $currentChildIndex"
            }
        )
    }

    fun navigateToChild(childIndex: Int): Boolean {
        val thread = threads[currentThreadIndex]
        val maxIndex = thread.childVideos.size // Use existing property

        return if (childIndex >= 0 && childIndex <= maxIndex) {
            onChildChange(childIndex)
            val video = if (childIndex == 0) thread.parentVideo else thread.childVideos[childIndex - 1]
            onVideoChange(video)
            true
        } else {
            false
        }
    }
}

// MARK: - Main Thread Navigation Container

/**
 * Reusable thread navigation container
 * FIXED: Uses existing ThreadData structure
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadNavigationContainer(
    thread: ThreadData,
    isActive: Boolean,
    currentUserID: String,
    engagementCoordinator: EngagementCoordinator,
    showVerticalGestures: Boolean = true,
    onVerticalSwipe: ((NavigationDirection) -> Unit)? = null,
    onVideoChange: ((CoreVideoMetadata) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Use existing ThreadData.allVideos property
    val allVideosInThread = thread.allVideos
    val horizontalPagerState = rememberPagerState(pageCount = { allVideosInThread.size })
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    // Current navigation state
    val navigationState = remember(horizontalPagerState.currentPage) {
        ThreadNavigationState(
            currentThread = thread,
            allVideosInThread = allVideosInThread,
            currentVideoIndex = horizontalPagerState.currentPage,
            totalVideosInThread = allVideosInThread.size,
            isOnParent = horizontalPagerState.currentPage == 0,
            canNavigateLeft = horizontalPagerState.currentPage > 0,
            canNavigateRight = horizontalPagerState.currentPage < allVideosInThread.size - 1,
            threadPosition = when {
                horizontalPagerState.currentPage == 0 -> "Thread"
                horizontalPagerState.currentPage == 1 -> "Stitch"
                else -> "Stitch ${horizontalPagerState.currentPage}"
            }
        )
    }

    // Video change callback
    LaunchedEffect(navigationState.currentVideo) {
        onVideoChange?.invoke(navigationState.currentVideo)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Horizontal pager for thread navigation
        HorizontalPager(
            state = horizontalPagerState,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            val video = allVideosInThread[pageIndex]
            val isCurrentPage = pageIndex == horizontalPagerState.currentPage

            ThreadVideoView(
                video = video,
                isActive = isCurrentPage && isActive,
                currentUserID = currentUserID,
                engagementCoordinator = engagementCoordinator,
                navigationState = navigationState,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Thread navigation indicators (bottom)
        if (allVideosInThread.size > 1) {
            ThreadNavigationIndicators(
                navigationState = navigationState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }

        // Thread position indicator (top)
        ThreadPositionIndicator(
            navigationState = navigationState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
        )
    }
}

// MARK: - Individual Video View with Navigation Context

/**
 * Individual video view within thread navigation
 * FIXED: ContextualVideoOverlay parameter name and explicit types
 */
@Composable
private fun ThreadVideoView(
    video: CoreVideoMetadata,
    isActive: Boolean,
    currentUserID: String,
    engagementCoordinator: EngagementCoordinator,
    navigationState: ThreadNavigationState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Background video player
        VideoPlayerComposable(
            video = video,
            isActive = isActive,
            onEngagement = { interactionType ->
                println("🎯 THREAD NAV: Engagement $interactionType on ${video.title}")
                // Handle engagement through coordinator
            },
            onVideoClick = {
                println("📹 THREAD NAV: Video clicked: ${video.title}")
            },
            modifier = Modifier.fillMaxSize()
        )

        // ✅ FIXED: ContextualVideoOverlay with explicit parameters to resolve ambiguity
        ContextualVideoOverlay(
            video = video,
            overlayContext = if (navigationState.isOnParent) OverlayContext.HOME_FEED else OverlayContext.THREAD_VIEW,
            currentUserID = currentUserID,
            threadVideo = if (!navigationState.isOnParent) navigationState.currentThread.parentVideo else null,
            isVisible = isActive,
            currentUserTier = UserTier.ROOKIE,
            isFollowing = false, // Use backward compatibility version explicitly
            onAction = { action: OverlayAction ->  // Explicit type annotation
                when (action) {
                    is OverlayAction.NavigateToProfile -> {
                        println("👤 THREAD NAV: Navigate to profile ${video.creatorID}")
                    }
                    is OverlayAction.NavigateToThread -> {
                        println("🧵 THREAD NAV: Navigate to thread ${video.threadID}")
                    }
                    is OverlayAction.Engagement -> {
                        println("💫 THREAD NAV: Engagement ${action.type}")
                        // Handle engagement through coordinator
                    }
                    is OverlayAction.Follow -> {
                        println("➕ THREAD NAV: Follow user ${video.creatorID}")
                    }
                    is OverlayAction.Unfollow -> {
                        println("➖ THREAD NAV: Unfollow user ${video.creatorID}")
                    }
                    is OverlayAction.Share -> {
                        println("📤 THREAD NAV: Share video ${video.id}")
                    }
                    is OverlayAction.StitchRecording -> {
                        println("🎬 THREAD NAV: Stitch recording ${video.id}")
                    }
                }
            }
        )
    }
}

// MARK: - Navigation UI Components

/**
 * Thread navigation indicators (dots at bottom)
 */
@Composable
private fun ThreadNavigationIndicators(
    navigationState: ThreadNavigationState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(navigationState.totalVideosInThread) { index ->
            val isActive = index == navigationState.currentVideoIndex
            val isParent = index == 0

            Box(
                modifier = Modifier
                    .size(if (isActive) 10.dp else 6.dp)
                    .background(
                        color = when {
                            isActive && isParent -> Color.White
                            isActive -> Color.Cyan
                            isParent -> Color.White.copy(alpha = 0.5f)
                            else -> Color.Gray.copy(alpha = 0.5f)
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * Thread position indicator (top center)
 */
@Composable
private fun ThreadPositionIndicator(
    navigationState: ThreadNavigationState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = navigationState.threadPosition,
            color = if (navigationState.isOnParent) Color.White else Color.Cyan,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}