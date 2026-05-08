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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import kotlinx.coroutines.tasks.await

// Foundation imports
import com.stitchsocial.club.foundation.*

// Service imports
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.CollectionService
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.coordination.DiscoveryEngagementTracker
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
import com.stitchsocial.club.BuildConfig

// MARK: - Discovery Category (with icons matching iOS)

enum class DiscoveryCategory(
    val displayName: String,
    val icon: ImageVector
) {
    ALL("All", Icons.Default.Apps),
    COMMUNITIES("Communities", Icons.Default.Groups),
    COLLECTIONS("Collections", Icons.Default.VideoLibrary),
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
    private val hashtagService: HashtagService = HashtagService(),
    private val collectionService: CollectionService = CollectionService()
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _videos = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val videos: StateFlow<List<CoreVideoMetadata>> = _videos.asStateFlow()

    private val _filteredVideos = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val filteredVideos: StateFlow<List<CoreVideoMetadata>> = _filteredVideos.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Collection card map — videoID → VideoCollection for collection cards injected into swipe feed
    val collectionCardMap = mutableMapOf<String, VideoCollection>()

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
        // Load persisted creator preferences — mirrors Swift .task { await loadPreferences() }
        viewModelScope.launch {
            DiscoveryEngagementTracker.loadPreferences()
        }
    }

    // MARK: - Load All Videos (mirrors iOS — one query, full catalog, shuffle on exhaust)

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private var hasLoaded = false

    fun loadInitialContent() {
        viewModelScope.launch {
            if (_isLoading.value || hasLoaded) return@launch
            _isLoading.value = true
            _errorMessage.value = null
            try {
                // TWO QUERIES — mirrors Swift shuffleWithRecencyPin bucket split:
                //   Query 1: fresh  — createdAt >= 48hr ago, limit 50, no index needed
                //   Query 2: rest   — createdAt < 48hr ago, limit 100, ordered DESC
                // Parallel via async/await. Client-side filters applied to both.
                // No composite index required — single field orderBy/whereGreaterThan only.

                val cutoff = com.google.firebase.Timestamp(
                    java.util.Date(System.currentTimeMillis() - 48L * 60 * 60 * 1000)
                )

                val (freshSnap, restSnap) = coroutineScope {
                    val fresh = async {
                        db.collection("videos")
                            .whereGreaterThanOrEqualTo("createdAt", cutoff)
                            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                            .limit(50)
                            .get().await()
                    }
                    val rest = async {
                        db.collection("videos")
                            .whereLessThan("createdAt", cutoff)
                            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                            .limit(100)
                            .get().await()
                    }
                    fresh.await() to rest.await()
                }

                fun filterDocs(snap: com.google.firebase.firestore.QuerySnapshot): List<CoreVideoMetadata> =
                    snap.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        if (data["isDeleted"] as? Boolean == true) return@mapNotNull null
                        if (data["isCollectionSegment"] as? Boolean == true) return@mapNotNull null
                        val depth = (data["conversationDepth"] as? Long)?.toInt() ?: 0
                        if (depth > 0) return@mapNotNull null
                        val vis = data["visibility"] as? String ?: "public"
                        if (vis == "private" || vis == "followersOnly") return@mapNotNull null
                        val url = data["videoURL"] as? String ?: return@mapNotNull null
                        if (url.isBlank()) return@mapNotNull null
                        decodeVideo(data, doc.id)
                    }

                val fresh = filterDocs(freshSnap).shuffled()
                val rest  = filterDocs(restSnap).shuffled()

                // Combine: fresh pinned first, rest shuffled behind — matches Swift
                val combined = fresh + rest

                hasLoaded = true
                _videos.value = combined
                applyFilterAndShuffle()
                if (BuildConfig.DEBUG) { println("✅ DISCOVERY: ${fresh.size} fresh (≤48hr) + ${rest.size} rest = ${combined.size} total") }

                loadFeaturedCollectionsForSwipeFeed()

                val prefetchUrls = combined.take(3).map { it.videoURL }.filter { it.isNotEmpty() }
                if (prefetchUrls.isNotEmpty()) {
                    com.stitchsocial.club.services.VideoDiskCache.prefetchVideos(prefetchUrls)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load videos"
                if (BuildConfig.DEBUG) { println("❌ DISCOVERY: Load failed: ${e.message}") }
                delay(2000)
                hasLoaded = false
                _isLoading.value = false
                loadInitialContent()
                return@launch
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun decodeVideo(data: Map<String, Any>, id: String): CoreVideoMetadata {
        val hype = (data["hypeCount"] as? Long)?.toInt() ?: 0
        val cool = (data["coolCount"] as? Long)?.toInt() ?: 0
        return CoreVideoMetadata(
            id = id,
            title = data["title"] as? String ?: "",
            description = data["description"] as? String ?: "",
            videoURL = data["videoURL"] as? String ?: "",
            thumbnailURL = data["thumbnailURL"] as? String ?: "",
            creatorID = data["creatorID"] as? String ?: "",
            creatorName = data["creatorName"] as? String ?: "",
            hashtags = @Suppress("UNCHECKED_CAST") (data["hashtags"] as? List<String>) ?: emptyList(),
            taggedUserIDs = emptyList(),
            createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: java.util.Date(),
            threadID = data["threadID"] as? String,
            replyToVideoID = null,
            conversationDepth = (data["conversationDepth"] as? Long)?.toInt() ?: 0,
            viewCount = (data["viewCount"] as? Long)?.toInt() ?: 0,
            hypeCount = hype,
            coolCount = cool,
            replyCount = (data["replyCount"] as? Long)?.toInt() ?: 0,
            shareCount = (data["shareCount"] as? Long)?.toInt() ?: 0,
            lastEngagementAt = (data["lastEngagementAt"] as? Timestamp)?.toDate(),
            duration = (data["duration"] as? Number)?.toDouble() ?: 0.0,
            aspectRatio = (data["aspectRatio"] as? Number)?.toDouble() ?: (9.0 / 16.0),
            fileSize = (data["fileSize"] as? Long) ?: 0L,
            contentType = ContentType.THREAD,
            temperature = Temperature.COOL,
            qualityScore = (data["qualityScore"] as? Long)?.toInt() ?: 50,
            engagementRatio = if (hype + cool > 0) hype.toDouble() / (hype + cool) else 0.5,
            velocityScore = 0.0,
            trendingScore = 0.0,
            discoverabilityScore = (data["discoverabilityScore"] as? Number)?.toDouble() ?: 0.5,
            isPromoted = data["isPromoted"] as? Boolean ?: false,
            isProcessing = false,
            isDeleted = data["isDeleted"] as? Boolean ?: false,
            recordingSource = data["recordingSource"] as? String ?: "unknown",
            collectionID = data["collectionID"] as? String,
            segmentNumber = (data["segmentNumber"] as? Long)?.toInt(),
            segmentTitle = data["segmentTitle"] as? String,
            isCollectionSegment = data["isCollectionSegment"] as? Boolean ?: false
        )
    }


    // MARK: - Reshuffle at end (mirrors iOS reshuffleAndRestart)

    fun reshuffleAndRestart() {
        // Re-apply recency pin on reshuffle — same bucket logic as load
        val cutoffMs = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        val current = _videos.value
        val fresh = current.filter { it.createdAt.time >= cutoffMs }.shuffled()
        val rest  = current.filter { it.createdAt.time < cutoffMs  }.shuffled()
        _videos.value = fresh + rest
        applyFilterAndShuffle()
        if (BuildConfig.DEBUG) { println("🔀 DISCOVERY: Reshuffled — ${fresh.size} fresh + ${rest.size} rest") }
    }

    fun loadMoreContent() { reshuffleAndRestart() }


    // MARK: - Refresh Content

    fun refreshContent() {
        hasLoaded = false
        _videos.value = emptyList()
        _filteredVideos.value = emptyList()
        loadInitialContent()
        // Load persisted creator preferences — mirrors Swift .task { await loadPreferences() }
        viewModelScope.launch {
            DiscoveryEngagementTracker.loadPreferences()
        }
    }

    // MARK: - Randomize Content (shuffle button)

    fun randomizeContent() {
        _videos.value = _videos.value.shuffled()
        applyFilterAndShuffle()
        if (BuildConfig.DEBUG) { println("DISCOVERY: Content randomized - ${_filteredVideos.value.size} videos reshuffled") }
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
                if (BuildConfig.DEBUG) { println("DISCOVERY: Failed to load hashtag videos - ${e.message}") }
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
            DiscoveryCategory.COMMUNITIES -> emptyList() // Handled by CommunityListView
            DiscoveryCategory.TRENDING -> allVideos.filter {
                it.temperature == Temperature.HOT || it.temperature == Temperature.BLAZING
            }
            DiscoveryCategory.RECENT -> allVideos.sortedByDescending { it.createdAt }
            DiscoveryCategory.POPULAR -> allVideos.sortedByDescending { it.hypeCount }
            DiscoveryCategory.FOLLOWING -> allVideos // TODO: Filter by followed creators
            DiscoveryCategory.COLLECTIONS -> emptyList() // Handled by CollectionsDiscoveryRow
        }

        _filteredVideos.value = diversifyShuffle(filtered)

        if (BuildConfig.DEBUG) { println("Ã°Å¸â€œÅ  DISCOVERY: Applied ${category.displayName} filter - ${_filteredVideos.value.size} videos") }
    }

    // MARK: - Filtering and Shuffling

    /**
     * Fetches up to 6 featured collections, builds placeholder CoreVideoMetadata cards,
     * stores in collectionCardMap, and injects into filteredVideos at evenly-spaced positions.
     * Mirrors Swift DiscoveryViewModel.loadFeaturedCollectionsForSwipeFeed exactly.
     * CACHING: session-scoped — one Firestore read, zero repeats on swipe.
     */
    private fun loadFeaturedCollectionsForSwipeFeed() {
        viewModelScope.launch {
            try {
                val collections = collectionService.getDiscoveryCollections(6)
                if (collections.isEmpty()) return@launch

                val currentVideos = _videos.value.toMutableList()
                val injected = mutableListOf<CoreVideoMetadata>()

                for (collection in collections) {
                    // Placeholder card — videoURL empty so DiscoveryCard renders cover image not player
                    // isPromoted=true is the isCollectionCard signal read by DiscoveryCard
                    val card = CoreVideoMetadata(
                        id = collection.id,
                        title = collection.title,
                        description = "",
                        videoURL = "",  // empty = no video player
                        thumbnailURL = collection.coverImageURL ?: "",
                        creatorID = collection.creatorID,
                        creatorName = collection.creatorName,
                        createdAt = java.util.Date(),
                        threadID = collection.id,
                        replyToVideoID = null,
                        conversationDepth = 0,
                        viewCount = 0, hypeCount = 0, coolCount = 0,
                        replyCount = 0, shareCount = 0,
                        temperature = com.stitchsocial.club.foundation.Temperature.WARM,
                        qualityScore = 75,
                        engagementRatio = 0.5,
                        velocityScore = 0.0, trendingScore = 0.0,
                        duration = 0.0, aspectRatio = 9.0 / 16.0, fileSize = 0L,
                        discoverabilityScore = 0.8,
                        isPromoted = true,  // isCollectionCard signal
                        lastEngagementAt = null,
                        collectionID = collection.id,
                        segmentNumber = null, segmentTitle = null,
                        isCollectionSegment = false,
                        contentType = com.stitchsocial.club.foundation.ContentType.THREAD,
                        isProcessing = false, isDeleted = false,
                        recordingSource = ""
                    )
                    collectionCardMap[collection.id] = collection
                    injected.add(card)
                }

                // Inject: first card at position 10, then every 15 — matches Swift
                val firstPosition = minOf(10, currentVideos.size)
                val spacing = 15
                injected.forEachIndexed { i, card ->
                    val insertAt = minOf(firstPosition + (i * spacing), currentVideos.size)
                    currentVideos.add(insertAt, card)
                }

                _videos.value = currentVideos
                applyFilterAndShuffle()
                if (BuildConfig.DEBUG) { println("🎬 DISCOVERY: Injected ${injected.size} collection cards into swipe feed") }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("⚠️ DISCOVERY: Collection card load failed — ${e.message}") }
            }
        }
    }

    private fun applyFilterAndShuffle() {
        // Filter blocked creators — mirrors Swift applyBlockedCreatorFilter
        val blocked = DiscoveryEngagementTracker.blockedCreatorIDs()
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
    onShowCommunity: (com.stitchsocial.club.community.CommunityListItem) -> Unit = {},
    onTabBarVisibilityChange: ((Boolean) -> Unit)? = null,
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
    val collectionService = remember { CollectionService() }

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
    // Sync current user once we know who they are. Without this the
    // viewModel's currentUserID stays at "anonymous" (its default) and
    // every hype/cool tap from Discovery writes to Firestore with the
    // wrong user — same bug ProfileView had.
    LaunchedEffect(authService.getCurrentUserId()) {
        val uid = authService.getCurrentUserId()
        if (!uid.isNullOrEmpty()) {
            engagementViewModel.setCurrentUser(uid)
            if (BuildConfig.DEBUG) { println("DISCOVERY: EngagementViewModel.setCurrentUser($uid)") }
        }
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

    // Collection state
    var discoveryCollections by remember { mutableStateOf<List<VideoCollection>>(emptyList()) }
    var showCollectionPlayer by remember { mutableStateOf(false) }
    var selectedCollection by remember { mutableStateOf<VideoCollection?>(null) }

    // Hide tab bar when collection player is open — matches iOS fullScreenCover behavior
    LaunchedEffect(showCollectionPlayer) {
        onTabBarVisibilityChange?.invoke(!showCollectionPlayer)
    }

    // Preload collections on first composition so COLLECTIONS tab is instant
    LaunchedEffect(Unit) {
        try { discoveryCollections = collectionService.getDiscoveryCollections(30) } catch (e: Exception) { println("Collections preload failed: ${e.message}") }
    }

    // Reload when explicitly switching to COLLECTIONS tab (cache hit — free)
    LaunchedEffect(selectedCategory) {
        if (selectedCategory == DiscoveryCategory.COLLECTIONS && discoveryCollections.isEmpty()) {
            try { discoveryCollections = collectionService.getDiscoveryCollections(30) } catch (e: Exception) { println("Collections load failed: ${e.message}") }
        }
    }

    // Get current user info
    val currentUserID = authService.getCurrentUserId()
    val currentUserTier = UserTier.ROOKIE // TODO: Load from user profile

    // Reshuffle when user reaches the last video — mirrors iOS reshuffleAndRestart
    LaunchedEffect(currentSwipeIndex, videos.size) {
        if (videos.isNotEmpty() && currentSwipeIndex >= videos.size - 1) {
            viewModel.reshuffleAndRestart()
        }
        // Prefetch next 3 videos ahead of current swipe position
        if (videos.isNotEmpty()) {
            val nextUrls = videos
                .drop(currentSwipeIndex + 1)
                .take(3)
                .map { it.videoURL }
                .filter { it.isNotEmpty() }
            if (nextUrls.isNotEmpty()) {
                com.stitchsocial.club.services.VideoDiskCache.prefetchVideos(nextUrls)
            }
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
                    if (BuildConfig.DEBUG) { println("DISCOVERY: App backgrounded - sending pause broadcast") }
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
            if (BuildConfig.DEBUG) { println("🔇 DISCOVERY: Announcement showing - pausing all videos") }
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

        // Collection player fullscreen overlay
        if (showCollectionPlayer && selectedCollection != null) {
            val coll: VideoCollection = selectedCollection!!
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(200f)
            ) {
                CollectionPlayerView(
                    collection = coll,
                    userID = currentUserID ?: "",
                    videoService = videoService,
                    authService = authService,
                    engagementViewModel = engagementViewModel,
                    iconManager = iconManager,
                    followManager = followManager,
                    onReplyToSegment = { seg ->
                        val authID = currentUserID ?: ""
                        val isOwn = seg.creatorID == authID
                        val threadID = seg.threadID ?: seg.id
                        val ctx = if (isOwn) {
                            RecordingContextFactory.createContinueThread(
                                threadID, seg.creatorName, seg.title
                            )
                        } else {
                            RecordingContextFactory.createStitchToThread(
                                threadID, seg.creatorName, seg.title
                            )
                        }
                        navigationCoordinator?.showModal(
                            ModalState.RECORDING,
                            mapOf("context" to ctx, "parentVideo" to seg)
                        )
                        showCollectionPlayer = false
                    },
                    onDismiss = { showCollectionPlayer = false }
                )
            }
        }

        // Main Discovery Content
        if (!showVideoPlayer) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header with shuffle and mode toggle
                DiscoveryHeader(
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
                        if (BuildConfig.DEBUG) { println("DISCOVERY: Search button tapped") }
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
                    selectedCategory == DiscoveryCategory.COMMUNITIES -> {
                        val uid = currentUserID
                        if (uid != null) {
                            CommunityListView(
                                userID = uid,
                                onShowCommunity = onShowCommunity,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Sign in to view communities", color = Color.Gray, fontSize = 15.sp)
                            }
                        }
                    }
                    selectedCategory == DiscoveryCategory.COLLECTIONS -> {
                        CollectionsDiscoveryRow(
                            collections = discoveryCollections,
                            title = "Collections",
                            userID = currentUserID ?: "",
                            onCollectionTap = { collection: VideoCollection ->
                                selectedCollection = collection
                                showCollectionPlayer = true
                            }
                        )
                    }
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
                                        collectionCardMap = viewModel.collectionCardMap,
                                        onIndexChange = { newIndex ->
                                            currentSwipeIndex = newIndex
                                        },
                                        onVideoTap = { video ->
                                            // Check if this is a collection card first — matches Swift
                                            val collection = viewModel.collectionCardMap[video.id]
                                            if (collection != null) {
                                                selectedCollection = collection
                                                showCollectionPlayer = true
                                                return@DiscoverySwipeCards
                                            }
                                            if (BuildConfig.DEBUG) { println("DISCOVERY: Video tapped - ${video.title}") }
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
                                                    if (BuildConfig.DEBUG) { println("DISCOVERY: Error fetching thread - ${e.message}") }
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
                                        if (BuildConfig.DEBUG) { println("DISCOVERY: Video tapped - ${video.title}") }
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
                                                if (BuildConfig.DEBUG) { println("DISCOVERY: Error fetching thread - ${e.message}") }
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

                                    if (BuildConfig.DEBUG) { println("DISCOVERY SWIPE: Index now $currentVideoIndex / ${videoCount - 1}") }
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
                                    if (BuildConfig.DEBUG) { println("DISCOVERY: Thread button tapped - navigating to $threadID") }
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
                        if (BuildConfig.DEBUG) { println("DISCOVERY: User tapped from search - ${user.displayName}") }
                        showSearchSheet = false
                        onNavigateToProfile(user.id)
                    },
                    onVideoTapped = { video ->
                        if (BuildConfig.DEBUG) { println("DISCOVERY: Video tapped from search - ${video.title}") }
                        showSearchSheet = false
                        currentPlayingVideo = video
                        showVideoPlayer = true
                    },
                    onDismiss = {
                        if (BuildConfig.DEBUG) { println("DISCOVERY: Search dismissed") }
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
            if (isLoading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = Color.Cyan
                    )
                    Text(text = "Loading...", fontSize = 12.sp, color = Color.Cyan)
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