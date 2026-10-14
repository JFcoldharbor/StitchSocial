package com.stitchsocial.club.views

/*
 * StitchChainRail.kt — mirrors iOS Overlays/StitchChainRail.swift (mockup 8a).
 *
 * The thread drawn up the right edge: who started it, where you are, what's
 * next, and a dashed open slot at the end that is the invitation to stitch.
 * The clip you're watching is portrait and ringed in the Stitch accent; the
 * others are small squares. "N of M" underneath. Lavender is the Stitch accent
 * and nothing else uses it.
 *
 * It replaces the purple "thread creator" pill that used to float top-left
 * when you swiped into somebody's reply — the lineage is here now, and the
 * "on X's thread" credit is a line in the creator block.
 *
 * Drawn by ContextualVideoOverlay, which reads a thread once and shares it
 * through ThreadChainCache — no listener.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CoreVideoMetadata
import java.util.concurrent.ConcurrentHashMap

/** One clip in the lineage. */
internal data class StitchChainRailLink(val id: String, val thumbnailURL: String?)

private object RailStyle {
    val tileSide = 44.dp
    /** The current clip is portrait — it's the one you're watching. */
    val currentWidth = 52.dp
    val currentHeight = 68.dp
    /** The current tile plus the 4dp of room its halo ring is drawn into. */
    val currentSlotHeight = currentHeight + 8.dp
    val columnWidth = 58.dp
    val gap = 7.dp
    /** Three tiles and a little air. */
    val railHeight = currentHeight + 96.dp
}

/**
 * The lineage the rail draws — must match the order the players page in: the
 * head first, then its direct stitches ranked by net score (hype − cool), newer
 * winning ties. Two orderings of one thread put the highlight on the wrong tile.
 */
internal fun buildStitchChain(parent: CoreVideoMetadata?, children: List<CoreVideoMetadata>): List<StitchChainRailLink> {
    parent ?: return emptyList()
    val depth = parent.conversationDepth
    val ranked = children
        .filter { it.conversationDepth == depth + 1 }
        .sortedWith(compareByDescending<CoreVideoMetadata> { it.hypeCount - it.coolCount }
            .thenByDescending { it.createdAt })
    return (listOf(parent) + ranked).map { StitchChainRailLink(it.id, it.thumbnailURL.ifBlank { null }) }
}

/**
 * Thread data for the rail, shared across overlays so paging through one
 * thread reads it once. Five minutes, like the overlay's user cache.
 */
internal object ThreadChainCache {
    private data class Entry(val parent: CoreVideoMetadata?, val children: List<CoreVideoMetadata>, val at: Long)
    private val map = ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 300_000L

    fun get(threadID: String): Pair<CoreVideoMetadata?, List<CoreVideoMetadata>>? {
        val e = map[threadID] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) { map.remove(threadID); return null }
        return e.parent to e.children
    }

    fun put(threadID: String, parent: CoreVideoMetadata?, children: List<CoreVideoMetadata>) {
        map[threadID] = Entry(parent, children, System.currentTimeMillis())
    }
}

@Composable
internal fun StitchChainRail(
    chain: List<StitchChainRailLink>,
    currentID: String,
    onTapLink: (StitchChainRailLink) -> Unit,
    onTapOpenSlot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentIndex = chain.indexOfFirst { it.id == currentID }.let { if (it < 0) maxOf(0, chain.size - 1) else it }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val halfRail = RailStyle.railHeight / 2

    // Top to bottom, as iOS draws it: the head first, then the stitches, then
    // the open slot. The current clip is centred in the window by its measured
    // position (every tile has a fixed height), so the order can never be
    // flipped by where a lazy list decides to anchor. Half a rail of space at
    // either end lets the first and last clips centre too. The strip can still
    // be dragged to look along the thread; tapping a tile is what commits.
    val centreOffset = remember(chain, currentIndex) {
        var y = 0.dp
        for (i in 0 until currentIndex) y += RailStyle.tileSide + RailStyle.gap
        y + RailStyle.currentSlotHeight / 2
    }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(currentID, chain.size) {
        val target = with(density) { centreOffset.roundToPx() }
        if (placed) scrollState.animateScrollTo(target) else { scrollState.scrollTo(target); placed = true }
    }

    Column(
        modifier = modifier.width(RailStyle.columnWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "THREAD",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 10.fixedSp(),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.fixedSp(),
            modifier = Modifier.padding(bottom = 9.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(RailStyle.railHeight)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RailStyle.gap)
        ) {
            Spacer(Modifier.height(halfRail - RailStyle.gap))
            chain.forEach { link ->
                key(link.id) {
                    if (link.id == currentID) CurrentTile(link) else Tile(link) { onTapLink(link) }
                }
            }
            OpenSlot(onTapOpenSlot)
            Spacer(Modifier.height(halfRail - RailStyle.gap))
        }
        Text(
            "${currentIndex + 1} of ${chain.size}",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.fixedSp(),
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 9.dp)
        )
    }
}

/** The mockup's diagonal hatch: a clip with no thumbnail still reads as a clip. */
private fun Modifier.railHatch(light: Color, dark: Color) = this.drawBehind {
    drawRect(dark)
    val step = 12.dp.toPx()
    var x = -size.height
    while (x < size.width + size.height) {
        drawLine(light, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = step / 2)
        x += step
    }
}

@Composable
private fun RailFill(url: String?, light: Color, dark: Color) {
    Box(Modifier.fillMaxSize().railHatch(light, dark)) {
        if (!url.isNullOrEmpty()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Tile(link: StitchChainRailLink, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(RailStyle.tileSide)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .clickable(onClick = onClick)
    ) {
        RailFill(link.thumbnailURL, Color(0xFF33343F), Color(0xFF282932))
    }
}

@Composable
private fun CurrentTile(link: StitchChainRailLink) {
    val shape = RoundedCornerShape(9.dp)
    // A halo ring (accent at 16%) just outside the 2dp accent border, so it
    // reads as a held position on the thread rather than a blur. Drawn outside
    // the tile's bounds so the column width stays 58dp.
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(RailStyle.currentWidth, RailStyle.currentHeight)
            .drawBehind {
                val spread = 3.dp.toPx()
                drawRoundRect(
                    color = StitchChainAccent.copy(alpha = 0.16f),
                    topLeft = Offset(-spread, -spread),
                    size = Size(size.width + spread * 2, size.height + spread * 2),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = spread)
                )
            }
            .clip(shape)
            .border(2.dp, StitchChainAccent, shape)
    ) {
        RailFill(link.thumbnailURL, Color(0xFF3B3546), Color(0xFF2E2937))
    }
}

@Composable
private fun OpenSlot(onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(RailStyle.tileSide)
            .clip(shape)
            .background(StitchChainAccent.copy(alpha = 0.08f))
            .drawBehind {
                drawRoundRect(
                    color = StitchChainAccent.copy(alpha = 0.8f),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Add, contentDescription = "Stitch your clip onto this thread", tint = StitchChainAccent, modifier = Modifier.size(20.dp))
    }
}
