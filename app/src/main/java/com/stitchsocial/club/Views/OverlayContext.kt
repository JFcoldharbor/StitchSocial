/*
 * ContextualVideoOverlay.kt - WITH FOLLOWMANAGER INTEGRATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Universal Contextual Video Overlay
 * ✅ WORKING: All existing functionality preserved
 * ✅ ADDED: FollowManager integration with follow button
 * ✅ ADDED: Static user cache (5min expiration)
 * ✅ ADDED: Batch user loading
 * ✅ ADDED: Minimal discovery mode
 * ✅ ADDED: Video pause broadcasts
 * ✅ ADDED: BLAZING/FROZEN temperature support
 */

package com.stitchsocial.club.views

import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

// Foundation imports
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.engagement.HypeRatingCalculator
import com.stitchsocial.club.FollowManager

// 3D Button imports (YOUR WORKING IMPORTS)
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// MARK: - Enums

enum class OverlayContext {
    HOME_FEED,
    DISCOVERY,
    PROFILE_OWN,
    PROFILE_OTHER,
    THREAD_VIEW
}

enum class EngagementType {
    HYPE,
    COOL,
    REPLY,
    SHARE,
    STITCH,
    THREAD
}

// MARK: - Overlay Actions

sealed class OverlayAction {
    data class NavigateToProfile(val userID: String) : OverlayAction()
    object NavigateToThread : OverlayAction()
    object Follow : OverlayAction()
    object Unfollow : OverlayAction()
    data class Engagement(val type: EngagementType) : OverlayAction()
    object Share : OverlayAction()
    object StitchRecording : OverlayAction()
}

// MARK: - Static User Cache (NEW)

private data class CachedUserData(
    val displayName: String,
    val profileImageURL: String?,
    val tier: UserTier?,
    val cachedAt: Date
)

private object UserDataCache {
    private val cache = ConcurrentHashMap<String, CachedUserData>()
    private val timestamps = ConcurrentHashMap<String, Date>()
    private const val CACHE_EXPIRATION_MS = 300_000L // 5 minutes
    private const val BATCH_SIZE = 10

    fun get(userID: String): CachedUserData? {
        val timestamp = timestamps[userID] ?: return null
        val now = Date()

        if (now.time - timestamp.time > CACHE_EXPIRATION_MS) {
            cache.remove(userID)
            timestamps.remove(userID)
            return null
        }

        return cache[userID]
    }

    fun put(userID: String, data: CachedUserData) {
        cache[userID] = data
        timestamps[userID] = Date()
    }

    fun clearExpired() {
        val now = Date()
        val expired = timestamps.filterValues { now.time - it.time > CACHE_EXPIRATION_MS }
        expired.keys.forEach { userID ->
            cache.remove(userID)
            timestamps.remove(userID)
        }
    }

    suspend fun batchLoad(userIDs: List<String>, userService: UserService) {
        val uncached = userIDs.filter { get(it) == null }
        if (uncached.isEmpty()) return

        println("CACHE: Batch loading ${uncached.size} users")

        uncached.take(BATCH_SIZE).forEach { userID ->
            try {
                val user = userService.getBasicUserInfo(userID)
                if (user != null) {
                    put(userID, CachedUserData(
                        displayName = user.displayName,
                        profileImageURL = user.profileImageURL,
                        tier = user.tier,
                        cachedAt = Date()
                    ))
                }
            } catch (e: Exception) {
                println("CACHE: Failed to load user $userID: ${e.message}")
            }
        }
    }
}

// MARK: - Video Pause Helper (NEW)

private fun pauseAllVideos(context: Context) {
    val intent = Intent("com.stitchsocial.PAUSE_ALL_VIDEOS")
    LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
}

// MARK: - Main Composable

@Composable
fun ContextualVideoOverlay(
    video: CoreVideoMetadata,
    overlayContext: OverlayContext = OverlayContext.HOME_FEED,
    currentUserID: String? = null,
    threadVideo: CoreVideoMetadata? = null,
    isVisible: Boolean = true,
    currentUserTier: UserTier = UserTier.ROOKIE,
    followManager: FollowManager? = null,
    engagementViewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    navigationCoordinator: com.stitchsocial.club.coordination.NavigationCoordinator? = null,
    onAction: ((OverlayAction) -> Unit)? = null
) {
    if (!isVisible) return

    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current

    // Services
    val userService = remember { UserService(context) }
    val authService = remember { AuthService() }
    val videoService = remember { com.stitchsocial.club.services.VideoServiceImpl() }

    // State
    var isLoadingUserData by remember { mutableStateOf(true) }
    var realCreatorName by remember { mutableStateOf<String?>(null) }
    var realCreatorProfileImageURL by remember { mutableStateOf<String?>(null) }
    var realThreadCreatorName by remember { mutableStateOf<String?>(null) }
    var realThreadCreatorProfileImageURL by remember { mutableStateOf<String?>(null) }

    // Follow state
    var isFollowing by remember { mutableStateOf(false) }
    var isFollowLoading by remember { mutableStateOf(false) }

    // ✅ NEW: Load cached or fresh user data with batch loading
    LaunchedEffect(video.creatorID, threadVideo?.creatorID) {
        isLoadingUserData = true

        UserDataCache.clearExpired()

        // Try cache first
        val cachedCreator = UserDataCache.get(video.creatorID)
        if (cachedCreator != null) {
            realCreatorName = cachedCreator.displayName
            realCreatorProfileImageURL = cachedCreator.profileImageURL
        }

        val cachedThread = threadVideo?.let { UserDataCache.get(it.creatorID) }
        if (cachedThread != null) {
            realThreadCreatorName = cachedThread.displayName
            realThreadCreatorProfileImageURL = cachedThread.profileImageURL
        }

        // Batch load missing users
        val usersToLoad = mutableListOf<String>()
        if (cachedCreator == null) usersToLoad.add(video.creatorID)
        if (threadVideo != null && cachedThread == null) usersToLoad.add(threadVideo.creatorID)

        if (usersToLoad.isNotEmpty()) {
            UserDataCache.batchLoad(usersToLoad, userService)

            // Update UI with fresh cache
            UserDataCache.get(video.creatorID)?.let {
                realCreatorName = it.displayName
                realCreatorProfileImageURL = it.profileImageURL
            }

            threadVideo?.let { thread ->
                UserDataCache.get(thread.creatorID)?.let {
                    realThreadCreatorName = it.displayName
                    realThreadCreatorProfileImageURL = it.profileImageURL
                }
            }
        }

        isLoadingUserData = false
    }

    // ✅ REACTIVE: Observe follow state from FollowManager
    LaunchedEffect(video.creatorID, followManager) {
        followManager?.let { manager ->
            // Load initial state
            manager.loadFollowState(video.creatorID)

            // Observe state changes
            manager.followingStates.collect { states ->
                isFollowing = states[video.creatorID] ?: false
                println("🔄 OVERLAY: Follow state updated for ${video.creatorID}: $isFollowing")
            }
        }
    }

    // ✅ REACTIVE: Observe loading state from FollowManager
    LaunchedEffect(video.creatorID, followManager) {
        followManager?.let { manager ->
            manager.loadingStates.collect { loadingSet ->
                isFollowLoading = loadingSet.contains(video.creatorID)
                println("⏳ OVERLAY: Loading state for ${video.creatorID}: $isFollowLoading")
            }
        }
    }

    // ✅ FIXED: Temperature calculation with BLAZING/FROZEN from foundation Temperature enum
    val temperature = remember(video.hypeCount, video.coolCount, video.viewCount, video.createdAt) {
        val ageInMinutes = ((System.currentTimeMillis() - video.createdAt.time) / 60000.0).coerceAtLeast(1.0)
        val videoTemp = HypeRatingCalculator.calculateTemperature(
            hypeCount = video.hypeCount,
            coolCount = video.coolCount,
            viewCount = video.viewCount,
            ageInMinutes = ageInMinutes,
            creatorTier = currentUserTier
        )
        when (videoTemp) {
            com.stitchsocial.club.engagement.VideoTemperature.HOT -> Temperature.HOT
            com.stitchsocial.club.engagement.VideoTemperature.WARM -> Temperature.WARM
            com.stitchsocial.club.engagement.VideoTemperature.COOL -> Temperature.COOL
            com.stitchsocial.club.engagement.VideoTemperature.COLD -> Temperature.COLD
        }
    }

    val temperatureColor = when (temperature) {
        Temperature.BLAZING -> Color.Red
        Temperature.HOT -> Color.Red
        Temperature.WARM -> Color(0xFFFFA500)
        Temperature.COOL -> Color.Blue
        Temperature.COLD -> Color.Cyan
        Temperature.FROZEN -> Color.Cyan
    }

    // Visibility conditions
    val shouldShowFollow = overlayContext != OverlayContext.PROFILE_OWN &&
            currentUserID != null &&
            currentUserID != video.creatorID

    val shouldShowEngagement = overlayContext != OverlayContext.PROFILE_OWN

    // ✅ NEW: Route to minimal vs full overlay
    Box(modifier = Modifier.fillMaxSize()) {
        if (overlayContext == OverlayContext.DISCOVERY) {
            // MINIMAL DISCOVERY OVERLAY
            MinimalDiscoveryOverlay(
                video = video,
                creatorName = realCreatorName ?: video.creatorName,
                temperatureColor = temperatureColor,
                context = context,
                onAction = onAction
            )
        } else {
            // FULL OVERLAY (EXISTING)
            FullOverlayContent(
                video = video,
                overlayContext = overlayContext,
                threadVideo = threadVideo,
                realCreatorName = realCreatorName,
                realCreatorProfileImageURL = realCreatorProfileImageURL,
                realThreadCreatorName = realThreadCreatorName,
                realThreadCreatorProfileImageURL = realThreadCreatorProfileImageURL,
                isLoadingUserData = isLoadingUserData,
                isFollowing = isFollowing,
                isFollowLoading = isFollowLoading,
                shouldShowFollow = shouldShowFollow,
                shouldShowEngagement = shouldShowEngagement,
                currentUserTier = currentUserTier,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                followManager = followManager,
                navigationCoordinator = navigationCoordinator,
                videoService = videoService,
                context = context,
                hapticFeedback = hapticFeedback,
                scope = scope,
                onAction = onAction
            )
        }
    }
}

// MARK: - Minimal Discovery Overlay (NEW)

@Composable
private fun MinimalDiscoveryOverlay(
    video: CoreVideoMetadata,
    creatorName: String,
    temperatureColor: Color,
    context: Context,
    onAction: ((OverlayAction) -> Unit)?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top: Creator name with temperature dot
        Row(
            modifier = Modifier
                .padding(top = 12.dp, start = 12.dp)
                .clickable {
                    pauseAllVideos(context)
                    onAction?.invoke(OverlayAction.NavigateToProfile(video.creatorID))
                }
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(temperatureColor, CircleShape)
            )

            Text(
                text = creatorName,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Bottom: Video title only
        if (video.title.isNotEmpty()) {
            Row(modifier = Modifier.padding(bottom = 16.dp, start = 12.dp, end = 12.dp)) {
                Text(
                    text = video.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

// MARK: - Full Overlay Content (EXISTING with pause broadcasts and FollowManager)

@Composable
private fun FullOverlayContent(
    video: CoreVideoMetadata,
    overlayContext: OverlayContext,
    threadVideo: CoreVideoMetadata?,
    realCreatorName: String?,
    realCreatorProfileImageURL: String?,
    realThreadCreatorName: String?,
    realThreadCreatorProfileImageURL: String?,
    isLoadingUserData: Boolean,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    shouldShowFollow: Boolean,
    shouldShowEngagement: Boolean,
    currentUserTier: UserTier,
    engagementViewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    followManager: FollowManager?,
    navigationCoordinator: com.stitchsocial.club.coordination.NavigationCoordinator?,
    videoService: com.stitchsocial.club.services.VideoServiceImpl,
    context: Context,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    scope: kotlinx.coroutines.CoroutineScope,
    onAction: ((OverlayAction) -> Unit)?
) {
    // 👁️ VIEW TRACKING: Local state that increments on first view
    var displayViewCount by remember(video.id) { mutableStateOf(video.viewCount) }
    var hasTrackedView by remember(video.id) { mutableStateOf(false) }

    // 👁️ VIEWERS BOTTOM SHEET STATE
    var showViewersSheet by remember { mutableStateOf(false) }
    var isLoadingViewers by remember { mutableStateOf(false) }
    var viewers by remember { mutableStateOf<List<ViewerInfo>>(emptyList()) }

    // 👁️ Track view when video appears
    LaunchedEffect(video.id) {
        if (!hasTrackedView) {
            hasTrackedView = true
            displayViewCount += 1
            // Track view in backend
            engagementViewModel.trackView(video.id)
            println("👁️ VIEW TRACKED: ${video.id} - Count now: $displayViewCount")
        }
    }

    // 👁️ Load viewers when sheet opens
    LaunchedEffect(showViewersSheet) {
        if (showViewersSheet) {
            isLoadingViewers = true
            try {
                // Load viewers from backend (includes user data)
                val viewersList = videoService.getViewers(video.id)
                viewers = viewersList.map { viewerData ->
                    // Convert tier string to UserTier enum
                    val tierEnum = try {
                        UserTier.valueOf(viewerData.tier)
                    } catch (e: Exception) {
                        UserTier.ROOKIE
                    }

                    ViewerInfo(
                        userID = viewerData.userID,
                        displayName = viewerData.displayName,
                        username = viewerData.username,
                        profileImageURL = viewerData.profileImageURL,
                        tier = tierEnum,
                        viewedAt = viewerData.viewedAt,
                        isFollowing = followManager?.isFollowing(viewerData.userID) ?: false
                    )
                }
                println("👁️ LOADED ${viewers.size} VIEWERS for video ${video.id}")
            } catch (e: Exception) {
                println("❌ ERROR loading viewers: ${e.message}")
                viewers = emptyList()
            }
            isLoadingViewers = false
        }
    }

    // 👁️ VIEWERS BOTTOM SHEET
    ViewersBottomSheet(
        isVisible = showViewersSheet,
        videoID = video.id,
        viewCount = displayViewCount,
        viewers = viewers,
        isLoading = isLoadingViewers,
        onDismiss = { showViewersSheet = false },
        onViewerClick = { userID ->
            println("👤 VIEWER CLICKED: $userID - Navigating to profile")
            showViewersSheet = false
            pauseAllVideos(context)
            scope.launch {
                delay(100)
                // Use onAction callback for navigation
                onAction?.invoke(OverlayAction.NavigateToProfile(userID))
            }
        },
        onFollowClick = { userID ->
            scope.launch {
                followManager?.toggleFollow(userID)
                // Update the viewer's follow state in the list
                viewers = viewers.map { viewer ->
                    if (viewer.userID == userID) {
                        viewer.copy(isFollowing = !viewer.isFollowing)
                    } else {
                        viewer
                    }
                }
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Top Left - Creator Profile Pill
        if (overlayContext != OverlayContext.PROFILE_OWN) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 60.dp, start = 16.dp)
            ) {
                CreatorProfilePill(
                    creatorName = realCreatorName ?: video.creatorName,
                    profileImageURL = realCreatorProfileImageURL,
                    isLoading = isLoadingUserData,
                    onClick = {
                        pauseAllVideos(context)
                        scope.launch {
                            delay(100)
                            onAction?.invoke(OverlayAction.NavigateToProfile(video.creatorID))
                        }
                    }
                )

                if (video.isChild && threadVideo != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ThreadIndicatorPill(
                        threadCreatorName = realThreadCreatorName ?: threadVideo.creatorName,
                        profileImageURL = realThreadCreatorProfileImageURL,
                        isLoading = isLoadingUserData,
                        onClick = {
                            pauseAllVideos(context)
                            scope.launch {
                                delay(100)
                                onAction?.invoke(OverlayAction.NavigateToThread)
                            }
                        }
                    )
                }
            }
        }

        // Top Right - More Button
        if (overlayContext == OverlayContext.PROFILE_OWN) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 50.dp, end = 16.dp)
            ) {
                IconButton(onClick = {}) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = Color.White,
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            .padding(6.dp)
                    )
                }
            }
        }

        // Bottom Section - Metadata & Engagement
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 100.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
        ) {
            // Thread breadcrumb
            if (threadVideo != null && threadVideo.id != video.id) {
                ThreadBreadcrumb(
                    threadVideo = threadVideo,
                    onClick = {
                        pauseAllVideos(context)
                        scope.launch {
                            delay(100)
                            onAction?.invoke(OverlayAction.NavigateToThread)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Video Title
            if (video.title.isNotEmpty()) {
                Text(
                    text = video.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Metadata Row WITH FOLLOW BUTTON (Swift match)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 👁️ TAPPABLE VIEW COUNT - Opens viewers bottom sheet
                MetadataChip(
                    icon = Icons.Default.RemoveRedEye,
                    count = displayViewCount,
                    label = "views",
                    color = Color.White,
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        showViewersSheet = true
                        println("👁️ VIEWERS: Opening bottom sheet for ${video.id}")
                    }
                )

                if (overlayContext != OverlayContext.PROFILE_OWN) {
                    MetadataChip(
                        icon = Icons.Default.ContentCut,
                        count = video.replyCount,
                        label = "stitches",
                        color = Color.Cyan,
                        onClick = {
                            pauseAllVideos(context)
                            scope.launch {
                                delay(100)
                                onAction?.invoke(OverlayAction.NavigateToThread)
                            }
                        }
                    )
                }

                // ✅ SWIFT MATCH: Circular icon follow button in metadata row
                if (shouldShowFollow) {
                    FollowButton(
                        isFollowing = isFollowing,
                        isLoading = isFollowLoading,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            followManager?.toggleFollow(video.creatorID) ?: run {
                                println("⚠️ FOLLOW: FollowManager not provided")
                                onAction?.invoke(OverlayAction.Follow)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Engagement Buttons
            BottomEngagementButtons(
                video = video,
                shouldShowEngagement = shouldShowEngagement,
                currentUserTier = currentUserTier,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                navigationCoordinator = navigationCoordinator,
                context = context,
                onAction = onAction,
                hapticFeedback = hapticFeedback
            )
        }
    }
}

// ✅ SWIFT MATCH: Circular icon follow button (person.fill)

@Composable
private fun FollowButton(
    isFollowing: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(
                color = Color.Black.copy(alpha = 0.3f),
                shape = CircleShape
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.3f),
                shape = CircleShape
            )
            .clickable(enabled = !isLoading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = Color.White,
                strokeWidth = 1.5.dp
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = if (isFollowing) "Following" else "Follow",
                tint = if (isFollowing) Color(0xFF9C27B0) else Color.Transparent,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// MARK: - Bottom Engagement Buttons (with pause broadcasts)

@Composable
fun BottomEngagementButtons(
    video: CoreVideoMetadata,
    shouldShowEngagement: Boolean,
    currentUserTier: UserTier,
    engagementViewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    navigationCoordinator: com.stitchsocial.club.coordination.NavigationCoordinator?,
    context: Context,
    onAction: ((OverlayAction) -> Unit)?,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Thread + Cool
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircleEngagementButton(
                icon = Icons.Default.Link,
                count = null,
                label = "Thread",
                color = Color.Cyan,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    pauseAllVideos(context)
                    kotlinx.coroutines.GlobalScope.launch {
                        delay(100)
                        onAction?.invoke(OverlayAction.NavigateToThread)
                    }
                }
            )

            if (shouldShowEngagement) {
                ProgressiveCoolButton3D(
                    videoID = video.id,
                    userTier = currentUserTier,
                    coolCount = video.coolCount,
                    viewModel = engagementViewModel,
                    iconManager = iconManager
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Right: Hype + Stitch
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (shouldShowEngagement) {
                ProgressiveHypeButton3D(
                    videoID = video.id,
                    userTier = currentUserTier,
                    hypeCount = video.hypeCount,
                    viewModel = engagementViewModel,
                    iconManager = iconManager
                )
            }

            if (shouldShowEngagement) {
                CircleEngagementButton(
                    icon = Icons.Default.ContentCut,
                    count = null,
                    label = null,
                    color = Color.Magenta,
                    onClick = {
                        println("🎬 STITCH BUTTON CLICKED!")
                        println("🎬 navigationCoordinator = $navigationCoordinator")

                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)

                        // Stop all videos
                        pauseAllVideos(context)
                        println("📡 STITCH: Broadcast sent to pause videos")

                        // Wait for videos to stop before opening camera
                        kotlinx.coroutines.GlobalScope.launch {
                            delay(200) // Give videos time to stop

                            if (navigationCoordinator == null) {
                                println("❌ STITCH: NavigationCoordinator is NULL!")
                            } else {
                                println("✅ STITCH: Opening camera...")

                                // Open camera in stitch mode
                                navigationCoordinator.showModal(
                                    com.stitchsocial.club.coordination.ModalState.RECORDING,
                                    mapOf(
                                        "context" to com.stitchsocial.club.camera.RecordingContext.StitchToThread(
                                            threadId = video.id,
                                            threadInfo = com.stitchsocial.club.camera.ThreadInfo(
                                                title = video.title,
                                                creatorName = video.creatorName,
                                                creatorId = video.creatorID,
                                                participantCount = 0,
                                                stitchCount = 0
                                            )
                                        )
                                    )
                                )
                                println("✅ STITCH: showModal called")
                            }
                        }
                    }
                )
            }
        }
    }
}

// MARK: - Supporting Components (EXISTING - No changes)

@Composable
fun CircleEngagementButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int?,
    label: String?,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                .border(1.5.dp, color.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        if (count != null || label != null) {
            Text(
                text = count?.let { if (it >= 1000) "${it / 1000}k" else it.toString() }
                    ?: label ?: "",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun CreatorProfilePill(
    creatorName: String,
    profileImageURL: String?,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(profileImageURL ?: "")
                .crossfade(true)
                .build(),
            contentDescription = "Profile",
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.3f)),
            contentScale = ContentScale.Crop
        )

        Text(
            text = "@$creatorName",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )

        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "View Profile",
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
fun ThreadIndicatorPill(
    threadCreatorName: String,
    profileImageURL: String?,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(Color.Cyan.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.ArrowUpward,
            contentDescription = "Thread",
            tint = Color.Cyan,
            modifier = Modifier.size(12.dp)
        )

        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(profileImageURL ?: "")
                .crossfade(true)
                .build(),
            contentDescription = "Thread Creator",
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.3f)),
            contentScale = ContentScale.Crop
        )

        Text(
            text = "@$threadCreatorName",
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ThreadBreadcrumb(
    threadVideo: CoreVideoMetadata,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.ArrowUpward,
            contentDescription = "Thread",
            tint = Color.Cyan.copy(alpha = 0.7f),
            modifier = Modifier.size(8.dp)
        )

        Text(
            text = "Thread by @${threadVideo.creatorName}",
            color = Color.Cyan.copy(alpha = 0.7f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium
        )

        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "Go",
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(8.dp)
        )
    }
}

@Composable
fun MetadataChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    label: String,
    color: Color,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(10.dp)
        )

        Text(
            text = if (count >= 1000) "${count / 1000}k" else count.toString(),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun FollowMetadataButton(
    isFollowing: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick, enabled = !isLoading)
            .background(
                if (isFollowing) Color.Gray.copy(alpha = 0.3f)
                else Color(0xFFFF6B35).copy(alpha = 0.9f),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = Color.White
            )
        } else {
            Text(
                text = if (isFollowing) "Following" else "Follow",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}