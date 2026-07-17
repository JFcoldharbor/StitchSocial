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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.*
import com.stitchsocial.club.ui.components.Thread3DInfoPanel
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.offset
import com.stitchsocial.club.R
import com.stitchsocial.club.ui.theme.StitchColors
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
import kotlinx.coroutines.tasks.await
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

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
import com.stitchsocial.club.BuildConfig

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
    CAROUSEL,    // Minimal overlay for CardVideoCarouselView
    COLLECTION   // Show/episode segment playback — stitch button becomes Reply
}

enum class EngagementType {
    HYPE,
    COOL,
    REPLY,
    SHARE,
    STITCH,
    THREAD,
    TIP
}

// Swappable slot: swipe UP → Tip, swipe DOWN → Hype (mirrors iOS SwappableEngagementButton)
enum class SwappableSlotMode { HYPE, TIP }

// MARK: - Overlay Actions

sealed class OverlayAction {
    data class NavigateToProfile(val userID: String) : OverlayAction()
    // NavigateToThread carries an optional targetVideoID — when the user
    // taps a specific reply in the Thread3DInfoPanel preview, this is the
    // reply we want full ThreadView to focus on. Null = jump to thread root.
    data class NavigateToThread(val targetVideoID: String? = null) : OverlayAction()
    object Follow : OverlayAction()
    object Unfollow : OverlayAction()
    data class Engagement(val type: EngagementType) : OverlayAction()
    object Share : OverlayAction()
    object StitchRecording : OverlayAction()
    /**
     * User-initiated report. Target is either a video or a user — see
     * ReportSheet's REPORT_TARGET_TYPES set. Wired to the submitReport
     * Cloud Function via ReportService.submitReport().
     */
    data class Report(val targetID: String, val targetType: String = "video") : OverlayAction()
}

// MARK: - Static User Cache

private data class OverlayUserCache(
    val displayName: String,
    val profileImageURL: String?,
    val tier: UserTier?,
    val cachedAt: Date
)

private object UserDataCache {
    private val cache = ConcurrentHashMap<String, OverlayUserCache>()
    private val timestamps = ConcurrentHashMap<String, Date>()
    private const val CACHE_EXPIRATION_MS = 300_000L // 5 minutes

    fun get(userID: String): OverlayUserCache? {
        val timestamp = timestamps[userID] ?: return null
        val now = Date()
        if (now.time - timestamp.time > CACHE_EXPIRATION_MS) {
            cache.remove(userID)
            timestamps.remove(userID)
            return null
        }
        return cache[userID]
    }

    fun set(userID: String, data: OverlayUserCache) {
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

internal data class ContextualVideoEngagement(
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
    // Override the computed bottom padding — e.g. Discovery fullscreen hides the
    // tab bar, so it wants the content lower than the HomeFeed tab-bar clearance.
    bottomPaddingOverride: Dp? = null,
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

    // Calculate responsive bottom padding based on screen height (unless the
    // caller overrides it — e.g. fullscreen with no tab bar wants a smaller gap).
    val bottomPadding = bottomPaddingOverride ?: remember(screenHeight) {
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

    // Thread preview panel state (Option 1 in-place transformation).
    // Tapping the Thread button opens Thread3DInfoPanel as a holographic
    // overlay over the bottom of the feed canvas — parent video keeps
    // playing, no PAUSE_ALL_VIDEOS broadcast. Tapping a reply thumbnail
    // in the panel is what triggers the push to full ThreadView focused
    // on that reply. Mirrors iOS ContextualVideoOverlay.swift 1.7(50)+.
    var showThreadPanel by remember { mutableStateOf(false) }
    var threadPanelExpanded by remember { mutableStateOf(true) }
    var threadPanelChildren by remember { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }

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

    // =========================================================================
    // VIEW TRACKING — EXACT MATCH iOS ContextualVideoOverlay.trackVideoView()
    //
    // iOS: waits 5 seconds then calls VideoService.incrementViewCount()
    // Android: DisposableEffect cancels job if user scrolls away before 5s — no false counts.
    //
    // CACHING: recordVideoView() deduplicates via interactions collection.
    // =========================================================================
    DisposableEffect(video.id, currentUserID) {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        val job = scope.launch {
            if (currentUserID != null) {
                try {
                    // Wait 5 seconds — matches iOS Task.sleep(nanoseconds: 5_000_000_000)
                    kotlinx.coroutines.delay(5_000)

                    val userData = userService.getUserProfile(currentUserID)
                    val viewerData = mapOf(
                        "displayName"     to (userData?.displayName ?: "User"),
                        "username"        to (userData?.username ?: ""),
                        "profileImageURL" to (userData?.profileImageURL ?: ""),
                        "tier"            to (userData?.tier?.name ?: "ROOKIE")
                    )

                    videoService.recordVideoView(video.id, currentUserID, viewerData, watchTime = 5.0)
                    Log.d("VIEW_TRACKING", "View recorded after 5s: ${video.id} by $currentUserID")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d("VIEW_TRACKING", "View cancelled (scrolled away): ${video.id}")
                } catch (e: Exception) {
                    Log.e("VIEW_TRACKING", "View record failed: ${e.message}")
                }
            }
        }
        onDispose { job.cancel() }
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
                OverlayContext.PROFILE_OWN, OverlayContext.HOME_FEED, OverlayContext.THREAD_VIEW, OverlayContext.CAROUSEL, OverlayContext.COLLECTION -> true
                else -> false
            }
        } else true
    }

    // Collection segments only allow replies — no stitching or spinoffs (iOS parity)
    val isStitchBlocked: Boolean = overlayContext == OverlayContext.COLLECTION

    // Stitch button properties — flips to Reply when stitch is blocked
    val stitchButtonIcon: androidx.compose.ui.graphics.vector.ImageVector = when {
        isStitchBlocked -> Icons.Default.Reply
        isUserVideo -> Icons.Default.AddCircle
        else -> Icons.Default.ContentCut
    }
    val stitchButtonLabel: String = when {
        isStitchBlocked -> "Reply"
        isUserVideo -> "Continue"
        else -> "Stitch"
    }
    val stitchButtonRingColor: Color = when {
        isStitchBlocked -> Color.Cyan
        isUserVideo -> Color.Green
        else -> Color(0xFF9C27B0)
    }
    // Plain stitch shows the brand logo glyph, matching iOS stitchButtonGlyph
    val stitchUseLogo: Boolean = !isStitchBlocked && !isUserVideo

    // Swappable hype/tip slot state (mirrors iOS SwappableEngagementButton)
    var slotMode by remember { mutableStateOf(SwappableSlotMode.HYPE) }
    val isSelfTip = isUserVideo

    // Load user data - fetch from service if not cached
    LaunchedEffect(video.creatorID) {
        if (BuildConfig.DEBUG) { println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: Fetching profile for creatorID: ${video.creatorID}") }
        if (BuildConfig.DEBUG) { println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: video.creatorName: ${video.creatorName}") }

        val cached = UserDataCache.get(video.creatorID)
        if (cached != null) {
            if (BuildConfig.DEBUG) { println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: Found in cache: ${cached.displayName}") }
            realCreatorName = cached.displayName
            realCreatorProfileImageURL = cached.profileImageURL
        } else {
            // Fetch from UserService
            try {
                isLoadingUserData = true
                if (BuildConfig.DEBUG) { println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: Calling userService.getUserProfile...") }
                val profile = userService.getUserProfile(video.creatorID)
                if (BuildConfig.DEBUG) { println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: Profile result: $profile") }
                if (profile != null) {
                    realCreatorName = profile.displayName.ifEmpty { profile.username }
                    realCreatorProfileImageURL = profile.profileImageURL
                    if (BuildConfig.DEBUG) { println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: displayName=${realCreatorName}, imageURL=${realCreatorProfileImageURL}") }
                    // Cache the result
                    UserDataCache.set(video.creatorID, OverlayUserCache(
                        displayName = realCreatorName ?: "",
                        profileImageURL = realCreatorProfileImageURL,
                        tier = null,
                        cachedAt = Date()
                    ))
                    if (BuildConfig.DEBUG) { println("Ã°Å¸â€˜Â¤ OVERLAY: Fetched creator profile: ${profile.displayName}") }
                } else {
                    if (BuildConfig.DEBUG) { println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: Profile was NULL!") }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("Ã¢ÂÅ’ OVERLAY: Failed to fetch creator profile: ${e.message}") }
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
                        UserDataCache.set(creatorID, OverlayUserCache(
                            displayName = realThreadCreatorName ?: "",
                            profileImageURL = realThreadCreatorProfileImageURL,
                            tier = null,
                            cachedAt = Date()
                        ))
                        if (BuildConfig.DEBUG) { println("Ã°Å¸â€˜Â¤ OVERLAY: Fetched thread creator profile: ${profile.displayName}") }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) { println("Ã¢ÂÅ’ OVERLAY: Failed to fetch thread creator profile: ${e.message}") }
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

    // Initialize engagement data.
    //
    // Step 1: paint immediately from the parameter so the overlay isn't
    //         empty while the network call is in flight.
    // Step 2: fetch fresh from Firestore via getVideoById and overwrite,
    //         so the displayed counts match the database (not whatever
    //         stale snapshot the parent view passed in via getUserVideos
    //         or a cached feed).
    //
    // Without step 2 the overlay always showed the parent's cached counts,
    // which on the profile path could be hours/days old.
    LaunchedEffect(video.id) {
        // Step 1 — instant local data
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
        // Step 2 — refresh from Firestore
        try {
            val fresh = videoService.getVideoById(video.id)
            if (fresh != null) {
                videoEngagement = ContextualVideoEngagement(
                    videoID = fresh.id,
                    creatorID = fresh.creatorID,
                    hypeCount = fresh.hypeCount,
                    coolCount = fresh.coolCount,
                    shareCount = fresh.shareCount,
                    replyCount = fresh.replyCount,
                    viewCount = fresh.viewCount,
                    lastEngagementAt = Date()
                )
            }
        } catch (e: Exception) {
            Log.w("OVERLAY", "Failed to refresh engagement from Firestore: ${e.message}")
        }
    }

    // After each completed engagement, re-fetch fresh counts so the displayed
    // numbers reflect what just landed in Firestore. Without this the overlay
    // showed the same number after taps as before — looking like the tap
    // didn't persist (it did, the UI just wasn't reading the new value).
    val lastEngagement by (engagementViewModel?.lastEngagementFeedback?.collectAsState() ?: remember { mutableStateOf(null) })
    LaunchedEffect(lastEngagement) {
        if (lastEngagement != null) {
            try {
                val fresh = videoService.getVideoById(video.id)
                if (fresh != null) {
                    videoEngagement = ContextualVideoEngagement(
                        videoID = fresh.id,
                        creatorID = fresh.creatorID,
                        hypeCount = fresh.hypeCount,
                        coolCount = fresh.coolCount,
                        shareCount = fresh.shareCount,
                        replyCount = fresh.replyCount,
                        viewCount = fresh.viewCount,
                        lastEngagementAt = Date()
                    )
                }
            } catch (_: Exception) { /* swallow — visual feedback already happened */ }
        }
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
                onAction = onAction,
                currentUserID = currentUserID
            )
        } else if (overlayContext == OverlayContext.CAROUSEL) {
            CarouselOverlay(
                video = video,
                canReply = canReply,
                stitchButtonIcon = stitchButtonIcon,
                stitchButtonLabel = stitchButtonLabel,
                stitchButtonRingColor = stitchButtonRingColor,
                stitchUseLogo = stitchUseLogo,
                videoEngagement = videoEngagement,
                currentUserTier = currentUserTier,
                currentUserID = currentUserID,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                slotMode = slotMode,
                isSelfTip = isSelfTip,
                onSlotModeChange = { slotMode = it },
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
                isStitchBlocked = isStitchBlocked,
                stitchButtonIcon = stitchButtonIcon,
                stitchButtonLabel = stitchButtonLabel,
                stitchButtonRingColor = stitchButtonRingColor,
                stitchUseLogo = stitchUseLogo,
                videoEngagement = videoEngagement,
                videoDescription = videoDescription,
                currentUserTier = currentUserTier,
                currentUserID = currentUserID,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                followManager = followManager,
                slotMode = slotMode,
                isSelfTip = isSelfTip,
                onSlotModeChange = { slotMode = it },
                bottomPadding = bottomPadding,
                context = context,
                hapticFeedback = hapticFeedback,
                scope = scope,
                onFollowToggle = {
                    followManager?.toggleFollow(video.creatorID)
                    Log.d("OVERLAY_FOLLOW", "Toggle follow for ${video.creatorID}, current: $isFollowing")
                },
                onViewersTap = { showViewersSheet = true },
                onAction = onAction,
                onThreadTap = {
                    // Option 1: opens the preview panel locally instead of
                    // pushing full ThreadView. Parent video keeps playing.
                    showThreadPanel = true
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

        // Thread preview panel (Option 1). Holographic overlay anchored
        // bottom; parent video keeps playing behind it. Tap a reply
        // thumbnail = pause + push full ThreadView; tap close = dismiss.
        AnimatedVisibility(
            visible = showThreadPanel,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Thread3DInfoPanel(
                parentVideo = video,
                childVideos = threadPanelChildren,
                selectedVideo = null,
                isExpanded = threadPanelExpanded,
                onExpandChange = { threadPanelExpanded = it },
                onVideoTap = { tappedChild ->
                    showThreadPanel = false
                    pauseAllVideos(context)
                    scope.launch {
                        delay(250)
                        onAction?.invoke(OverlayAction.NavigateToThread(targetVideoID = tappedChild.id))
                    }
                },
                onClose = { showThreadPanel = false }
            )
        }
    }

    // Lazy-load thread children when the panel opens for the first time.
    // 1 Firestore round-trip per overlay instance; cached after.
    LaunchedEffect(showThreadPanel) {
        if (showThreadPanel && threadPanelChildren.isEmpty()) {
            try {
                val threadID = video.threadID ?: video.id
                val (_, children) = videoService.getThreadData(threadID)
                threadPanelChildren = children
                Log.d("THREAD_PANEL", "Loaded ${children.size} children for $threadID")
            } catch (e: Exception) {
                Log.e("THREAD_PANEL", "Failed to load children: ${e.message}")
            }
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

// MARK: - More Options Menu (Report / Block)
//
// Required by App Store Guideline 1.2 / Play Store UGC policy: users must
// be able to flag objectionable content and block abusive users from any
// UGC surface. Self-contained — owns its own DropdownMenu, AlertDialog, and
// ReportSheet so callers only need to drop it into their overlay's right rail.
//
@Composable
private fun MoreOptionsMenu(
    video: CoreVideoMetadata,
    displayCreatorName: String,
    currentUserID: String?
) {
    val isUserVideo = currentUserID != null && currentUserID == video.creatorID

    var menuExpanded by remember { mutableStateOf(false) }
    var showReportSheet by remember { mutableStateOf(false) }
    var reportTargetType by remember { mutableStateOf("video") }
    var showBlockConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val blockedIds by com.stitchsocial.club.services.BlockService.shared
        .blockedUserIds.collectAsStateWithLifecycle()
    val isBlocked = blockedIds.contains(video.creatorID)
    val savedIds by com.stitchsocial.club.services.SaveService.shared
        .savedVideoIds.collectAsStateWithLifecycle()
    val isSaved = savedIds.contains(video.id)

    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(6.dp)
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            // Save for later — private bookmark, available on every video
            // (own videos included; report/block stay other-users-only below).
            DropdownMenuItem(
                text = { Text(if (isSaved) "Remove from Saved" else "Save video") },
                leadingIcon = {
                    Icon(
                        if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = null
                    )
                },
                onClick = {
                    menuExpanded = false
                    scope.launch {
                        com.stitchsocial.club.services.SaveService.shared.toggleSave(video)
                    }
                }
            )
            if (!isUserVideo) {
            DropdownMenuItem(
                text = { Text("Report video") },
                leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    reportTargetType = "video"
                    showReportSheet = true
                }
            )
            DropdownMenuItem(
                text = { Text("Report user") },
                leadingIcon = { Icon(Icons.Default.PersonOff, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    reportTargetType = "user"
                    showReportSheet = true
                }
            )
            if (isBlocked) {
                DropdownMenuItem(
                    text = { Text("Unblock @$displayCreatorName") },
                    leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        scope.launch {
                            com.stitchsocial.club.services.BlockService.shared
                                .unblockUser(video.creatorID)
                        }
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Block @$displayCreatorName") },
                    leadingIcon = { Icon(Icons.Default.Block, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        showBlockConfirm = true
                    }
                )
            }
            }  // if (!isUserVideo)
        }
    }

    if (showReportSheet) {
        ReportSheet(
            targetType = reportTargetType,
            targetID = if (reportTargetType == "user") video.creatorID else video.id,
            onDismiss = { showReportSheet = false }
        )
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text("Block @$displayCreatorName?") },
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
                                .blockUser(video.creatorID)
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

// MARK: - Minimal Discovery Overlay

@Composable
private fun MinimalDiscoveryOverlay(
    video: CoreVideoMetadata,
    displayCreatorName: String,
    temperatureColor: Color,
    displayReplyCount: Int,
    context: Context,
    onAction: ((OverlayAction) -> Unit)?,
    currentUserID: String? = null
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

            // More options (Report / Block)
            MoreOptionsMenu(
                video = video,
                displayCreatorName = displayCreatorName,
                currentUserID = currentUserID
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
    stitchUseLogo: Boolean,
    videoEngagement: ContextualVideoEngagement?,
    currentUserTier: UserTier,
    currentUserID: String?,
    engagementViewModel: EngagementViewModel?,
    iconManager: FloatingIconManager?,
    slotMode: SwappableSlotMode,
    isSelfTip: Boolean,
    onSlotModeChange: (SwappableSlotMode) -> Unit,
    context: Context,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onAction: ((OverlayAction) -> Unit)?
) {
    Box(modifier = Modifier.fillMaxSize()) {
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

            // Swappable Hype / Tip slot (swipe UP = tip, DOWN = hype)
            SwappableEngagementSlot(
                video = video,
                videoEngagement = videoEngagement,
                currentUserID = currentUserID ?: "",
                currentUserTier = currentUserTier,
                engagementViewModel = engagementViewModel,
                iconManager = iconManager,
                slotMode = slotMode,
                isSelfTip = isSelfTip,
                onSlotModeChange = onSlotModeChange,
                hapticFeedback = hapticFeedback,
                onAction = onAction
            )

            // Stitch Button — 3D base + brand rim (matches iOS)
            if (canReply) {
                Overlay3DActionButton(
                    label = stitchButtonLabel,
                    rimColors = listOf(
                        StitchColors.gradientStart.copy(alpha = 0.7f),
                        StitchColors.gradientEnd.copy(alpha = 0.5f)
                    ),
                    glowColor = StitchColors.primary,
                    onClick = {
                        pauseAllVideos(context)
                        onAction?.invoke(OverlayAction.StitchRecording)
                    }
                ) {
                    StitchButtonGlyph(useLogo = stitchUseLogo, icon = stitchButtonIcon)
                }
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
    isStitchBlocked: Boolean,
    stitchButtonIcon: androidx.compose.ui.graphics.vector.ImageVector,
    stitchButtonLabel: String,
    stitchButtonRingColor: Color,
    stitchUseLogo: Boolean,
    videoEngagement: ContextualVideoEngagement?,
    videoDescription: String?,
    currentUserTier: UserTier,
    currentUserID: String?,
    engagementViewModel: EngagementViewModel?,
    iconManager: FloatingIconManager?,
    followManager: FollowManager?,
    slotMode: SwappableSlotMode,
    isSelfTip: Boolean,
    onSlotModeChange: (SwappableSlotMode) -> Unit,
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
                onAction = onAction,
                currentUserID = currentUserID
            )
        }

        // Readability scrim — a soft gradient behind the bottom metadata + action
        // buttons so white text/icons stay legible over bright video.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                    )
                )
        )

        // Bottom Section
        BottomSection(
            video = video,
            videoEngagement = videoEngagement,
            videoDescription = videoDescription,
            isUserVideo = isUserVideo,
            isFollowing = isFollowing,
            isFollowLoading = isFollowLoading,
            canReply = canReply,
            isStitchBlocked = isStitchBlocked,
            stitchButtonIcon = stitchButtonIcon,
            stitchButtonLabel = stitchButtonLabel,
            stitchButtonRingColor = stitchButtonRingColor,
            stitchUseLogo = stitchUseLogo,
            currentUserTier = currentUserTier,
            currentUserID = currentUserID,
            engagementViewModel = engagementViewModel,
            iconManager = iconManager,
            slotMode = slotMode,
            isSelfTip = isSelfTip,
            onSlotModeChange = onSlotModeChange,
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
    onAction: ((OverlayAction) -> Unit)?,
    currentUserID: String? = null
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

        // Right: Report / Block menu (App Store Guideline 1.2 / Play Store UGC)
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MoreOptionsMenu(
                video = video,
                displayCreatorName = displayCreatorName,
                currentUserID = currentUserID
            )
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
    isStitchBlocked: Boolean,
    stitchButtonIcon: androidx.compose.ui.graphics.vector.ImageVector,
    stitchButtonLabel: String,
    stitchButtonRingColor: Color,
    stitchUseLogo: Boolean,
    currentUserTier: UserTier,
    currentUserID: String?,
    engagementViewModel: EngagementViewModel?,
    iconManager: FloatingIconManager?,
    slotMode: SwappableSlotMode,
    isSelfTip: Boolean,
    onSlotModeChange: (SwappableSlotMode) -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(8.dp)  // tightened (was 12)
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
                // Thread Button — 3D base + conversation glyph (beta feedback:
                // the flat circle read washed-out next to its 3D row-mates)
                Overlay3DActionButton(
                    label = "Thread",
                    rimColors = listOf(Color.Cyan.copy(alpha = 0.8f), Color(0xFF2196F3).copy(alpha = 0.5f)),
                    glowColor = Color.Cyan,
                    onClick = {
                        // Option 1 entry: parent video keeps playing — no
                        // pauseAllVideos broadcast, no navigation. onThreadTap
                        // is now the panel-open callback (see line ~661).
                        onThreadTap()
                    }
                ) {
                    Icon(Icons.Default.Forum, "Thread", tint = Color.White, modifier = Modifier.size(OverlaySizes.ICON_SIZE))
                }

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

            // Right group: Swappable (Hype/Tip) + Stitch
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Swappable Hype / Tip slot — swipe UP = tip, DOWN = hype
                SwappableEngagementSlot(
                    video = video,
                    videoEngagement = videoEngagement,
                    currentUserID = currentUserID ?: "",
                    currentUserTier = currentUserTier,
                    engagementViewModel = engagementViewModel,
                    iconManager = iconManager,
                    slotMode = slotMode,
                    isSelfTip = isSelfTip,
                    onSlotModeChange = onSlotModeChange,
                    hapticFeedback = hapticFeedback,
                    onAction = onAction
                )

                // Stitch Button (conditional). For COLLECTION context the button
                // *looks* like Reply (cyan, reply-arrow icon) but still emits
                // StitchRecording — the caller turns that into the recording flow
                // with the segment as parent.
                if (canReply) {
                    Overlay3DActionButton(
                        label = stitchButtonLabel,
                        rimColors = listOf(
                            StitchColors.gradientStart.copy(alpha = 0.7f),
                            StitchColors.gradientEnd.copy(alpha = 0.5f)
                        ),
                        glowColor = StitchColors.primary,
                        onClick = {
                            pauseAllVideos(context)
                            onAction?.invoke(OverlayAction.StitchRecording)
                        }
                    ) {
                        StitchButtonGlyph(useLogo = stitchUseLogo, icon = stitchButtonIcon)
                    }
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

// MARK: - 3D Overlay Button (iOS overlayButton3DBase parity)
//
// Stacked offset shadow circles for depth + top-lit sphere + gradient rim +
// colored glow, so Thread/Stitch feel tactile like the hype/cool 3D buttons.

@Composable
private fun Overlay3DActionButton(
    label: String,
    rimColors: List<Color>,
    glowColor: Color,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit
) {
    val labelFontSize = OverlaySizes.LABEL_SMALL.fixedSp()
    val diameter = OverlaySizes.BUTTON_SIZE
    val density = LocalDensity.current
    val sphereRadiusPx = with(density) { (diameter * 0.7f).toPx() }
    val depthRadiusPx = with(density) { (diameter * 0.6f).toPx() }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(diameter + 6.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.TopStart
        ) {
            // Stacked offset shadow circles → depth
            for (layer in 0 until 4) {
                Box(
                    modifier = Modifier
                        .size(diameter)
                        .offset(x = (layer * 1.5f).dp, y = (layer * 1.5f).dp)
                        .alpha(0.4f - layer * 0.1f)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.3f)),
                                radius = depthRadiusPx
                            ),
                            CircleShape
                        )
                )
            }
            // Top-lit sphere + rim + glow
            Box(
                modifier = Modifier
                    .size(diameter)
                    .shadow(4.dp, CircleShape, ambientColor = glowColor, spotColor = glowColor)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.7f),
                                Color.Black.copy(alpha = 0.9f)
                            ),
                            center = Offset(0f, 0f),
                            radius = sphereRadiusPx
                        ),
                        CircleShape
                    )
                    .border(1.5.dp, Brush.linearGradient(rimColors), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                glyph()
            }
        }
        Text(label, fontSize = labelFontSize, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.8f))
    }
}

/** Brand logo glyph for plain stitch, vector icon for reply/continue states. */
@Composable
private fun StitchButtonGlyph(useLogo: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    if (useLogo) {
        Icon(
            painter = painterResource(R.drawable.stitchsociallogo),
            contentDescription = "Stitch",
            tint = Color.White,
            modifier = Modifier.size(OverlaySizes.ICON_SIZE + 3.dp)
        )
    } else {
        Icon(icon, "Stitch", tint = Color.White, modifier = Modifier.size(OverlaySizes.ICON_SIZE))
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

// ============================================================================
// MARK: - SWAPPABLE ENGAGEMENT SLOT
// Swipe UP → TipButton, swipe DOWN → HypeButton
// Mirrors iOS SwappableEngagementButton.swift exactly.
// CACHING: Balance read from HypeCoinCoordinator cached flow — 0 extra reads.
// ============================================================================

@androidx.compose.runtime.Composable
internal fun SwappableEngagementSlot(
    video: CoreVideoMetadata,
    videoEngagement: ContextualVideoEngagement?,
    currentUserID: String,
    currentUserTier: UserTier,
    engagementViewModel: EngagementViewModel?,
    iconManager: FloatingIconManager?,
    slotMode: SwappableSlotMode,
    isSelfTip: Boolean,
    onSlotModeChange: (SwappableSlotMode) -> Unit,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onAction: ((OverlayAction) -> Unit)?
) {
    var dragAccum by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    val switchThreshold = 60f

    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier
            .pointerInput(slotMode) {
                detectVerticalDragGestures(
                    onDragEnd = { dragAccum = 0f },
                    onDragCancel = { dragAccum = 0f }
                ) { _, dragAmount ->
                    dragAccum += dragAmount
                    if (dragAccum < -switchThreshold && slotMode == SwappableSlotMode.HYPE) {
                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onSlotModeChange(SwappableSlotMode.TIP)
                        dragAccum = 0f
                    } else if (dragAccum > switchThreshold && slotMode == SwappableSlotMode.TIP) {
                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onSlotModeChange(SwappableSlotMode.HYPE)
                        dragAccum = 0f
                    }
                }
            },
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        AnimatedContent(
            targetState = slotMode,
            transitionSpec = {
                if (targetState == SwappableSlotMode.TIP) {
                    (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
                } else {
                    (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                }
            },
            label = "swappable_slot"
        ) { mode ->
            when (mode) {
                SwappableSlotMode.HYPE -> {
                    if (engagementViewModel != null && iconManager != null) {
                        ProgressiveHypeButton3D(
                            videoID = video.id,
                            creatorID = video.creatorID,
                            userTier = currentUserTier,
                            hypeCount = videoEngagement?.hypeCount ?: video.hypeCount,
                            currentUserID = currentUserID,
                            viewModel = engagementViewModel,
                            iconManager = iconManager
                        )
                    } else {
                        OverlayActionButton(
                            icon = Icons.Default.LocalFireDepartment,
                            label = "Hype",
                            ringColor = Color(0xFFFF8C00),
                            onClick = {
                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                onAction?.invoke(OverlayAction.Engagement(EngagementType.HYPE))
                            }
                        )
                    }
                }
                SwappableSlotMode.TIP -> {
                    TipButton(
                        videoID = video.id,
                        creatorID = video.creatorID,
                        currentUserID = currentUserID,
                        coinTotal = 0,
                        isSelfTip = isSelfTip,
                        onAction = onAction
                    )
                }
            }
        }

        // Swipe hint arrow
        androidx.compose.material3.Icon(
            imageVector = if (slotMode == SwappableSlotMode.HYPE)
                Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = androidx.compose.ui.Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .size(10.dp)
        )
    }
}

// ============================================================================
// MARK: - TIP BUTTON
// Mirrors TipButton.swift: tap=1 coin, long press=5 coins
// isSelfTip disabled with PersonOff icon
// Optimistic sessionTotal over persisted coinTotal (matches iOS field name)
// Writes: debit tipper coin_balances, credit creator pendingCoins,
//         increment videos.coinTotal, write tip notification, update
//         creator's supporters subcollection + topSupporters array.
// ============================================================================

@androidx.compose.runtime.Composable
internal fun TipButton(
    videoID: String,
    creatorID: String,
    currentUserID: String,
    coinTotal: Int,
    isSelfTip: Boolean,
    onAction: ((OverlayAction) -> Unit)?
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val db = androidx.compose.runtime.remember { FirebaseFirestore.getInstance("stitchfin") }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    var sessionTotal by androidx.compose.runtime.remember(videoID) { androidx.compose.runtime.mutableStateOf(0) }
    val displayCount = coinTotal + sessionTotal

    var isPressed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showBurst by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var errorMsg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var showError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val scale by animateFloatAsState(if (isPressed) 0.9f else 1f, label = "tip_scale")

    fun sendTip(amount: Int) {
        sessionTotal += amount
        scope.launch {
            try {
                db.collection("coin_balances").document(currentUserID)
                    .update("availableCoins", FieldValue.increment(-amount.toLong())).await()
                db.collection("coin_balances").document(creatorID)
                    .update("pendingCoins", FieldValue.increment(amount.toLong())).await()
                db.collection("videos").document(videoID)
                    .update(
                        "coinTotal", FieldValue.increment(amount.toLong()),
                        "lastTippedAt", com.google.firebase.Timestamp(java.util.Date())
                    ).await()
                if (BuildConfig.DEBUG) { println("💰 TIP: $currentUserID → $creatorID +$amount on $videoID") }

                // Side-effects — notification + creator's supporters list +
                // topSupporters array. Run sequentially after the core
                // transfer so a side-effect failure doesn't roll back the tip.
                recordTipSideEffects(
                    db = db,
                    tipperID = currentUserID,
                    creatorID = creatorID,
                    videoID = videoID,
                    amount = amount
                )
            } catch (e: Exception) {
                sessionTotal -= amount
                errorMsg = "Tip failed"
                showError = true
                scope.launch { delay(2000); showError = false }
                if (BuildConfig.DEBUG) { println("❌ TIP: ${e.message}") }
            }
        }
    }

    androidx.compose.foundation.layout.Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Count display (mirrors tipCountDisplay)
        androidx.compose.material3.Text(
            "$displayCount",
            fontSize = 12.fixedSp(),
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        // Button circle
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .size(42.dp)
                .scale(scale)
                .background(
                    if (isSelfTip) Color.Gray.copy(0.4f) else Color.Black.copy(0.4f),
                    CircleShape
                )
                .border(
                    width = if (showBurst) 3.dp else 1.5.dp,
                    brush = when {
                        isSelfTip -> Brush.linearGradient(listOf(Color.Gray, Color.Gray.copy(0.5f)))
                        showBurst -> Brush.linearGradient(listOf(Color(0xFFFFD700), Color(0xFFFF8C00), Color(0xFFFF4500)))
                        else -> Brush.linearGradient(listOf(Color(0xFFFFD700).copy(0.6f), Color(0xFFFF8C00).copy(0.4f)))
                    },
                    shape = CircleShape
                )
                .clickable(enabled = !isSelfTip) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isPressed = true
                    sendTip(1)
                    scope.launch { delay(150); isPressed = false }
                },
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            if (isSelfTip) {
                androidx.compose.material3.Icon(
                    Icons.Default.PersonOff,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = androidx.compose.ui.Modifier.size(18.dp)
                )
            } else {
                androidx.compose.material3.Text("🪙", fontSize = 20.sp)
            }

            if (showBurst) {
                androidx.compose.material3.Text(
                    "x5",
                    fontSize = 8.fixedSp(),
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFFFD700),
                    modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                )
            }
        }

        // Label
        androidx.compose.material3.Text(
            if (isSelfTip) "—" else "Tip",
            fontSize = 10.fixedSp(),
            fontWeight = FontWeight.Medium,
            color = if (isSelfTip) Color.Gray else Color.White.copy(0.8f)
        )

        // Error toast
        if (showError) {
            androidx.compose.material3.Text(
                errorMsg,
                fontSize = 10.fixedSp(),
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = androidx.compose.ui.Modifier
                    .background(Color.Black.copy(0.75f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

// ============================================================================
// MARK: - Tip side-effects (mirrors iOS TipService.recordTipAggregates +
// sendTipNotification). Run after the tip transfer succeeds.
//
// Schema:
//   notifications/{id}                                           — drives FCM
//   users/{creatorID}/supporters/{tipperID}                      — per-tipper aggregate
//   users/{creatorID}.topSupporters: [{tipperID, username, totalSent}]
//                                                                 — denormalized top 10
// ============================================================================

private suspend fun recordTipSideEffects(
    db: FirebaseFirestore,
    tipperID: String,
    creatorID: String,
    videoID: String,
    amount: Int
) {
    if (tipperID.isEmpty() || creatorID.isEmpty()) return

    // Resolve tipper's display name with the same fallback chain iOS uses:
    // displayName → username → "Someone". Notification copy reads better
    // than a raw uid prefix.
    val tipperUsername = resolveDisplayName(db, tipperID)

    // 1) In-app notification — onNotificationCreated CF will fan out FCM.
    //    Cooldown handled by checking tipper→creator pair within 60s.
    try {
        val cooldownKey = "tip_${tipperID}_${creatorID}"
        val cooldownDoc = db.collection("notification_cooldowns").document(cooldownKey).get().await()
        val lastTs = cooldownDoc.getTimestamp("lastNotificationAt")?.toDate()?.time ?: 0L
        val now = System.currentTimeMillis()
        val withinCooldown = lastTs > 0 && (now - lastTs) < 60_000L
        if (!withinCooldown) {
            val amountText = if (amount == 1) "1 coin" else "$amount coins"
            val notificationID = java.util.UUID.randomUUID().toString()
            val notification = mapOf(
                "id" to notificationID,
                "recipientID" to creatorID,
                "senderID" to tipperID,
                "type" to "tip",
                "title" to "💰 You got tipped!",
                "message" to "$tipperUsername tipped you $amountText",
                "payload" to mapOf(
                    "senderUsername" to tipperUsername,
                    "senderID" to tipperID,
                    "amount" to amount,
                    "videoID" to videoID,
                    "notificationType" to "tip"
                ),
                "isRead" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "expiresAt" to com.google.firebase.Timestamp(
                    java.util.Date(now + 30L * 24 * 60 * 60 * 1000)
                )
            )
            db.collection("notifications").document(notificationID).set(notification).await()
            db.collection("notification_cooldowns").document(cooldownKey)
                .set(mapOf("lastNotificationAt" to com.google.firebase.Timestamp(java.util.Date())))
                .await()
            if (BuildConfig.DEBUG) { println("✅ TIP NOTIF: $amountText tip notification written for $creatorID") }
        } else {
            if (BuildConfig.DEBUG) { println("⏱ TIP NOTIF: Cooldown active ($tipperID → $creatorID)") }
        }
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) { println("⚠️ TIP NOTIF: write failed — ${e.message}") }
    }

    // 2) Per-tipper supporter row under the creator (atomic increment so
    //    repeat tips just bump totalSent).
    try {
        db.collection("users").document(creatorID)
            .collection("supporters").document(tipperID)
            .set(
                mapOf(
                    "tipperID" to tipperID,
                    "username" to tipperUsername,
                    "totalSent" to FieldValue.increment(amount.toLong()),
                    "lastSentAt" to com.google.firebase.Timestamp(java.util.Date())
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .await()
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) { println("⚠️ TIP AGG: supporter row update failed for $creatorID/$tipperID — ${e.message}") }
        return
    }

    // 3) Refresh creator's top-10 array. Read subcollection ordered desc,
    //    project to {tipperID, username, totalSent}, write to user doc.
    try {
        val snapshot = db.collection("users").document(creatorID)
            .collection("supporters")
            .orderBy("totalSent", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(10)
            .get()
            .await()
        val top: List<Map<String, Any>> = snapshot.documents.map { doc ->
            val data = doc.data ?: emptyMap()
            mapOf(
                "tipperID" to (data["tipperID"] as? String ?: doc.id),
                "username" to (data["username"] as? String ?: "user"),
                "totalSent" to ((data["totalSent"] as? Number)?.toInt() ?: 0)
            )
        }
        db.collection("users").document(creatorID).update("topSupporters", top).await()
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) { println("⚠️ TIP AGG: topSupporters refresh failed for $creatorID — ${e.message}") }
    }
}

private suspend fun resolveDisplayName(db: FirebaseFirestore, userID: String): String {
    if (userID.isEmpty()) return "Someone"
    return try {
        val data = db.collection("users").document(userID).get().await().data ?: return "Someone"
        val displayName = (data["displayName"] as? String)?.takeIf { it.isNotEmpty() }
        val username    = (data["username"]    as? String)?.takeIf { it.isNotEmpty() }
        displayName ?: username ?: "Someone"
    } catch (_: Exception) {
        "Someone"
    }
}