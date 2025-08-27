/*
 * VideoManager.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Global Video Manager - Ensures only ONE video plays at a time
 * Fixes: Multiple ExoPlayers playing simultaneously causing performance issues
 */

package com.example.stitchsocialclub

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton VideoManager to control all video playback across the app
 * Ensures only one video plays at a time
 */
object VideoManager {
    
    private var currentActivePlayer: ExoPlayer? = null
    private var currentActiveVideoId: String? = null
    
    private val _isAnyVideoPlaying = MutableStateFlow(false)
    val isAnyVideoPlaying: StateFlow<Boolean> = _isAnyVideoPlaying
    
    /**
     * Register a new video player as the active one
     * Automatically pauses any previously active player
     */
    fun setActivePlayer(player: ExoPlayer, videoId: String) {
        android.util.Log.d("VIDEO_MANAGER", "🎯 Setting active player: $videoId")
        
        // Pause any currently active player
        currentActivePlayer?.let { activePlayer ->
            if (activePlayer != player) {
                android.util.Log.d("VIDEO_MANAGER", "⏸️ Pausing previous player: $currentActiveVideoId")
                activePlayer.pause()
                activePlayer.playWhenReady = false
            }
        }
        
        // Set new active player
        currentActivePlayer = player
        currentActiveVideoId = videoId
        _isAnyVideoPlaying.value = true
        
        android.util.Log.i("VIDEO_MANAGER", "✅ Active player set: $videoId")
    }
    
    /**
     * Pause the currently active player
     */
    fun pauseActivePlayer() {
        currentActivePlayer?.let { player ->
            android.util.Log.d("VIDEO_MANAGER", "⏸️ Pausing active player: $currentActiveVideoId")
            player.pause()
            player.playWhenReady = false
            _isAnyVideoPlaying.value = false
        }
    }
    
    /**
     * Resume the currently active player
     */
    fun resumeActivePlayer() {
        currentActivePlayer?.let { player ->
            android.util.Log.d("VIDEO_MANAGER", "▶️ Resuming active player: $currentActiveVideoId")
            player.play()
            player.playWhenReady = true
            _isAnyVideoPlaying.value = true
        }
    }
    
    /**
     * Pause ALL video players (for app backgrounding, etc.)
     */
    fun pauseAllPlayers() {
        android.util.Log.w("VIDEO_MANAGER", "🛑 PAUSE ALL PLAYERS")
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
            android.util.Log.d("VIDEO_MANAGER", "🗑️ Clearing active player: $videoId")
            currentActivePlayer = null
            currentActiveVideoId = null
            _isAnyVideoPlaying.value = false
        }
    }
    
    /**
     * Get current active video ID
     */
    fun getCurrentActiveVideoId(): String? = currentActiveVideoId
    
    /**
     * Check if a specific video is currently active
     */
    fun isVideoActive(videoId: String): Boolean = currentActiveVideoId == videoId
}