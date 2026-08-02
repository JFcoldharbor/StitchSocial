package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.VideoCollection
import kotlinx.coroutines.delay

/**
 * A playable teaser for one collection.
 *
 * @param durationSeconds segment length when the doc carries it — drives where
 *   the teaser starts.
 */
data class CollectionHighlight(
    val id: String,
    val collectionID: String,
    val videoURL: String,
    val thumbnailURL: String?,
    val durationSeconds: Double?
) {
    /**
     * Where to start the teaser. Openings are titles, logos and throat-clearing
     * — the point of a hero is to make a show look good, so it cuts in around a
     * third of the way through, where something is actually happening. Falls
     * back to a small offset when the duration is unknown, which still beats
     * frame zero.
     */
    fun teaserStart(clipLength: Double): Double {
        val d = durationSeconds
        if (d == null || d <= clipLength + 2) return 2.0
        return minOf(d * 0.35, d - clipLength - 1)
    }
}

/**
 * The Collections hero — an auto-playing rotator (iOS parity with
 * CollectionsHeroRotator).
 *
 * Android's hero was Continue Watching: a still cover, a progress bar and a
 * resume button. That handed the biggest, loudest slot on the surface to the one
 * thing the viewer had ALREADY chosen, and showed a viewer with nothing in
 * progress a hero that couldn't render at all. Nothing on the surface ever moved,
 * so nothing sold a show to someone who hadn't started one.
 *
 * Now it rotates muted, auto-playing teasers. Resume didn't move away — it's the
 * whole Watching tab plus the in-progress rail.
 *
 * CLIPS ONLY on Android for now: iOS alternates in first-party sponsored slots,
 * which don't exist here yet. The rotation is the seam they'd slot into.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun CollectionsHeroRotator(
    highlights: List<Pair<CollectionHighlight, VideoCollection>>,
    onOpen: (VideoCollection) -> Unit,
    modifier: Modifier = Modifier
) {
    if (highlights.isEmpty()) return

    // Long enough to register, short enough to keep moving. Matches the clip
    // length so a teaser plays its whole cut once and rotates on the boundary
    // rather than being cut off mid-scene.
    val clipLength = 30.0

    var index by remember(highlights.size) { mutableStateOf(0) }
    val (highlight, collection) = highlights[index.coerceIn(0, highlights.lastIndex)]

    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f          // a hero that makes noise unprompted is a hero people scroll past
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    // Rotate.
    LaunchedEffect(index, highlights.size) {
        delay((clipLength * 1000).toLong())
        index = (index + 1) % highlights.size
    }

    // Load the current teaser, seeking past the opening.
    LaunchedEffect(highlight.id) {
        player.setMediaItem(MediaItem.fromUri(highlight.videoURL))
        player.prepare()
        player.seekTo((highlight.teaserStart(clipLength) * 1000).toLong())
        player.playWhenReady = true
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(190.dp)
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
            .clickable { onOpen(collection) }
    ) {
        // Poster underneath, so the slot is never empty while the first frame
        // decodes — the gap is exactly when a viewer decides to scroll on.
        highlight.thumbnailURL?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Legibility scrim for the title.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.75f)
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
        ) {
            Text(
                collection.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                collection.creatorName,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 1
            )
        }

        // Position dots.
        if (highlights.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                highlights.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == index) Color.White else Color.White.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }
    }
}

/** Text alias so this file doesn't pull the whole material3 namespace. */
@Composable
private fun Text(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE
) = androidx.compose.material3.Text(
    text = text, color = color, fontSize = fontSize,
    fontWeight = fontWeight, maxLines = maxLines
)
