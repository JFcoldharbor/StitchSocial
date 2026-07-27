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
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.viewinterop.AndroidView
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.stitchsocial.club.ui.theme.Spacing
import com.stitchsocial.club.ui.theme.StitchColors
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.stitchsocial.club.R
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.SearchService
import com.stitchsocial.club.services.SponsoredSlotService
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
    // Trimmed to the 5 tabs we keep (2026-07-20). For You is the default feed
    // (renamed from All). Recent/Popular/Following removed.
    FOR_YOU("For You", Icons.Default.Apps),
    EVENTS("Events", Icons.Default.CalendarMonth),
    COMMUNITIES("Community", Icons.Default.Groups),
    COLLECTIONS("Collections", Icons.Default.VideoLibrary),
    TRENDING("Trending", Icons.Default.LocalFireDepartment)
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

    // Sponsored slots — slotID → SponsoredSlot for first-party ad cards injected into the feed.
    // The pseudo CoreVideoMetadata entries live in sponsoredCards ONLY (never in _videos), so
    // they can never enter diversifyShuffle/weighted shuffles — they're re-spaced
    // deterministically after every shuffle by injectSponsoredCards().
    val sponsoredSlotMap = mutableMapOf<String, SponsoredSlot>()
    private val sponsoredSlotService = SponsoredSlotService()
    private var sponsoredCards: List<CoreVideoMetadata> = emptyList()

    private val _currentCategory = MutableStateFlow(DiscoveryCategory.FOR_YOU)
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

    // These MUST be declared BEFORE init{}. init calls loadInitialContent(), and its
    // viewModelScope coroutine (Main.immediate) can run SYNCHRONOUSLY during
    // construction — especially now the VM is created via a retained viewModel{}.
    // If these are declared after init, they're still null/0 at that point:
    //   • db.collection(...) NPEs on a null ref → feed never loads, and
    //   • MAX_LOAD_RETRIES reads 0 → 0 < 0 is false → NO retry, error shown instantly.
    // remember{} happened to defer the dispatch, which hid this ordering bug.
    // Generous retry budget (~10s) rides out the cold-start auth/connection race;
    // capped so a genuine failure can't loop forever.
    private val db = FirebaseFirestore.getInstance("stitchfin")
    private var hasLoaded = false
    private var loadRetries = 0
    private val MAX_LOAD_RETRIES = 6

    init {
        loadInitialContent()
        // Load persisted creator preferences — mirrors Swift .task { await loadPreferences() }
        viewModelScope.launch {
            DiscoveryEngagementTracker.loadPreferences()
        }
    }

    // MARK: - Load All Videos (mirrors iOS — one query, full catalog, shuffle on exhaust)

    fun loadInitialContent(isRetry: Boolean = false) {
        viewModelScope.launch {
            if (_isLoading.value || hasLoaded) return@launch
            // Fresh (user-initiated / first) load resets the retry budget so a manual
            // "try again" gets its own full set of auto-retries; internal auto-retries
            // pass isRetry=true to keep counting down toward the cap.
            if (!isRetry) loadRetries = 0
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
                loadRetries = 0
                _videos.value = combined
                applyFilterAndShuffle()
                if (BuildConfig.DEBUG) { println("✅ DISCOVERY: ${fresh.size} fresh (≤48hr) + ${rest.size} rest = ${combined.size} total") }

                loadFeaturedCollectionsForSwipeFeed()
                loadSponsoredSlots()

                val prefetchUrls = combined.take(3).map { it.videoURL }.filter { it.isNotEmpty() }
                if (prefetchUrls.isNotEmpty()) {
                    com.stitchsocial.club.services.VideoDiskCache.prefetchVideos(prefetchUrls)
                }
            } catch (e: Exception) {
                // Don't flash "Failed to load" while we're about to auto-retry —
                // only surface the visible error once retries are exhausted. But
                // CAP the retries (was an unconditional 2s-delay recursion that
                // could loop forever on a persistent failure) and use a short
                // increasing backoff instead of a flat 2s so a cold-start hiccup
                // doesn't cost a fixed 2s.
                if (loadRetries < MAX_LOAD_RETRIES) {
                    loadRetries++
                    val backoffMs = (600L * loadRetries).coerceAtMost(2000L)  // 0.6,1.2,1.8,2,2,2 ≈ 9.6s
                    if (BuildConfig.DEBUG) { println("❌ DISCOVERY: Load failed: ${e.message} (retry $loadRetries/$MAX_LOAD_RETRIES in ${backoffMs}ms)") }
                    delay(backoffMs)
                    hasLoaded = false
                    _isLoading.value = false
                    _errorMessage.value = null  // keep the slate clean for the retry
                    loadInitialContent(isRetry = true)
                    return@launch
                } else {
                    if (BuildConfig.DEBUG) { println("❌ DISCOVERY: Load failed after $MAX_LOAD_RETRIES retries: ${e.message}") }
                    _errorMessage.value = "Couldn't load videos. Pull to retry."
                }
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
            isCollectionSegment = data["isCollectionSegment"] as? Boolean ?: false,
            eventID = data["eventId"] as? String,
            isEventPromo = data["isEventPromo"] as? Boolean ?: false,
            isEventRecap = data["isEventRecap"] as? Boolean ?: false
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

    /**
     * Fresh order each time you return to Discovery WITHOUT a network reload. The
     * VM is now retained across tab switches (keep-alive), so the feed no longer
     * reloads/reshuffles on re-entry — this reorders the already-loaded list in
     * memory (instant) so returning still feels fresh. No-op on the very first
     * mount (nothing loaded yet); the initial load does its own shuffle.
     */
    fun reshuffleOnReentry() {
        if (hasLoaded && _videos.value.isNotEmpty()) {
            reshuffleAndRestart()
            if (BuildConfig.DEBUG) { println("🔀 DISCOVERY: reshuffled on re-entry (no reload)") }
        }
    }


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

        // EVENTS is a server-backed feed (single-field eventStartAt query), not a
        // slice of the loaded catalog — load it async and set the list directly.
        if (category == DiscoveryCategory.EVENTS) {
            loadUpcomingEvents()
            return
        }

        val allVideos = _videos.value

        val filtered = when (category) {
            DiscoveryCategory.FOR_YOU -> allVideos
            DiscoveryCategory.EVENTS -> allVideos // unreachable — handled above
            DiscoveryCategory.COMMUNITIES -> emptyList() // Handled by CommunityListView
            DiscoveryCategory.TRENDING -> allVideos.filter {
                it.temperature == Temperature.HOT || it.temperature == Temperature.BLAZING
            }
            DiscoveryCategory.COLLECTIONS -> emptyList() // Handled by CollectionsDiscoveryRow
        }

        _filteredVideos.value = injectSponsoredCards(diversifyShuffle(filtered))

        if (BuildConfig.DEBUG) { println("Ã°Å¸â€œÅ  DISCOVERY: Applied ${category.displayName} filter - ${_filteredVideos.value.size} videos") }
    }

    /** Load upcoming + live event heads into the feed (soonest first, no shuffle,
     *  no sponsored injection — events stay chronological). */
    private fun loadUpcomingEvents() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val events = com.stitchsocial.club.events.EventService.getUpcomingEvents(40)
                _filteredVideos.value = events
                if (BuildConfig.DEBUG) { println("EVENT: Discovery Events tab — ${events.size} upcoming/live") }
            } catch (e: Exception) {
                _filteredVideos.value = emptyList()
                if (BuildConfig.DEBUG) { println("EVENT: Discovery Events load failed — ${e.message}") }
            } finally {
                _isLoading.value = false
            }
        }
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

    /**
     * Fetches active sponsored slots (one read per feed load), builds pseudo
     * CoreVideoMetadata cards and stores them in sponsoredCards + sponsoredSlotMap.
     * Mirrors iOS SponsoredSlot injection:
     *   - videoURL = "" — NEVER handed to a player
     *   - thumbnailURL = slot.imageURL — grid/thumbnail paths render the creative for free
     *   - creatorID = "" — guarded everywhere (engagement tracker, avatar fetch)
     *   - isPromoted = true
     */
    private fun loadSponsoredSlots() {
        viewModelScope.launch {
            try {
                val slots = sponsoredSlotService.getActiveSlots(4)
                if (slots.isEmpty()) return@launch

                sponsoredCards = slots.map { slot ->
                    sponsoredSlotMap[slot.id] = slot
                    CoreVideoMetadata(
                        id = slot.id,
                        title = slot.title,
                        description = "",
                        videoURL = "",  // empty = never reaches a player
                        thumbnailURL = slot.imageURL,  // 9:16 creative renders via thumbnail paths
                        creatorID = "",  // pseudo entry — empty creatorID is guarded everywhere
                        creatorName = slot.advertiserName,
                        createdAt = java.util.Date(),
                        threadID = null,
                        replyToVideoID = null,
                        conversationDepth = 0,
                        viewCount = 0, hypeCount = 0, coolCount = 0,
                        replyCount = 0, shareCount = 0,
                        temperature = com.stitchsocial.club.foundation.Temperature.WARM,
                        qualityScore = 75,
                        engagementRatio = 0.5,
                        velocityScore = 0.0, trendingScore = 0.0,
                        duration = 0.0, aspectRatio = 9.0 / 16.0, fileSize = 0L,
                        discoverabilityScore = 0.0,
                        isPromoted = true,  // sponsored signal
                        lastEngagementAt = null,
                        collectionID = null,
                        segmentNumber = null, segmentTitle = null,
                        isCollectionSegment = false,
                        contentType = com.stitchsocial.club.foundation.ContentType.THREAD,
                        isProcessing = false, isDeleted = false,
                        recordingSource = ""
                    )
                }

                applyFilterAndShuffle()  // re-space ads into the current feed
                if (BuildConfig.DEBUG) { println("📣 DISCOVERY: Injected ${sponsoredCards.size} sponsored slot(s) into feed") }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("⚠️ DISCOVERY: Sponsored slot load failed — ${e.message}") }
            }
        }
    }

    /**
     * Deterministically place sponsored cards into an organic feed:
     * first sponsored card within the first 7 items (index 6), then one every 20.
     * Any prior placements are stripped first, so reshuffles re-space instead of
     * duplicating — sponsored cards NEVER participate in any shuffle.
     */
    private fun injectSponsoredCards(feed: List<CoreVideoMetadata>): List<CoreVideoMetadata> {
        if (sponsoredCards.isEmpty()) return feed
        val organic = feed.filterNot { sponsoredSlotMap.containsKey(it.id) }.toMutableList()
        if (organic.isEmpty()) return organic  // no ads in empty/special feeds
        sponsoredCards.forEachIndexed { i, card ->
            val position = minOf(6 + i * 20, organic.size)
            organic.add(position, card)
        }
        return organic
    }

    private fun applyFilterAndShuffle() {
        // Filter blocked creators — mirrors Swift applyBlockedCreatorFilter
        val blocked = DiscoveryEngagementTracker.blockedCreatorIDs()
        _filteredVideos.value = injectSponsoredCards(diversifyShuffle(_videos.value))
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
    val sponsoredSlotService = remember { SponsoredSlotService() }

    // ViewModels
    // Retained in the Activity ViewModelStore (viewModel {}), NOT remember {}. The
    // tab host swaps screens with when(selectedTab), which disposes this composable
    // on every tab switch — with remember the VM (and its loaded feed) was destroyed
    // and fully reloaded from the network each time you returned to Discovery. A
    // retained VM keeps hasLoaded/_videos alive, so re-entry renders instantly.
    val viewModel: DiscoveryViewModel = viewModel {
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

    // Sponsored slot tap: record (session-deduped) + open ctaURL externally — mirrors iOS
    val openSponsoredSlot: (SponsoredSlot) -> Unit = { slot ->
        sponsoredSlotService.recordTap(slot.id)
        if (slot.ctaURL.isNotBlank()) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(slot.ctaURL)))
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("⚠️ SPONSORED: Could not open ctaURL — ${e.message}") }
            }
        }
    }

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
    var selectedCategory by remember { mutableStateOf(DiscoveryCategory.FOR_YOU) }

    // Swipe cards state
    var currentSwipeIndex by remember { mutableStateOf(0) }

    // Fullscreen video state with horizontal navigation
    var showVideoPlayer by remember { mutableStateOf(false) }
    var currentPlayingVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var allVideos by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var currentVideoIndex by remember { mutableStateOf(0) }
    // DECK PAGING: position tracked internally while fullscreen is up; synced
    // back to currentSwipeIndex only at dismiss (live sync makes the hidden
    // card stack rebind players per page = play-pause-play stutter; see iOS).
    var deckPosition by remember { mutableStateOf(0) }

    // Search sheet state
    var showSearchSheet by remember { mutableStateOf(false) }

    // Collection state
    var discoveryCollections by remember { mutableStateOf<List<VideoCollection>>(emptyList()) }
    var showCollectionPlayer by remember { mutableStateOf(false) }
    // Segment index to resume at when the Collections takeover plays something.
    var collectionStartIndex by remember { mutableStateOf(0) }
    var selectedCollection by remember { mutableStateOf<VideoCollection?>(null) }

    // (Tab-bar visibility is driven by a single combined effect below, after all
    // fullscreen surfaces are declared — see LaunchedEffect(showVideoPlayer, ...).)

    // Preload collections on first composition so COLLECTIONS tab is instant
    LaunchedEffect(Unit) {
        try { discoveryCollections = collectionService.getDiscoveryCollections(30) } catch (e: Exception) { println("Collections preload failed: ${e.message}") }
    }

    // Fires once per entry into Discovery. The VM is retained across tab switches,
    // so returning no longer reloads/reshuffles — this reorders the retained feed
    // in memory (instant, no network) so each visit still feels fresh. No-op on the
    // first mount while the initial load is still running.
    LaunchedEffect(Unit) {
        viewModel.reshuffleOnReentry()
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

    // Events — shared VM + a full-screen Hub overlay (hoisted to the Discovery
    // root so the event page takes over the screen like iOS, above the chrome).
    val eventsContext = androidx.compose.ui.platform.LocalContext.current
    val eventsVM = remember { com.stitchsocial.club.events.EventsViewModel() }
    var eventHub by remember { mutableStateOf<com.stitchsocial.club.events.StitchEventEntity?>(null) }
    var eventPlayerVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    LaunchedEffect(currentUserID) {
        val uid = currentUserID ?: ""
        val username = if (uid.isNotBlank())
            runCatching { com.stitchsocial.club.services.UserService(eventsContext).getBasicUserInfo(uid)?.username }.getOrNull() ?: "" else ""
        eventsVM.configure(uid, username)
    }
    LaunchedEffect(selectedCategory) {
        if (selectedCategory == DiscoveryCategory.EVENTS) eventsVM.load()
    }
    // Event deep-link (notification tap, in-app or FCM): only an eventID is
    // parked, so hydrate it before the Hub can open. Gated on isConfigured —
    // configure() lands asynchronously (it awaits a username lookup) and
    // EventsViewModel.currentUserID is a plain var, so a Hub composed before it
    // arrives would read isHost=false and never recompose: the host would get
    // their own event rendered as a guest.
    val deepLinkEventID by com.stitchsocial.club.events.EventDeepLink.pending
    val eventsConfigured by eventsVM.isConfigured.collectAsState()
    LaunchedEffect(deepLinkEventID, eventsConfigured) {
        val id = deepLinkEventID ?: return@LaunchedEffect
        if (!eventsConfigured) return@LaunchedEffect
        com.stitchsocial.club.events.EventDeepLink.consume()
        // Land on Events so dismissing the Hub reveals the events list rather
        // than whatever feed happened to be behind it.
        selectedCategory = DiscoveryCategory.EVENTS
        eventsVM.loadEvent(id)
    }
    // loadEvent is async; present once the entity arrives, then ack the signal.
    val deepLinkedEvent by eventsVM.openEvent.collectAsState()
    LaunchedEffect(deepLinkedEvent) {
        deepLinkedEvent?.let {
            eventHub = it
            eventsVM.clearOpenEvent()
        }
    }
    // Hide the custom tab bar whenever ANY fullscreen surface is up — the
    // fullscreen video deck, the collection player, the event hub, or the
    // community list takeover — so those are truly full screen (matches iOS
    // fullScreenCover). Single combined effect so the surfaces can't race each
    // other on tab-bar visibility.
    //
    // Community and Events are full-screen surfaces: the app tab bar must not be
    // visible on EITHER of them, on any of their screens.
    //
    // Two separate reasons the bar used to show through:
    //  - Community renders its takeover at zIndex 200 *inside* DiscoveryView, but
    //    the tab bar is a sibling of DiscoveryView in MainActivity — zIndex can't
    //    reach across that boundary, so the bar drew on top of the takeover.
    //  - Events was only handled for `eventHub` (the single-event detail). The
    //    Events *browse* surface is the EVENTS category, which renders inline in
    //    the content area below, so it kept the bar.
    // Keying off the category covers every screen within each surface.
    val fullScreenCategoryUp = selectedCategory == DiscoveryCategory.COMMUNITIES ||
        selectedCategory == DiscoveryCategory.EVENTS ||
        selectedCategory == DiscoveryCategory.COLLECTIONS
    LaunchedEffect(showVideoPlayer, showCollectionPlayer, eventHub, fullScreenCategoryUp) {
        onTabBarVisibilityChange?.invoke(
            !showVideoPlayer && !showCollectionPlayer && eventHub == null && !fullScreenCategoryUp
        )
    }

    // Reshuffle when user reaches the last video — mirrors iOS reshuffleAndRestart
    LaunchedEffect(currentSwipeIndex, videos.size) {
        // Sponsored impression: fires when the ad card becomes the active/top card.
        // Service dedupes per app session, so reshuffles never double-count.
        videos.getOrNull(currentSwipeIndex)?.let { active ->
            viewModel.sponsoredSlotMap[active.id]?.let { slot ->
                sponsoredSlotService.recordImpression(slot.id)
            }
        }
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

        // Event Hub — full-screen overlay above the Discovery chrome (iOS fullScreenCover parity).
        eventHub?.let { ev ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(200f)
            ) {
                com.stitchsocial.club.events.EventHubScreen(
                    event = ev,
                    vm = eventsVM,
                    onDismiss = { eventHub = null; eventsVM.load() },
                    // Host Go Live / promo (NewThread) or guest POV (StitchToThread
                    // + parent video): the Hub arms EventMomentBridge, then we open
                    // the recorder. ThreadComposer takes the arm at queue time and
                    // attaches the moment on post.
                    onRecord = { ctx, parent -> navigationCoordinator?.showRecordingModal(ctx, parent) },
                    // Play an event video (promo/recap/moment) fullscreen with the
                    // full engagement overlay — same deck as any other video.
                    onOpenVideo = { eventPlayerVideo = it }
                )
            }
        }

        // Collections — full-screen takeover above the Discovery chrome, same
        // rule as Community/Events. Rendered BEFORE the collection player block
        // below so a tap on a card puts the player on top of the takeover
        // (equal zIndex → later sibling wins), which is what makes play instant.
        if (selectedCategory == DiscoveryCategory.COLLECTIONS) {
            Box(modifier = Modifier.fillMaxSize().zIndex(200f)) {
                CollectionsBrowseView(
                    collections = discoveryCollections,
                    userID = currentUserID ?: "",
                    onClose = { selectedCategory = DiscoveryCategory.FOR_YOU },
                    onPlay = { coll, startIndex ->
                        selectedCollection = coll
                        collectionStartIndex = startIndex
                        showCollectionPlayer = true
                    },
                )
            }
        }

        // Community — full-screen takeover above the Discovery chrome (iOS
        // fullScreenCover parity). CommunityListView pauses the deck on open and
        // its ✕ resets the category back to the feed.
        if (selectedCategory == DiscoveryCategory.COMMUNITIES) {
            val communityUID = currentUserID
            if (communityUID != null) {
                Box(modifier = Modifier.fillMaxSize().zIndex(200f)) {
                    CommunityListView(
                        userID = communityUID,
                        onShowCommunity = onShowCommunity,
                        onClose = { selectedCategory = DiscoveryCategory.FOR_YOU },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Event video fullscreen — the engagement deck, above the Hub (zIndex 300).
        eventPlayerVideo?.let { ev ->
            DiscoveryFullscreenDeck(
                rootVideos = listOf(ev),
                initialVideoID = ev.id,
                currentUserID = currentUserID,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                followManager = followManager,
                navigationCoordinator = navigationCoordinator,
                videoService = videoService,
                isAnnouncementShowing = isAnnouncementShowing,
                onSettledIndexChange = {},
                onDismiss = { eventPlayerVideo = null },
                onNavigateToProfile = { userID, _ -> eventPlayerVideo = null; onNavigateToProfile(userID) },
                onShowThreadView = { threadID, targetVideoID -> onShowThreadView(threadID, targetVideoID) },
                modifier = Modifier.zIndex(300f)
            )
        }

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
                    startingIndex = collectionStartIndex,
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
                        // Rendered as a full-screen takeover overlay above the chrome
                        // (see the Community overlay near the Event Hub). Only the
                        // signed-out fallback shows inline.
                        if (currentUserID == null) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Sign in to view communities", color = Color.Gray, fontSize = 15.sp)
                            }
                        }
                    }
                    selectedCategory == DiscoveryCategory.COLLECTIONS -> {
                        // Rendered as a full-screen takeover overlay above the
                        // chrome (see the Collections overlay near the Community
                        // one). Nothing renders inline any more — the old
                        // single-lane CollectionsDiscoveryRow is superseded by
                        // CollectionsBrowseView. It stays in the codebase because
                        // the profile/creator surfaces still use that lane.
                    }
                    // Events tab = the Concept B rows/hub, not the v1 video feed.
                    selectedCategory == DiscoveryCategory.EVENTS -> {
                        com.stitchsocial.club.events.EventRowsScreen(vm = eventsVM, onOpenEvent = { eventHub = it })
                    }
                    // Show the loading view whenever the feed is empty AND
                    // either we're actively loading OR there's no error.
                    // Covers (a) the initial cold-launch black gap and
                    // (b) the 2-second auto-retry window after a transient
                    // failure — the user shouldn't see "Failed to load"
                    // when the next attempt is already queued.
                    videos.isEmpty() && (isLoading || currentErrorMessage == null) -> {
                        DiscoveryLoadingView()
                    }
                    currentErrorMessage != null && !isLoading -> {
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
                                        sponsoredSlotMap = viewModel.sponsoredSlotMap,
                                        onSponsoredCta = { slot -> openSponsoredSlot(slot) },
                                        onIndexChange = { newIndex ->
                                            currentSwipeIndex = newIndex
                                        },
                                        onVideoTap = { video ->
                                            // Sponsored card — recordTap + open ctaURL, never the player
                                            val slot = viewModel.sponsoredSlotMap[video.id]
                                            if (slot != null) {
                                                openSponsoredSlot(slot)
                                                return@DiscoverySwipeCards
                                            }
                                            // Check if this is a collection card first — matches Swift
                                            val collection = viewModel.collectionCardMap[video.id]
                                            if (collection != null) {
                                                selectedCollection = collection
                                                showCollectionPlayer = true
                                                return@DiscoverySwipeCards
                                            }
                                            if (BuildConfig.DEBUG) { println("DISCOVERY: Video tapped - ${video.title}") }
                                            deckPosition = currentSwipeIndex
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

                                    // Next/Back/Fullscreen instruction pill removed per request.
                                }
                            }
                            DiscoveryMode.GRID -> {
                                DiscoveryGridView(
                                    videos = videos,
                                    sponsoredIds = viewModel.sponsoredSlotMap.keys,
                                    onSponsoredShown = { slotID ->
                                        // Visible in grid = impression (session-deduped)
                                        sponsoredSlotService.recordImpression(slotID)
                                    },
                                    onVideoTapped = { video ->
                                        // Sponsored card — recordTap + open ctaURL, never the player
                                        val slot = viewModel.sponsoredSlotMap[video.id]
                                        if (slot != null) {
                                            openSponsoredSlot(slot)
                                            return@DiscoveryGridView
                                        }
                                        if (BuildConfig.DEBUG) { println("DISCOVERY: Video tapped - ${video.title}") }
                                        deckPosition = videos.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
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

        // Fullscreen video deck — VerticalPager (TikTok-style; mirrors HomeFeedView).
        // The old hand-rolled Animatable deck paged by swapping data + snapTo(0) and
        // re-mounting a fresh ExoPlayer for the new id, so you saw the neighbor as a
        // static thumbnail then a black->buffer->play flash on settle. A VerticalPager
        // keeps a window of pages composed, so the incoming video's player is already
        // prepared (eager prepare()) as it scrolls in and just plays when it lands.
        if (showVideoPlayer && currentPlayingVideo != null && videos.isNotEmpty()) {
            DiscoveryFullscreenDeck(
                rootVideos = videos,
                initialVideoID = currentPlayingVideo!!.id,
                currentUserID = currentUserID,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                followManager = followManager,
                navigationCoordinator = navigationCoordinator,
                videoService = videoService,
                isAnnouncementShowing = isAnnouncementShowing,
                onSettledIndexChange = { idx -> deckPosition = idx },
                onDismiss = { settledIdx ->
                    currentSwipeIndex = settledIdx.coerceIn(0, (videos.size - 1).coerceAtLeast(0))
                    showVideoPlayer = false
                    currentPlayingVideo = null
                    allVideos = emptyList()
                },
                onNavigateToProfile = { userID, settledIdx ->
                    currentSwipeIndex = settledIdx
                    showVideoPlayer = false
                    onNavigateToProfile(userID)
                },
                onShowThreadView = { threadID, targetVideoID ->
                    onShowThreadView(threadID, targetVideoID)
                },
                modifier = Modifier.zIndex(100f)
            )
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
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            // Brand wordmark — logo glyph + "StitchSocial" (iOS parity; "Social"
            // takes the magenta Discovery accent, matching the rest of the header).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.stitchsociallogo),
                    contentDescription = "Stitch Social",
                    modifier = Modifier.size(28.dp)
                )
                Row {
                    Text("Stitch", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                    Text("Social", color = Color.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                }
            }
            if (isLoading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = StitchColors.primary
                    )
                    Text(text = "Loading...", fontSize = 12.sp, color = StitchColors.primary)
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Shuffle button
            IconButton(onClick = onShuffleTapped) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = StitchColors.primary
                )
            }

            // Mode toggle
            IconButton(onClick = onModeToggle) {
                Icon(
                    imageVector = discoveryMode.icon,
                    contentDescription = "Toggle ${discoveryMode.displayName}",
                    tint = if (discoveryMode == DiscoveryMode.SWIPE) StitchColors.primary else Color.White.copy(alpha = 0.7f)
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
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        // Capsule pills: SELECTED = solid magenta fill + white text/icon (filled
        // highlight indicator); unselected = white 0.5 on a faint fill.
        DiscoveryCategory.values().forEach { category ->
            val selected = selectedCategory == category
            val accent = StitchColors.primary
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) accent else Color.White.copy(alpha = 0.06f))
                    .clickable { onCategorySelected(category) }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (selected) Color.White else Color.White.copy(alpha = 0.5f)
                )
                Text(
                    text = category.displayName,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.5f)
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
    onVideoTapped: (CoreVideoMetadata) -> Unit,
    sponsoredIds: Set<String> = emptySet(),
    onSponsoredShown: (String) -> Unit = {}
) {
    val context = LocalContext.current
    // WiFi gate: only autoplay off cellular (raw MP4s are brutal on cellular).
    val allowAutoplay = remember { shouldAutoplay(context) }
    // One tile per row autoplays; the column zigzags via the [0,0,2] cycle.
    val rowCycle = listOf(0, 0, 2)

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(videos.size) { index ->
            val isSponsored = sponsoredIds.contains(videos[index].id)
            // Sponsored pseudo entries have videoURL = "" so the isNotBlank()
            // gate below already excludes them from autoplay selection.
            val row = index / 3
            val isAutoplay = allowAutoplay && videos[index].videoURL.isNotBlank() &&
                    (index % 3 == rowCycle[row % rowCycle.size])
            if (isSponsored) {
                // Tile composed = visible in grid = impression (service dedupes per session)
                LaunchedEffect(videos[index].id) { onSponsoredShown(videos[index].id) }
            }
            DiscoveryVideoCard(
                video = videos[index],
                onTapped = { onVideoTapped(videos[index]) },
                previewVideoURL = if (isAutoplay) videos[index].videoURL else null,
                isSponsored = isSponsored
            )
        }
    }
}

// MARK: - Video Card

@OptIn(UnstableApi::class)
@Composable
private fun DiscoveryVideoCard(
    video: CoreVideoMetadata,
    onTapped: () -> Unit,
    previewVideoURL: String? = null,
    isSponsored: Boolean = false
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onTapped() }
            .background(Color(0xFF1C1C1E))
    ) {
        // Muted player when this tile autoplays, else the thumbnail. Shown as
        // the BASE (not layered under a thumbnail) — a SurfaceView under a Compose
        // thumbnail won't render. Reuses the proven VideoPlayerComposable with
        // managed=false so multiple grid tiles can play concurrently.
        if (previewVideoURL != null) {
            VideoPlayerComposable(
                video = video,
                isActive = true,
                muted = true,
                managed = false,
                onVideoClick = onTapped,
                modifier = Modifier.matchParentSize()
            )
        } else {
            // Thumbnail with a placeholder fallback as the BASE — it shows through
            // while the image loads and stands in when thumbnailURL is missing or
            // fails (mirrors iOS DiscoveryGridView's generated-frame fallback).
            GridThumbnailPlaceholder()
            if (video.thumbnailURL.isNotBlank()) {
                AsyncImage(
                    model = video.thumbnailURL,
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

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

        // Sponsored capsule — first-party ad slot tile (creative renders via thumbnail).
        if (isSponsored) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "SPONSORED",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            }
        }

        // Contest pill — active challenge head (matches the tile's badge style).
        if (video.isChallengeActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(StitchColors.primary)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = "Contest",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Content overlay — HYPE ONLY. Title, @creator, replies, views and the
        // temperature badge were removed per request; the grid shows just heat.
        if (video.hypeCount > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    tint = StitchColors.primary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "${video.hypeCount}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}

/** Fallback shown under a grid thumbnail when thumbnailURL is missing / still
 *  loading — a subtle gradient + film glyph (iOS DiscoveryGridView placeholder). */
@Composable
private fun GridThumbnailPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Movie,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.25f),
            modifier = Modifier.size(30.dp)
        )
    }
}

// MARK: - Grid autoplay helpers

@OptIn(UnstableApi::class)
private fun buildGridPreviewPlayer(context: Context, url: String): ExoPlayer {
    val builder = try {
        ExoPlayer.Builder(context).setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                com.stitchsocial.club.services.VideoDiskCache.buildCacheDataSourceFactory()
            )
        )
    } catch (_: Exception) {
        ExoPlayer.Builder(context)
    }
    return builder.build().apply {
        volume = 0f
        repeatMode = Player.REPEAT_MODE_ONE
        setMediaItem(MediaItem.fromUri(url))
        prepare()
        playWhenReady = true
    }
}

/** Always allow autoplay. HLS ABR now streams fine on cellular (the master
 *  adapts bitrate to the link), so the old cellular suppression is gone —
 *  grid previews autoplay on any transport. */
private fun shouldAutoplay(context: Context): Boolean {
    return true
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
            color = StitchColors.primary,
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
                containerColor = StitchColors.primary,
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

// MARK: - Fullscreen Video Deck (TikTok-style VerticalPager)
//
// Replaces the old hand-rolled Animatable deck. Sponsored / collection
// pseudo-cards carry a blank videoURL and are filtered out so they never reach
// an ExoPlayer. beyondBoundsPageCount = 1 keeps one neighbor on each side
// composed, so the next video's player is prepared before it scrolls in.
@Composable
private fun DiscoveryFullscreenDeck(
    rootVideos: List<CoreVideoMetadata>,
    initialVideoID: String,
    currentUserID: String?,
    engagementViewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    followManager: FollowManager,
    navigationCoordinator: NavigationCoordinator?,
    videoService: VideoServiceImpl,
    isAnnouncementShowing: Boolean,
    onSettledIndexChange: (Int) -> Unit,
    onDismiss: (settledIndex: Int) -> Unit,
    onNavigateToProfile: (userID: String, settledIndex: Int) -> Unit,
    onShowThreadView: (threadID: String, targetVideoID: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val playable = remember(rootVideos) { rootVideos.filter { it.videoURL.isNotBlank() } }
    if (playable.isEmpty()) return

    val initialPage = remember(playable, initialVideoID) {
        playable.indexOfFirst { it.id == initialVideoID }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { playable.size })

    fun currentFullIndex(): Int {
        val settled = playable.getOrNull(pagerState.currentPage) ?: return 0
        return rootVideos.indexOfFirst { it.id == settled.id }.coerceAtLeast(0)
    }

    // Sync the settled page back to the caller's full-list index (hidden swipe
    // cursor + dismiss) and warm the neighbor byte-caches.
    LaunchedEffect(pagerState.currentPage, playable) {
        onSettledIndexChange(currentFullIndex())
        val warm = listOfNotNull(
            playable.getOrNull(pagerState.currentPage + 1)?.videoURL,
            playable.getOrNull(pagerState.currentPage - 1)?.videoURL
        ).filter { it.isNotBlank() }
        if (warm.isNotEmpty()) {
            runCatching { com.stitchsocial.club.services.VideoDiskCache.prefetchVideos(warm) }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val root = playable[page]
            val isCurrentPage = pagerState.currentPage == page
            key(root.id) {
                DiscoveryFullscreenCard(
                    root = root,
                    isCurrentPage = isCurrentPage,
                    isAnnouncementShowing = isAnnouncementShowing,
                    currentUserID = currentUserID,
                    engagementViewModel = engagementViewModel,
                    iconManager = iconManager,
                    followManager = followManager,
                    videoService = videoService,
                    onExit = { onDismiss(currentFullIndex()) },
                    onNavigateToProfile = { userID -> onNavigateToProfile(userID, currentFullIndex()) },
                    onShowThreadView = onShowThreadView,
                    onStitchRecording = { video ->
                        val isOwn = video.creatorID == currentUserID
                        val ctx = if (isOwn) {
                            RecordingContextFactory.createContinueThread(
                                video.threadID ?: video.id, video.creatorName, video.title
                            )
                        } else {
                            RecordingContextFactory.createStitchToThread(
                                video.threadID ?: video.id, video.creatorName, video.title
                            )
                        }
                        navigationCoordinator?.showModal(
                            ModalState.RECORDING,
                            mapOf("context" to ctx, "parentVideo" to video)
                        )
                    }
                )
            }
        }
    }
}

// MARK: - Fullscreen card (one root video + its horizontal reply strip)
@Composable
private fun DiscoveryFullscreenCard(
    root: CoreVideoMetadata,
    isCurrentPage: Boolean,
    isAnnouncementShowing: Boolean,
    currentUserID: String?,
    engagementViewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    followManager: FollowManager,
    videoService: VideoServiceImpl,
    onExit: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onShowThreadView: (threadID: String, targetVideoID: String?) -> Unit,
    onStitchRecording: (CoreVideoMetadata) -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    // Thread reply strip: parent + children, loaded once the card is composed so
    // the horizontal reply swipe is ready by the time the page is current.
    var allVideos by remember(root.id) { mutableStateOf(listOf(root)) }
    LaunchedEffect(root.id) {
        val threadID = root.threadID
        if (threadID != null) {
            runCatching {
                val (parent, children) = videoService.getThreadData(threadID)
                if (parent != null) allVideos = listOf(parent) + children
            }
        }
    }

    val videoCount = allVideos.size
    var currentIndex by remember(root.id) { mutableStateOf(0) }
    val safeIndex = currentIndex.coerceIn(0, (videoCount - 1).coerceAtLeast(0))
    val currentVideo = allVideos.getOrElse(safeIndex) { root }
    val isOnParent = safeIndex == 0

    val offsetX = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val isActive = isCurrentPage && !isDragging && !isAnnouncementShowing

    val dragThreshold = screenWidthPx * 0.3f
    val velocityTracker = remember { VelocityTracker() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(videoCount) {
                if (videoCount <= 1) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        scope.launch {
                            val velocity = velocityTracker.calculateVelocity().x
                            val shouldSnap = kotlin.math.abs(offsetX.value) > dragThreshold ||
                                kotlin.math.abs(velocity) > 1000f
                            if (shouldSnap) {
                                val targetIndex = if (offsetX.value < 0)
                                    (safeIndex + 1).coerceIn(0, videoCount - 1)
                                else
                                    (safeIndex - 1).coerceIn(0, videoCount - 1)
                                offsetX.animateTo(
                                    targetValue = when {
                                        targetIndex > safeIndex -> -screenWidthPx
                                        targetIndex < safeIndex -> screenWidthPx
                                        else -> 0f
                                    },
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                                if (targetIndex != safeIndex) currentIndex = targetIndex
                                offsetX.snapTo(0f)
                            } else {
                                offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                            }
                            isDragging = false
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            offsetX.animateTo(0f)
                            isDragging = false
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        val newOffset = (offsetX.value + dragAmount).coerceIn(
                            if (safeIndex == 0) -screenWidthPx else -screenWidthPx * 1.5f,
                            if (safeIndex == videoCount - 1) screenWidthPx else screenWidthPx * 1.5f
                        )
                        offsetX.snapTo(newOffset)
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value }
        ) {
            key(currentVideo.id) {
                VideoPlayerComposable(
                    video = currentVideo,
                    isActive = isActive,
                    modifier = Modifier.fillMaxSize()
                )
            }

            ContextualVideoOverlay(
                video = currentVideo,
                overlayContext = if (isOnParent) OverlayContext.HOME_FEED else OverlayContext.THREAD_VIEW,
                currentUserID = currentUserID,
                currentUserTier = UserTier.ROOKIE,
                threadVideo = if (!isOnParent) allVideos.firstOrNull() else null,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                followManager = followManager,
                isVisible = !isDragging,
                // Fullscreen hides the tab bar; drop the metadata + actions lower and
                // let the overlay's scrim sit flush to the screen edge.
                bottomPaddingOverride = 42.dp,
                showShareInTop = true,
                onExit = onExit,
                onAction = { action ->
                    when (action) {
                        is OverlayAction.NavigateToProfile -> onNavigateToProfile(action.userID)
                        is OverlayAction.NavigateToThread -> {
                            val threadID = currentVideo.threadID ?: currentVideo.id
                            onShowThreadView(threadID, action.targetVideoID ?: currentVideo.id)
                        }
                        is OverlayAction.StitchRecording -> onStitchRecording(currentVideo)
                        else -> {}
                    }
                }
            )
        }
    }
}
