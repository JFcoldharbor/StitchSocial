/*
 * MainActivity.kt - WITH FCM PUSH NOTIFICATIONS & ANNOUNCEMENTS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * ✅ FIXED: FCM registration now happens AFTER authentication completes
 * ✅ FIXED: All RecordingContext references now use camera.RecordingContext
 * ✅ FIXED: Removed coordination.RecordingContext enum conversions
 * ✅ FIXED: Gallery picker shows processing modal
 * ✅ FIXED: ThreadComposer modal shown after processing
 * ✅ FIXED: LoginView import from correct package
 * ✅ FIXED: Notification navigation with proper state management
 * ✅ NEW: NotificationView integrated in NOTIFICATIONS tab
 * ✅ NEW: FCM push notifications fully integrated
 * ✅ NEW: Android 13+ notification permission request added
 * ✅ NEW: Announcement system integrated
 */

package com.stitchsocial.club

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

// Foundation and Services
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.services.*
import com.stitchsocial.club.views.*
import com.stitchsocial.club.ui.screens.ThreadView
import com.stitchsocial.club.services.ConversationLaneService
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.VideoCoordinator
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.ui.theme.StitchSocialClubTheme
import com.stitchsocial.club.ui.theme.ThemeState
import com.stitchsocial.club.ui.theme.StitchColors
import com.stitchsocial.club.camera.RecordingContext
import com.stitchsocial.club.camera.getBadgeText
import com.stitchsocial.club.camera.ThreadComposer
import com.stitchsocial.club.VideoReviewView
import com.stitchsocial.club.VideoEditState
import java.io.File

// ✅ NEW: Announcement imports
import com.stitchsocial.club.models.Announcement
import com.stitchsocial.club.services.AnnouncementService
import com.stitchsocial.club.services.ShareService
import com.stitchsocial.club.views.AnnouncementOverlayView
import com.stitchsocial.club.community.CommunityListItem
import com.stitchsocial.club.community.CommunityService
import com.stitchsocial.club.BuildConfig


class MainActivity : ComponentActivity() {

    companion object {
        /**
         * FCM transport keys that ride along on a tray-built tap intent. Not
         * payload, so they're not handed to the router.
         */
        private val FCM_TRANSPORT_KEYS = setOf(
            "fromNotification", "notificationTitle", "notificationBody",
            "from", "collapse_key", "google.message_id", "google.sent_time",
            "google.ttl", "google.original_priority", "google.delivered_priority",
            "google.product_id", "google.c.sender.id", "gcm.n.analytics_data"
        )

        // ✅ NEW: Broadcast action for pausing videos
        const val ACTION_PAUSE_ALL_VIDEOS = "com.stitchsocial.club.PAUSE_ALL_VIDEOS"

        // ✅ NEW: Broadcast action for resuming videos after camera/modal closes
        const val ACTION_RESUME_ALL_VIDEOS = "com.stitchsocial.club.RESUME_ALL_VIDEOS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        suppressCameraLogging()

        // Firebase Analytics auto-collection was already on; this arms the
        // custom-event layer (AnalyticsService) for the rest of the app.
        com.stitchsocial.club.services.AnalyticsService.init(this)

        Log.d("STITCH_MAIN", "App starting up...")

        // ✅ REMOVED: FCM initialization moved to AFTER authentication
        // FCM will be initialized in LaunchedEffect after user authenticates

        // ✅ NEW: Create notification channel EARLY (before any push can arrive)
        FCMService.ensureChannelExists(this)

        // Initialize the per-stream video-clip disk cache. One-shot setup —
        // creates the cache directory under context.cacheDir.
        com.stitchsocial.club.live.StreamClipCache.init(this)

        // Initialize the MAIN video disk cache. Without this,
        // VideoDiskCache.buildCacheDataSourceFactory() throws, which broke
        // ExoPlayer playback (black video) and all prefetch. One-shot setup.
        com.stitchsocial.club.services.VideoDiskCache.init(this)

        // Stitch Landings: read the Play Install Referrer once per install, so a
        // guest who scanned a table QR before having the app lands on that
        // restaurant after signup (first-launch routing, LandingArrival).
        com.stitchsocial.club.landing.LandingReferrer.readOnce(this)

        // Bootstrap LinkedAccountManager so AuthService can use the
        // static `shared` accessor when seeding linked accounts on signIn.
        com.stitchsocial.club.services.LinkedAccountManager.getInstance(this)

        // ✅ NEW: Request notification permission (Android 13+)
        requestNotificationPermission()

        // Consent first, THEN the Ads SDK — see AdMobBootstrap. Started from the
        // Activity because a consent form is a dialog and needs one.
        com.stitchsocial.club.ads.AdMobBootstrap.start(this)

        enableEdgeToEdge()

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        ThemeState.load(this)
        setContent {
            StitchSocialClubTheme(mode = ThemeState.mode) {
                MainScreen()
            }
        }

        // ✅ Handle notification intent if app opened from notification.
        // NOT on recreation (rotation, process restore): the Activity's intent
        // is the same one that already routed, and routing it again would yank
        // the user back to a notification they've moved on from.
        handleNotificationIntent(intent, isRecreation = savedInstanceState != null)
        // Cold start from a tapped stitchsocial.me link.
        handleDeepLinkIntent(intent)
    }

    // ✅ Handle new intents when app is already running
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent, isRecreation = false)
        // Warm start: singleTop means onCreate does NOT re-run, so a link tapped
        // while the app is alive only arrives here.
        handleDeepLinkIntent(intent)
    }

    /**
     * ✅ NEW: Pause all videos when app goes to background
     */
    override fun onPause() {
        super.onPause()
        Log.d("STITCH_MAIN", "⏸️ App pausing - broadcasting pause all videos")
        pauseAllVideos()
    }

    /**
     * ✅ NEW: Stop all videos when app is no longer visible
     */
    override fun onStop() {
        super.onStop()
        Log.d("STITCH_MAIN", "⏹️ App stopping - broadcasting pause all videos")
        pauseAllVideos()
        ShareService.cleanup(this)
    }

    /**
     * ✅ NEW: Broadcast pause intent to all video players
     */
    private fun pauseAllVideos() {
        try {
            val intent = Intent(ACTION_PAUSE_ALL_VIDEOS)
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d("STITCH_MAIN", "✅ Pause broadcast sent")
        } catch (e: Exception) {
            Log.e("STITCH_MAIN", "❌ Failed to send pause broadcast: ${e.message}")
        }
    }

    /**
     * ✅ NEW: Broadcast resume intent to all video players (called when camera/modal closes)
     */
    private fun resumeAllVideos() {
        try {
            val intent = Intent(ACTION_RESUME_ALL_VIDEOS)
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d("STITCH_MAIN", "✅ Resume broadcast sent")
        } catch (e: Exception) {
            Log.e("STITCH_MAIN", "❌ Failed to send resume broadcast: ${e.message}")
        }
    }

    /**
     * ✅ NEW: Request notification permission for Android 13+
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d("STITCH_MAIN", "📱 Requesting POST_NOTIFICATIONS permission...")
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        } else {
            Log.d("STITCH_MAIN", "📱 Android < 13, notification permission not required")
        }
    }

    /**
     * ✅ NEW: Handle permission result (deprecated but still works for requestPermissions)
     */
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.d("STITCH_MAIN", "✅ Notification permission granted")
            } else {
                Log.w("STITCH_MAIN", "⚠️ Notification permission denied")
            }
        }
    }

    /**
     * ✅ FIXED: Store notification intent for processing by Compose
     */
    /**
     * Routes an incoming stitchsocial.me link (or a stitchsocial:// scheme link)
     * — iOS parity with StitchSocialApp.routeIncomingURL.
     *
     * Called from BOTH onCreate and onNewIntent. The launcher is singleTop, so a
     * link tapped while the app is already running arrives at onNewIntent and
     * NEVER re-runs onCreate; handling only one of the two is the standard way
     * this ends up working cold and silently failing warm.
     *
     * The custom scheme puts the segment in the host (stitchsocial://e/{id})
     * while the https form puts it in the path, so both shapes are normalised
     * here rather than per link type.
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return

        val segments = uri.pathSegments.orEmpty().filter { it.isNotBlank() }

        fun segmentAfter(key: String): String? {
            if (uri.host == key) return segments.firstOrNull()?.takeIf { it.isNotBlank() }
            val i = segments.indexOf(key)
            return if (i >= 0 && i + 1 < segments.size) segments[i + 1] else null
        }

        // Content links go through the same router as notification taps, so a
        // link opened over a recording / live stream / composer asks "Leave?"
        // and interrupts exactly the way a tap does.
        segmentAfter("e")?.let { eventID ->
            Log.d("STITCH_MAIN", "🔗 LINK: event $eventID")
            NotificationRouter.request(NotificationRouter.TYPE_SHARED_LINK, mapOf("eventID" to eventID))
            return
        }

        // /l/{slug}?t={spot}: a Landing (a restaurant's table QR). Routed like
        // any shared link, so it interrupts and asks "Leave?" the same way.
        segmentAfter("l")?.let { slug ->
            val spot = uri.getQueryParameter("t")?.takeIf { it.isNotBlank() }
            Log.d("STITCH_MAIN", "🔗 LINK: landing $slug (spot=$spot)")
            val data = mutableMapOf("landingSlug" to slug)
            spot?.let { data["landingSpot"] = it }
            NotificationRouter.request(NotificationRouter.TYPE_SHARED_LINK, data)
            return
        }

        // /invite/{code}: whoever tapped this has no account yet, so there's
        // nothing to navigate to — the code is held until they reach signup,
        // which may be several screens and a process death away.
        segmentAfter("invite")?.let { code ->
            Log.d("STITCH_MAIN", "🎟️ LINK: captured referral ${code.uppercase()}")
            com.stitchsocial.club.foundation.PendingReferral.capture(this, code)
            return
        }

        // /u/{username}: the link the profile share sheet hands out. It carries a
        // USERNAME and every profile surface takes a userID, so resolve first.
        segmentAfter("u")?.let { username ->
            lifecycleScope.launch {
                // Usernames are stored lowercase; links get title-cased in
                // transit by some clients.
                val userID = UserService(applicationContext)
                    .userIDForUsername(username.lowercase())
                if (userID == null) {
                    Log.d("STITCH_MAIN", "🔗 LINK: no user for @$username")
                    return@launch
                }
                Log.d("STITCH_MAIN", "🔗 LINK: profile @$username -> $userID")
                NotificationRouter.request(NotificationRouter.TYPE_SHARED_LINK, mapOf("userID" to userID))
            }
            return
        }

        // /v/{videoID}: the link the share button hands out. Opens the
        // fullscreen player on that video, same as a notification about it;
        // the route fetches it (and stays put if it's gone).
        segmentAfter("v")?.let { videoID ->
            Log.d("STITCH_MAIN", "🔗 LINK: video $videoID")
            NotificationRouter.request(NotificationRouter.TYPE_SHARED_LINK, mapOf("videoID" to videoID))
            return
        }

        Log.d("STITCH_MAIN", "🔗 LINK: unrouted $uri")
    }

    /**
     * Parks a tapped notification with [NotificationRouter]; MainScreen routes it
     * once the user is signed in and the main UI is up.
     *
     * Two kinds of tap reach here, and only one used to be recognised:
     *  - FOREGROUND: FCMService builds the notification itself and marks the
     *    intent fromNotification=true.
     *  - BACKGROUND / KILLED: the server always sends a `notification` block, so
     *    the SYSTEM tray builds it. Its tap intent carries the data keys as
     *    extras (plus google.message_id) and no fromNotification — and was
     *    dropped as a "normal app launch", so no background tap ever routed.
     * Either is recognised now by the FCM data it carries.
     *
     * The extras are stripped once parked, so the same intent can't route twice
     * (e.g. redelivered on a later recreation).
     */
    private fun handleNotificationIntent(intent: Intent?, isRecreation: Boolean) {
        val extras = intent?.extras ?: return
        val isNotificationTap = extras.getBoolean("fromNotification", false) ||
            extras.containsKey("google.message_id") ||
            extras.containsKey("notificationID") ||
            extras.containsKey("type")
        if (!isNotificationTap) {
            Log.d("STITCH_MAIN", "📱 Normal app launch - no notification")
            return
        }

        // Relaunched from Recents: Android hands back the ORIGINAL launch intent,
        // extras and all. That tap was handled when it happened.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (isRecreation || fromHistory) {
            Log.d("STITCH_MAIN", "📱 Notification intent ignored (recreation=$isRecreation, recents=$fromHistory)")
            return
        }

        val data = mutableMapOf<String, String>()
        for (key in extras.keySet()) {
            if (key in FCM_TRANSPORT_KEYS || key.startsWith("google.") || key.startsWith("gcm.")) continue
            @Suppress("DEPRECATION")
            extras.get(key)?.toString()?.takeIf { it.isNotBlank() }?.let { data[key] = it }
        }
        val type = data["type"] ?: data["notification_type"]

        Log.d(
            "STITCH_MAIN",
            "📱 Notification tap: type=$type, id=${data["notificationID"]}, " +
                "video=${data["videoID"] ?: data["video_id"]}, user=${data["userID"] ?: data["senderID"] ?: data["user_id"]}"
        )
        NotificationRouter.request(type, data)

        // Consume: a later recreation that re-reads this intent finds nothing.
        intent.replaceExtras(Bundle())
        setIntent(intent)
    }

    private fun suppressCameraLogging() {
        try {
            System.setProperty("log.tag.camerahalserver", "ERROR")
            System.setProperty("log.tag.gralloc4", "ERROR")
            System.setProperty("log.tag.MtkCam", "ERROR")
        } catch (e: Exception) {
            Log.w("STITCH_MAIN", "Could not suppress camera logging: ${e.message}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(MainAppTab.DISCOVERY) }  // ✅ Launch to Discovery

    // ✅ NEW: Track when ThreadView is active (renders on top of tab bar)
    var isShowingThreadView by remember { mutableStateOf(false) }
    var threadViewThreadID by remember { mutableStateOf<String?>(null) }
    var threadViewTargetVideoID by remember { mutableStateOf<String?>(null) }

    // Debug logging for ThreadView state
    LaunchedEffect(isShowingThreadView) {
        Log.d("STITCH_MAIN", "🧵 isShowingThreadView changed to: $isShowingThreadView")
    }

    // ✅ NEW: Track when ProfileView is active (renders on top of tab bar)
    var isShowingProfileView by remember { mutableStateOf(false) }
    var profileViewUserID by remember { mutableStateOf<String?>(null) }

    // Community overlay state
    var isShowingCommunity by remember { mutableStateOf(false) }
    var selectedCommunityItem by remember { mutableStateOf<CommunityListItem?>(null) }
    /// Stream to open immediately once the community screen mounts (go-live tap).
    var autoOpenStreamID by remember { mutableStateOf<String?>(null) }

    // ── Notification destinations ──
    // The video a tapped notification is about, open in the fullscreen player.
    var notificationVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    // Campaign / ad / wallet screen a notification opened (they otherwise live
    // deep inside Settings, which a tap can't reach).
    var notificationScreen by remember { mutableStateOf<NotificationDestination?>(null) }
    // The campaign a NotificationDestination.Campaign screen shows, fetched by the route.
    var notificationCampaign by remember {
        mutableStateOf<com.stitchsocial.club.services.CreatorCampaign?>(null)
    }
    // Bumped by a notification tap to remount the tab content, which closes
    // everything open INSIDE a tab (fullscreen decks, sheets, platform Dialog
    // windows) — state this layer can't reach any other way. Feeds are kept in
    // retained ViewModels, so this costs what a tab switch costs.
    var interruptGeneration by remember { mutableIntStateOf(0) }
    // A route waiting on "Leave?" because the user is recording or live.
    var routeAwaitingConfirm by remember { mutableStateOf<Pair<NotificationRouter.Request, String>?>(null) }
    // Latest route wins when two overlap (one may still be fetching its video).
    var routeToken by remember { mutableIntStateOf(0) }

    fun communityListItem(community: com.stitchsocial.club.community.Community, isLive: Boolean) =
        CommunityListItem(
            id = community.id,
            creatorUsername = community.creatorUsername,
            creatorDisplayName = community.creatorDisplayName,
            creatorTier = community.creatorTier,
            profileImageURL = community.profileImageURL,
            memberCount = community.memberCount,
            userLevel = 0, userXP = 0, unreadCount = 0,
            lastActivityPreview = "",
            lastActivityAt = java.util.Date(),
            isCreatorLive = isLive,
            isVerified = false
        )

    val authService = remember { AuthService() }
    val videoService = remember { VideoServiceImpl() }
    val userService = remember { UserService(context) }
    val videoCoordinator = remember { VideoCoordinator(videoService, context) }
    val navigationCoordinator = remember { NavigationCoordinator(videoCoordinator) }
    val feedService = remember { HybridHomeFeedService(videoService, userService) }

    // Initialize ConversationLaneService singleton with videoService
    LaunchedEffect(videoService) {
        ConversationLaneService.shared.initialize(videoService)
    }

    // ✅ NEW: Announcement service reference
    val announcementService = remember { AnnouncementService.shared }

    // Initialize ShareService for video file sharing
    LaunchedEffect(Unit) {
        ShareService.initialize(context)
    }

    // Event deep-link: the Hub is presented by DiscoveryView (it owns the root
    // overlay and the shared EventsViewModel), so all this layer does is make
    // sure that tab is on screen. Serves both sources — the in-app Notifications
    // tap and the FCM intent above. DiscoveryView consumes the id as it opens.
    val pendingEventID by com.stitchsocial.club.events.EventDeepLink.pending
    LaunchedEffect(pendingEventID) {
        if (pendingEventID != null && selectedTab != MainAppTab.DISCOVERY) {
            Log.d("STITCH_MAIN", "📅 Event deep-link pending -> switching to Discovery")
            selectedTab = MainAppTab.DISCOVERY
        }
    }

    // Go-live taps: open the community AND the stream, not just the community.
    val pendingLive by com.stitchsocial.club.live.LiveDeepLink.pending
    LaunchedEffect(pendingLive) {
        com.stitchsocial.club.live.LiveDeepLink.consume()?.let { target ->
            // Same precedence rule as the notification router: a tap wins over
            // whatever is already open.
            isShowingThreadView = false
            isShowingProfileView = false
            val community = CommunityService.shared.fetchCommunity(target.communityID)
            if (community != null) {
                selectedCommunityItem = communityListItem(community, isLive = true)
                autoOpenStreamID = target.streamID
                isShowingCommunity = true
            } else {
                // Gone or unreadable — the inbox beats a tap that does nothing.
                selectedTab = MainAppTab.NOTIFICATIONS
            }
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            // Show parallel-processing briefly while we prepare the file
            // (content:// → local), then route through VIDEO_REVIEW so the
            // user can trim / caption like a recorded clip. Previously this
            // skipped review entirely and dumped users straight into
            // ThreadComposer — gallery imports got no editing affordance.
            navigationCoordinator.showModal(ModalState.PARALLEL_PROCESSING)
            scope.launch {
                try {
                    videoCoordinator.processGalleryVideo(it)
                    val videoPath = videoCoordinator.lastProcessedVideoPath.value
                    val aiResult = videoCoordinator.lastAIResult.value
                    if (videoPath != null) {
                        val modalDataMap = mutableMapOf<String, Any>("videoPath" to videoPath)
                        if (aiResult != null) modalDataMap["aiResult"] = aiResult
                        // Gallery imports default to a new-thread context.
                        modalDataMap["context"] = com.stitchsocial.club.camera.RecordingContext.NewThread
                        // Route to VIDEO_REVIEW (matches recording flow at
                        // line 838-850). The review's onContinueToThread
                        // handles PARALLEL_PROCESSING + THREAD_COMPOSER from
                        // there — same path as a freshly-recorded clip.
                        navigationCoordinator.showModal(ModalState.VIDEO_REVIEW, modalDataMap)
                    } else {
                        navigationCoordinator.dismissModal()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    navigationCoordinator.dismissModal()
                }
            }
        }
    }

    LaunchedEffect(navigationCoordinator) {
        navigationCoordinator.onGalleryPickerRequested = {
            galleryPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
    }

    val isAuthenticated by authService.isAuthenticated.collectAsState()
    val firebaseUser by authService.currentUser.collectAsState()
    val authLoading by authService.isLoading.collectAsState()
    val currentModal by navigationCoordinator.currentModal.collectAsState()
    val modalData by navigationCoordinator.modalData.collectAsState()

    // ✅ CRITICAL: Pause all videos when recording modal shows
    LaunchedEffect(currentModal) {
        if (currentModal == ModalState.RECORDING) {
            val intent = Intent("com.stitchsocial.club.PAUSE_ALL_VIDEOS")
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            Log.d("STITCH_MAIN", "🎬 RECORDING MODAL: Paused all videos")
        }
        if (currentModal == ModalState.USER_PROFILE) {
            val targetUserID = modalData["userID"] as? String
            if (targetUserID != null) {
                profileViewUserID = targetUserID
                isShowingProfileView = true
                Log.d("STITCH_MAIN", "📱 USER_PROFILE modal → opening profile for $targetUserID")
            }
            navigationCoordinator.dismissModal()
        }
    }

    var currentUser: BasicUserInfo? by remember { mutableStateOf(null) }
    var isLoadingUser by remember { mutableStateOf(true) }
    var userError by remember { mutableStateOf<String?>(null) }

    // ── Age gate (app-launch level) ──────────────────────────────────
    // Existing users who never open their profile would never hit the
    // ProfileView-level prompt — gate it here at the root so every signed-in
    // session goes through it once.  Resolved values:
    //   "checking"  — read in flight, show splash
    //   "prompt"    — birthdate missing, show BirthdayPromptView
    //   "blocked"   — under-13, show Under13BlockedView
    //   "teen"      — 13-17: the app, filtered (see services/TeenSafety.kt)
    //   "ok"        — adult, render the main app
    var ageGateState by remember { mutableStateOf("checking") }
    // Bind analytics identity to the signed-in user (cleared on sign-out) so
    // funnels can be followed across sessions.
    LaunchedEffect(currentUser?.id) {
        com.stitchsocial.club.services.AnalyticsService.setUser(currentUser?.id)
    }
    LaunchedEffect(currentUser?.id) {
        val uid = currentUser?.id ?: run { ageGateState = "checking"; return@LaunchedEffect }
        try {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("stitchfin")
            val snap = db.collection("users").document(uid).get().await()
            @Suppress("UNCHECKED_CAST")
            val privacy = snap.get("privacySettings") as? Map<String, Any>
            val ageGroupRaw = privacy?.get("ageGroup") as? String
            if (ageGroupRaw == "blocked") {
                ageGateState = "blocked"
            } else {
                // Birthdate lives in the owner-only users/{uid}/private/profile.
                val dob = com.stitchsocial.club.services.PrivateProfileService
                    .resolveOwnBirthdate(uid, privacy)
                if (dob == null && (ageGroupRaw == "adult" || ageGroupRaw == "teen")) {
                    // Already through the gate (server-derived ageGroup) but the
                    // birthdate isn't readable here — don't prompt again.
                    ageGateState = if (ageGroupRaw == "teen") "teen" else "ok"
                    TeenSafety.setLane(ageGroupRaw)
                } else if (dob == null) {
                    ageGateState = "prompt"
                } else {
                    val cal = java.util.Calendar.getInstance().apply { time = dob }
                    val now = java.util.Calendar.getInstance()
                    var age = now.get(java.util.Calendar.YEAR) - cal.get(java.util.Calendar.YEAR)
                    if (now.get(java.util.Calendar.DAY_OF_YEAR) < cal.get(java.util.Calendar.DAY_OF_YEAR)) age--
                    ageGateState = when {
                        age < 13 -> "blocked"
                        age < 18 -> "teen"
                        else     -> "ok"
                    }
                    // The one read this screen already makes is also the read
                    // every feed needs, so the lane is set from it rather than
                    // fetched again per surface.
                    TeenSafety.setLane(if (ageGateState == "teen") "teen" else "adult")
                }
            }
        } catch (e: Exception) {
            // Network / read failure — fall through to "ok" so users aren't
            // hard-blocked by a transient error.  Next launch retries.
            //
            // The CONTENT lane does not follow it there. Letting an adult into
            // the app on a failed read costs nothing if they turn out to be an
            // adult; showing unfiltered video to someone who turns out to be
            // thirteen is the mistake this exists to prevent, so TeenSafety
            // keeps its cautious default until a read succeeds.
            ageGateState = "ok"
        }
    }

    // ✅ NEW: Announcement state
    val isShowingAnnouncement by announcementService.isShowingAnnouncement.collectAsState()
    val currentAnnouncement by announcementService.currentAnnouncement.collectAsState()

    // ✅ NEW: Periodic check to auto-dismiss expired announcements
    LaunchedEffect(currentAnnouncement) {
        currentAnnouncement?.let { announcement ->
            while (isShowingAnnouncement) {
                // Check every 10 seconds if announcement has expired
                delay(10000)

                if (!announcement.isCurrentlyActive) {
                    Log.d("STITCH_MAIN", "📢 Announcement expired - auto-dismissing: ${announcement.title}")
                    announcementService.hideAnnouncement()
                    break
                }
            }
        }
    }

    // ✅ FIXED: FCM registration moved HERE - after authentication completes
    LaunchedEffect(firebaseUser, isAuthenticated) {
        scope.launch {
            try {
                isLoadingUser = true
                userError = null
                val user = firebaseUser
                if (user != null && isAuthenticated) {
                    // ✅ REGISTER FCM TOKEN AFTER AUTH
                    Log.d("STITCH_MAIN", "🔐 User authenticated: ${user.uid}")
                    Log.d("STITCH_MAIN", "📱 Registering FCM token for authenticated user...")

                    try {
                        FCMManager.initialize(context)
                        Log.d("STITCH_MAIN", "✅ FCM registration initiated")
                    } catch (e: Exception) {
                        Log.e("STITCH_MAIN", "❌ FCM registration failed: ${e.message}")
                        e.printStackTrace()
                    }

                    // Load user profile
                    var userProfile: BasicUserInfo? = null
                    var retryCount = 0
                    val maxRetries = 5
                    while (userProfile == null && retryCount < maxRetries) {
                        userProfile = userService.getUserProfile(user.uid)
                        if (userProfile == null) {
                            retryCount++
                            delay(1000L * retryCount)
                        }
                    }
                    if (userProfile != null) {
                        currentUser = userProfile
                        Log.d("STITCH_MAIN", "✅ User profile loaded: ${userProfile.username}")

                        // Load hype rating (matches iOS)
                        HypeRatingService.shared.loadRating()

                        // ✅ NEW: Check for announcements after user profile loads
                        Log.d("STITCH_MAIN", "📢 Checking for announcements...")
                        try {
                            // Calculate account age (days since account created)
                            val accountAgeDays = userProfile.createdAt?.let { createdAt ->
                                val diffMs = System.currentTimeMillis() - createdAt.time
                                (diffMs / (1000 * 60 * 60 * 24)).toInt()
                            } ?: 0

                            // ✅ MINIMAL FIX: Wait for Firestore to initialize (Android-specific)
                            delay(2000)

                            announcementService.checkForCriticalAnnouncements(
                                userId = userProfile.id,
                                userTier = userProfile.tier.name.lowercase(),  // ✅ FIXED: Convert UserTier enum to lowercase string
                                accountAge = accountAgeDays
                            )
                            Log.d("STITCH_MAIN", "✅ Announcement check completed")
                        } catch (e: Exception) {
                            Log.e("STITCH_MAIN", "❌ Announcement check failed: ${e.message}")
                        }
                    } else {
                        userError = "Could not load user profile"
                    }
                } else {
                    currentUser = null
                }
                isLoadingUser = false
            } catch (e: Exception) {
                userError = e.message
                isLoadingUser = false
                Log.e("STITCH_MAIN", "❌ User loading error: ${e.message}")
            }
        }
    }

    // ── Notification tap routing (push + inbox) ─────────────────────────────

    /**
     * A tap INTERRUPTS: everything open is closed and playback paused before the
     * destination opens, so the destination is never set underneath something
     * that hides it. Callers have already asked "Leave?" if that loses work.
     */
    fun routeNotification(request: NotificationRouter.Request) {
        val destination = NotificationDestination.resolve(request.type, request.data)
        Log.d("STITCH_MAIN", "📱 Routing notification: type=${request.type} -> $destination")
        val token = ++routeToken

        // Pause first so nothing keeps playing while the destination loads.
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(MainActivity.ACTION_PAUSE_ALL_VIDEOS))

        // Close everything. A recording/review modal leaves the way its own
        // Cancel does; a live host's screen ends the stream on dispose.
        if (currentModal != ModalState.NONE) {
            com.stitchsocial.club.community.CommunityClipRouter.cancel()
            navigationCoordinator.dismissModal()
        }
        isShowingThreadView = false
        threadViewThreadID = null
        threadViewTargetVideoID = null
        isShowingProfileView = false
        profileViewUserID = null
        isShowingCommunity = false
        selectedCommunityItem = null
        autoOpenStreamID = null
        notificationVideo = null
        notificationScreen = null
        notificationCampaign = null
        com.stitchsocial.club.landing.LandingPresenter.dismissAll()
        com.stitchsocial.club.landing.LandingPostRouter.cancel()
        interruptGeneration++

        when (destination) {
            is NotificationDestination.Event -> {
                com.stitchsocial.club.events.EventDeepLink.request(destination.eventID)
                selectedTab = MainAppTab.DISCOVERY
            }
            is NotificationDestination.Live ->
                com.stitchsocial.club.live.LiveDeepLink.request(destination.communityID, destination.streamID)
            is NotificationDestination.Video -> scope.launch {
                val video = videoService.getVideoById(destination.videoID)
                if (token != routeToken) return@launch  // a newer tap took over
                if (video == null || video.isDeleted) {
                    // A dead shared link has no inbox row to land on; stay put.
                    if (request.type != NotificationRouter.TYPE_SHARED_LINK) {
                        Log.d("STITCH_MAIN", "📱 Notification video ${destination.videoID} missing -> inbox")
                        selectedTab = MainAppTab.NOTIFICATIONS
                    } else {
                        Log.d("STITCH_MAIN", "🔗 Linked video ${destination.videoID} missing")
                    }
                } else {
                    notificationVideo = video
                }
            }
            is NotificationDestination.Profile -> {
                profileViewUserID = destination.userID
                isShowingProfileView = true
            }
            NotificationDestination.OwnProfile -> selectedTab = MainAppTab.PROGRESSION
            is NotificationDestination.Community -> scope.launch {
                val community = CommunityService.shared.fetchCommunity(destination.communityID)
                if (token != routeToken) return@launch
                if (community != null) {
                    selectedCommunityItem = communityListItem(community, isLive = false)
                    isShowingCommunity = true
                } else {
                    selectedTab = MainAppTab.NOTIFICATIONS
                }
            }
            is NotificationDestination.Campaign -> scope.launch {
                val campaign = com.stitchsocial.club.services.CreatorCampaignService.getInstance()
                    .fetchCampaign(destination.campaignID)
                if (token != routeToken) return@launch
                if (campaign != null) {
                    notificationCampaign = campaign
                    notificationScreen = destination
                } else {
                    // Gone or unreadable: the marketplace still beats nothing.
                    Log.d("STITCH_MAIN", "📱 Campaign ${destination.campaignID} unreadable -> hub")
                    notificationScreen = NotificationDestination.Campaigns
                }
            }
            NotificationDestination.Campaigns,
            NotificationDestination.AdOpportunities,
            NotificationDestination.Wallet -> notificationScreen = destination
            is NotificationDestination.Landing -> com.stitchsocial.club.landing.LandingPresenter.open(
                com.stitchsocial.club.landing.LandingPresenter.Screen.Guest(
                    destination.slug, destination.spot, destination.source, destination.autoClaim
                )
            )
            NotificationDestination.Home -> selectedTab = MainAppTab.HOME
            NotificationDestination.Inbox -> selectedTab = MainAppTab.NOTIFICATIONS
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = StitchColors.background) {
        when {
            authLoading || isLoadingUser -> LoadingScreen()
            !isAuthenticated || firebaseUser == null -> LoginView(authService = authService, onLoginSuccess = {})
            userError != null -> ErrorScreen(message = userError ?: "Unknown error", onRetry = {
                scope.launch {
                    isLoadingUser = true
                    userError = null
                    val user = firebaseUser
                    currentUser = user?.let { userService.getUserProfile(it.uid) }
                    isLoadingUser = false
                }
            })
            // Age gate — applies to every signed-in session, not just profile open.
            // Render the gate in front of the main app whenever the gate isn't "ok".
            currentUser != null && ageGateState == "checking" -> LoadingScreen()
            currentUser != null && ageGateState == "blocked" -> {
                com.stitchsocial.club.views.Under13BlockedView(onAcknowledged = {
                    // Already signed-out by the prompt; just clear local state.
                    currentUser = null
                    ageGateState = "checking"
                })
            }
            // "teen" no longer has a branch of its own: a 13-17 viewer gets
            // the app, with every feed filtered through TeenSafety and the tab
            // bar showing only the surfaces that have been made safe for them
            // (MainAppTab.teenLaneTabs). TeenLockedView was what stood in for
            // that while none of it existed.
            currentUser != null && ageGateState == "prompt" -> {
                val uid = currentUser!!.id
                com.stitchsocial.club.views.BirthdayPromptView(
                    userID = uid,
                    isBusiness = currentUser!!.isBusiness,
                    onCompleted = { outcome ->
                        ageGateState = when (outcome) {
                            is com.stitchsocial.club.views.AgeGateOutcome.Adult           -> "ok"
                            is com.stitchsocial.club.views.AgeGateOutcome.Teen            -> "teen"
                            is com.stitchsocial.club.views.AgeGateOutcome.Under13Blocked  -> "blocked"
                        }
                        // The birthdate just arrived, so this is the first
                        // moment the lane is actually known — the read at
                        // launch found nothing and left it at teen.
                        com.stitchsocial.club.services.TeenSafety.setLane(
                            if (ageGateState == "ok") "adult" else "teen"
                        )
                    }
                )
            }
            currentUser != null -> {
                // Notification taps are taken HERE, not at the top of MainScreen:
                // this branch composing IS "signed in, past the age gate, main UI
                // up". The old router waited a fixed second for auth and routed
                // into a screen that might not exist yet; a cold tap now simply
                // waits in NotificationRouter until this point is reached.
                val pendingRoute by NotificationRouter.pending
                LaunchedEffect(pendingRoute) {
                    val request = NotificationRouter.consume() ?: return@LaunchedEffect
                    // Ask before throwing away a recording, a post in progress,
                    // or ending a broadcast.
                    val loses = when {
                        com.stitchsocial.club.live.CreatorScreenPresence.isHostingAny() ->
                            "Your live stream will end."
                        currentModal in setOf(
                            ModalState.RECORDING, ModalState.REACTION, ModalState.VIDEO_REVIEW,
                            ModalState.PARALLEL_PROCESSING, ModalState.THREAD_COMPOSER
                        ) -> "Your recording will be lost."
                        // In-screen composers (community clip, episode, event,
                        // live video comment, edits) register while they hold work.
                        else -> com.stitchsocial.club.foundation.ComposerPresence.lossMessage()
                    }
                    if (loses != null) {
                        routeAwaitingConfirm = request to loses  // newest tap wins
                    } else {
                        routeNotification(request)
                    }
                }

                // Stitch Landings first-launch routing (referrer → handoff →
                // location → Home banner). Here for the same reason as the
                // router above: this branch is "signed up, past the age gate".
                com.stitchsocial.club.landing.LandingArrival(accountCreatedAt = currentUser?.createdAt)

                routeAwaitingConfirm?.let { (request, loses) ->
                    AlertDialog(
                        onDismissRequest = { routeAwaitingConfirm = null },
                        title = { Text("Leave?") },
                        text = { Text(loses) },
                        confirmButton = {
                            TextButton(onClick = {
                                routeAwaitingConfirm = null
                                routeNotification(request)
                            }) { Text("Leave", color = StitchColors.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { routeAwaitingConfirm = null }) { Text("Stay") }
                        }
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Tab content with explicit lower z-index
                    var isShowingCollectionPlayer by remember { mutableStateOf(false) }

                    // Landing surfaces (guest page, My perks, owner tab, active
                    // ticket) — see LandingPresenter.
                    val landingScreen by com.stitchsocial.club.landing.LandingPresenter.screen
                    val activePerk by com.stitchsocial.club.landing.LandingPresenter.activePerk
                    val landingBanner by com.stitchsocial.club.landing.LandingPresenter.homeBanner

                    // Streak "or die" banner — loaded once per session, shown
                    // globally (every tab) when the streak is at risk, until dismissed.
                    var streakBannerDismissed by remember { mutableStateOf(false) }
                    // Set by Home's FRIENDS / DASH toggle.
                    var isDashActive by remember { mutableStateOf(false) }
                    val streakCurrent by StreakService.shared.current.collectAsState()
                    LaunchedEffect(currentUser?.id) { StreakService.shared.load() }

                    // Hide the entire tab content (Discovery, Home, Profile tab,
                    // Notifications, etc.) when a modal is active. Compose
                    // zIndex / opaque overlays don't reliably cover ExoPlayer's
                    // SurfaceView — it draws on a separate hardware layer that
                    // can poke through any Composable. Conditional rendering
                    // unmounts the PlayerViews entirely so there's nothing to
                    // bleed through. Tab state is preserved by remember; only
                    // momentary scroll positions in feeds may reset.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(0f)  // Ensure this is below ThreadView
                    ) {
                        // Also unmounted under a notification's fullscreen video,
                        // for the same SurfaceView reason; keyed so a notification
                        // tap remounts it (see interruptGeneration).
                        if (currentModal == ModalState.NONE && notificationVideo == null) key(interruptGeneration) { TabContent(
                            selectedTab = selectedTab,
                            currentUser = currentUser!!,
                            videoService = videoService,
                            userService = userService,
                            feedService = feedService,
                            navigationCoordinator = navigationCoordinator,
                            isAnnouncementShowing = isShowingAnnouncement,
                            onShowThreadView = { threadID, targetVideoID ->
                                threadViewThreadID = threadID
                                threadViewTargetVideoID = targetVideoID
                                isShowingThreadView = true
                            },
                            onShowProfileView = { userId ->
                                profileViewUserID = userId
                                isShowingProfileView = true
                            },
                            onShowCommunity = { community ->
                                selectedCommunityItem = community
                                isShowingCommunity = true
                            },
                            onDashActiveChange = { isDashActive = it },
                            onTabBarVisibilityChange = { visible ->
                                isShowingCollectionPlayer = !visible
                            }
                        ) }

                        // Blocking overlay when ThreadView is active
                        if (isShowingThreadView) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black)
                                    .zIndex(200f)  // Above all TabContent elements including DiscoveryView (100f)
                            )
                        }
                    }

                    // Streak "or die" banner — global top overlay (renders over
                    // every tab, same pattern as the tab bar). Suppressed during
                    // modals / thread view / announcements / the collection player.
                    // (streakCurrent is read here so this recomposes once load()
                    // populates the streak — isAtRisk alone reads the raw value.)
                    // Dash carries its own streak tile, so the banner stands
                    // down there — it is what FRIENDS has instead of that tile,
                    // not as well as it.
                    if (streakCurrent >= 1 && !isDashActive && currentModal == ModalState.NONE && !isShowingThreadView &&
                        notificationVideo == null && notificationScreen == null && landingScreen == null &&
                        !isShowingAnnouncement && !isShowingCollectionPlayer && !isShowingCommunity &&
                        !streakBannerDismissed && StreakService.shared.isAtRisk) {
                        StreakBanner(
                            hoursLeft = StreakService.shared.hoursUntilBreak,
                            current = streakCurrent,
                            onKeep = {
                                streakBannerDismissed = true
                                navigationCoordinator.showModal(ModalState.RECORDING)
                            },
                            onDismiss = { streakBannerDismissed = true },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .zIndex(60f)
                        )
                    }

                    // First-launch fallback: the nearest Landing, on Home (and
                    // Discovery, which is where the app opens). Same layer and
                    // suppression rules as the streak banner.
                    landingBanner?.let { nearest ->
                        if ((selectedTab == MainAppTab.HOME || selectedTab == MainAppTab.DISCOVERY) &&
                            currentModal == ModalState.NONE && !isShowingThreadView && notificationVideo == null &&
                            notificationScreen == null && landingScreen == null && !isShowingAnnouncement &&
                            !isShowingCollectionPlayer && !isShowingCommunity
                        ) {
                            com.stitchsocial.club.landing.LandingHomeBanner(
                                landing = nearest,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .zIndex(61f)
                            )
                        }
                    }

                    // ThreadView and ModalOverlay are intentionally moved OUT
                    // of this Box (after CommunityDetailView below) so they
                    // can render above ProfileView/CommunityDetailView.
                    // Inside this Box, zIndex can't compete with siblings of
                    // this Box that live outside it.

                    // Only show tab bar when no modal is active AND no announcement showing AND no ThreadView
                    //
                    // Community, Events and Collections are full-screen surfaces —
                    // the tab bar must not be visible on ANY of them. It can't be
                    // covered by layering: the takeovers render at zIndex 200
                    // inside DiscoveryView, but this bar is a LATER sibling of
                    // DiscoveryView's parent Box at an equal zIndex(0f), and
                    // between equal-zIndex siblings the later one wins. So the
                    // boolean below is the only thing hiding it, and it has to be
                    // right on the very first frame.
                    //
                    // Sources, deliberately redundant because a single miss shows
                    // the bar on top of a takeover:
                    //  - fullScreenSurfaceUp: written synchronously by the category
                    //    tap in DiscoveryView (no effect delay, no callback chain).
                    //  - isShowingCollectionPlayer: the older callback route, kept
                    //    as a backstop for the fullscreen deck / collection player.
                    //  - isShowingCommunity: the channel interior, hosted here
                    //    rather than inside DiscoveryView.
                    val fullScreenSurfaceUp by com.stitchsocial.club.foundation
                        .FullScreenSurfaceState.isUp.collectAsState()
                    val shouldShowTabBar = currentModal == ModalState.NONE && !isShowingAnnouncement &&
                        !isShowingThreadView && !isShowingCollectionPlayer && !isShowingCommunity &&
                        !fullScreenSurfaceUp && notificationVideo == null && notificationScreen == null &&
                        landingScreen == null && activePerk == null
                    LaunchedEffect(shouldShowTabBar, fullScreenSurfaceUp, isShowingCommunity) {
                        Log.d(
                            "STITCH_MAIN",
                            "📊 Tab bar decision - shouldShow: $shouldShowTabBar | modal: $currentModal | " +
                                "fullscreenSurface: $fullScreenSurfaceUp | community: $isShowingCommunity | " +
                                "collectionPlayer: $isShowingCollectionPlayer | threadView: $isShowingThreadView"
                        )
                    }

                    if (shouldShowTabBar) {
                        Log.d("STITCH_MAIN", "🎨 RENDERING CustomDippedTabBar")
                        CustomDippedTabBar(
                            selectedTab = selectedTab,
                            onTabSelected = { tab -> selectedTab = tab },
                            onCreateTapped = { navigationCoordinator.showModal(ModalState.RECORDING) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(0f)  // Explicitly lower than ThreadView
                        )
                    } else {
                        Log.d("STITCH_MAIN", "🚫 NOT RENDERING CustomDippedTabBar - shouldShowTabBar is FALSE")
                    }

                    // ModalOverlay rendered below at the same level as
                    // ProfileView/CommunityDetailView (after this Box closes)
                    // so its zIndex(500f) actually competes with theirs.

                    // ✅ NEW: Announcement overlay (shows on top of everything)
                    if (isShowingAnnouncement && currentAnnouncement != null) {
                        // ✅ BLOCKING MODAL BOX - Prevents swipes and interactions with content underneath
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)  // Solid background
                                .pointerInput(Unit) {
                                    // Consume ALL gestures - prevents swipes from reaching HomeFeed
                                    detectDragGestures { _, _ ->
                                        // Do nothing - just consume the gesture
                                    }
                                }
                        ) {
                            val announcement = currentAnnouncement!!
                            val userId = currentUser!!.id

                            // State to hold video URL
                            var announcementVideoUrl by remember { mutableStateOf<String?>(null) }

                            // Fetch video URL when announcement changes
                            LaunchedEffect(announcement.id) {  // ✅ FIXED: Use announcement.id as key
                                try {
                                    Log.d("STITCH_MAIN", "📢 Announcement ID: ${announcement.id}")
                                    Log.d("STITCH_MAIN", "📢 Announcement videoId: ${announcement.videoId}")
                                    Log.d("STITCH_MAIN", "📢 Announcement title: ${announcement.title}")

                                    // Fetch video metadata from Firestore (stitchfin database)
                                    val firebaseApp = com.google.firebase.FirebaseApp.getInstance()
                                    val db = com.google.firebase.firestore.FirebaseFirestore.getInstance(firebaseApp, "stitchfin")
                                    val videoDoc = db.collection("videos")
                                        .document(announcement.videoId)  // ✅ videoId is already a String
                                        .get()
                                        .await()

                                    if (videoDoc.exists()) {
                                        announcementVideoUrl = videoDoc.getString("videoURL")
                                        Log.d("STITCH_MAIN", "📢 Loaded announcement video URL from stitchfin: $announcementVideoUrl")
                                        Log.d("STITCH_MAIN", "📢 Video title from Firestore: ${videoDoc.getString("title")}")
                                    } else {
                                        Log.e("STITCH_MAIN", "📢 Video document does not exist for ID: ${announcement.videoId}")
                                        announcementVideoUrl = null
                                    }
                                } catch (e: Exception) {
                                    Log.e("STITCH_MAIN", "📢 Failed to load announcement video: ${e.message}")
                                    e.printStackTrace()
                                    announcementVideoUrl = null
                                }
                            }

                            AnnouncementOverlayView(
                                announcement = announcement,
                                videoUrl = announcementVideoUrl,
                                creatorName = "Stitch Official",
                                creatorProfileImageUrl = null,
                                onComplete = { watchedSeconds ->
                                    scope.launch {
                                        try {
                                            announcementService.markAsCompleted(userId, announcement.id, watchedSeconds)
                                            Log.d("STITCH_MAIN", "📢 Completed announcement '${announcement.title}' after ${watchedSeconds}s")
                                            announcementService.hideAnnouncement()  // ✅ HIDE THE OVERLAY
                                        } catch (e: Exception) {
                                            Log.e("STITCH_MAIN", "📢 Error completing announcement: ${e.message}")
                                            announcementService.hideAnnouncement()
                                        }
                                    }
                                },
                                onDismiss = {
                                    scope.launch {
                                        try {
                                            announcementService.dismissAnnouncement(userId, announcement.id)
                                            announcementService.hideAnnouncement()  // ✅ HIDE THE OVERLAY
                                        } catch (e: Exception) {
                                            Log.e("STITCH_MAIN", "📢 Error dismissing announcement: ${e.message}")
                                            announcementService.hideAnnouncement()
                                        }
                                    }
                                },
                                onCreatorTap = { creatorId ->
                                    // Navigate to creator profile
                                    Log.d("STITCH_MAIN", "📢 Creator profile tapped: $creatorId")
                                    navigationCoordinator.showModal(
                                        modal = ModalState.USER_PROFILE,
                                        data = mapOf("userID" to creatorId)
                                    )
                                }
                            )
                        }
                    }
                }

                // ✅ NEW: ProfileView Overlay (renders on top of tab bar with zIndex)
                if (isShowingProfileView) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(50f)  // Above tab bar
                            .background(Color.Black)
                    ) {
                        ProfileView(
                            userID = profileViewUserID ?: "",
                            viewingUserID = currentUser?.id,
                            navigationCoordinator = navigationCoordinator,
                            // Wire the thread-nav callback so taps on the
                            // contextual overlay's "Thread" button (and the
                            // profile-launched fullscreen player's overlay)
                            // route into the ThreadView overlay. Without this,
                            // ProfileView gets the default no-op and the
                            // button silently dismisses the player.
                            onShowThreadView = { threadID, targetVideoID ->
                                threadViewThreadID = threadID
                                threadViewTargetVideoID = targetVideoID
                                isShowingThreadView = true
                            },
                            onDismiss = {
                                isShowingProfileView = false
                                profileViewUserID = null
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // ✅ NEW: CommunityDetailView Overlay
                val community = selectedCommunityItem
                if (isShowingCommunity && community != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(50f)
                            .background(Color.Black)
                    ) {
                        // V2 community detail — TabView with Home + Threads,
                        // sticky header, contextual FAB. Mirrors iOS
                        // CommunityDetailV2View. V1 is still in the codebase
                        // as a fallback if needed.
                        com.stitchsocial.club.views.CommunityDetailV2View(
                            userID = currentUser?.id ?: "",
                            communityID = community.id,
                            communityItem = community,
                            // Set only by a go-live tap; consumed once on mount.
                            autoOpenStreamID = autoOpenStreamID,
                            onAutoOpenConsumed = { autoOpenStreamID = null },
                            onDismiss = {
                                isShowingCommunity = false
                                selectedCommunityItem = null
                            },
                            // Community record + stitch open the SAME recorder as
                            // the main + button (iOS parity, e6f51f6). The router
                            // makes the finished edit come back here instead of
                            // becoming a global thread.
                            onRecordClip = { communityID, postID ->
                                com.stitchsocial.club.community.CommunityClipRouter
                                    .request(communityID, postID)
                                navigationCoordinator.showModal(
                                    ModalState.RECORDING,
                                    mapOf("context" to RecordingContext.NewThread)
                                )
                            }
                        )
                    }
                }

                // ── Top-level overlays (siblings of ProfileView / CommunityDetail) ──
                //
                // These MUST live outside the main Box (which ends earlier)
                // so they can compete on z-index with ProfileView (zIndex 50)
                // and CommunityDetail (zIndex 50). Previously they lived
                // inside the main Box and zIndex couldn't reach across the
                // parent boundary, so tapping Stitch/Thread from a profile-
                // launched player rendered the modal underneath the profile.

                // Campaign / ad / wallet screen opened by a notification. Same
                // layer as ProfileView; the screens themselves take (user, onDismiss).
                notificationScreen?.let { screen ->
                    val user = currentUser!!
                    val close = { notificationScreen = null; notificationCampaign = null }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(50f)
                            .background(Color.Black)
                    ) {
                        androidx.activity.compose.BackHandler(onBack = close)
                        when (screen) {
                            NotificationDestination.Campaigns -> CreatorCampaignsHubView(
                                currentUserID = user.id,
                                isBrandAccount = user.isBusiness,
                                brandName = user.displayName,
                                brandLogoURL = user.profileImageURL,
                                onDismiss = close
                            )
                            // Same role rule as the hub: a business account is the brand.
                            is NotificationDestination.Campaign -> notificationCampaign?.let { campaign ->
                                CreatorCampaignDetailView(
                                    campaign = campaign,
                                    currentUserID = user.id,
                                    isBrandAccount = user.isBusiness,
                                    onDismiss = close
                                )
                            }
                            NotificationDestination.AdOpportunities ->
                                AdOpportunitiesView(user = user, onDismiss = close)
                            NotificationDestination.Wallet ->
                                WalletView(userID = user.id, userTier = user.tier, onDismiss = close)
                            else -> {}
                        }
                    }
                }

                // Landing screens — same layer as the notification screens. The
                // recorder (500) opens over a guest Landing and closes back
                // onto it, which is where "Unlocking…" is waiting.
                com.stitchsocial.club.landing.LandingPresenter.screen.value?.let { screen ->
                    val closeLanding = { com.stitchsocial.club.landing.LandingPresenter.close() }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(50f)
                            .background(Color.Black)
                    ) {
                        when (screen) {
                            is com.stitchsocial.club.landing.LandingPresenter.Screen.Guest ->
                                com.stitchsocial.club.landing.LandingScreen(
                                    slug = screen.slug,
                                    spot = screen.spot,
                                    source = screen.source,
                                    autoClaim = screen.autoClaim,
                                    onDismiss = closeLanding,
                                    onRecord = {
                                        navigationCoordinator.showModal(
                                            ModalState.RECORDING,
                                            mapOf("context" to RecordingContext.NewThread)
                                        )
                                    },
                                    onOpenVideo = { video -> notificationVideo = video }
                                )
                            com.stitchsocial.club.landing.LandingPresenter.Screen.MyPerks ->
                                com.stitchsocial.club.landing.MyPerksScreen(onDismiss = closeLanding)
                            com.stitchsocial.club.landing.LandingPresenter.Screen.Owner ->
                                com.stitchsocial.club.landing.LandingOwnerScreen(onDismiss = closeLanding)
                        }
                    }
                }

                // ModalOverlay — recording, video review, thread composer, etc.
                Box(modifier = Modifier.fillMaxSize().zIndex(500f)) {
                    ModalOverlay(currentModal, modalData, navigationCoordinator, videoCoordinator) { tab -> selectedTab = tab }
                }

                // The video a tapped notification is about. Above the recorder's
                // layer (its Stitch button dismisses this first) and below
                // ThreadView. Closing it puts the user back on their tab.
                notificationVideo?.let { video ->
                    Box(modifier = Modifier.fillMaxSize().zIndex(600f)) {
                        FullscreenVideoPlayer(
                            video = video,
                            currentUserID = currentUser?.id,
                            currentUserTier = currentUser?.tier ?: UserTier.ROOKIE,
                            navigationCoordinator = navigationCoordinator,
                            onShowThreadView = { threadID, targetVideoID ->
                                threadViewThreadID = threadID
                                threadViewTargetVideoID = targetVideoID
                                isShowingThreadView = true
                            },
                            onShowProfile = { userID ->
                                profileViewUserID = userID
                                isShowingProfileView = true
                            },
                            onDismiss = { notificationVideo = null }
                        )
                    }
                }

                // The active perk ticket — above everything the guest might
                // have open, because it's what they're holding up at a register.
                com.stitchsocial.club.landing.LandingPresenter.activePerk.value?.let { perk ->
                    Box(modifier = Modifier.fillMaxSize().zIndex(700f)) {
                        com.stitchsocial.club.landing.ActivePerkScreen(
                            perk = perk,
                            username = currentUser?.username ?: "",
                            onDismiss = { com.stitchsocial.club.landing.LandingPresenter.activePerk.value = null }
                        )
                    }
                }

                // ThreadView — full-screen conversation view, takes precedence
                // over modals when both are active.
                if (isShowingThreadView) {
                    Log.d("STITCH_MAIN", "🧵 RENDERING ThreadView")
                    val user = currentUser
                    if (user != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(1000f)
                                .background(Color.Black)
                                .pointerInput(Unit) {
                                    detectTapGestures { /* consume */ }
                                }
                        ) {
                            ThreadView(
                                threadID = threadViewThreadID ?: "",
                                videoService = videoService,
                                targetVideoID = threadViewTargetVideoID,
                                currentUserID = user.id,
                                currentUserTier = user.tier,
                                navigationCoordinator = navigationCoordinator,
                                onDismiss = {
                                    isShowingThreadView = false
                                    threadViewThreadID = null
                                    threadViewTargetVideoID = null
                                },
                                onVideoTap = { video ->
                                    // Video tap handled within ThreadView
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModalOverlay(
    currentModal: ModalState,
    modalData: Map<String, Any>,
    navigationCoordinator: NavigationCoordinator,
    videoCoordinator: VideoCoordinator,
    onTabChange: (MainAppTab) -> Unit
) {
    if (currentModal != ModalState.NONE) {
        val context = LocalContext.current
        val draftManager = remember { LocalDraftManager.getInstance(context) }
        val drafts by draftManager.drafts.collectAsState()
        // Keep the drafts list fresh (drops any whose bytes went missing) while
        // any recording-flow modal is up, so the camera badge count is accurate.
        LaunchedEffect(currentModal) { draftManager.loadDrafts() }

        // FULLY opaque black background. The shared StitchColors.modalOverlay
        // is 90% black which let DiscoveryView's video player bleed through
        // the modal. Recording/review modals need to fully cover the feed.
        Box(modifier = Modifier.fillMaxSize().zIndex(100f).background(Color.Black)) {
            when (currentModal) {
                ModalState.RECORDING -> {
                    val recordingContext = (modalData["context"] as? RecordingContext) ?: RecordingContext.NewThread
                    val parentVideo = modalData["parentVideo"] as? CoreVideoMetadata
                    if (BuildConfig.DEBUG) { println("🎬 MAINACT: RECORDING Modal active - context: $recordingContext") }
                    CameraView(
                        recordingContext = recordingContext,
                        parentVideo = parentVideo,
                        onVideoRecorded = { recordedVideo ->
                            try {
                                com.stitchsocial.club.services.AnalyticsService
                                    .videoRecorded(recordingContext.getBadgeText())
                                if (BuildConfig.DEBUG) { println("📹 MAINACT: Video recorded, going to VIDEO_REVIEW") }
                                if (BuildConfig.DEBUG) { println("📹 MAINACT: Video path: ${recordedVideo.videoURL}") }

                                // Go to VIDEO_REVIEW for editing before processing
                                navigationCoordinator.showModal(
                                    ModalState.VIDEO_REVIEW,
                                    mapOf(
                                        "videoPath" to recordedVideo.videoURL,
                                        "context" to recordingContext,
                                        "metadata" to recordedVideo
                                    )
                                )
                                if (BuildConfig.DEBUG) { println("📹 MAINACT: VIDEO_REVIEW modal shown") }
                            } catch (e: Exception) {
                                if (BuildConfig.DEBUG) { println("❌ MAINACT: EXCEPTION in onVideoRecorded: ${e.message}") }
                                e.printStackTrace()
                            }
                        },
                        onCancel = {
                            if (BuildConfig.DEBUG) { println("❌ MAINACT: Camera CANCELLED - dismissing modal") }
                            com.stitchsocial.club.community.CommunityClipRouter.cancel()
                            com.stitchsocial.club.landing.LandingPostRouter.cancel()
                            navigationCoordinator.dismissModal()
                        },
                        onStopAllVideos = {},
                        onDisposeAllVideos = {},
                        onGalleryRequested = { navigationCoordinator.requestGalleryPicker() },
                        draftCount = drafts.size,
                        onDraftsRequested = { navigationCoordinator.showModal(ModalState.DRAFTS) },
                        onReactionRequested = {
                            // Switch from RECORDING to REACTION. If we entered
                            // RECORDING from a stitch (parentVideo set), auto-
                            // fill the source zone with that video's URL so
                            // the user can react immediately. Otherwise they
                            // pick a source manually inside ReactionCameraView.
                            val parentUrl = parentVideo?.videoURL
                            val data = if (!parentUrl.isNullOrBlank()) {
                                mapOf(
                                    "sourceUri" to parentUrl,
                                    "context" to recordingContext
                                )
                            } else {
                                mapOf("context" to recordingContext)
                            }
                            navigationCoordinator.showModal(ModalState.REACTION, data)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Saved-drafts grid (reachable from the camera Drafts badge)
                ModalState.DRAFTS -> {
                    DraftsScreen(
                        onSelectDraft = { draft ->
                            // Mark it the active session and reopen in review.
                            draftManager.markActiveDraft(draft.draftId)
                            navigationCoordinator.showModal(
                                ModalState.VIDEO_REVIEW,
                                mapOf(
                                    "videoPath" to draft.videoUri.toString(),
                                    "draftId" to draft.draftId
                                )
                            )
                        },
                        onBack = { navigationCoordinator.showModal(ModalState.RECORDING) },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Video Review/Edit screen (trim, filters, captions)
                ModalState.VIDEO_REVIEW -> {
                    val videoPath = modalData["videoPath"] as? String
                    val recordingContext = (modalData["context"] as? RecordingContext) ?: RecordingContext.NewThread
                    val metadata = modalData["metadata"] as? CoreVideoMetadata

                    if (BuildConfig.DEBUG) { println("✂️ MAINACT: VIDEO_REVIEW Modal active") }
                    if (BuildConfig.DEBUG) { println("✂️ MAINACT: Video path: $videoPath") }

                    if (videoPath != null) {
                        // Properly parse video path to URI
                        val videoUri = if (videoPath.startsWith("content://") || videoPath.startsWith("file://")) {
                            Uri.parse(videoPath)
                        } else {
                            // It's a file path without scheme - add file:// prefix
                            Uri.fromFile(java.io.File(videoPath))
                        }

                        if (BuildConfig.DEBUG) { println("✂️ MAINACT: Parsed video URI: $videoUri") }

                        // Resuming a saved draft? Load its full state (same
                        // draftId, persisted bytes) so exiting/posting acts on
                        // the same draft. Otherwise start a fresh edit session.
                        val resumeDraftId = modalData["draftId"] as? String
                        val reviewInitialState = resumeDraftId
                            ?.let { draftManager.getDraft(it) }
                            ?: VideoEditState.create(videoUri = videoUri, duration = 0.0)

                        VideoReviewView(
                            initialState = reviewInitialState,
                            onContinueToThread = { editedState ->
                                if (BuildConfig.DEBUG) { println("✅ MAINACT: Video editing complete, starting processing") }
                                if (BuildConfig.DEBUG) { println("✅ MAINACT: hasEdits=${editedState.hasEdits}, hasTrimEdits=${editedState.hasTrimEdits}") }
                                if (BuildConfig.DEBUG) { println("✅ MAINACT: processedVideoUri=${editedState.processedVideoUri}") }

                                // Use processed video if available, otherwise original
                                // Convert URI to file path (VideoCoordinator expects raw path, not URI)
                                val finalVideoPath = when {
                                    editedState.processedVideoUri != null -> {
                                        // Processed video - get path from URI
                                        editedState.processedVideoUri!!.path ?: videoPath
                                    }
                                    videoPath.startsWith("file://") -> {
                                        // Strip file:// prefix
                                        videoPath.removePrefix("file://")
                                    }
                                    videoPath.startsWith("/") -> {
                                        // Already a raw path
                                        videoPath
                                    }
                                    else -> {
                                        // Try parsing as URI
                                        Uri.parse(videoPath).path ?: videoPath
                                    }
                                }
                                if (BuildConfig.DEBUG) { println("✅ MAINACT: Using finalVideoPath=$finalVideoPath") }

                                // Community capture (iOS parity, e6f51f6): a community
                                // surface armed the router before opening the recorder,
                                // so hand the finished edit straight back to it. No
                                // parallel processing, no global video doc, no
                                // ThreadComposer — the clip becomes a community-only
                                // post/reply instead.
                                if (com.stitchsocial.club.community.CommunityClipRouter.isArmed) {
                                    if (BuildConfig.DEBUG) { println("🏘️ MAINACT: routing finished edit to community") }
                                    com.stitchsocial.club.community.CommunityClipRouter.deliver(finalVideoPath)
                                    navigationCoordinator.dismissModal()
                                    return@VideoReviewView
                                }

                                navigationCoordinator.showModal(ModalState.PARALLEL_PROCESSING)

                                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        if (BuildConfig.DEBUG) { println("🔄 MAINACT: Starting parallel processing after edit...") }
                                        if (BuildConfig.DEBUG) { println("🔄 MAINACT: finalVideoPath = $finalVideoPath") }
                                        if (BuildConfig.DEBUG) { println("🔄 MAINACT: File exists = ${java.io.File(finalVideoPath).exists()}") }

                                        val finalMetadata = metadata ?: CoreVideoMetadata(
                                            id = "temp_${System.currentTimeMillis()}",
                                            title = "",
                                            description = "",
                                            videoURL = finalVideoPath,
                                            thumbnailURL = "",
                                            creatorID = "",
                                            creatorName = "",
                                            hashtags = emptyList(),
                                            createdAt = java.util.Date(),
                                            threadID = null,
                                            replyToVideoID = null,
                                            conversationDepth = 0,
                                            viewCount = 0,
                                            hypeCount = 0,
                                            coolCount = 0,
                                            replyCount = 0,
                                            shareCount = 0,
                                            lastEngagementAt = null,
                                            duration = editedState.trimmedDuration,
                                            // From the edited file, rotation applied;
                                            // VideoCoordinator re-probes before writing.
                                            aspectRatio = com.stitchsocial.club.services.VideoAspectProbe
                                                .aspectRatio(finalVideoPath)
                                                ?: com.stitchsocial.club.foundation.MediaFit.DEFAULT_ASPECT,
                                            fileSize = 0L,
                                            contentType = ContentType.THREAD,
                                            temperature = Temperature.WARM,
                                            qualityScore = 50,
                                            engagementRatio = 0.0,
                                            velocityScore = 0.0,
                                            trendingScore = 0.0,
                                            discoverabilityScore = 0.5,
                                            isPromoted = false,
                                            isProcessing = false,
                                            isDeleted = false
                                        )

                                        videoCoordinator.startParallelProcessing(
                                            finalVideoPath,
                                            finalMetadata,
                                            recordingContext
                                        )
                                        if (BuildConfig.DEBUG) { println("✅ MAINACT: Parallel processing completed!") }
                                        // ParallelProcessingView will auto-transition when complete

                                    } catch (e: Exception) {
                                        if (BuildConfig.DEBUG) { println("❌ MAINACT: Processing EXCEPTION: ${e.message}") }
                                        e.printStackTrace()
                                    }
                                }
                            },
                            onCancel = {
                                if (BuildConfig.DEBUG) { println("❌ MAINACT: Video editing CANCELLED") }
                                com.stitchsocial.club.community.CommunityClipRouter.cancel()
                                com.stitchsocial.club.landing.LandingPostRouter.cancel()
                                navigationCoordinator.dismissModal()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        if (BuildConfig.DEBUG) { println("❌ MAINACT: No video path for VIDEO_REVIEW") }
                        navigationCoordinator.dismissModal()
                    }
                }

                ModalState.REACTION -> {
                    // Split-canvas reaction recorder. The composited MP4 is
                    // routed through VIDEO_REVIEW so the user can trim/caption
                    // before posting — same path the normal recording flow uses.
                    val initialSourceUriString = modalData["sourceUri"] as? String
                    val initialSourceUri = initialSourceUriString?.let { Uri.parse(it) }
                    val reactionContext = (modalData["context"] as? RecordingContext) ?: RecordingContext.NewThread
                    com.stitchsocial.club.views.ReactionCameraView(
                        onCancel = {
                            if (BuildConfig.DEBUG) { println("🎬 REACTION: cancelled") }
                            navigationCoordinator.dismissModal()
                        },
                        onComplete = { resultUri ->
                            if (BuildConfig.DEBUG) { println("🎬 REACTION: completed → $resultUri, routing to VIDEO_REVIEW") }
                            navigationCoordinator.showModal(
                                ModalState.VIDEO_REVIEW,
                                mapOf(
                                    "videoPath" to resultUri.toString(),
                                    "context" to reactionContext
                                )
                            )
                        },
                        initialSourceUri = initialSourceUri,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                ModalState.PARALLEL_PROCESSING -> ParallelProcessingView(
                    navigationCoordinator = navigationCoordinator,
                    modifier = Modifier.fillMaxSize()
                )

                ModalState.THREAD_COMPOSER -> {
                    val videoPath = modalData["videoPath"] as? String
                    val aiResult = videoCoordinator.lastAIResult.value
                    val recordingContext = videoCoordinator.lastRecordingContext.value ?: RecordingContext.NewThread
                    if (videoPath != null) {
                        ThreadComposer(
                            recordedVideoURL = videoPath,
                            recordingContext = recordingContext,
                            aiResult = aiResult,
                            videoCoordinator = videoCoordinator,
                            onVideoCreated = {
                                // Posted successfully — drop the resumed draft so
                                // it can't be double-posted.
                                draftManager.deleteActiveDraftAfterPost()
                                navigationCoordinator.dismissModal(); onTabChange(MainAppTab.HOME)
                            },
                            onCancel = {
                                com.stitchsocial.club.landing.LandingPostRouter.cancel()
                                navigationCoordinator.dismissModal()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        navigationCoordinator.dismissModal()
                    }
                }

                else -> {}
            }
        }
    }
}

@Composable
private fun TabContent(
    selectedTab: MainAppTab,
    currentUser: BasicUserInfo,
    videoService: VideoServiceImpl,
    userService: UserService,
    feedService: HybridHomeFeedService,
    navigationCoordinator: NavigationCoordinator,
    isAnnouncementShowing: Boolean = false,
    onShowThreadView: (threadID: String, targetVideoID: String?) -> Unit,
    onShowProfileView: (userId: String) -> Unit,
    onShowCommunity: (CommunityListItem) -> Unit = {},
    onTabBarVisibilityChange: (Boolean) -> Unit = {},
    /** Home reports which of its two surfaces is up. */
    onDashActiveChange: (Boolean) -> Unit = {}
) {
    when (selectedTab) {
        MainAppTab.HOME -> {
            HomeFeedView(
                userID = currentUser.id,
                navigationCoordinator = navigationCoordinator,
                isAnnouncementShowing = isAnnouncementShowing,
                onShowThreadView = onShowThreadView,
                onDashActiveChange = onDashActiveChange,
                modifier = Modifier.fillMaxSize()
            )
        }
        MainAppTab.DISCOVERY -> {
            DiscoveryView(
                onNavigateToVideo = { video ->
                    Log.d("NAVIGATION", "Discovery video selected: ${video.title}")
                },
                onNavigateToProfile = { userId ->
                    Log.d("NAVIGATION", "Navigate to profile: $userId")
                    onShowProfileView(userId)
                },
                onNavigateToSearch = {
                    Log.d("NAVIGATION", "🔍 SEARCH NAVIGATION TRIGGERED")
                },
                onShowThreadView = onShowThreadView,
                onShowCommunity = { community ->
                    onShowCommunity(community)
                },
                onTabBarVisibilityChange = onTabBarVisibilityChange,
                isAnnouncementShowing = isAnnouncementShowing,
                navigationCoordinator = navigationCoordinator,
                modifier = Modifier.fillMaxSize()
            )
        }
        MainAppTab.PROGRESSION -> {
            ProfileView(
                userID = currentUser.id,
                navigationCoordinator = navigationCoordinator,
                onShowThreadView = onShowThreadView,
                modifier = Modifier.fillMaxSize()
            )
        }
        MainAppTab.NOTIFICATIONS -> {
            NotificationViewComplete(
                navigationCoordinator = navigationCoordinator,
                onShowThreadView = onShowThreadView,
                onNavigateToProfile = onShowProfileView,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = StitchColors.primary)
            Text("Loading...", color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text("Error", color = StitchColors.error, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(message, color = Color.White, fontSize = 16.sp)
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = StitchColors.primary)) {
                Text("Retry")
            }
        }
    }
}