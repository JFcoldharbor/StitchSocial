/*
 * CollectionsBrowseView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views — Discover -> Collections, rebuilt as a full-screen takeover.
 *
 * Port of iOS CollectionsBrowseView.swift at its END state (06e813b ... 722c26d),
 * not the original handoff design. iOS shipped the shows/seasons/binge framing
 * first and then reframed it: Discover collections are standalone, not
 * multi-episode shows, so grouping them by showId was pretending. The surface is
 * now find-by-type:
 *
 *  - Continue Watching hero + EPISODES IN PROGRESS rail lead (real progress).
 *  - BY TYPE chips; selecting one swaps the rails for a grid of that type.
 *  - Otherwise one rail per content type (Podcast, Series, Film...), each a row
 *    of playable collection cards.
 *  - Every card plays the collection directly — no empty show-detail stop.
 *
 * iOS left its dead shows-grouping code in place ("harmless pending cleanup");
 * this port simply doesn't carry it over.
 *
 * The core finding from the handoff holds on Android too: CollectionProgress
 * (currentSegmentIndex, currentTimestamp, percentComplete, resumePromptText) was
 * already written by CollectionPlayerViewModel and simply never surfaced. This
 * is mostly exposing shipped data, not new backend work.
 *
 * Progress is episode-scoped (CollectionProgress.id = "{userID}_{collectionID}"),
 * so every resume string here describes ONE episode — never a whole show. That's
 * why the meta reads "38% of this episode".
 *
 * Playback is delegated: this view calls onPlay(collection, startIndex) and
 * DiscoveryView's existing CollectionPlayerView overlay takes it from there.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CollectionContentType
import com.stitchsocial.club.foundation.CollectionProgress
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.services.CollectionService

private object COL {
    val bg = Color(0xFF0A0A0D)
    val pink = Color(0xFFF0245F)
    val cyan = Color(0xFF22D3EE)
    val ink = Color(0xFF0A0A0D)
    val cardW = 104.dp
    val coverH = 185.dp // 104 * 16/9 — covers are locked 9:16
}

/** Mirrors iOS ShowCard.colorFor, mapped onto Android's CollectionContentType cases. */
internal fun collectionTypeColor(type: CollectionContentType): Color = when (type) {
    CollectionContentType.PODCAST -> Color(0xFFF59E0B) // orange
    CollectionContentType.FILM -> Color(0xFFA78BFA)    // purple
    CollectionContentType.SERIES -> Color(0xFFF0245F)  // pink
    CollectionContentType.COURSE -> Color(0xFF10B981)  // green
    CollectionContentType.EVENT -> Color(0xFF22D3EE)   // cyan
    CollectionContentType.GENERAL -> Color(0xFF3B82F6) // blue
}

private enum class BrowseMode { BROWSE, WATCHING }

private data class ProgressPair(val ep: VideoCollection, val progress: CollectionProgress)

@Composable
fun CollectionsBrowseView(
    collections: List<VideoCollection>,
    userID: String,
    onClose: () -> Unit,
    /** Play a collection, resuming at [startIndex] (the segment index). */
    onPlay: (VideoCollection, Int) -> Unit,
) {
    var mode by remember { mutableStateOf(BrowseMode.BROWSE) }
    var selectedType by remember { mutableStateOf<CollectionContentType?>(null) }
    var progressByID by remember { mutableStateOf<Map<String, CollectionProgress>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    // Full-screen takeover → pause the Discovery deck underneath so its video
    // audio doesn't bleed through (same as CommunityListView / iOS
    // pauseAllPlayback).
    LaunchedEffect(Unit) { com.stitchsocial.club.VideoManager.pauseAllPlayers() }

    // Progress is per-collection; CollectionService caches, so this settles fast
    // on repeat opens. Only what's on screen is fetched.
    LaunchedEffect(collections.map { it.id }) {
        val service = CollectionService()
        val result = mutableMapOf<String, CollectionProgress>()
        for (ep in collections) {
            runCatching { service.getWatchProgress(userID, ep.id) }
                .getOrNull()?.let { result[ep.id] = it }
        }
        progressByID = result
    }

    val inProgress = remember(collections, progressByID) {
        collections.mapNotNull { ep ->
            progressByID[ep.id]?.takeIf { it.isInProgress }?.let { ProgressPair(ep, it) }
        }.sortedByDescending { it.progress.lastWatchedAt }
    }
    val resumeItem = inProgress.firstOrNull()

    val searched = remember(collections, query) {
        if (query.isBlank()) collections
        else collections.filter { it.title.contains(query, ignoreCase = true) }
    }
    val contentTypes = remember(searched) {
        searched.map { it.contentType }.distinct().sortedBy { it.displayName }
    }

    Box(modifier = Modifier.fillMaxSize().background(COL.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header: close · Browse/Watching toggle · search ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircleIconButton(Icons.Default.Close, "Close", onTap = onClose)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TogglePill("Browse", mode == BrowseMode.BROWSE, Modifier.weight(1f)) {
                        mode = BrowseMode.BROWSE
                    }
                    TogglePill(
                        "Watching · ${inProgress.size}",
                        mode == BrowseMode.WATCHING,
                        Modifier.weight(1f),
                    ) { mode = BrowseMode.WATCHING }
                }
                CircleIconButton(Icons.Default.Search, "Search") {
                    searching = !searching
                    if (!searching) query = ""
                }
            }

            if (searching) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (mode == BrowseMode.BROWSE) {
                    if (resumeItem != null && query.isBlank()) {
                        SectionLabel(
                            "CONTINUE WATCHING",
                            Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
                        )
                        ContinueHero(
                            ep = resumeItem.ep,
                            progress = resumeItem.progress,
                            onPlay = { onPlay(resumeItem.ep, resumeItem.progress.currentSegmentIndex) },
                        )
                    }

                    if (contentTypes.isNotEmpty()) {
                        ByTypeChips(
                            types = contentTypes,
                            selected = selectedType,
                            onSelect = { t -> selectedType = if (selectedType == t) null else t },
                        )
                    }

                    if (inProgress.isNotEmpty() && query.isBlank()) {
                        RailHeader("EPISODES IN PROGRESS")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            items(inProgress, key = { it.ep.id }) { pair ->
                                EpisodeCard(
                                    ep = pair.ep,
                                    progress = pair.progress,
                                    onTap = { onPlay(pair.ep, pair.progress.currentSegmentIndex) },
                                )
                            }
                        }
                    }

                    val type = selectedType
                    if (type != null) {
                        // Filtered to one type → a grid of just those collections.
                        val items = searched.filter { it.contentType == type }
                        CollectionGrid(items = items, onPlay = { onPlay(it, 0) })
                    } else {
                        // One rail per content type, each a row of playable cards.
                        contentTypes.forEach { t ->
                            val items = searched.filter { it.contentType == t }
                            if (items.isNotEmpty()) {
                                RailHeader(t.displayName.uppercase())
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(top = 10.dp),
                                ) {
                                    items(items, key = { it.id }) { ep ->
                                        CollectionCard(ep = ep, onTap = { onPlay(ep, 0) })
                                    }
                                }
                            }
                        }
                    }

                    if (searched.isEmpty()) {
                        EmptyState(if (query.isBlank()) "No collections yet" else "Nothing matches \"$query\"")
                    }
                } else {
                    // ── Watching tab ──
                    if (inProgress.isEmpty()) {
                        EmptyState("Nothing in progress")
                    } else {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            inProgress.forEach { pair ->
                                WatchingRow(
                                    ep = pair.ep,
                                    progress = pair.progress,
                                    onTap = { onPlay(pair.ep, pair.progress.currentSegmentIndex) },
                                )
                            }
                        }
                    }
                }

                // Clear the system nav / gesture bar — Collections is full screen,
                // so nothing else is covering this edge.
                Spacer(Modifier.height(34.dp).navigationBarsPadding())
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Continue Watching hero
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ContinueHero(
    ep: VideoCollection,
    progress: CollectionProgress,
    onPlay: () -> Unit,
) {
    val tint = collectionTypeColor(ep.contentType)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(COL.pink.copy(alpha = 0.06f))
            .border(1.dp, COL.pink.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(158.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onPlay() },
        ) {
            CoverBackground(ep.coverImageURL, tint, Modifier.fillMaxSize())

            Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                ContentTypeBadge(ep.contentType, tint, Modifier.align(Alignment.TopStart))
                CountBadge("${ep.segmentCount} segments", Modifier.align(Alignment.BottomEnd))
            }

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }

            // Progress bar pinned to the bottom.
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.percentComplete.toFloat().coerceIn(0f, 1f))
                    .height(3.dp)
                    .align(Alignment.BottomStart)
                    .background(COL.pink),
            )
        }

        Text(
            ep.episodeDisplayTitle,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Episode-scoped by construction — progress only ever describes one episode.
        Text(
            "Part ${progress.currentSegmentIndex + 1} of ${ep.segmentCount} · " +
                "${(progress.percentComplete * 100).toInt()}% of this episode · " +
                "${progress.daysSinceLastWatch}d ago",
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 9.sp,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(COL.pink)
                .clickable { onPlay() }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                progress.resumePromptText,
                color = Color.White,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Cards
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CollectionCard(ep: VideoCollection, onTap: () -> Unit) {
    val tint = collectionTypeColor(ep.contentType)
    Column(
        modifier = Modifier.width(COL.cardW).clickable { onTap() },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = COL.cardW, height = COL.coverH)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            CoverBackground(ep.coverImageURL, tint, Modifier.fillMaxSize())
            Box(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                ContentTypeBadge(ep.contentType, tint, Modifier.align(Alignment.TopStart))
                CountBadge("${ep.segmentCount} seg", Modifier.align(Alignment.BottomEnd))
            }
        }
        Text(
            ep.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${ep.segmentCount} segment${if (ep.segmentCount == 1) "" else "s"} · ${viewCount(ep.totalViews)} views",
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EpisodeCard(ep: VideoCollection, progress: CollectionProgress, onTap: () -> Unit) {
    val tint = collectionTypeColor(ep.contentType)
    Column(
        modifier = Modifier.width(COL.cardW).clickable { onTap() },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = COL.cardW, height = COL.coverH)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            CoverBackground(ep.coverImageURL, tint, Modifier.fillMaxSize())
            Box(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                ContentTypeBadge(ep.contentType, tint, Modifier.align(Alignment.TopStart))
                CountBadge("${ep.segmentCount} seg", Modifier.align(Alignment.BottomEnd))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.percentComplete.toFloat().coerceIn(0f, 1f))
                    .height(3.dp)
                    .align(Alignment.BottomStart)
                    .background(COL.pink),
            )
        }
        Text(
            ep.episodeDisplayTitle,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Part ${progress.currentSegmentIndex + 1} of ${ep.segmentCount} · from ${progress.formattedCurrentTimestamp}",
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CollectionGrid(items: List<VideoCollection>, onPlay: (VideoCollection) -> Unit) {
    // Fixed-height grid inside a vertical scroll: compute rows so the grid
    // reports a real height instead of trying to scroll within a scroll.
    val columns = 3
    val rows = (items.size + columns - 1) / columns
    val rowHeight = COL.coverH + 52.dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight * rows + 16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
    ) {
        items(items, key = { it.id }) { ep ->
            CollectionCard(ep = ep, onTap = { onPlay(ep) })
        }
    }
}

@Composable
private fun WatchingRow(ep: VideoCollection, progress: CollectionProgress, onTap: () -> Unit) {
    val tint = collectionTypeColor(ep.contentType)
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onTap() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 92.dp, height = 52.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            CoverBackground(ep.coverImageURL, tint, Modifier.fillMaxSize())
            Icon(
                Icons.Default.PlayArrow,
                null,
                tint = Color.White,
                modifier = Modifier.size(18.dp).align(Alignment.Center),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.percentComplete.toFloat().coerceIn(0f, 1f))
                    .height(3.dp)
                    .align(Alignment.BottomStart)
                    .background(COL.pink),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                ep.episodeDisplayTitle,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Part ${progress.currentSegmentIndex + 1} of ${ep.segmentCount} · from ${progress.formattedCurrentTimestamp}",
                color = COL.pink,
                fontSize = 10.sp,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bits
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ByTypeChips(
    types: List<CollectionContentType>,
    selected: CollectionContentType?,
    onSelect: (CollectionContentType) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("BY TYPE", Modifier.padding(horizontal = 14.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(types, key = { it.rawValue }) { type ->
                val c = collectionTypeColor(type)
                val sel = selected == type
                Text(
                    type.displayName,
                    color = if (sel) Color.White else c,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (sel) c else c.copy(alpha = 0.14f))
                        .border(1.dp, c.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                        .clickable { onSelect(type) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.40f),
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.7.sp,
        modifier = modifier,
    )
}

@Composable
private fun RailHeader(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(text)
        Spacer(Modifier.weight(1f))
        Text("See all", color = COL.cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CoverBackground(coverURL: String?, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(tint.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.7f)))
        )
    ) {
        if (!coverURL.isNullOrBlank()) {
            AsyncImage(
                model = coverURL,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ContentTypeBadge(type: CollectionContentType, tint: Color, modifier: Modifier = Modifier) {
    Text(
        type.displayName.uppercase(),
        color = Color.White,
        fontSize = 7.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.85f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun CountBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 8.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun TogglePill(label: String, selected: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable { onTap() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) COL.ink else Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(COL.cyan),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (query.isEmpty()) {
                    Text("Search collections", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                }
                inner()
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun EmptyState(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.VideoLibrary,
            null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(40.dp),
        )
        Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

private fun viewCount(n: Int): String =
    if (n >= 1000) String.format("%.1fk", n / 1000.0) else "$n"
