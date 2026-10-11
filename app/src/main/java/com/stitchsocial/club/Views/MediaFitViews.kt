/*
 * MediaFitViews.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Views — the shared drawing half of media fit (MEDIA_FIT_CONTRACT).
 *
 * The decision lives in foundation/MediaFit.kt. This file draws it:
 *   - MediaFitFrame: fill as today, or place the media in its fit rect over
 *     the blurred backdrop. The player, its posters and the sponsored / brand
 *     ad creatives all go through it, so a poster and the video it covers
 *     land in exactly the same rect and nothing jumps when playback starts.
 *   - MediaFitBackdrop: the poster shrunk to ~24 px (BlurredPosterCache),
 *     drawn back up with bilinear filtering, 30% black over it.
 *   - TabBarMetrics: the tab bar's measured height, the band's bottom edge.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.stitchsocial.club.foundation.MediaFit
import com.stitchsocial.club.foundation.MediaFitMode
import com.stitchsocial.club.services.BlurredPosterCache
import kotlin.math.roundToInt

/**
 * Where fit-mode media may sit, as insets from the edges of the media's own
 * frame (normally the whole screen).
 *
 *  - The band (creator posts): top = bottom of the screen's top chrome,
 *    bottom = top of the tab bar (or the bottom safe area), full width.
 *  - The clear area (sponsored posts, brand ads): top chrome down to the top
 *    of the card's bottom block, 12dp in from each side.
 */
@Immutable
data class MediaFitBand(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val horizontal: Dp = 0.dp
) {
    companion object {
        fun clearArea(top: Dp, bottom: Dp): MediaFitBand =
            MediaFitBand(top = top, bottom = bottom, horizontal = MediaFit.CLEAR_AREA_SIDE_INSET_DP.dp)
    }
}

/**
 * The tab bar's measured height while it is on screen, 0 when it isn't.
 * Written by CustomDippedTabBar; read as the band's bottom edge. The system
 * bars are hidden app-wide (MainActivity), so there is no other inset below it.
 */
object TabBarMetrics {
    var clearance by mutableStateOf(0.dp)
}

/**
 * Fill or fit, decided by [MediaFit.mode].
 *
 * Fill: [content] fills the frame, exactly as before (callers keep cropping).
 * Fit: the backdrop fills the frame and [content] is laid out in the fit rect
 * inside [band]; `isFit` tells it to letterbox rather than crop.
 *
 * A null [band] always fills — surfaces that keep filling (grid tiles, the
 * carousel, collections) simply don't pass one.
 */
@Composable
fun MediaFitFrame(
    aspect: Double?,
    fitOverride: String?,
    band: MediaFitBand?,
    backdropURL: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(isFit: Boolean) -> Unit
) {
    val mode = if (band == null) MediaFitMode.FILL else MediaFit.mode(aspect, fitOverride)
    if (mode == MediaFitMode.FILL || band == null) {
        Box(modifier = modifier) { content(false) }
        return
    }

    BoxWithConstraints(modifier = modifier) {
        MediaFitBackdrop(url = backdropURL, modifier = Modifier.fillMaxSize())

        if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            val density = LocalDensity.current
            val rect = with(density) {
                val side = band.horizontal.toPx()
                val top = band.top.toPx()
                MediaFit.fitRect(
                    aspect,
                    left = side,
                    top = top,
                    width = (constraints.maxWidth - 2 * side).coerceAtLeast(0f),
                    height = (constraints.maxHeight - top - band.bottom.toPx()).coerceAtLeast(0f)
                )
            }
            val widthDp = with(density) { rect.width.toDp() }
            val heightDp = with(density) { rect.height.toDp() }
            Box(
                modifier = Modifier
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(widthDp, heightDp)
            ) { content(true) }
        } else {
            Box(modifier = Modifier.fillMaxSize()) { content(true) }
        }
    }
}

/**
 * The blurred, dimmed copy behind fitted media. Black until the shrunk poster
 * is ready; cached per URL, so a feed of fitted posts builds each one once.
 */
@Composable
fun MediaFitBackdrop(url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf(url?.let { BlurredPosterCache.peek(it) }) }
    LaunchedEffect(url) {
        if (bitmap == null && !url.isNullOrBlank()) {
            bitmap = BlurredPosterCache.load(context, url)
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }

    Box(modifier = modifier.background(Color.Black)) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Low = bilinear. The smoothing of a 24-px image IS the blur.
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = MediaFit.BACKDROP_DIM))
        )
    }
}
