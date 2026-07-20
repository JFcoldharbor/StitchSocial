/*
 * ProfileView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - User Profile (iOS Parity)
 * ✅ Enhanced profile image with hype progress ring
 * ✅ Tier gradient borders + badges
 * ✅ Clickable stats (Stitchers opens StitchersListView)
 * ✅ FollowManager integration
 * ✅ Action buttons: Edit+Settings (own) / Follow+Subscribe (other)
 * ✅ Close button overlay for other profiles
 * ✅ Tab bar with icons + sticky tab bar on scroll
 * ✅ Pinned videos support + infinite scroll pagination
 * ✅ Collections row placeholder
 * ✅ Bio section with "Add bio" prompt
 * ✅ Contextual bio generation for verified/founder
 * ✅ Video player fullscreen overlay
 * ✅ Video deletion with confirmation
 * ✅ Swipe-to-dismiss for other profiles
 * ✅ Error view with retry
 */

package com.stitchsocial.club.views

import com.stitchsocial.club.ui.theme.AppTheme
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import com.stitchsocial.club.services.AdRevenueShare
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

// Foundation
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.BasicVideoInfo
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.CoinPriceTier
import kotlin.math.log10
import androidx.compose.foundation.BorderStroke
import com.stitchsocial.club.ui.theme.Spacing
import com.stitchsocial.club.ui.theme.StitchColors
import com.stitchsocial.club.services.StreakService
import com.stitchsocial.club.services.SubscriptionService
import com.stitchsocial.club.ui.theme.color

// Services
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.CollectionService
import com.stitchsocial.club.services.ShowService
import com.stitchsocial.club.foundation.VideoCollection

// Coordination
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.coordination.NavigationEvent
import com.stitchsocial.club.coordination.EngagementCoordinator

// ViewModels
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// Views
import com.stitchsocial.club.ProfileVideoGrid
import com.stitchsocial.club.views.FullscreenVideoPlayer
import com.stitchsocial.club.views.VideoInfo
import com.stitchsocial.club.views.OverlayContext
import com.stitchsocial.club.views.OverlayAction
import com.stitchsocial.club.views.ContextualVideoOverlay
import com.stitchsocial.club.views.VideoPlayerComposable
import com.stitchsocial.club.camera.RecordingContextFactory
import com.stitchsocial.club.FollowManager
import com.stitchsocial.club.BuildConfig

// ===== HELPER FUNCTIONS =====

// Canonical tier-color gradient (2 stops from UserTier.color). Was a duplicated,
// inconsistent per-tier switch — now matches the brand spectrum everywhere.
private fun getTierColors(tier: UserTier): List<Color> =
    listOf(tier.color, tier.color.copy(alpha = 0.7f))

private fun getTierIcon(tier: UserTier): ImageVector {
    return when (tier) {
        UserTier.ROOKIE -> Icons.Default.Person
        UserTier.RISING -> Icons.Default.TrendingUp
        UserTier.VETERAN -> Icons.Default.Shield
        UserTier.INFLUENCER -> Icons.Default.Star
        UserTier.AMBASSADOR -> Icons.Default.Public
        UserTier.ELITE -> Icons.Default.Diamond
        UserTier.PARTNER -> Icons.Default.Handshake
        UserTier.LEGENDARY -> Icons.Default.EmojiEvents
        UserTier.TOP_CREATOR -> Icons.Default.WorkspacePremium
        UserTier.FOUNDER -> Icons.Default.Verified
        UserTier.CO_FOUNDER -> Icons.Default.Verified
        UserTier.BUSINESS -> Icons.Default.Business
    }
}

private fun calculateHypeLevel(user: BasicUserInfo, videos: List<CoreVideoMetadata> = emptyList(), followerCount: Int = 0): Float {
    // Server-authoritative hype rating — mirrors iOS ProfileView.calculateHypeRating.
    // Reads the SERVER aggregates (totalHypesReceived / totalCoolsReceived, kept
    // current by the aggregateCreatorEngagement Cloud Function) so every viewer of
    // the same profile computes the identical number. `videos` is intentionally
    // unused now — it used to be a per-page local sum that differed per viewer.
    val defaultStartingClout = 1500.0  // OptimizationConfig.User.defaultStartingClout

    // Tier base rating (iOS tierBaseRating)
    val tierBase = when (user.tier) {
        UserTier.FOUNDER, UserTier.CO_FOUNDER -> defaultStartingClout * 0.063
        UserTier.TOP_CREATOR -> defaultStartingClout * 0.057
        UserTier.PARTNER -> defaultStartingClout * 0.050
        UserTier.ELITE -> defaultStartingClout * 0.045
        UserTier.AMBASSADOR -> defaultStartingClout * 0.043
        UserTier.INFLUENCER -> defaultStartingClout * 0.043
        UserTier.VETERAN -> defaultStartingClout * 0.038
        UserTier.RISING -> defaultStartingClout * 0.033
        UserTier.ROOKIE -> defaultStartingClout * 0.023
        else -> defaultStartingClout * 0.017
    }

    // Starting-bonus amount (iOS getUserStartingBonus -> UserStartingBonus.bonusAmount).
    // A freshly-built HypeRating's effectiveRating is baseRating + bonusAmount
    // (decay ~1, bonus unexpired), so this is an additive bonus, NOT a multiplier.
    val startingBonusAmount = when {
        user.isVerified -> 75.0  // betaTester
        user.tier == UserTier.FOUNDER || user.tier == UserTier.CO_FOUNDER -> 50.0  // earlyAdopter
        else -> 10.0             // newcomer
    }
    val effectiveRating = tierBase + startingBonusAmount

    // Engagement score from SERVER aggregates (iOS engagementScore)
    val totalHypes = user.totalHypesReceived
    val totalCools = user.totalCoolsReceived
    val totalReactions = totalHypes + totalCools

    // Quality: share of reactions that are positive (neutral 0.5 when none).
    val engagementRatio = if (totalReactions > 0) totalHypes.toDouble() / totalReactions else 0.5
    val ratioPoints = engagementRatio * 10.0 * 1.5  // InteractionType.hype.pointValue = 10
    // Volume: rewards reach with diminishing returns (log curve, capped at 25).
    val volumePoints = minOf(25.0, log10(totalReactions.toDouble() + 1.0) * 8.0)
    val engagementScore = ratioPoints + volumePoints

    // Clout bonus
    val cloutBonus = minOf(10.0, (user.clout ?: 0).toDouble() / defaultStartingClout * 10.0)

    // Social bonus (iOS threshold = OptimizationConfig.Performance.maxBackgroundTasks)
    val actualFollowers = if (followerCount > 0) followerCount else (user.followerCount ?: 0)
    val socialBonus = minOf(8.0, actualFollowers.toDouble() / 5.0 * 8.0)

    // Verification bonus
    val verificationBonus = if (user.isVerified) 5.0 else 0.0

    // Final calculation (iOS finalRating)
    val baseRating = effectiveRating / 100.0 * 50.0
    val bonusPoints = engagementScore + cloutBonus + socialBonus + verificationBonus
    val finalRating = baseRating + bonusPoints

    return finalRating.coerceIn(0.0, 100.0).toFloat()
}

private fun formatLargeNumber(num: Int): String {
    return when {
        num >= 1_000_000 -> "%.1fM".format(num / 1_000_000.0).replace(".0", "")
        num >= 1_000 -> "%.1fK".format(num / 1_000.0).replace(".0", "")
        else -> num.toString()
    }
}

private fun getBioText(user: BasicUserInfo): String {
    return user.bio?.trim() ?: ""
}

/** iOS parity: generate contextual bio for verified/founder users with no bio */
private fun generateContextualBio(user: BasicUserInfo): String? {
    val bio = user.bio?.trim()
    if (!bio.isNullOrEmpty()) return null

    return when {
        user.tier == UserTier.FOUNDER ->
            "Building the future of social video \uD83D\uDE80 | Creator of Stitch Social"
        user.tier == UserTier.CO_FOUNDER ->
            "Co-founder at Stitch Social | Passionate about connecting creators \uD83C\uDFAC"
        user.tier == UserTier.TOP_CREATOR ->
            "Top creator with ${formatLargeNumber(user.clout ?: 0)} clout | Making viral content daily \u2728"
        user.tier == UserTier.PARTNER ->
            "Official partner creator | ${user.videoCount ?: 0} threads and counting \uD83D\uDD25"
        user.isVerified -> {
            val parts = mutableListOf<String>()
            if ((user.clout ?: 0) > 500) parts.add("\uD83C\uDF1F High performer")
            if ((user.videoCount ?: 0) >= 10) parts.add("\uD83D\uDCF9 Active creator")
            if ((user.followerCount ?: 0) >= 100) parts.add("\uD83D\uDC65 Community leader")
            if (user.tier != UserTier.ROOKIE) parts.add("\uD83D\uDE80 ${user.tier.displayName}")
            parts.add("\u2705 Verified")
            parts.joinToString(" | ")
        }
        else -> null
    }
}

// Tab config matching iOS
private data class ProfileTab(val title: String, val icon: ImageVector)

private val profileTabs = listOf(
    ProfileTab("Threads", Icons.Default.ViewList),
    ProfileTab("Stitches", Icons.Default.ContentCut),
    ProfileTab("Replies", Icons.Default.Reply)
)

// ===== MAIN PROFILE VIEW =====

/**
 * Retains loaded profile data across tab switches / modal re-opens. The tab host
 * disposes ProfileView on every switch, so without this the own profile re-fetched
 * user + videos + pinned + collections from the network on each return. Keyed by
 * userID (small LRU cap) so the own tab AND recently-viewed profiles restore
 * instantly. Activity-scoped via viewModel().
 */
class ProfileCacheViewModel : ViewModel() {
    data class Entry(
        val user: com.stitchsocial.club.foundation.BasicUserInfo?,
        val videos: List<com.stitchsocial.club.foundation.CoreVideoMetadata>,
        val pinned: List<com.stitchsocial.club.foundation.CoreVideoMetadata>,
        val collections: List<com.stitchsocial.club.foundation.VideoCollection>
    )
    private val cache = LinkedHashMap<String, Entry>()
    private val cap = 6
    fun get(userID: String): Entry? = synchronized(cache) { cache[userID] }
    fun put(userID: String, e: Entry) = synchronized(cache) {
        cache.remove(userID); cache[userID] = e
        while (cache.size > cap) cache.remove(cache.keys.first())
    }
    fun invalidate(userID: String) = synchronized(cache) { cache.remove(userID) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileView(
    userID: String,
    viewingUserID: String? = null,
    navigationCoordinator: NavigationCoordinator? = null,
    engagementCoordinator: EngagementCoordinator? = null,
    engagementViewModel: EngagementViewModel? = null,
    onShowThreadView: (threadID: String, targetVideoID: String?) -> Unit = { _, _ -> },
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Services
    val userService = remember { UserService(context) }
    val videoService = remember { VideoServiceImpl() }
    val authService = remember { AuthService() }
    val followManager = remember { FollowManager(context) }

    val collectionService = remember { CollectionService() }
    val viewModel = engagementViewModel ?: remember {
        EngagementViewModel(authService, videoService, userService)
    }
    val iconManager = remember { FloatingIconManager() }

    // CRITICAL: EngagementViewModel.currentUserID defaults to "anonymous"
    // and is only updated via setCurrentUser(). HomeFeedView calls this at
    // its line 91; ProfileView never did, so every hype/cool tap from a
    // profile-launched player wrote to Firestore as "anonymous" — breaking
    // per-user lock keys, clout attribution, and state persistence. This
    // syncs the viewModel to the actual auth user as soon as we know who
    // they are.
    val authUserID = authService.currentUser.collectAsState().value?.uid
    LaunchedEffect(authUserID) {
        if (!authUserID.isNullOrEmpty()) {
            viewModel.setCurrentUser(authUserID)
            if (BuildConfig.DEBUG) { println("PROFILE: EngagementViewModel.setCurrentUser($authUserID)") }
        }
    }

    // Own profile check
    val currentAuthUserID = authService.currentUser.collectAsState().value?.uid
    val isOwnProfile = viewingUserID == null || viewingUserID == userID || currentAuthUserID == userID

    // Follow states
    val followingStates by followManager.followingStates.collectAsState()
    val loadingStates by followManager.loadingStates.collectAsState()
    val isFollowing = followingStates[userID] ?: false
    val isFollowLoading = loadingStates.contains(userID)

    // Retained profile cache (Activity-scoped) — restores instantly on tab re-entry
    // / modal re-open instead of re-fetching. Keyed by userID. See ProfileCacheViewModel.
    val profileCache: ProfileCacheViewModel = viewModel()
    val cachedProfile = remember(userID) { profileCache.get(userID) }

    // Profile state — seeded from cache when present (instant, no spinner).
    var currentUser by remember(userID) { mutableStateOf(cachedProfile?.user) }
    var userVideos by remember(userID) { mutableStateOf(cachedProfile?.videos ?: emptyList()) }
    var pinnedVideos by remember(userID) { mutableStateOf(cachedProfile?.pinned ?: emptyList()) }
    var isLoading by remember(userID) { mutableStateOf(cachedProfile == null) }
    var isLoadingVideos by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMoreVideos by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // UI state
    var isShowingFullBio by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var showingEditProfile by remember { mutableStateOf(false) }
    var showingSettings by remember { mutableStateOf(false) }
    var showingSavedVideos by remember { mutableStateOf(false) }
    var showingAdOpportunities by remember { mutableStateOf(false) }
    var showingBadgePage by remember { mutableStateOf(false) }

    // Video player state
    var showingVideoPlayer by remember { mutableStateOf(false) }
    var selectedVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var selectedVideoIndex by remember { mutableStateOf(0) }
    var currentVideoList by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }

    // Delete state
    var videoToDelete by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var showingDeleteConfirmation by remember { mutableStateOf(false) }

    // Stitchers sheet state
    var showStitchersSheet by remember { mutableStateOf(false) }

    // Age gate state — own profile only
    var showingBirthdayPrompt by remember { mutableStateOf(false) }
    var showingUnder13Block by remember { mutableStateOf(false) }
    var teenLocked by remember { mutableStateOf(false) }
    var ageGateChecked by remember { mutableStateOf(false) }

    // Collections state
    var userCollections by remember(userID) { mutableStateOf(cachedProfile?.collections ?: emptyList()) }
    var showCollectionPlayer by remember { mutableStateOf(false) }
    var selectedCollection by remember { mutableStateOf<VideoCollection?>(null) }
    var showingAllCollections by remember { mutableStateOf(false) }
    var selectedShowId by remember { mutableStateOf<String?>(null) }
    var selectedShowEpisodes by remember { mutableStateOf<List<VideoCollection>>(emptyList()) }
    var showingShowDetail by remember { mutableStateOf(false) }

    val scrollState = rememberLazyListState()

    // Filtered videos
    val filteredVideos = remember(userVideos, pinnedVideos, selectedTab) {
        val pinnedIDs = pinnedVideos.map { it.id }.toSet()
        when (selectedTab) {
            // Videos tab — pinned videos lead, then unpinned parents (iOS parity)
            0 -> pinnedVideos + userVideos.filter { it.conversationDepth == 0 && !pinnedIDs.contains(it.id) }
            1 -> userVideos.filter { it.conversationDepth == 1 }
            2 -> userVideos.filter { it.conversationDepth >= 2 }
            else -> userVideos
        }
    }

    // Load functions
    suspend fun loadUser() {
        isLoading = true
        errorMessage = null
        try {
            currentUser = userService.getUserProfile(userID)
            if (currentUser == null) errorMessage = "User not found"
            if (!isOwnProfile) followManager.loadFollowState(userID)
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load profile"
        } finally {
            isLoading = false
        }
    }

    suspend fun loadVideos() {
        isLoadingVideos = true
        try {
            // Fetch newest 150 (ordered server-side) so Threads/Stitches/Replies
            // all populate — the old arbitrary 50 starved the depth-1/2 tabs.
            userVideos = videoService.getUserVideos(userID, limit = 150)
            hasMoreVideos = userVideos.size >= 150
        } catch (_: Exception) { } finally {
            isLoadingVideos = false
        }
    }

    suspend fun loadPinnedVideos() {
        try {
            // Read pinnedVideoIDs from user document directly
            val userDoc = com.google.firebase.firestore.FirebaseFirestore.getInstance("stitchfin")
                .collection("users").document(userID).get().await()
            val pinnedIDs = (userDoc.get("pinnedVideoIDs") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            if (pinnedIDs.isEmpty()) {
                pinnedVideos = emptyList()
                return
            }
            val videos = pinnedIDs.mapNotNull { videoID ->
                try { videoService.getVideoById(videoID) } catch (_: Exception) { null }
            }
            pinnedVideos = videos
        } catch (_: Exception) {
            pinnedVideos = emptyList()
        }
    }

    suspend fun loadMoreVideos() {
        if (isLoadingMore || !hasMoreVideos) return
        isLoadingMore = true
        try {
            // TODO: Implement cursor-based pagination when VideoService supports it
            // For now, load all at once with higher limit
            hasMoreVideos = false
        } catch (_: Exception) { } finally {
            isLoadingMore = false
        }
    }

    val isOwnProfileForAgeGate = (userID == currentAuthUserID)

    // Persist loaded profile data into the retained cache so the next return to this
    // profile restores instantly. Fires whenever the loaded content changes.
    LaunchedEffect(userID, currentUser, userVideos, pinnedVideos, userCollections) {
        if (currentUser != null) {
            profileCache.put(
                userID,
                ProfileCacheViewModel.Entry(currentUser, userVideos, pinnedVideos, userCollections)
            )
        }
    }

    LaunchedEffect(userID) {
        // Restored from the retained cache → skip every network load (instant). The
        // age gate also only runs on a real (uncached) load, which is fine — it
        // already ran when this profile was first loaded this session.
        if (cachedProfile != null) return@LaunchedEffect
        // Launch all in parallel — collections no longer blocked by user/video load
        launch { loadUser() }
        launch { loadVideos() }
        launch { loadPinnedVideos() }

        // Age gate — own profile only.  Reads users/{id}.privacySettings.birthdate.
        // Server-side custom claim (audienceLane) is set by onUserBirthdateSet
        // Cloud Function — this client check is for UX only.
        if (isOwnProfileForAgeGate && !ageGateChecked) {
            launch {
                try {
                    val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("stitchfin")
                    val snap = db.collection("users").document(userID).get().await()
                    @Suppress("UNCHECKED_CAST")
                    val privacy = snap.get("privacySettings") as? Map<String, Any>
                    val ageGroupRaw = privacy?.get("ageGroup") as? String
                    if (ageGroupRaw == "blocked") {
                        showingUnder13Block = true
                    } else {
                        val dob = (privacy?.get("birthdate") as? com.google.firebase.Timestamp)?.toDate()
                        if (dob == null) {
                            showingBirthdayPrompt = true
                        } else {
                            val age = java.util.Calendar.getInstance().let { now ->
                                val cal = java.util.Calendar.getInstance().apply { time = dob }
                                var a = now.get(java.util.Calendar.YEAR) - cal.get(java.util.Calendar.YEAR)
                                if (now.get(java.util.Calendar.DAY_OF_YEAR) < cal.get(java.util.Calendar.DAY_OF_YEAR)) a--
                                a.coerceAtLeast(0)
                            }
                            when {
                                age < 13 -> showingUnder13Block = true
                                age < 18 -> teenLocked = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) { println("PROFILE: ageGate read failed — ${e.message}") }
                }
                ageGateChecked = true
            }
        }
        launch {
            try {
                val showService = ShowService.shared
                // Mirror iOS: load via shows first (no composite index needed)
                val shows = showService.getCreatorShows(userID)
                val showEpisodes = mutableListOf<VideoCollection>()
                for (show in shows) {
                    val eps = showService.getAllEpisodes(show.id)
                    showEpisodes.addAll(eps)
                }
                // Also load standalone collections (legacy / no showId)
                val standalone = try {
                    collectionService.getUserCollections(userID)
                } catch (e: Exception) { emptyList() }

                // Merge deduped by id
                val seen = mutableSetOf<String>()
                val merged = mutableListOf<VideoCollection>()
                for (ep in showEpisodes) { if (seen.add(ep.id)) merged.add(ep) }
                for (col in standalone) { if (seen.add(col.id)) merged.add(col) }

                userCollections = merged
                if (BuildConfig.DEBUG) { println("📚 PROFILE: Loaded ${merged.size} collections (${showEpisodes.size} from shows, ${standalone.size} standalone) for $userID") }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("❌ PROFILE: Collections load failed for $userID: ${e.message}") }
            }
        }
    }

    // Swipe-to-dismiss
    val offsetY = remember { Animatable(0f) }

    // ===== MAIN LAYOUT =====
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.bg)
            .then(
                if (!isOwnProfile && onDismiss != null) {
                    Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (offsetY.value > 150f) onDismiss.invoke()
                                    else offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            },
                            onDragCancel = {
                                scope.launch { offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) }
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            scope.launch { offsetY.snapTo((offsetY.value + dragAmount).coerceAtLeast(0f)) }
                        }
                    }
                } else Modifier
            )
            .graphicsLayer {
                translationY = offsetY.value
                alpha = 1f - (offsetY.value / 1000f).coerceIn(0f, 0.2f)
            }
    ) {
        when {
            isLoading -> ProfileLoadingView()
            errorMessage != null -> ProfileErrorView(errorMessage!!) { scope.launch { loadUser(); loadVideos() } }
            currentUser == null -> NoUserView()
            currentUser != null -> {
                LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize()) {
                    // Top bar — share + overflow (iOS profileTopBar parity)
                    item {
                        ProfileTopBar(
                            isOwnProfile = isOwnProfile,
                            username = currentUser?.username ?: "",
                            onSaved = { showingSavedVideos = true },
                            onSettings = { showingSettings = true },
                            onEdit = { showingEditProfile = true }
                        )
                    }
                    // Header
                    item {
                        ProfileHeader(
                            user = currentUser!!,
                            videos = userVideos,
                            pinnedVideoCount = pinnedVideos.size,
                            isOwnProfile = isOwnProfile,
                            isShowingFullBio = isShowingFullBio,
                            onToggleBio = { isShowingFullBio = !isShowingFullBio },
                            onEditProfile = { showingEditProfile = true },
                            onSettingsClick = { showingSettings = true },
                            onSavedClick = { showingSavedVideos = true },
                            onAdOpportunities = { showingAdOpportunities = true },
                            onFollowersClick = { showStitchersSheet = true },
                            isFollowing = isFollowing,
                            isFollowLoading = isFollowLoading,
                            onFollowToggle = { followManager.toggleFollow(userID) },
                            onShowBadgePage = { showingBadgePage = true }
                        )
                    }

                    // Collections row — contextual, show-grouped, mirrors iOS ProfileCollectionsRow
                    item {
                        ProfileCollectionsRow(
                            collections = userCollections,
                            isOwnProfile = isOwnProfile,
                            onAddTap = { /* TODO: navigate to ShowEditorView */ },
                            onShowTap = { showId: String, eps: List<VideoCollection> ->
                                selectedShowId = showId
                                selectedShowEpisodes = eps
                                showingShowDetail = true
                            },
                            onCollectionTap = { collection: VideoCollection ->
                                selectedCollection = collection
                                showCollectionPlayer = true
                            },
                            onSeeAllTap = { showingAllCollections = true },
                            onCollectionDelete = if (isOwnProfile) ({ collection: VideoCollection ->
                                scope.launch {
                                    try {
                                        collectionService.deleteCollection(collection.id)
                                        userCollections = userCollections.filter { it.id != collection.id }
                                    } catch (e: Exception) {
                                        if (BuildConfig.DEBUG) { println("❌ PROFILE: Delete failed: ${e.message}") }
                                    }
                                }
                            }) else null
                        )
                    }

                    // Tab bar with icons — pinned now live inside the grid (iOS parity)
                    item {
                        val pinnedIDs = pinnedVideos.map { it.id }.toSet()
                        ProfileTabBar(
                            selectedTab = selectedTab,
                            tabCounts = listOf(
                                pinnedVideos.size + userVideos.count { it.conversationDepth == 0 && !pinnedIDs.contains(it.id) },
                                userVideos.count { it.conversationDepth == 1 },
                                userVideos.count { it.conversationDepth >= 2 }
                            ),
                            onTabSelected = { selectedTab = it }
                        )
                    }

                    // Video grid
                    item {
                        val currentUserId = authService.getCurrentUserId()
                        val isOwn = (userID == currentUserId)

                        ProfileVideoGrid(
                            videos = filteredVideos.map { it.toBasicVideoInfo() },
                            selectedTab = selectedTab,
                            tabTitles = profileTabs.map { it.title },
                            isLoading = isLoadingVideos,
                            isCurrentUserProfile = isOwn,
                            pinnedVideoIDs = pinnedVideos.map { it.id }.toSet(),
                            onVideoTap = { basicVideo, index, _ ->
                                val coreVideo = filteredVideos.find { it.id == basicVideo.id }
                                if (coreVideo != null) {
                                    scope.launch {
                                        // For child videos (depth > 0), find the thread parent and load full thread
                                        if (coreVideo.conversationDepth > 0 && !coreVideo.threadID.isNullOrEmpty()) {
                                            val parentVideo = userVideos.find { it.id == coreVideo.threadID }
                                            if (parentVideo != null) {
                                                // Load full thread: parent + children
                                                try {
                                                    val children = videoService.getThreadChildren(parentVideo.id)
                                                    val threadList = listOf(parentVideo) + children.sortedBy { it.conversationDepth }
                                                    val childIndex = threadList.indexOfFirst { it.id == coreVideo.id }
                                                    currentVideoList = threadList
                                                    selectedVideoIndex = if (childIndex >= 0) childIndex else 0
                                                    selectedVideo = currentVideoList.getOrNull(selectedVideoIndex)
                                                } catch (_: Exception) {
                                                    currentVideoList = listOf(parentVideo)
                                                    selectedVideoIndex = 0
                                                    selectedVideo = parentVideo
                                                }
                                            } else {
                                                selectedVideo = coreVideo
                                                selectedVideoIndex = 0
                                                currentVideoList = listOf(coreVideo)
                                            }
                                        } else {
                                            // Parent video — load its children for thread swiping
                                            try {
                                                val children = if (coreVideo.replyCount > 0) {
                                                    videoService.getThreadChildren(coreVideo.id)
                                                } else emptyList()
                                                val threadList = listOf(coreVideo) + children.sortedBy { it.conversationDepth }
                                                currentVideoList = threadList
                                                selectedVideoIndex = 0
                                                selectedVideo = coreVideo
                                            } catch (_: Exception) {
                                                currentVideoList = listOf(coreVideo)
                                                selectedVideoIndex = 0
                                                selectedVideo = coreVideo
                                            }
                                        }
                                        showingVideoPlayer = true
                                    }
                                }
                            },
                            onVideoDelete = { basicVideo ->
                                userVideos.find { it.id == basicVideo.id }?.let {
                                    videoToDelete = it
                                    showingDeleteConfirmation = true
                                }
                            }
                        )
                    }

                    // Load more trigger
                    if (hasMoreVideos && !isLoadingMore) {
                        item {
                            LaunchedEffect(Unit) { loadMoreVideos() }
                        }
                    }

                    if (isLoadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(100.dp)) }
                }
            }
        }

        // ── Age gate overlays (own profile only) ──────────
        // Order matters: under-13 block wins over teen-locked, which wins
        // over the prompt. The prompt only shows when DOB is missing.
        if (isOwnProfileForAgeGate) {
            if (showingUnder13Block) {
                Box(modifier = Modifier.fillMaxSize().zIndex(50f)) {
                    Under13BlockedView(onAcknowledged = { showingUnder13Block = false })
                }
            } else if (teenLocked) {
                val name = currentUser?.displayName ?: "there"
                Box(modifier = Modifier.fillMaxSize().zIndex(50f)) {
                    TeenLockedView(displayName = name, onSignedOut = { teenLocked = false })
                }
            } else if (showingBirthdayPrompt) {
                Box(modifier = Modifier.fillMaxSize().zIndex(50f)) {
                    BirthdayPromptView(
                        userID = userID,
                        onCompleted = { outcome ->
                            showingBirthdayPrompt = false
                            when (outcome) {
                                is AgeGateOutcome.Adult -> { /* allow */ }
                                is AgeGateOutcome.Teen  -> teenLocked = true
                                is AgeGateOutcome.Under13Blocked -> showingUnder13Block = true
                            }
                        }
                    )
                }
            }
        }

        // Close button overlay for other profiles (iOS: top-right X)
        if (!isOwnProfile && onDismiss != null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                IconButton(
                    onClick = { onDismiss.invoke() },
                    modifier = Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, "Close", tint = AppTheme.colors.textPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }


    // ===== COLLECTION PLAYER FULLSCREEN =====
    if (showCollectionPlayer && selectedCollection != null) {
        val coll: VideoCollection = selectedCollection!!
        CollectionPlayerView(
            collection = coll,
            userID = currentAuthUserID ?: userID,
            currentUserTier = currentUser?.tier ?: UserTier.ROOKIE,
            videoService = videoService,
            authService = authService,
            engagementViewModel = viewModel,
            iconManager = iconManager,
            followManager = followManager,
            onReplyToSegment = { seg ->
                val authID = currentAuthUserID ?: ""
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

    // ===== VIDEO PLAYER FULLSCREEN =====
    if (showingVideoPlayer && selectedVideo != null) {
        // Swipe tracking state
        var swipeOffset by remember { mutableFloatStateOf(0f) }

        // No more VideoInfo conversion — the direct mount uses
        // CoreVideoMetadata throughout, like HomeFeedView does.
        val currentVid = selectedVideo!!

        // Peek videos for left/right edges
        val prevVideo = if (selectedVideoIndex > 0) currentVideoList.getOrNull(selectedVideoIndex - 1) else null
        val nextVideo = if (selectedVideoIndex < currentVideoList.size - 1) currentVideoList.getOrNull(selectedVideoIndex + 1) else null

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colors.bg)
                .pointerInput(selectedVideoIndex, currentVideoList.size) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeOffset < -120f && selectedVideoIndex < currentVideoList.size - 1) {
                                selectedVideoIndex++
                                selectedVideo = currentVideoList.getOrNull(selectedVideoIndex)
                            } else if (swipeOffset > 120f && selectedVideoIndex > 0) {
                                selectedVideoIndex--
                                selectedVideo = currentVideoList.getOrNull(selectedVideoIndex)
                            }
                            swipeOffset = 0f
                        },
                        onDragCancel = { swipeOffset = 0f },
                        onHorizontalDrag = { _, dragAmount -> swipeOffset += dragAmount }
                    )
                }
        ) {
            // Mount the player + overlay directly, mirroring HomeFeedView:447-475
            // 1:1. The previous FullscreenVideoPlayer wrapper added an extra
            // layer that wasn't propagating taps cleanly; this puts the same
            // structure home feed uses, so behavior matches by construction.
            key(currentVid.id) {
                VideoPlayerComposable(
                    video = currentVid,
                    isActive = true,
                    modifier = Modifier.fillMaxSize()
                )
            }

            ContextualVideoOverlay(
                video = currentVid,
                overlayContext = if (isOwnProfile) OverlayContext.PROFILE_OWN else OverlayContext.PROFILE_OTHER,
                currentUserID = currentAuthUserID,
                threadVideo = null,
                currentUserTier = currentUser?.tier ?: UserTier.ROOKIE,
                engagementViewModel = viewModel,
                iconManager = iconManager,
                followManager = followManager,
                navigationCoordinator = navigationCoordinator,
                onAction = { action ->
                    if (BuildConfig.DEBUG) { println("PROFILE PLAYER: action received: $action") }
                    when (action) {
                        is OverlayAction.NavigateToProfile -> {
                            // Open the tapped creator's profile, dismiss this player
                            navigationCoordinator?.showModal(
                                ModalState.USER_PROFILE,
                                mapOf("userID" to action.userID)
                            )
                            showingVideoPlayer = false
                            selectedVideo = null
                        }
                        is OverlayAction.StitchRecording -> {
                            val authID = currentAuthUserID ?: ""
                            val isOwn = currentVid.creatorID == authID
                            val threadID = currentVid.threadID ?: currentVid.id
                            val ctx = if (isOwn) {
                                RecordingContextFactory.createContinueThread(
                                    threadID, currentVid.creatorName, currentVid.title
                                )
                            } else {
                                RecordingContextFactory.createStitchToThread(
                                    threadID, currentVid.creatorName, currentVid.title
                                )
                            }
                            if (BuildConfig.DEBUG) { println("PROFILE PLAYER: Stitch — threadID=$threadID isOwn=$isOwn") }
                            navigationCoordinator?.showModal(
                                ModalState.RECORDING,
                                mapOf("context" to ctx, "parentVideo" to currentVid)
                            )
                            // Dismiss this overlay so the recording modal isn't
                            // covered by ProfileView's video-player overlay.
                            showingVideoPlayer = false
                            selectedVideo = null
                        }
                        is OverlayAction.NavigateToThread -> {
                            val threadID = currentVid.threadID ?: currentVid.id
                            // Respect the Thread3DInfoPanel's focus target if set.
                            onShowThreadView(threadID, action.targetVideoID ?: currentVid.id)
                            scope.launch {
                                delay(100)
                                showingVideoPlayer = false
                                selectedVideo = null
                            }
                        }
                        else -> {
                            // Engagement (HYPE/COOL) is handled internally by
                            // ContextualVideoOverlay via ProgressiveHypeButton3D
                            // → engagementViewModel.onHypeTap. Do NOT call
                            // processHype here or you'll double-count.
                        }
                    }
                }
            )

            // Close button (top-right)
            IconButton(
                onClick = {
                    showingVideoPlayer = false
                    selectedVideo = null
                    scope.launch { loadVideos() }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = AppTheme.colors.textPrimary
                )
            }

            // Navigation peek thumbnails (matches DiscoveryView)
            if (currentVideoList.size > 1) {
                // Next video preview (right edge)
                if (nextVideo != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .size(60.dp, 80.dp)
                            .graphicsLayer { alpha = 0.9f }
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
                if (prevVideo != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 8.dp)
                            .size(60.dp, 80.dp)
                            .graphicsLayer { alpha = 0.9f }
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
            }

            // Thread position dots
            if (currentVideoList.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    currentVideoList.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .size(if (index == selectedVideoIndex) 8.dp else 6.dp)
                                .background(
                                    if (index == selectedVideoIndex) Color.Cyan else Color.White.copy(alpha = 0.4f),
                                    CircleShape
                                )
                        )
                    }
                }
            }
        }
    }

    // ===== SHOW DETAIL FULLSCREEN =====
    if (showingShowDetail && selectedShowId != null) {
        ShowDetailView(
            showId = selectedShowId!!,
            currentUserID = currentAuthUserID ?: "",
            onDismiss = { showingShowDetail = false },
            onPlayEpisode = { episode ->
                showingShowDetail = false
                selectedCollection = episode
                showCollectionPlayer = true
            }
        )
    }

    // ===== ALL COLLECTIONS (See All) =====
    if (showingAllCollections) {
        Dialog(
            onDismissRequest = { showingAllCollections = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppTheme.colors.bg)
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Shows",
                            color = AppTheme.colors.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showingAllCollections = false }) {
                            Icon(Icons.Default.Close, "Close", tint = AppTheme.colors.textPrimary)
                        }
                    }
                    // Show grid grouped by showId
                    val groups = remember(userCollections) {
                        userCollections.filter { !it.showId.isNullOrEmpty() }
                            .groupBy { it.showId!! }
                            .map { (showId, eps) -> Pair(showId, eps.sortedBy { it.episodeNumber ?: 0 }) }
                            .sortedByDescending { it.second.firstOrNull()?.createdAt }
                    }
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                        contentPadding = PaddingValues(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(groups.size) { idx ->
                            val (showId, eps) = groups[idx]
                            ShowCard(
                                episodes = eps,
                                onTap = {
                                    showingAllCollections = false
                                    selectedShowId = showId
                                    selectedShowEpisodes = eps
                                    showingShowDetail = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ===== EDIT PROFILE =====
    // EditProfileView now owns the full save flow (image upload + Firestore write).
    // onSave just refreshes profile data and dismisses.
    if (showingEditProfile && currentUser != null) {
        EditProfileView(
            userID = userID,
            currentUserName = currentUser!!.displayName,
            currentUsername = currentUser!!.username,
            currentUserImage = currentUser!!.profileImageURL,
            currentBio = currentUser!!.bio ?: "",
            currentIsPrivate = currentUser!!.isPrivate ?: false,
            onSave = { _, _, _, _ ->
                scope.launch {
                    loadUser()
                    showingEditProfile = false
                }
            },
            onCancel = { showingEditProfile = false }
        )
    }


    // ===== SETTINGS =====
    if (showingSettings && currentUser != null) {
        SettingsView(
            currentUser = currentUser!!,
            authService = authService,
            onDismiss = { showingSettings = false },
            onSignOutSuccess = { showingSettings = false }
        )
    }

    // ===== SAVED VIDEOS =====
    // Own-profile bookmark grid; zIndexed above the profile like BadgePageView.
    if (showingSavedVideos) {
        Box(modifier = Modifier.fillMaxSize().zIndex(60f)) {
            SavedVideosScreen(onDismiss = { showingSavedVideos = false })
        }
    }

    // ===== AD OPPORTUNITIES ===== (green $ button; Influencer+ personal)
    if (showingAdOpportunities && currentUser != null) {
        Box(modifier = Modifier.fillMaxSize().zIndex(60f)) {
            AdOpportunitiesView(user = currentUser!!, onDismiss = { showingAdOpportunities = false })
        }
    }

    // ===== BADGE PAGE =====
    // Mounted at the top of ProfileView (sibling to the LazyColumn) so
    // BadgePageView's verticalScroll has bounded constraints. Mounting
    // it inside the scrollable header throws "infinity max height".
    if (showingBadgePage && currentUser != null) {
        Box(modifier = Modifier.fillMaxSize().background(AppTheme.colors.bg).zIndex(60f)) {
            BadgePageView(
                userID = currentUser!!.id,
                isOwner = isOwnProfile,
                stats = com.stitchsocial.club.services.RealUserStats(
                    clout = currentUser!!.clout,
                    followers = currentUser!!.followerCount ?: 0,
                    posts = userVideos.size
                ),
                xp = currentUser!!.clout,
                tierRaw = currentUser!!.tier.rawValue,
                onDismiss = { showingBadgePage = false }
            )
        }
    }

    // ===== DELETE CONFIRMATION =====
    if (showingDeleteConfirmation && videoToDelete != null) {
        AlertDialog(
            onDismissRequest = { showingDeleteConfirmation = false },
            title = { Text("Delete Video", color = AppTheme.colors.textPrimary) },
            text = {
                Text(
                    "Are you sure you want to delete '${videoToDelete?.title}'? This action cannot be undone.",
                    color = AppTheme.colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        videoToDelete?.let { video ->
                            try {
                                videoService.deleteVideo(video.id)
                                userVideos = userVideos.filter { it.id != video.id }
                            } catch (e: Exception) {
                                errorMessage = "Failed to delete video: ${e.message}"
                            }
                        }
                        showingDeleteConfirmation = false
                        videoToDelete = null
                    }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showingDeleteConfirmation = false }) { Text("Cancel", color = AppTheme.colors.textSecondary) }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }

    // ===== STITCHERS LIST (FULL SCREEN) =====
    if (showStitchersSheet) {
        Dialog(
            onDismissRequest = { showStitchersSheet = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            StitchersListView(
                profileUserID = userID,
                profileUsername = currentUser?.username ?: "",
                isOwnProfile = isOwnProfile,
                followManager = followManager,
                onDismiss = { showStitchersSheet = false },
                onUserTap = { tappedUserID ->
                    showStitchersSheet = false
                    if (navigationCoordinator != null) {
                        navigationCoordinator.showModal(
                            ModalState.USER_PROFILE,
                            mapOf("userID" to tappedUserID)
                        )
                    } else {
                        if (BuildConfig.DEBUG) { println("PROFILE: navigationCoordinator is NULL - cannot navigate to $tappedUserID") }
                    }
                }
            )
        }
    }
}

// ===== PROFILE HEADER =====

@Composable
private fun ProfileHeader(
    user: BasicUserInfo,
    videos: List<CoreVideoMetadata> = emptyList(),
    pinnedVideoCount: Int = 0,
    isOwnProfile: Boolean,
    isShowingFullBio: Boolean,
    onToggleBio: () -> Unit,
    onEditProfile: () -> Unit,
    onSettingsClick: () -> Unit,
    onSavedClick: () -> Unit = {},
    onAdOpportunities: () -> Unit = {},
    onFollowersClick: () -> Unit,
    isFollowing: Boolean = false,
    isFollowLoading: Boolean = false,
    onFollowToggle: () -> Unit = {},
    onShowBadgePage: () -> Unit = {}
) {
    // Creator Rank sheet — opened by tapping the tier-colored verification
    // check (tier-as-verification; deliberately no separate rank pill).
    var showRankSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxl, bottom = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Profile image + info row (iOS layout: avatar + badge stack | name/handle/bio)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.Top
        ) {
            // Left column: avatar with the compact badge stack tucked underneath
            // (non-business only — business accounts don't show badges, iOS parity).
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                EnhancedProfileImage(user = user, videos = videos)
                if (!user.isBusiness) {
                    ProfileBadgeStack(
                        userID = user.id,
                        isOwner = isOwnProfile,
                        onTapView = onShowBadgePage
                    )
                }
            }

            // Right column: name + tier check, @handle, and the bio directly below.
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Headliner name — ~30sp bold, single line (iOS uses 32pt title).
                    Text(
                        user.displayName,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // Tier-as-verification: a tier-colored check IS the verification
                    // (UserTier.color). Personal shows for tier != Rookie or verified;
                    // business shows a teal check. Replaces the red icon + tier chip.
                    if (user.isBusiness) {
                        Icon(Icons.Default.Verified, "Business", tint = StitchColors.tierBusiness, modifier = Modifier.size(16.dp))
                    } else if (user.tier != UserTier.ROOKIE || user.isVerified) {
                        // Tapping the check opens the Creator Rank sheet (iOS parity).
                        Icon(
                            Icons.Default.Verified, "Verified",
                            tint = user.tier.color,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { showRankSheet = true }
                        )
                    }
                }

                Text("@${user.username}", fontSize = 13.sp, color = AppTheme.colors.textSecondary)

                // Bio sits directly under the handle, not under the badges (iOS parity).
                BioSection(user = user, isOwnProfile = isOwnProfile, isShowingFullBio = isShowingFullBio, onToggleBio = onToggleBio, onEditProfile = onEditProfile)
            }
        }

        // Streak (own profile only) — above the hype meter; opens the streak sheet.
        if (isOwnProfile) {
            ProfileStreakSection()
        }

        // Hype meter
        HypeMeter(user = user, videos = videos)

        // Stats
        StatsRow(user = user, videoCount = videos.size + pinnedVideoCount, onFollowersClick = onFollowersClick)

        // Action buttons
        ActionButtonsRow(
            isOwnProfile = isOwnProfile,
            isFollowing = isFollowing,
            isFollowLoading = isFollowLoading,
            onEditProfile = onEditProfile,
            onSettingsClick = onSettingsClick,
            onFollowToggle = onFollowToggle,
            userTier = user.tier,
            isBusiness = user.isBusiness,
            onAdOpportunities = onAdOpportunities,
            targetUserID = user.id,
            targetUsername = user.username
        )

    }

    if (showRankSheet) {
        RankSheetView(
            user = user,
            followerCount = user.followerCount,
            isOwnProfile = isOwnProfile,
            onDismiss = { showRankSheet = false }
        )
    }
}

// ===== ENHANCED PROFILE IMAGE (hype progress ring) =====

@Composable
private fun EnhancedProfileImage(user: BasicUserInfo, videos: List<CoreVideoMetadata> = emptyList()) {
    // Supporter (subscription) ring — colored by the owner's TOP supporter tier,
    // with a count bubble. Was a hype-progress ring; hype now lives in the meter.
    var supporterCount by remember(user.id) { mutableStateOf(0) }
    var ringColor by remember(user.id) { mutableStateOf<Color?>(null) }
    LaunchedEffect(user.id) {
        try {
            val subs = SubscriptionService.shared.fetchMySubscribers(user.id)
            supporterCount = subs.size
            ringColor = subs.maxByOrNull { it.coinTier.rawValue }?.let { coinTierColor(it.coinTier) }
        } catch (_: Exception) { }
    }

    Box(modifier = Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        // Neutral base ring
        Canvas(modifier = Modifier.size(76.dp)) {
            drawArc(
                color = Color.Gray.copy(alpha = 0.3f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Supporter ring (full circle) when there are supporters
        ringColor?.let { rc ->
            Canvas(modifier = Modifier.size(76.dp)) {
                drawArc(
                    color = rc,
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // Profile image
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(user.profileImageURL ?: "")
                .crossfade(true)
                .build(),
            contentDescription = "Profile",
            modifier = Modifier.size(66.dp).clip(CircleShape).background(AppTheme.colors.textSecondary.copy(alpha = 0.3f)),
            contentScale = ContentScale.Crop
        )

        // Supporter count bubble
        if (supporterCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(ringColor ?: StitchColors.primary)
                    .border(1.5.dp, Color.Black, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("$supporterCount", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

/** Subscription coin-tier -> ring color (mirrors iOS SupporterRingView). */
private fun coinTierColor(tier: CoinPriceTier): Color = when (tier) {
    CoinPriceTier.STARTER -> Color(0xFF9CA3AF)  // gray
    CoinPriceTier.BASIC   -> Color(0xFF4ADE80)  // green
    CoinPriceTier.PLUS    -> Color(0xFF60A5FA)  // blue
    CoinPriceTier.PRO     -> Color(0xFFC084FC)  // purple
    CoinPriceTier.MAX     -> Color(0xFFFBBF24)  // gold
}

// ===== TIER BADGE =====

@Composable
private fun ProfileTierBadge(tier: UserTier) {
    val tierColors = getTierColors(tier)
    val tierIcon = getTierIcon(tier)

    Row(
        modifier = Modifier
            .background(
                brush = Brush.horizontalGradient(
                    colors = if (tierColors.size > 1) tierColors.map { it.copy(alpha = 0.3f) }
                    else listOf(tierColors[0].copy(alpha = 0.3f), tierColors[0].copy(alpha = 0.3f))
                ),
                shape = RoundedCornerShape(10.dp)
            )
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(tierIcon, null, tint = tierColors.firstOrNull() ?: Color.White, modifier = Modifier.size(12.dp))
        Text(tier.displayName, color = AppTheme.colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ===== BIO SECTION =====

@Composable
private fun BioSection(
    user: BasicUserInfo,
    isOwnProfile: Boolean,
    isShowingFullBio: Boolean,
    onToggleBio: () -> Unit,
    onEditProfile: () -> Unit
) {
    val bioText = getBioText(user)
    val contextualBio = if (bioText.isEmpty()) generateContextualBio(user) else null
    val displayBio = bioText.ifEmpty { contextualBio ?: "" }

    // No horizontal padding here — the bio now lives inside the already-padded
    // right column of the identity row (iOS parity), so it inherits that inset.
    Column(modifier = Modifier.fillMaxWidth()) {
        if (displayBio.isNotEmpty()) {
            val shouldTruncate = displayBio.length > 80

            if (shouldTruncate && !isShowingFullBio) {
                Text(displayBio, color = AppTheme.colors.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(displayBio, color = AppTheme.colors.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
            }

            if (shouldTruncate) {
                Text(
                    if (isShowingFullBio) "Show less" else "Show more",
                    color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onToggleBio() }
                )
            }
        } else if (isOwnProfile) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onEditProfile() }
            ) {
                Icon(Icons.Default.AddCircleOutline, null, tint = AppTheme.colors.textSecondary.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                Text("Add bio", color = AppTheme.colors.textSecondary.copy(alpha = 0.8f), fontSize = 14.sp)
            }
        }
    }
}

// ===== HYPE METER =====

@Composable
private fun HypeMeter(user: BasicUserInfo, videos: List<CoreVideoMetadata> = emptyList()) {
    val hypeRating = calculateHypeLevel(user, videos, user.followerCount ?: 0)
    val progress = hypeRating / 100f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(AppTheme.colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, AppTheme.colors.hairline, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Whatshot, null, tint = StitchColors.primary, modifier = Modifier.size(14.dp))
                Text("Hype Rating", color = AppTheme.colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("${hypeRating.toInt()}%", color = AppTheme.colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Box(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            Box(Modifier.fillMaxSize().background(AppTheme.colors.textSecondary.copy(alpha = 0.2f), RoundedCornerShape(5.dp)))
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(
                        Brush.horizontalGradient(listOf(StitchColors.gradientStart, StitchColors.gradientEnd)),
                        RoundedCornerShape(5.dp)
                    )
            )
        }
    }
}

// ===== STATS ROW =====

@Composable
private fun StatsRow(user: BasicUserInfo, videoCount: Int, onFollowersClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(videoCount, "Videos", Modifier.weight(1f)) { }
        StatCard(user.followerCount ?: 0, "Stitchers", Modifier.weight(1f), onFollowersClick)
        StatCard(user.clout ?: 0, "Clout", Modifier.weight(1f)) { }
    }
}

@Composable
private fun StatCard(count: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(AppTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(formatLargeNumber(count), color = AppTheme.colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label.uppercase(), color = AppTheme.colors.textSecondary, fontSize = 10.sp, letterSpacing = 0.5.sp)
    }
}

// ===== TOP BAR (share + overflow menu) — iOS profileTopBar parity =====

@Composable
private fun ProfileTopBar(
    isOwnProfile: Boolean,
    username: String,
    onSaved: () -> Unit,
    onSettings: () -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Share
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).background(AppTheme.colors.surface)
                .clickable {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "https://stitchsocial.me/u/$username")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share profile"))
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Share, "Share", tint = AppTheme.colors.textPrimary, modifier = Modifier.size(18.dp))
        }

        // Overflow (own profile: Saved / Settings / Edit)
        if (isOwnProfile) {
            Spacer(Modifier.width(Spacing.xs))
            Box {
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(AppTheme.colors.surface).clickable { menuOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MoreHoriz, "More", tint = AppTheme.colors.textPrimary, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Saved videos") }, onClick = { menuOpen = false; onSaved() })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onSettings() })
                    DropdownMenuItem(text = { Text("Edit profile") }, onClick = { menuOpen = false; onEdit() })
                }
            }
        }
    }
}

// ===== ACTION BUTTONS ROW (iOS: side by side) =====

@Composable
private fun ActionButtonsRow(
    isOwnProfile: Boolean,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    onEditProfile: () -> Unit,
    onSettingsClick: () -> Unit,
    onFollowToggle: () -> Unit,
    userTier: UserTier = UserTier.ROOKIE,
    isBusiness: Boolean = false,
    onAdOpportunities: () -> Unit = {},
    targetUserID: String = "",
    targetUsername: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (isOwnProfile) {
            // Edit profile — surface fill + magenta hairline (iOS: Theme.surface + accent stroke)
            Button(
                onClick = onEditProfile,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surface),
                border = BorderStroke(1.5.dp, StitchColors.primary),
                shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Edit profile", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
            }

            // Ad opportunities — green $ button, Influencer+ personal only (iOS parity).
            if (!isBusiness && AdRevenueShare.canAccessAds(userTier)) {
                Button(
                    onClick = onAdOpportunities,
                    modifier = Modifier.size(width = 46.dp, height = 44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF33C759)),
                    shape = RoundedCornerShape(13.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.AttachMoney, "Ad opportunities", tint = Color(0xFF053610), modifier = Modifier.size(20.dp))
                }
            }

            // Settings — surface fill + neutral hairline.
            Button(
                onClick = onSettingsClick,
                modifier = Modifier.size(width = 46.dp, height = 44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surface),
                border = BorderStroke(1.dp, AppTheme.colors.hairline),
                shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Settings, "Settings", tint = AppTheme.colors.textPrimary, modifier = Modifier.size(18.dp))
            }
            // Saved moved to the top-bar ⋯ menu (iOS parity).
        } else {
            Button(
                onClick = onFollowToggle,
                enabled = !isFollowLoading,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isFollowing) Color.White else StitchColors.primary),
                shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isFollowLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = if (isFollowing) Color.Black else Color.White
                    )
                } else {
                    Text(
                        if (isFollowing) "Following" else "Follow",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = if (isFollowing) Color.Black else Color.White
                    )
                }
            }

            Button(
                onClick = { /* TODO: Subscribe */ },
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surface),
                border = BorderStroke(1.dp, AppTheme.colors.hairline),
                shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Subscribe", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
            }

            // More menu (Report / Block) — App Store Guideline 1.2 / Play Store UGC
            ProfileMoreMenu(
                targetUserID = targetUserID,
                targetUsername = targetUsername
            )
        }
    }
}

// ===== PROFILE MORE MENU (Report / Block) =====
//
// Self-contained: hosts its own DropdownMenu, ReportSheet, and Block
// confirmation AlertDialog so ActionButtonsRow only needs to drop it in.
//
@Composable
private fun ProfileMoreMenu(
    targetUserID: String,
    targetUsername: String
) {
    if (targetUserID.isBlank()) return

    var menuExpanded by remember { mutableStateOf(false) }
    var showReportSheet by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val blockedIds by com.stitchsocial.club.services.BlockService.shared
        .blockedUserIds.collectAsState()
    val isBlocked = blockedIds.contains(targetUserID)

    Box {
        Button(
            onClick = { menuExpanded = true },
            modifier = Modifier.size(36.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.textSecondary.copy(alpha = 0.8f)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(
                Icons.Default.MoreHoriz,
                contentDescription = "More",
                tint = AppTheme.colors.textPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Report user") },
                leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    showReportSheet = true
                }
            )
            if (isBlocked) {
                DropdownMenuItem(
                    text = { Text("Unblock @$targetUsername") },
                    leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        scope.launch {
                            com.stitchsocial.club.services.BlockService.shared
                                .unblockUser(targetUserID)
                        }
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Block @$targetUsername") },
                    leadingIcon = { Icon(Icons.Default.Block, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        showBlockConfirm = true
                    }
                )
            }
        }
    }

    if (showReportSheet) {
        ReportSheet(
            targetType = "user",
            targetID = targetUserID,
            onDismiss = { showReportSheet = false }
        )
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text("Block @$targetUsername?") },
            text = {
                Text(
                    "You won't see their videos, replies, or stitches. They won't be notified."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBlockConfirm = false
                        scope.launch {
                            com.stitchsocial.club.services.BlockService.shared
                                .blockUser(targetUserID)
                        }
                    }
                ) { Text("Block", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ===== COLLECTIONS ROW PLACEHOLDER =====

@Composable
private fun CollectionsRowPlaceholder(isOwnProfile: Boolean, tier: UserTier) {
    val isEligible = tier in listOf(
        UserTier.AMBASSADOR, UserTier.ELITE, UserTier.PARTNER,
        UserTier.LEGENDARY, UserTier.TOP_CREATOR, UserTier.FOUNDER, UserTier.CO_FOUNDER
    )
    if (!isEligible) return

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Collections", color = AppTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("See All", color = Color.Cyan, fontSize = 13.sp, modifier = Modifier.clickable { /* TODO */ })
        }

        Spacer(Modifier.height(12.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isOwnProfile) {
                item {
                    Box(
                        modifier = Modifier
                            .size(width = 100.dp, height = 140.dp)
                            .background(AppTheme.colors.surface, RoundedCornerShape(12.dp))
                            .border(1.dp, AppTheme.colors.hairline, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Add, null, tint = Color.Cyan, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(4.dp))
                            Text("New", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ===== TAB BAR WITH ICONS =====

@Composable
private fun ProfileTabBar(selectedTab: Int, tabCounts: List<Int>, onTabSelected: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(AppTheme.colors.bg).padding(top = 20.dp)) {
        profileTabs.forEachIndexed { index, tab ->
            val isSelected = selectedTab == index
            Column(
                modifier = Modifier.weight(1f).clickable { onTabSelected(index) }.height(50.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        tab.icon, null,
                        tint = if (isSelected) Color.Cyan.copy(alpha = 0.8f) else AppTheme.colors.textSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(tab.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (isSelected) Color.Cyan else AppTheme.colors.textSecondary)
                    Text("(${tabCounts.getOrNull(index) ?: 0})", fontSize = 10.sp, color = if (isSelected) Color.Cyan.copy(alpha = 0.8f) else AppTheme.colors.textSecondary.copy(alpha = 0.6f))
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (isSelected) Color.Cyan else Color.Transparent))
            }
        }
    }
}

// ===== LOADING / ERROR / EMPTY =====

@Composable
private fun ProfileLoadingView() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            CircularProgressIndicator(color = AppTheme.colors.textPrimary, modifier = Modifier.size(36.dp))
            Text("Loading Profile...", color = AppTheme.colors.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ProfileErrorView(error: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(40.dp)
        ) {
            Icon(Icons.Default.Warning, null, tint = Color.Red, modifier = Modifier.size(50.dp))
            Text("Error", color = AppTheme.colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(error, color = AppTheme.colors.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Retry", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NoUserView() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.PersonOff, null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(50.dp))
            Text("No User Found", color = AppTheme.colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ===== EXTENSION =====

private fun CoreVideoMetadata.toBasicVideoInfo(): BasicVideoInfo {
    return BasicVideoInfo(
        id = this.id,
        title = this.title,
        creatorName = this.creatorName,
        creatorID = this.creatorID,
        thumbnailURL = this.thumbnailURL,
        videoURL = this.videoURL,
        duration = this.duration,
        hypeCount = this.hypeCount,
        coolCount = this.coolCount,
        viewCount = this.viewCount,
        createdAt = this.createdAt,
        contentType = this.contentType,
        temperature = this.temperature
    )
}


// ===== STREAK (button + slide-up sheet) =====

@Composable
private fun ProfileStreakSection() {
    val streak = StreakService.shared
    val current by streak.current.collectAsState()
    var showSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { streak.load() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surface)
            .clickable { showSheet = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Whatshot, null, tint = StitchColors.primary, modifier = Modifier.size(18.dp))
        Text(
            if (current > 0) "$current day streak" else "Start your streak",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ChevronRight, null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
    }

    if (showSheet) {
        StreakSheet(onDismiss = { showSheet = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreakSheet(onDismiss: () -> Unit) {
    val streak = StreakService.shared
    val current by streak.current.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF0A0B0D)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(88.dp).clip(CircleShape)
                    .background(StitchColors.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Whatshot, null, tint = StitchColors.primary, modifier = Modifier.size(44.dp)) }

            Text(
                "$current ${if (current == 1) "day" else "days"}",
                fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary
            )

            // Streak-or-die: the bar to keep it escalates by week.
            if (streak.todaySecured) {
                Text("\u2705 secured today \u00B7 come back tomorrow",
                    fontSize = 13.sp, color = Color(0xFFF572A6))
            } else {
                Text("Today to keep your streak", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                Text(streak.todayRequirementLabel, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, color = Color(0xFFF572A6))
            }

            if (streak.hasWeeklyBoost) {
                Text("\uD83D\uDE80 ${streak.weeklyBoostDays}-day boost active",
                    fontSize = 13.sp, color = StitchColors.secondary)
            } else {
                streak.daysToNextTier?.let { d ->
                    Text("$d ${if (d == 1) "day" else "days"} to your next boost",
                        fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Maybe later", fontSize = 13.sp, color = AppTheme.colors.textSecondary,
                modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
        }
    }
}
