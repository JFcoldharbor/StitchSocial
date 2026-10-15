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
import androidx.compose.material.icons.filled.Star
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
import com.stitchsocial.club.ui.theme.Spacing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.stitchsocial.club.services.CoinTransferKind
import com.stitchsocial.club.services.HypeCoinService
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
    val BOTTOM_PADDING_MIN = 56.dp  // Clears the tab bar; the creator row hugs the title on its own
    val BOTTOM_PADDING_MAX = 76.dp  // The action labels sat under the tab bar at 58
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
    val cachedAt: Date,
    /** users/{id}.birthdayUntil, from the same read — drives the 🎂 ring. */
    val birthdayUntil: Date? = null
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
    var lastEngagementAt: Date,
    /// Coins tipped on this video, all time (videos/{id}.coinTotal).
    var coinTotal: Int = 0
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
    // Fullscreen hosts pass these so share + exit + the 3-dots render in ONE
    // coordinated top-right row (no overlapping floating buttons).
    showShareInTop: Boolean = false,
    onExit: (() -> Unit)? = null,
    onAction: ((OverlayAction) -> Unit)? = null,
    // Promoted posts (PROMOTED_POSTS_CONTRACT). The Discovery ad position
    // passes the slot's CTA here, which also forces the Sponsored chip; every
    // other host leaves it null and the post's own `promotion` decides.
    sponsoredCta: SponsoredCta? = null,
    // "sponsored" when this view comes from the ad position; null = organic.
    viewSource: String? = null,
    // Height from the bottom edge to the top of the bottom block (caption +
    // CTA + actions). The ad position uses it as the media-fit clear area's
    // bottom edge.
    onBottomBlockHeight: ((Dp) -> Unit)? = null,
    // A tile in the thread rail was tapped (iOS onSelectStitch): the host
    // switches playback to that clip — pages its pager to it, or loads it in
    // place. Return false when the host can't play it here; the overlay then
    // falls back to the thread preview panel. Null = always the panel.
    onSelectStitch: ((CoreVideoMetadata) -> Boolean)? = null
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
            else -> 66.dp // Medium screens
        }
    }

    // State
    var isLoadingUserData by remember { mutableStateOf(false) }
    var realCreatorName by remember { mutableStateOf<String?>(null) }
    var realCreatorProfileImageURL by remember { mutableStateOf<String?>(null) }
    var realCreatorBirthdayUntil by remember { mutableStateOf<Date?>(null) }
    var realThreadCreatorName by remember { mutableStateOf<String?>(null) }
    var realThreadCreatorProfileImageURL by remember { mutableStateOf<String?>(null) }
    var videoEngagement by remember { mutableStateOf<ContextualVideoEngagement?>(null) }
    /** publicVisibility as last re-read from Firestore (null = not read yet). */
    var freshVisibility by remember(video.id) { mutableStateOf<String?>(null) }
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

                    videoService.recordVideoView(video.id, currentUserID, viewerData, watchTime = 5.0, source = viewSource)
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
    val isUserVideo: Boolean = currentUserID != null && video.creatorID == currentUserID
    // The re-read count wins over the snapshot the host passed in, which can
    // be minutes (or a cached feed) old — that's what kept a new reply's
    // count from ever showing until relaunch.
    val displayReplyCount: Int = actualReplyCount ?: videoEngagement?.replyCount ?: video.replyCount
    // Author-only "Under review" label while moderation runs on their post.
    val isUnderReview: Boolean = isUserVideo && (freshVisibility ?: video.publicVisibility) == "pending"
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
            realCreatorBirthdayUntil = cached.birthdayUntil
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
                    realCreatorBirthdayUntil = profile.birthdayUntil
                    if (BuildConfig.DEBUG) { println("Ã°Å¸â€˜Â¤ CREATOR PILL DEBUG: displayName=${realCreatorName}, imageURL=${realCreatorProfileImageURL}") }
                    // Cache the result
                    UserDataCache.set(video.creatorID, OverlayUserCache(
                        displayName = realCreatorName ?: "",
                        profileImageURL = realCreatorProfileImageURL,
                        tier = null,
                        cachedAt = Date(),
                        birthdayUntil = profile.birthdayUntil
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
            lastEngagementAt = Date(),
            coinTotal = video.coinTotal
        )
        // Step 2 — refresh from Firestore
        try {
            val fresh = videoService.getVideoById(video.id)
            if (fresh != null) {
                freshVisibility = fresh.publicVisibility ?: "public"
                videoEngagement = ContextualVideoEngagement(
                    videoID = fresh.id,
                    creatorID = fresh.creatorID,
                    hypeCount = fresh.hypeCount,
                    coolCount = fresh.coolCount,
                    shareCount = fresh.shareCount,
                    replyCount = fresh.replyCount,
                    viewCount = fresh.viewCount,
                    lastEngagementAt = Date(),
                    coinTotal = fresh.coinTotal
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
                        lastEngagementAt = Date(),
                        coinTotal = fresh.coinTotal
                    )
                }
            } catch (_: Exception) { /* swallow — visual feedback already happened */ }
        }
    }

    // One-shot re-read (no listener) when the viewer's own reply lands on
    // this video's thread, or the app returns to the foreground: fresh
    // counts, and the thread panel reloads next time it's showing.
    val threadRefreshTick = rememberThreadRefreshTick { it.touches(video.id, video.threadID) }
    LaunchedEffect(video.id, threadRefreshTick) {
        if (threadRefreshTick == 0) return@LaunchedEffect
        try {
            val fresh = videoService.getVideoById(video.id) ?: return@LaunchedEffect
            freshVisibility = fresh.publicVisibility ?: "public"
            videoEngagement = ContextualVideoEngagement(
                videoID = fresh.id,
                creatorID = fresh.creatorID,
                hypeCount = fresh.hypeCount,
                coolCount = fresh.coolCount,
                shareCount = fresh.shareCount,
                replyCount = fresh.replyCount,
                viewCount = fresh.viewCount,
                lastEngagementAt = Date(),
                coinTotal = fresh.coinTotal
            )
        } catch (_: Exception) { /* keep what's on screen */ }
    }

    // Thread lineage for the right-edge chain rail (iOS StitchChainRail). Read
    // once per thread and shared through ThreadChainCache, so paging through a
    // thread doesn't re-read it; re-read on an own-reply / foreground tick.
    val chainThreadID = video.threadID ?: video.id
    var chainData by remember(chainThreadID) { mutableStateOf(ThreadChainCache.get(chainThreadID)) }
    LaunchedEffect(chainThreadID, threadRefreshTick) {
        if (overlayContext == OverlayContext.DISCOVERY || overlayContext == OverlayContext.CAROUSEL) return@LaunchedEffect
        if (chainData != null && threadRefreshTick == 0) return@LaunchedEffect
        try {
            val (parent, children) = videoService.getThreadData(chainThreadID)
            if (parent != null) {
                ThreadChainCache.put(chainThreadID, parent, children)
                chainData = parent to children
            }
        } catch (e: Exception) {
            Log.w("CHAIN_RAIL", "Thread load failed for $chainThreadID: ${e.message}")
        }
    }
    val stitchChain: List<StitchChainRailLink> = remember(chainData) {
        chainData?.let { (parent, children) -> buildStitchChain(parent, children) } ?: emptyList()
    }

    // One line and a "more"; views and the thread credit on request. A new
    // clip starts collapsed, whichever way you arrived at it.
    var detailExpanded by remember(video.id) { mutableStateOf(false) }

    // Promoted now: the Sponsored chip by the name, and the CTA above the
    // caption. The ad position's slot CTA wins over the post's own.
    val isSponsored: Boolean = sponsoredCta != null || video.isPromotedNow
    val ownCta: SponsoredCta? = remember(video.promotion) {
        video.promotion?.takeIf { video.isPromotedNow }
            ?.let { promo -> SponsoredCta.forPromotion(promo, context) }
    }
    val effectiveCta: SponsoredCta? = sponsoredCta ?: ownCta

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
                currentUserID = currentUserID,
                currentUserTier = currentUserTier
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
                displayCreatorBirthdayUntil = realCreatorBirthdayUntil,
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
                },
                showShareInTop = showShareInTop,
                onExit = onExit,
                isSponsored = isSponsored,
                isUnderReview = isUnderReview,
                sponsoredCta = effectiveCta,
                onBottomBlockHeight = onBottomBlockHeight,
                stitchChain = stitchChain,
                detailExpanded = detailExpanded,
                onDetailExpandedChange = { detailExpanded = it },
                onChainLinkTap = { link ->
                    if (link.id != video.id) {
                        // The clip itself is already in hand — the rail was
                        // built from it — so the host never has to fetch.
                        val target = chainData?.let { (parent, children) ->
                            if (parent?.id == link.id) parent else children.firstOrNull { it.id == link.id }
                        }
                        val handled = target != null && onSelectStitch?.invoke(target) == true
                        if (!handled) {
                            // The host can't play it in place: the thread
                            // preview, the same fallback iOS takes.
                            chainData?.second?.let { threadPanelChildren = it }
                            threadPanelExpanded = true
                            showThreadPanel = true
                        }
                    }
                },
                onOpenSlotTap = {
                    // The open slot IS the stitch action — the same branch as
                    // the rail's Stitch button, so they can't drift apart.
                    if (com.stitchsocial.club.services.AccountStanding.guardPosting()) {
                        pauseAllVideos(context)
                        onAction?.invoke(OverlayAction.StitchRecording)
                    }
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
    // Re-read once per refresh trigger (own reply / foreground) as well.
    var threadPanelLoadedTick by remember(video.id) { mutableStateOf(-1) }
    LaunchedEffect(showThreadPanel, threadRefreshTick) {
        if (showThreadPanel && (threadPanelChildren.isEmpty() || threadPanelLoadedTick != threadRefreshTick)) {
            try {
                val threadID = video.threadID ?: video.id
                val (_, children) = videoService.getThreadData(threadID)
                threadPanelChildren = children
                threadPanelLoadedTick = threadRefreshTick
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

    // Admin highlight — spotlight ANY video as the launch announcement.
    // Gated on the same email allowlist the Firestore rule enforces, so a
    // normal user never sees this item and the server refuses it regardless.
    val adminEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email
    val canHighlight = com.stitchsocial.club.services.AnnouncementService.shared
        .isAuthorizedCreator(adminEmail)
    var showHighlightSheet by remember { mutableStateOf(false) }
    var showCollabSheet by remember { mutableStateOf(false) }

    // A brand can promote its own posts. Promotion is bought on the web
    // portal, so the item only opens stitchsocial.me/brand/promote.
    val context = androidx.compose.ui.platform.LocalContext.current
    val isBrandOwner by produceState(false, currentUserID, isUserVideo) {
        value = isUserVideo &&
            com.stitchsocial.club.services.BrandPortal.isBusinessAccount(currentUserID.orEmpty())
    }

    Box {
        // 32dp circle to match the share + exit buttons in the vertical top stack.
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable { menuExpanded = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
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
            if (canHighlight) {
                DropdownMenuItem(
                    text = { Text("Highlight this video") },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        showHighlightSheet = true
                    }
                )
            }
            // Owner only: createCollabInvite refuses anybody else, so offering
            // the item to a viewer would be offering a button that cannot work.
            if (isBrandOwner) {
                DropdownMenuItem(
                    text = { Text("Promote this post") },
                    leadingIcon = { Icon(Icons.Default.Campaign, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        com.stitchsocial.club.services.BrandPortal.open(
                            context, com.stitchsocial.club.AppConfig.URLs.brandPromote(video.id)
                        )
                    }
                )
            }
            if (isUserVideo) {
                DropdownMenuItem(
                    text = { Text("Collaborators") },
                    leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        showCollabSheet = true
                    }
                )
            }
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

    if (showCollabSheet) {
        CollabSheet(video = video, onDismiss = { showCollabSheet = false })
    }

    if (showHighlightSheet) {
        AdminHighlightSheet(
            videoId = video.id,
            videoTitle = video.title,
            adminEmail = adminEmail ?: "",
            adminUserId = currentUserID ?: "",
            onDismiss = { showHighlightSheet = false },
        )
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
    currentUserID: String? = null,
    currentUserTier: UserTier = UserTier.ROOKIE
) {
    // Fixed text sizes
    val nameFontSize = OverlaySizes.LABEL_MEDIUM.fixedSp()
    val titleFontSize = OverlaySizes.LABEL_LARGE.fixedSp()

    Box(modifier = Modifier.fillMaxSize()) {
        // Top: Creator name with temperature dot
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                // 24 rather than 12: at 12 the pill sat up against the top bar.
                .padding(top = 24.dp, start = 12.dp)
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

        // Bottom: chips, then the title. iOS minimalDiscoveryOverlay — the
        // right edge is empty on a discovery card now; share and the 3-dots
        // live on the creator row of the full player.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 16.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            com.stitchsocial.club.landing.VideoLandingChips(
                video = video,
                fontSize = OverlaySizes.LABEL_TINY.fixedSp(),
                beforeOpen = { pauseAllVideos(context) }
            )
            if (video.title.isNotEmpty()) {
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
                    label = "Cooldown",
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
                        // Stitch / reply / spin-off: suspended accounts get the
                        // sheet; playback isn't even paused.
                        if (!com.stitchsocial.club.services.AccountStanding.guardPosting()) return@Overlay3DActionButton
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
    displayCreatorBirthdayUntil: Date?,
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
    onThreadTap: () -> Unit,
    showShareInTop: Boolean = false,
    onExit: (() -> Unit)? = null,
    isSponsored: Boolean = false,
    isUnderReview: Boolean = false,
    sponsoredCta: SponsoredCta? = null,
    onBottomBlockHeight: ((Dp) -> Unit)? = null,
    stitchChain: List<StitchChainRailLink> = emptyList(),
    detailExpanded: Boolean = false,
    onDetailExpandedChange: (Boolean) -> Unit = {},
    onChainLinkTap: (StitchChainRailLink) -> Unit = {},
    onOpenSlotTap: () -> Unit = {}
) {
    // The rail earns its space only when there's an actual chain to draw — a
    // thread of one would be a single tile over an open slot.
    val showsChainRail = stitchChain.size > 1
    // Height from the screen's bottom edge to the top of the action rail,
    // measured, so the chain rail centres in what's above it.
    var actionRailClearance by remember { mutableStateOf(0.dp) }
    var overlayCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { overlayCoords = it }) {
        // The creator pills that used to sit top-left (and the purple thread
        // creator pill beside them) live in the creator block at the bottom
        // now, beside everything else about the post. Share and the 3-dots
        // moved there too. Only a fullscreen host's exit stays up here.
        if (onExit != null && overlayContext != OverlayContext.CAROUSEL) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 16.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(onClick = onExit),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
            }
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

        // The chain as a right-edge thread, centred in the height above the
        // action rail rather than pinned under the notch, so it reads as part
        // of the video and can never sit on the buttons. The creator row and
        // caption reserve its column (railReserve), so nothing runs under it.
        if (showsChainRail) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = actionRailClearance),
                contentAlignment = Alignment.CenterEnd
            ) {
                StitchChainRail(
                    chain = stitchChain,
                    currentID = video.id,
                    onTapLink = onChainLinkTap,
                    onTapOpenSlot = onOpenSlotTap,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        }

        BottomSection(
            video = video,
            threadVideo = threadVideo,
            displayCreatorName = displayCreatorName,
            displayCreatorProfileImageURL = displayCreatorProfileImageURL,
            displayCreatorBirthdayUntil = displayCreatorBirthdayUntil,
            displayThreadCreatorName = displayThreadCreatorName,
            videoEngagement = videoEngagement,
            isUserVideo = isUserVideo,
            isFollowing = isFollowing,
            isFollowLoading = isFollowLoading,
            canReply = canReply,
            stitchButtonIcon = stitchButtonIcon,
            stitchButtonLabel = stitchButtonLabel,
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
            onFollowToggle = onFollowToggle,
            onViewersTap = onViewersTap,
            onAction = onAction,
            onThreadTap = onThreadTap,
            isSponsored = isSponsored,
            isUnderReview = isUnderReview,
            sponsoredCta = sponsoredCta,
            onBottomBlockHeight = onBottomBlockHeight,
            // The rail's column (58) + its 16 edge margin + a gap, less the
            // block's own 12 side padding: everything above the action rail
            // stops short of the rail, on every host.
            railReserve = if (showsChainRail) 70.dp else 0.dp,
            detailExpanded = detailExpanded,
            onDetailExpandedChange = onDetailExpandedChange,
            onActionRailPositioned = { rail ->
                // Overlay's bottom edge to the action rail's top edge, in the
                // overlay's own coordinates (hosts pad the overlay's top).
                val outer = overlayCoords
                if (outer != null && outer.isAttached && rail.isAttached) {
                    val top = outer.localPositionOf(rail, Offset.Zero).y
                    val clearance = with(density) { (outer.size.height - top).toDp() }
                    if (clearance != actionRailClearance) actionRailClearance = clearance
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// MARK: - Bottom Section
//
// iOS bottomSection: who, then what, then what you can do about it.
//   1. Creator row — CreatorBlock (avatar + follow badge, name, detail) with
//      share and the 3-dots at the end of the row.
//   2. Landing / under-review / reach-boost chips, then the sponsor's CTA.
//   3. The caption on one line with "more"; expanded it shows up to 4 lines
//      plus the description, and tapping it collapses.
//   4. The action rail: four equal columns — Thread, Cooldown, Hype/Tip,
//      Stitch (when the viewer can stitch, reply or spin off here).

@Composable
private fun BottomSection(
    video: CoreVideoMetadata,
    threadVideo: CoreVideoMetadata?,
    displayCreatorName: String,
    displayCreatorProfileImageURL: String?,
    displayCreatorBirthdayUntil: Date?,
    displayThreadCreatorName: String,
    videoEngagement: ContextualVideoEngagement?,
    isUserVideo: Boolean,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    canReply: Boolean,
    stitchButtonIcon: androidx.compose.ui.graphics.vector.ImageVector,
    stitchButtonLabel: String,
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
    onFollowToggle: () -> Unit,
    onViewersTap: () -> Unit,
    onAction: ((OverlayAction) -> Unit)?,
    onThreadTap: () -> Unit,
    isSponsored: Boolean,
    isUnderReview: Boolean,
    sponsoredCta: SponsoredCta? = null,
    onBottomBlockHeight: ((Dp) -> Unit)? = null,
    railReserve: Dp = 0.dp,
    detailExpanded: Boolean = false,
    onDetailExpandedChange: (Boolean) -> Unit = {},
    onActionRailPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val captionShadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 2f), 4f)
    val showsThreadOwner = threadVideo != null && threadVideo.creatorID != video.creatorID

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Measured before the padding, so the height runs from the bottom
            // edge to the top of the block — the clear area's bottom edge.
            .then(
                if (onBottomBlockHeight != null) Modifier.onSizeChanged { size ->
                    onBottomBlockHeight(with(density) { size.height.toDp() })
                } else Modifier
            )
            .padding(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Creator row. The block takes all the width that's left and
        // ellipsizes inside it, so share and the 3-dots sit at one fixed spot
        // at the row's right end — a long name, the Sponsored chip or the
        // expanded detail lines can't push them. Bottom-aligned: share and the
        // 3-dots stack taller than the avatar, and top-aligned the avatar rode
        // up to their top, leaving a gap above the caption. Against the bottom
        // it sits on the caption, and expanded detail lines grow upward.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp + railReserve),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            CreatorBlock(
                displayName = displayCreatorName,
                handle = video.creatorName.ifEmpty { null },
                profileImageURL = displayCreatorProfileImageURL,
                identityKey = video.creatorID,
                // users/{id}.birthdayUntil from the creator read above.
                birthdayUntil = displayCreatorBirthdayUntil,
                viewCount = videoEngagement?.viewCount ?: video.viewCount,
                showsDetail = detailExpanded,
                onViewsTap = if (isUserVideo) onViewersTap else null,
                threadOwnerName = if (showsThreadOwner) displayThreadCreatorName else null,
                onThreadOwnerTap = threadVideo?.let { tv ->
                    {
                        pauseAllVideos(context)
                        onAction?.invoke(OverlayAction.NavigateToProfile(tv.creatorID))
                    }
                },
                collaborators = video.collaborators,
                isSponsored = isSponsored,
                isFollowing = isUserVideo || isFollowing,
                onFollowTap = if (isUserVideo) null else ({ if (!isFollowLoading) onFollowToggle() }),
                modifier = Modifier.weight(1f),
                onTap = {
                    pauseAllVideos(context)
                    onAction?.invoke(OverlayAction.NavigateToProfile(video.creatorID))
                }
            )
            // Share and the 3-dots at the end of the row that names the
            // creator — small, because they aren't what this block is for.
            Column(
                modifier = Modifier.width(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ShareButton(
                    video = video,
                    creatorUsername = displayCreatorName,
                    threadID = video.threadID ?: video.id,
                    size = ShareButtonSize.TINY,
                    userTier = currentUserTier
                )
                MoreOptionsMenu(
                    video = video,
                    displayCreatorName = displayCreatorName,
                    currentUserID = currentUserID
                )
            }
        }

        // 2. Chips said before the caption: a paid-for post says so first.
        val chipModifier = Modifier.padding(start = 12.dp, end = 12.dp + railReserve)
        com.stitchsocial.club.landing.VideoLandingChips(
            video = video,
            modifier = chipModifier,
            fontSize = OverlaySizes.LABEL_TINY.fixedSp(),
            beforeOpen = { pauseAllVideos(context) }
        )
        if (isUnderReview) {
            UnderReviewChip(fontSize = OverlaySizes.LABEL_TINY.fixedSp(), modifier = chipModifier)
        }
        // Invite first-post boost: owner-only, never a label for viewers.
        if (isUserVideo && video.isReachBoostedNow) {
            video.reachBoostUntil?.let { until ->
                Box(chipModifier) { BoostedUntilChip(until = until, fontSize = OverlaySizes.LABEL_TINY.fixedSp()) }
            }
        }

        // The sponsor's button, above the caption.
        if (sponsoredCta != null) {
            SponsoredCtaButton(
                cta = sponsoredCta,
                onTap = { pauseAllVideos(context) },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp + railReserve)
            )
        }

        // 3. Caption: one line and a "more"; expanded, the caption itself is
        // the way back.
        if (video.title.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp + railReserve),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = video.title,
                    style = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = OverlaySizes.TITLE.fixedSp(),
                        fontWeight = FontWeight.SemiBold,
                        shadow = captionShadow
                    ),
                    maxLines = if (detailExpanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable(enabled = detailExpanded) { onDetailExpandedChange(false) }
                )
                if (!detailExpanded) {
                    Text(
                        text = "more",
                        style = androidx.compose.ui.text.TextStyle(
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = OverlaySizes.TITLE.fixedSp(),
                            fontWeight = FontWeight.SemiBold,
                            shadow = captionShadow
                        ),
                        maxLines = 1,
                        modifier = Modifier.clickable { onDetailExpandedChange(true) }
                    )
                }
            }
        } else if (!detailExpanded) {
            // No caption: "more" alone still opens the detail lines.
            Text(
                text = "more",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = OverlaySizes.TITLE.fixedSp(),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clickable { onDetailExpandedChange(true) }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 4. The action rail — four equal columns, full width.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .onGloballyPositioned { coords -> onActionRailPositioned(coords) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Overlay3DActionButton(
                    label = "Thread",
                    rimColors = listOf(Color.Cyan.copy(alpha = 0.8f), Color(0xFF2196F3).copy(alpha = 0.5f)),
                    glowColor = Color.Cyan,
                    onClick = {
                        // Parent video keeps playing — the thread preview
                        // panel slides in over the bottom of the feed.
                        onThreadTap()
                    }
                ) {
                    Icon(Icons.Default.Forum, "Thread", tint = Color.White, modifier = Modifier.size(OverlaySizes.ICON_SIZE))
                }
            }

            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
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
                        label = "Cooldown",
                        ringColor = Color.Blue,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAction?.invoke(OverlayAction.Engagement(EngagementType.COOL))
                        }
                    )
                }
            }

            // Swappable Hype / Tip slot — swipe UP = tip, DOWN = hype
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
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
            }

            // Stitch (conditional). For COLLECTION context it looks like
            // Reply but still emits StitchRecording — the caller turns that
            // into the recording flow with the segment as parent.
            if (canReply) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Overlay3DActionButton(
                        label = stitchButtonLabel,
                        rimColors = listOf(
                            StitchColors.gradientStart.copy(alpha = 0.7f),
                            StitchColors.gradientEnd.copy(alpha = 0.5f)
                        ),
                        glowColor = StitchColors.primary,
                        onClick = {
                            // Suspended accounts get the sheet; playback isn't even paused.
                            if (!com.stitchsocial.club.services.AccountStanding.guardPosting()) return@Overlay3DActionButton
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
        // The brand logo has internal whitespace, so at ICON_SIZE it read tiny inside
        // the 42dp sphere. Fill most of the button (34dp) leaving ~4dp margin to the rim.
        Icon(
            painter = painterResource(R.drawable.stitchsociallogo),
            contentDescription = "Stitch",
            tint = Color.White,
            modifier = Modifier.size(OverlaySizes.BUTTON_SIZE - 8.dp)
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
    // Tips sent from this screen, held here rather than in TipButton: the
    // button leaves composition when the slot swaps back to Hype, and its own
    // count went with it.
    var sessionTips by androidx.compose.runtime.remember(video.id) { androidx.compose.runtime.mutableStateOf(0) }

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
                        coinTotal = (videoEngagement?.coinTotal ?: video.coinTotal) + sessionTips,
                        isSelfTip = isSelfTip,
                        onAction = onAction,
                        onTipSent = { sessionTips += it }
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
// Coins move through the coins_transfer callable (kind tip), which also
// increments videos.coinTotal and sends the tip notification. Client writes
// left: creator's supporters subcollection + topSupporters array.
// ============================================================================

@androidx.compose.runtime.Composable
internal fun TipButton(
    videoID: String,
    creatorID: String,
    currentUserID: String,
    coinTotal: Int,
    isSelfTip: Boolean,
    onAction: ((OverlayAction) -> Unit)?,
    /** Coins sent (negative when a send fails and is taken back). */
    onTipSent: (Int) -> Unit = {}
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val db = androidx.compose.runtime.remember { FirebaseFirestore.getInstance("stitchfin") }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // coinTotal already includes this session's tips — the host adds them.
    val displayCount = coinTotal

    var isPressed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showBurst by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var errorMsg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var showError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val scale by animateFloatAsState(if (isPressed) 0.9f else 1f, label = "tip_scale")

    fun sendTip(amount: Int) {
        onTipSent(amount)
        scope.launch {
            try {
                // Server moves the coins, bumps videos.coinTotal and sends the
                // tip notification (with its 60s cooldown). One clientTxnID per
                // tap; HypeCoinService reuses it on transient retries.
                HypeCoinService.shared.transferCoins(
                    fromUserID = currentUserID,
                    toUserID = creatorID,
                    amount = amount,
                    kind = CoinTransferKind.TIP,
                    videoID = videoID
                )
                if (BuildConfig.DEBUG) { println("💰 TIP: $currentUserID → $creatorID +$amount on $videoID") }

                // Side-effects — creator's supporters list + topSupporters
                // array. Run after the transfer so a side-effect failure
                // doesn't roll back the tip.
                recordTipSideEffects(
                    db = db,
                    tipperID = currentUserID,
                    creatorID = creatorID,
                    amount = amount
                )
            } catch (e: Exception) {
                onTipSent(-amount)
                errorMsg = when (e) {
                    is CoinError.InsufficientBalance -> "Not enough coins"
                    is CoinError.CoinsFrozen -> e.message ?: "Tip failed"
                    else -> "Tip failed"
                }
                showError = true
                scope.launch { delay(if (e is CoinError.CoinsFrozen) 4000 else 2000); showError = false }
                if (BuildConfig.DEBUG) { println("❌ TIP: ${e.message}") }
            }
        }
    }

    androidx.compose.foundation.layout.Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
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

        // Label — count and word together, like Hype; the word alone until
        // there is a count. "Tip" on your own video too (greyed).
        androidx.compose.material3.Text(
            when {
                displayCount <= 0 -> "Tip"
                displayCount == 1 -> "1 Tip"
                else -> "${formatHypeCount(displayCount)} Tips"
            },
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
// MARK: - Tip side-effects (mirrors iOS TipService.recordTipAggregates).
// Run after the tip transfer succeeds.
//
// Schema:
//   users/{creatorID}/supporters/{tipperID}                      — per-tipper aggregate
//   users/{creatorID}.topSupporters: [{tipperID, username, totalSent}]
//                                                                 — denormalized top 10
// ============================================================================

private suspend fun recordTipSideEffects(
    db: FirebaseFirestore,
    tipperID: String,
    creatorID: String,
    amount: Int
) {
    if (tipperID.isEmpty() || creatorID.isEmpty()) return

    // Resolve tipper's display name with the same fallback chain iOS uses:
    // displayName → username → "Someone". The supporters list reads better
    // than a raw uid prefix.
    val tipperUsername = resolveDisplayName(db, tipperID)

    // The tip notification (and its 60s tipper→creator cooldown) is sent by
    // coins_transfer on the server — not written from here any more.

    // 1) Per-tipper supporter row under the creator (atomic increment so
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

    // 2) Refresh creator's top-10 array. Read subcollection ordered desc,
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

// MARK: - Promoted posts (PROMOTED_POSTS_CONTRACT)

/** The CTA a promoted post shows above its caption. */
data class SponsoredCta(val text: String, val onClick: () -> Unit) {
    companion object {
        /**
         * The post's own promotion CTA (every surface but the ad position,
         * which passes its slot's). A landing_claim routes in-app and claims
         * the welcome perk; anything else opens the link.
         */
        fun forPromotion(promo: VideoPromotion, context: Context): SponsoredCta? {
            val slug = promo.landingSlug
            if (promo.isLandingClaim && slug != null) {
                return SponsoredCta(promo.displayCtaText) {
                    com.stitchsocial.club.landing.LandingPresenter.route(
                        slug = slug,
                        source = com.stitchsocial.club.landing.LandingSource.SPONSORED,
                        autoClaim = true
                    )
                }
            }
            val url = promo.resolvedCtaURL
            if (url.isBlank()) return null
            return SponsoredCta(promo.displayCtaText) {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Log.w("SPONSORED", "Could not open ctaURL: ${e.message}")
                }
            }
        }
    }
}

/** Stitch brand magenta, the sponsored CTA's colour everywhere. */
private val SponsoredCtaMagenta = Color(0xFFE91E63)

/** Small "Sponsored" label beside a creator's name. */
@Composable
internal fun SponsoredChip(fontSize: TextUnit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "Sponsored",
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1
        )
    }
}

@Composable
private fun SponsoredCtaButton(
    cta: SponsoredCta,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(SponsoredCtaMagenta)
            .clickable {
                onTap()
                cta.onClick()
            }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = cta.text,
            fontSize = 14.fixedSp(),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Default.ArrowForward,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** Small "Sponsored" badge for profile and grid tiles of a promoted post. */
@Composable
fun SponsoredTileBadge(modifier: Modifier = Modifier) {
    Text(
        text = "Sponsored",
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}


/** "Boosted until 9:40 PM" (or "Oct 14, 9:40 PM" past today), creator only. */
@Composable
private fun BoostedUntilChip(until: Date, fontSize: TextUnit) {
    val context = LocalContext.current
    val label = remember(until) {
        val sameDay = android.text.format.DateUtils.isToday(until.time)
        val flags = android.text.format.DateUtils.FORMAT_SHOW_TIME or
            (if (sameDay) 0 else android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
        android.text.format.DateUtils.formatDateTime(context, until.time, flags)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF8A7AFF).copy(alpha = 0.75f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
        Text(
            text = "Boosted until $label",
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1
        )
    }
}
