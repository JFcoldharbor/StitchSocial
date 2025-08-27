/*
 * HomeFeedView.kt - 2D SWIPE MECHANICS VERSION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Real HomeFeedView with 2D navigation:
 * - VERTICAL: Thread navigation (up/down between different conversations)
 * - HORIZONTAL: Depth navigation (left/right within same conversation)
 * Blueprint: Swift HomeFeedView.swift exact mechanics
 */

package com.example.stitchsocialclub.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.stitchsocialclub.services.VideoServiceImpl
import com.example.stitchsocialclub.foundation.InteractionType
import kotlinx.coroutines.launch

/**
 * Video data structure extracted from SimpleThreadData
 */
data class VideoData(
    val id: String,
    val title: String,
    val videoURL: String,
    val thumbnailURL: String,
    val creatorID: String,
    val creatorName: String,
    val threadID: String?,
    val conversationDepth: Int,
    val viewCount: Int = 0,
    val hypeCount: Int = 0,
    val coolCount: Int = 0
)

/**
 * Thread with conversation depth structure
 * Blueprint: Swift HomeFeedView thread organization
 */
data class ThreadWithDepth(
    val threadId: String,
    val threadVideo: VideoData,      // conversationDepth = 0
    val childVideos: List<VideoData>, // conversationDepth = 1
    val stepChildVideos: List<VideoData> // conversationDepth = 2
) {
    fun getVideoAtDepth(depth: Int): VideoData? {
        return when (depth) {
            0 -> threadVideo
            1 -> childVideos.firstOrNull()
            2 -> stepChildVideos.firstOrNull()
            else -> null
        }
    }

    fun getMaxDepth(): Int {
        return when {
            stepChildVideos.isNotEmpty() -> 2
            childVideos.isNotEmpty() -> 1
            else -> 0
        }
    }

    fun getAllVideosAtDepth(depth: Int): List<VideoData> {
        return when (depth) {
            0 -> listOf(threadVideo)
            1 -> childVideos
            2 -> stepChildVideos
            else -> emptyList()
        }
    }
}

/**
 * Enhanced Video Manager for 2D navigation
 */
object Enhanced2DVideoManager {
    private var currentActivePlayer: ExoPlayer? = null
    private var currentActiveVideoId: String? = null
    private var currentThreadIndex: Int = 0
    private var currentDepthIndex: Int = 0

    fun setActivePlayer(
        player: ExoPlayer,
        videoId: String,
        threadIndex: Int,
        depthIndex: Int
    ) {
        android.util.Log.d("2D_VIDEO_MANAGER", "Setting active: $videoId [T:$threadIndex D:$depthIndex]")

        // Pause any currently active player
        currentActivePlayer?.let { activePlayer ->
            if (activePlayer != player) {
                android.util.Log.d("2D_VIDEO_MANAGER", "Pausing previous player: $currentActiveVideoId")
                activePlayer.pause()
                activePlayer.playWhenReady = false
            }
        }

        // Set new active player and position
        currentActivePlayer = player
        currentActiveVideoId = videoId
        currentThreadIndex = threadIndex
        currentDepthIndex = depthIndex
    }

    fun pauseActivePlayer() {
        currentActivePlayer?.let { player ->
            android.util.Log.d("2D_VIDEO_MANAGER", "Pausing active player: $currentActiveVideoId")
            player.pause()
            player.playWhenReady = false
        }
    }

    fun clearPlayer(videoId: String) {
        if (currentActiveVideoId == videoId) {
            android.util.Log.d("2D_VIDEO_MANAGER", "Clearing active player: $videoId")
            currentActivePlayer = null
            currentActiveVideoId = null
        }
    }

    fun isVideoActive(threadIndex: Int, depthIndex: Int): Boolean {
        return currentThreadIndex == threadIndex && currentDepthIndex == depthIndex
    }
}

/**
 * 2D HomeFeedView with Vertical (threads) + Horizontal (depth) navigation
 * Blueprint: Swift HomeFeedView.swift swipe mechanics
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedView(
    userID: String,
    modifier: Modifier = Modifier
) {
    android.util.Log.i("HOMEFEED_2D", "Loading 2D HomeFeedView for user: $userID")

    val videoService = remember { VideoServiceImpl() }
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    // State management
    var threadsWithDepth by remember { mutableStateOf<List<ThreadWithDepth>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 2D Navigation State
    val verticalPagerState = rememberPagerState(pageCount = { threadsWithDepth.size })
    val horizontalPagerStates = remember { mutableMapOf<Int, androidx.compose.foundation.pager.PagerState>() }

    // Current position tracking
    var currentThreadIndex by remember { mutableStateOf(0) }
    var currentDepthIndex by remember { mutableStateOf(0) }

    // Load and organize videos by thread hierarchy
    LaunchedEffect(userID) {
        scope.launch {
            try {
                android.util.Log.i("HOMEFEED_2D", "Loading videos and organizing by threads")
                val loadedThreads = videoService.getFeedVideos(emptyList())

                // Convert SimpleThreadData to VideoData and organize by hierarchy
                val threadsMap = mutableMapOf<String, MutableList<VideoData>>()

                loadedThreads.forEach { threadData ->
                    val parentVideo = threadData.parentVideo
                    val threadId = parentVideo.id // Use id as threadID fallback

                    android.util.Log.d("HOMEFEED_2D", "Processing video: ${parentVideo.title}")
                    android.util.Log.d("HOMEFEED_2D", "Video ID: ${parentVideo.id}")
                    android.util.Log.d("HOMEFEED_2D", "Creator: ${parentVideo.creatorName}")
                    android.util.Log.d("HOMEFEED_2D", "URL: ${parentVideo.videoURL}")

                    // Convert to VideoData - using only available properties
                    val videoData = VideoData(
                        id = parentVideo.id,
                        title = parentVideo.title,
                        videoURL = parentVideo.videoURL,
                        thumbnailURL = "", // Default value since not available
                        creatorID = parentVideo.creatorName, // Use creatorName as creatorID
                        creatorName = parentVideo.creatorName,
                        threadID = parentVideo.id, // Use id as threadID
                        conversationDepth = 0, // Default to 0 since not available
                        viewCount = 0, // Default values
                        hypeCount = 0,
                        coolCount = 0
                    )

                    if (!threadsMap.containsKey(threadId)) {
                        threadsMap[threadId] = mutableListOf()
                    }
                    threadsMap[threadId]?.add(videoData)

                    android.util.Log.d("HOMEFEED_2D", "Added video to thread: $threadId")
                }

                android.util.Log.d("HOMEFEED_2D", "Total threads created: ${threadsMap.size}")

                // TEMPORARY: Create fake child/stepchild videos for testing
                val expandedThreadsMap = mutableMapOf<String, MutableList<VideoData>>()

                threadsMap.forEach { (threadId, videos) ->
                    val parentVideo = videos.first()
                    val expandedVideos = mutableListOf<VideoData>()

                    // Add original parent (depth 0)
                    expandedVideos.add(parentVideo)

                    // Add fake child video (depth 1)
                    expandedVideos.add(parentVideo.copy(
                        id = parentVideo.id + "_child",
                        title = "Child of: ${parentVideo.title}",
                        conversationDepth = 1
                    ))

                    // Add fake stepchild video (depth 2)
                    expandedVideos.add(parentVideo.copy(
                        id = parentVideo.id + "_stepchild",
                        title = "Stepchild of: ${parentVideo.title}",
                        conversationDepth = 2
                    ))

                    expandedThreadsMap[threadId] = expandedVideos

                    android.util.Log.d("HOMEFEED_2D", "Thread $threadId now has ${expandedVideos.size} videos (parent + child + stepchild)")
                }

                // Convert to ThreadWithDepth structure
                threadsWithDepth = expandedThreadsMap.map { (threadId, videos) ->
                    val sortedVideos = videos.sortedBy { it.conversationDepth }

                    val threadVideo = sortedVideos.firstOrNull { it.conversationDepth == 0 }
                        ?: sortedVideos.first()
                    val childVideos = sortedVideos.filter { it.conversationDepth == 1 }
                    val stepChildVideos = sortedVideos.filter { it.conversationDepth >= 2 }

                    android.util.Log.d("HOMEFEED_2D", "Thread $threadId structure:")
                    android.util.Log.d("HOMEFEED_2D", "  Parent: ${threadVideo.title} (depth ${threadVideo.conversationDepth})")
                    android.util.Log.d("HOMEFEED_2D", "  Children: ${childVideos.size} videos")
                    android.util.Log.d("HOMEFEED_2D", "  Stepchildren: ${stepChildVideos.size} videos")
                    android.util.Log.d("HOMEFEED_2D", "  Max depth: ${maxOf(threadVideo.conversationDepth, childVideos.maxOfOrNull { it.conversationDepth } ?: 0, stepChildVideos.maxOfOrNull { it.conversationDepth } ?: 0)}")

                    ThreadWithDepth(
                        threadId = threadId,
                        threadVideo = threadVideo,
                        childVideos = childVideos,
                        stepChildVideos = stepChildVideos
                    )
                }.filter { it.threadVideo != null } // Ensure we have valid threads

                android.util.Log.i("HOMEFEED_2D", "Organized ${threadsWithDepth.size} threads with depth")
                errorMessage = null
            } catch (e: Exception) {
                android.util.Log.e("HOMEFEED_2D", "Failed to load videos: ${e.message}")
                errorMessage = "Failed to load videos: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Initialize horizontal pager states for each thread
    LaunchedEffect(threadsWithDepth.size) {
        if (threadsWithDepth.isNotEmpty()) {
            horizontalPagerStates.clear()
            threadsWithDepth.forEachIndexed { index, thread ->
                val maxDepth = thread.getMaxDepth()
                android.util.Log.d("HOMEFEED_2D", "Thread $index max depth: $maxDepth")

                horizontalPagerStates[index] = androidx.compose.foundation.pager.PagerState(
                    currentPage = 0,
                    pageCount = { maxDepth + 1 }
                )

                android.util.Log.d("HOMEFEED_2D", "Created horizontal pager for thread $index with ${maxDepth + 1} pages")
            }
        }
    }

    // Track vertical pager changes (thread navigation)
    LaunchedEffect(verticalPagerState.currentPage) {
        val newThreadIndex = verticalPagerState.currentPage
        if (newThreadIndex != currentThreadIndex && newThreadIndex < threadsWithDepth.size) {
            android.util.Log.d("HOMEFEED_2D", "Thread changed: $currentThreadIndex → $newThreadIndex")

            // Pause previous video
            Enhanced2DVideoManager.pauseActivePlayer()

            currentThreadIndex = newThreadIndex
            currentDepthIndex = 0 // Reset to thread level when changing threads

            android.util.Log.d("HOMEFEED_2D", "NEW ACTIVE POSITION: Thread=$currentThreadIndex, Depth=$currentDepthIndex")
        }
    }

    // Track horizontal pager changes (depth navigation)
    LaunchedEffect(currentThreadIndex, threadsWithDepth.size) {
        if (threadsWithDepth.isNotEmpty() && currentThreadIndex < threadsWithDepth.size) {
            horizontalPagerStates[currentThreadIndex]?.let { horizontalState ->
                snapshotFlow { horizontalState.currentPage }.collect { newDepthIndex ->
                    if (newDepthIndex != currentDepthIndex) {
                        android.util.Log.d("HOMEFEED_2D", "Depth changed: $currentDepthIndex → $newDepthIndex")

                        // Pause previous video
                        Enhanced2DVideoManager.pauseActivePlayer()

                        currentDepthIndex = newDepthIndex

                        android.util.Log.d("HOMEFEED_2D", "NEW ACTIVE POSITION: Thread=$currentThreadIndex, Depth=$currentDepthIndex")
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            isLoading -> {
                LoadingIndicator()
            }
            errorMessage != null -> {
                ErrorDisplay(
                    message = errorMessage!!,
                    onRetry = {
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            // Retry logic here
                        }
                    }
                )
            }
            threadsWithDepth.isEmpty() -> {
                EmptyState()
            }
            else -> {
                // 2D Navigation: Vertical Pager (Threads) + Horizontal Pager (Depth)
                VerticalPager(
                    state = verticalPagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black) // Ensure black background
                ) { threadIndex ->
                    val thread = threadsWithDepth[threadIndex]
                    val horizontalState = horizontalPagerStates[threadIndex]

                    if (horizontalState != null) {
                        HorizontalPager(
                            state = horizontalState,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black) // Ensure black background
                        ) { depthIndex ->
                            val videoAtDepth = thread.getVideoAtDepth(depthIndex)

                            if (videoAtDepth != null) {
                                VideoFeedItem2D(
                                    video = videoAtDepth,
                                    threadIndex = threadIndex,
                                    depthIndex = depthIndex,
                                    isActive = (threadIndex == currentThreadIndex && depthIndex == currentDepthIndex),
                                    onEngagement = { videoId, interactionType ->
                                        android.util.Log.d("HOMEFEED_2D", "Engagement: $interactionType on $videoId")
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black) // Edge-to-edge black background
                                )
                            } else {
                                // No video at this depth - show placeholder
                                DepthPlaceholder(
                                    thread = thread,
                                    depthIndex = depthIndex,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black)
                                )
                            }
                        }
                    }
                }

                // Navigation indicators overlay - SIMPLIFIED
                if (threadsWithDepth.isNotEmpty()) {
                    SimpleNavigationIndicators(
                        currentThreadIndex = currentThreadIndex,
                        totalThreads = threadsWithDepth.size,
                        currentDepthIndex = currentDepthIndex,
                        maxDepth = threadsWithDepth[currentThreadIndex].getMaxDepth(),
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }
        }
    }
}

/**
 * Individual video item for 2D navigation - PURE VIDEO (NO OVERLAYS)
 */
@Composable
private fun VideoFeedItem2D(
    video: VideoData,
    threadIndex: Int,
    depthIndex: Int,
    isActive: Boolean,
    onEngagement: (String, InteractionType) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black) // Edge-to-edge black background
    ) {
        // PURE IMMERSIVE VIDEO PLAYER - NO OVERLAYS
        if (video.videoURL.isNotEmpty()) {
            ExoPlayerView2D(
                videoUrl = video.videoURL,
                videoId = video.id,
                threadIndex = threadIndex,
                depthIndex = depthIndex,
                isActive = isActive,
                modifier = Modifier.fillMaxSize() // Complete screen fill
            )
        } else {
            // Fallback placeholder
            VideoPlaceholder2D(
                video = video,
                depthIndex = depthIndex,
                modifier = Modifier.fillMaxSize()
            )
        }

        // TEMPORARY DEBUG INFO OVERLAY
        if (isActive) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp)
            ) {
                Text(
                    text = "T:$threadIndex D:$depthIndex",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = video.title,
                    color = Color.Yellow,
                    fontSize = 10.sp,
                    maxLines = 1
                )
                Text(
                    text = "Depth: ${video.conversationDepth}",
                    color = Color.Cyan,
                    fontSize = 10.sp
                )
            }
        }

        // NO OVERLAYS - PURE VIDEO EXPERIENCE
        // Removed: VideoMetadataOverlay2D
        // Removed: EngagementControls2D
        // Removed: DepthIndicatorBadge
    }
}

/**
 * ExoPlayer with 2D position awareness
 */
@Composable
private fun ExoPlayerView2D(
    videoUrl: String,
    videoId: String,
    threadIndex: Int,
    depthIndex: Int,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                val mediaItem = MediaItem.fromUri(videoUrl)
                setMediaItem(mediaItem)
                prepare()
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = false // Don't auto-play until active

                android.util.Log.d("EXOPLAYER_2D", "Created player for video: $videoId [T:$threadIndex D:$depthIndex]")
                android.util.Log.d("EXOPLAYER_2D", "Video URL: $videoUrl")

                // Add player event listener for debugging
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateString = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN"
                        }
                        android.util.Log.d("EXOPLAYER_2D", "Player state changed to: $stateString for video: $videoId")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        android.util.Log.d("EXOPLAYER_2D", "Is playing changed to: $isPlaying for video: $videoId")
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("EXOPLAYER_2D", "Player error for video $videoId: ${error.message}")
                    }
                })
            }
    }

    // Manage playback based on 2D active state
    LaunchedEffect(isActive) {
        android.util.Log.d("EXOPLAYER_2D", "Active state changed to: $isActive for video: $videoId [T:$threadIndex D:$depthIndex]")

        if (isActive) {
            Enhanced2DVideoManager.setActivePlayer(exoPlayer, videoId, threadIndex, depthIndex)
            exoPlayer.playWhenReady = true
            exoPlayer.play()
            android.util.Log.d("EXOPLAYER_2D", "STARTING PLAYBACK for video: $videoId [T:$threadIndex D:$depthIndex]")
        } else {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
            android.util.Log.d("EXOPLAYER_2D", "PAUSING PLAYBACK for video: $videoId [T:$threadIndex D:$depthIndex]")
        }
    }

    // Cleanup on disposal
    DisposableEffect(Unit) {
        onDispose {
            android.util.Log.d("EXOPLAYER_2D", "Disposing player for video: $videoId")
            Enhanced2DVideoManager.clearPlayer(videoId)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                player = exoPlayer
                useController = false // Hide default controls
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // Edge-to-edge fill
                setUseArtwork(false)
                setDefaultArtwork(null)
                setShowRewindButton(false)
                setShowFastForwardButton(false)
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowShuffleButton(false)
                controllerAutoShow = false

                android.util.Log.d("EXOPLAYER_2D", "PlayerView configured for video: $videoId")
            }
        },
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black), // Ensure black background
        update = { playerView ->
            // Force update the player reference
            playerView.player = exoPlayer
            android.util.Log.d("EXOPLAYER_2D", "PlayerView updated for video: $videoId, isActive: $isActive")
        }
    )
}

/**
 * Minimal navigation indicators - NO INTRUSIVE OVERLAYS
 */
@Composable
private fun SimpleNavigationIndicators(
    currentThreadIndex: Int,
    totalThreads: Int,
    currentDepthIndex: Int,
    maxDepth: Int,
    modifier: Modifier = Modifier
) {
    // Minimal dot indicators only
    Row(
        modifier = modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thread dots
        repeat(minOf(totalThreads, 5)) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == currentThreadIndex) 8.dp else 4.dp)
                    .background(
                        color = if (index == currentThreadIndex) Color.White else Color.White.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
            )
        }

        if (maxDepth > 0) {
            Spacer(modifier = Modifier.width(8.dp))

            // Depth indicator
            Text(
                text = when(currentDepthIndex) {
                    0 -> "•"
                    1 -> "••"
                    2 -> "•••"
                    else -> "••••"
                },
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Enhanced video metadata with glassmorphism overlay
 */
@Composable
private fun VideoMetadataOverlay2D(
    video: VideoData,
    depthIndex: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Creator info with glassmorphism
        Text(
            text = "@${video.creatorName}",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.8f),
                    offset = Offset(2f, 2f),
                    blurRadius = 4f
                )
            )
        )

        // Video title with shadow
        if (video.title.isNotEmpty()) {
            Text(
                text = video.title,
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.8f),
                        offset = Offset(1f, 1f),
                        blurRadius = 3f
                    )
                )
            )
        }

        // Conversation depth indicator (compact)
        if (depthIndex > 0) {
            Text(
                text = when(depthIndex) {
                    1 -> "↳ Child Video"
                    2 -> "↳ Stepchild Video"
                    else -> "↳ Depth $depthIndex"
                },
                color = when(depthIndex) {
                    1 -> Color.Green
                    2 -> Color.Magenta
                    else -> Color.Gray
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.8f),
                        offset = Offset(1f, 1f),
                        blurRadius = 2f
                    )
                )
            )
        }
    }
}

/**
 * Compact depth indicator badge for immersive view
 */
@Composable
private fun DepthIndicatorBadge(
    depthIndex: Int,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when(depthIndex) {
                0 -> Color.Yellow.copy(alpha = 0.9f)
                1 -> Color.Green.copy(alpha = 0.9f)
                2 -> Color.Magenta.copy(alpha = 0.9f)
                else -> Color.Gray.copy(alpha = 0.9f)
            }
        ),
        modifier = modifier
    ) {
        Text(
            text = when(depthIndex) {
                0 -> "🧵"
                1 -> "🔗"
                2 -> "👶"
                else -> "D$depthIndex"
            },
            color = Color.Black,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(8.dp)
        )
    }
}

/**
 * Enhanced engagement controls with glassmorphism for immersive view
 */
@Composable
private fun EngagementControls2D(
    onEngagement: (InteractionType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f))
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Hype button - Enhanced glassmorphism
        IconButton(
            onClick = { onEngagement(InteractionType.HYPE) },
            modifier = Modifier
                .size(56.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Red.copy(alpha = 0.4f),
                            Color(0xFFFFA500).copy(alpha = 0.2f) // Orange with alpha
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            Text(
                text = "🔥",
                fontSize = 24.sp
            )
        }

        // Cool button - Enhanced glassmorphism
        IconButton(
            onClick = { onEngagement(InteractionType.COOL) },
            modifier = Modifier
                .size(56.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Blue.copy(alpha = 0.4f),
                            Color.Cyan.copy(alpha = 0.2f)
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            Text(
                text = "❄️",
                fontSize = 24.sp
            )
        }

        // Share button - Enhanced glassmorphism
        IconButton(
            onClick = { onEngagement(InteractionType.SHARE) },
            modifier = Modifier
                .size(56.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.4f),
                            Color.Gray.copy(alpha = 0.2f)
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Minimal placeholder for videos not available at current depth
 */
@Composable
private fun VideoPlaceholder2D(
    video: VideoData,
    depthIndex: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Minimal placeholder - just video info
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "📹",
                fontSize = 48.sp
            )

            Text(
                text = video.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "@${video.creatorName}",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Minimal placeholder when no video exists at current depth
 */
@Composable
private fun DepthPlaceholder(
    thread: ThreadWithDepth,
    depthIndex: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = when(depthIndex) {
                    1 -> "No Children"
                    2 -> "No Stepchildren"
                    else -> "No Content"
                },
                color = Color.Gray,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Swipe to explore",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }
    }
}

// Reuse existing LoadingIndicator, ErrorDisplay, EmptyState components
@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = Color(0xFF00BFFF),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Loading 2D feed...",
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
private fun ErrorDisplay(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Connection Error",
                color = Color.Red,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = message,
                color = Color.White,
                fontSize = 14.sp
            )

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00BFFF)
                )
            ) {
                Text("Retry", color = Color.White)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No threads found",
            color = Color.White,
            fontSize = 18.sp
        )
    }
}