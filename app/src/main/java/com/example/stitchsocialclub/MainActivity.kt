/*
 * MainActivity.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Main application entry point with CameraView and ThreadComposer integration
 * Features: Premium glassmorphism, camera recording flow, video composition
 * UPDATED: Integrated minimal CameraView and ThreadComposer
 */

package com.example.stitchsocialclub

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.stitchsocialclub.views.HomeFeedView
import com.example.stitchsocialclub.views.ProfileView
import com.example.stitchsocialclub.views.LoginView
import com.example.stitchsocialclub.views.CustomDippedTabBar
import com.example.stitchsocialclub.views.MainAppTab
import com.example.stitchsocialclub.views.CameraView
import com.example.stitchsocialclub.views.ThreadComposer
import com.example.stitchsocialclub.services.AuthService
import com.example.stitchsocialclub.services.VideoServiceImpl.SimpleVideoMetadata
import com.example.stitchsocialclub.views.CameraView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide system navigation bar completely
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Keep screen on and full screen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContent {
            StitchSocialTheme {
                MainActivityContent()
            }
        }
    }
}

// MARK: - Stitch Colors
object StitchColors {
    val primary = Color(0xFF00BFFF)        // Electric Blue
    val secondary = Color(0xFF1E90FF)      // Deeper Blue
    val textSecondary = Color(0xFF9CA3AF)  // Light Gray
    val background = Color.Black
}

// MARK: - MainAppTab Enum
enum class MainAppTab(
    val route: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    HOME("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    DISCOVERY("discovery", "Discover", Icons.Outlined.Search, Icons.Filled.Search),
    PROGRESSION("progression", "Profile", Icons.Outlined.Person, Icons.Filled.Person),
    NOTIFICATIONS("notifications", "Inbox", Icons.Outlined.Notifications, Icons.Filled.Notifications);

    companion object {
        val leftSideTabs = listOf(HOME, DISCOVERY)
        val rightSideTabs = listOf(PROGRESSION, NOTIFICATIONS)
    }
}

// MARK: - Recording Flow States
enum class RecordingFlowState {
    NONE,           // Not recording
    CAMERA,         // Camera view active
    COMPOSER        // Thread composer active
}

@Composable
fun MainActivityContent() {
    var selectedTab by remember { mutableStateOf(MainAppTab.HOME) }
    var recordingFlowState by remember { mutableStateOf(RecordingFlowState.NONE) }
    var recordedVideo by remember { mutableStateOf<SimpleVideoMetadata?>(null) }

    // Get current authenticated user
    val authService = remember { AuthService() }
    val currentUser by authService.currentUser.collectAsState()
    val authState by authService.authState.collectAsState()
    val scope = rememberCoroutineScope()

    // DEBUG: Print current user info
    LaunchedEffect(currentUser) {
        println("DEBUG AUTH: currentUser = ${currentUser?.uid}")
        println("DEBUG AUTH: email = ${currentUser?.email}")
        println("DEBUG AUTH: authState = $authState")
        if (currentUser != null) {
            println("DEBUG AUTH: User is authenticated - ID = ${currentUser!!.uid}")
        } else {
            println("DEBUG AUTH: No current user found")
        }
    }

    // Show clean LoginView if not authenticated
    if (currentUser == null) {
        CleanLoginView(
            authService = authService,
            onLoginSuccess = {
                println("LOGIN SUCCESS: User authenticated, showing main app")
            }
        )
        return
    }

    // Main app content when authenticated
    Box(modifier = Modifier.fillMaxSize()) {

        // Show recording flow or main content
        when (recordingFlowState) {
            RecordingFlowState.NONE -> {
                // Main content based on selected tab
                when (selectedTab) {
                    MainAppTab.HOME -> {
                        println("DEBUG: Loading real HomeFeedView from MainActivity with userID: ${currentUser!!.uid}")
                        Box(modifier = Modifier.fillMaxSize()) {
                            HomeFeedView(userID = currentUser!!.uid)
                        }
                    }
                    MainAppTab.DISCOVERY -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            DiscoveryPlaceholder()
                        }
                    }
                    MainAppTab.PROGRESSION -> {
                        // Show ProfileView with sign-out option
                        Box(modifier = Modifier.fillMaxSize()) {
                            ProfileView(userID = currentUser!!.uid)

                            // Sign out button in top-right corner
                            Button(
                                onClick = {
                                    println("SIGN OUT: User requested sign out")
                                    scope.launch {
                                        try {
                                            authService.signOut()
                                            println("SIGN OUT: Successfully signed out")
                                        } catch (e: Exception) {
                                            println("SIGN OUT: Failed - ${e.message}")
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Red.copy(alpha = 0.8f)
                                ),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExitToApp,
                                    contentDescription = "Sign Out",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Sign Out",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    MainAppTab.NOTIFICATIONS -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NotificationsPlaceholder()
                        }
                    }
                }

                // Enhanced CustomDippedTabBar
                CustomDippedTabBar(
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        println("DEBUG: Tab selected: ${tab.title}")
                        selectedTab = tab
                    },
                    onCreateTapped = {
                        println("DEBUG: Create button tapped - Opening CameraView")
                        recordingFlowState = RecordingFlowState.CAMERA
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            RecordingFlowState.CAMERA -> {
                // Show CameraView
                CameraView(
                    onVideoCreated = { videoMetadata ->
                        println("DEBUG: Video created - Moving to ThreadComposer")
                        recordedVideo = videoMetadata
                        recordingFlowState = RecordingFlowState.COMPOSER
                    },
                    onCancel = {
                        println("DEBUG: Camera cancelled - Back to main view")
                        recordingFlowState = RecordingFlowState.NONE
                        recordedVideo = null
                    }
                )
            }

            RecordingFlowState.COMPOSER -> {
                // Show ThreadComposer
                recordedVideo?.let { video ->
                    ThreadComposer(
                        videoMetadata = video,
                        onVideoCreated = { finalVideo ->
                            println("DEBUG: Thread created successfully - ${finalVideo.title}")
                            // TODO: Add video to feed or show success message
                            recordingFlowState = RecordingFlowState.NONE
                            recordedVideo = null
                            selectedTab = MainAppTab.HOME // Return to home feed
                        },
                        onCancel = {
                            println("DEBUG: ThreadComposer cancelled - Back to main view")
                            recordingFlowState = RecordingFlowState.NONE
                            recordedVideo = null
                        }
                    )
                }
            }
        }
    }
}

// MARK: - Clean Login Screen (NO BRANDING)
@Composable
fun CleanLoginView(
    authService: AuthService,
    onLoginSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Watch for auth service state
    val authServiceLoading by authService.isLoading.collectAsState()
    val authServiceError by authService.lastError.collectAsState()

    LaunchedEffect(authServiceLoading) {
        isLoading = authServiceLoading
    }

    LaunchedEffect(authServiceError) {
        errorMessage = authServiceError?.message
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            // Simple welcome text
            Text(
                text = "Welcome",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Light
            )

            // Error message if present
            errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Red.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        color = Color.Red,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Sign in anonymously button
            Button(
                onClick = {
                    errorMessage = null
                    scope.launch {
                        try {
                            isLoading = true
                            val result = authService.signInAnonymously()
                            if (result.success) {
                                onLoginSuccess()
                            } else {
                                errorMessage = "Sign in failed. Please try again."
                            }
                        } catch (e: Exception) {
                            errorMessage = "Error: ${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StitchColors.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Sign In",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Continue as Guest",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Email sign in placeholder
            Button(
                onClick = {
                    println("Email sign in coming soon")
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.1f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = "Email Sign In",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Sign In with Email",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// MARK: - Placeholder Views
@Composable
fun DiscoveryPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Discovery View\nComing Soon",
            color = Color.White,
            fontSize = 24.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun NotificationsPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Notifications View\nComing Soon",
            color = Color.White,
            fontSize = 24.sp,
            textAlign = TextAlign.Center
        )
    }
}

// MARK: - App Theme
@Composable
fun StitchSocialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = StitchColors.primary,
            background = Color.Black,
            surface = Color(0xFF1A1A1A)
        )
    ) {
        content()
    }
}