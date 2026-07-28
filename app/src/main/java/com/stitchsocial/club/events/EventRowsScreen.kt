package com.stitchsocial.club.events

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.ui.theme.StitchColors
import kotlinx.coroutines.launch

/**
 * Layer 5: The Events index (iOS parity with ios/Events/EventRowsView.swift).
 * Two tabs — Events (browse: live + upcoming) and My Events (hosted). Tapping a
 * row opens the fullscreen [EventHubScreen]; the Host FAB opens create.
 */
private enum class EventsTab { BROWSE, MINE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventRowsScreen(
    vm: EventsViewModel,
    onOpenEvent: (StitchEventEntity) -> Unit,
    /**
     * Dismiss the takeover. Events is full screen now — the Discovery category
     * chips are covered, so this is the only way back to the feed. Null keeps
     * the old inline behaviour (no close button) for any embedded use.
     */
    onClose: (() -> Unit)? = null,
) {
    val blue = StitchColors.primary  // events accent = brand magenta (was blue #3399FF)

    val live by vm.liveEvents.collectAsState()
    val upcoming by vm.upcomingEvents.collectAsState()
    val mine by vm.myEvents.collectAsState()
    val myRSVPs by vm.myRSVPs.collectAsState()
    val isLoading by vm.isLoading.collectAsState()

    var tab by remember { mutableStateOf(EventsTab.BROWSE) }
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<StitchEventEntity?>(null) }

    // Create is a form → fine inside the tab content. The Hub is hoisted to the
    // Discovery root so it takes over the full screen (see onOpenEvent).
    if (showCreate) {
        EventCreateScreen(vm = vm, onDismiss = { showCreate = false })
        return
    }

    Box(Modifier.fillMaxSize().background(StitchColors.background)) {
        Column(Modifier.fillMaxSize()) {
            // Header: close + the Events / My Events pills. The close button
            // matches the Community and Collections takeovers (32dp circle,
            // white @ 9%) so the three surfaces dismiss the same way.
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onClose != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.09f))
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                tabButton("Events", tab == EventsTab.BROWSE) { tab = EventsTab.BROWSE }
                tabButton("My Events", tab == EventsTab.MINE) { tab = EventsTab.MINE }
            }

            when (tab) {
                EventsTab.BROWSE -> {
                    // Live events pin to the top — they cut the line (iOS
                    // featuredEvents = liveEvents + upcomingEvents).
                    val featured = live + upcoming
                    if (isLoading && featured.isEmpty()) {
                        Loading()
                    } else if (featured.isEmpty()) {
                        Empty("No events yet", "Live and upcoming events show up here.")
                    } else {
                        val pager = rememberPagerState(pageCount = { featured.size })
                        LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 120.dp),
                        ) {
                            // Hero carousel — swipe sideways; scroll past it to
                            // the day-grouped list of the same events.
                            item {
                                HorizontalPager(
                                    state = pager,
                                    pageSpacing = 12.dp,
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    modifier = Modifier.fillMaxWidth().height(430.dp),
                                ) { page ->
                                    val ev = featured[page]
                                    HeroEventCard(
                                        event = ev,
                                        accent = blue,
                                        isGoing = myRSVPs[ev.id] == EventRSVPStatus.GOING,
                                        onTap = { onOpenEvent(ev) },
                                        onToggleGoing = { vm.toggleGoing(ev) },
                                    )
                                }
                            }
                            if (featured.size > 1) {
                                item { PageDots(count = featured.size, active = pager.currentPage) }
                            }
                            // Same events again, grouped by day.
                            dayGroups(featured).forEach { (title, events) ->
                                item { sectionHeader(title) }
                                items(events, key = { "list_" + it.id }) {
                                    EventListRow(it, blue, onOpen = { onOpenEvent(it) })
                                }
                            }
                        }
                    }
                }
                EventsTab.MINE -> {
                    // iOS splits My Events three ways rather than one flat list.
                    val now = live.filter { vm.isHost(it) || myRSVPs[it.id] == EventRSVPStatus.GOING }
                    val going = upcoming.filter { myRSVPs[it.id] == EventRSVPStatus.GOING && !vm.isHost(it) }
                    val hosting = mine.filter { !it.isLive }
                    if (isLoading && now.isEmpty() && going.isEmpty() && hosting.isEmpty()) {
                        Loading()
                    } else if (now.isEmpty() && going.isEmpty() && hosting.isEmpty()) {
                        Empty("You haven't hosted any events", "Tap Host to create one.")
                    } else {
                        LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 120.dp),
                        ) {
                            if (now.isNotEmpty()) {
                                item { sectionHeader("Happening now") }
                                items(now, key = { "now_" + it.id }) {
                                    EventListRow(it, blue, onOpen = { onOpenEvent(it) })
                                }
                            }
                            if (going.isNotEmpty()) {
                                item { sectionHeader("Going") }
                                items(going, key = { "going_" + it.id }) {
                                    EventListRow(it, blue, onOpen = { onOpenEvent(it) })
                                }
                            }
                            if (hosting.isNotEmpty()) {
                                item { sectionHeader("Hosting") }
                                items(hosting, key = { "host_" + it.id }) { ev ->
                                    MyEventRow(ev, blue, onOpen = { onOpenEvent(ev) }, onLongPress = { pendingDelete = ev })
                                }
                            }
                            item { HostBigButton { showCreate = true } }
                        }
                    }
                }
            }
        }

        // Host FAB. The app tab bar used to cover this corner; Events is full
        // screen now, so the FAB has to clear the system nav / gesture bar itself.
        Button(
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) { Text("+ Host", color = Color.Black, fontWeight = FontWeight.Bold) }
    }

    pendingDelete?.let { ev ->
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this event?") },
            text = { Text("This removes the event, its agenda, and all posts. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { vm.deleteEvent(ev) }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun tabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Color.Black else Color.White.copy(alpha = 0.6f),
        fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * Hero card (iOS HeroEventCard). Cover photo -> promo thumbnail -> gradient,
 * with the avatar + LIVE/date pill on top and title, going/where and the RSVP
 * CTA over a bottom scrim.
 *
 * Not ported: the 3-second-dwell promo autoplay. It needs per-card active-page
 * tracking plus an ExoPlayer per card in a pager; the still + CTA carry the
 * card's job, so this shows the cover art and leaves autoplay as follow-up.
 */
@Composable
private fun HeroEventCard(
    event: StitchEventEntity,
    accent: Color,
    isGoing: Boolean,
    onTap: () -> Unit,
    onToggleGoing: () -> Unit,
) {
    val cover = event.coverImageURL ?: event.promoThumbnailURL
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF332644), Color(0xFF0A0A0F)))
            )
            .clickable { onTap() },
    ) {
        if (!cover.isNullOrBlank()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Top row — host avatar + LIVE badge or start date/time.
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(accent, Color(0xFFA78BFA)))
                    )
            )
            Spacer(Modifier.weight(1f))
            if (event.isLive) {
                Text(
                    "● LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accent)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            } else {
                Text(
                    heroDateTime(event.doorsAt), color = Color.Black, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }

        // Bottom — title, going/where, CTA over a scrim.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                event.name, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${event.goingCount} going · ${event.whereLine}",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                // Live -> "Answer this" (opens the room); otherwise RSVP toggle.
                val title = if (event.isLive) "Answer this" else if (isGoing) "✓ Going" else "I'm going"
                val filled = event.isLive || !isGoing
                Text(
                    title,
                    color = if (filled) Color.Black else Color.White,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (filled) Color.White else Color.White.copy(alpha = 0.16f))
                        .clickable { if (event.isLive) onTap() else onToggleGoing() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, active: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = if (i == active) 16.dp else 6.dp, height = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (i == active) Color.White else Color.White.copy(alpha = 0.3f))
            )
        }
    }
}

/**
 * Scannable list row (iOS listRow): 52dp rounded tile with a live dot, name,
 * "● Live now" or the venue line, and the start time trailing.
 *
 * Replaces the old row, which carried a venue/host line, a going/posts line and
 * an inline Join button — iOS moved RSVP onto the hero CTA so the list stays
 * scannable.
 */
@Composable
private fun EventListRow(event: StitchEventEntity, accent: Color, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            if (event.isLive) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(StitchColors.primary))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                event.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (event.isLive) "● Live now" else event.whereLine,
                color = if (event.isLive) StitchColors.primary else Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            shortTime(event.doorsAt), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
        )
    }
}

@Composable
private fun HostBigButton(onTap: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .clickable { onTap() }
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("+ Host an event", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Group events by calendar day — "Today" / "Tomorrow" / "Sat, Aug 2". */
private fun dayGroups(events: List<StitchEventEntity>): List<Pair<String, List<StitchEventEntity>>> {
    val fmt = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault())
    val dayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    val todayKey = dayKey.format(java.util.Date())
    val tomorrowKey = dayKey.format(java.util.Date(System.currentTimeMillis() + 86_400_000L))
    return events.groupBy { dayKey.format(it.doorsAt) }
        .toSortedMap()
        .map { (key, list) ->
            val title = when (key) {
                todayKey -> "Today"
                tomorrowKey -> "Tomorrow"
                else -> fmt.format(list.first().doorsAt)
            }
            title to list
        }
}

private fun heroDateTime(date: java.util.Date): String =
    java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.getDefault()).format(date)

private fun shortTime(date: java.util.Date): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MyEventRow(event: StitchEventEntity, blue: Color, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val (label, color) = when {
        event.isLive -> "LIVE" to blue
        event.hasEnded -> (if (event.recapVideoID != null) "RECAP" else "ENDED") to StitchColors.primary
        else -> "UPCOMING" to Color.White.copy(alpha = 0.5f)
    }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(event.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${event.whereLine} · ${event.whenLine}", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, maxLines = 1)
        }
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun sectionHeader(title: String) {
    Text(
        title.uppercase(), color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp)
    )
}

@Composable
private fun ColumnScope.Loading() {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
}

@Composable
private fun ColumnScope.Empty(title: String, subtitle: String) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
    }
}
