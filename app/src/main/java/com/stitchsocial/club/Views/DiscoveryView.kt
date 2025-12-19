/*
 * DiscoveryView.kt - WITH SEARCH SHEET MODAL - COMPLETE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * ✅ COMPLETE REWRITE: All compilation errors fixed
 * ✅ FIXED: Proper StateFlow collection with initial values
 * ✅ FIXED: VideoPlayer (not FullscreenVideoPlayer)
 * ✅ FIXED: FollowManager(context) parameter
 * ✅ WORKING: Grid mode, categories, fullscreen video playback, search modal
 */

package com.stitchsocial.club.views

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

// MARK: - Discovery Category & Mode

enum class DiscoveryCategory(val displayName: String) {
    ALL("All"),
    TRENDING("Trending"),
    RECENT("Recent"),
    POPULAR("Popular"),
    FOLLOWING("Following")
}

enum class DiscoveryMode(val displayName: String, val icon: ImageVector) {
    GRID("Grid", Icons.Default.Apps),
    LIST("List", Icons.Default.List);

    fun toggle(): DiscoveryMode = when (this) {
        GRID -> LIST
        LIST -> GRID
    }
}

// MARK: - Discovery ViewModel

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
        loadContent()
    }

    fun loadContent() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                println("DISCOVERY VM: Loading discovery content...")

                val result: List<CoreVideoMetadata> = searchService.getRecentVideos(50)
                _videos.value = result
                _filteredVideos.value = result

                println("DISCOVERY VM: Loaded ${result.size} videos")

            } catch (e: Exception) {
                _errorMessage.value = "Failed to load discovery content"
                _videos.value = emptyList()
                _filteredVideos.value = emptyList()
                println("DISCOVERY VM: Failed to load content: ${e.message}")

            } finally {
                _isLoading.value = false
            }
        }
    }

    fun filterBy(category: DiscoveryCategory) {
        _currentCategory.value = category

        val allVideos = _videos.value

        _filteredVideos.value = when (category) {
            DiscoveryCategory.ALL -> allVideos
            DiscoveryCategory.TRENDING -> allVideos.filter {
                it.temperature == Temperature.HOT || it.temperature == Temperature.BLAZING
            }
            DiscoveryCategory.RECENT -> allVideos.sortedByDescending { it.createdAt }
            DiscoveryCategory.POPULAR -> allVideos.sortedByDescending { it.hypeCount }
            DiscoveryCategory.FOLLOWING -> allVideos
        }

        println("DISCOVERY VM: Filtered to ${_filteredVideos.value.size} videos for ${category.displayName}")
    }
}

// MARK: - Main Discovery View (WITH SEARCH SHEET)

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

    var discoveryMode by remember { mutableStateOf(DiscoveryMode.GRID) }
    var selectedCategory by remember { mutableStateOf(DiscoveryCategory.ALL) }
    var showVideoPlayer by remember { mutableStateOf(false) }
    var currentPlayingVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }

    // Search sheet state
    var showSearchSheet by remember { mutableStateOf(false) }

    // Get current user info
    val currentUserID = authService.getCurrentUserId()
    val currentUserTier = UserTier.ROOKIE // TODO: Load from user profile

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

    Box(modifier = modifier.fillMaxSize()) {

        // Main Discovery Content
        if (!showVideoPlayer) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                DiscoveryHeader(
                    videoCount = videos.size,
                    discoveryMode = discoveryMode,
                    onModeToggle = { discoveryMode = discoveryMode.toggle() },
                    onSearchTapped = {
                        println("DISCOVERY: Search button tapped")
                        showSearchSheet = true
                    }
                )

                // Category Selector
                DiscoveryCategorySelector(
                    selectedCategory = selectedCategory,
                    onCategorySelected = { category ->
                        selectedCategory = category
                        viewModel.filterBy(category)
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
                            onRetry = { viewModel.loadContent() }
                        )
                    }
                    else -> {
                        DiscoveryContentView(
                            videos = videos,
                            mode = discoveryMode,
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

// MARK: - Header Component

@Composable
private fun DiscoveryHeader(
    videoCount: Int,
    discoveryMode: DiscoveryMode,
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
            Text(
                text = "$videoCount videos",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(onClick = onModeToggle) {
                Icon(
                    imageVector = discoveryMode.icon,
                    contentDescription = "Toggle ${discoveryMode.displayName}",
                    tint = Color.White
                )
            }
            IconButton(onClick = onSearchTapped) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color.White
                )
            }
        }
    }
}

// MARK: - Category Selector

@Composable
private fun DiscoveryCategorySelector(
    selectedCategory: DiscoveryCategory,
    onCategorySelected: (DiscoveryCategory) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(DiscoveryCategory.values()) { category ->
            FilterChip(
                onClick = { onCategorySelected(category) },
                label = {
                    Text(
                        text = category.displayName,
                        color = if (selectedCategory == category) Color.Black else Color.White
                    )
                },
                selected = selectedCategory == category,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color.White,
                    containerColor = Color.Transparent
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedCategory == category,
                    borderColor = Color.White.copy(alpha = 0.5f),
                    selectedBorderColor = Color.White
                )
            )
        }
    }
}

// MARK: - Content Views

@Composable
private fun DiscoveryContentView(
    videos: List<CoreVideoMetadata>,
    mode: DiscoveryMode,
    onVideoTapped: (CoreVideoMetadata) -> Unit
) {
    when (mode) {
        DiscoveryMode.GRID -> DiscoveryGridView(videos, onVideoTapped)
        DiscoveryMode.LIST -> DiscoveryListView(videos, onVideoTapped)
    }
}

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

@Composable
private fun DiscoveryListView(
    videos: List<CoreVideoMetadata>,
    onVideoTapped: (CoreVideoMetadata) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(videos.size) { index ->
            DiscoveryVideoCard(
                video = videos[index],
                onTapped = { onVideoTapped(videos[index]) },
                isListMode = true
            )
        }
    }
}

@Composable
private fun DiscoveryVideoCard(
    video: CoreVideoMetadata,
    onTapped: () -> Unit,
    isListMode: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isListMode) 120.dp else 240.dp)
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
                        text = "❄️ ${video.coolCount}",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "💬 ${video.replyCount}",
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
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = Color.Cyan,
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
private fun DiscoveryErrorView(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Discovery Error",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = message,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Cyan
                )
            ) {
                Text("Retry")
            }
        }
    }
}