/*
 * GlobalVideoPlayer.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Package: com.example.stitchsocialclub.services (put with other services)
 * Layer 4: Core Services - Single ExoPlayer Instance Manager
 * Dependencies: Media3 ExoPlayer only
 */

package com.example.stitchsocialclub.services

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single ExoPlayer instance manager to prevent codec crashes
 * CRITICAL: Only one ExoPlayer instance exists at any time
 */
object GlobalVideoPlayer {

    // Single player instance
    private var player: ExoPlayer? = null
    private var currentVideoId: String? = null
    private var currentVideoUrl: String? = null
    private var attachedView: PlayerView? = null

    // Player state for UI updates
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Playback listener for state updates
    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            println("GLOBAL PLAYER: Playing state changed: $isPlaying")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isLoading.value = playbackState == Player.STATE_BUFFERING
            when (playbackState) {
                Player.STATE_READY -> {
                    println("GLOBAL PLAYER: Ready")
                }
                Player.STATE_BUFFERING -> {
                    println("GLOBAL PLAYER: Buffering...")
                }
                Player.STATE_ENDED -> {
                    println("GLOBAL PLAYER: Playback ended")
                }
                Player.STATE_IDLE -> {
                    println("GLOBAL PLAYER: Idle")
                }
            }
        }
    }

    /**
     * Initialize the global player (call once per app session)
     */
    fun initialize(context: Context): ExoPlayer {
        if (player == null) {
            player = ExoPlayer.Builder(context)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(5000, 15000, 1000, 2000)
                        .build()
                )
                .build().apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 1.0f
                    addListener(playbackListener)
                }

            println("GLOBAL PLAYER: ✅ Initialized single ExoPlayer instance")
        }
        return player!!
    }

    /**
     * Set current video (switches media source)
     */
    fun setCurrentVideo(videoUrl: String, videoId: String) {
        if (currentVideoId == videoId && currentVideoUrl == videoUrl) {
            println("GLOBAL PLAYER: 🔄 Same video, skipping switch")
            return
        }

        player?.let { p ->
            println("GLOBAL PLAYER: 🎬 Switching to video: $videoId")

            // Stop current playback
            p.stop()
            p.clearMediaItems()

            // Set new media
            p.setMediaItem(MediaItem.fromUri(videoUrl))
            p.prepare()

            // Update state
            currentVideoId = videoId
            currentVideoUrl = videoUrl

            println("GLOBAL PLAYER: ✅ Video switched successfully")
        } ?: run {
            println("GLOBAL PLAYER: ❌ Player not initialized")
        }
    }

    /**
     * Start playback
     */
    fun play() {
        player?.let { p ->
            p.playWhenReady = true
            p.play()
            println("GLOBAL PLAYER: ▶️ Playing: $currentVideoId")
        }
    }

    /**
     * Pause playback
     */
    fun pause() {
        player?.let { p ->
            p.pause()
            p.playWhenReady = false
            println("GLOBAL PLAYER: ⏸️ Paused: $currentVideoId")
        }
    }

    /**
     * Stop playback and clear media
     */
    fun stop() {
        player?.let { p ->
            p.stop()
            p.playWhenReady = false
            _isPlaying.value = false
            println("GLOBAL PLAYER: ⏹️ Stopped: $currentVideoId")
        }
    }

    /**
     * Attach player to a PlayerView
     */
    fun attachToView(view: PlayerView) {
        view.player = player
        attachedView = view
        println("GLOBAL PLAYER: 📺 Attached to PlayerView")
    }

    /**
     * Get current video information
     */
    fun getCurrentVideoId(): String? = currentVideoId
    fun getCurrentVideoUrl(): String? = currentVideoUrl

    /**
     * Check if player is initialized
     */
    fun isInitialized(): Boolean = player != null

    /**
     * Release player resources (call on app shutdown)
     */
    fun release() {
        player?.let { p ->
            p.removeListener(playbackListener)
            p.release()
            println("GLOBAL PLAYER: 🗑️ Released player resources")
        }

        player = null
        currentVideoId = null
        currentVideoUrl = null
        attachedView = null

        // Reset state
        _isPlaying.value = false
        _isLoading.value = false
    }

    /**
     * Hello world test
     */
    fun helloWorldTest() {
        println("GLOBAL PLAYER: Hello World - Single ExoPlayer ready!")
        println("GLOBAL PLAYER: Features: Crash-safe, Memory efficient, Codec-safe")
    }
}