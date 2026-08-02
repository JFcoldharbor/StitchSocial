package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CollectionStatus
import com.stitchsocial.club.foundation.Show
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.services.ShowService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private object HUB {
    val cyan = Color(0xFF22D3EE)
    val green = Color(0xFF4ADE80)
    val pink = Color(0xFFF0245F)
    val bg = Color(0xFF0B0B0D)
    val card = Color(0xFF141418)
    val hairline = Color(0x1FFFFFFF)
}

/**
 * Episode state — DERIVED, never stored.
 *
 * Storing it would give a third overlapping publish state on top of `status` and
 * `publishedAt`, and they'd drift the first time one was written without the
 * other.
 */
private enum class EpisodeState {
    NO_VIDEO, SCHEDULED, PUBLISHED;

    companion object {
        fun of(ep: VideoCollection): EpisodeState = when {
            ep.status == CollectionStatus.PUBLISHED -> PUBLISHED
            ep.segmentCount == 0 -> NO_VIDEO
            else -> SCHEDULED
        }
    }
}

/**
 * Show Hub — the screen a creator returns to every week (iOS parity with
 * ShowHubView, design_handoff_show_flow §1, screen 1a-1).
 *
 * What's live, what's scheduled, what the next empty slot is, and one tap to
 * fill it.
 *
 * Three things it fixes, all of which Android's ShowListView still has:
 *
 * 1. FOUR SAVE ACTIONS → AUTOSAVE. The old editor had Save, Save Episode,
 *    Finalize, and a schedule write on every cadence tap. Here edits debounce
 *    into a write and a "Saved" indicator replaces the buttons.
 * 2. EPISODES BURIED IN AN ACCORDION → a flat list, always visible. Season 1 is
 *    silent; the season switcher appears only when there's more than one, so
 *    one-off shows never see season UI.
 * 3. SCHEDULE CONFIGURED BEFORE CONTENT EXISTS → the hub shows the CONSEQUENCE
 *    of the cadence ("Next drop · Tue Aug 4"), not its configuration.
 *
 * Show status is DERIVED: a show with at least one published episode is live.
 */
@Composable
fun ShowHubView(
    showId: String,
    onBack: () -> Unit,
    onOpenEpisode: (VideoCollection) -> Unit,
    onNewEpisode: (seasonId: String, episodeNumber: Int) -> Unit,
    onOpenSettings: (Show) -> Unit
) {
    var show by remember { mutableStateOf<Show?>(null) }
    var episodesBySeason by remember { mutableStateOf<Map<String, List<VideoCollection>>>(emptyMap()) }
    var seasonIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var visibleSeasonId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(showId) {
        isLoading = true
        val (s, seasons, eps) = ShowService.shared.loadFullShow(showId)
        show = s
        seasonIds = seasons.map { it.id }
        episodesBySeason = eps
        visibleSeasonId = seasons.firstOrNull()?.id
        isLoading = false
    }

    val allEpisodes = remember(episodesBySeason) { episodesBySeason.values.flatten() }
    val visibleEpisodes = remember(episodesBySeason, visibleSeasonId) {
        (episodesBySeason[visibleSeasonId] ?: emptyList())
            .sortedBy { it.episodeNumber ?: 0 }
    }
    // Derived, not creator-set: anything published means the show is live.
    val isLive = allEpisodes.any { it.status == CollectionStatus.PUBLISHED }
    val nextSlot = remember(show, allEpisodes) {
        show?.let { ShowService.shared.nextAvailableSlot(it, allEpisodes) }
    }
    val nextEpisodeNumber = (allEpisodes.mapNotNull { it.episodeNumber }.maxOrNull() ?: 0) + 1

    Column(Modifier.fillMaxSize().background(HUB.bg)) {

        // Nav bar. No Save button — autosave means there's nothing to press.
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 44.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ArrowBack, "Back", tint = Color.White,
                modifier = Modifier.size(22.dp).clickable { onBack() }
            )
            Spacer(Modifier.weight(1f))
            show?.let { s ->
                Icon(
                    Icons.Default.Settings, "Show settings", tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(20.dp).clickable { onOpenSettings(s) }
                )
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = HUB.pink)
            }
            return@Column
        }

        val s = show ?: run {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Show not found", color = Color.White.copy(alpha = 0.6f))
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {

            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                    s.coverImageURL?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 74.dp, height = 100.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title.ifBlank { "Untitled show" },
                            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isLive) "LIVE" else "DRAFT",
                            color = if (isLive) HUB.green else Color.White.copy(alpha = 0.45f),
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${allEpisodes.size} episode${if (allEpisodes.size == 1) "" else "s"}",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // The CONSEQUENCE of the cadence, not its configuration.
            nextSlot?.let { slot ->
                item {
                    val fmt = SimpleDateFormat("EEE MMM d", Locale.getDefault())
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(HUB.cyan.copy(alpha = 0.08f))
                            .border(1.dp, HUB.cyan.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                            .clickable { visibleSeasonId?.let { onNewEpisode(it, nextEpisodeNumber) } }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Next drop · ${fmt.format(slot)}", color = Color.White,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Episode $nextEpisodeNumber · tap to fill this slot",
                                color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                        }
                        Icon(Icons.Default.Add, null, tint = HUB.cyan, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Season switcher ONLY when there's more than one — a one-off show
            // should never see season UI it didn't ask for.
            if (seasonIds.size > 1) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        seasonIds.forEachIndexed { i, id ->
                            val selected = id == visibleSeasonId
                            Text(
                                "S${i + 1}",
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) HUB.pink else Color.Transparent)
                                    .clickable { visibleSeasonId = id }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (visibleEpisodes.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No episodes yet", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Record one and it lands here.",
                            color = Color.White.copy(alpha = 0.38f), fontSize = 12.sp
                        )
                    }
                }
            }

            items(visibleEpisodes, key = { it.id }) { ep ->
                EpisodeRow(ep, nextSlot) { onOpenEpisode(ep) }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun EpisodeRow(ep: VideoCollection, slot: Date?, onClick: () -> Unit) {
    val state = EpisodeState.of(ep)
    val meta = when (state) {
        EpisodeState.NO_VIDEO ->
            slot?.let { "No video yet · slot ${SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(it)}" }
                ?: "No video yet"
        EpisodeState.SCHEDULED -> {
            val p = ep.publishedAt
            if (p == null) "${ep.segmentCount} segments"
            else {
                val days = TimeUnit.MILLISECONDS.toDays(
                    (p.time - System.currentTimeMillis()).coerceAtLeast(0)
                )
                "${ep.segmentCount} segments · premieres in $days day${if (days == 1L) "" else "s"}"
            }
        }
        EpisodeState.PUBLISHED -> "${ep.segmentCount} segments · ${ep.totalViews} views"
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(HUB.card)
            .border(0.5.dp, HUB.hairline, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "E${ep.episodeNumber ?: 0}",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(30.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                ep.title.ifBlank { "Untitled episode" },
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
            Text(
                meta,
                color = if (state == EpisodeState.SCHEDULED) HUB.cyan.copy(alpha = 0.9f)
                        else Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        if (state == EpisodeState.PUBLISHED) {
            Text("LIVE", color = HUB.green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}
