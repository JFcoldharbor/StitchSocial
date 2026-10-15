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
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    // For You is the default feed (renamed from All). Every case stays for
    // internal filtering and deep links; only visibleTabs are drawn.
    FOR_YOU("For You", Icons.Default.Apps),
    EVENTS("Events", Icons.Default.CalendarMonth),
    COMMUNITIES("Community", Icons.Default.Groups),
    // Shows, not Collections (iOS parity): a viewer is looking for something
    // to watch, and what they are watching is a show.
    COLLECTIONS("Shows", Icons.Default.VideoLibrary),
    TRENDING("Trending", Icons.Default.LocalFireDepartment);

    companion object {
        /**
         * The ONLY chips in the Discover rail — iOS DiscoveryCategory.visibleTabs.
         * Discover is for discovering content, and everything that was not that
         * has left: Community is Dash's, Events has its own tab, and Trending was
         * a filter over the videos For You ranks anyway.
         */
        val visibleTabs = listOf(FOR_YOU, COLLECTIONS)
    }
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

    // Promoted posts (PROMOTED_POSTS_CONTRACT): slotID → the real post behind a
    // slot with a sourceVideoID. A slot present here renders as the post with
    // the full overlay; one missing falls back to the old card.
    val sponsoredPostMap = mutableMapOf<String, CoreVideoMetadata>()
    // Every live slot, in order. The cards actually placed are picked from it
    // on each injection, skipping posts already seen organically this session.
    private var sponsoredPool: List<CoreVideoMetadata> = emptyList()
    private val maxSponsoredCards = 4

    // Invite first-post boosts (INVITE_CONTRACT §4): up to 5 videos whose
    // `reachBoostUntil` is still ahead, mixed in at most 1 per 8 cards near
    // the start. Organic reach — rendered as ordinary posts, no label.
    private var boostedPool: List<CoreVideoMetadata> = emptyList()
    private val maxBoosted = 5
    private val boostedSpacing = 8

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
    /** When the feed was last read. Older than STALE_MS on return → read again (iOS staleDuration). */
    private var loadedAtMs = 0L
    private val STALE_MS = 30L * 60 * 1000
    private var loadRetries = 0
    private val MAX_LOAD_RETRIES = 6
    /** The watched-IDs read rides alongside the catalogue read; past this it's skipped. */
    private val WATCHED_READ_TIMEOUT_MS = 2_500L

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
                // ONE QUERY, the whole catalogue, newest first — iOS
                // DiscoveryViewModel.loadInitialContent. The filters below
                // keep roots only, so this is every public thread.
                //
                // It used to be two capped queries: the newest 50 of the last
                // 48 hours, plus an 80-video feedSeed slice of the rest. After
                // the client filters that was about 50 videos, which then
                // looped for the whole session — and anything without a
                // feedSeed (server-made posts like birthday cards, 129 of 540
                // public threads) could only come in through the 48-hour
                // query. iOS orders by createdAt, which every post has.
                //
                // What this user watched in the last 14 days, read alongside
                // the catalogue so it adds no round trip. Capped and
                // failure-proof: a slow or failed read just means nothing is
                // treated as watched — it never holds up or fails the feed.
                val watchedRead = async {
                    withTimeoutOrNull(WATCHED_READ_TIMEOUT_MS) {
                        com.stitchsocial.club.watchhistory.WatchHistory.recentlyWatchedIDs()
                    }
                }
                val snapshot = db.collection("videos")
                    .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .get().await()
                val cutoffMs = System.currentTimeMillis() - 48L * 60 * 60 * 1000

                fun filterDocs(
                    docs: List<com.google.firebase.firestore.DocumentSnapshot>
                ): List<CoreVideoMetadata> =
                    docs.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        if (data["isDeleted"] as? Boolean == true) return@mapNotNull null
                        if (data["isCollectionSegment"] as? Boolean == true) return@mapNotNull null
                        // Event moments/POVs stay in the room until the host locks
                        // the moment (iOS parity with VideoServiceDiscovery.swift:45).
                        val evID = data["eventId"] as? String
                        if (!evID.isNullOrBlank() &&
                            data["isEventRecap"] as? Boolean != true &&
                            data["isEventPromo"] as? Boolean != true &&
                            data["eventMomentPublished"] as? Boolean != true
                        ) return@mapNotNull null
                        val depth = (data["conversationDepth"] as? Long)?.toInt() ?: 0
                        if (depth > 0) return@mapNotNull null
                        val vis = data["visibility"] as? String ?: "public"
                        if (vis == "private" || vis == "followersOnly") return@mapNotNull null
                        // `visibility` is the CREATOR's audience choice. Moderation
                        // is a SEPARATE field, and this builder never checked it —
                        // so flagged content the scan had already caught still
                        // reached Discovery here. Missing = "public" so legacy
                        // docs are unaffected. See foundation/PublicVisibility.kt.
                        if (!isVideoPubliclyVisible(data)) return@mapNotNull null
                        // Discovery builds its own list here rather than going
                        // through DiscoveryService, so it needs the teen rule
                        // of its own. See services/TeenSafety.kt.
                        if (!com.stitchsocial.club.services.TeenSafety.allows(data)) return@mapNotNull null
                        val url = data["videoURL"] as? String ?: return@mapNotNull null
                        if (url.isBlank()) return@mapNotNull null
                        decodeVideo(data, doc.id)
                    }

                // The last 48 hours first, each half shuffled — iOS shuffleWithRecencyPin.
                val all = filterDocs(snapshot.documents)
                runCatching { watchedRead.await() }
                val watched = com.stitchsocial.club.watchhistory.WatchHistory.watchedIDs
                val unwatched = all.filter { it.id !in watched }
                val fresh = unwatched.filter { it.createdAt.time >= cutoffMs }.shuffled()
                val rest  = unwatched.filter { it.createdAt.time < cutoffMs }.shuffled()
                val seen  = all.filter { it.id in watched }.shuffled()

                // Combine: fresh pinned first, rest shuffled behind — matches
                // Swift — and anything watched in the last 14 days behind both.
                val combined = fresh + rest + seen

                hasLoaded = true
                loadedAtMs = System.currentTimeMillis()
                loadRetries = 0
                _videos.value = combined
                applyFilterAndShuffle()
                if (BuildConfig.DEBUG) { println("✅ DISCOVERY: ${fresh.size} fresh (≤48hr) + ${rest.size} rest + ${seen.size} watched = ${combined.size} total") }

                loadFeaturedCollectionsForSwipeFeed()
                loadSponsoredSlots()
                loadBoostedVideos()

                // From the feed as shown — combined is re-ordered by the shuffle above.
                val prefetchUrls = _filteredVideos.value.map { it.videoURL }.filter { it.isNotEmpty() }.take(3)
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

    /**
     * The organic feed's own document filters (deleted, segment, unpublished
     * event moment, audience, moderation, teen lane, playable URL) — minus the
     * depth rule, since a boosted first post may be a reply.
     */
    private fun passesBoostedFilters(data: Map<String, Any>): Boolean {
        if (data["isDeleted"] as? Boolean == true) return false
        if (data["isCollectionSegment"] as? Boolean == true) return false
        val evID = data["eventId"] as? String
        if (!evID.isNullOrBlank() &&
            data["isEventRecap"] as? Boolean != true &&
            data["isEventPromo"] as? Boolean != true &&
            data["eventMomentPublished"] as? Boolean != true
        ) return false
        val vis = data["visibility"] as? String ?: "public"
        if (vis == "private" || vis == "followersOnly") return false
        if (!isVideoPubliclyVisible(data)) return false
        if (!com.stitchsocial.club.services.TeenSafety.allows(data)) return false
        val url = data["videoURL"] as? String ?: return false
        return url.isNotBlank()
    }

    /**
     * Single-field query (`reachBoostUntil > now` ordered by it) — no composite
     * index. A failure just means no boosted cards this session.
     */
    private fun loadBoostedVideos() {
        viewModelScope.launch {
            try {
                val now = Timestamp(java.util.Date())
                val snap = db.collection("videos")
                    .whereGreaterThan("reachBoostUntil", now)
                    .orderBy("reachBoostUntil")
                    .limit(maxBoosted.toLong())
                    .get().await()
                boostedPool = snap.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    if (!passesBoostedFilters(data)) return@mapNotNull null
                    decodeVideo(data, doc.id)
                }
                if (boostedPool.isNotEmpty() && _currentCategory.value == DiscoveryCategory.FOR_YOU) {
                    applyFilterAndShuffle()
                }
                if (BuildConfig.DEBUG) { println("🚀 DISCOVERY: ${boostedPool.size} invite-boosted video(s) live") }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("⚠️ DISCOVERY: Boosted load failed — ${e.message}") }
            }
        }
    }

    /**
     * Place boosted videos into an organic feed: the k-th one somewhere in
     * cards [8k+2, 8k+7], so at most one per 8 and all of them near the start.
     * Never index 0/1. The same video is removed from the organic part first,
     * so it can't appear twice.
     */
    private fun injectBoosted(feed: List<CoreVideoMetadata>): List<CoreVideoMetadata> {
        if (boostedPool.isEmpty()) return feed
        val blocked = DiscoveryEngagementTracker.blockedCreatorIDs() +
            com.stitchsocial.club.services.BlockService.shared.blockedUserIds.value
        val boosted = boostedPool.filter { it.isReachBoostedNow && it.creatorID !in blocked }
        if (boosted.isEmpty()) return feed
        val boostedIDs = boosted.map { it.id }.toSet()
        val organic = feed.filterNot { it.id in boostedIDs }.toMutableList()
        if (organic.isEmpty()) return organic
        boosted.forEachIndexed { k, video ->
            val index = boostedSpacing * k + (2..boostedSpacing - 1).random()
            organic.add(minOf(index, organic.size), video)
        }
        return organic
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
            // Read it. This was hardcoded COOL, and TRENDING filters on HOT or
            // BLAZING — so the tab could only ever come back empty, whatever the
            // documents said.
            temperature = (data["temperature"] as? String)?.lowercase()
                ?.let { raw -> Temperature.values().firstOrNull { it.rawValue == raw } }
                ?: Temperature.COOL,
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
            isEventRecap = data["isEventRecap"] as? Boolean ?: false,
            eventMomentPublished = data["eventMomentPublished"] as? Boolean ?: false,
            // Collab: who else is on this post, and what their acceptance bought.
            collaboratorIDs = (data["collaboratorIDs"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            participantIDs = (data["participantIDs"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            collaborators = com.stitchsocial.club.services.Collaborator.list(data["collaborators"]),
            collabBoost = com.stitchsocial.club.services.CollabBoost.from(data["collabBoost"]),
            // Landings: drives the "Posted for a perk" label.
            landingID = data["landingID"] as? String,
            landingSpot = data["landingSpot"] as? String,
            incentivized = data["incentivized"] as? Boolean ?: false,
            landingName = (data["landingName"] as? String)?.takeIf { it.isNotBlank() },
            landingSlug = (data["landingSlug"] as? String)?.takeIf { it.isNotBlank() },
            // Promoted posts: the Sponsored label + CTA while a run is attached.
            promotion = com.stitchsocial.club.foundation.VideoPromotion.from(data["promotion"]),
            wasPromoted = data["wasPromoted"] as? Boolean ?: false,
            isFlyer = data["isFlyer"] as? Boolean ?: false,
            reachBoostUntil = (data["reachBoostUntil"] as? Timestamp)?.toDate()
        )
    }


    // MARK: - Reshuffle at end (mirrors iOS reshuffleAndRestart)

    fun reshuffleAndRestart() {
        // Re-apply recency pin on reshuffle — same bucket logic as load —
        // with the watched set as it is NOW, so what was watched since the
        // load (this session's views) also drops to the back.
        _videos.value = watchedLast(_videos.value) { it.shuffled() }
        applyFilterAndShuffle()
        if (BuildConfig.DEBUG) { println("🔀 DISCOVERY: Reshuffled — ${_videos.value.size} videos, watched last") }
    }

    /**
     * For You order: [fresh unwatched] + [older unwatched] + [watched in the
     * last 14 days], each tier arranged by [arrange]. People kept seeing the
     * same videos come round again; anything they haven't seen now comes
     * first. Watched ones stay at the back rather than being dropped, so the
     * feed never runs dry.
     */
    private fun watchedLast(
        videos: List<CoreVideoMetadata>,
        arrange: (List<CoreVideoMetadata>) -> List<CoreVideoMetadata>
    ): List<CoreVideoMetadata> {
        val watched = com.stitchsocial.club.watchhistory.WatchHistory.watchedIDs
        val cutoffMs = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        val (seen, unseen) = videos.partition { it.id in watched }
        val (fresh, rest) = unseen.partition { it.createdAt.time >= cutoffMs }
        return arrange(fresh) + arrange(rest) + arrange(seen)
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
        // Away long enough that there are new posts: read the feed again.
        if (hasLoaded && System.currentTimeMillis() - loadedAtMs > STALE_MS) {
            refreshContent()
            if (BuildConfig.DEBUG) { println("🔄 DISCOVERY: stale on re-entry — reloading") }
            return
        }
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

        // For You keeps its tiers (see watchedLast); diversifying the whole
        // list at once would scatter watched videos back to the front.
        val shuffled = if (category == DiscoveryCategory.FOR_YOU) watchedLast(filtered, ::diversifyShuffle)
            else diversifyShuffle(filtered)
        _filteredVideos.value = injectSponsoredCards(
            if (category == DiscoveryCategory.FOR_YOU) injectBoosted(shuffled) else shuffled
        )

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
     * CoreVideoMetadata cards and stores them in sponsoredPool + sponsoredSlotMap.
     * Mirrors iOS SponsoredSlot injection:
     *   - videoURL = "" — NEVER handed to a player
     *   - thumbnailURL = slot.imageURL — grid/thumbnail paths render the creative for free
     *   - creatorID = "" — guarded everywhere (engagement tracker, avatar fetch)
     *   - isPromoted = true
     *
     * A slot with a sourceVideoID also loads that post (sponsoredPostMap). The
     * pseudo entry stays keyed by the slot id, so impressions and taps work as
     * before; the deck renders the post in its place. If the post can't be
     * loaded (gone, removed, hidden by moderation) the slot falls back to its
     * card, or is dropped when it has no creative of its own.
     */
    private fun loadSponsoredSlots() {
        viewModelScope.launch {
            try {
                // The whole live set, not just four: a slot whose post was
                // already seen organically gives way to the next one.
                val slots = sponsoredSlotService.getActiveSlots(limit = 50)
                if (slots.isEmpty()) return@launch

                val posts: Map<String, CoreVideoMetadata?> = coroutineScope {
                    slots.filter { it.sourceVideoID != null }
                        .map { slot ->
                            async {
                                slot.id to runCatching { videoService.getVideoById(slot.sourceVideoID!!) }
                                    .getOrNull()
                                    ?.takeIf { post ->
                                        !post.isDeleted && post.isPubliclyVisible &&
                                            post.videoURL.isNotBlank()
                                    }
                            }
                        }
                        .awaitAll()
                        .toMap()
                }

                val seenSources = mutableSetOf<String>()
                sponsoredPool = slots.mapNotNull { slot ->
                    val post = posts[slot.id]
                    // The fallback card needs a creative of its own.
                    if (post == null && slot.imageURL.isBlank()) return@mapNotNull null
                    // Two slots on the same post would place it twice.
                    if (post != null && !seenSources.add(post.id)) return@mapNotNull null
                    sponsoredSlotMap[slot.id] = slot
                    if (post != null) sponsoredPostMap[slot.id] = post else sponsoredPostMap.remove(slot.id)
                    CoreVideoMetadata(
                        id = slot.id,
                        title = post?.title ?: slot.title,
                        description = "",
                        videoURL = "",  // empty = never reaches a player
                        // Grid tile: the post's own poster when it has one.
                        thumbnailURL = post?.thumbnailURL?.takeIf { it.isNotBlank() } ?: slot.imageURL,
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
                if (BuildConfig.DEBUG) { println("📣 DISCOVERY: ${sponsoredPool.size} sponsored slot(s) live, ${sponsoredPostMap.size} as posts") }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("⚠️ DISCOVERY: Sponsored slot load failed — ${e.message}") }
            }
        }
    }

    /**
     * The cards to place this time: up to [maxSponsoredCards] from the pool,
     * skipping any whose post this session has already shown organically
     * (PROMOTED_POSTS_CONTRACT de-duplication — take the next slot).
     */
    private fun pickSponsoredCards(): List<CoreVideoMetadata> =
        sponsoredPool.filter { card ->
            val post = sponsoredPostMap[card.id] ?: return@filter true
            !PromotedSessionDedupe.wasShownOrganically(post.id) ||
                PromotedSessionDedupe.wasShownAsAd(post.id)
        }.take(maxSponsoredCards)

    /**
     * Deterministically place sponsored cards into an organic feed:
     * first sponsored card within the first few items, then roughly one every
     * twenty. Any prior placements are stripped first, so reshuffles re-space
     * instead of duplicating — sponsored cards NEVER participate in any shuffle.
     *
     * The positions are RANGES, not fixed indices. They were 6 and 20 exactly,
     * so the same ad sat on card 7 of every session for every user, which reads
     * as furniture rather than as a placement and is the first thing a regular
     * learns to swipe past without looking.
     *
     * Never index 0 or 1: a feed that opens on an ad feels like a different
     * product.
     *
     * A promoted post placed as an ad, or already shown as one this session,
     * is taken out of the organic part so it never appears twice.
     */
    private fun injectSponsoredCards(feed: List<CoreVideoMetadata>): List<CoreVideoMetadata> {
        if (sponsoredPool.isEmpty()) return feed
        sponsoredCards = pickSponsoredCards()
        val adPostIDs = sponsoredCards.mapNotNull { sponsoredPostMap[it.id]?.id }.toSet()
        val organic = feed.filterNot {
            sponsoredSlotMap.containsKey(it.id) ||
                it.id in adPostIDs ||
                PromotedSessionDedupe.wasShownAsAd(it.id)
        }.toMutableList()
        if (organic.isEmpty() || sponsoredCards.isEmpty()) return organic  // no ads in empty/special feeds
        // Drawn fresh per shuffle, and kept monotonic so two cards cannot land
        // on the same index or out of order.
        var cursor = (3..9).random()
        sponsoredCards.forEach { card ->
            organic.add(minOf(cursor, organic.size), card)
            cursor += (15..25).random()
        }
        return organic
    }

    /**
     * One-shot re-read of a single thread head (e.g. after the viewer's own
     * reply to it published), swapped in place — no reshuffle, no reload of
     * the feed. Fixes the cached replyCount the cards were built from.
     */
    fun refreshThreadRoot(threadID: String) {
        if (_videos.value.none { it.id == threadID } &&
            _filteredVideos.value.none { it.id == threadID }) return
        viewModelScope.launch {
            val fresh = runCatching { videoService.getVideoById(threadID) }.getOrNull() ?: return@launch
            if (fresh.isDeleted || !fresh.isPubliclyVisible) return@launch
            _videos.value = _videos.value.map { if (it.id == threadID) fresh else it }
            _filteredVideos.value = _filteredVideos.value.map { if (it.id == threadID) fresh else it }
        }
    }

    private fun applyFilterAndShuffle() {
        // Filter blocked creators — mirrors Swift applyBlockedCreatorFilter.
        // The set was read into a local and then never used: "don't show me this
        // creator" went into the tracker, was persisted, and changed nothing about
        // what the feed handed back.
        val blocked = DiscoveryEngagementTracker.blockedCreatorIDs()
        val visible = if (blocked.isEmpty()) _videos.value
            else _videos.value.filter { it.creatorID !in blocked }
        // Creator variety is applied inside each For You tier, not across
        // them — across them it would undo both the 48-hour pin and the
        // watched-last order the list was just built in.
        val forYou = _currentCategory.value == DiscoveryCategory.FOR_YOU
        val shuffled = if (forYou) watchedLast(visible, ::diversifyShuffle) else diversifyShuffle(visible)
        _filteredVideos.value = injectSponsoredCards(
            if (forYou) injectBoosted(shuffled) else shuffled
        )
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
    /** Opens Inbox, which lives in this toolbar now rather than in the tab bar. */
    onOpenInbox: (() -> Unit)? = null,
    /** Something drawn over Discovery by the host (Inbox) — the feed pauses. */
    externallyCovered: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val density = LocalDensity.current
    // Height of the chrome floating over the feed, measured rather than assumed —
    // the cell needs it to keep the overlay's top row clear of the tab rail.
    var floatingChromeHeight by remember { mutableStateOf(0.dp) }

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

    // The viewer's own reply published: re-read that thread's head once so
    // its cached card (replyCount) is current. The open deck card re-reads
    // its reply strip itself (DiscoveryFullscreenCard).
    OnOwnReplyPublished(matches = { true }) { event -> viewModel.refreshThreadRoot(event.threadID) }

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

    // Sponsored slot tap: record (session-deduped) + open ctaURL externally — mirrors iOS.
    // A Landing-linked promotion (ctaKind "landing_claim") instead opens the
    // Landing in-app and claims its welcome perk (source "sponsored").
    val openSponsoredSlot: (SponsoredSlot) -> Unit = { slot ->
        sponsoredSlotService.recordTap(slot.id)
        if (slot.isLandingClaim) {
            com.stitchsocial.club.landing.LandingPresenter.route(
                slug = slot.landingSlug!!,
                source = com.stitchsocial.club.landing.LandingSource.SPONSORED,
                autoClaim = true
            )
        } else if (slot.ctaURL.isNotBlank()) {
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
    /** Counts player closes, so the browse screen re-reads watch progress. */
    var collectionPlayerCloses by remember { mutableIntStateOf(0) }
    // Segment index to resume at when the Collections takeover plays something.
    var collectionStartIndex by remember { mutableStateOf(0) }
    var selectedCollection by remember { mutableStateOf<VideoCollection?>(null) }
    /** A show's page opened from the Collections takeover's SHOWS rail. */
    var openShowID by remember { mutableStateOf<String?>(null) }

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

    // The Inbox bell's dot. Re-read on entry and whenever Inbox closes, which
    // is when the count can have changed (iOS reads it on the same beats).
    var unreadNotifications by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentUserID, externallyCovered) {
        val uid = currentUserID
        if (uid.isNullOrEmpty() || externallyCovered) return@LaunchedEffect
        unreadNotifications = runCatching {
            com.stitchsocial.club.services.NotificationService().getUnreadCount(uid)
        }.getOrDefault(unreadNotifications)
    }

    // Events moved to a tab of its own (EventsTabView below, iOS parity): its
    // view model, Hub, event player and deep-link handling all live there now.

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
    val anyFullScreenUp = showVideoPlayer || showCollectionPlayer || fullScreenCategoryUp
    LaunchedEffect(anyFullScreenUp) {
        onTabBarVisibilityChange?.invoke(!anyFullScreenUp)
        // Backstop for the paths that don't go through the category tap:
        // deep links (which set the category directly), the fullscreen video
        // deck and the collection player.
        com.stitchsocial.club.foundation.FullScreenSurfaceState.set(anyFullScreenUp)
    }
    // Never leave the bar hidden after Discovery goes away — MainActivity
    // unmounts TabContent whenever a modal opens, which disposes this view.
    DisposableEffect(Unit) {
        onDispose { com.stitchsocial.club.foundation.FullScreenSurfaceState.clear() }
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
            // The end of the whole catalogue: a new order, from the top (iOS
            // reshuffleAndRestart sets swipeIndex = 0).
            viewModel.reshuffleAndRestart()
            currentSwipeIndex = 0
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
                    progressRefreshKey = collectionPlayerCloses,
                    onPlay = { coll, startIndex ->
                        // startIndex > 0 means the user came in via resume — the
                        // whole point of surfacing watch progress, so it's split
                        // out as its own dimension rather than a plain play.
                        com.stitchsocial.club.services.AnalyticsService.collectionPlayed(
                            collectionID = coll.id,
                            contentType = coll.contentType.rawValue,
                            resumed = startIndex > 0,
                        )
                        selectedCollection = coll
                        collectionStartIndex = startIndex
                        showCollectionPlayer = true
                    },
                    onOpenShow = { openShowID = it },
                )
            }
            // The show's page — episodes, this week's schedule, when it airs.
            // Above the takeover, below the player, so playing an episode from
            // it lands on top.
            openShowID?.let { showID ->
                androidx.activity.compose.BackHandler { openShowID = null }
                Box(modifier = Modifier.fillMaxSize().zIndex(200f)) {
                    ShowDetailView(
                        showId = showID,
                        currentUserID = currentUserID ?: "",
                        onDismiss = { openShowID = null },
                        onPlayEpisode = { ep ->
                            openShowID = null
                            selectedCollection = ep
                            collectionStartIndex = 0
                            showCollectionPlayer = true
                        },
                        onResumeEpisode = { ep, index ->
                            openShowID = null
                            selectedCollection = ep
                            collectionStartIndex = index
                            showCollectionPlayer = true
                        },
                    )
                }
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
                    onDismiss = {
                        showCollectionPlayer = false
                        collectionPlayerCloses += 1
                    }
                )
            }
        }

        // Main Discovery Content
        if (!showVideoPlayer) {
            // Full-bleed applies to the feed and nothing else. Grid stays boxed —
            // it is a scanning surface and cannot be edge-to-edge — the takeover
            // categories draw their own screens, and empty / loading / error states
            // stay boxed too: a scrim over no video is just a dark band.
            // (iOS parity — 5b281e5.)
            val isFullBleedFeed = discoveryMode == DiscoveryMode.SWIPE &&
                selectedCategory == DiscoveryCategory.FOR_YOU &&
                selectedHashtag == null &&
                errorMessage == null &&
                videos.isNotEmpty()

            val chromeContent: @Composable ColumnScope.() -> Unit = {
                // Header with shuffle and mode toggle
                DiscoveryHeader(
                    isLoading = isLoading,
                    discoveryMode = discoveryMode,
                    showFeedControls = selectedCategory == DiscoveryCategory.FOR_YOU ||
                        selectedCategory == DiscoveryCategory.TRENDING,
                    unreadCount = unreadNotifications,
                    onInboxTapped = onOpenInbox,
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
                        // Set the full-screen flag SYNCHRONOUSLY here, in the tap
                        // that opens the surface. Doing it in a LaunchedEffect
                        // means the first frame of the takeover can render with
                        // the tab bar still on top.
                        com.stitchsocial.club.foundation.FullScreenSurfaceState.set(
                            category == DiscoveryCategory.COMMUNITIES ||
                                category == DiscoveryCategory.EVENTS ||
                                category == DiscoveryCategory.COLLECTIONS
                        )
                        viewModel.filterBy(category)
                        currentSwipeIndex = 0 // Reset swipe position
                    }
                )

                // Stitch Landings: live restaurants nearby. Only when location
                // is already granted (the row never asks) and only on For You;
                // it renders nothing when there are none.
                if (selectedCategory == DiscoveryCategory.FOR_YOU && selectedHashtag == null) {
                    // The running holiday's thread ("🎃 Show us your costume");
                    // nothing on other days.
                    com.stitchsocial.club.occasion.OccasionDiscoverBanner(navigationCoordinator)
                    com.stitchsocial.club.landing.NearYouLandingsRow(
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }

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
            }

            // The feed, and everything that stands in for it. Takes the clearance
            // it needs under the floating chrome — zero when the chrome is stacked
            // above it instead of over it.
            val mainContent: @Composable (Dp) -> Unit = { topInset ->
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
                    // Events has a tab of its own now (EventsTabView); nothing
                    // selects it here any more.
                    selectedCategory == DiscoveryCategory.EVENTS -> {
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
                                // The feed. This was a stack of inset cards you
                                // swiped sideways, where a tap opened the real
                                // player — the video was a preview of itself. Now
                                // the browsing surface IS the player: one vertical
                                // pager, one video, full bleed, with the same
                                // overlay, thread swipe and peeks the cover had.
                                // (iOS parity — 60b1f77 / f91cf1a.)
                                DiscoveryFullscreenDeck(
                                    rootVideos = videos,
                                    initialVideoID = videos.firstOrNull()?.id ?: "",
                                    initialIndex = currentSwipeIndex,
                                    hosted = true,
                                    topInset = topInset,
                                    currentUserID = currentUserID,
                                    engagementViewModel = engagementViewModel,
                                    iconManager = iconManager,
                                    followManager = followManager,
                                    navigationCoordinator = navigationCoordinator,
                                    videoService = videoService,
                                    // Pauses the feed under anything drawn over
                                    // it. An episode opened from a feed card is an
                                    // overlay, not a new screen, so the feed cell
                                    // played on under the show — two soundtracks,
                                    // and the feed's audio still going on exit.
                                    // Search and Inbox are covers too (iOS
                                    // feedIsCovered): each one opened over a
                                    // feed that kept playing underneath.
                                    isAnnouncementShowing = isAnnouncementShowing || showCollectionPlayer ||
                                        showSearchSheet || externallyCovered,
                                    collectionCardMap = viewModel.collectionCardMap,
                                    sponsoredSlotMap = viewModel.sponsoredSlotMap,
                                    sponsoredPostMap = viewModel.sponsoredPostMap,
                                    onSponsoredCta = { slot -> openSponsoredSlot(slot) },
                                    onSponsoredShown = { slotID -> sponsoredSlotService.recordImpression(slotID) },
                                    onCollectionTap = { collection ->
                                        selectedCollection = collection
                                        showCollectionPlayer = true
                                    },
                                    onSettledIndexChange = { idx -> currentSwipeIndex = idx },
                                    // Hosted: no exit, and profile navigation keeps
                                    // the cursor where the feed left it.
                                    onDismiss = {},
                                    onNavigateToProfile = { userID, settledIdx ->
                                        currentSwipeIndex = settledIdx
                                        onNavigateToProfile(userID)
                                    },
                                    onShowThreadView = { threadID, targetVideoID ->
                                        onShowThreadView(threadID, targetVideoID)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
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
                                        // Sponsored card — recordTap + open ctaURL, never the player.
                                        // A promoted post opens like any post (the deck renders
                                        // it as the ad position, CTA and all).
                                        val slot = viewModel.sponsoredSlotMap[video.id]
                                        if (slot != null && !viewModel.sponsoredPostMap.containsKey(slot.id)) {
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

            if (isFullBleedFeed) {
                Box(modifier = Modifier.fillMaxSize()) {
                    mainContent(floatingChromeHeight)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            // The scrim the floating chrome sits on. Same contract
                            // as the player's own: chrome over video never depends
                            // on the frame behind it being dark.
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.55f),
                                        Color.Black.copy(alpha = 0.22f),
                                        Color.Transparent
                                    )
                                )
                            )
                            // Measured, not guessed: the toolbar and tab rail are
                            // one line of text each, and their height moves with
                            // the device's font scale.
                            .onGloballyPositioned { coords ->
                                val h = with(density) { coords.size.height.toDp() }
                                if (h != floatingChromeHeight) floatingChromeHeight = h
                            },
                        content = chromeContent
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    chromeContent()
                    mainContent(0.dp)
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
                // Only the slots backed by a post: they are pages here. The
                // old cards stay out of the cover, as before.
                sponsoredSlotMap = viewModel.sponsoredSlotMap.filterKeys { it in viewModel.sponsoredPostMap },
                sponsoredPostMap = viewModel.sponsoredPostMap,
                onSponsoredCta = { slot -> openSponsoredSlot(slot) },
                onSponsoredShown = { slotID -> sponsoredSlotService.recordImpression(slotID) },
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
    /** Refresh and the swipe/grid toggle only mean something on a video feed. */
    showFeedControls: Boolean,
    unreadCount: Int,
    onInboxTapped: (() -> Unit)?,
    onShuffleTapped: () -> Unit,
    onModeToggle: () -> Unit,
    onSearchTapped: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A soft fade behind the wordmark and glyphs, so they read over a
            // bright video without boxing the whole bar.
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)))
            .padding(horizontal = Spacing.lg)
            // Top tightened to 2dp against 16 — iOS parity. This sits straight
            // under the status-bar inset, which is already generous, so the
            // padding read as a gap rather than as breathing room. The inset is
            // the floor; below it the wordmark meets the clock.
            .padding(top = 2.dp, bottom = Spacing.md),
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

        // iOS discoveryToolbar: refresh, mode, search, Inbox — cyan glyphs.
        // Packed tight on a dark capsule: spread across the bar over a playing
        // video, the glyphs were both far apart and hard to make out.
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showFeedControls) {
                // Refresh, not shuffle. Same action — it reshuffles and restarts —
                // but "shuffle" named the mechanism, and the only thing a viewer
                // wants from it is different videos.
                HeaderGlyph(Icons.Default.Refresh, "Refresh", Color.Cyan, onShuffleTapped)
                HeaderGlyph(
                    discoveryMode.icon, "Toggle ${discoveryMode.displayName}",
                    if (discoveryMode == DiscoveryMode.SWIPE) Color.Cyan else Color.White.copy(alpha = 0.6f),
                    onModeToggle
                )
            }
            HeaderGlyph(Icons.Default.Search, "Search", Color.Cyan, onSearchTapped)
            // Inbox. It was a fifth of the tab bar; it is a bell beside search,
            // with a dot when something is waiting — which is the only part of a
            // tab a notification list was really using.
            if (onInboxTapped != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onInboxTapped
                        )
                        .semantics {
                            contentDescription = if (unreadCount > 0) "Inbox, $unreadCount unread" else "Inbox"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Notifications, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(22.dp))
                    if (unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-2).dp, y = 3.dp)
                                .size(7.dp)
                                .background(StitchColors.primary, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderGlyph(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

// MARK: - Category Selector (iOS style with icons and underline)

@Composable
private fun DiscoveryCategorySelector(
    selectedCategory: DiscoveryCategory,
    onCategorySelected: (DiscoveryCategory) -> Unit
) {
    // Underlined tabs, not pills. A pill reads as a filter you toggle — an
    // underline reads as a place you are. (iOS parity — 5bd9770.)
    //
    // Two labels fit on every phone, so the rail is centred (iOS parity). The
    // scroll and the right-edge fade stay for when they don't — a large font
    // scale can still overflow it, and a rail that can only be centred breaks
    // when it does.
    val scroll = rememberScrollState()
    val overflows = scroll.maxValue > 0
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = 0.99f }
            .drawWithContent {
                drawContent()
                if (overflows) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black,
                                0.82f to Color.Black,
                                1f to Color.Transparent
                            )
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
    ) {
        val railWidth = maxWidth
        Row(modifier = Modifier.horizontalScroll(scroll)) {
            Row(
                modifier = Modifier
                    .widthIn(min = railWidth)
                    .padding(horizontal = Spacing.lg)
                    .padding(top = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(19.dp, Alignment.CenterHorizontally)
            ) {
                DiscoveryCategory.visibleTabs.forEach { category ->
                    val selected = selectedCategory == category
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // Intrinsic width so the rule below matches the label it underlines.
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .clickable { onCategorySelected(category) }
                    ) {
                        Text(
                            text = category.displayName.uppercase(),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp,
                            maxLines = 1,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.75f),
                            // Every label carries its own contrast floor rather than
                            // borrowing one from a chip background that no longer exists.
                            style = LocalTextStyle.current.copy(
                                shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 4f)
                            )
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(if (selected) DashPalette.railAccent else Color.Transparent)
                        )
                    }
                }
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
        if (isSponsored || video.isPromotedNow) {
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
// Replaces the old hand-rolled Animatable deck. beyondBoundsPageCount = 1 keeps
// one neighbor on each side composed, so the next video's player is prepared
// before it scrolls in.
//
// Two hosts, one deck. `hosted = true` is Discovery's own feed: the deck IS the
// browsing surface, chrome floats over it, and there is no way out because there
// is nothing to exit to. Unhosted it is a cover opened from the grid or search,
// which owns a close button and a dismiss index. iOS made the same call in
// f91cf1a — a browsing deck and a fullscreen deck that differ only in chrome are
// one composable, not two, or they drift.
//
// Sponsored / collection pseudo-cards carry a blank videoURL. They are pages
// here rather than being filtered out: dropping them is what kept ads and
// collections out of the fullscreen feed entirely.
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
    /** Discovery's own feed: no close button, chrome floats above, tap pauses. */
    hosted: Boolean = false,
    /** Height of that floating chrome, so the overlay's top row clears it. */
    topInset: Dp = 0.dp,
    /** Where to open. Takes precedence over initialVideoID when hosted. */
    initialIndex: Int? = null,
    collectionCardMap: Map<String, VideoCollection> = emptyMap(),
    sponsoredSlotMap: Map<String, SponsoredSlot> = emptyMap(),
    /** slotID → the real post behind it (PROMOTED_POSTS_CONTRACT). */
    sponsoredPostMap: Map<String, CoreVideoMetadata> = emptyMap(),
    onSponsoredCta: (SponsoredSlot) -> Unit = {},
    onSponsoredShown: (String) -> Unit = {},
    onCollectionTap: (VideoCollection) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // A page is playable if it has a video, or if it is a pseudo-card that draws
    // itself. Anything else — a decode that produced neither — would be a blank
    // page you can swipe onto.
    val playable = remember(rootVideos, collectionCardMap, sponsoredSlotMap) {
        rootVideos.filter {
            it.videoURL.isNotBlank() ||
                sponsoredSlotMap.containsKey(it.id) ||
                collectionCardMap.containsKey(it.id)
        }
    }
    if (playable.isEmpty()) return

    val initialPage = remember(playable, initialVideoID, initialIndex) {
        val byIndex = initialIndex
            ?.let { idx -> rootVideos.getOrNull(idx)?.id }
            ?.let { id -> playable.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
        byIndex ?: playable.indexOfFirst { it.id == initialVideoID }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { playable.size })
    var horizontalSwipeActive by remember { mutableStateOf(false) }

    // The caller sends the cursor back to 0 when the end of the catalogue
    // reshuffles it (iOS reshuffleAndRestart): follow it to the top. Only 0 —
    // every other index the caller holds is one this pager reported.
    LaunchedEffect(initialIndex) {
        if (initialIndex == 0 && pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }

    fun currentFullIndex(): Int {
        val settled = playable.getOrNull(pagerState.currentPage) ?: return 0
        return rootVideos.indexOfFirst { it.id == settled.id }.coerceAtLeast(0)
    }

    // Discovery's signal tracker used to live on the card deck. The deck is gone,
    // so the feed carries it: same four calls, driven by the pager's settled page
    // instead of a swipe gesture. Only when hosted — a cover opened from the grid
    // is not a browsing session and would start a second one on top of this.
    val tracker = DiscoveryEngagementTracker
    var lastTrackedPage by remember { mutableStateOf(-1) }
    LaunchedEffect(hosted) { if (hosted) tracker.startNewSession() }

    // Sync the settled page back to the caller's full-list index (hidden swipe
    // cursor + dismiss) and warm the neighbor byte-caches.
    LaunchedEffect(pagerState.currentPage, playable) {
        onSettledIndexChange(currentFullIndex())
        if (hosted) {
            val settled = playable.getOrNull(pagerState.currentPage)
            if (settled != null && pagerState.currentPage != lastTrackedPage) {
                // The first page of a session was never swiped away from anything.
                if (lastTrackedPage >= 0) {
                    tracker.cardSwipedAway(wasSwipeBack = pagerState.currentPage < lastTrackedPage)
                }
                lastTrackedPage = pagerState.currentPage
                tracker.cardBecameActive(videoID = settled.id, creatorID = settled.creatorID)
            }
        }
        // Session de-duplication: a post seen here organically is not used for
        // the ad position later (the ad pages mark themselves below).
        playable.getOrNull(pagerState.currentPage)?.let { settled ->
            if (!sponsoredSlotMap.containsKey(settled.id) && !collectionCardMap.containsKey(settled.id)) {
                PromotedSessionDedupe.markOrganic(settled.id)
            }
        }
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
            // No moving to the next post mid-way through a sideways swipe: a
            // swipe that drifted vertically was taken by the pager.
            userScrollEnabled = !horizontalSwipeActive,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val root = playable[page]
            val isCurrentPage = pagerState.currentPage == page
            val sponsored = sponsoredSlotMap[root.id]
            val sponsoredPost = sponsored?.let { sponsoredPostMap[it.id] }
            val collection = collectionCardMap[root.id]
            val onStitchRecording: (CoreVideoMetadata) -> Unit = { video ->
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
            key(root.id) {
                if (sponsored != null && sponsoredPost != null) {
                    // The ad IS the post: the normal cell, overlay and all
                    // (creator, hype, cool, stitch, thread), plus the Sponsored
                    // chip and the slot's CTA. Media fit uses the clear area.
                    DiscoveryFullscreenCard(
                        root = sponsoredPost,
                        isCurrentPage = isCurrentPage,
                        hosted = hosted,
                        topInset = topInset,
                        isAnnouncementShowing = isAnnouncementShowing,
                        currentUserID = currentUserID,
                        engagementViewModel = engagementViewModel,
                        iconManager = iconManager,
                        followManager = followManager,
                        videoService = videoService,
                        onExit = { onDismiss(currentFullIndex()) },
                        onNavigateToProfile = { userID -> onNavigateToProfile(userID, currentFullIndex()) },
                        onShowThreadView = onShowThreadView,
                        onStitchRecording = onStitchRecording,
                        sponsoredSlot = sponsored,
                        onSponsoredCta = { onSponsoredCta(sponsored) },
                        onHorizontalDragChange = { horizontalSwipeActive = it }
                    )
                    // Impression as before (session-deduped by the service),
                    // and the post is now spoken for this session.
                    LaunchedEffect(isCurrentPage) {
                        if (isCurrentPage) {
                            onSponsoredShown(sponsored.id)
                            PromotedSessionDedupe.markAd(sponsoredPost.id)
                        }
                    }
                } else if (sponsored != null) {
                    // Full-bleed, not the inset card the deck used to draw. A
                    // sponsored page is a page like any other now.
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        SponsoredSwipeCard(
                            slot = sponsored,
                            onCtaClick = { onSponsoredCta(sponsored) },
                            modifier = Modifier.fillMaxSize(),
                            isActive = isCurrentPage && !isAnnouncementShowing,
                            // Hosted, Discovery's chrome floats above and the tab
                            // bar sits below — the card used to get neither, so
                            // its creative and CTA ran under both.
                            topInset = if (hosted) topInset else 0.dp,
                            bottomInset = if (hosted) TabBarMetrics.clearance else 0.dp
                        )
                    }
                    // A page you have settled on is an impression. Session-deduped
                    // by the service, so a swipe back does not count twice.
                    LaunchedEffect(isCurrentPage) {
                        if (isCurrentPage) onSponsoredShown(sponsored.id)
                    }
                } else if (collection != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .clickable { onCollectionTap(collection) }
                    ) {
                        CollectionSwipeCard(collection = collection, modifier = Modifier.fillMaxSize())
                    }
                } else DiscoveryFullscreenCard(
                    root = root,
                    isCurrentPage = isCurrentPage,
                    hosted = hosted,
                    topInset = topInset,
                    isAnnouncementShowing = isAnnouncementShowing,
                    currentUserID = currentUserID,
                    engagementViewModel = engagementViewModel,
                    iconManager = iconManager,
                    followManager = followManager,
                    videoService = videoService,
                    onExit = { onDismiss(currentFullIndex()) },
                    onNavigateToProfile = { userID -> onNavigateToProfile(userID, currentFullIndex()) },
                    onShowThreadView = onShowThreadView,
                    onStitchRecording = onStitchRecording,
                    onHorizontalDragChange = { horizontalSwipeActive = it }
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
    /** Discovery's own feed — no exit button, and the tab bar is still there. */
    hosted: Boolean = false,
    /** Clearance for the floating chrome above this cell. */
    topInset: Dp = 0.dp,
    isAnnouncementShowing: Boolean,
    currentUserID: String?,
    engagementViewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    followManager: FollowManager,
    videoService: VideoServiceImpl,
    onExit: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onShowThreadView: (threadID: String, targetVideoID: String?) -> Unit,
    onStitchRecording: (CoreVideoMetadata) -> Unit,
    /**
     * Set when this cell is the Discovery ad position showing a promoted post
     * (PROMOTED_POSTS_CONTRACT). The root then gets the slot's CTA, the
     * Sponsored chip, the clear-area media fit and a "sponsored" view source.
     * Replies swiped to from it are ordinary posts.
     */
    sponsoredSlot: SponsoredSlot? = null,
    onSponsoredCta: () -> Unit = {},
    /** A sideways stitch swipe started (true) or settled (false): the deck locks vertical paging meanwhile. */
    onHorizontalDragChange: (Boolean) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    // Thread reply strip: parent + children, loaded once the card is composed so
    // the horizontal reply swipe is ready by the time the page is current.
    var allVideos by remember(root.id) { mutableStateOf(listOf(root)) }
    // Re-read once (no listener) when the viewer's own reply to this thread
    // publishes or the app returns to the foreground — not just on first
    // composition, which is what left a new reply invisible until relaunch.
    val threadRefreshTick = rememberThreadRefreshTick { it.touches(root.id, root.threadID) }
    LaunchedEffect(root.id, threadRefreshTick) {
        val threadID = root.threadID
        // A promoted reply stays itself in the ad position — its thread's
        // strip would open on the thread head, not the post that was paid for.
        if (sponsoredSlot != null && root.conversationDepth > 0) return@LaunchedEffect
        if (threadID != null) {
            runCatching {
                val (parent, children) = videoService.getThreadData(threadID)
                if (parent != null) allVideos = listOf(parent) + children
            }
        }
    }

    val videoCount = allVideos.size
    // A delegated MutableState, NOT a derived val — and that distinction is why
    // HomeFeed's gesture works and the earlier version here didn't. The drag
    // lambda outlives recomposition; capturing a plain `val safeIndex` freezes
    // it, while reading this delegate goes through the state object every time
    // and always sees the current value.
    var currentIndex by remember(root.id) { mutableStateOf(0) }

    // Warm the neighbours' bytes so a swipe plays from disk instead of waiting
    // on the network — the buffer gap the poster is currently covering.
    //
    // Prefetch the MP4, never the HLS. Downloading an .m3u8 caches the MANIFEST
    // and nothing else; the segments it points at are separate requests, so
    // prefetching HLS has been quietly doing nothing. Legacy videos have no
    // mp4URL and their videoURL IS an MP4, so it caches fine.
    LaunchedEffect(allVideos, currentIndex) {
        val idx = currentIndex.coerceIn(0, (allVideos.size - 1).coerceAtLeast(0))
        listOfNotNull(allVideos.getOrNull(idx + 1), allVideos.getOrNull(idx - 1))
            .mapNotNull { v -> v.mp4URL?.takeIf { it.isNotEmpty() } ?: v.videoURL.takeIf { it.isNotEmpty() } }
            .let { com.stitchsocial.club.services.VideoDiskCache.prefetchVideos(it) }
    }
    val safeIndex = currentIndex.coerceIn(0, (videoCount - 1).coerceAtLeast(0))
    val currentVideo = allVideos.getOrElse(safeIndex) { root }
    val isOnParent = safeIndex == 0

    // Sideways is a pager, exactly like vertical — see StitchPager.
    var isDragging by remember { mutableStateOf(false) }
    val isActive = isCurrentPage && !isAnnouncementShowing

    // Media fit band: below Discovery's floating chrome and above the tab bar
    // when hosted; a cover has neither, so it is the whole screen.
    //
    // The ad position uses the clear area instead: from the chrome down to
    // the top of the overlay's bottom block (CTA + caption + actions),
    // measured at runtime, 12dp in from the sides.
    val isAdOnScreen = sponsoredSlot != null && isOnParent
    var adBottomBlockHeight by remember(root.id) { mutableStateOf(0.dp) }
    val fitBand = if (isAdOnScreen) MediaFitBand.clearArea(
        top = if (hosted) topInset else 0.dp,
        bottom = adBottomBlockHeight
    ) else MediaFitBand(
        top = if (hosted) topInset else 0.dp,
        bottom = if (hosted) TabBarMetrics.clearance else 0.dp
    )
    val fitOverride: String? = if (isAdOnScreen) {
        sponsoredSlot?.fit?.takeIf { it != "auto" } ?: currentVideo.promotion?.fit
    } else null
    // Media-fit band for a stitch that isn't the ad position.
    val plainBand = MediaFitBand(
        top = if (hosted) topInset else 0.dp,
        bottom = if (hosted) TabBarMetrics.clearance else 0.dp
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        StitchPager(
            videos = allVideos,
            index = currentIndex,
            onIndexChange = { currentIndex = it },
            isOnScreen = isCurrentPage,
            isPaused = isAnnouncementShowing,
            fitBandFor = { page -> if (page == 0 && isAdOnScreen) fitBand else plainBand },
            fitOverrideFor = { page -> if (page == 0 && isAdOnScreen) fitOverride else null },
            onScrollingChange = { isDragging = it; onHorizontalDragChange(it) }
        )

        // The overlay and the edge peeks stand still while the video slides under
        // them, the way iOS draws one overlay over the strip — share, the three
        // dots, the creator and the actions don't ride the swipe, and they switch
        // to the next stitch when it lands. They used to sit inside the sliding
        // layer and hide for the whole drag.
        Box(modifier = Modifier.fillMaxSize()) {
            // Hosted, the chrome above is Discovery's own and the tab bar is still
            // below, so the overlay keeps HomeFeed's bottom clearance and is pushed
            // down past the toolbar and tabs. Top padding only — the bottom edge of
            // this box is still the bottom of the screen, so nothing moves up.
            Box(modifier = Modifier.fillMaxSize().padding(top = if (hosted) topInset else 0.dp)) {
            ContextualVideoOverlay(
                video = currentVideo,
                overlayContext = if (isOnParent) OverlayContext.HOME_FEED else OverlayContext.THREAD_VIEW,
                currentUserID = currentUserID,
                currentUserTier = UserTier.ROOKIE,
                threadVideo = if (!isOnParent) allVideos.firstOrNull() else null,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                followManager = followManager,
                isVisible = true,
                // A cover hides the tab bar: drop the metadata + actions lower and
                // let the overlay's scrim sit flush to the screen edge. Hosted, the
                // tab bar is there and the default clearance is the right one.
                bottomPaddingOverride = if (hosted) null else 28.dp,
                // No close button on the browsing feed — there is nothing to close.
                showShareInTop = !hosted,
                onExit = if (hosted) null else onExit,
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
                },
                sponsoredCta = if (isAdOnScreen && sponsoredSlot != null) {
                    SponsoredCta(sponsoredSlot.ctaText) { onSponsoredCta() }
                } else null,
                viewSource = if (isAdOnScreen) "sponsored" else null,
                onBottomBlockHeight = if (isAdOnScreen) ({ h ->
                    if (h != adBottomBlockHeight) adBottomBlockHeight = h
                }) else null,
                // A tile in the thread rail (iOS onSelectStitch): slide this
                // deck's strip to it; a clip the strip doesn't hold opens in
                // the thread player, focused on it.
                onSelectStitch = { target ->
                    val idx = allVideos.indexOfFirst { it.id == target.id }
                    if (idx >= 0) currentIndex = idx
                    else onShowThreadView(target.threadID ?: currentVideo.threadID ?: currentVideo.id, target.id)
                    true
                }
            )
            }

            // Edge peeks. This deck has had a horizontal reply swipe since it
            // was rewritten to a pager, but NOTHING on screen said so — no
            // affordance, so the gesture was undiscoverable unless you already
            // knew. HomeFeedView and ThreadView both show these; the rewrite
            // dropped them here.
            //
            // Above the video, below the overlay: the overlay owns the taps that
            // matter (profile, stitch, exit) and must win any overlap.
            if (allVideos.size > 1) {
                VideoNavigationPeeks(
                    allVideos = allVideos,
                    currentVideoIndex = currentIndex,
                    onTapPrevious = { if (currentIndex > 0) currentIndex -= 1 },
                    onTapNext = { if (currentIndex < allVideos.size - 1) currentIndex += 1 },
                    // The thread rail owns the right edge (iOS 8a).
                    showsNextPeek = false,
                    // Hidden while the pager moves so a peek doesn't slide across
                    // the incoming video.
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isDragging) 0f else 1f)
                )
            }
        }
    }
}

// MARK: - Events tab (iOS parity: Events is a tab of its own)

/**
 * The Events tab root. This was the EVENTS category inside Discover — a
 * takeover over the feed with its own close button. It is a tab now, like iOS
 * (EventRowsView in ContentView), so the rows have nowhere to close to and the
 * tab bar stays up under them. The Hub and an event video are still full
 * screen over the tab, and this is where event deep links land.
 *
 * Lives in this file because an event video plays in DiscoveryFullscreenDeck,
 * the same deck as every other video.
 */
@Composable
fun EventsTabView(
    navigationCoordinator: NavigationCoordinator?,
    isAnnouncementShowing: Boolean,
    onNavigateToProfile: (String) -> Unit,
    onShowThreadView: (threadID: String, targetVideoID: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authService = remember { AuthService() }
    val videoService = remember { VideoServiceImpl() }
    val userService = remember { UserService(context) }
    val engagementViewModel = remember {
        EngagementViewModel(authService = authService, videoService = videoService, userService = userService)
    }
    val iconManager = remember { FloatingIconManager() }
    val followManager = remember { FollowManager(context) }
    val currentUserID = authService.getCurrentUserId()

    val eventsVM = remember { com.stitchsocial.club.events.EventsViewModel() }
    var eventHub by remember { mutableStateOf<com.stitchsocial.club.events.StitchEventEntity?>(null) }
    var eventPlayerVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }

    LaunchedEffect(currentUserID) {
        val uid = currentUserID ?: ""
        if (uid.isNotBlank()) engagementViewModel.setCurrentUser(uid)
        val username = if (uid.isNotBlank())
            runCatching { userService.getBasicUserInfo(uid)?.username }.getOrNull() ?: "" else ""
        eventsVM.configure(uid, username)
        eventsVM.load()
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

    // The Hub and an event video are full screen: the tab bar steps aside.
    val coverUp = eventHub != null || eventPlayerVideo != null
    LaunchedEffect(coverUp) { com.stitchsocial.club.foundation.FullScreenSurfaceState.set(coverUp) }
    DisposableEffect(Unit) {
        onDispose { com.stitchsocial.club.foundation.FullScreenSurfaceState.clear() }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // No onClose: this is a tab root, not a takeover.
        com.stitchsocial.club.events.EventRowsScreen(
            vm = eventsVM,
            onOpenEvent = { eventHub = it },
        )

        eventHub?.let { ev ->
            Box(modifier = Modifier.fillMaxSize().zIndex(300f)) {
                com.stitchsocial.club.events.EventHubScreen(
                    event = ev,
                    vm = eventsVM,
                    onDismiss = { eventHub = null; eventsVM.load() },
                    // Host Go Live / promo (NewThread) or guest POV (StitchToThread
                    // + parent video): the Hub arms EventMomentBridge, then we open
                    // the recorder. ThreadComposer takes the arm at queue time and
                    // attaches the moment on post.
                    onRecord = { ctx, parent -> navigationCoordinator?.showRecordingModal(ctx, parent) },
                    onOpenVideo = { eventPlayerVideo = it }
                )
            }
        }

        // Event video fullscreen — the engagement deck, above the Hub.
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
                modifier = Modifier.zIndex(310f)
            )
        }
    }
}
