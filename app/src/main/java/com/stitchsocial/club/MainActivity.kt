/*
 * MainActivity.kt - WITH FCM PUSH NOTIFICATIONS (FIXED)
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
 */

package com.stitchsocial.club

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// Foundation and Services
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.services.*
import com.stitchsocial.club.views.*
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.VideoCoordinator
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.ui.theme.StitchSocialClubTheme
import com.stitchsocial.club.camera.RecordingContext
import com.stitchsocial.club.camera.ThreadComposer

// Stitch colors theme
object StitchColors {
    val primary = Color(0xFF00BCD4)
    val background = Color.Black
    val textSecondary = Color.Gray
    val error = Color.Red
    val modalOverlay = Color.Black.copy(alpha = 0.7f)
    val cardBackground = Color(0xFF1E1E1E)
}

class MainActivity : ComponentActivity() {

    companion object {
        // ✅ FIXED: Store pending notification intent for Compose processing
        var pendingNotificationIntent: Intent? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        suppressCameraLogging()

        Log.d("STITCH_MAIN", "App starting up...")

        // ✅ REMOVED: FCM initialization moved to AFTER authentication
        // FCM will be initialized in LaunchedEffect after user authenticates

        // ✅ NEW: Request notification permission (Android 13+)
        requestNotificationPermission()

        enableEdgeToEdge()

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            StitchSocialClubTheme {
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
    var selectedTab by remember { mutableStateOf(MainAppTab.HOME) }

    val authService = remember { AuthService() }
    val videoService = remember { VideoServiceImpl() }
    val userService = remember { UserService(context) }
    val videoCoordinator = remember { VideoCoordinator(videoService, context) }
    val navigationCoordinator = remember { NavigationCoordinator(videoCoordinator) }
    val feedService = remember { HybridHomeFeedService(videoService, userService) }

    // ✅ FIXED: Process pending notification intent
    LaunchedEffect(navigationCoordinator) {
        MainActivity.pendingNotificationIntent?.let { intent ->
            Log.d("STITCH_MAIN", "📱 Processing pending notification intent")

            val notificationType = intent.getStringExtra("notification_type")
            val videoId = intent.getStringExtra("video_id")
            val userId = intent.getStringExtra("user_id")

            // Wait for authentication to complete
            delay(1000)

            when (notificationType) {
                "hype", "reply", "cool" -> {
                    videoId?.let { id ->
                        Log.d("STITCH_MAIN", "📱 Navigating to video: $id")
                        navigationCoordinator.showModal(
                            modal = ModalState.VIDEO_PLAYER,
                            data = mapOf("videoId" to id)
                        )
                    }
                }
                "follow" -> {
                    userId?.let { id ->
                        Log.d("STITCH_MAIN", "📱 Navigating to profile: $id")
                        navigationCoordinator.showModal(
                            modal = ModalState.USER_PROFILE,
                            data = mapOf("userID" to id)
                        )
                    }
                }
                "milestone" -> {
                    Log.d("STITCH_MAIN", "📱 Switching to notifications tab")
                    selectedTab = MainAppTab.NOTIFICATIONS
                }
            }

            // Clear pending intent
            MainActivity.pendingNotificationIntent = null
            Log.d("STITCH_MAIN", "✅ Notification intent processed and cleared")
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            navigationCoordinator.showModal(ModalState.PARALLEL_PROCESSING)
            scope.launch {
                try {
                    videoCoordinator.processGalleryVideo(it)
                    val videoPath = videoCoordinator.lastProcessedVideoPath.value
                    val aiResult = videoCoordinator.lastAIResult.value
                    if (videoPath != null) {
                        val modalDataMap = mutableMapOf<String, Any>("videoPath" to videoPath)
                        if (aiResult != null) modalDataMap["aiResult"] = aiResult
                        navigationCoordinator.showModal(ModalState.THREAD_COMPOSER, modalDataMap)
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

    var currentUser: BasicUserInfo? by remember { mutableStateOf(null) }
    var isLoadingUser by remember { mutableStateOf(true) }
    var userError by remember { mutableStateOf<String?>(null) }

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
            currentUser != null -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    TabContent(selectedTab, currentUser!!, videoService, userService, feedService, navigationCoordinator)

                    // Only show tab bar when no modal is active
                    if (currentModal == ModalState.NONE) {
                        CustomDippedTabBar(
                            selectedTab = selectedTab,
                            onTabSelected = { tab -> selectedTab = tab },
                            onCreateTapped = { navigationCoordinator.showModal(ModalState.RECORDING) },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }

                    ModalOverlay(currentModal, modalData, navigationCoordinator, videoCoordinator) { tab -> selectedTab = tab }
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
        Box(modifier = Modifier.fillMaxSize().background(StitchColors.modalOverlay)) {
            when (currentModal) {
                ModalState.RECORDING -> {
                    val recordingContext = (modalData["context"] as? RecordingContext) ?: RecordingContext.NewThread
                    val parentVideo = modalData["parentVideo"] as? CoreVideoMetadata
                    println("🎬 MAINACT: RECORDING Modal active - context: $recordingContext")
                    CameraView(
                        recordingContext = recordingContext,
                        parentVideo = parentVideo,
                        onVideoRecorded = { recordedVideo ->
                            try {
                                println("📹📹📹 MAINACT: === onVideoRecorded CALLBACK START ===")
                                println("📹 MAINACT: Video ID: ${recordedVideo.id}")
                                println("📹 MAINACT: Video path: ${recordedVideo.videoURL}")
                                println("📹 MAINACT: Recording context: $recordingContext")

                                println("📹 MAINACT: About to call showModal(PARALLEL_PROCESSING)...")
                                navigationCoordinator.showModal(ModalState.PARALLEL_PROCESSING)
                                println("📹 MAINACT: showModal called successfully!")

                                println("📹 MAINACT: Launching coroutine for processing...")
                                kotlinx.coroutines.GlobalScope.launch {
                                    try {
                                        println("🔄 MAINACT: Inside coroutine - starting parallel processing...")
                                        videoCoordinator.startParallelProcessing(recordedVideo.videoURL, recordedVideo, recordingContext)
                                        println("✅ MAINACT: Parallel processing completed!")
                                    } catch (e: Exception) {
                                        println("❌ MAINACT: Processing EXCEPTION: ${e.message}")
                                        e.printStackTrace()
                                    }
                                }
                                println("📹 MAINACT: Coroutine launched!")
                                println("📹📹📹 MAINACT: === onVideoRecorded CALLBACK END ===")
                            } catch (e: Exception) {
                                println("❌❌❌ MAINACT: EXCEPTION in onVideoRecorded: ${e.message}")
                                e.printStackTrace()
                            }
                        },
                        onCancel = {
                            println("❌ MAINACT: Camera CANCELLED - dismissing modal")
                            navigationCoordinator.dismissModal()
                        },
                        onStopAllVideos = {},
                        onDisposeAllVideos = {},
                        onGalleryRequested = { navigationCoordinator.requestGalleryPicker() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                ModalState.PARALLEL_PROCESSING -> ParallelProcessingView(navigationCoordinator, Modifier.fillMaxSize())
                ModalState.THREAD_COMPOSER -> {
                    val videoPath = modalData["videoPath"] as? String
                    // Get AI result directly from VideoCoordinator (stored as services.VideoAnalysisResult)
                    val aiResult = videoCoordinator.lastAIResult.value
                    // Get the recording context from VideoCoordinator (stored during startParallelProcessing)
                    val recordingContext = videoCoordinator.lastRecordingContext.value ?: RecordingContext.NewThread
                    if (videoPath != null) {
                        ThreadComposer(
                            recordedVideoURL = videoPath,
                            recordingContext = recordingContext,
                            aiResult = aiResult,
                            videoCoordinator = videoCoordinator,
                            onVideoCreated = { navigationCoordinator.dismissModal(); onTabChange(MainAppTab.HOME) },
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
    navigationCoordinator: NavigationCoordinator
) {
    when (selectedTab) {
        MainAppTab.HOME -> {
            HomeFeedView(
                userID = currentUser.id,
                navigationCoordinator = navigationCoordinator,
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
                },
                onNavigateToSearch = {
                    Log.d("NAVIGATION", "🔍 SEARCH NAVIGATION TRIGGERED")
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        MainAppTab.PROGRESSION -> {
            ProfileView(
                userID = currentUser.id,
                navigationCoordinator = navigationCoordinator,
                modifier = Modifier.fillMaxSize()
            )
        }
        MainAppTab.NOTIFICATIONS -> {
            NotificationViewComplete(
                navigationCoordinator = navigationCoordinator,
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