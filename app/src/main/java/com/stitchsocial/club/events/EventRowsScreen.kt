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
fun EventRowsScreen(vm: EventsViewModel, onOpenEvent: (StitchEventEntity) -> Unit) {
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
            // Tab bar
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabButton("Events", tab == EventsTab.BROWSE) { tab = EventsTab.BROWSE }
                tabButton("My Events", tab == EventsTab.MINE) { tab = EventsTab.MINE }
            }

            when (tab) {
                EventsTab.BROWSE -> {
                    if (isLoading && live.isEmpty() && upcoming.isEmpty()) {
                        Loading()
                    } else if (live.isEmpty() && upcoming.isEmpty()) {
                        Empty("No events yet", "Live and upcoming events show up here.")
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            if (live.isNotEmpty()) {
                                item { sectionHeader("Today") }
                                items(live, key = { it.id }) { EventRow(it, myRSVPs[it.id], blue, { onOpenEvent(it) }, { vm.toggleGoing(it) }) }
                            }
                            if (upcoming.isNotEmpty()) {
                                item { sectionHeader("This week") }
                                items(upcoming, key = { it.id }) { EventRow(it, myRSVPs[it.id], blue, { onOpenEvent(it) }, { vm.toggleGoing(it) }) }
                            }
                        }
                    }
                }
                EventsTab.MINE -> {
                    if (isLoading && mine.isEmpty()) {
                        Loading()
                    } else if (mine.isEmpty()) {
                        Empty("You haven't hosted any events", "Tap Host to create one.")
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(mine, key = { it.id }) { ev ->
                                MyEventRow(ev, blue, onOpen = { onOpenEvent(ev) }, onLongPress = { pendingDelete = ev })
                            }
                        }
                    }
                }
            }
        }

        // Host FAB
        Button(
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
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

@Composable
private fun EventRow(
    event: StitchEventEntity, rsvp: EventRSVPStatus?, blue: Color,
    onOpen: () -> Unit, onToggleGoing: () -> Unit,
) {
    val isGoing = rsvp == EventRSVPStatus.GOING
    Row(
        Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (event.isLive) blue else Color.White.copy(alpha = 0.25f)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(event.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (event.isLive) Text("HAPPENING NOW", color = blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("${event.venueName} · @${event.hostUsername}", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp, maxLines = 1)
            Text("${event.goingCount} going · ${event.postCount} posts", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
        }
        Button(
            onClick = onToggleGoing,
            colors = ButtonDefaults.buttonColors(containerColor = if (isGoing) Color.White.copy(alpha = 0.15f) else Color.White)
        ) { Text(if (isGoing) "Going" else "Join", color = if (isGoing) Color.White else Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

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
