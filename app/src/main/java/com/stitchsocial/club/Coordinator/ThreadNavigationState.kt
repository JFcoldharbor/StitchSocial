/*
 * ThreadNavigationState.kt - THREAD NAVIGATION SYSTEM
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 6: Coordination - Thread Navigation System
 * Dependencies: Foundation types (ThreadData, CoreVideoMetadata)
 * Features: Horizontal thread navigation, visual indicators, gesture detection
 * ✅ FIXED: Added engagementViewModel and iconManager parameters
 */

package com.stitchsocial.club.coordination

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
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

// Foundation imports
import com.stitchsocial.club.foundation.ThreadData
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.views.VideoPlayerComposable
import com.stitchsocial.club.views.ContextualVideoOverlay
import com.stitchsocial.club.views.OverlayContext
import com.stitchsocial.club.views.OverlayAction
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// MARK: - Navigation State Management

data class ThreadNavigationState(
    val currentThread: ThreadData,
    val allVideosInThread: List<CoreVideoMetadata>,
    val currentVideoIndex: Int,
    val totalVideosInThread: Int,
    val isOnParent: Boolean,
    val canNavigateLeft: Boolean,
    val canNavigateRight: Boolean,
    val threadPosition: String
) {
    val currentVideo: CoreVideoMetadata = allVideosInThread[currentVideoIndex]
}

enum class NavigationDirection {
    HORIZONTAL_LEFT,
    HORIZONTAL_RIGHT,
    VERTICAL_UP,
    VERTICAL_DOWN,
    NONE
}

class ThreadNavigationController(
    private val threads: List<ThreadData>,
    private val currentThreadIndex: Int,
    private val onThreadChange: (Int) -> Unit = {},
    private val onChildChange: (Int) -> Unit = {},
    private val onVideoChange: (CoreVideoMetadata) -> Unit = {}
) {
    fun getCurrentNavigationState(currentChildIndex: Int): ThreadNavigationState {
        val thread = threads[currentThreadIndex]
        val allVideos = thread.allVideos

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
        val maxIndex = thread.childVideos.size

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
    val allVideosInThread = thread.allVideos
    val pagerState = rememberPagerState(pageCount = { allVideosInThread.size })
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    val navigationState = ThreadNavigationState(
        currentThread = thread,
        allVideosInThread = allVideosInThread,
        currentVideoIndex = pagerState.currentPage,
        totalVideosInThread = allVideosInThread.size,
        isOnParent = pagerState.currentPage == 0,
        canNavigateLeft = pagerState.currentPage > 0,
        canNavigateRight = pagerState.currentPage < allVideosInThread.size - 1,
        threadPosition = when (pagerState.currentPage) {
            0 -> "Thread"
            1 -> "Stitch"
            else -> "Stitch ${pagerState.currentPage}"
        }
    )

    LaunchedEffect(pagerState.currentPage) {
        onVideoChange?.invoke(allVideosInThread[pagerState.currentPage])
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val video = allVideosInThread[page]
            val isVideoActive = isActive && page == pagerState.currentPage

            ThreadVideoView(
                video = video,
                isActive = isVideoActive,
                currentUserID = currentUserID,
                engagementCoordinator = engagementCoordinator,
                navigationState = navigationState,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (allVideosInThread.size > 1) {
            ThreadNavigationIndicators(
                navigationState = navigationState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }

        ThreadPositionIndicator(
            navigationState = navigationState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
        )
    }
}

// MARK: - Individual Video View with Navigation Context

@Composable
fun ThreadVideoView(
    video: CoreVideoMetadata,
    isActive: Boolean,
    currentUserID: String,
    engagementCoordinator: EngagementCoordinator,
    navigationState: ThreadNavigationState,
    modifier: Modifier = Modifier
) {
    // ✅ ADD: Initialize view models
    val engagementViewModel = viewModel<EngagementViewModel>()
    val iconManager = remember { FloatingIconManager() }

    Box(modifier = modifier.fillMaxSize()) {
        VideoPlayerComposable(
            video = video,
            isActive = isActive,
            onEngagement = { interactionType ->
                println("🎯 THREAD NAV: Engagement $interactionType on ${video.title}")
            },
            onVideoClick = {
                println("🔹 THREAD NAV: Video clicked: ${video.title}")
            },
            modifier = Modifier.fillMaxSize()
        )

        // ✅ FIXED: Added missing parameters
        ContextualVideoOverlay(
            video = video,
            overlayContext = if (navigationState.isOnParent) OverlayContext.HOME_FEED else OverlayContext.THREAD_VIEW,
            currentUserID = currentUserID,
            threadVideo = if (!navigationState.isOnParent) navigationState.currentThread.parentVideo else null,
            isVisible = isActive,
            currentUserTier = UserTier.ROOKIE,
            engagementViewModel = engagementViewModel,  // ✅ ADDED
            iconManager = iconManager,                  // ✅ ADDED
            onAction = { action: OverlayAction ->
                when (action) {
                    is OverlayAction.NavigateToProfile -> {
                        println("👤 THREAD NAV: Navigate to profile ${video.creatorID}")
                    }
                    is OverlayAction.NavigateToThread -> {
                        println("🧵 THREAD NAV: Navigate to thread ${video.threadID}")
                    }
                    is OverlayAction.Engagement -> {
                        println("💫 THREAD NAV: Engagement ${action.type}")
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

@Composable
fun ThreadNavigationIndicators(
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

@Composable
fun ThreadPositionIndicator(
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