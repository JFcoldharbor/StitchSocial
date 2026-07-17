package com.stitchsocial.club.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.ui.theme.StitchColors
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Layer 5: The Event Hub — a fullscreen room (iOS parity with
 * ios/Events/EventHubView.swift). Phase 2 shell: header + clock + RSVP + host
 * Edit/Delete + Agenda and Prizes tabs. Invite, Host-Thread video cards, promo/
 * recap videos, and geofenced POV land in Phase 4.
 */
private enum class HubTab(val label: String) { AGENDA("Timeline"), INVITE("Invite"), PRIZES("Prizes") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventHubScreen(event: StitchEventEntity, vm: EventsViewModel, onDismiss: () -> Unit) {
    val blue = StitchColors.secondary
    val isHost = vm.isHost(event)
    val agenda by vm.agenda.collectAsState()
    val giveaways by vm.giveaways.collectAsState()
    val myRSVPs by vm.myRSVPs.collectAsState()
    val isGoing = myRSVPs[event.id] == EventRSVPStatus.GOING

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locationService = remember { LocationService(context) }
    val isOnsite by vm.isOnsite.collectAsState()
    val isCheckingPresence by vm.isCheckingPresence.collectAsState()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.refreshPresence(event, locationService)
    }
    var tab by remember { mutableStateOf(HubTab.AGENDA) }
    var menuOpen by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(event.id) {
        vm.loadAgenda(event.id)
        vm.loadGiveaways(event.id)
    }
    LaunchedEffect(event.id, isGoing) {
        if (!isHost && isGoing && locationService.hasPermission()) vm.refreshPresence(event, locationService)
    }

    if (showEdit) {
        EventCreateScreen(vm = vm, editing = event, onDismiss = { showEdit = false; onDismiss() })
        return
    }
    if (showInvite) {
        EventInviteSheet(vm = vm, event = event, onDismiss = { showInvite = false })
        return
    }

    Column(Modifier.fillMaxSize().background(StitchColors.background)) {

        // Close + overflow row
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButtonCircle("✕") { onDismiss() }
            Spacer(Modifier.weight(1f))
            Box {
                IconButtonCircle("⋯") { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (isHost) {
                        DropdownMenuItem(text = { Text("Edit event") }, onClick = { menuOpen = false; showEdit = true })
                        DropdownMenuItem(text = { Text("Invite people") }, onClick = { menuOpen = false; showInvite = true })
                        DropdownMenuItem(text = { Text("Delete event", color = Color.Red) }, onClick = { menuOpen = false; confirmDelete = true })
                    } else {
                        DropdownMenuItem(text = { Text("Not interested", color = Color.Red) }, onClick = {
                            menuOpen = false; vm.markNotInterested(event); onDismiss()
                        })
                    }
                }
            }
        }

        // Title + RSVP
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(event.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text("${event.whereLine} · hosted by @${event.hostUsername}", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1)
            }
            Button(
                onClick = { vm.toggleGoing(event) },
                colors = ButtonDefaults.buttonColors(containerColor = if (isGoing) Color.White.copy(alpha = 0.15f) else Color.White)
            ) { Text(if (isGoing) "Going" else "Join", color = if (isGoing) Color.White else Color.Black, fontWeight = FontWeight.Bold) }
        }

        // Presence — geofenced check-in for Going guests during a live event.
        if (!isHost && isGoing && event.isLive) {
            val (label, color) = when {
                isCheckingPresence -> "Checking you're here…" to Color.White.copy(alpha = 0.6f)
                isOnsite -> "You're at the venue ✓" to StitchColors.success
                else -> "Not at the venue · tap to check in" to Color.White.copy(alpha = 0.6f)
            }
            Text(
                label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
                    .clickable {
                        if (locationService.hasPermission()) vm.refreshPresence(event, locationService)
                        else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // Clock rail
        if (event.isLive) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.1f))
            ) {
                Box(Modifier.fillMaxWidth(event.clockProgress.toFloat()).fillMaxHeight().background(blue))
            }
        } else {
            Text(
                if (event.hasEnded) "Ended" else "Starts ${event.whenLine}",
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }

        // Tabs
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubTab.entries.forEach { t ->
                val on = tab == t
                Text(
                    t.label, color = if (on) Color.Black else Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50)).background(if (on) blue else Color.White.copy(alpha = 0.08f))
                        .clickable { tab = t }.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        when (tab) {
            HubTab.AGENDA -> AgendaTab(event, agenda, isHost, blue, vm)
            HubTab.INVITE -> InviteTab(event, blue) { showInvite = true }
            HubTab.PRIZES -> PrizesTab(event, giveaways, isHost, blue, vm)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this event?") },
            text = { Text("This removes the event, its agenda, and all posts. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { vm.deleteEvent(event); onDismiss() }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AgendaTab(event: StitchEventEntity, agenda: List<EventAgendaItem>, isHost: Boolean, blue: Color, vm: EventsViewModel) {
    var newTitle by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        if (isHost) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedTextField(
                    value = newTitle, onValueChange = { newTitle = it },
                    placeholder = { Text("Add a moment…", color = Color.White.copy(alpha = 0.3f)) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Button(onClick = {
                    if (newTitle.isNotBlank()) { vm.addAgendaItem(event, newTitle.trim(), Date()); newTitle = "" }
                }, colors = ButtonDefaults.buttonColors(containerColor = blue)) { Text("Add", color = Color.Black) }
            }
        }
        if (agenda.isEmpty()) {
            Text("No agenda yet.", color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn {
                items(agenda.byTime(), key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(item.timeLabel, color = blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (item.isFilled) Text("● filled", color = StitchColors.success, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrizesTab(event: StitchEventEntity, giveaways: List<EventGiveaway>, isHost: Boolean, blue: Color, vm: EventsViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        if (giveaways.isEmpty()) {
            Text("No prizes yet.", color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn {
                items(giveaways, key = { it.id }) { g ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("🎁 ${g.prize}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("· ${g.winnerCount}w · ${g.entryRule.displayName}", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                        }
                        if (g.isDrawn) {
                            Text("Winners: ${g.winnerUsernames?.joinToString(", ") ?: "—"}", color = StitchColors.success, fontSize = 12.sp)
                        } else if (isHost) {
                            TextButton(onClick = { vm.drawGiveaway(event, g, seed = "${event.id}-${g.id}") }) {
                                Text("Draw winners", color = blue)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteTab(event: StitchEventEntity, blue: Color, onInvite: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Invite people", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Send it to anyone — they land right on this event.", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
        Button(onClick = onInvite, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = blue)) {
            Text("Invite from your circle", color = Color.Black, fontWeight = FontWeight.Bold)
        }
        if (event.goingCount > 0) {
            Text("${event.goingCount} going", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
        }
        Text("stitchsocial.me/e/${event.id}", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
    }
}

@Composable
private fun IconButtonCircle(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(glyph, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}
