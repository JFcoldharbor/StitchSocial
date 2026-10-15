package com.stitchsocial.club.services

import android.content.Context
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.stitchsocial.club.foundation.CoreVideoMetadata

/*
 * PlayerWarmPool.kt — the stitch either side of the one you're watching,
 * already loaded.
 *
 * A thread's sideways swipe shows its neighbours as stills (one live surface on
 * screen at a time), and VideoPlayerComposable used to build the new stitch's
 * player only once the swipe had landed — so every swipe ended on a cover while
 * that player prepared and buffered. Now the card warms the next and previous
 * stitch here, prepared and paused, and the composable takes the warm player
 * when it mounts: the first frame is already decoded and it plays at once.
 *
 * Small on purpose (two players) and emptied whenever the card stops being the
 * one on screen: each warm player holds a decoder, and a budget phone has few.
 * Main thread only, like ExoPlayer.
 *
 * Layer 4: Core Services
 */
object PlayerWarmPool {

    private const val MAX_WARM = 2

    /** videoId → (the URL it was loaded with, the prepared player), oldest first. */
    private val warm = LinkedHashMap<String, Pair<String, ExoPlayer>>()

    /**
     * The URL a feed player loads for this video — a cached faststart MP4 first
     * (local bytes, no round trip), then the model's playbackURL (HLS → MP4 →
     * videoURL). VideoPlayerComposable and the warm-up both call this, so a warm
     * player is only ever handed to a screen that would have loaded the same URL.
     */
    fun playbackURL(video: CoreVideoMetadata): String {
        val cachedMp4 = video.mp4URL?.takeIf { it.isNotEmpty() && VideoDiskCache.isCached(it) }
        return cachedMp4 ?: video.playbackURL ?: video.videoURL
    }

    /** A feed player with the short-form buffering and looping every feed uses. */
    fun newPlayer(context: Context): ExoPlayer {
        val builder = try {
            ExoPlayer.Builder(context).setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(VideoDiskCache.buildCacheDataSourceFactory())
            )
        } catch (_: Exception) {
            ExoPlayer.Builder(context)
        }
        // Short-form tuning: start after ~0.3s buffered, cap the buffer so a
        // clip doesn't over-fetch (ExoPlayer's defaults wait ~2.5s).
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 20_000, 300, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return builder.setLoadControl(loadControl).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
        }
    }

    /** Load `video` and leave it paused, ready for the swipe that lands on it. */
    @MainThread
    fun warm(context: Context, video: CoreVideoMetadata) {
        val url = playbackURL(video)
        if (url.isEmpty()) return
        warm[video.id]?.let { (warmURL, player) ->
            if (warmURL == url) {
                // Still wanted: move it to the newest end so it isn't the one evicted.
                warm.remove(video.id); warm[video.id] = warmURL to player
                return
            }
            warm.remove(video.id); player.release()
        }
        val player = newPlayer(context.applicationContext)
        runCatching {
            player.setMediaItem(MediaItem.fromUri(url))
            val resumeAt = VideoPositionMemory.get(video.id)
            if (resumeAt > 0) player.seekTo(resumeAt)
            player.prepare()
        }.onFailure { player.release(); return }
        warm[video.id] = url to player
        while (warm.size > MAX_WARM) {
            val oldest = warm.keys.first()
            warm.remove(oldest)?.second?.release()
        }
    }

    /** Keep only these videos warm; everything else is released. */
    @MainThread
    fun keepOnly(videoIDs: Set<String>) {
        warm.keys.filter { it !in videoIDs }.forEach { warm.remove(it)?.second?.release() }
    }

    /**
     * The warm player for this video, if it was loaded with `url` — ownership
     * passes to the caller, who releases it. Null means build one as before.
     */
    @MainThread
    fun take(videoId: String, url: String): ExoPlayer? {
        val (warmURL, player) = warm.remove(videoId) ?: return null
        if (warmURL != url) { player.release(); return null }
        return player
    }

    /** Whether a player for this video is loaded and waiting. */
    @MainThread
    fun isWarm(videoId: String): Boolean = warm.containsKey(videoId)

    @MainThread
    fun releaseAll() {
        warm.values.forEach { it.second.release() }
        warm.clear()
    }
}
