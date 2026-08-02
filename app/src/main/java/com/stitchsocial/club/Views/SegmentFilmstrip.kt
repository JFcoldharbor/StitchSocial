package com.stitchsocial.club.views

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cut lane — a CapCut-style filmstrip: real frames from the video laid out
 * along the timeline with segment boundaries drawn on top (iOS parity with
 * SegmentFilmstrip.swift).
 *
 * The alternative is flat coloured blocks, which tell you how MANY parts you
 * have and roughly how long each is, but nothing about what's IN them — so
 * placing a cut is guesswork, and that's what makes manual splitting feel like a
 * fight.
 *
 * Frame extraction is deliberately cheap: a FIXED number of samples regardless
 * of video length, downscaled hard, using OPTION_CLOSEST_SYNC so the decoder can
 * hand back the nearest keyframe instead of decoding to an exact one. A
 * 40-minute 4K video costs the same as a 2-minute one.
 */
class FilmstripGenerator {

    private var loadedKey: String? = null

    /** Enough to read the video at a glance; small enough to stay cheap. */
    private val sampleCount = 14

    suspend fun load(context: Context, uri: Uri, durationMs: Long): List<Bitmap> =
        withContext(Dispatchers.IO) {
            val key = "$uri#${durationMs / 1000}"
            if (key == loadedKey || durationMs <= 0) return@withContext emptyList()

            val retriever = MediaMetadataRetriever()
            val out = mutableListOf<Bitmap>()
            try {
                retriever.setDataSource(context, uri)
                val step = durationMs / sampleCount
                for (i in 0 until sampleCount) {
                    val us = (i * step) * 1000
                    // CLOSEST_SYNC, not CLOSEST: exact-frame extraction decodes
                    // forward from the previous keyframe, which on a long video
                    // is the difference between instant and tens of seconds.
                    val frame = retriever.getScaledFrameAtTime(
                        us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 160
                    ) ?: continue
                    out += frame
                }
                loadedKey = key
            } catch (_: Exception) {
                // A filmstrip is an aid, not a requirement — cutting still works
                // without it, so a failure here must never block the composer.
            } finally {
                runCatching { retriever.release() }
            }
            out
        }
}

/**
 * The lane itself: frames underneath, boundaries on top, drag to move a cut.
 *
 * @param boundariesMs cut positions BETWEEN segments — never includes 0 or the
 *   end, since those aren't movable and offering a handle for them implies they
 *   are.
 */
@Composable
fun SegmentFilmstripLane(
    frames: List<Bitmap>,
    durationMs: Long,
    boundariesMs: List<Long>,
    onBoundaryMoved: (index: Int, newMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val laneHeight = 64.dp
    var laneWidthPx by remember { mutableStateOf(1f) }
    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxWidth()
            .height(laneHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1F))
    ) {
        // Frames.
        Row(Modifier.fillMaxSize()) {
            if (frames.isEmpty()) {
                // Placeholder rather than an empty box, so the lane has the
                // right shape while frames decode.
                repeat(14) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF212128)))
                    Spacer(Modifier.width(1.dp))
                }
            } else {
                frames.forEach { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }

        // Boundaries.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(boundariesMs, durationMs) {
                    laneWidthPx = size.width.toFloat()
                    detectHorizontalDragGestures { change, _ ->
                        if (durationMs <= 0 || boundariesMs.isEmpty()) return@detectHorizontalDragGestures
                        val x = change.position.x.coerceIn(0f, size.width.toFloat())
                        val ms = ((x / size.width) * durationMs).toLong()
                        // Move whichever cut is nearest the finger. Grabbing by
                        // proximity rather than requiring a hit on a 2dp line is
                        // the difference between adjustable and fiddly.
                        val nearest = boundariesMs.indices.minByOrNull {
                            kotlin.math.abs(boundariesMs[it] - ms)
                        } ?: return@detectHorizontalDragGestures
                        onBoundaryMoved(nearest, ms)
                        change.consume()
                    }
                }
        ) {
            boundariesMs.forEach { ms ->
                val fraction = if (durationMs > 0) ms.toFloat() / durationMs else 0f
                Box(
                    Modifier
                        .offset(x = with(density) { (fraction * laneWidthPx).toDp() })
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFE91E63))
                )
            }
        }
    }
}
