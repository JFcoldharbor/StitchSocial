/*
 * NavigationCoordinator.kt
 * StitchSocial Android
 * 
 * Layer 6: Coordination - Navigation and Route Management
 * Dependencies: Foundation layer only
 * Orchestrates: Tab Navigation → Modals → Deep Linking → Route Management
 * 
 * BLUEPRINT: SwiftUI navigation patterns from MainTabContainer.swift
 */

package com.example.stitchsocialclub.coordination

import com.example.stitchsocialclub.foundation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

/**
 * Main app tab destinations
 */
enum class MainAppTab(val route: String) {
    HOME("home"),
    DISCOVERY("discovery"),
    PROGRESSION("progression"),
    NOTIFICATIONS("notifications");

    val title: String
        get() = when (this) {
            HOME -> "Home"
            DISCOVERY -> "Discover"
            PROGRESSION -> "Profile"
            NOTIFICATIONS -> "Inbox"
        }

    val icon: String
        get() = when (this) {
            HOME -> "ic_home"
            DISCOVERY -> "ic_search"
            PROGRESSION -> "ic_person"
            NOTIFICATIONS -> "ic_notifications"
        }

    val selectedIcon: String
        get() = when (this) {
            HOME -> "ic_home_filled"
            DISCOVERY -> "ic_search_filled"
            PROGRESSION -> "ic_person_filled"
            NOTIFICATIONS -> "ic_notifications_filled"
        }

    companion object {
        val leftSideTabs = listOf(HOME, DISCOVERY)
        val rightSideTabs = listOf(PROGRESSION, NOTIFICATIONS)
    }
}

/**
 * Navigation destinations in the app
 */
sealed class NavigationDestination(val route: String) {
    // Tab Destinations
    object Home : NavigationDestination("home")
    object Discovery : NavigationDestination("discovery")
    object Profile : NavigationDestination("profile")
    object Notifications : NavigationDestination("notifications")

    // Modal Destinations
    object Recording : NavigationDestination("recording")
    object Settings : NavigationDestination("settings")
    object VideoPlayer : NavigationDestination("video_player")

    // Deep Link Destinations
    data class UserProfile(val userID: String) : NavigationDestination("user_profile/$userID")
    data class VideoDetail(val videoID: String) : NavigationDestination("video_detail/$videoID")
    data class ThreadDetail(val threadID: String) : NavigationDestination("thread_detail/$threadID")
}

/**
 * Modal presentation states
 */
enum class ModalState {
    NONE,
    RECORDING,
    SETTINGS,
    USER_PROFILE,
    VIDEO_PLAYER,
    NOTIFICATION_DETAIL
}

/**
 * Navigation events for UI communication
 */
sealed class NavigationEvent {
    data class TabSelected(val tab: MainAppTab) : NavigationEvent()
    data class ShowModal(val modal: ModalState, val data: Map<String, Any> = emptyMap()) : NavigationEvent()
    object DismissModal : NavigationEvent()
    data class NavigateToDestination(val destination: NavigationDestination) : NavigationEvent()
    data class HandleDeepLink(val link: String) : NavigationEvent()
    object NavigateBack : NavigationEvent()
}

/**
 * Recording context for video creation
 */
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

/**
 * Navigation state for the entire app
 */
data class NavigationState(
    val currentTab: MainAppTab = MainAppTab.HOME,
    val modalState: ModalState = ModalState.NONE,
    val modalData: Map<String, Any> = emptyMap(),
    val navigationStack: List<NavigationDestination> = emptyList(),
    val canNavigateBack: Boolean = false,
    val isTabBarVisible: Boolean = true,
    val recordingContext: VideoRecordingContext? = null
)

/**
 * Orchestrates complete navigation workflow for the app
 * Coordinates between tab navigation, modals, deep linking, and route management
 */
class NavigationCoordinator {

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

    // MARK: - Recording State

    private val _recordingContext = MutableStateFlow<VideoRecordingContext?>(null)
    val recordingContext: StateFlow<VideoRecordingContext?> = _recordingContext.asStateFlow()

    private val _showingRecording = MutableStateFlow(false)
    val showingRecording: StateFlow<Boolean> = _showingRecording.asStateFlow()

    // MARK: - Coroutine Scope

    private val coordinatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        println("🧭 NAVIGATION COORDINATOR: Initialized - Ready for app navigation orchestration")

        // Initialize with home tab
        updateNavigationState()
    }

    // MARK: - Tab Navigation

    /**
     * Navigate to specific tab
     */
    fun navigateToTab(tab: MainAppTab) {
        if (_selectedTab.value == tab) {
            // Same tab selected - could implement scroll to top behavior
            println("🧭 TAB: Same tab selected - ${tab.title}")
            return
        }

        _selectedTab.value = tab

        // Close any modal when switching tabs
        if (_currentModal.value != ModalState.NONE) {
            dismissModal()
        }

        // Clear navigation stack when switching tabs
        clearNavigationStack()

        updateNavigationState()

        // Emit navigation event
        coordinatorScope.launch {
            _navigationEvents.emit(NavigationEvent.TabSelected(tab))
        }

        println("🧭 TAB: Navigated to ${tab.title}")
    }

    /**
     * Show create button modal (recording)
     */
    fun showCreateModal(context: VideoRecordingContext = VideoRecordingContext.NEW_THREAD) {
        _recordingContext.value = context
        _showingRecording.value = true
        _currentModal.value = ModalState.RECORDING
        _tabBarVisible.value = false

        updateNavigationState()

        coordinatorScope.launch {
            _navigationEvents.emit(
                NavigationEvent.ShowModal(
                    ModalState.RECORDING,
                    mapOf("context" to context)
                )
            )
        }

        println("🧭 MODAL: Showing recording modal - ${context.displayName}")
    }

    // MARK: - Modal Management

    /**
     * Show modal with optional data
     */
    fun showModal(modal: ModalState, data: Map<String, Any> = emptyMap()) {
        _currentModal.value = modal
        _modalData.value = data

        // Hide tab bar for certain modals
        when (modal) {
            ModalState.RECORDING, ModalState.VIDEO_PLAYER -> {
                _tabBarVisible.value = false
            }
            else -> {
                // Keep tab bar visible for sheet-style modals
            }
        }

        updateNavigationState()

        coordinatorScope.launch {
            _navigationEvents.emit(NavigationEvent.ShowModal(modal, data))
        }

        println("🧭 MODAL: Showing ${modal.name} modal")
    }

    /**
     * Dismiss current modal
     */
    fun dismissModal() {
        val currentModal = _currentModal.value

        _currentModal.value = ModalState.NONE
        _modalData.value = emptyMap()
        _showingRecording.value = false
        _recordingContext.value = null
        _tabBarVisible.value = true

        updateNavigationState()

        coordinatorScope.launch {
            _navigationEvents.emit(NavigationEvent.DismissModal)
        }

        println("🧭 MODAL: Dismissed ${currentModal.name} modal")
    }

    // MARK: - Navigation Stack Management

    /**
     * Navigate to specific destination
     */
    fun navigateTo(destination: NavigationDestination) {
        val currentStack = _navigationStack.value.toMutableList()
        currentStack.add(destination)

        _navigationStack.value = currentStack
        _canNavigateBack.value = currentStack.isNotEmpty()

        updateNavigationState()

        coordinatorScope.launch {
            _navigationEvents.emit(NavigationEvent.NavigateToDestination(destination))
        }

        println("🧭 NAVIGATE: To ${destination.route}")
    }

    /**
     * Navigate back in the stack
     */
    fun navigateBack(): Boolean {
        val currentStack = _navigationStack.value.toMutableList()

        return if (currentStack.isNotEmpty()) {
            val removedDestination = currentStack.removeLastOrNull()
            _navigationStack.value = currentStack
            _canNavigateBack.value = currentStack.isNotEmpty()

            updateNavigationState()

            coordinatorScope.launch {
                _navigationEvents.emit(NavigationEvent.NavigateBack)
            }

            println("🧭 NAVIGATE: Back from ${removedDestination?.route}")
            true
        } else {
            false
        }
    }

    /**
     * Clear navigation stack
     */
    fun clearNavigationStack() {
        _navigationStack.value = emptyList()
        _canNavigateBack.value = false
        updateNavigationState()

        println("🧭 NAVIGATE: Cleared navigation stack")
    }

    // MARK: - Deep Linking

    /**
     * Handle deep link navigation
     */
    fun handleDeepLink(link: String) {
        println("🧭 DEEP LINK: Handling $link")

        // Parse deep link and determine destination
        val destination = parseDeepLink(link)

        if (destination != null) {
            // Dismiss any current modal
            if (_currentModal.value != ModalState.NONE) {
                dismissModal()
            }

            // Navigate to appropriate tab first
            when (destination) {
                is NavigationDestination.UserProfile -> navigateToTab(MainAppTab.PROGRESSION)
                is NavigationDestination.VideoDetail -> navigateToTab(MainAppTab.HOME)
                is NavigationDestination.ThreadDetail -> navigateToTab(MainAppTab.HOME)
                else -> {
                    // Keep current tab
                }
            }

            // Then navigate to specific destination
            navigateTo(destination)

            coordinatorScope.launch {
                _navigationEvents.emit(NavigationEvent.HandleDeepLink(link))
            }

            println("🧭 DEEP LINK: Navigated to ${destination.route}")
        } else {
            println("🧭 DEEP LINK: Could not parse $link")
        }
    }

    /**
     * Parse deep link string into navigation destination
     */
    private fun parseDeepLink(link: String): NavigationDestination? {
        return try {
            when {
                link.startsWith("stitchsocial://user/") -> {
                    val userID = link.substringAfter("stitchsocial://user/")
                    NavigationDestination.UserProfile(userID)
                }
                link.startsWith("stitchsocial://video/") -> {
                    val videoID = link.substringAfter("stitchsocial://video/")
                    NavigationDestination.VideoDetail(videoID)
                }
                link.startsWith("stitchsocial://thread/") -> {
                    val threadID = link.substringAfter("stitchsocial://thread/")
                    NavigationDestination.ThreadDetail(threadID)
                }
                link == "stitchsocial://home" -> NavigationDestination.Home
                link == "stitchsocial://discovery" -> NavigationDestination.Discovery
                link == "stitchsocial://profile" -> NavigationDestination.Profile
                link == "stitchsocial://notifications" -> NavigationDestination.Notifications
                else -> null
            }
        } catch (e: Exception) {
            println("🧭 DEEP LINK: Error parsing $link - ${e.message}")
            null
        }
    }

    // MARK: - Tab Bar Visibility

    /**
     * Show tab bar
     */
    fun showTabBar() {
        _tabBarVisible.value = true
        updateNavigationState()
        println("🧭 TAB BAR: Shown")
    }

    /**
     * Hide tab bar
     */
    fun hideTabBar() {
        _tabBarVisible.value = false
        updateNavigationState()
        println("🧭 TAB BAR: Hidden")
    }

    // MARK: - Recording Coordination

    /**
     * Handle video creation completion
     */
    fun onVideoCreated(videoData: Map<String, Any>) {
        println("🧭 RECORDING: Video created - ${videoData}")

        // Dismiss recording modal
        dismissModal()

        // Navigate to home to show the new video
        navigateToTab(MainAppTab.HOME)

        // Could trigger feed refresh here
        println("🧭 RECORDING: Navigation complete after video creation")
    }

    /**
     * Handle recording cancellation
     */
    fun onRecordingCancelled() {
        println("🧭 RECORDING: Cancelled")
        dismissModal()
    }

    // MARK: - Helper Methods

    /**
     * Update combined navigation state
     */
    private fun updateNavigationState() {
        _navigationState.value = NavigationState(
            currentTab = _selectedTab.value,
            modalState = _currentModal.value,
            modalData = _modalData.value,
            navigationStack = _navigationStack.value,
            canNavigateBack = _canNavigateBack.value,
            isTabBarVisible = _tabBarVisible.value,
            recordingContext = _recordingContext.value
        )
    }

    /**
     * Get current route for external navigation systems
     */
    fun getCurrentRoute(): String {
        val state = _navigationState.value

        return when (state.modalState) {
            ModalState.NONE -> {
                if (state.navigationStack.isNotEmpty()) {
                    state.navigationStack.last().route
                } else {
                    state.currentTab.route
                }
            }
            else -> state.modalState.name.lowercase()
        }
    }

    /**
     * Check if specific tab is selected
     */
    fun isTabSelected(tab: MainAppTab): Boolean {
        return _selectedTab.value == tab
    }

    /**
     * Check if modal is currently shown
     */
    fun isModalShown(modal: ModalState): Boolean {
        return _currentModal.value == modal
    }

    /**
     * Get navigation stack depth
     */
    fun getNavigationDepth(): Int {
        return _navigationStack.value.size
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        coordinatorScope.cancel()
        println("🧭 NAVIGATION COORDINATOR: Cleaned up")
    }
}

// MARK: - Navigation Extensions

/**
 * Quick access to navigation state properties
 */
val NavigationCoordinator.isRecording: Boolean
    get() = currentModal.value == ModalState.RECORDING

val NavigationCoordinator.currentTabTitle: String
    get() = selectedTab.value.title

val NavigationCoordinator.hasNavigationStack: Boolean
    get() = navigationStack.value.isNotEmpty()

/**
 * Tab bar helper methods
 */
fun NavigationCoordinator.isHomeSelected() = isTabSelected(MainAppTab.HOME)
fun NavigationCoordinator.isDiscoverySelected() = isTabSelected(MainAppTab.DISCOVERY)
fun NavigationCoordinator.isProfileSelected() = isTabSelected(MainAppTab.PROGRESSION)
fun NavigationCoordinator.isNotificationsSelected() = isTabSelected(MainAppTab.NOTIFICATIONS)