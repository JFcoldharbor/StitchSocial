package com.stitchsocial.club.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import com.stitchsocial.club.foundation.CoreVideoMetadata

/*
 * StitchPager.kt — the sideways swipe between videos, the same way the vertical
 * feed pages: Discover's and Home's stitches, and a profile's videos.
 *
 * A HorizontalPager whose neighbours are live players paused on their first
 * frame. The next one slides in under the finger while the current one keeps
 * playing, and takes over when the swipe settles — no cover, no ▶︎. The three
 * screens each had a hand-built drag over still images instead.
 *
 * Two details that matter on Android:
 *  - Each page is clipped and draws into a TextureView. A 9:16 video filling a
 *    taller screen is wider than its page; unclipped, the next video's overflow
 *    painted over the right edge of the current one, and a SurfaceView ignores
 *    clipping.
 *  - Neighbours are composed only for the screen the user is on
 *    (`isOnScreen`): each one is a decoder, and a budget phone has few.
 *
 * The overlay is NOT drawn here. Callers draw one overlay over the pager for
 * `videos[index]`, so it stands still while the video slides (iOS parity).
 *
 * Layer 8: Views
 */
@Composable
fun StitchPager(
    videos: List<CoreVideoMetadata>,
    /** The settled page. Moving it (the thread rail, an edge peek) scrolls the pager there. */
    index: Int,
    onIndexChange: (Int) -> Unit,
    /** The screen this pager is on is the one showing (its feed page is current, its cover is up). */
    isOnScreen: Boolean,
    /** Paused for something drawn over it (an announcement, a sheet). */
    isPaused: Boolean = false,
    fitBandFor: (page: Int) -> MediaFitBand?,
    fitOverrideFor: (page: Int) -> String? = { null },
    /** True while a sideways swipe is moving — a vertical feed locks its own paging meanwhile. */
    onScrollingChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (videos.isEmpty()) return
    val pager = rememberPagerState(initialPage = index.coerceIn(0, videos.size - 1), pageCount = { videos.size })

    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != index) onIndexChange(pager.settledPage)
    }
    LaunchedEffect(index) {
        val target = index.coerceIn(0, videos.size - 1)
        if (pager.settledPage != target && !pager.isScrollInProgress) pager.animateScrollToPage(target)
    }
    LaunchedEffect(pager.isScrollInProgress) { onScrollingChange(pager.isScrollInProgress) }

    HorizontalPager(
        state = pager,
        beyondViewportPageCount = if (isOnScreen) 1 else 0,
        key = { videos[it].id },
        modifier = modifier.fillMaxSize()
    ) { page ->
        val video = videos[page]
        // The settled page plays; the one being swiped in waits on its first
        // frame until it lands, the way the vertical feed does.
        val active = isOnScreen && !isPaused && page == pager.settledPage
        var showPoster by remember(video.id) { mutableStateOf(true) }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            VideoPlayerComposable(
                video = video,
                isActive = active,
                modifier = Modifier.fillMaxSize(),
                tapToPause = true,
                onPlaybackStarted = { showPoster = false },
                onFirstFrameRendered = { showPoster = false },
                textureSurface = true,
                fitBand = fitBandFor(page),
                fitOverride = fitOverrideFor(page)
            )
            // Only until the first frame is drawn — then the player itself is
            // the picture, paused or playing.
            if (showPoster) {
                VideoThumbnailPeek(video = video, modifier = Modifier.fillMaxSize(),
                    fitBand = fitBandFor(page), fitOverride = fitOverrideFor(page))
            }
            LaunchedEffect(video.id) {
                kotlinx.coroutines.delay(2000)
                showPoster = false
            }
        }
    }
}
