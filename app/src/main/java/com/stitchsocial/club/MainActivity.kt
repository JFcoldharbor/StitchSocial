/*
 * MainActivity.kt - FIXED PROFILE LOADING WITH INCREASED RETRY DELAY
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * CRITICAL FIX: Increased retry delay from 500ms to 1000ms
 * CRITICAL FIX: Empty onLoginSuccess callback prevents scope cancellation
 */

package com.stitchsocial.club

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.stitchsocial.club.coordination.VideoAnalysisResult
import com.stitchsocial.club.ui.theme.StitchSocialClubTheme
import com.stitchsocial.club.camera.RecordingContext
import com.stitchsocial.club.camera.ThreadComposer

// FIXED: Import LoginView from correct package
import com.example.stitchsocialclub.views.LoginView

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        suppressCameraLogging()

        Log.d("STITCH_MAIN", "App starting up...")

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

    // Real Firebase services
    val authService = remember { AuthService() }
    val videoService = remember { VideoServiceImpl() }
    val userService = remember { UserService(context) }
    val videoCoordinator = remember { VideoCoordinator(videoService, context) }
    val navigationCoordinator = remember { NavigationCoordinator(videoCoordinator) }
    val feedService = remember { HybridHomeFeedService(videoService, userService) }

    // Firebase authentication state
    val isAuthenticated by authService.isAuthenticated.collectAsState()
    val firebaseUser by authService.currentUser.collectAsState()
    val authLoading by authService.isLoading.collectAsState()

    // Navigation coordinator state
    val currentModal by navigationCoordinator.currentModal.collectAsState()
    val modalData by navigationCoordinator.modalData.collectAsState()

    // User profile
    var currentUser: BasicUserInfo? by remember { mutableStateOf(null) }
    var isLoadingUser by remember { mutableStateOf(true) }
    var userError by remember { mutableStateOf<String?>(null) }

    // Load current user profile from Firebase with retry logic
    LaunchedEffect(firebaseUser, isAuthenticated) {
        scope.launch {
            try {
                isLoadingUser = true
                userError = null

                val user = firebaseUser
                if (user != null && isAuthenticated) {
                    Log.d("STITCH_MAIN", "Loading profile for authenticated user: ${user.uid}")

                    // Retry logic for profile loading (handles race condition with profile creation)
                    var userProfile: BasicUserInfo? = null
                    var attempts = 0
                    val maxAttempts = 3

                    while (userProfile == null && attempts < maxAttempts) {
                        attempts++
                        Log.d("STITCH_MAIN", "Profile load attempt $attempts of $maxAttempts")

                        userProfile = userService.getUserProfile(user.uid)

                        if (userProfile == null && attempts < maxAttempts) {
                            // CRITICAL FIX: Increased from 500ms to 1000ms
                            Log.d("STITCH_MAIN", "Profile not found, waiting 1000ms before retry...")
                            delay(1000)
                        }
                    }

                    if (userProfile != null) {
                        currentUser = userProfile
                        Log.d("STITCH_MAIN", "Loaded user profile: ${userProfile.displayName}")
                    } else {
                        userError = "Profile not found for user ${user.uid} after $maxAttempts attempts"
                        Log.e("STITCH_MAIN", "Profile not found after $maxAttempts attempts")
                    }
                } else {
                    Log.d("STITCH_MAIN", "No authenticated user - showing sign-in")
                    currentUser = null
                }
            } catch (e: Exception) {
                userError = "Failed to load profile: ${e.message}"
                Log.e("STITCH_MAIN", "Profile loading failed: ${e.message}")
            } finally {
                isLoadingUser = false
            }
        }
    }

    // Show authentication loading
    if (authLoading || isLoadingUser) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = StitchColors.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (authLoading) "Authenticating..." else "Loading profile...",
                    color = StitchColors.textSecondary,
                    fontSize = 16.sp
                )
            }
        }
        return
    }

    // CRITICAL FIX: Show LoginView if not authenticated
    // Empty callback and early return prevent scope cancellation
    if (!isAuthenticated || currentUser == null) {
        LoginView(
            authService = authService,
            onLoginSuccess = {
                // CRITICAL FIX: Empty callback
                // Let reactive state handle navigation naturally
                Log.d("STITCH_MAIN", "Login successful - waiting for state update")
            },
            modifier = Modifier.fillMaxSize()
        )
        return  // CRITICAL: Early return prevents rest of UI from rendering
    }

    // Main UI with authenticated user
    val user = currentUser ?: return

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content with CustomDippedTabBar
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Tab content area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                TabContent(
                    selectedTab = selectedTab,
                    currentUser = user,
                    videoService = videoService,
                    userService = userService,
                    feedService = feedService,
                    navigationCoordinator = navigationCoordinator
                )
            }

            // CustomDippedTabBar
            CustomDippedTabBar(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab
                    Log.d("STITCH_MAIN", "Selected tab: ${tab.title}")
                },
                onCreateTapped = {
                    Log.d("STITCH_MAIN", "CREATE BUTTON: Opening camera for recording")
                    navigationCoordinator.showModal(
                        ModalState.RECORDING,
                        mapOf("context" to RecordingContext.NewThread)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Modal overlays based on NavigationCoordinator state
        when (currentModal) {
            ModalState.RECORDING -> {
                CameraView(
                    recordingContext = RecordingContext.NewThread,
                    parentVideo = null,
                    userTier = user.tier,
                    onVideoRecorded = { videoMetadata ->
                        Log.d("STITCH_MAIN", "Video recorded: ${videoMetadata.title}")

                        scope.launch {
                            try {
                                val videoData = mapOf(
                                    "videoPath" to videoMetadata.videoURL,
                                    "metadata" to videoMetadata,
                                    "recordingContext" to RecordingContext.NewThread
                                )
                                navigationCoordinator.onVideoCreated(videoData)
                                Log.d("STITCH_MAIN", "Video processing started")
                            } catch (e: Exception) {
                                Log.e("STITCH_MAIN", "Video processing failed: ${e.message}")
                            }
                        }
                    },
                    onCancel = {
                        Log.d("STITCH_MAIN", "Camera cancelled")
                        navigationCoordinator.dismissModal()
                    },
                    onStopAllVideos = {
                        Log.d("STITCH_MAIN", "Stopping all videos for recording")
                    },
                    onDisposeAllVideos = {
                        Log.d("STITCH_MAIN", "Disposing all video players")
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            ModalState.PARALLEL_PROCESSING -> {
                ParallelProcessingView(
                    navigationCoordinator = navigationCoordinator,
                    modifier = Modifier.fillMaxSize()
                )
            }

            ModalState.THREAD_COMPOSER -> {
                val videoPath = modalData["videoPath"] as? String
                val aiResult = modalData["aiResult"] as? VideoAnalysisResult

                if (videoPath != null) {
                    ThreadComposer(
                        recordedVideoURL = videoPath,
                        recordingContext = RecordingContext.NewThread,
                        aiResult = aiResult,
                        videoCoordinator = videoCoordinator,
                        onVideoCreated = { finalVideo ->
                            Log.d("STITCH_MAIN", "Video created: ${finalVideo.id}")
                            navigationCoordinator.dismissModal()
                            selectedTab = MainAppTab.HOME
                        },
                        onCancel = {
                            Log.d("STITCH_MAIN", "ThreadComposer cancelled")
                            navigationCoordinator.dismissModal()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Log.e("STITCH_MAIN", "ThreadComposer: Missing video path")
                    navigationCoordinator.dismissModal()
                }
            }

            ModalState.NONE -> {
                // No modal
            }

            else -> {
                // Handle any other modal states
                Log.w("STITCH_MAIN", "Unhandled modal state: $currentModal")
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
                modifier = Modifier.fillMaxSize()
            )
        }
        MainAppTab.DISCOVERY -> {
            DiscoveryView(
                onNavigateToVideo = { video ->
                    Log.d("NAVIGATION", "Discovery video selected: ${video.title}")
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
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Notifications\nComing Soon",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}