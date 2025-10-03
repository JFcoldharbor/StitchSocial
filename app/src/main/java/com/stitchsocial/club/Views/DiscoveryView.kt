/*
 * Enhanced DiscoveryView.kt - YOUR DISCOVERYVIEW WITH VIDEO PLAYER INTEGRATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Your existing DiscoveryView enhanced with fullscreen video player
 * Features: Grid mode, categories, fullscreen video playback, all your existing functionality
 */

package com.stitchsocial.club.views

import com.stitchsocial.club.services.SearchService
import com.stitchsocial.club.foundation.Temperature
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.foundation.Temperature as FoundationTemperature
import com.stitchsocial.club.services.*
import com.stitchsocial.club.views.VideoPlayerComposable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.collections.filter
import kotlin.text.ifEmpty

// MARK: - Discovery Mode (Your existing code)

enum class DiscoveryMode(val displayName: String, val icon: ImageVector) {
    SWIPE("Swipe", Icons.Default.ViewCarousel),
    GRID("Grid", Icons.Default.GridView);

    fun toggle(): DiscoveryMode = when (this) {
        SWIPE -> GRID
        GRID -> SWIPE
    }
}

// MARK: - Discovery Category (Your existing code)

enum class DiscoveryCategory(
    val displayName: String,
    val icon: ImageVector
) {
    ALL("All", Icons.Default.GridView),
    TRENDING("Trending", Icons.Default.TrendingUp),
    RECENT("Recent", Icons.Default.Schedule),
    POPULAR("Popular", Icons.Default.Star),
    FOLLOWING("Following", Icons.Default.Group);
}

// MARK: - Discovery ViewModel (Your existing code)

class DiscoveryViewModel(
    private val videoService: VideoServiceImpl,
    private val searchService: SearchService
) : ViewModel() {

    // State management - exact iOS port
    private val _videos = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val videos: StateFlow<List<CoreVideoMetadata>> = _videos.asStateFlow()

    private val _filteredVideos = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val filteredVideos: StateFlow<List<CoreVideoMetadata>> = _filteredVideos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentCategory = MutableStateFlow(DiscoveryCategory.ALL)
    val currentCategory: StateFlow<DiscoveryCategory> = _currentCategory.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Load content - exact iOS port
     */
    fun loadContent() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                println("DISCOVERY VM: Loading discovery content...")

                // Use simple video loading (same as iOS)
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

    /**
     * Filter by category - exact iOS port
     */
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
            DiscoveryCategory.FOLLOWING -> allVideos // TODO: Add following filter
        }

        println("DISCOVERY VM: Filtered to ${_filteredVideos.value.size} videos for ${category.displayName}")
    }
}

// MARK: - Enhanced Discovery View (WITH VIDEO PLAYER INTEGRATION)

@Composable
fun DiscoveryView(
    onNavigateToVideo: (CoreVideoMetadata) -> Unit = {},
    onNavigateToProfile: (String) -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {

    // ViewModels
    val viewModel = remember {
        DiscoveryViewModel(VideoServiceImpl(), SearchService())
    }

    // State
    val videos by viewModel.filteredVideos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentCategory by viewModel.currentCategory.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var discoveryMode by remember { mutableStateOf(DiscoveryMode.GRID) }
    var selectedCategory by remember { mutableStateOf(DiscoveryCategory.ALL) }

    // VIDEO PLAYER STATE - ADDED FOR INTEGRATION
    var showVideoPlayer by remember { mutableStateOf(false) }
    var currentPlayingVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }

    // VIDEO INTEGRATION: Grid state for visibility tracking
    val gridState = rememberLazyGridState()

    // Load content on first appearance
    LaunchedEffect(Unit) {
        viewModel.loadContent()
    }

    // Simple cleanup
    DisposableEffect(Unit) {
        onDispose {
            println("DISCOVERY: Cleaning up view")
        }
    }

    // EXACT iOS Background Gradient
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Black,
                        Color(0xFF6A0DAD).copy(alpha = 0.3f), // Purple
                        Color(0xFFFFC0CB).copy(alpha = 0.2f), // Pink
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

                // Header (iOS exact layout)
                DiscoveryHeader(
                    videoCount = videos.size,
                    discoveryMode = discoveryMode,
                    onModeToggle = { discoveryMode = discoveryMode.toggle() },
                    onSearchTapped = onNavigateToSearch
                )

                // Category Selector (iOS exact styling)
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
                                // Instead of external navigation, show internal video player
                                currentPlayingVideo = video
                                showVideoPlayer = true
                            }
                        )
                    }
                }
            }
        }

        // FULLSCREEN VIDEO PLAYER OVERLAY
        if (showVideoPlayer && currentPlayingVideo != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .zIndex(10f)
            ) {
                VideoPlayerComposable(
                    video = currentPlayingVideo!!,
                    isActive = true,
                    onEngagement = { interactionType ->
                        println("DISCOVERY: Video engagement - $interactionType")
                    },
                    onVideoClick = {
                        println("DISCOVERY: Video clicked during playback")
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Close button
                IconButton(
                    onClick = {
                        showVideoPlayer = false
                        currentPlayingVideo = null
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close Video",
                        tint = Color.White
                    )
                }

                // Video info overlay
                currentPlayingVideo?.let { video ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                            .background(
                                Color.Black.copy(alpha = 0.7f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = video.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (video.creatorName.isNotEmpty()) {
                            Text(
                                text = "by ${video.creatorName}",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Your Existing Components (Header, Category Selector, etc.)

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
                Text(
                    text = "Try Again",
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun DiscoveryContentView(
    videos: List<CoreVideoMetadata>,
    mode: DiscoveryMode,
    onVideoTapped: (CoreVideoMetadata) -> Unit
) {
    when (mode) {
        DiscoveryMode.GRID -> {
            DiscoveryGridView(
                videos = videos,
                gridState = rememberLazyGridState(),
                onVideoTapped = onVideoTapped
            )
        }
        DiscoveryMode.SWIPE -> {
            DiscoverySwipeView(videos = videos, onVideoTapped = onVideoTapped)
        }
    }
}

// MARK: - Grid View (Your existing with click integration)

@Composable
private fun DiscoveryGridView(
    videos: List<CoreVideoMetadata>,
    gridState: LazyGridState,
    onVideoTapped: (CoreVideoMetadata) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = videos,
            key = { video -> video.id }
        ) { video ->
            DiscoveryVideoCard(
                video = video,
                onClick = {
                    println("DISCOVERY: Card clicked - ${video.title}")
                    onVideoTapped(video)
                }
            )
        }
    }
}

// MARK: - Video Card (Clean thumbnail without play button)

@Composable
private fun DiscoveryVideoCard(
    video: CoreVideoMetadata,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                println("DISCOVERY: Box clicked - ${video.title}")
                onClick()
            }
    ) {
        // Static thumbnail
        AsyncImage(
            model = video.thumbnailURL.ifEmpty { "https://via.placeholder.com/300x533" },
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        ),
                        startY = 200f
                    )
                )
        )

        // Video info
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (video.title.isNotEmpty()) {
                Text(
                    text = video.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (video.creatorName.isNotEmpty()) {
                Text(
                    text = video.creatorName,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// MARK: - Swipe View (Placeholder for your SWIPE mode)

@Composable
private fun DiscoverySwipeView(
    videos: List<CoreVideoMetadata>,
    onVideoTapped: (CoreVideoMetadata) -> Unit
) {
    // Your existing swipe implementation or placeholder
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Swipe Mode\nComing Soon",
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}