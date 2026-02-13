/*
 * DiscoveryView.kt - COMPLETE iOS PORT WITH SWIPE CARDS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Ã¢Å“â€¦ ADDED: Swipe mode with DiscoverySwipeCards (matches iOS)
 * Ã¢Å“â€¦ ADDED: Shuffle button for content randomization
 * Ã¢Å“â€¦ ADDED: Swipe instructions indicator
 * Ã¢Å“â€¦ ADDED: Category icons matching iOS
 * Ã¢Å“â€¦ ADDED: Deep randomization with creator diversity
 * Ã¢Å“â€¦ FIXED: Mode toggle cycles through Swipe/Grid/List
 */

package com.stitchsocial.club.views

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// Foundation imports
import com.stitchsocial.club.foundation.*

// Service imports
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.SearchService
import com.stitchsocial.club.services.HashtagService
import com.stitchsocial.club.services.TrendingHashtag
import com.stitchsocial.club.services.VelocityTier

// Coordination imports
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.camera.RecordingContextFactory
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// Search and Follow imports
import com.stitchsocial.club.SearchView
import com.stitchsocial.club.FollowManager
import com.stitchsocial.club.ShareButton
import com.stitchsocial.club.ShareButtonSize

// MARK: - Discovery Category (with icons matching iOS)

enum class DiscoveryCategory(
    val displayName: String,
    val icon: ImageVector
) {
    ALL("All", Icons.Default.Apps),
    TRENDING("Trending", Icons.Default.LocalFireDepartment),
    RECENT("Recent", Icons.Default.Schedule),
    POPULAR("Popular", Icons.Default.Star),
    FOLLOWING("Following", Icons.Default.People)
}

// MARK: - Discovery Mode (matching iOS: swipe, grid)

enum class DiscoveryMode(
    val displayName: String,
    val icon: ImageVector
) {
    SWIPE("Swipe", Icons.Default.Layers),
    GRID("Grid", Icons.Default.GridView);

    fun toggle(): DiscoveryMode = when (this) {
        SWIPE -> GRID
        GRID -> SWIPE
    }
}

// MARK: - Discovery ViewModel with Deep Randomization (iOS port)

class DiscoveryViewModel(
    private val videoService: VideoServiceImpl,
    private val searchService: SearchService,
    private val hashtagService: HashtagService = HashtagService()
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _videos = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val videos: StateFlow<List<CoreVideoMetadata>> = _videos.asStateFlow()

    private val _filteredVideos = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val filteredVideos: StateFlow<List<CoreVideoMetadata>> = _filteredVideos.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _currentCategory = MutableStateFlow(DiscoveryCategory.ALL)
    val currentCategory: StateFlow<DiscoveryCategory> = _currentCategory.asStateFlow()

    // MARK: - Hashtag State (matches iOS DiscoveryViewModel)
    private val _trendingHashtags = MutableStateFlow<List<TrendingHashtag>>(emptyList())
    val trendingHashtags: StateFlow<List<TrendingHashtag>> = _trendingHashtags.asStateFlow()

    private val _isLoadingHashtags = MutableStateFlow(false)
    val isLoadingHashtags: StateFlow<Boolean> = _isLoadingHashtags.asStateFlow()

    private val _selectedHashtag = MutableStateFlow<TrendingHashtag?>(null)
    val selectedHashtag: StateFlow<TrendingHashtag?> = _selectedHashtag.asStateFlow()

    private val _hashtagVideos = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val hashtagVideos: StateFlow<List<CoreVideoMetadata>> = _hashtagVideos.asStateFlow()

    init {
        loadInitialContent()
    }

    // MARK: - Load Initial Content - PARENT THREADS ONLY

    fun loadInitialContent() {
        viewModelScope.launch {
            if (_isLoading.value) return@launch

            _isLoading.value = true
            _errorMessage.value = null

            try {
                println("DISCOVERY: Loading parent threads only (no replies)")

                val result = searchService.getRecentVideos(100)

                // FIX: Filter for parent videos only (conversationDepth == 0)
                val parentVideos = result.filter {
                    it.id.isNotEmpty() && it.conversationDepth == 0
                }

                println("DISCOVERY: Filtered ${result.size} -> ${parentVideos.size} (parents only)")

                _videos.value = parentVideos
                applyFilterAndShuffle()

                println("DISCOVERY: Loaded ${_filteredVideos.value.size} parent threads")

            } catch (e: Exception) {
                _errorMessage.value = "Failed to load discovery content"
                _videos.value = emptyList()
                _filteredVideos.value = emptyList()
                println("DISCOVERY: Load failed: ${e.message}")

            } finally {
                _isLoading.value = false
            }
        }
    }


    // MARK: - Load More Content - PARENT THREADS ONLY

    fun loadMoreContent() {
        viewModelScope.launch {
            if (_isLoading.value) return@launch

            _isLoading.value = true

            try {
                println("DISCOVERY: Loading more parent threads")

                val newVideos = searchService.getRecentVideos(50)

                // FIX: Filter for parent videos only (conversationDepth == 0)
                val parentVideos = newVideos.filter {
                    it.id.isNotEmpty() && it.conversationDepth == 0
                }

                // Add to existing videos, avoiding duplicates
                val currentIds = _videos.value.map { it.id }.toSet()
                val uniqueNewVideos = parentVideos.filter { it.id !in currentIds }

                val currentVideos = _videos.value.toMutableList()
                currentVideos.addAll(uniqueNewVideos)
                _videos.value = currentVideos

                applyFilterAndShuffle()

                println("DISCOVERY: Added ${uniqueNewVideos.size} more parent threads, total: ${_filteredVideos.value.size}")

            } catch (e: Exception) {
                println("DISCOVERY: Failed to load more: ${e.message}")

            } finally {
                _isLoading.value = false
            }
        }
    }


    // MARK: - Refresh Content

    fun refreshContent() {
        _videos.value = emptyList()
        loadInitialContent()
    }

    // MARK: - Randomize Content (shuffle button)

    fun randomizeContent() {
        _videos.value = _videos.value.shuffled()
        applyFilterAndShuffle()
        println("DISCOVERY: Content randomized - ${_filteredVideos.value.size} videos reshuffled")
    }

    // MARK: - Hashtag Methods (matches iOS DiscoveryViewModel)

    fun loadTrendingHashtags() {
        viewModelScope.launch {
            _isLoadingHashtags.value = true
            val trending = hashtagService.loadTrendingHashtags(10)
            _trendingHashtags.value = trending
            _isLoadingHashtags.value = false
        }
    }

    fun selectHashtag(hashtag: TrendingHashtag) {
        viewModelScope.launch {
            _selectedHashtag.value = hashtag
            _isLoading.value = true

            try {
                val result = hashtagService.getVideosForHashtag(hashtag.tag, 40)
                _hashtagVideos.value = result.videos
                _filteredVideos.value = result.videos
            } catch (e: Exception) {
                println("DISCOVERY: Failed to load hashtag videos - ${e.message}")
            }

            _isLoading.value = false
        }
    }

    fun clearHashtagFilter() {
        _selectedHashtag.value = null
        _hashtagVideos.value = emptyList()
        applyFilterAndShuffle()
    }

    // MARK: - Category Filtering

    fun filterBy(category: DiscoveryCategory) {
        _currentCategory.value = category

        val allVideos = _videos.value

        val filtered = when (category) {
            DiscoveryCategory.ALL -> allVideos
            DiscoveryCategory.TRENDING -> allVideos.filter {
                it.temperature == Temperature.HOT || it.temperature == Temperature.BLAZING
            }
            DiscoveryCategory.RECENT -> allVideos.sortedByDescending { it.createdAt }
            DiscoveryCategory.POPULAR -> allVideos.sortedByDescending { it.hypeCount }
            DiscoveryCategory.FOLLOWING -> allVideos // TODO: Filter by followed creators
        }

        _filteredVideos.value = diversifyShuffle(filtered)

        println("Ã°Å¸â€œÅ  DISCOVERY: Applied ${category.displayName} filter - ${_filteredVideos.value.size} videos")
    }

    // MARK: - Filtering and Shuffling

    private fun applyFilterAndShuffle() {
        _filteredVideos.value = diversifyShuffle(_videos.value)
    }

    /**
     * Shuffle with maximum creator variety (iOS port)
     */
    private fun diversifyShuffle(videos: List<CoreVideoMetadata>): List<CoreVideoMetadata> {
        if (videos.size <= 1) return videos

        // Group by creator
        val creatorBuckets = videos.groupBy { it.creatorID }.toMutableMap()
            .mapValues { it.value.shuffled().toMutableList() }
            .toMutableMap()

        // Interleave to maximize variety
        val result = mutableListOf<CoreVideoMetadata>()
        val recentCreators = mutableListOf<String>()
        val maxRecentTracking = 5

        while (creatorBuckets.isNotEmpty()) {
            val availableCreators = creatorBuckets.keys.filter { !recentCreators.contains(it) }

            val chosenCreatorID = if (availableCreators.isNotEmpty()) {
                availableCreators.random()
            } else {
                creatorBuckets.keys.random().also {
                    recentCreators.clear()
                }
            }

            val creatorVideos = creatorBuckets[chosenCreatorID]
            if (creatorVideos != null && creatorVideos.isNotEmpty()) {
                val video = creatorVideos.removeAt(0)
                result.add(video)

                recentCreators.add(chosenCreatorID)
                if (recentCreators.size > maxRecentTracking) {
                    recentCreators.removeAt(0)
                }

                if (creatorVideos.isEmpty()) {
                    creatorBuckets.remove(chosenCreatorID)
                }
            }
        }

        return result
    }
}

// MARK: - Main Discovery View (with Swipe Mode - iOS port)

@Composable
fun DiscoveryView(
    onNavigateToVideo: (CoreVideoMetadata) -> Unit = {},
    onNavigateToProfile: (String) -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowThreadView: (threadID: String, targetVideoID: String?) -> Unit = { _, _ -> },
    navigationCoordinator: NavigationCoordinator? = null,
    isAnnouncementShowing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Services
    val authService = remember { AuthService() }
    val videoService = remember { VideoServiceImpl() }
    val userService = remember { UserService(context) }
    val searchService = remember { SearchService() }

    // ViewModels
    val viewModel = remember {
        DiscoveryViewModel(videoService, searchService)
    }

    // Engagement setup
    val engagementCoordinator = remember { EngagementCoordinator(videoService, userService) }
    val engagementViewModel = remember {
        EngagementViewModel(
            authService = authService,
            videoService = videoService,
            userService = userService
        )
    }
    val iconManager = remember { FloatingIconManager() }

    // Follow manager for search (needs context)
    val followManager = remember { FollowManager(context) }

    // State
    val videos by viewModel.filteredVideos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val currentCategory by viewModel.currentCategory.collectAsState()

    // Hashtag state
    val trendingHashtags by viewModel.trendingHashtags.collectAsState()
    val isLoadingHashtags by viewModel.isLoadingHashtags.collectAsState()
    val selectedHashtag by viewModel.selectedHashtag.collectAsState()
    val hashtagVideos by viewModel.hashtagVideos.collectAsState()

    // Discovery Mode - default to SWIPE like iOS
    var discoveryMode by remember { mutableStateOf(DiscoveryMode.SWIPE) }
    var selectedCategory by remember { mutableStateOf(DiscoveryCategory.ALL) }

    // Swipe cards state
    var currentSwipeIndex by remember { mutableStateOf(0) }

    // Fullscreen video state with horizontal navigation
    var showVideoPlayer by remember { mutableStateOf(false) }
    var currentPlayingVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var allVideos by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var currentVideoIndex by remember { mutableStateOf(0) }

    // Search sheet state
    var showSearchSheet by remember { mutableStateOf(false) }

    // Get current user info
    val currentUserID = authService.getCurrentUserId()
    val currentUserTier = UserTier.ROOKIE // TODO: Load from user profile

    // Load more when nearing end of swipe cards
    LaunchedEffect(currentSwipeIndex, videos.size) {
        if (currentSwipeIndex >= videos.size - 10 && videos.isNotEmpty()) {
            viewModel.loadMoreContent()
        }
    }

    // Load trending hashtags on first composition
    LaunchedEffect(Unit) {
        viewModel.loadTrendingHashtags()
    }

    // Lifecycle observer to pause ALL videos when app goes to background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    println("DISCOVERY: App backgrounded - sending pause broadcast")
                    val intent = Intent("com.stitchsocial.club.PAUSE_ALL_VIDEOS")
                    LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Pause videos when announcement is showing
    LaunchedEffect(isAnnouncementShowing) {
        if (isAnnouncementShowing) {
            println("🔇 DISCOVERY: Announcement showing - pausing all videos")
            val intent = Intent("com.stitchsocial.club.PAUSE_ALL_VIDEOS")
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Black,
                        Color(0xFF800080).copy(alpha = 0.3f),
                        Color(0xFFFF69B4).copy(alpha = 0.2f),
                        Color.Black
                    )
                )
            )
    ) {

        // Main Discovery Content
        if (!showVideoPlayer) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header with shuffle and mode toggle
                DiscoveryHeader(
                    videoCount = videos.size,
                    isLoading = isLoading,
                    discoveryMode = discoveryMode,
                    onShuffleTapped = {
                        viewModel.randomizeContent()
                        currentSwipeIndex = 0 // Reset to first card
                    },
                    onModeToggle = {
                        discoveryMode = discoveryMode.toggle()
                    },
                    onSearchTapped = {
                        println("DISCOVERY: Search button tapped")
                        showSearchSheet = true
                    }
                )

                // Category Selector with icons
                DiscoveryCategorySelector(
                    selectedCategory = selectedCategory,
                    onCategorySelected = { category ->
                        selectedCategory = category
                        viewModel.filterBy(category)
                        currentSwipeIndex = 0 // Reset swipe position
                    }
                )

                // Trending Hashtags (show when Trending category selected)
                if (selectedCategory == DiscoveryCategory.TRENDING) {
                    TrendingHashtagsSection(
                        hashtags = trendingHashtags,
                        isLoading = isLoadingHashtags,
                        onHashtagTapped = { hashtag ->
                            viewModel.selectHashtag(hashtag)
                            currentSwipeIndex = 0
                        }
                    )
                }

                // Active hashtag filter bar
                if (selectedHashtag != null) {
                    HashtagFilterBar(
                        hashtag = selectedHashtag!!,
                        videoCount = hashtagVideos.size,
                        onClear = {
                            viewModel.clearHashtagFilter()
                            currentSwipeIndex = 0
                        }
                    )
                }

                // Content Area
                val currentErrorMessage = errorMessage
                when {
                    isLoading && videos.isEmpty() -> {
                        DiscoveryLoadingView()
                    }
                    currentErrorMessage != null -> {
                        DiscoveryErrorView(
                            message = currentErrorMessage,
                            onRetry = { viewModel.loadInitialContent() }
                        )
                    }
                    else -> {
                        when (discoveryMode) {
                            DiscoveryMode.SWIPE -> {
                                // Swipe Cards Mode (iOS style)
                                Box(modifier = Modifier.fillMaxSize()) {
                                    DiscoverySwipeCards(
                                        videos = videos,
                                        currentIndex = currentSwipeIndex,
                                        onIndexChange = { newIndex ->
                                            currentSwipeIndex = newIndex
                                        },
                                        onVideoTap = { video ->
                                            println("DISCOVERY: Video tapped - ${video.title}")
                                            currentPlayingVideo = video

                                            // Fetch thread data (parent + children)
                                            scope.launch {
                                                try {
                                                    if (video.threadID != null) {
                                                        val (parent, children) = videoService.getThreadData(video.threadID)
                                                        allVideos = if (parent != null) {
                                                            listOf(parent) + children  // Like HomeFeedView
                                                        } else {
                                                            listOf(video)
                                                        }
                                                    } else {
                                                        allVideos = listOf(video)
                                                    }
                                                    currentVideoIndex = 0  // Start at parent
                                                    showVideoPlayer = true
                                                } catch (e: Exception) {
                                                    println("DISCOVERY: Error fetching thread - ${e.message}")
                                                    allVideos = listOf(video)
                                                    currentVideoIndex = 0
                                                    showVideoPlayer = true
                                                }
                                            }
                                        },
                                        isAnnouncementShowing = isAnnouncementShowing,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    // Swipe Instructions Indicator (iOS style)
                                    SwipeInstructionsIndicator(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = 20.dp)
                                    )
                                }
                            }
                            DiscoveryMode.GRID -> {
                                DiscoveryGridView(
                                    videos = videos,
                                    onVideoTapped = { video ->
                                        println("DISCOVERY: Video tapped - ${video.title}")
                                        currentPlayingVideo = video

                                        // Fetch thread data (parent + children)
                                        scope.launch {
                                            try {
                                                if (video.threadID != null) {
                                                    val (parent, children) = videoService.getThreadData(video.threadID)
                                                    allVideos = if (parent != null) {
                                                        listOf(parent) + children  // Like HomeFeedView
                                                    } else {
                                                        listOf(video)
                                                    }
                                                } else {
                                                    allVideos = listOf(video)
                                                }
                                                currentVideoIndex = 0  // Start at parent
                                                showVideoPlayer = true
                                            } catch (e: Exception) {
                                                println("DISCOVERY: Error fetching thread - ${e.message}")
                                                allVideos = listOf(video)
                                                currentVideoIndex = 0
                                                showVideoPlayer = true
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Fullscreen Video Player with Horizontal Swipe (like HomeFeedView)
        if (showVideoPlayer && allVideos.isNotEmpty()) {
            val scope = rememberCoroutineScope()
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

            val videoCount = allVideos.size
            val offsetX = remember { Animatable(0f) }
            var isDragging by remember { mutableStateOf(false) }

            // Reset when video changes
            LaunchedEffect(currentPlayingVideo?.id) {
                currentVideoIndex = 0
                offsetX.snapTo(0f)
            }

            val currentVideo = allVideos.getOrNull(currentVideoIndex) ?: allVideos[0]

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f)
                    .background(Color.Black)
                    .pointerInput(videoCount) {
                        if (videoCount <= 1) return@pointerInput  // No swipe for single video

                        val velocityTracker = VelocityTracker()

                        detectHorizontalDragGestures(
                            onDragStart = {
                                isDragging = true
                                velocityTracker.resetTracking()
                            },
                            onDragEnd = {
                                isDragging = false

                                val velocity = velocityTracker.calculateVelocity().x
                                val currentOffset = offsetX.value

                                val threshold = screenWidthPx * 0.2f  // 20% of screen
                                val velocityThreshold = 300f

                                scope.launch {
                                    val targetIndex = when {
                                        // Swipe left (next) - negative offset or velocity
                                        currentOffset < -threshold || velocity < -velocityThreshold -> {
                                            (currentVideoIndex + 1).coerceAtMost(videoCount - 1)
                                        }
                                        // Swipe right (prev) - positive offset or velocity
                                        currentOffset > threshold || velocity > velocityThreshold -> {
                                            (currentVideoIndex - 1).coerceAtLeast(0)
                                        }
                                        // Snap back
                                        else -> currentVideoIndex
                                    }

                                    currentVideoIndex = targetIndex

                                    offsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessLow
                                        )
                                    )

                                    println("DISCOVERY SWIPE: Index now $currentVideoIndex / ${videoCount - 1}")
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                scope.launch {
                                    offsetX.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessLow
                                        )
                                    )
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPosition(
                                    change.uptimeMillis,
                                    change.position
                                )

                                // Edge resistance
                                val resistance = when {
                                    currentVideoIndex == 0 && offsetX.value + dragAmount > 0 -> 0.4f
                                    currentVideoIndex == videoCount - 1 && offsetX.value + dragAmount < 0 -> 0.4f
                                    else -> 1f
                                }

                                scope.launch {
                                    offsetX.snapTo(offsetX.value + dragAmount * resistance)
                                }
                            }
                        )
                    }
            ) {
                // Video layer with horizontal offset
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = offsetX.value
                        }
                ) {
                    key(currentVideo.id) {
                        VideoPlayerComposable(
                            video = currentVideo,
                            isActive = !isAnnouncementShowing && !isDragging,
                            modifier = Modifier.fillMaxSize(),
                            onSwipeUp = {
                                showVideoPlayer = false
                                currentPlayingVideo = null
                                allVideos = emptyList()
                                currentVideoIndex = 0
                            }
                        )
                    }
                }

                // Contextual overlay with bottom padding for tab bar area - FULL OVERLAY
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 18.dp)  // Just a bit lower
                ) {
                    ContextualVideoOverlay(
                        video = currentVideo,
                        overlayContext = OverlayContext.HOME_FEED,  // Full overlay with all buttons
                        currentUserID = currentUserID,
                        currentUserTier = currentUserTier ?: UserTier.ROOKIE,
                        engagementViewModel = engagementViewModel,
                        iconManager = iconManager,
                        followManager = followManager,
                        isVisible = true && !isDragging,
                        onAction = { action ->
                            when (action) {
                                is OverlayAction.NavigateToProfile -> {
                                    showVideoPlayer = false
                                    onNavigateToProfile(action.userID)
                                }
                                is OverlayAction.NavigateToThread -> {
                                    // Navigate to thread via parent callback
                                    val threadID = currentVideo.threadID ?: currentVideo.id
                                    onShowThreadView(threadID, currentVideo.id)
                                    println("DISCOVERY: Thread button tapped - navigating to $threadID")
                                }
                                is OverlayAction.StitchRecording -> {
                                    val isOwn = currentVideo.creatorID == currentUserID
                                    val ctx = if (isOwn) {
                                        RecordingContextFactory.createContinueThread(
                                            currentVideo.threadID ?: currentVideo.id,
                                            currentVideo.creatorName,
                                            currentVideo.title
                                        )
                                    } else {
                                        RecordingContextFactory.createStitchToThread(
                                            currentVideo.threadID ?: currentVideo.id,
                                            currentVideo.creatorName,
                                            currentVideo.title
                                        )
                                    }
                                    navigationCoordinator?.showModal(
                                        ModalState.RECORDING,
                                        mapOf(
                                            "context" to ctx,
                                            "parentVideo" to currentVideo
                                        )
                                    )
                                }
                                else -> {}
                            }
                        }
                    )
                }

                // Share button at top-right
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .zIndex(10f)
                ) {
                    ShareButton(
                        video = currentVideo,
                        creatorUsername = currentVideo.creatorName,
                        size = ShareButtonSize.MEDIUM
                    )
                }

                // Navigation indicators (like HomeFeedView)
                if (videoCount > 1) {
                    // Next video preview (right edge)
                    if (currentVideoIndex < videoCount - 1) {
                        val nextVideo = allVideos[currentVideoIndex + 1]
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 8.dp)
                                .size(60.dp, 80.dp)
                                .graphicsLayer {
                                    translationX = offsetX.value
                                    alpha = 0.9f  // More visible
                                }
                        ) {
                            AsyncImage(
                                model = nextVideo.thumbnailURL,
                                contentDescription = "Next",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }

                    // Previous video preview (left edge)
                    if (currentVideoIndex > 0) {
                        val prevVideo = allVideos[currentVideoIndex - 1]
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 8.dp)
                                .size(60.dp, 80.dp)
                                .graphicsLayer {
                                    translationX = offsetX.value
                                    alpha = 0.9f  // More visible
                                }
                        ) {
                            AsyncImage(
                                model = prevVideo.thumbnailURL,
                                contentDescription = "Previous",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }

                    // Progress indicator
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 60.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        repeat(videoCount.coerceAtMost(10)) { index ->
                            Box(
                                modifier = Modifier
                                    .size(if (index == currentVideoIndex) 8.dp else 6.dp)
                                    .background(
                                        color = if (index == currentVideoIndex)
                                            Color.White
                                        else
                                            Color.White.copy(alpha = 0.4f),
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        }

        // Search Sheet Modal
        if (showSearchSheet) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .zIndex(100f)
            ) {
                SearchView(
                    followManager = followManager,
                    onUserTapped = { user ->
                        println("DISCOVERY: User tapped from search - ${user.displayName}")
                        showSearchSheet = false
                        onNavigateToProfile(user.id)
                    },
                    onVideoTapped = { video ->
                        println("DISCOVERY: Video tapped from search - ${video.title}")
                        showSearchSheet = false
                        currentPlayingVideo = video
                        showVideoPlayer = true
                    },
                    onDismiss = {
                        println("DISCOVERY: Search dismissed")
                        showSearchSheet = false
                    }
                )
            }
        }
    }
}

// MARK: - Header Component (iOS style with shuffle)

@Composable
private fun DiscoveryHeader(
    videoCount: Int,
    isLoading: Boolean,
    discoveryMode: DiscoveryMode,
    onShuffleTapped: () -> Unit,
    onModeToggle: () -> Unit,
    onSearchTapped: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Discovery",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$videoCount videos",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = Color.Cyan
                    )
                    Text(
                        text = "Loading...",
                        fontSize = 12.sp,
                        color = Color.Cyan
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Shuffle button
            IconButton(onClick = onShuffleTapped) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = Color.Cyan
                )
            }

            // Mode toggle
            IconButton(onClick = onModeToggle) {
                Icon(
                    imageVector = discoveryMode.icon,
                    contentDescription = "Toggle ${discoveryMode.displayName}",
                    tint = if (discoveryMode == DiscoveryMode.SWIPE) Color.Cyan else Color.White.copy(alpha = 0.7f)
                )
            }

            // Search button
            IconButton(onClick = onSearchTapped) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// MARK: - Category Selector (iOS style with icons and underline)

@Composable
private fun DiscoveryCategorySelector(
    selectedCategory: DiscoveryCategory,
    onCategorySelected: (DiscoveryCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DiscoveryCategory.values().forEach { category ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onCategorySelected(category) }
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = category.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selectedCategory == category) Color.Cyan else Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = category.displayName,
                        fontSize = 14.sp,
                        fontWeight = if (selectedCategory == category) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selectedCategory == category) Color.Cyan else Color.White.copy(alpha = 0.7f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Underline indicator
                Box(
                    modifier = Modifier
                        .width(if (selectedCategory == category) 40.dp else 0.dp)
                        .height(2.dp)
                        .background(if (selectedCategory == category) Color.Cyan else Color.Transparent)
                )
            }
        }
    }
}

// MARK: - Swipe Instructions Indicator (iOS style)

@Composable
private fun SwipeInstructionsIndicator(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.5f),
                RoundedCornerShape(20.dp)
            )
            .border(
                1.dp,
                Color.White.copy(alpha = 0.2f),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left = Next
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color.White.copy(alpha = 0.8f)
            )
            Text(
                text = "Next",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(16.dp)
                .background(Color.White.copy(alpha = 0.3f))
        )

        // Right = Back
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color.White.copy(alpha = 0.8f)
            )
            Text(
                text = "Back",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(16.dp)
                .background(Color.White.copy(alpha = 0.3f))
        )

        // Tap = Fullscreen
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color.White.copy(alpha = 0.8f)
            )
            Text(
                text = "Fullscreen",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

// MARK: - Grid View

@Composable
private fun DiscoveryGridView(
    videos: List<CoreVideoMetadata>,
    onVideoTapped: (CoreVideoMetadata) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(videos.size) { index ->
            DiscoveryVideoCard(
                video = videos[index],
                onTapped = { onVideoTapped(videos[index]) }
            )
        }
    }
}

// MARK: - Video Card

@Composable
private fun DiscoveryVideoCard(
    video: CoreVideoMetadata,
    onTapped: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onTapped() }
            .background(Color(0xFF1C1C1E))
    ) {
        // Thumbnail
        AsyncImage(
            model = video.thumbnailURL,
            contentDescription = video.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Gradient Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        )

        // Content Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Temperature badge
                if (video.temperature != Temperature.COOL) {
                    Box(
                        modifier = Modifier
                            .background(
                                Color.White.copy(alpha = 0.2f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = video.temperature.emoji,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Bottom info
            Column {
                Text(
                    text = video.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "@${video.creatorName}",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "ðŸ”¥ ${video.hypeCount}",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "ðŸ’¬ ${video.replyCount}",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "ðŸ‘ ${video.viewCount}",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

// MARK: - Loading/Error Views

@Composable
private fun DiscoveryLoadingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = Color.Cyan,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Discovering amazing content...",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Finding videos from all time periods",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun DiscoveryErrorView(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(50.dp),
            tint = Color.Yellow
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Oops!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Cyan,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(25.dp),
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Text(
                text = "Try Again",
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// MARK: - Trending Hashtags Section (matches iOS trendingHashtagsSection)

@Composable
private fun TrendingHashtagsSection(
    hashtags: List<TrendingHashtag>,
    isLoading: Boolean,
    onHashtagTapped: (TrendingHashtag) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(vertical = 8.dp)
    ) {
        if (isLoading) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    color = Color(0xFFFF69B4),
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Loading trends...",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }
        } else if (hashtags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                hashtags.forEach { hashtag ->
                    DiscoveryHashtagChip(
                        hashtag = hashtag,
                        isSelected = false,
                        onTap = { onHashtagTapped(hashtag) }
                    )
                }
            }
        }
    }
}

// MARK: - Hashtag Filter Bar (matches iOS hashtagFilterBar)

@Composable
private fun HashtagFilterBar(
    hashtag: TrendingHashtag,
    videoCount: Int,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFF69B4).copy(alpha = 0.15f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = hashtag.velocityTier.emoji, fontSize = 14.sp)
            Text(
                text = "Viewing ${hashtag.displayTag}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = "• $videoCount videos",
                fontSize = 13.sp,
                color = Color.Gray
            )
        }

        IconButton(
            onClick = onClear,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Clear filter",
                tint = Color.Gray,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// MARK: - Discovery Hashtag Chip (matches iOS DiscoveryHashtagChip)

@Composable
private fun DiscoveryHashtagChip(
    hashtag: TrendingHashtag,
    isSelected: Boolean,
    onTap: () -> Unit
) {
    val background = if (isSelected) {
        Brush.horizontalGradient(listOf(Color(0xFFFF69B4), Color(0xFFFF69B4)))
    } else {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.05f))
        )
    }
    val borderColor = if (isSelected) Color(0xFFFF69B4) else Color.White.copy(alpha = 0.2f)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = hashtag.velocityTier.emoji, fontSize = 12.sp)
        Text(
            text = hashtag.displayTag,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) Color.Black else Color.White
        )
        Text(
            text = "${hashtag.videoCount}",
            fontSize = 11.sp,
            color = if (isSelected) Color.Black.copy(alpha = 0.7f) else Color.Gray
        )
    }
}