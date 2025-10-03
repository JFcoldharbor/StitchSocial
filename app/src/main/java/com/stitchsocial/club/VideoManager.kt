/*
 * VideoManager.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Global Video Manager - Ensures only ONE video plays at a time + Recording State Management
 * UPDATED: Added recording state tracking and automatic video cleanup
 */

package com.stitchsocial.club
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton VideoManager to control all video playback across the app
 * UPDATED: Now tracks recording state and automatically pauses videos during recording
 */
object VideoManager {

    // MARK: - Video Player Management

    private var currentActivePlayer: ExoPlayer? = null
    private var currentActiveVideoId: String? = null

    // MARK: - Playback State

    private val _isAnyVideoPlaying = MutableStateFlow(false)
    val isAnyVideoPlaying: StateFlow<Boolean> = _isAnyVideoPlaying

    // MARK: - Recording State Management (NEW)

    private val _isRecordingActive = MutableStateFlow(false)
    val isRecordingActive: StateFlow<Boolean> = _isRecordingActive

    private var playerBeforeRecording: ExoPlayer? = null
    private var videoIdBeforeRecording: String? = null

    // MARK: - Video Player Control

    /**
     * Register a new video player as the active one
     * Automatically pauses any previously active player
     * UPDATED: Respects recording state - won't play if recording is active
     */
    fun setActivePlayer(player: ExoPlayer, videoId: String) {
        Log.d("VIDEO_MANAGER", "🎯 Setting active player: $videoId")

        // Don't allow video playback during recording
        if (_isRecordingActive.value) {
            Log.w("VIDEO_MANAGER", "🎥 Recording active - blocking video playback for $videoId")
            player.pause()
            player.playWhenReady = false
            return
        }

        // Pause any currently active player
        currentActivePlayer?.let { activePlayer ->
            if (activePlayer != player) {
                Log.d("VIDEO_MANAGER", "⏸️ Pausing previous player: $currentActiveVideoId")
                activePlayer.pause()
                activePlayer.playWhenReady = false
            }
        }

        // Set new active player
        currentActivePlayer = player
        currentActiveVideoId = videoId
        _isAnyVideoPlaying.value = true

        Log.i("VIDEO_MANAGER", "✅ Active player set: $videoId")
    }

    /**
     * Pause the currently active player
     */
    fun pauseActivePlayer() {
        currentActivePlayer?.let { player ->
            Log.d("VIDEO_MANAGER", "⏸️ Pausing active player: $currentActiveVideoId")
            player.pause()
            player.playWhenReady = false
            _isAnyVideoPlaying.value = false
        }
    }

    /**
     * Resume the currently active player
     * UPDATED: Only resumes if recording is not active
     */
    fun resumeActivePlayer() {
        if (_isRecordingActive.value) {
            Log.w("VIDEO_MANAGER", "🎥 Recording active - cannot resume video playback")
            return
        }

        currentActivePlayer?.let { player ->
            Log.d("VIDEO_MANAGER", "▶️ Resuming active player: $currentActiveVideoId")
            player.play()
            player.playWhenReady = true
            _isAnyVideoPlaying.value = true
        }
    }

    /**
     * Pause ALL video players (for app backgrounding, etc.)
     * UPDATED: Enhanced logging and state management
     */
    fun pauseAllPlayers() {
        Log.w("VIDEO_MANAGER", "🛑 PAUSE ALL PLAYERS")
        currentActivePlayer?.let { player ->
            player.pause()
            player.playWhenReady = false
        }
        _isAnyVideoPlaying.value = false
    }

    /**
     * Clear reference to a specific player (when disposed)
     */
    fun clearPlayer(videoId: String) {
        if (currentActiveVideoId == videoId) {
            Log.d("VIDEO_MANAGER", "🗑️ Clearing active player: $videoId")
            currentActivePlayer = null
            currentActiveVideoId = null
            _isAnyVideoPlaying.value = false
        }
    }

    // MARK: - Recording State Management (NEW)

    /**
     * Start recording mode - pauses all video playback
     * Saves current video state for potential resume
     */
    fun startRecording() {
        Log.i("VIDEO_MANAGER", "🎥 RECORDING STARTED - Pausing all video playback")

        // Save current player state for resume
        playerBeforeRecording = currentActivePlayer
        videoIdBeforeRecording = currentActiveVideoId

        // Pause all video playback
        pauseAllPlayers()

        // Set recording state
        _isRecordingActive.value = true

        Log.i("VIDEO_MANAGER", "🎬 Recording mode active - all videos paused")
    }

    /**
     * Stop recording mode - allows video playback to resume
     * Optionally resumes the video that was playing before recording
     */
    fun stopRecording(resumePreviousVideo: Boolean = false) {
        Log.i("VIDEO_MANAGER", "🎥 RECORDING STOPPED - Video playback allowed")

        // Clear recording state first
        _isRecordingActive.value = false

        // Optionally resume previous video
        if (resumePreviousVideo && playerBeforeRecording != null && videoIdBeforeRecording != null) {
            Log.d("VIDEO_MANAGER", "🔄 Resuming previous video: $videoIdBeforeRecording")
            currentActivePlayer = playerBeforeRecording
            currentActiveVideoId = videoIdBeforeRecording
            resumeActivePlayer()
        }

        // Clear saved state
        playerBeforeRecording = null
        videoIdBeforeRecording = null

        Log.i("VIDEO_MANAGER", "✅ Recording mode ended - videos can resume")
    }

    /**
     * Force stop all video playback immediately
     * ENHANCED: More aggressive cleanup for critical situations
     */
    fun forceStopAllVideos() {
        Log.w("VIDEO_MANAGER", "🚨 FORCE STOP ALL VIDEOS")

        currentActivePlayer?.let { player ->
            try {
                player.stop()
                player.pause()
                player.playWhenReady = false
                Log.d("VIDEO_MANAGER", "🛑 Force stopped player: $currentActiveVideoId")
            } catch (e: Exception) {
                Log.e("VIDEO_MANAGER", "❌ Error force stopping player: ${e.message}")
            }
        }

        // Clear all state
        currentActivePlayer = null
        currentActiveVideoId = null
        _isAnyVideoPlaying.value = false

        Log.w("VIDEO_MANAGER", "🚨 All video playback force stopped")
    }

    // MARK: - State Queries

    /**
     * Get current active video ID
     */
    fun getCurrentActiveVideoId(): String? = currentActiveVideoId

    /**
     * Check if a specific video is currently active
     */
    fun isVideoActive(videoId: String): Boolean = currentActiveVideoId == videoId

    /**
     * Check if recording is currently active
     */
    fun isRecording(): Boolean = _isRecordingActive.value

    /**
     * Get comprehensive playback state for debugging
     */
    fun getPlaybackState(): String {
        return """
            VideoManager State:
            - Active Video: $currentActiveVideoId
            - Is Playing: ${_isAnyVideoPlaying.value}
            - Recording Active: ${_isRecordingActive.value}
            - Player exists: ${currentActivePlayer != null}
            - Saved for recording: $videoIdBeforeRecording
        """.trimIndent()
    }

    // MARK: - Debug and Monitoring

    /**
     * Log current state for debugging
     */
    fun logCurrentState() {
        Log.d("VIDEO_MANAGER", getPlaybackState())
    }
}