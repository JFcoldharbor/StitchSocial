/*
 * NavigationCoordinator.kt - FIXED RECORDING CONTEXT IMPORTS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 6: Coordination - Navigation and modal state management
 * Dependencies: VideoCoordinator (Layer 6)
 * Features: Tab navigation, modal management, video processing workflow, gallery picker
 *
 * ✅ FIXED: Removed coordination.RecordingContext enum (now uses camera.RecordingContext)
 * ✅ FIXED: All RecordingContext references now use camera package
 */

package com.stitchsocial.club.coordination

import com.stitchsocial.club.foundation.CoreVideoMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.VideoManager
import com.stitchsocial.club.coordination.VideoCoordinator
import com.stitchsocial.club.coordination.VideoAnalysisResult
import com.stitchsocial.club.camera.RecordingContext
import java.util.UUID

// MARK: - Supporting Enums and Data Classes

enum class MainAppTab(val title: String, val index: Int) {
    HOME("Home", 0),
    DISCOVERY("Discover", 1),
    PROGRESSION("Profile", 2),
    NOTIFICATIONS("Notifications", 3)
}

sealed class NavigationDestination(val route: String) {
    data class UserProfile(val userID: String) : NavigationDestination("user_profile/$userID")
    data class VideoDetail(val videoID: String) : NavigationDestination("video_detail/$videoID")
    data class ThreadDetail(val threadID: String) : NavigationDestination("thread_detail/$threadID")
}

enum class ModalState {
    NONE,
    RECORDING,
    PARALLEL_PROCESSING,
    THREAD_COMPOSER,
    SETTINGS,
    USER_PROFILE,
    VIDEO_PLAYER,
    NOTIFICATION_DETAIL,
    UPLOADING_VIDEO
}

sealed class NavigationEvent {
    data class TabSelected(val tab: MainAppTab) : NavigationEvent()
    data class ShowModal(val modal: ModalState, val data: Map<String, Any> = emptyMap()) : NavigationEvent()
    object DismissModal : NavigationEvent()
    data class NavigateToDestination(val destination: NavigationDestination) : NavigationEvent()
    data class HandleDeepLink(val link: String) : NavigationEvent()
    object NavigateBack : NavigationEvent()
    data class VideoUploadProgress(val progress: Double, val phase: String) : NavigationEvent()
    data class VideoUploadComplete(val videoId: String) : NavigationEvent()
    data class VideoUploadError(val error: String) : NavigationEvent()
    data class ParallelProcessingProgress(val progress: Double, val phase: String, val taskProgress: Map<String, Double>) : NavigationEvent()
}

enum class VideoRecordingContext {
    NEW_THREAD,
    REPLY_TO_THREAD,
    RESPOND_TO_CHILD;

    val displayName: String
        get() = when (this) {
            NEW_THREAD -> "New Thread"
            REPLY_TO_THREAD -> "Reply to Thread"
            RESPOND_TO_CHILD -> "Respond to Child"
        }

    val contentType: ContentType
        get() = when (this) {
            NEW_THREAD -> ContentType.THREAD
            REPLY_TO_THREAD -> ContentType.CHILD
            RESPOND_TO_CHILD -> ContentType.STEPCHILD
        }
}

data class NavigationState(
    val currentTab: MainAppTab = MainAppTab.HOME,
    val modalState: ModalState = ModalState.NONE,
    val modalData: Map<String, Any> = emptyMap(),
    val navigationStack: List<NavigationDestination> = emptyList(),
    val canNavigateBack: Boolean = false,
    val isTabBarVisible: Boolean = true,
    val recordingContext: VideoRecordingContext? = null,
    val isProcessing: Boolean = false,
    val parallelProgress: Double = 0.0,
    val parallelPhase: String = "",
    val isUploading: Boolean = false,
    val uploadProgress: Double = 0.0,
    val uploadPhase: String = ""
)

/**
 * NavigationCoordinator - Complete navigation and video workflow management
 * ✅ FIXED: Now properly uses camera.RecordingContext
 */
class NavigationCoordinator(
    private val videoCoordinator: VideoCoordinator
) {

    // MARK: - Coroutine Scope

    private val coordinatorScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    )

    // Expose VideoCoordinator for UI components
    val exposedVideoCoordinator: VideoCoordinator get() = videoCoordinator

    // MARK: - Gallery Picker Callback

    var onGalleryPickerRequested: (() -> Unit)? = null

    // MARK: - Navigation State

    private val _navigationState = MutableStateFlow(NavigationState())
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents: SharedFlow<NavigationEvent> = _navigationEvents.asSharedFlow()

    // MARK: - Tab Navigation State

    private val _selectedTab = MutableStateFlow(MainAppTab.HOME)
    val selectedTab: StateFlow<MainAppTab> = _selectedTab.asStateFlow()

    private val _tabBarVisible = MutableStateFlow(true)
    val tabBarVisible: StateFlow<Boolean> = _tabBarVisible.asStateFlow()

    // MARK: - Modal State

    private val _currentModal = MutableStateFlow(ModalState.NONE)
    val currentModal: StateFlow<ModalState> = _currentModal.asStateFlow()

    private val _modalData = MutableStateFlow<Map<String, Any>>(emptyMap())
    val modalData: StateFlow<Map<String, Any>> = _modalData.asStateFlow()

    // MARK: - Navigation History

    private val _navigationStack = MutableStateFlow<List<NavigationDestination>>(emptyList())
    val navigationStack: StateFlow<List<NavigationDestination>> = _navigationStack.asStateFlow()

    private val _canNavigateBack = MutableStateFlow(false)
    val canNavigateBack: StateFlow<Boolean> = _canNavigateBack.asStateFlow()

    // MARK: - Processing State

    private val _recordingContext = MutableStateFlow<VideoRecordingContext?>(null)
    val recordingContext: StateFlow<VideoRecordingContext?> = _recordingContext.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _parallelProgress = MutableStateFlow(0.0)
    val parallelProgress: StateFlow<Double> = _parallelProgress.asStateFlow()

    private val _parallelPhase = MutableStateFlow("")
    val parallelPhase: StateFlow<String> = _parallelPhase.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0.0)
    val uploadProgress: StateFlow<Double> = _uploadProgress.asStateFlow()

    private val _uploadPhase = MutableStateFlow("")
    val uploadPhase: StateFlow<String> = _uploadPhase.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    // MARK: - Tab Navigation

    /**
     * Navigate to specific tab
     */
    fun navigateToTab(tab: MainAppTab) {
        _selectedTab.value = tab
        updateNavigationState()

        coordinatorScope.launch {
            _navigationEvents.emit(NavigationEvent.TabSelected(tab))
        }

        println("🧭 TAB: ${tab.title}")
    }

    // MARK: - Gallery Picker

    /**
     * Request gallery picker launch
     */
    fun requestGalleryPicker() {
        println("📱 NAV COORDINATOR: Requesting gallery picker")
        onGalleryPickerRequested?.invoke()
    }

    // MARK: - Modal Management

    /**
     * Show modal with optional data
     */
    fun showModal(modal: ModalState, data: Map<String, Any> = emptyMap()) {
        _currentModal.value = modal
        _modalData.value = data
        updateNavigationState()

        coordinatorScope.launch {
            _navigationEvents.emit(NavigationEvent.ShowModal(modal, data))
        }

        println("🧭 MODAL: Showing ${modal.name}")
    }

    /**
     * Show recording modal
     */
    fun showRecordingModal(context: VideoRecordingContext = VideoRecordingContext.NEW_THREAD) {
        _recordingContext.value = context
        showModal(ModalState.RECORDING, mapOf("context" to context))
        println("🧭 RECORDING: Modal shown for ${context.displayName}")
    }

    /**
     * Dismiss current modal
     */
    fun dismissModal() {
        val previousModal = _currentModal.value
        _currentModal.value = ModalState.NONE
        _modalData.value = emptyMap()
        updateNavigationState()

        coordinatorScope.launch {
            _navigationEvents.emit(NavigationEvent.DismissModal)
        }

        println("🧭 MODAL: Dismissed $previousModal")
    }

    // MARK: - Video Processing Workflow

    /**
     * Handle video creation from CameraView - triggers parallel processing
     * ✅ FIXED: Now properly uses camera.RecordingContext
     */
    fun onVideoCreated(videoData: Map<String, Any>) {
        println("🧭 NAVIGATION: Video created, starting parallel processing")

        val videoPath = videoData["videoPath"] as? String
        val metadata = videoData["metadata"] as? CoreVideoMetadata
        val cameraRecordingContext = videoData["recordingContext"] as? RecordingContext

        if (videoPath == null || metadata == null || cameraRecordingContext == null) {
            println("❌ NAVIGATION: Missing required video data")
            return
        }

        // Show parallel processing modal
        showModal(ModalState.PARALLEL_PROCESSING, videoData)

        // Start VideoCoordinator processing in background
        coordinatorScope.launch {
            try {
                _isProcessing.value = true
                _parallelProgress.value = 0.0
                _parallelPhase.value = "Starting parallel processing..."

                println("🧭 RECORDING: Starting VideoCoordinator parallel processing")

                // ✅ FIXED: Pass camera.RecordingContext directly (VideoCoordinator expects it)
                videoCoordinator.startParallelProcessing(
                    videoPath = videoPath,
                    metadata = metadata,
                    recordingContext = cameraRecordingContext
                )

                println("🧭 RECORDING: Parallel processing complete")

            } catch (e: Exception) {
                println("❌ NAVIGATION: Processing failed - ${e.message}")
                _isProcessing.value = false
                dismissModal()
                coordinatorScope.launch {
                    _navigationEvents.emit(NavigationEvent.VideoUploadError(e.message ?: "Processing failed"))
                }
            }
        }
    }

    /**
     * Handle parallel processing completion - show ThreadComposer
     */
    fun onParallelProcessingComplete() {
        println("🧭 NAVIGATION: Parallel processing complete, showing composer")

        coordinatorScope.launch {
            try {
                // Get processed data from VideoCoordinator
                val videoPath = videoCoordinator.lastProcessedVideoPath.value
                val aiResult = videoCoordinator.lastAIResult.value

                if (videoPath == null) {
                    println("❌ NAVIGATION: Missing processed video path")
                    dismissModal()
                    return@launch
                }

                val composerData = mutableMapOf<String, Any>()
                composerData["videoPath"] = videoPath
                aiResult?.let { composerData["aiResult"] = it }

                // Show ThreadComposer modal
                showModal(ModalState.THREAD_COMPOSER, composerData)

                _isProcessing.value = false
                println("🧭 NAVIGATION: ThreadComposer ready for user input")

            } catch (e: Exception) {
                println("❌ NAVIGATION: Failed to show ThreadComposer - ${e.message}")
                dismissModal()
                _isProcessing.value = false
            }
        }
    }

    /**
     * Handle recording cancellation
     */
    fun onRecordingCancelled() {
        println("🧭 RECORDING: Cancelled")
        dismissModal()
    }

    // MARK: - Stack Navigation

    fun navigateTo(destination: NavigationDestination) {
        val currentStack = _navigationStack.value.toMutableList()
        currentStack.add(destination)

        _navigationStack.value = currentStack
        _canNavigateBack.value = currentStack.isNotEmpty()
        updateNavigationState()

        coordinatorScope.launch {
            _navigationEvents.emit(NavigationEvent.NavigateToDestination(destination))
        }

        println("🧭 NAVIGATE: Push -> ${destination.route} (stack depth: ${currentStack.size})")
    }

    fun navigateBack() {
        val currentStack = _navigationStack.value.toMutableList()
        if (currentStack.isNotEmpty()) {
            currentStack.removeLastOrNull()
            _navigationStack.value = currentStack
            _canNavigateBack.value = currentStack.isNotEmpty()
            updateNavigationState()

            coordinatorScope.launch {
                _navigationEvents.emit(NavigationEvent.NavigateBack)
            }

            println("🧭 NAVIGATE: Back (stack depth: ${currentStack.size})")
        }
    }

    // MARK: - Deep Link Handling

    fun handleDeepLink(link: String) {
        coordinatorScope.launch {
            val destination = parseDeepLink(link)
            if (destination != null) {
                navigateTo(destination)
                _navigationEvents.emit(NavigationEvent.HandleDeepLink(link))
                println("🧭 DEEP LINK: Handled $link -> ${destination.route}")
            } else {
                println("🧭 DEEP LINK: Could not parse $link")
            }
        }
    }

    private fun parseDeepLink(link: String): NavigationDestination? {
        return try {
            when {
                link.contains("/user/") -> {
                    val userId = link.substringAfterLast("/")
                    NavigationDestination.UserProfile(userId)
                }
                link.contains("/video/") -> {
                    val videoId = link.substringAfterLast("/")
                    NavigationDestination.VideoDetail(videoId)
                }
                link.contains("/thread/") -> {
                    val threadId = link.substringAfterLast("/")
                    NavigationDestination.ThreadDetail(threadId)
                }
                else -> null
            }
        } catch (e: Exception) {
            println("🧭 DEEP LINK: Parse error - ${e.message}")
            null
        }
    }

    // MARK: - State Management

    private fun updateNavigationState() {
        _navigationState.value = _navigationState.value.copy(
            currentTab = _selectedTab.value,
            modalState = _currentModal.value,
            modalData = _modalData.value,
            navigationStack = _navigationStack.value,
            canNavigateBack = _canNavigateBack.value,
            isTabBarVisible = _tabBarVisible.value,
            recordingContext = _recordingContext.value,
            isProcessing = _isProcessing.value,
            parallelProgress = _parallelProgress.value,
            parallelPhase = _parallelPhase.value,
            isUploading = _isUploading.value,
            uploadProgress = _uploadProgress.value,
            uploadPhase = _uploadPhase.value
        )
    }

    // MARK: - Error Handling

    fun handleNavigationError(error: StitchError) {
        coordinatorScope.launch {
            println("🧭 ERROR: ${error.message}")

            when (error) {
                is StitchError.NetworkError -> {
                    _navigationEvents.emit(NavigationEvent.VideoUploadError("Network error: ${error.message}"))
                }
                is StitchError.ValidationError -> {
                    _navigationEvents.emit(NavigationEvent.VideoUploadError("Validation error: ${error.message}"))
                }
                else -> {
                    _navigationEvents.emit(NavigationEvent.VideoUploadError("Error: ${error.message}"))
                }
            }
        }
    }

    // MARK: - Cleanup

    fun cleanup() {
        coordinatorScope.cancel()
        println("🧭 CLEANUP: NavigationCoordinator cleaned up")
    }
}