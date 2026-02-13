/*
 * AppEvents.kt - UNIFIED APP EVENT NAMES
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Single Source of Truth for All App Events
 * Android equivalent of iOS NotificationNames.swift
 * Uses sealed class events + singleton EventBus (SharedFlow)
 *
 * EXACT PORT: NotificationNames.swift
 */

package com.stitchsocial.club.foundation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

// MARK: - App Events (sealed class hierarchy)

sealed class AppEvent {

    // Player Control
    object KillAllVideoPlayers : AppEvent()
    object PauseAllVideoPlayers : AppEvent()
    object ResumeVideoPlayback : AppEvent()

    // Feed Notifications
    object PreloadHomeFeed : AppEvent()
    data class RefreshFeeds(val newVideoID: String? = null) : AppEvent()
    object RefreshHomeFeed : AppEvent()
    object RefreshDiscovery : AppEvent()
    object RefreshProfile : AppEvent()

    // Navigation
    data class NavigateToVideo(val videoID: String) : AppEvent()
    data class NavigateToProfile(val userID: String) : AppEvent()
    data class NavigateToThread(val threadID: String) : AppEvent()
    object NavigateToNotifications : AppEvent()
    data class ScrollToVideo(val videoID: String) : AppEvent()
    data class FocusThread(val threadID: String) : AppEvent()
    data class LoadUserProfile(val userID: String) : AppEvent()
    data class SetDiscoveryFilter(val filter: String) : AppEvent()

    // Recording
    object PresentRecording : AppEvent()
    data class RecordingCompleted(val videoID: String? = null) : AppEvent()

    // App State
    object FullscreenModeActivated : AppEvent()
    object FullscreenProfileOpened : AppEvent()
    object FullscreenProfileClosed : AppEvent()
    object UserDataCacheUpdated : AppEvent()
    object StopAllBackgroundActivity : AppEvent()
    object DeactivateAllPlayers : AppEvent()
    object DisableVideoAutoRestart : AppEvent()

    // Background Activity
    object KillAllBackgroundTimers : AppEvent()

    // Push Notifications
    data class PushNotificationReceived(val data: Map<String, String>) : AppEvent()
    data class PushNotificationTapped(val data: Map<String, String>) : AppEvent()

    // Collections
    data class SegmentUploadCompleted(val segmentID: String) : AppEvent()
    data class SegmentUploadFailed(val segmentID: String, val error: String) : AppEvent()
    data class CollectionPublished(val collectionID: String) : AppEvent()
    data class CollectionUpdated(val collectionID: String) : AppEvent()
}

// MARK: - Event Bus (Singleton)

object AppEventBus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    fun post(event: AppEvent) {
        scope.launch { _events.emit(event) }
    }

    // Convenience methods matching iOS MyStitchNotification helper

    fun killAllPlayers() = post(AppEvent.KillAllVideoPlayers)

    fun pauseAllPlayers() = post(AppEvent.PauseAllVideoPlayers)

    fun resumePlayback() = post(AppEvent.ResumeVideoPlayback)

    fun stopAllVideoActivity() {
        post(AppEvent.KillAllVideoPlayers)
        post(AppEvent.PauseAllVideoPlayers)
        post(AppEvent.StopAllBackgroundActivity)
        post(AppEvent.DeactivateAllPlayers)
    }

    fun refreshAllFeeds(newVideoID: String? = null) {
        post(AppEvent.RefreshFeeds(newVideoID))
    }

    fun refreshProfile() = post(AppEvent.RefreshProfile)
}