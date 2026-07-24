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
import com.stitchsocial.club.BuildConfig


class MainActivity : ComponentActivity() {

    companion object {
        // ✅ FIXED: Store pending notification intent for Compose processing
        var pendingNotificationIntent: Intent? = null

        // ✅ FIX: Incremented on every onNewIntent so the routing LaunchedEffect
        // re-runs when the app is already open and a notification is tapped.
        var notificationTrigger = androidx.compose.runtime.mutableIntStateOf(0)

        // ✅ NEW: Broadcast action for pausing videos
        const val ACTION_PAUSE_ALL_VIDEOS = "com.stitchsocial.club.PAUSE_ALL_VIDEOS"

        // ✅ NEW: Broadcast action for resuming videos after camera/modal closes
        const val ACTION_RESUME_ALL_VIDEOS = "com.stitchsocial.club.RESUME_ALL_VIDEOS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        suppressCameraLogging()

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

        // Bootstrap LinkedAccountManager so AuthService can use the
        // static `shared` accessor when seeding linked accounts on signIn.
        com.stitchsocial.club.services.LinkedAccountManager.getInstance(this)

        // ✅ NEW: Request notification permission (Android 13+)
        requestNotificationPermission()

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

        // ✅ Handle notification intent if app opened from notification
        handleNotificationIntent(intent)
    }

    // ✅ Handle new intents when app is already running
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
        // ✅ FIX: Signal Compose to re-run the routing LaunchedEffect
        notificationTrigger.intValue++
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
    private fun handleNotificationIntent(intent: Intent?) {
        intent?.let {
            val fromNotification = it.getBooleanExtra("fromNotification", false)

            if (fromNotification) {
                Log.d("STITCH_MAIN", "📱 Notification intent detected - storing for processing")
                pendingNotificationIntent = it

                // Log notification details for debugging
                val notificationType = it.getStringExtra("notification_type")
                val videoId = it.getStringExtra("video_id")
                val userId = it.getStringExtra("user_id")
                Log.d("STITCH_MAIN", "📱 Type: $notificationType, VideoID: $videoId, UserID: $userId")
            } else {
                Log.d("STITCH_MAIN", "📱 Normal app launch - no notification")
            }
        }
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

    // ✅ FIXED: Process pending notification intent
    // Keys on notificationTrigger so foreground taps (onNewIntent) re-run this block.
    val notificationTrigger by MainActivity.notificationTrigger
    LaunchedEffect(navigationCoordinator, notificationTrigger) {
        MainActivity.pendingNotificationIntent?.let { intent ->
            Log.d("STITCH_MAIN", "📱 Processing pending notification intent")

            val notificationType = intent.getStringExtra("notification_type")
                ?: intent.getStringExtra("type")
            val videoId = intent.getStringExtra("video_id")
                ?: intent.getStringExtra("videoID")
                ?: intent.getStringExtra("videoId")
            val threadId = intent.getStringExtra("thread_id")
                ?: intent.getStringExtra("threadID")
                ?: intent.getStringExtra("threadId")
            val userId = intent.getStringExtra("user_id")
                ?: intent.getStringExtra("userID")
                ?: intent.getStringExtra("senderID")
            val eventId = intent.getStringExtra("event_id")
                ?: intent.getStringExtra("eventID")
                ?: intent.getStringExtra("eventId")

            Log.d("STITCH_MAIN", "📱 Notification intent: type=$notificationType, video=$videoId, thread=$threadId, user=$userId, event=$eventId")

            // Wait for authentication to complete
            delay(1000)

            // Event invite — routed on the PAYLOAD, not the type, and ahead of
            // the when below. Invites are written as SYSTEM notifications, so the
            // push arrives with type="system" and would fall straight through the
            // type table; the eventID extra is the dependable signal. (FCMService
            // copies every data key to an extra verbatim, so it survives the tap.)
            if (eventId != null) {
                Log.d("STITCH_MAIN", "📱 Deep-linking to event hub: $eventId")
                com.stitchsocial.club.events.EventDeepLink.request(eventId)
            }

            when (notificationType) {
                // Video-related notifications → ThreadView (matches iOS AppDelegate routing)
                "hype", "reply", "cool", "engagement",
                "stitch", "thread", "newVideo", "mention",
                "video", "reengagement_stitches", "reengagement_milestone",
                "tip", "question" -> {
                    // Cloud Functions include threadID in payload — use it for deep linking
                    val resolvedThreadID = threadId ?: videoId
                    if (resolvedThreadID != null) {
                        Log.d("STITCH_MAIN", "📱 Navigating to ThreadView: thread=$resolvedThreadID, target=$videoId")
                        threadViewThreadID = resolvedThreadID
                        threadViewTargetVideoID = videoId
                        isShowingThreadView = true
                    }
                }
                // Follow / new subscriber → profile of the sender
                "follow", "user", "reengagement_followers", "subscription" -> {
                    userId?.let { id ->
                        Log.d("STITCH_MAIN", "📱 Navigating to profile: $id")
                        profileViewUserID = id
                        isShowingProfileView = true
                    }
                }
                // Milestone → notifications tab
                "milestone" -> {
                    // Milestones with videoID → ThreadView, otherwise → tab
                    val resolvedThreadID = threadId ?: videoId
                    if (resolvedThreadID != null) {
                        threadViewThreadID = resolvedThreadID
                        threadViewTargetVideoID = videoId
                        isShowingThreadView = true
                    } else {
                        Log.d("STITCH_MAIN", "📱 Switching to notifications tab")
                        selectedTab = MainAppTab.NOTIFICATIONS
                    }
                }
            }

            // Clear pending intent
            MainActivity.pendingNotificationIntent = null
            Log.d("STITCH_MAIN", "✅ Notification intent processed and cleared")
        }
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
    //   "teen"      — show TeenLockedView until the teen lane is built
    //   "ok"        — adult, render the main app
    var ageGateState by remember { mutableStateOf("checking") }
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
                val dob = (privacy?.get("birthdate") as? com.google.firebase.Timestamp)?.toDate()
                if (dob == null) {
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
                }
            }
        } catch (e: Exception) {
            // Network / read failure — fall through to "ok" so users aren't
            // hard-blocked by a transient error.  Next launch retries.
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
            currentUser != null && ageGateState == "teen" -> {
                com.stitchsocial.club.views.TeenLockedView(
                    displayName = currentUser?.displayName ?: "there",
                    onSignedOut = {
                        currentUser = null
                        ageGateState = "checking"
                    }
                )
            }
            currentUser != null && ageGateState == "prompt" -> {
                val uid = currentUser!!.id
                com.stitchsocial.club.views.BirthdayPromptView(
                    userID = uid,
                    onCompleted = { outcome ->
                        ageGateState = when (outcome) {
                            is com.stitchsocial.club.views.AgeGateOutcome.Adult           -> "ok"
                            is com.stitchsocial.club.views.AgeGateOutcome.Teen            -> "teen"
                            is com.stitchsocial.club.views.AgeGateOutcome.Under13Blocked  -> "blocked"
                        }
                    }
                )
            }
            currentUser != null -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Tab content with explicit lower z-index
                    var isShowingCollectionPlayer by remember { mutableStateOf(false) }

                    // Streak "or die" banner — loaded once per session, shown
                    // globally (every tab) when the streak is at risk, until dismissed.
                    var streakBannerDismissed by remember { mutableStateOf(false) }
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
                        if (currentModal == ModalState.NONE) TabContent(
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
                            onTabBarVisibilityChange = { visible ->
                                isShowingCollectionPlayer = !visible
                            }
                        )

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
                    if (streakCurrent >= 1 && currentModal == ModalState.NONE && !isShowingThreadView &&
                        !isShowingAnnouncement && !isShowingCollectionPlayer &&
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

                    // ThreadView and ModalOverlay are intentionally moved OUT
                    // of this Box (after CommunityDetailView below) so they
                    // can render above ProfileView/CommunityDetailView.
                    // Inside this Box, zIndex can't compete with siblings of
                    // this Box that live outside it.

                    // Only show tab bar when no modal is active AND no announcement showing AND no ThreadView
                    val shouldShowTabBar = currentModal == ModalState.NONE && !isShowingAnnouncement && !isShowingThreadView && !isShowingCollectionPlayer
                    LaunchedEffect(currentModal, isShowingAnnouncement, isShowingThreadView) {
                        Log.d("STITCH_MAIN", "📊 Tab bar decision - shouldShow: $shouldShowTabBar | modal: $currentModal | announcement: $isShowingAnnouncement | threadView: $isShowingThreadView")
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
                            onDismiss = {
                                isShowingCommunity = false
                                selectedCommunityItem = null
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

                // ModalOverlay — recording, video review, thread composer, etc.
                Box(modifier = Modifier.fillMaxSize().zIndex(500f)) {
                    ModalOverlay(currentModal, modalData, navigationCoordinator, videoCoordinator) { tab -> selectedTab = tab }
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
                                            aspectRatio = 9.0/16.0,
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
                            onCancel = { navigationCoordinator.dismissModal() },
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
    onTabBarVisibilityChange: (Boolean) -> Unit = {}
) {
    when (selectedTab) {
        MainAppTab.HOME -> {
            HomeFeedView(
                userID = currentUser.id,
                navigationCoordinator = navigationCoordinator,
                isAnnouncementShowing = isAnnouncementShowing,
                onShowThreadView = onShowThreadView,
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