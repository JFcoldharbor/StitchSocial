package com.stitchsocial.club.landing

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.stitchsocial.club.views.TabBarMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one song playing in the app. It starts on an artist's page and follows the
 * viewer around under a bar. Music is the extra here, so anything the viewer
 * actually chose wins:
 *
 *  - A video with sound stays silent until it's tapped. The tap gives it the
 *    sound and holds the music, until the bar's play button or leaving the feed.
 *  - A show, a live stream, thread view and the like hold the music with no bar,
 *    and it carries on when they close.
 *  - Recording holds it too, but it comes back paused, not blasting over someone
 *    who just posted.
 *  - A quick trip out of the app holds it. A long one, or closing the app, ends
 *    it, and the song's page still knows where it stopped.
 *
 * Layer 4: Core Services
 */
object MusicSession {

    data class Song(
        val landingID: String,
        val artistName: String,
        val slug: String,
        val videoID: String,
        val title: String,
        val artworkURL: String?,
        val videoURL: String
    )

    private const val AWAY_LIMIT_MS = 5 * 60_000L
    val barHeight = 36.dp
    val barWidth = 208.dp
    /** What a screen with its own top row shifts down to make room for the bar. */
    val topRoom get() = if (barIsVisible) 40.dp else 0.dp

    /**
     * Home's tabs already sit below the cutout, so they only move if the bar would
     * reach them: on a phone with no cutout. Dash, under them, moves the same.
     */
    @Composable
    fun homeTabsShift(): androidx.compose.ui.unit.Dp {
        if (!barIsVisible) return 0.dp
        val cutout = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
        return (42.dp - cutout - 22.dp).coerceAtLeast(0.dp)
    }

    var song: SongController? = null
        private set

    var current by mutableStateOf<Song?>(null)
        private set
    /** The viewer paused it, or it came back from recording. */
    var isPaused by mutableStateOf(false)
        private set
    /** The viewer tapped a video for its sound. */
    var videoHasSound by mutableStateOf(false)
        private set
    /** The song's own page has the controls, so no bar on it. */
    var isOnArtistPage: Boolean
        get() = pageIsUp
        set(value) {
            pageIsUp = value
            apply()
        }
    private var pageIsUp by mutableStateOf(false)
    /** Sponsored videos on screen, which get the sound. */
    private val adsOnScreen = mutableStateMapOf<String, Boolean>()
    /**
     * Goes up each time the viewer stops the music, so the video on screen knows
     * to carry on. Not when a screen or a tapped video took over.
     */
    var stoppedCount by mutableStateOf(0)
        private set
    var heroMuted by mutableStateOf(false)
        private set
    private var inForeground by mutableStateOf(true)
    /** Who's holding the music, and whether it should come back paused. */
    private val holds = mutableStateMapOf<String, Boolean>()

    private var queue: List<Song> = emptyList()
    private var wentAwayAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Music coming out of the speaker right now. */
    val isAudible: Boolean
        get() = current != null && !isPaused && !videoHasSound && holds.isEmpty() && adsOnScreen.isEmpty() && inForeground

    /** Videos stay quiet under the music until one is tapped. */
    val videosMustBeSilent: Boolean get() = isAudible

    val barIsVisible: Boolean
        get() = current != null && holds.isEmpty() && !isOnArtistPage

    fun start(context: Context) {
        if (song != null) return
        song = SongController(context.applicationContext) { playNext() }
        scope.launch {
            while (true) {
                song?.tick()
                delay(500)
            }
        }
    }

    /** Starts this song, or does nothing if it's already the one up. */
    fun play(next: Song, queue: List<Song>, startMs: Long) {
        this.queue = queue
        if (next == current) return
        current = next
        isPaused = false
        videoHasSound = false
        song?.load(next.landingID, next.videoID, next.videoURL, startMs)
        apply()
    }

    private fun playNext(): Boolean {
        val at = queue.indexOfFirst { it.videoID == current?.videoID }
        if (queue.size < 2 || at < 0) return false
        val next = queue[(at + 1) % queue.size]
        current = next
        song?.load(next.landingID, next.videoID, next.videoURL, 0)
        apply()
        return true
    }

    /**
     * The bar and the tracklist's button. Pressing play always gives the music
     * back, even over a video that had the sound.
     */
    fun togglePlay() {
        if (isAudible) {
            isPaused = true
            viewerStoppedMusic()
        } else {
            isPaused = false
            videoHasSound = false
            adsOnScreen.clear()
        }
        apply()
    }

    fun setMuted(muted: Boolean) {
        heroMuted = muted
        apply()
    }

    /** The bar's X. The song's page still remembers where it stopped. */
    fun dismiss() {
        if (isAudible) viewerStoppedMusic()
        song?.stop()
        current = null
        queue = emptyList()
        isPaused = false
        videoHasSound = false
        apply()
    }

    /** A tap on a video. True if it was used to give the video its sound. */
    fun takeTapForVideo(): Boolean {
        if (!videosMustBeSilent) return false
        videoHasSound = true
        apply()
        return true
    }

    /** The viewer left the feed or closed the video. The music carries on. */
    fun leftVideo() {
        if (!videoHasSound) return
        videoHasSound = false
        apply()
    }

    /** A sponsored video on screen gets the sound; the bar's play button overrides it. */
    fun setAd(id: String, onScreen: Boolean) {
        if (onScreen) adsOnScreen[id] = true else adsOnScreen.remove(id)
        apply()
    }

    fun hold(id: String, comesBackPaused: Boolean = false) {
        holds[id] = comesBackPaused
        apply()
    }

    fun release(id: String) {
        val comesBackPaused = holds.remove(id) ?: return
        if (comesBackPaused && current != null) isPaused = true
        apply()
    }

    fun wentAway() {
        wentAwayAt = System.currentTimeMillis()
        inForeground = false
        apply()
    }

    fun cameBack() {
        val away = if (wentAwayAt > 0) System.currentTimeMillis() - wentAwayAt else 0L
        wentAwayAt = 0
        inForeground = true
        if (away > AWAY_LIMIT_MS) dismiss() else apply()
    }

    private fun apply() {
        song?.setActive(isAudible, heroMuted)
        song?.setPictureShown(pageIsUp)
    }

    /** The song's own page has no video under it to carry on. */
    private fun viewerStoppedMusic() {
        if (!pageIsUp) stoppedCount++
    }
}

/** A sponsored video gets the sound while it's on screen. The bar stays up. */
@Composable
fun AdTakesTheSound(id: String, onScreen: Boolean) {
    DisposableEffect(id, onScreen) {
        MusicSession.setAd(id, onScreen)
        onDispose { MusicSession.setAd(id, false) }
    }
}

/** While this screen is up the music holds and the bar stays away. */
@Composable
fun HoldsMusic(comesBackPaused: Boolean = false) {
    val id = remember { java.util.UUID.randomUUID().toString() }
    DisposableEffect(id) {
        MusicSession.hold(id, comesBackPaused)
        onDispose { MusicSession.release(id) }
    }
}

/** The song and spot this device last reached on each artist page. */
private fun songPlaces(context: Context) = context.getSharedPreferences("song_places", Context.MODE_PRIVATE)

/** The player itself, and the place it saves as it goes. */
@OptIn(UnstableApi::class)
class SongController(context: Context, private val onEnded: () -> Boolean) {
    private val places = songPlaces(context)
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var renderedVideoID by mutableStateOf<String?>(null)
        private set
    private var landingID: String? = null
    private var videoID: String? = null
    private var pictureShown = true

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED && !onEnded()) seek(0)
            }
            override fun onRenderedFirstFrame() { renderedVideoID = videoID }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) savePlace() }
        })
    }

    /** The song this page was on when the viewer left, if any. */
    fun savedPlace(landingID: String): Pair<String, Long>? {
        val video = places.getString("$landingID.video", null) ?: return null
        return video to places.getLong("$landingID.ms", 0L)
    }

    fun load(landingID: String, videoID: String, url: String, startMs: Long) {
        if (videoID == this.videoID && landingID == this.landingID) return
        savePlace()
        this.landingID = landingID
        this.videoID = videoID
        positionMs = startMs
        durationMs = 0
        player.setMediaItem(MediaItem.fromUri(url), startMs)
        player.prepare()
    }

    fun setActive(active: Boolean, muted: Boolean) {
        player.volume = if (muted) 0f else 1f
        player.playWhenReady = active
    }

    /**
     * Nobody is watching the picture once the viewer has left the song's page, so
     * a stream with several qualities drops to its smallest. A plain file has only
     * the one and can't.
     */
    fun setPictureShown(shown: Boolean) {
        if (shown == pictureShown) return
        pictureShown = shown
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setForceLowestBitrate(!shown)
            .build()
    }

    fun seek(ms: Long) {
        player.seekTo(ms.coerceAtLeast(0L))
        positionMs = ms.coerceAtLeast(0L)
        savePlace()
    }

    fun stop() {
        savePlace()
        player.stop()
        player.clearMediaItems()
        landingID = null
        videoID = null
        renderedVideoID = null
    }

    fun tick() {
        if (!player.isPlaying) return
        positionMs = player.currentPosition
        if (durationMs <= 0) durationMs = player.duration.coerceAtLeast(0L)
        savePlace()
    }

    private fun savePlace() {
        val landing = landingID ?: return
        val video = videoID ?: return
        // Almost at the end isn't a place to come back to.
        val ms = if (durationMs > 0 && positionMs > durationMs - 3_000) 0L else positionMs
        places.edit().putString("$landing.video", video).putLong("$landing.ms", ms).apply()
    }
}

/**
 * Small enough to sit where the logo does. It takes the logo's place in Discovery
 * and rides at the top of every other screen. With no tab bar under the screen
 * it's a full-screen one, whose top-left corner is its close button.
 */
@Composable
fun MusicBar(modifier: Modifier = Modifier) {
    val song = MusicSession.current ?: return
    if (!MusicSession.barIsVisible) return
    val controller = MusicSession.song
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            // Level with the logo, which also takes no cutout padding.
            .padding(start = if (TabBarMetrics.clearance > 0.dp) 12.dp else 60.dp, top = 2.dp)
            // Stops short of the icons on Discovery's top row, on a narrow phone too.
            .size(
                width = minOf(MusicSession.barWidth, (LocalConfiguration.current.screenWidthDp - 176).dp),
                height = MusicSession.barHeight
            )
            .clip(shape)
            .background(Color(0xFF1C1C24).copy(alpha = 0.92f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
    ) {
        Row(Modifier.fillMaxSize().padding(start = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        LandingPresenter.open(
                            LandingPresenter.Screen.Guest(song.slug, null, LandingSource.PROFILE, asProfile = true)
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AsyncImage(
                    model = song.artworkURL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(26.dp).clip(CircleShape).background(LandingColors.coverFallback)
                )
                Text(song.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                modifier = Modifier.size(34.dp, 36.dp).clickable { MusicSession.togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (MusicSession.isAudible) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "${if (MusicSession.isAudible) "Pause" else "Play"} ${song.title}",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(
                modifier = Modifier.size(28.dp, 36.dp).clickable { MusicSession.dismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Stop the music", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            }
        }
        val fraction = if (controller != null && controller.durationMs > 0)
            (controller.positionMs.toFloat() / controller.durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.15f))
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
        }
    }
}
