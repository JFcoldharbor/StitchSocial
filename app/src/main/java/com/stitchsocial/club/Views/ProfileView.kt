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

private fun getTierColors(tier: UserTier): List<Color> {
    return when (tier) {
        UserTier.ROOKIE -> listOf(Color(0xFF808080))
        UserTier.RISING -> listOf(Color(0xFF4A90E2), Color(0xFF64B5F6))
        UserTier.VETERAN -> listOf(Color(0xFF50C878), Color(0xFF81C784))
        UserTier.INFLUENCER -> listOf(Color(0xFFFFD700), Color(0xFFFDD835))
        UserTier.AMBASSADOR -> listOf(Color(0xFF9B59B6), Color(0xFFAB47BC))
        UserTier.ELITE -> listOf(Color(0xFF9B59B6), Color(0xFFBA68C8))
        UserTier.PARTNER -> listOf(Color(0xFFE74C3C), Color(0xFFEF5350))
        UserTier.LEGENDARY -> listOf(Color(0xFFFF6B35), Color(0xFFFF8A65))
        UserTier.TOP_CREATOR -> listOf(Color(0xFFFFD700), Color(0xFFFFA726))
        UserTier.FOUNDER -> listOf(Color(0xFFFFD700), Color(0xFFFF6B35), Color(0xFFE91E63))
        UserTier.CO_FOUNDER -> listOf(Color(0xFFFFD700), Color(0xFFFF6B35))
        UserTier.BUSINESS -> listOf(Color(0xFF00BCD4), Color(0xFF26C6DA))
    }
}

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
    // Tier base rating (matches iOS tierBaseRating)
    val defaultStartingClout = 160.0
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

    // Starting bonus (matches iOS getUserStartingBonus)
    val startingBonus = when {
        user.isVerified -> 2.0   // betaTester
        user.tier == UserTier.FOUNDER || user.tier == UserTier.CO_FOUNDER -> 1.5 // earlyAdopter
        else -> 1.0              // newcomer
    }
    val effectiveRating = tierBase * startingBonus

    // Engagement score from videos (matches iOS engagementScore)
    val engagementScore = if (videos.isNotEmpty()) {
        val totalHypes = videos.sumOf { it.hypeCount }
        val totalCools = videos.sumOf { it.coolCount }
        val totalViews = videos.sumOf { it.viewCount }
        val totalReplies = videos.sumOf { it.replyCount }

        val totalReactions = totalHypes + totalCools
        val engagementRatio = if (totalReactions > 0) totalHypes.toDouble() / totalReactions else 0.5
        val engagementPoints = engagementRatio * 10.0 * 1.5 // InteractionType.hype.pointValue = 10

        val viewEngagementRatio = if (totalViews > 0) totalReactions.toDouble() / totalViews else 0.0
        val viewPoints = minOf(10.0, viewEngagementRatio * 1000.0)

        val replyBonus = minOf(5.0, totalReplies.toDouble() / 50.0 * 5.0)

        engagementPoints + viewPoints + replyBonus
    } else 0.0

    // Activity score (matches iOS activityScore)
    val activityScore = if (videos.isNotEmpty()) {
        val recentCount = videos.count {
            val ageHours = (System.currentTimeMillis() - (it.createdAt?.time ?: 0)) / 3_600_000.0
            ageHours < 168.0 // 7 days (trendingWindowHours)
        }
        minOf(15.0, recentCount * 2.5)
    } else 0.0

    // Clout bonus
    val cloutBonus = minOf(10.0, (user.clout ?: 0).toDouble() / defaultStartingClout * 10.0)

    // Social bonus
    val actualFollowers = if (followerCount > 0) followerCount else (user.followerCount ?: 0)
    val socialBonus = minOf(8.0, actualFollowers.toDouble() / 10.0 * 8.0)

    // Verification bonus
    val verificationBonus = if (user.isVerified) 5.0 else 0.0

    // Final calculation (matches iOS)
    val baseRating = effectiveRating / 100.0 * 50.0
    val bonusPoints = engagementScore + activityScore + cloutBonus + socialBonus + verificationBonus
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

    // Profile state
    var currentUser by remember { mutableStateOf<BasicUserInfo?>(null) }
    var userVideos by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var pinnedVideos by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingVideos by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMoreVideos by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // UI state
    var isShowingFullBio by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var showingEditProfile by remember { mutableStateOf(false) }
    var showingSettings by remember { mutableStateOf(false) }
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
    var userCollections by remember { mutableStateOf<List<VideoCollection>>(emptyList()) }
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
            userVideos = videoService.getUserVideos(userID, limit = 50)
            hasMoreVideos = userVideos.size >= 50
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

    LaunchedEffect(userID) {
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
            .background(Color.Black)
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
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(18.dp))
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
                .background(Color.Black)
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
                            onShowThreadView(threadID, currentVid.id)
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
                    tint = Color.White
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
                    .background(Color.Black)
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
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showingAllCollections = false }) {
                            Icon(Icons.Default.Close, "Close", tint = Color.White)
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

    // ===== BADGE PAGE =====
    // Mounted at the top of ProfileView (sibling to the LazyColumn) so
    // BadgePageView's verticalScroll has bounded constraints. Mounting
    // it inside the scrollable header throws "infinity max height".
    if (showingBadgePage && currentUser != null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(60f)) {
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
            title = { Text("Delete Video", color = Color.White) },
            text = {
                Text(
                    "Are you sure you want to delete '${videoToDelete?.title}'? This action cannot be undone.",
                    color = Color.Gray
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
                TextButton(onClick = { showingDeleteConfirmation = false }) { Text("Cancel", color = Color.Gray) }
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
    onFollowersClick: () -> Unit,
    isFollowing: Boolean = false,
    isFollowLoading: Boolean = false,
    onFollowToggle: () -> Unit = {},
    onShowBadgePage: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 36.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Profile image + info row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EnhancedProfileImage(user = user, videos = videos)

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(user.displayName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (user.isVerified) {
                        Icon(Icons.Default.Verified, "Verified", tint = Color.Red, modifier = Modifier.size(16.dp))
                    }
                }

                Text("@${user.username}", fontSize = 13.sp, color = Color.Gray)

                ProfileTierBadge(tier = user.tier)
            }
        }

        // Bio
        BioSection(user = user, isOwnProfile = isOwnProfile, isShowingFullBio = isShowingFullBio, onToggleBio = onToggleBio, onEditProfile = onEditProfile)

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
            onFollowToggle = onFollowToggle
        )

        // Badge preview — pinned-first, taps open the badge page.
        // Mounting the BadgePageView itself happens at the top of
        // ProfileView (outside the scrolling container) — nesting
        // BadgePageView's verticalScroll inside Profile's scroll
        // throws "infinity max height".
        ProfileBadgePreviewRow(
            userID = user.id,
            isOwner = isOwnProfile,
            onTapView = onShowBadgePage
        )
    }
}

// ===== ENHANCED PROFILE IMAGE (hype progress ring) =====

@Composable
private fun EnhancedProfileImage(user: BasicUserInfo, videos: List<CoreVideoMetadata> = emptyList()) {
    val tierColors = getTierColors(user.tier)
    val hypeProgress = calculateHypeLevel(user, videos, user.followerCount ?: 0) / 100f
    val sweepAngle = 360f * hypeProgress.coerceIn(0f, 1f)

    Box(modifier = Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        // Background ring
        Canvas(modifier = Modifier.size(76.dp)) {
            drawArc(
                color = Color.Gray.copy(alpha = 0.3f),
                startAngle = -90f, sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Hype progress ring
        Canvas(modifier = Modifier.size(76.dp)) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = if (tierColors.size > 1) tierColors else listOf(tierColors[0], tierColors[0])
                ),
                startAngle = -90f, sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Profile image
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(user.profileImageURL ?: "")
                .crossfade(true)
                .build(),
            contentDescription = "Profile",
            modifier = Modifier.size(66.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.3f)),
            contentScale = ContentScale.Crop
        )
    }
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
        Text(tier.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (displayBio.isNotEmpty()) {
            val shouldTruncate = displayBio.length > 80

            if (shouldTruncate && !isShowingFullBio) {
                Text(displayBio, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(displayBio, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
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
                Icon(Icons.Default.AddCircleOutline, null, tint = Color.Gray.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                Text("Add bio", color = Color.Gray.copy(alpha = 0.8f), fontSize = 14.sp)
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
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Whatshot, null, tint = Color(0xFFFF9800), modifier = Modifier.size(14.dp))
                Text("Hype Rating", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("${hypeRating.toInt()}%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Box(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            Box(Modifier.fillMaxSize().background(Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(5.dp)))
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(
                        Brush.horizontalGradient(listOf(Color(0xFF4CAF50), Color(0xFFFFEB3B), Color(0xFFFF9800), Color(0xFFF44336), Color(0xFF9C27B0))),
                        RoundedCornerShape(5.dp)
                    )
            )
        }
    }
}

// ===== STATS ROW =====

@Composable
private fun StatsRow(user: BasicUserInfo, videoCount: Int, onFollowersClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        StatItem(videoCount, "Videos") { }
        StatItem(user.followerCount ?: 0, "Stitchers", onFollowersClick)
        StatItem(user.clout ?: 0, "Clout") { }
    }
}

@Composable
private fun StatItem(count: Int, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(formatLargeNumber(count), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = if (label == "Stitchers") Color.Cyan else Color.Gray, fontSize = 11.sp)
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
    onFollowToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isOwnProfile) {
            Button(
                onClick = onEditProfile,
                modifier = Modifier.weight(1f).height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Edit Profile", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }

            Button(
                onClick = onSettingsClick,
                modifier = Modifier.size(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Settings, "Settings", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        } else {
            Button(
                onClick = onFollowToggle,
                enabled = !isFollowLoading,
                modifier = Modifier.weight(1f).height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isFollowing) Color.White else Color.Cyan),
                shape = RoundedCornerShape(8.dp),
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
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = if (isFollowing) Color.Black else Color.White
                    )
                }
            }

            Button(
                onClick = { /* TODO: Subscribe */ },
                modifier = Modifier.weight(1f).height(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Subscribe", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
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
            Text("Collections", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("See All", color = Color.Cyan, fontSize = 13.sp, modifier = Modifier.clickable { /* TODO */ })
        }

        Spacer(Modifier.height(12.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isOwnProfile) {
                item {
                    Box(
                        modifier = Modifier
                            .size(width = 100.dp, height = 140.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Add, null, tint = Color.Cyan, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(4.dp))
                            Text("New", color = Color.Gray, fontSize = 11.sp)
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
    Row(modifier = Modifier.fillMaxWidth().background(Color.Black).padding(top = 20.dp)) {
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
                        tint = if (isSelected) Color.Cyan.copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(tab.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (isSelected) Color.Cyan else Color.Gray)
                    Text("(${tabCounts.getOrNull(index) ?: 0})", fontSize = 10.sp, color = if (isSelected) Color.Cyan.copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.6f))
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
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
            Text("Loading Profile...", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
            Text("Error", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(error, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
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
            Icon(Icons.Default.PersonOff, null, tint = Color.Gray, modifier = Modifier.size(50.dp))
            Text("No User Found", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
