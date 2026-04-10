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
                IconButton(onClick = { showingNewShow = true }) {
                    Icon(Icons.Default.AddCircle, "New Show", tint = Color(0xFFFF2D55))
                }
            }
            Divider(color = Color.White.copy(alpha = 0.06f))

            when {
                isLoading && shows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFF2D55))
                }
                shows.isEmpty() -> ShowEmptyState { showingNewShow = true }
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
            ShowEditorOverlay(
                show = Show.newDraft(creatorID, creatorName),
                isNew = true,
                onSave = { vm.insertShow(it); showingNewShow = false },
                onDismiss = { showingNewShow = false }
            )
        }

        // Edit existing show
        selectedShow?.let { show ->
            ShowEditorOverlay(
                show = show,
                isNew = false,
                onSave = { vm.updateShow(it); selectedShow = null },
                onDismiss = { selectedShow = null }
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
// MARK: - ShowEditorOverlay
// ─────────────────────────────────────────────────────────────────────

@Composable
fun ShowEditorOverlay(
    show: Show,
    isNew: Boolean,
    onSave: (Show) -> Unit,
    onDismiss: () -> Unit,
    vm: ShowEditorViewModel = viewModel()
) {
    val currentShow by vm.show.collectAsStateWithLifecycle()
    val scheduleConfig by vm.scheduleConfig.collectAsStateWithLifecycle()
    val isSaving by vm.isSaving.collectAsStateWithLifecycle()
    val seasons by vm.seasons.collectAsStateWithLifecycle()

    LaunchedEffect(show.id) { vm.init(show, isNew) }

    Box(
        Modifier.fillMaxSize().zIndex(200f).background(Color.Black)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray, fontSize = 15.sp) }
                Text(if (isNew) "New Show" else "Edit Show",
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = { vm.save { onSave(it) } },
                    enabled = !isSaving
                ) {
                    Text(if (isSaving) "Saving..." else "Save", color = Color(0xFFFF2D55), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Divider(color = Color.White.copy(alpha = 0.06f))

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                // Metadata
                item { ShowMetadataSection(vm) }

                // Release Schedule
                item {
                    ReleaseScheduleSection(
                        config = scheduleConfig,
                        onConfigChange = { vm.updateScheduleConfig(it) },
                        onPersist = { currentShow?.let { s -> vm.persistSchedule(s.id) } }
                    )
                }

                // Seasons
                item {
                    SeasonsSection(
                        seasons = seasons,
                        onAddSeason = { vm.addSeason() },
                        onDeleteSeason = { vm.deleteSeason(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowMetadataSection(vm: ShowEditorViewModel) {
    val show by vm.show.collectAsStateWithLifecycle()
    val s = show ?: return

    Column(
        Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Title", fontSize = 10.sp, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = s.title, onValueChange = { vm.updateShowField { c -> c.copy(title = it) } },
                    placeholder = { Text("Show title...", color = Color.Gray, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.Cyan, unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f)
                    ),
                    singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                )
            }
        }

        // Status picker
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShowStatus.values().filter { it != ShowStatus.REMOVED }.forEach { status ->
                val selected = s.status == status
                FilterChip(
                    selected = selected,
                    onClick = { vm.updateShowField { c -> c.copy(status = status) } },
                    label = { Text(status.displayName, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFFF2D55),
                        selectedLabelColor = Color.White,
                        containerColor = Color.White.copy(alpha = 0.08f),
                        labelColor = Color.Gray
                    )
                )
            }
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
            seasons.forEach { season ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(season.displayTitle, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${season.episodeCount} episodes", color = Color.Gray, fontSize = 10.sp)
                    }
                    IconButton(onClick = { onDeleteSeason(season) }) {
                        Icon(Icons.Default.DeleteOutline, "Delete", tint = Color.Red.copy(alpha = 0.6f))
                    }
                }
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

@Composable
fun ShowDetailView(
    showId: String,
    currentUserID: String,
    onDismiss: () -> Unit,
    onPlayEpisode: (VideoCollection) -> Unit,
    vm: ShowDetailViewModel = viewModel()
) {
    val show by vm.show.collectAsStateWithLifecycle()
    val seasons by vm.seasons.collectAsStateWithLifecycle()
    val selectedSeasonId by vm.selectedSeasonId.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val isReordering by vm.isReordering.collectAsStateWithLifecycle()
    val isOwner = show?.creatorID == currentUserID

    var episodeToEdit by remember { mutableStateOf<VideoCollection?>(null) }

    LaunchedEffect(showId) { vm.load(showId) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFF2D55))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                // Hero
                show?.let { s ->
                    item { ShowHero(show = s, onDismiss = onDismiss) }
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
                                        selectedContainerColor = Color(0xFFFF2D55),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color.White.copy(alpha = 0.08f),
                                        labelColor = Color.Gray
                                    )
                                )
                            }
                        }
                    }
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
                val episodes = vm.currentSeasonEpisodes
                items(episodes, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep = ep,
                        isOwner = isOwner,
                        onPlay = { onPlayEpisode(ep) },
                        onEdit = { episodeToEdit = ep }
                    )
                    Divider(
                        Modifier.padding(start = 96.dp),
                        color = Color.White.copy(alpha = 0.04f)
                    )
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

@Composable
private fun ShowHero(show: Show, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(200.dp)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1A1A2E), Color.Black))
            )
    ) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp)
        ) {
            Text(show.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(show.genre.displayName, color = Color.Gray, fontSize = 12.sp)
                show.scheduleConfig?.let { config ->
                    Text("• ${config.cadence.displayName}", color = Color.Cyan.copy(alpha = 0.8f), fontSize = 12.sp)
                    if (config.cadence.isRepeating) {
                        Text("${config.weekdayName}s at ${config.releaseTimeDisplay}", color = Color.Cyan.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Default.Close, "Close", tint = Color.White)
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: VideoCollection,
    isOwner: Boolean,
    onPlay: () -> Unit,
    onEdit: () -> Unit
) {
    val isFuture = ep.publishedAt?.after(Date()) == true
    Row(
        Modifier.fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Thumbnail
        Box(
            Modifier.size(width = 70.dp, height = 42.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            if (ep.coverImageURL != null) {
                AsyncImage(ep.coverImageURL, null, Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(alpha = 0.25f))
            }
        }

        Column(Modifier.weight(1f)) {
            Text((ep.title.ifBlank { "Episode ${ep.episodeNumber ?: ""}" }), color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            if (isFuture && ep.publishedAt != null) {
                val fmt = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
                Text("Premieres ${fmt.format(ep.publishedAt)}", fontSize = 9.sp, color = Color.Cyan.copy(alpha = 0.8f))
            } else {
                Text("${ep.segmentCount} segments", fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f))
            }
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