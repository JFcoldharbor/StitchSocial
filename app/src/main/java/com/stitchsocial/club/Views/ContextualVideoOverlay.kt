/*
 * ContextualVideoOverlay.kt - UNIVERSAL CONTEXTUAL VIDEO OVERLAY
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Universal Contextual Video Overlay with Viewer Tracking
 * Dependencies: EngagementViewModel, UserService, AuthService, FollowManager, VideoService
 * Features: Static overlay, special user permissions, context-aware profile navigation
 *
 * UPDATED: Fixed sizing - non-scaled fonts, responsive padding, size constraints
 * UPDATED: Integrated ShareButton, SwipeForRepliesBanner, TaggedUsersRow from iOS
 * Ã¢Å“â€¦ ADDED: Automatic view tracking when video is displayed
 * Ã¢Å“â€¦ ADDED: ThreadView integration (iOS-style fullscreen)
 */

package com.stitchsocial.club.views

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

// StateFlow collection
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// Foundation imports
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.ShareButton
import com.stitchsocial.club.ShareButtonSize
import com.stitchsocial.club.engagement.HypeRatingCalculator
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.FollowManager

// 3D Button imports
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager

// ============================================================================
// ThreadView import
// ============================================================================
import com.stitchsocial.club.ui.screens.ThreadView

// ============================================================================
// MARK: - NON-SCALED TEXT SIZE UTILITIES
// ============================================================================

/**
 * Extension to create non-scaled sp values
 * This prevents text from scaling with system accessibility font settings
 * Use this for overlay UI elements that must maintain fixed sizes
 */
@Composable
fun Int.fixedSp(): TextUnit {
    val density = LocalDensity.current
    return with(density) {
        // Convert dp to sp, effectively ignoring font scale
        (this@fixedSp / density.fontScale).sp
    }
}

@Composable
fun Float.fixedSp(): TextUnit {
    val density = LocalDensity.current
    return with(density) {
        (this@fixedSp / density.fontScale).sp
    }
}

/**
 * Object containing all fixed overlay text sizes
 * Centralized for easy adjustment
 */
object OverlaySizes {
    // Text sizes (will be converted to non-scaled sp)
    const val LABEL_TINY = 9
    const val LABEL_SMALL = 10
    const val LABEL_MEDIUM = 11
    const val LABEL_REGULAR = 12
    const val LABEL_LARGE = 13
    const val TITLE = 14

    // Component sizes (dp - already fixed)
    val BUTTON_SIZE = 42.dp
    val BUTTON_SIZE_SMALL = 32.dp
    val BUTTON_SIZE_LARGE = 52.dp
    val ICON_SIZE = 18.dp
    val ICON_SIZE_SMALL = 14.dp
    val ICON_SIZE_TINY = 10.dp
    val PROFILE_IMAGE = 24.dp
    val PROFILE_IMAGE_THREAD = 28.dp

    // Spacing
    val BOTTOM_PADDING_MIN = 60.dp  // Higher from tab bar for HomeFeed
    val BOTTOM_PADDING_MAX = 80.dp  // Higher from tab bar for large screens
}

// MARK: - Enums

enum class OverlayContext {
    HOME_FEED,
    DISCOVERY,
    PROFILE_OWN,
    PROFILE_OTHER,
    THREAD_VIEW,
    CAROUSEL  // Minimal overlay for CardVideoCarouselView
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

// MARK: - Static User Cache

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

    fun set(userID: String, data: CachedUserData) {
        cache[userID] = data
        timestamps[userID] = Date()
    }

    fun clearExpired() {
        val now = Date()
        timestamps.entries.filter { now.time - it.value.time > CACHE_EXPIRATION_MS }
            .forEach { entry ->
                cache.remove(entry.key)
                timestamps.remove(entry.key)
            }
    }
}

// MARK: - Video Engagement Data

private data class ContextualVideoEngagement(
    val videoID: String,
    val creatorID: String,
    var hypeCount: Int,
    var coolCount: Int,
    var shareCount: Int,
    var replyCount: Int,
    var viewCount: Int,
    var lastEngagementAt: Date
) {
    val totalEngagements: Int get() = hypeCount + coolCount
    val engagementRatio: Double get() {
        val total = totalEngagements
        return if (total > 0) hypeCount.toDouble() / total.toDouble() else 0.5
    }
}

// MARK: - Helper: Pause All Videos

private fun pauseAllVideos(context: Context) {
    val intent = Intent("com.stitchsocial.club.PAUSE_ALL_VIDEOS")
    LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
}

// MARK: - Main Overlay Composable

@Composable
fun ContextualVideoOverlay(
    video: CoreVideoMetadata,
    overlayContext: OverlayContext,
    currentUserID: String? = null,
    threadVideo: CoreVideoMetadata? = null,
    isVisible: Boolean = true,
    currentUserTier: UserTier = UserTier.ROOKIE,
    engagementViewModel: EngagementViewModel? = null,
    iconManager: FloatingIconManager? = null,
    followManager: FollowManager? = null,
    navigationCoordinator: com.stitchsocial.club.coordination.NavigationCoordinator? = null,
    actualReplyCount: Int? = null,
    onAction: ((OverlayAction) -> Unit)? = null
) {
    // Early return if not visible
    if (!isVisible) return
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Get screen configuration for responsive sizing
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeight = configuration.screenHeightDp.dp

    // Debug: Log font scale to detect accessibility settings
    LaunchedEffect(Unit) {
        Log.d("OVERLAY_SIZE", "Screen: ${configuration.screenWidthDp}x${configuration.screenHeightDp}dp")
        Log.d("OVERLAY_SIZE", "Density: ${density.density}, FontScale: ${density.fontScale}")
        if (density.fontScale > 1.0f) {
            Log.w("OVERLAY_SIZE", "Ã¢Å¡Â Ã¯Â¸Â Font scaling active (${density.fontScale}x) - using fixed sizes")
        }
    }

    // Calculate responsive bottom padding based on screen height
    val bottomPadding = remember(screenHeight) {
        when {
            screenHeight < 600.dp -> OverlaySizes.BOTTOM_PADDING_MIN
            screenHeight > 800.dp -> OverlaySizes.BOTTOM_PADDING_MAX
            else -> 70.dp // Medium screens - higher from tab bar for HomeFeed
        }
    }

    // State
    var isLoadingUserData by remember { mutableStateOf(false) }
    var realCreatorName by remember { mutableStateOf<String?>(null) }
    var realCreatorProfileImageURL by remember { mutableStateOf<String?>(null) }
    var realThreadCreatorName by remember { mutableStateOf<String?>(null) }
    var realThreadCreatorProfileImageURL by remember { mutableStateOf<String?>(null) }
    var videoEngagement by remember { mutableStateOf<ContextualVideoEngagement?>(null) }
    var videoDescription by remember { mutableStateOf<String?>(null) }
    var showViewersSheet by remember { mutableStateOf(false) }

    // Services for view tracking
    val videoService = remember { VideoServiceImpl() }
    val userService = remember { UserService(context) }
    val authService = remember { AuthService() }

    // Create EngagementCoordinator if needed
    val engagementCoordinator = remember {
        com.stitchsocial.club.coordination.EngagementCoordinator(
            videoService = videoService,
            userService = userService
        )
    }

    // Track video views - only once per video per user session
    LaunchedEffect(video.id, currentUserID) {
        // Only track if we have a current user
        if (currentUserID != null) {
            try {
                // Get user data for viewer record
                val userData = userService.getUserProfile(currentUserID)

                val viewerData = mapOf(
                    "displayName" to (userData?.displayName ?: "User"),
                    "username" to (userData?.username ?: ""),
                    "profileImageURL" to (userData?.profileImageURL ?: ""),
                    "tier" to (userData?.tier?.name ?: "ROOKIE")
                )

                // Record the view in database
                videoService.recordVideoView(video.id, currentUserID, viewerData)

                // Update local engagement data to reflect the new view
                videoEngagement?.let { currentEngagement ->
                    videoEngagement = currentEngagement.copy(
                        viewCount = currentEngagement.viewCount + 1
                    )
                }

                Log.d("VIEW_TRACKING", "Ã¢Å“â€¦ Recorded view for video ${video.id} by user $currentUserID")
            } catch (e: Exception) {
                Log.e("VIEW_TRACKING", "Ã¢ÂÅ’ Failed to record view: ${e.message}")
            }
        } else {
            Log.d("VIEW_TRACKING", "Ã¢Å¡Â Ã¯Â¸Â No currentUserID - skipping view tracking for video ${video.id}")
        }
    }

    // =========================================================================
    // FOLLOW STATE - Observe from FollowManager StateFlow for app-wide sync
    // =========================================================================

    // Collect follow states from FollowManager (reactive - updates across app)
    val followingStates by followManager?.followingStates?.collectAsStateWithLifecycle(
        initialValue = emptyMap()
    ) ?: remember { mutableStateOf(emptyMap()) }

    val loadingStates by followManager?.loadingStates?.collectAsStateWithLifecycle(
        initialValue = emptySet()
    ) ?: remember { mutableStateOf(emptySet()) }

    // Derive follow state from the observed StateFlow
    val isFollowing = followingStates[video.creatorID] ?: false
    val isFollowLoading = loadingStates.contains(video.creatorID)

    // Debug: Log when follow state changes
    LaunchedEffect(isFollowing) {
        Log.d("OVERLAY_FOLLOW", "Follow state changed for ${video.creatorID}: $isFollowing")
    }

    // Computed properties
    val displayReplyCount: Int = actualReplyCount ?: video.replyCount
    val isUserVideo: Boolean = currentUserID != null && video.creatorID == currentUserID
    val shouldShowMinimalDisplay: Boolean = overlayContext == OverlayContext.DISCOVERY

    // Display names with cache fallback
    val displayCreatorName: String = realCreatorName ?: video.creatorName.ifEmpty { "Loading..." }
    val displayThreadCreatorName: String = realThreadCreatorName ?: threadVideo?.creatorName ?: displayCreatorName

    // Temperature color
    val temperatureColor: Color = when (video.temperature) {
        Temperature.HOT, Temperature.BLAZING -> Color.Red
        Temperature.WARM -> Color(0xFFFF8C00)
        Temperature.COOL -> Color.Blue
        Temperature.COLD, Temperature.FROZEN -> Color.Cyan
        else -> Color.Gray
    }

    // Can reply logic (self-stitching support)
    val canReply: Boolean = remember(video.conversationDepth, isUserVideo, overlayContext) {
        // Allow replies at all depths (removed depth > 1 restriction)
        if (isUserVideo) {
            when (overlayContext) {
                OverlayContext.PROFILE_OWN, OverlayContext.HOME_FEED, OverlayContext.THREAD_VIEW, OverlayContext.CAROUSEL -> true
                else -> false
            }
        } else true
    }

    // Stitch button properties
    val stitchButtonIcon: androidx.compose.ui.graphics.vector.ImageVector = if (isUserVideo) Icons.Default.AddCircle else Icons.Default.ContentCut
    val stitchButtonLabel: String = if (isUserVideo) "Continue" else "Stitch"
    val stitchButtonRingColor: Color = if (isUserVideo) Color.Green else Color(0xFF9C27B0)

    // Load user data - fetch from service if not cached
    LaunchedEffect(video.creatorID) {
        println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: Fetching profile for creatorID: ${video.creatorID}")
        println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: video.creatorName: ${video.creatorName}")

        val cached = UserDataCache.get(video.creatorID)
        if (cached != null) {
            println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: Found in cache: ${cached.displayName}")
            realCreatorName = cached.displayName
            realCreatorProfileImageURL = cached.profileImageURL
        } else {
            // Fetch from UserService
            try {
                isLoadingUserData = true
                println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: Calling userService.getUserProfile...")
                val profile = userService.getUserProfile(video.creatorID)
                println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: Profile result: $profile")
                if (profile != null) {
                    realCreatorName = profile.displayName.ifEmpty { profile.username }
                    realCreatorProfileImageURL = profile.profileImageURL
                    println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: displayName=${realCreatorName}, imageURL=${realCreatorProfileImageURL}")
                    // Cache the result
                    UserDataCache.set(video.creatorID, CachedUserData(
                        displayName = realCreatorName ?: "",
                        profileImageURL = realCreatorProfileImageURL,
                        tier = null,
                        cachedAt = Date()
                    ))
                    println("Ã°Å¸â€˜Â¤ OVERLAY: Fetched creator profile: ${profile.displayName}")
                } else {
                    println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: Profile was NULL!")
                }
            } catch (e: Exception) {
                println("Ã¢ÂÅ’ OVERLAY: Failed to fetch creator profile: ${e.message}")
                e.printStackTrace()
            } finally {
                isLoadingUserData = false
            }
        }
    }

    LaunchedEffect(threadVideo?.creatorID) {
        threadVideo?.creatorID?.let { creatorID ->
            val cached = UserDataCache.get(creatorID)
            if (cached != null) {
                realThreadCreatorName = cached.displayName
                realThreadCreatorProfileImageURL = cached.profileImageURL
            } else {
                // Fetch from UserService
                try {
                    val profile = userService.getUserProfile(creatorID)
                    if (profile != null) {
                        realThreadCreatorName = profile.displayName.ifEmpty { profile.username }
                        realThreadCreatorProfileImageURL = profile.profileImageURL
                        // Cache the result
                        UserDataCache.set(creatorID, CachedUserData(
                            displayName = realThreadCreatorName ?: "",
                            profileImageURL = realThreadCreatorProfileImageURL,
                            tier = null,
                            cachedAt = Date()
                        ))
                        println("Ã°Å¸â€˜Â¤ OVERLAY: Fetched thread creator profile: ${profile.displayName}")
                    }
                } catch (e: Exception) {
                    println("Ã¢ÂÅ’ OVERLAY: Failed to fetch thread creator profile: ${e.message}")
                }
            }
        }
    }

    // Load follow state from server on first appearance
    // The StateFlow will automatically update when state changes
    LaunchedEffect(video.creatorID) {
        followManager?.loadFollowState(video.creatorID)
        Log.d("OVERLAY_FOLLOW", "Loading follow state for ${video.creatorID}")
    }

    // Initialize engagement data
    LaunchedEffect(video.id) {
        videoEngagement = ContextualVideoEngagement(
            videoID = video.id,
            creatorID = video.creatorID,
            hypeCount = video.hypeCount,
            coolCount = video.coolCount,
            shareCount = video.shareCount,
            replyCount = video.replyCount,
            viewCount = video.viewCount,
            lastEngagementAt = Date()
        )
    }

    // Render
    Box(modifier = Modifier.fillMaxSize()) {
        if (shouldShowMinimalDisplay) {
            MinimalDiscoveryOverlay(
                video = video,
                displayCreatorName = displayCreatorName,
                temperatureColor = temperatureColor,
                displayReplyCount = displayReplyCount,
                context = context,
                onAction = onAction
            )
        } else if (overlayContext == OverlayContext.CAROUSEL) {
            // Carousel-specific overlay - just engagement buttons
            CarouselOverlay(
                video = video,
                canReply = canReply,
                stitchButtonIcon = stitchButtonIcon,
                stitchButtonLabel = stitchButtonLabel,
                stitchButtonRingColor = stitchButtonRingColor,
                videoEngagement = videoEngagement,
                currentUserTier = currentUserTier,
                currentUserID = currentUserID,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                context = context,
                hapticFeedback = hapticFeedback,
                onAction = onAction
            )
        } else {
            FullContextualOverlay(
                video = video,
                overlayContext = overlayContext,
                threadVideo = threadVideo,
                displayCreatorName = displayCreatorName,
                displayCreatorProfileImageURL = realCreatorProfileImageURL,
                displayThreadCreatorName = displayThreadCreatorName,
                displayThreadCreatorProfileImageURL = realThreadCreatorProfileImageURL,
                temperatureColor = temperatureColor,
                displayReplyCount = displayReplyCount,
                isUserVideo = isUserVideo,
                isFollowing = isFollowing,
                isFollowLoading = isFollowLoading,
                canReply = canReply,
                stitchButtonIcon = stitchButtonIcon,
                stitchButtonLabel = stitchButtonLabel,
                stitchButtonRingColor = stitchButtonRingColor,
                videoEngagement = videoEngagement,
                videoDescription = videoDescription,
                currentUserTier = currentUserTier,
                currentUserID = currentUserID,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                followManager = followManager,
                bottomPadding = bottomPadding,
                context = context,
                hapticFeedback = hapticFeedback,
                scope = scope,
                onFollowToggle = {
                    // FollowManager handles state updates via StateFlow
                    // No need to manually update local state
                    followManager?.toggleFollow(video.creatorID)
                    Log.d("OVERLAY_FOLLOW", "Toggle follow for ${video.creatorID}, current: $isFollowing")
                },
                onViewersTap = { showViewersSheet = true },
                onAction = onAction,
                onThreadTap = {
                    // Let parent handle thread navigation
                    onAction?.invoke(OverlayAction.NavigateToThread)
                }
            )
        }

        // Floating Icons Overlay - visually on top but passes through touches
        iconManager?.let { manager ->
            FloatingIconRenderer(
                iconManager = manager,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // Viewers Sheet
    if (showViewersSheet) {
        ViewersBottomSheet(
            isVisible = showViewersSheet,
            videoID = video.id,
            viewCount = videoEngagement?.viewCount ?: 0,
            onDismiss = { showViewersSheet = false },
            onViewerClick = { userID ->
                showViewersSheet = false
                pauseAllVideos(context)
                scope.launch {
                    delay(100)
                    onAction?.invoke(OverlayAction.NavigateToProfile(userID))
                }
            }
        )
    }
}

// MARK: - Minimal Discovery Overlay

@Composable
private fun MinimalDiscoveryOverlay(
    video: CoreVideoMetadata,
    displayCreatorName: String,
    temperatureColor: Color,
    displayReplyCount: Int,
    context: Context,
    onAction: ((OverlayAction) -> Unit)?
) {
    // Fixed text sizes
    val nameFontSize = OverlaySizes.LABEL_MEDIUM.fixedSp()
    val titleFontSize = OverlaySizes.LABEL_LARGE.fixedSp()

    Box(modifier = Modifier.fillMaxSize()) {
        // Top: Creator name with temperature dot
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable {
                    pauseAllVideos(context)
                    onAction?.invoke(OverlayAction.NavigateToProfile(video.creatorID))
                }
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
                text = displayCreatorName,
                color = Color.White,
                fontSize = nameFontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        // Bottom: Video title
        if (video.title.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 16.dp, start = 12.dp, end = 60.dp)
            ) {
                Text(
                    text = video.title,
                    color = Color.White,
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Right Side: Swipe banner + Share button
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Swipe for replies banner (parent videos only)
            if (video.conversationDepth == 0 && displayReplyCount > 0) {
                SwipeForRepliesBanner(replyCount = displayReplyCount)
            }

            // Share button
            ShareButton(
                video = video,
                creatorUsername = displayCreatorName,
                size = ShareButtonSize.MEDIUM
            )

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}


// MARK: - Carousel Overlay (for CardVideoCarouselView)

@Composable
private fun CarouselOverlay(
    video: CoreVideoMetadata,
    canReply: Boolean,
    stitchButtonIcon: androidx.compose.ui.graphics.vector.ImageVector,
    stitchButtonLabel: String,
    stitchButtonRingColor: Color,
    videoEngagement: ContextualVideoEngagement?,
    currentUserTier: UserTier,
    currentUserID: String?,
    engagementViewModel: EngagementViewModel?,
    iconManager: FloatingIconManager?,
    context: Context,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onAction: ((OverlayAction) -> Unit)?
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Bottom engagement buttons - centered horizontal row
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cool Button
            if (engagementViewModel != null && iconManager != null) {
                ProgressiveCoolButton3D(
                    videoID = video.id,
                    creatorID = video.creatorID,
                    userTier = currentUserTier,
                    coolCount = videoEngagement?.coolCount ?: video.coolCount,
                    currentUserID = currentUserID ?: "",
                    viewModel = engagementViewModel,
                    iconManager = iconManager
                )
            } else {
                OverlayActionButton(
                    icon = Icons.Default.AcUnit,
                    label = "Cool",
                    ringColor = Color.Blue,
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAction?.invoke(OverlayAction.Engagement(EngagementType.COOL))
                    }
                )
            }

            // Hype Button
            if (engagementViewModel != null && iconManager != null) {
                ProgressiveHypeButton3D(
                    videoID = video.id,
                    creatorID = video.creatorID,
                    userTier = currentUserTier,
                    hypeCount = videoEngagement?.hypeCount ?: video.hypeCount,
                    currentUserID = currentUserID ?: "",
                    viewModel = engagementViewModel,
                    iconManager = iconManager
                )
            } else {
                OverlayActionButton(
                    icon = Icons.Default.LocalFireDepartment,
                    label = "Hype",
                    ringColor = Color(0xFFFF8C00),
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAction?.invoke(OverlayAction.Engagement(EngagementType.HYPE))
                    }
                )
            }

            // Stitch Button
            if (canReply) {
                OverlayActionButton(
                    icon = stitchButtonIcon,
                    label = stitchButtonLabel,
                    ringColor = stitchButtonRingColor,
                    onClick = {
                        pauseAllVideos(context)
                        onAction?.invoke(OverlayAction.StitchRecording)
                    }
                )
            }
        }
    }
}

// MARK: - Full Contextual Overlay

@Composable
private fun FullContextualOverlay(
    video: CoreVideoMetadata,
    overlayContext: OverlayContext,
    threadVideo: CoreVideoMetadata?,
    displayCreatorName: String,
    displayCreatorProfileImageURL: String?,
    displayThreadCreatorName: String,
    displayThreadCreatorProfileImageURL: String?,
    temperatureColor: Color,
    displayReplyCount: Int,
    isUserVideo: Boolean,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    canReply: Boolean,
    stitchButtonIcon: androidx.compose.ui.graphics.vector.ImageVector,
    stitchButtonLabel: String,
    stitchButtonRingColor: Color,
    videoEngagement: ContextualVideoEngagement?,
    videoDescription: String?,
    currentUserTier: UserTier,
    currentUserID: String?,
    engagementViewModel: EngagementViewModel?,
    iconManager: FloatingIconManager?,
    followManager: FollowManager?,
    bottomPadding: Dp,
    context: Context,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    scope: kotlinx.coroutines.CoroutineScope,
    onFollowToggle: () -> Unit,
    onViewersTap: () -> Unit,
    onAction: ((OverlayAction) -> Unit)?,
    onThreadTap: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top Section - hide for CAROUSEL to keep view clean
        if (overlayContext != OverlayContext.CAROUSEL) {
            TopSection(
                video = video,
                threadVideo = threadVideo,
                displayCreatorName = displayCreatorName,
                displayCreatorProfileImageURL = displayCreatorProfileImageURL,
                displayThreadCreatorName = displayThreadCreatorName,
                displayThreadCreatorProfileImageURL = displayThreadCreatorProfileImageURL,
                temperatureColor = temperatureColor,
                overlayContext = overlayContext,
                context = context,
                onAction = onAction
            )
        }

        // Bottom Section
        BottomSection(
            video = video,
            videoEngagement = videoEngagement,
            videoDescription = videoDescription,
            isUserVideo = isUserVideo,
            isFollowing = isFollowing,
            isFollowLoading = isFollowLoading,
            canReply = canReply,
            stitchButtonIcon = stitchButtonIcon,
            stitchButtonLabel = stitchButtonLabel,
            stitchButtonRingColor = stitchButtonRingColor,
            currentUserTier = currentUserTier,
            currentUserID = currentUserID,
            engagementViewModel = engagementViewModel,
            iconManager = iconManager,
            bottomPadding = bottomPadding,
            context = context,
            hapticFeedback = hapticFeedback,
            scope = scope,
            onFollowToggle = onFollowToggle,
            onViewersTap = onViewersTap,
            onAction = onAction,
            onThreadTap = onThreadTap,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

    }
}

// MARK: - Top Section

@Composable
private fun TopSection(
    video: CoreVideoMetadata,
    threadVideo: CoreVideoMetadata?,
    displayCreatorName: String,
    displayCreatorProfileImageURL: String?,
    displayThreadCreatorName: String,
    displayThreadCreatorProfileImageURL: String?,
    temperatureColor: Color,
    overlayContext: OverlayContext,
    context: Context,
    onAction: ((OverlayAction) -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 50.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // Left: Creator Pills
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Video creator pill
            CreatorPill(
                displayName = displayCreatorName,
                profileImageURL = displayCreatorProfileImageURL,
                temperatureColor = temperatureColor,
                isThread = false,
                onClick = {
                    pauseAllVideos(context)
                    onAction?.invoke(OverlayAction.NavigateToProfile(video.creatorID))
                }
            )

            // Thread creator pill (if different)
            if (threadVideo != null && threadVideo.creatorID != video.creatorID) {
                CreatorPill(
                    displayName = displayThreadCreatorName,
                    profileImageURL = displayThreadCreatorProfileImageURL,
                    temperatureColor = Color(0xFF9C27B0), // Purple for thread creator
                    isThread = true,
                    onClick = {
                        pauseAllVideos(context)
                        onAction?.invoke(OverlayAction.NavigateToProfile(threadVideo.creatorID))
                    }
                )
            }
        }

        // Right: Reserved for future options
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // More options button removed - can be added back when needed
        }
    }
}

// MARK: - Bottom Section

@Composable
private fun BottomSection(
    video: CoreVideoMetadata,
    videoEngagement: ContextualVideoEngagement?,
    videoDescription: String?,
    isUserVideo: Boolean,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    canReply: Boolean,
    stitchButtonIcon: androidx.compose.ui.graphics.vector.ImageVector,
    stitchButtonLabel: String,
    stitchButtonRingColor: Color,
    currentUserTier: UserTier,
    currentUserID: String?,
    engagementViewModel: EngagementViewModel?,
    iconManager: FloatingIconManager?,
    bottomPadding: Dp,
    context: Context,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    scope: kotlinx.coroutines.CoroutineScope,
    onFollowToggle: () -> Unit,
    onViewersTap: () -> Unit,
    onAction: ((OverlayAction) -> Unit)?,
    onThreadTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Fixed text sizes
    val titleFontSize = OverlaySizes.TITLE.fixedSp()
    val descFontSize = OverlaySizes.LABEL_REGULAR.fixedSp()
    val labelFontSize = OverlaySizes.LABEL_SMALL.fixedSp()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),  // Use responsive padding
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Video Title - with side padding to avoid right column
        if (video.title.isNotEmpty()) {
            Text(
                text = video.title,
                color = Color.White,
                fontSize = titleFontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 70.dp)
            )
        }

        // Video Description - with side padding
        if (!videoDescription.isNullOrEmpty()) {
            Text(
                text = videoDescription,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = descFontSize,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 70.dp)
            )
        }

        // Metadata Row + Follow Button - with side padding
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 70.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoMetadataRow(
                engagement = videoEngagement,
                isUserVideo = isUserVideo,
                onViewersTap = onViewersTap
            )

            if (!isUserVideo) {
                // Small inline follow button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isFollowing) Color.Gray.copy(alpha = 0.3f) else Color.Cyan)
                        .clickable(enabled = !isFollowLoading) { onFollowToggle() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isFollowLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            color = if (isFollowing) Color.White else Color.Black,
                            strokeWidth = 1.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isFollowing) Icons.Default.Check else Icons.Default.PersonAdd,
                            contentDescription = null,
                            tint = if (isFollowing) Color.White else Color.Black,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    Text(
                        text = if (isFollowing) "Following" else "Follow",
                        fontSize = labelFontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isFollowing) Color.White else Color.Black
                    )
                }
            }
        }

        // Engagement Buttons Row - 2 groups, centered
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left group: Thread + Cool
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thread Button
                OverlayActionButton(
                    icon = Icons.Default.List,
                    label = "Thread",
                    ringColor = Color.Cyan,
                    onClick = {
                        pauseAllVideos(context)
                        onThreadTap()
                        onAction?.invoke(OverlayAction.NavigateToThread)
                    }
                )

                // Cool Button - Progressive 3D
                if (engagementViewModel != null && iconManager != null) {
                    ProgressiveCoolButton3D(
                        videoID = video.id,
                        creatorID = video.creatorID,
                        userTier = currentUserTier,
                        coolCount = videoEngagement?.coolCount ?: video.coolCount,
                        currentUserID = currentUserID ?: "",
                        viewModel = engagementViewModel,
                        iconManager = iconManager
                    )
                } else {
                    OverlayActionButton(
                        icon = Icons.Default.AcUnit,
                        label = "Cool",
                        ringColor = Color.Blue,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAction?.invoke(OverlayAction.Engagement(EngagementType.COOL))
                        }
                    )
                }
            }

            // Gap between groups
            Spacer(modifier = Modifier.width(32.dp))

            // Right group: Hype + Stitch
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Hype Button - Progressive 3D
                if (engagementViewModel != null && iconManager != null) {
                    ProgressiveHypeButton3D(
                        videoID = video.id,
                        creatorID = video.creatorID,
                        userTier = currentUserTier,
                        hypeCount = videoEngagement?.hypeCount ?: video.hypeCount,
                        currentUserID = currentUserID ?: "",
                        viewModel = engagementViewModel,
                        iconManager = iconManager
                    )
                } else {
                    OverlayActionButton(
                        icon = Icons.Default.LocalFireDepartment,
                        label = "Hype",
                        ringColor = Color(0xFFFF8C00),
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAction?.invoke(OverlayAction.Engagement(EngagementType.HYPE))
                        }
                    )
                }

                // Stitch Button (conditional)
                if (canReply) {
                    OverlayActionButton(
                        icon = stitchButtonIcon,
                        label = stitchButtonLabel,
                        ringColor = stitchButtonRingColor,
                        onClick = {
                            pauseAllVideos(context)
                            onAction?.invoke(OverlayAction.StitchRecording)
                        }
                    )
                }
            }
        }
    }
}

// ============================================================================
// MARK: - INLINE COMPONENTS
// ============================================================================

// MARK: - Creator Pill

@Composable
private fun CreatorPill(
    displayName: String,
    profileImageURL: String?,
    temperatureColor: Color,
    isThread: Boolean,
    onClick: () -> Unit
) {
    val imageSize: Dp = if (isThread) OverlaySizes.PROFILE_IMAGE_THREAD else OverlaySizes.PROFILE_IMAGE
    val cornerRadius: Dp = if (isThread) 16.dp else 12.dp
    val fontSize = if (isThread) OverlaySizes.LABEL_LARGE.fixedSp() else OverlaySizes.LABEL_MEDIUM.fixedSp()
    val threadLabelSize = OverlaySizes.LABEL_TINY.fixedSp()

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(cornerRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = if (isThread) 12.dp else 8.dp, vertical = if (isThread) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Profile image with gradient border
        Box(
            modifier = Modifier.size(imageSize),
            contentAlignment = Alignment.Center
        ) {
            // Gradient border
            val gradientColors: List<Color> = if (isThread) {
                listOf(Color(0xFF9C27B0), Color(0xFFE91E63))
            } else {
                when {
                    temperatureColor == Color.Red -> listOf(Color.Red, Color(0xFFFF8C00))
                    temperatureColor == Color.Blue -> listOf(Color.Blue, Color.Cyan)
                    temperatureColor == Color(0xFFFF8C00) -> listOf(Color(0xFFFF8C00), Color.Yellow)
                    temperatureColor == Color.Cyan -> listOf(Color.Cyan, Color.Blue)
                    else -> listOf(Color.Gray, Color.White)
                }
            }

            Box(
                modifier = Modifier
                    .size(imageSize)
                    .clip(CircleShape)
                    .background(brush = Brush.linearGradient(gradientColors))
            )

            // Profile image
            Box(
                modifier = Modifier
                    .size(imageSize - 4.dp)
                    .clip(CircleShape)
                    .background(Color.Gray.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                if (!profileImageURL.isNullOrEmpty()) {
                    AsyncImage(
                        model = profileImageURL,
                        contentDescription = "Profile",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(imageSize * 0.5f)
                    )
                }
            }
        }

        // Name + thread indicator
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1
                )
                if (isThread) {
                    Text(
                        text = "thread creator",
                        fontSize = threadLabelSize,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// MARK: - Tagged Users Row

@Composable
private fun TaggedUsersRow(
    taggedUserIDs: List<String>,
    onUserTap: (String) -> Unit
) {
    if (taggedUserIDs.isEmpty()) return

    val maxVisible: Int = 3
    val avatarSize: Dp = OverlaySizes.PROFILE_IMAGE
    val overlap: Dp = 8.dp
    val labelFontSize = OverlaySizes.LABEL_TINY.fixedSp()

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(1.dp, Color(0xFF9C27B0).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable { /* Show full list */ }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.People,
            contentDescription = null,
            tint = Color(0xFF9C27B0),
            modifier = Modifier.size(OverlaySizes.ICON_SIZE_SMALL)
        )

        // Stacked avatars
        Box {
            taggedUserIDs.take(maxVisible).forEachIndexed { index: Int, _: String ->
                Box(
                    modifier = Modifier
                        .offset(x = (index * overlap.value).dp)
                        .zIndex((maxVisible - index).toFloat())
                        .size(avatarSize)
                        .clip(CircleShape)
                        .border(2.dp, Color(0xFF9C27B0).copy(alpha = 0.8f), CircleShape)
                        .background(Color(0xFF9C27B0).copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(avatarSize * 0.4f)
                    )
                }
            }
        }

        // Count badge
        if (taggedUserIDs.size > maxVisible) {
            Text(
                text = "+${taggedUserIDs.size - maxVisible}",
                fontSize = labelFontSize,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xFF9C27B0).copy(alpha = 0.9f), RoundedCornerShape(50))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}

// MARK: - Video Metadata Row

@Composable
private fun VideoMetadataRow(
    engagement: ContextualVideoEngagement?,
    isUserVideo: Boolean,
    onViewersTap: () -> Unit
) {
    val labelFontSize = OverlaySizes.LABEL_SMALL.fixedSp()

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (engagement != null) {
            // Views (tappable for creator)
            if (isUserVideo) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF9C27B0).copy(alpha = 0.2f))
                        .clickable(onClick = onViewersTap)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Visibility, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(OverlaySizes.ICON_SIZE_TINY))
                    Text("${formatCount(engagement.viewCount)} views", fontSize = labelFontSize, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.9f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Visibility, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(OverlaySizes.ICON_SIZE_TINY))
                    Text("${formatCount(engagement.viewCount)} views", fontSize = labelFontSize, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.9f))
                }
            }

            Text("Ã¢â‚¬Â¢", fontSize = labelFontSize, color = Color.White.copy(alpha = 0.5f))

            // Stitches
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ContentCut, null, tint = Color.Cyan.copy(alpha = 0.7f), modifier = Modifier.size(OverlaySizes.ICON_SIZE_TINY))
                Text("${formatCount(engagement.replyCount)} stitches", fontSize = labelFontSize, fontWeight = FontWeight.Medium, color = Color.Cyan.copy(alpha = 0.9f))
            }
        } else {
            Icon(Icons.Default.Visibility, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(OverlaySizes.ICON_SIZE_TINY))
            Text("Loading...", fontSize = labelFontSize, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

// MARK: - Swipe For Replies Banner

@Composable
private fun SwipeForRepliesBanner(replyCount: Int) {
    val infiniteTransition: InfiniteTransition = rememberInfiniteTransition(label = "swipe")
    val pulse: Float by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse"
    )
    val arrowOffset: Float by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "arrow"
    )
    val glowAlpha: Float by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "glow"
    )

    val replyText: String = if (replyCount == 1) "reply" else "replies"
    val countFontSize = OverlaySizes.LABEL_MEDIUM.fixedSp()
    val hintFontSize = OverlaySizes.LABEL_TINY.fixedSp()

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(pulse)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(brush = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color(0xFF9C27B0).copy(alpha = 0.3f))))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ContentCut, null, tint = Color.Cyan, modifier = Modifier.size(OverlaySizes.ICON_SIZE_SMALL))
            Text("$replyCount $replyText", fontSize = countFontSize, fontWeight = FontWeight.SemiBold, color = Color.White)
            Icon(Icons.Default.ArrowForward, null, tint = Color.Cyan.copy(alpha = glowAlpha + 0.3f), modifier = Modifier.size(OverlaySizes.ICON_SIZE_SMALL).offset(x = arrowOffset.dp))
        }
        Text("Swipe Ã¢â€ â€™", fontSize = hintFontSize, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
    }
}

// MARK: - Overlay Action Button

@Composable
private fun OverlayActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    ringColor: Color,
    onClick: () -> Unit
) {
    val labelFontSize = OverlaySizes.LABEL_SMALL.fixedSp()

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(OverlaySizes.BUTTON_SIZE)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.3f))
                .border(1.2.dp, ringColor.copy(alpha = 0.4f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(OverlaySizes.ICON_SIZE))
        }
        Text(label, fontSize = labelFontSize, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.8f))
    }
}

// MARK: - More Options Button

@Composable
private fun MoreOptionsButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MoreVert, "More options", tint = Color.White, modifier = Modifier.size(OverlaySizes.ICON_SIZE_SMALL))
    }
}

// MARK: - Viewers Bottom Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewersBottomSheet(
    isVisible: Boolean,
    videoID: String,
    viewCount: Int,
    onDismiss: () -> Unit,
    onViewerClick: (String) -> Unit
) {
    if (!isVisible) return

    val titleFontSize = 18.fixedSp()
    val subtitleFontSize = OverlaySizes.TITLE.fixedSp()
    val bodyFontSize = OverlaySizes.LABEL_REGULAR.fixedSp()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Who Viewed", fontSize = titleFontSize, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text("$viewCount views", fontSize = subtitleFontSize, color = Color.White.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(24.dp))
            Text("Viewer list coming soon...", fontSize = bodyFontSize, color = Color.Gray)
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// MARK: - Utility

private fun formatCount(count: Int): String {
    return when {
        count < 1000 -> count.toString()
        count < 1_000_000 -> String.format("%.1fK", count / 1000.0).replace(".0", "")
        count < 1_000_000_000 -> String.format("%.1fM", count / 1_000_000.0).replace(".0", "")
        else -> String.format("%.1fB", count / 1_000_000_000.0).replace(".0", "")
    }
}