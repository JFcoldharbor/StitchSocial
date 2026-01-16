/*
 * DiscoveryView.kt - COMPLETE iOS PORT WITH SWIPE CARDS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * âœ… ADDED: Swipe mode with DiscoverySwipeCards (matches iOS)
 * âœ… ADDED: Shuffle button for content randomization
 * âœ… ADDED: Swipe instructions indicator
 * âœ… ADDED: Category icons matching iOS
 * âœ… ADDED: Deep randomization with creator diversity
 * âœ… FIXED: Mode toggle cycles through Swipe/Grid/List
 */

package com.stitchsocial.club.views

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

// Coordination imports
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// Search and Follow imports
import com.stitchsocial.club.SearchView
import com.stitchsocial.club.FollowManager

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
    private val searchService: SearchService
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
        println("ðŸŽ² DISCOVERY: Content randomized - ${_filteredVideos.value.size} videos reshuffled")
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

        println("ðŸ“Š DISCOVERY: Applied ${category.displayName} filter - ${_filteredVideos.value.size} videos")
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

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

    // Discovery Mode - default to SWIPE like iOS
    var discoveryMode by remember { mutableStateOf(DiscoveryMode.SWIPE) }
    var selectedCategory by remember { mutableStateOf(DiscoveryCategory.ALL) }

    // Swipe cards state
    var currentSwipeIndex by remember { mutableStateOf(0) }

    // Fullscreen video state
    var showVideoPlayer by remember { mutableStateOf(false) }
    var currentPlayingVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }

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

    // Lifecycle observer to pause ALL videos when app goes to background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    println("DISCOVERY: App backgrounded - sending pause broadcast")
                    val intent = Intent("com.stitchsocial.PAUSE_ALL_VIDEOS")
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
                                            showVideoPlayer = true
                                        },
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
                                        showVideoPlayer = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Fullscreen Video Player
        if (showVideoPlayer && currentPlayingVideo != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                VideoPlayer(
                    video = currentPlayingVideo!!,
                    currentUserID = currentUserID,
                    currentUserTier = currentUserTier ?: UserTier.ROOKIE,
                    engagementViewModel = engagementViewModel,
                    iconManager = iconManager,
                    onClose = {
                        showVideoPlayer = false
                        currentPlayingVideo = null
                    },
                    onNavigateToProfile = { creatorID ->
                        showVideoPlayer = false
                        onNavigateToProfile(creatorID)
                    }
                )
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
                        text = "🔥 ${video.hypeCount}",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "💬 ${video.replyCount}",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "👁 ${video.viewCount}",
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