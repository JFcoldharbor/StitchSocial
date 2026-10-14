/*
 * ShowView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 6: Views — Show List, Show Editor (with Release Schedule section),
 *          Show Detail (with drag-reorder for owners), Premiere Date Picker
 *
 * Mirrors ShowListView.swift + ShowEditorView.swift + ShowDetailView.swift
 * + PremiereDatePicker.swift exactly.
 *
 * CACHING: All reads go through ShowService.shared.
 * BATCHING: Drag-reorder writes via ShowService.applyReorderedSchedule (1 batch).
 */

package com.stitchsocial.club.views

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.viewmodels.*
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────
// MARK: - ShowListView
// ─────────────────────────────────────────────────────────────────────

@Composable
fun ShowListView(
    creatorID: String,
    creatorName: String,
    onDismiss: () -> Unit,
    vm: ShowListViewModel = viewModel()
) {
    val shows by vm.shows.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    var showingNewShow by remember { mutableStateOf(false) }
    var selectedShow by remember { mutableStateOf<Show?>(null) }
    var settingsFor by remember { mutableStateOf<Show?>(null) }
    // showId / seasonId / episodeNumber for the composer.
    var composerFor by remember { mutableStateOf<Triple<String, String, Int>?>(null) }
    /** The existing episode the composer is finishing or adding parts to. */
    var composerEpisode by remember { mutableStateOf<VideoCollection?>(null) }

    LaunchedEffect(creatorID) { vm.loadShows(creatorID) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Done", color = Color.Cyan, fontSize = 15.sp) }
                Text("My Shows", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { if (com.stitchsocial.club.services.AccountStanding.guardPosting()) showingNewShow = true }) {
                    Icon(Icons.Default.AddCircle, "New Show", tint = Color(0xFFFF2D55))
                }
            }
            Divider(color = Color.White.copy(alpha = 0.06f))

            when {
                isLoading && shows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFF2D55))
                }
                shows.isEmpty() -> ShowEmptyState { if (com.stitchsocial.club.services.AccountStanding.guardPosting()) showingNewShow = true }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shows, key = { it.id }) { show ->
                        ShowRowCard(show = show) { selectedShow = show }
                    }
                }
            }
        }

        // New show sheet
        if (showingNewShow) {
            ShowSettingsView(
                initial = Show.newDraft(creatorID, creatorName),
                isNew = true,
                onSaved = { vm.insertShow(it); showingNewShow = false },
                onDismiss = { showingNewShow = false }
            )
        }

        // Tapping a show opens the HUB, not the old editor. The hub is the
        // screen a creator returns to weekly; the editor is now reachable from
        // inside it as settings, which is where configuration belongs — below
        // the content, not in front of it.
        selectedShow?.let { show ->
            if (composerFor == null && settingsFor == null) {
                ShowHubView(
                    showId = show.id,
                    onBack = { selectedShow = null },
                    // Opens the episode in the composer: a draft gets finished,
                    // a live one gets parts added. Drafts had no way back in.
                    onOpenEpisode = { ep ->
                        // The composer exists to upload/publish: blocked while suspended.
                        if (!com.stitchsocial.club.services.AccountStanding.guardPosting()) return@ShowHubView
                        composerEpisode = ep
                        composerFor = Triple(show.id, ep.seasonId ?: "", ep.episodeNumber ?: 1)
                    },
                    onNewEpisode = { seasonId, epNumber ->
                        if (!com.stitchsocial.club.services.AccountStanding.guardPosting()) return@ShowHubView
                        composerEpisode = null
                        composerFor = Triple(show.id, seasonId, epNumber)
                    },
                    onOpenSettings = { settingsFor = it }
                )
            }
        }

        // Show settings. Still the old editor overlay — screen 1c-3 (the
        // redesigned settings) isn't ported yet, and an unreachable settings
        // screen is worse than an unfashionable one.
        settingsFor?.let { show ->
            ShowSettingsView(
                initial = show,
                isNew = false,
                onSaved = { vm.updateShow(it) },
                onDismiss = { settingsFor = null }
            )
        }

        composerFor?.let { (showId, seasonId, epNumber) ->
            EpisodeComposerView(
                showId = showId,
                seasonId = seasonId,
                episodeNumber = epNumber,
                creatorID = creatorID,
                creatorName = creatorName,
                show = shows.firstOrNull { it.id == showId },
                existing = composerEpisode,
                onDismiss = { composerFor = null; composerEpisode = null },
                onPublished = {
                    composerFor = null
                    composerEpisode = null
                    // Reopen the hub so the new episode is visible where the
                    // creator expects it, rather than dumping them at the list.
                    selectedShow = selectedShow
                }
            )
        }
    }
}

@Composable
private fun ShowEmptyState(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.VideoLibrary, null, tint = Color.Gray.copy(alpha = 0.4f), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("No Shows Yet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Create your first show to organize seasons and episodes.",
            color = Color.Gray, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCreate,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2D55))
        ) { Text("Create Show", color = Color.White) }
    }
}

@Composable
private fun ShowRowCard(show: Show, onTap: () -> Unit) {
    val statusColor = when (show.status) {
        ShowStatus.DRAFT     -> Color.Gray
        ShowStatus.PUBLISHED -> Color.Green
        ShowStatus.PAUSED    -> Color.Yellow
        ShowStatus.COMPLETED -> Color.Cyan
        ShowStatus.REMOVED   -> Color.Red
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onTap() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Cover
        Box(
            Modifier.size(width = 50.dp, height = 68.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            if (show.coverImageURL != null) {
                AsyncImage(show.coverImageURL, null, Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Movie, null, tint = Color.Gray.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                show.title.ifBlank { "Untitled Show" },
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(show.genre.displayName, color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(show.status.displayName, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                show.scheduleConfig?.let { config ->
                    Text("• ${config.cadence.displayName}", color = Color.Cyan.copy(alpha = 0.7f), fontSize = 9.sp)
                }
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text("${show.totalEpisodes} ep", color = Color.Gray, fontSize = 10.sp)
            Spacer(Modifier.height(2.dp))
            Text("${show.seasonCount} season${if (show.seasonCount != 1) "s" else ""}",
                color = Color.Gray, fontSize = 10.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// MARK: - Release Schedule Section
// ─────────────────────────────────────────────────────────────────────

@Composable
fun ReleaseScheduleSection(
    config: ShowScheduleConfig,
    onConfigChange: (ShowScheduleConfig) -> Unit,
    onPersist: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Release Schedule", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

        // Cadence chips
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Cadence", fontSize = 10.sp, color = Color.Gray)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ReleaseCadence.values()) { cad ->
                    val selected = config.cadence == cad
                    FilterChip(
                        selected = selected,
                        onClick = { onConfigChange(config.copy(cadence = cad)); onPersist() },
                        label = { Text(cad.displayName, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color.Cyan,
                            selectedLabelColor = Color.Black,
                            containerColor = Color.White.copy(alpha = 0.08f),
                            labelColor = Color.White.copy(alpha = 0.7f)
                        )
                    )
                }
            }
        }

        // Repeating cadence options
        if (config.cadence != ReleaseCadence.CUSTOM && config.cadence != ReleaseCadence.ONE_OFF) {
            // Day of week for weekly/biweekly
            if (config.cadence == ReleaseCadence.WEEKLY || config.cadence == ReleaseCadence.BIWEEKLY) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Release Day", fontSize = 10.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("S","M","T","W","T","F","S").forEachIndexed { idx, name ->
                            val day = idx + 1
                            val selected = config.releaseWeekday == day
                            Box(
                                Modifier.size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) Color.Cyan else Color.White.copy(alpha = 0.08f))
                                    .clickable { onConfigChange(config.copy(releaseWeekday = day)); onPersist() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(name, color = if (selected) Color.Black else Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Drop time picker
            val context = LocalContext.current
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Drop Time", fontSize = 10.sp, color = Color.Gray)
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(context, { _, h, m ->
                            onConfigChange(config.copy(releaseHour = h, releaseMinute = m))
                            onPersist()
                        }, config.releaseHour, config.releaseMinute, false).show()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Cyan),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    modifier = Modifier.background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(config.releaseTimeDisplay, fontSize = 13.sp)
                }
            }

            // Summary
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CalendarMonth, null, tint = Color.Cyan, modifier = Modifier.size(14.dp))
                val summary = when (config.cadence) {
                    ReleaseCadence.DAILY    -> "New episode every day at ${config.releaseTimeDisplay}"
                    ReleaseCadence.WEEKLY   -> "Every ${config.weekdayName} at ${config.releaseTimeDisplay}"
                    ReleaseCadence.BIWEEKLY -> "Every other ${config.weekdayName} at ${config.releaseTimeDisplay}"
                    ReleaseCadence.MONTHLY  -> "Monthly at ${config.releaseTimeDisplay}"
                    else -> ""
                }
                Text(summary, fontSize = 11.sp, color = Color.Cyan.copy(alpha = 0.85f))
            }
        }

        // One-off summary
        if (config.cadence == ReleaseCadence.ONE_OFF) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, null, tint = Color.Cyan, modifier = Modifier.size(14.dp))
                Text("Single premiere — one-time release", fontSize = 11.sp, color = Color.Cyan.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun SeasonsSection(
    seasons: List<Season>,
    onAddSeason: () -> Unit,
    onDeleteSeason: (Season) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Seasons", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onAddSeason, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "Add Season", tint = Color(0xFFFF2D55))
            }
        }

        if (seasons.isEmpty()) {
            Text("No seasons yet — tap + to add one", color = Color.Gray, fontSize = 11.sp)
        } else {
            // Tracks which season the user is confirming a delete for.  Putting
            // the destructive action behind an overflow menu + dialog keeps the
            // delete out of finger-fall distance from the season expand chevron.
            var seasonPendingDelete by remember { mutableStateOf<Season?>(null) }

            seasons.forEach { season ->
                SeasonRow(
                    season = season,
                    onRequestDelete = { seasonPendingDelete = season }
                )
            }

            seasonPendingDelete?.let { target ->
                AlertDialog(
                    onDismissRequest = { seasonPendingDelete = null },
                    title = { Text("Delete \"${target.displayTitle}\"?", color = Color.White) },
                    text = {
                        Text(
                            "This removes the season and all its episodes. This can't be undone.",
                            color = Color.Gray
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            onDeleteSeason(target)
                            seasonPendingDelete = null
                        }) { Text("Delete Season", color = Color(0xFFFF453A)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { seasonPendingDelete = null }) {
                            Text("Cancel", color = Color.Gray)
                        }
                    },
                    containerColor = Color(0xFF1C1C1E)
                )
            }
        }
    }
}

/**
 * Row for a single season.  The overflow menu (3-dot) replaces the inline
 * trash so there's spatial separation between expand-on-tap and destructive
 * actions.
 */
@Composable
private fun SeasonRow(
    season: Season,
    onRequestDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(season.displayTitle, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("${season.episodeCount} episodes", color = Color.Gray, fontSize = 10.sp)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, "More", tint = Color.Gray)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(Color(0xFF1C1C1E))
            ) {
                DropdownMenuItem(
                    text = { Text("Delete Season", color = Color(0xFFFF453A)) },
                    onClick = {
                        menuOpen = false
                        onRequestDelete()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.DeleteOutline, null,
                            tint = Color(0xFFFF453A),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// MARK: - PremiereDatePicker (mirrors PremiereDatePicker.swift)
// ─────────────────────────────────────────────────────────────────────

@Composable
fun PremiereDatePicker(
    intent: PublishIntent,
    onIntentChange: (PublishIntent) -> Unit,
    suggestedDate: Date? = null,
    minimumDate: Date = Date(System.currentTimeMillis() + 30 * 60 * 1000L)
) {
    val context = LocalContext.current
    val scheduledDate = remember(intent) {
        mutableStateOf((intent as? PublishIntent.Scheduled)?.date ?: defaultScheduleDate())
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Mode chips
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModeChip("Draft", Icons.Default.Description, intent is PublishIntent.Draft, Color.Gray) {
                onIntentChange(PublishIntent.Draft)
            }
            ModeChip("Publish Now", Icons.Default.CheckCircle, intent is PublishIntent.PublishNow, Color.Green) {
                onIntentChange(PublishIntent.PublishNow)
            }
            ModeChip("Premiere Date", Icons.Default.CalendarMonth, intent is PublishIntent.Scheduled, Color.Cyan) {
                onIntentChange(PublishIntent.Scheduled(scheduledDate.value))
            }
        }

        // Auto-suggest chip
        if (suggestedDate != null && intent !is PublishIntent.Scheduled) {
            val fmt = SimpleDateFormat("EEE MMM d 'at' h:mm a", Locale.getDefault())
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Cyan.copy(alpha = 0.12f))
                    .clickable {
                        scheduledDate.value = suggestedDate
                        onIntentChange(PublishIntent.Scheduled(suggestedDate))
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = Color.Cyan, modifier = Modifier.size(12.dp))
                Text("Suggested: ${fmt.format(suggestedDate)}", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.Cyan)
            }
        }

        // Date + time picker (scheduled only)
        if (intent is PublishIntent.Scheduled) {
            val cal = Calendar.getInstance().apply { time = scheduledDate.value }

            OutlinedButton(
                onClick = {
                    DatePickerDialog(context, { _, y, m, d ->
                        TimePickerDialog(context, { _, h, min ->
                            val newCal = Calendar.getInstance()
                            newCal.set(y, m, d, h, min, 0)
                            val newDate = newCal.time.takeIf { it.after(minimumDate) } ?: minimumDate
                            scheduledDate.value = newDate
                            onIntentChange(PublishIntent.Scheduled(newDate))
                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
                        datePicker.minDate = minimumDate.time
                    }.show()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Cyan),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            ) {
                Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                val fmt = SimpleDateFormat("EEE, MMM d yyyy 'at' h:mm a", Locale.getDefault())
                Text(fmt.format(scheduledDate.value), fontSize = 13.sp)
            }

            // Relative label
            val relFmt = SimpleDateFormat("EEE MMM d 'at' h:mm a", Locale.getDefault())
            Text(
                "Will premiere ${relFmt.format(scheduledDate.value)}",
                fontSize = 11.sp, color = Color.Cyan.copy(alpha = 0.85f)
            )
        }

        // Status badge
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            val dotColor = when (intent) {
                is PublishIntent.Draft -> Color.Gray
                is PublishIntent.PublishNow -> Color.Green
                is PublishIntent.Scheduled -> Color.Cyan
            }
            Canvas(Modifier.size(6.dp)) { drawCircle(dotColor) }
            Text(intent.label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) accent else Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) Color.Black else Color.White.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.6f))
    }
}

private fun defaultScheduleDate(): Date {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, 1)
    cal.set(Calendar.HOUR_OF_DAY, 12)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    return cal.time
}

// ─────────────────────────────────────────────────────────────────────
// MARK: - ShowDetailView (mirrors ShowDetailView.swift)
// ─────────────────────────────────────────────────────────────────────

private val ResumePink = Color(0xFFF0245F)
private val DetailSurface = Color.White.copy(alpha = 0.06f)
private val DetailSurfaceStrong = Color.White.copy(alpha = 0.10f)
private val DetailHairline = Color.White.copy(alpha = 0.06f)
private val DetailTextSecondary = Color.White.copy(alpha = 0.5f)

/**
 * Viewer-facing show page: hero, resume strip, season tabs, this week's
 * schedule, the episode list and a play button — iOS ShowDetailView.
 *
 * The hero also states when the show airs ("Weekly · Tues at 8:00 PM") and the
 * next premiere, from the show's releaseCadence/Weekday/Hour/Minute and the
 * episodes' publishedAt — data already loaded, no extra reads.
 */
@Composable
fun ShowDetailView(
    showId: String,
    currentUserID: String,
    onDismiss: () -> Unit,
    onPlayEpisode: (VideoCollection) -> Unit,
    /** Resume an in-progress episode at a segment index. Falls back to
     *  [onPlayEpisode] when null, so it plays from Part 1. */
    onResumeEpisode: ((VideoCollection, Int) -> Unit)? = null,
    vm: ShowDetailViewModel = viewModel(key = "showDetail_$showId")
) {
    val show by vm.show.collectAsStateWithLifecycle()
    val seasons by vm.seasons.collectAsStateWithLifecycle()
    val episodesBySeasonId by vm.episodesBySeasonId.collectAsStateWithLifecycle()
    val selectedSeasonId by vm.selectedSeasonId.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val isReordering by vm.isReordering.collectAsStateWithLifecycle()
    val isOwner = show?.creatorID == currentUserID

    var episodeToEdit by remember { mutableStateOf<VideoCollection?>(null) }

    LaunchedEffect(showId) { vm.load(showId) }

    // Per-episode watch progress — drives the resume strip and the watched /
    // in-progress row states. Read once the episodes are in; cached by
    // CollectionService.
    val allEpisodes = remember(episodesBySeasonId) { episodesBySeasonId.values.flatten() }
    var progressByID by remember { mutableStateOf<Map<String, CollectionProgress>>(emptyMap()) }
    LaunchedEffect(allEpisodes.map { it.id }, currentUserID) {
        if (currentUserID.isBlank() || allEpisodes.isEmpty()) return@LaunchedEffect
        val service = com.stitchsocial.club.services.CollectionService()
        val result = mutableMapOf<String, CollectionProgress>()
        for (ep in allEpisodes) {
            runCatching { service.getWatchProgress(currentUserID, ep.id) }
                .getOrNull()?.let { result[ep.id] = it }
        }
        progressByID = result
    }

    val currentEpisodes = remember(episodesBySeasonId, selectedSeasonId) {
        val sid = selectedSeasonId
        (if (sid != null) episodesBySeasonId[sid].orEmpty() else allEpisodes)
            .sortedBy { it.episodeNumber ?: 0 }
    }
    val tint = collectionTypeColor(show?.contentType ?: CollectionContentType.SERIES)
    val selectedSeasonTitle = seasons.firstOrNull { it.id == selectedSeasonId }?.displayTitle ?: "All"

    fun play(ep: VideoCollection) {
        val prog = progressByID[ep.id]
        val watched = (prog?.completedSegmentCount ?: 0) >= ep.segmentCount && ep.segmentCount > 0
        if (prog != null && prog.isInProgress && !watched && onResumeEpisode != null) {
            onResumeEpisode(ep, prog.currentSegmentIndex)
        } else onPlayEpisode(ep)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFF2D55))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                // Hero
                item {
                    ShowHero(
                        show = show,
                        tint = tint,
                        seasonCount = seasons.size,
                        episodes = allEpisodes,
                        onDismiss = onDismiss
                    )
                }

                // Resume strip — the most recently watched in-progress episode
                val resume = allEpisodes.mapNotNull { ep ->
                    progressByID[ep.id]?.takeIf { it.isInProgress }?.let { ep to it }
                }.maxByOrNull { it.second.lastWatchedAt }
                if (resume != null) {
                    item {
                        ResumeStrip(ep = resume.first, progress = resume.second) {
                            if (onResumeEpisode != null) onResumeEpisode(resume.first, resume.second.currentSegmentIndex)
                            else onPlayEpisode(resume.first)
                        }
                    }
                }

                // Season tabs
                if (seasons.size > 1) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons) { season ->
                                val selected = selectedSeasonId == season.id
                                FilterChip(
                                    selected = selected,
                                    onClick = { vm.selectSeason(season.id) },
                                    label = { Text(season.displayTitle, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = tint,
                                        selectedLabelColor = Color.White,
                                        containerColor = Color.White.copy(alpha = 0.08f),
                                        labelColor = Color.Gray
                                    )
                                )
                            }
                        }
                    }
                }

                // This Week — Mon–Sun, from the episodes' publishedAt
                val week = currentWeekSchedule(allEpisodes)
                if (week.isNotEmpty()) {
                    item { WeeklySchedule(days = week, tint = tint, onPlay = { play(it) }) }
                }

                // Reorder hint for owner with cadence
                if (isOwner && show?.scheduleConfig?.cadence?.isRepeating == true) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Long-press to reorder • dates auto-update", fontSize = 10.sp, color = Color.Gray)
                            if (isReordering) CircularProgressIndicator(Modifier.size(14.dp), color = Color.Cyan, strokeWidth = 2.dp)
                        }
                    }
                }

                // Episode list
                itemsIndexed(currentEpisodes, key = { _, ep -> ep.id }) { idx, ep ->
                    EpisodeRow(
                        ep = ep,
                        progress = progressByID[ep.id],
                        isNewest = idx == currentEpisodes.size - 1 && currentEpisodes.size > 1,
                        tint = tint,
                        isOwner = isOwner,
                        onPlay = { play(ep) },
                        onEdit = { episodeToEdit = ep }
                    )
                    if (idx < currentEpisodes.size - 1) {
                        HorizontalDivider(Modifier.padding(start = 96.dp), color = DetailHairline)
                    }
                }

                // Play button
                if (currentEpisodes.isNotEmpty()) {
                    item {
                        Column {
                            HorizontalDivider(color = DetailHairline)
                            Box(
                                Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Brush.horizontalGradient(listOf(tint, tint.copy(alpha = 0.7f))))
                                    .clickable { onPlayEpisode(currentEpisodes.first()) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Play $selectedSeasonTitle", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.navigationBarsPadding().height(16.dp))
                        }
                    }
                }
            }
        }

        // Episode edit overlay
        episodeToEdit?.let { ep ->
            show?.let { s ->
                val seasonId = seasons.firstOrNull { sid ->
                    vm.episodesBySeasonId.value[sid.id]?.any { it.id == ep.id } == true
                }?.id ?: return@let
                // Wrap in EpisodeEditorView equivalent — placeholder for now
                Box(
                    Modifier.fillMaxSize().zIndex(100f).background(Color.Black.copy(alpha = 0.95f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Episode editor goes here", color = Color.White)
                    // TODO: wire to EpisodeEditorView composable
                }
            }
        }
    }
}

// MARK: - Hero

@Composable
private fun ShowHero(
    show: Show?,
    tint: Color,
    seasonCount: Int,
    episodes: List<VideoCollection>,
    onDismiss: () -> Unit
) {
    val totalViews = episodes.sumOf { it.totalViews }
    // The next episode still to premiere — "Ep 4 premieres Tue, Oct 14 at 8:00 PM".
    val now = Date()
    val next = episodes.filter { it.publishedAt?.after(now) == true }.minByOrNull { it.publishedAt!! }

    Box(
        Modifier.fillMaxWidth().heightIn(min = 160.dp)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.25f), Color.Black)))
    ) {
        Box(
            Modifier.matchParentSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Badges
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HeroBadge(show?.contentType?.displayName?.uppercase() ?: "SHOW", tint)
                // Genre only when it differs from the content type — avoids "SERIES SERIES".
                if (show != null && show.genre.displayName.uppercase() != show.contentType.displayName.uppercase()) {
                    HeroBadge(show.genre.displayName.uppercase(), Color(0xFFA78BFA))
                }
                if (show?.status == ShowStatus.PUBLISHED) HeroBadge("PUBLISHED", Color(0xFF34C759))
                if (seasonCount > 1) HeroBadge("$seasonCount SEASONS", Color.Cyan)
            }
            Text(show?.title?.ifBlank { null } ?: "Untitled Show", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "by @${show?.creatorName.orEmpty()} · ${episodes.size} episodes · ${formatShowCount(totalViews)} views",
                color = DetailTextSecondary, fontSize = 11.sp
            )
            // When it airs
            show?.scheduleConfig?.let { config ->
                val airs = when {
                    config.cadence == ReleaseCadence.DAILY -> "${config.cadence.displayName} · ${config.releaseTimeDisplay}"
                    config.cadence.isRepeating -> "${config.cadence.displayName} · ${config.weekdayName}s at ${config.releaseTimeDisplay}"
                    else -> config.cadence.displayName
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Schedule, null, tint = Color.Cyan.copy(alpha = 0.8f), modifier = Modifier.size(12.dp))
                    Text(airs, color = Color.Cyan.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
            next?.publishedAt?.let { at ->
                val fmt = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
                val label = next.episodeNumber?.let { "Ep $it" } ?: "Next episode"
                Text("$label premieres ${fmt.format(at)}", color = Color.Cyan.copy(alpha = 0.6f), fontSize = 11.sp)
            }
        }
        // Close — top-left, like every other takeover.
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 8.dp, top = 8.dp)
                .size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun HeroBadge(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 7.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.2f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

// MARK: - Resume strip

@Composable
private fun ResumeStrip(ep: VideoCollection, progress: CollectionProgress, onTap: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ResumePink.copy(alpha = 0.10f))
            .border(1.dp, ResumePink.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(Modifier.size(width = 70.dp, height = 42.dp).clip(RoundedCornerShape(6.dp)).background(DetailSurface)) {
            ep.coverImageURL?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(16.dp))
            Box(
                Modifier.align(Alignment.BottomStart).height(3.dp)
                    .fillMaxWidth(progress.percentComplete.toFloat().coerceIn(0.03f, 1f))
                    .background(ResumePink)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Continue ${ep.episodeDisplayTitle} · Part ${progress.currentSegmentIndex + 1}",
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("from ${progress.formattedCurrentTimestamp} · ${(progress.percentComplete * 100).toInt()}% of this episode",
                color = ResumePink, fontSize = 9.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
    }
}

// MARK: - This Week

private enum class ScheduleState { LIVE, NEW, SOON, UPCOMING, WATCHED }

private data class ScheduleDay(
    val key: String,
    val dayShort: String,
    val dayNumber: Int,
    val isToday: Boolean,
    val episode: VideoCollection?,
    val state: ScheduleState
)

/** Mon–Sun of the current week, one row a day. Empty unless an episode falls
 *  in it — a week of "No episode" rows says nothing. */
private fun currentWeekSchedule(episodes: List<VideoCollection>): List<ScheduleDay> {
    if (episodes.isEmpty()) return emptyList()
    val now = Date()
    val cal = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        time = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    }
    if (cal.time.after(now)) cal.add(Calendar.DAY_OF_YEAR, -7)
    val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
    val today = Calendar.getInstance()
    val days = (0 until 7).map { offset ->
        val day = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
        val isToday = day.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val dayEnd = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val isFuture = day.time.after(now)
        val ep = episodes.firstOrNull { e ->
            val pub = e.publishedAt ?: return@firstOrNull false
            !pub.before(day.time) && pub.before(dayEnd.time)
        }
        val state = when {
            ep != null && isToday ->
                if (now.time - (ep.publishedAt?.time ?: now.time) < 2 * 3600_000L) ScheduleState.LIVE else ScheduleState.NEW
            ep != null && isFuture -> ScheduleState.SOON
            ep != null -> ScheduleState.WATCHED
            isFuture -> ScheduleState.UPCOMING
            else -> ScheduleState.WATCHED
        }
        val short = dayFmt.format(day.time).uppercase()
        val num = day.get(Calendar.DAY_OF_MONTH)
        ScheduleDay("$short$num", short, num, isToday, ep, state)
    }
    return if (days.any { it.episode != null }) days else emptyList()
}

private fun weekRangeLabel(): String {
    val cal = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    }
    if (cal.time.after(Date())) cal.add(Calendar.DAY_OF_YEAR, -7)
    val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
    val start = fmt.format(cal.time)
    cal.add(Calendar.DAY_OF_YEAR, 6)
    return "$start — ${fmt.format(cal.time)}"
}

@Composable
private fun WeeklySchedule(days: List<ScheduleDay>, tint: Color, onPlay: (VideoCollection) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("This Week", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(weekRangeLabel(), color = DetailTextSecondary, fontSize = 10.sp)
        }
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                .clip(RoundedCornerShape(12.dp)).background(DetailSurface)
        ) {
            days.forEachIndexed { i, day ->
                ScheduleRow(day, tint, onPlay)
                if (i < days.size - 1) HorizontalDivider(Modifier.padding(start = 52.dp), color = DetailHairline)
            }
        }
    }
}

@Composable
private fun ScheduleRow(day: ScheduleDay, tint: Color, onPlay: (VideoCollection) -> Unit) {
    val ep = day.episode
    val enabled = ep != null && day.state != ScheduleState.SOON && day.state != ScheduleState.UPCOMING
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { ep?.let(onPlay) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.width(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(day.dayShort, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                color = if (day.isToday) tint else DetailTextSecondary)
            Text("${day.dayNumber}", fontSize = 16.sp,
                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (day.isToday) Color.White else DetailTextSecondary)
        }
        Box(
            Modifier.size(width = 52.dp, height = 34.dp).clip(RoundedCornerShape(6.dp))
                .background(if (ep != null) DetailSurfaceStrong else DetailSurface),
            contentAlignment = Alignment.Center
        ) {
            if (ep?.coverImageURL != null) {
                AsyncImage(ep.coverImageURL, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            } else if (day.state == ScheduleState.SOON || day.state == ScheduleState.UPCOMING) {
                Icon(Icons.Default.Schedule, null, tint = DetailTextSecondary, modifier = Modifier.size(14.dp))
            }
            if (day.state == ScheduleState.LIVE) {
                Box(Modifier.matchParentSize().background(Color.Red.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Text("LIVE", fontSize = 8.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (ep != null) {
                Text(ep.title.ifBlank { "Episode ${ep.episodeNumber ?: 0}" }, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (day.state == ScheduleState.UPCOMING) DetailTextSecondary else Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val premiere = if (day.state == ScheduleState.SOON) ep.publishedAt?.let {
                    " · " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(it)
                } ?: "" else ""
                Text(
                    "${ep.segmentCount} seg · ${formatEpisodeDuration(ep.totalDuration)}" +
                        (if (ep.totalViews > 0) " · ${formatShowCount(ep.totalViews)} views" else "") + premiere,
                    fontSize = 9.sp, color = DetailTextSecondary
                )
            } else {
                Text(if (day.state == ScheduleState.UPCOMING) "Drops ${if (day.isToday) "today" else "soon"}" else "No episode",
                    fontSize = 12.sp, color = DetailTextSecondary)
            }
        }
        when (day.state) {
            ScheduleState.LIVE -> StateTag("LIVE", Color.Red, Color.Red.copy(alpha = 0.15f), FontWeight.Black)
            ScheduleState.NEW -> StateTag("NEW", tint, tint.copy(alpha = 0.15f), FontWeight.Bold)
            ScheduleState.SOON -> StateTag("SOON", DetailTextSecondary, DetailSurface, FontWeight.Bold)
            ScheduleState.UPCOMING -> {}
            ScheduleState.WATCHED -> Icon(Icons.Default.Check, null, tint = DetailTextSecondary, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun StateTag(text: String, fg: Color, bg: Color, weight: FontWeight) {
    Text(text, fontSize = 8.sp, fontWeight = weight, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 3.dp))
}

private fun formatEpisodeDuration(seconds: Double): String {
    val m = seconds.toInt() / 60; val s = seconds.toInt() % 60
    return if (s == 0) "${m}m" else "${m}m ${s}s"
}

private fun formatShowCount(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> "$n"
}

// MARK: - Episode row

@Composable
private fun EpisodeRow(
    ep: VideoCollection,
    progress: CollectionProgress?,
    isNewest: Boolean,
    tint: Color,
    isOwner: Boolean,
    onPlay: () -> Unit,
    onEdit: () -> Unit
) {
    val isFuture = ep.publishedAt?.after(Date()) == true
    val watched = (progress?.completedSegmentCount ?: 0) >= ep.segmentCount && ep.segmentCount > 0
    val inProgress = progress?.isInProgress == true && !watched
    Row(
        Modifier.fillMaxWidth()
            .background(if (inProgress) ResumePink.copy(alpha = 0.08f) else Color.Transparent)
            .clickable { onPlay() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .graphicsLayerAlpha(if (watched) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Thumbnail + duration badge
        Box(
            Modifier.size(width = 70.dp, height = 42.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DetailSurface),
            contentAlignment = Alignment.Center
        ) {
            if (ep.coverImageURL != null) {
                AsyncImage(ep.coverImageURL, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            } else {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(alpha = 0.25f))
            }
            Text(
                ep.formattedTotalDuration, fontSize = 7.sp, color = Color.White.copy(alpha = 0.6f),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }

        Column(Modifier.weight(1f)) {
            Text(ep.episodeDisplayTitle, color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            when {
                isFuture && ep.publishedAt != null -> {
                    val fmt = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
                    Text("Premieres ${fmt.format(ep.publishedAt)}", fontSize = 9.sp, color = Color.Cyan.copy(alpha = 0.8f))
                }
                inProgress && progress != null ->
                    Text("Part ${progress.currentSegmentIndex + 1} of ${ep.segmentCount} · from ${progress.formattedCurrentTimestamp}",
                        fontSize = 9.sp, color = ResumePink)
                else ->
                    Text("${ep.segmentCount} segments · ${formatShowCount(ep.totalViews)} views", fontSize = 9.sp, color = DetailTextSecondary)
            }
        }

        if (watched) Icon(Icons.Default.Check, null, tint = Color(0xFF34C759), modifier = Modifier.size(14.dp))
        if (isNewest && !isFuture) {
            Text("NEW", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = tint,
                modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(tint.copy(alpha = 0.15f))
                    .padding(horizontal = 5.dp, vertical = 2.dp))
        }

        if (isOwner) {
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (isFuture) Icons.Default.CalendarMonth else Icons.Default.Edit,
                    "Edit",
                    tint = if (isFuture) Color.Cyan.copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun Modifier.graphicsLayerAlpha(a: Float): Modifier =
    this.then(Modifier.graphicsLayer { alpha = a })
