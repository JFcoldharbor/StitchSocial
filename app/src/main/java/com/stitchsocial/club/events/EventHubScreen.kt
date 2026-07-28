package com.stitchsocial.club.events

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.stitchsocial.club.views.VideoPlayerComposable
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.camera.RecordingContext
import com.stitchsocial.club.camera.ThreadInfo
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Layer 5: The Event Hub — a fullscreen room (iOS parity with
 * ios/Events/EventHubView.swift). Header + clock + RSVP + host Edit/Delete +
 * Agenda and Prizes tabs. The Timeline tab shows a 9:16 hero ([MomentVideoCard]):
 * the live moment while live, else the promo teaser. On-site Going guests get a
 * "Stitch your POV" button (geofenced), iOS parity with EventHubView.
 */
private enum class HubTab(val label: String) { AGENDA("Timeline"), INVITE("Invite"), PRIZES("Prizes"), RECAP("Recap") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventHubScreen(
    event: StitchEventEntity,
    vm: EventsViewModel,
    onDismiss: () -> Unit,
    // Opens the recorder: NewThread (host Go Live / promo) or a StitchToThread
    // (guest POV) with the parent video. The bridge is armed just before this.
    onRecord: (RecordingContext, CoreVideoMetadata?) -> Unit = { _, _ -> },
    // Plays an event video fullscreen with the full engagement overlay — routed
    // up to DiscoveryView's DiscoveryFullscreenDeck (like any other video).
    onOpenVideo: (CoreVideoMetadata) -> Unit = {}
) {
    val blue = StitchColors.primary  // events accent = brand magenta (was blue #3399FF)
    val isHost = vm.isHost(event)
    val agenda by vm.agenda.collectAsState()
    val giveaways by vm.giveaways.collectAsState()
    val promoVideo by vm.promoVideo.collectAsState()
    val liveMoment by vm.liveMoment.collectAsState()
    val recapVideo by vm.recapVideo.collectAsState()
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
    var confirmEnd by remember { mutableStateOf(false) }

    LaunchedEffect(event.id) {
        // Clear any arm left over from an abandoned recorder in a prior session
        // before the host can arm a fresh one from this Hub.
        EventMomentBridge.disarm()
        vm.loadAgenda(event.id)
        vm.loadGiveaways(event.id)
        vm.loadPromo(event)
        vm.loadRecap(event)
    }
    LaunchedEffect(event.id, isGoing) {
        if (!isHost && isGoing && locationService.hasPermission()) vm.refreshPresence(event, locationService)
    }
    // Re-hydrate the live moment whenever the agenda changes (host goes live,
    // clock advances to the next slot, etc.).
    LaunchedEffect(agenda) { vm.loadLiveMoment() }

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
                        if (!event.hasEnded) {
                            DropdownMenuItem(text = { Text("End event") }, onClick = { menuOpen = false; confirmEnd = true })
                        }
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

        // Guest POV: Going + on-site + a filled live moment -> stitch a POV onto
        // its thread (iOS parity with startPOVStitch). The live moment = newest
        // FILLED slot (liveMomentItem); the geofence gate is enforced above.
        val liveItem = agenda.liveMomentItem()
        if (!isHost && isGoing && isOnsite && event.isLive && liveItem?.isFilled == true) {
            Button(
                onClick = {
                    scope.launch {
                        val video = vm.liveMomentVideo() ?: return@launch
                        vm.armPOV(event, liveItem.id)
                        val info = ThreadInfo(
                            title = video.title,
                            creatorName = video.creatorName,
                            creatorId = video.creatorID,
                            participantCount = 1,
                            stitchCount = video.replyCount
                        )
                        onRecord(RecordingContext.StitchToThread(video.id, info), video)
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = blue)
            ) { Text("Stitch your POV", color = Color.Black, fontWeight = FontWeight.Bold) }
        }

        // One-tap for a not-yet-Going guest: Join + geofence-check + stitch if
        // on-site (iOS parity with handlePlus(.guestJoinStitch)). Falls back to
        // the staged flow when location permission isn't granted yet.
        if (!isHost && !isGoing && event.isLive && liveItem?.isFilled == true) {
            Button(
                onClick = {
                    if (locationService.hasPermission()) {
                        scope.launch {
                            val video = vm.joinAndStitch(event, locationService) ?: return@launch
                            val info = ThreadInfo(
                                title = video.title,
                                creatorName = video.creatorName,
                                creatorId = video.creatorID,
                                participantCount = 1,
                                stitchCount = video.replyCount
                            )
                            onRecord(RecordingContext.StitchToThread(video.id, info), video)
                        }
                    } else {
                        vm.toggleGoing(event)
                        permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = blue)
            ) { Text("Join & stitch", color = Color.Black, fontWeight = FontWeight.Bold) }
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
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
            HubTab.AGENDA -> AgendaTab(event, agenda, promoVideo, liveMoment, isHost, blue, vm, onRecord = { onRecord(RecordingContext.NewThread, null) }, onVideoTap = onOpenVideo)
            HubTab.INVITE -> InviteTab(event, blue) { showInvite = true }
            HubTab.PRIZES -> PrizesTab(event, giveaways, isHost, blue, vm)
            HubTab.RECAP -> RecapTab(
                event, recapVideo, isHost, blue, vm,
                onRecord = { onRecord(RecordingContext.NewThread, null) },
                onVideoTap = onOpenVideo
            )
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

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this event?") },
            text = { Text("This closes the event now. It moves to ended and the recap stays available. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    scope.launch { vm.endEvent(event); onDismiss() }
                }) { Text("End event") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AgendaTab(
    event: StitchEventEntity,
    agenda: List<EventAgendaItem>,
    promoVideo: CoreVideoMetadata?,
    liveMoment: CoreVideoMetadata?,
    isHost: Boolean,
    blue: Color,
    vm: EventsViewModel,
    onRecord: () -> Unit,
    onVideoTap: (CoreVideoMetadata) -> Unit
) {
    var newTitle by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        if (isHost) {
            // Host recording controls. Go Live shows until the event ends — it's
            // how the host STARTS (posts the opener, which flips the event live)
            // as well as adds moments while live; gating on event.isLive would be
            // a chicken-and-egg (isLive requires an opener that only Go Live posts).
            // Record/Re-record promo (the pre-event teaser) also until it ends.
            // Each arms EventMomentBridge, then opens the recorder.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                if (!event.hasEnded) {
                    Button(
                        onClick = { scope.launch { if (vm.armGoLive(event)) onRecord() } },
                        colors = ButtonDefaults.buttonColors(containerColor = blue)
                    ) { Text(if (event.isLive) "＋ Go Live" else "Start event", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
                if (!event.hasEnded) {
                    val hasPromo = event.promoVideoID != null
                    OutlinedButton(onClick = { if (vm.armPromo(event)) onRecord() }) {
                        Text(if (hasPromo) "Re-record promo" else "Record promo", color = Color.White)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
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
        LazyColumn {
            // During a live event, the live moment is what a guest stitches onto —
            // show it so they can see it first. Before/outside live, fall back to
            // the promo teaser (both are 9:16 autoplay cards; one plays at a time).
            val hero = if (event.isLive && liveMoment != null) liveMoment else promoVideo
            hero?.let { video ->
                val isLive = video === liveMoment && event.isLive
                val pov = agenda.liveMomentItem()?.povCount ?: 0
                item(key = "hero") {
                    MomentVideoCard(
                        video = video,
                        label = if (isLive) "● LIVE" else "PROMO",
                        labelColor = if (isLive) Color(0xFFFF3B30) else StitchColors.success,
                        subLabel = if (isLive) (if (pov == 1) "1 POV" else "$pov POVs") else null,
                        onTap = { onVideoTap(video) }
                    )
                }
            }
            // The live moment shows as the hero above; drop it from the list so it
            // isn't duplicated. What's left: past filled moments (view-only cards)
            // + future/unfilled slots (text rows).
            val heroSlotID = if (event.isLive && liveMoment != null) agenda.liveMomentItem()?.id else null
            val listItems = agenda.byTime().filter { it.id != heroSlotID }
            if (listItems.isEmpty()) {
                item(key = "empty") {
                    Text("No agenda yet.", color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(top = 24.dp))
                }
            } else {
                items(listItems, key = { it.id }) { item ->
                    if (item.isFilled) {
                        PastMomentRow(
                            item, blue, isHost,
                            onTap = { scope.launch { vm.momentVideo(item)?.let(onVideoTap) } },
                            onMakeLive = { vm.reopenMoment(event, item.id) }
                        )
                    } else {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(item.timeLabel, color = blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A past (view-only) event moment: thumbnail + time/title + POV count. Tap plays
 * it fullscreen. Only the current live moment is stitchable — these have passed,
 * so there's no stitch affordance (iOS parity with the locked Host-Threads cards).
 * Host long-press → "Make live again" (bumps it back to newest-filled = live) for
 * the accidental-lock recovery case.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PastMomentRow(item: EventAgendaItem, blue: Color, isHost: Boolean, onTap: () -> Unit, onMakeLive: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onTap, onLongClick = { if (isHost) menu = true }),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(width = 48.dp, height = 84.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = item.thumbnailURL,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Text("▶", color = Color.White, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(item.timeLabel, color = blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val pov = item.povCount ?: 0
                Text(
                    if (pov > 0) "View only · $pov POV" else "View only",
                    color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Make live again") }, onClick = { menu = false; onMakeLive() })
        }
    }
}

@Composable
private fun RecapTab(
    event: StitchEventEntity,
    recapVideo: CoreVideoMetadata?,
    isHost: Boolean,
    blue: Color,
    vm: EventsViewModel,
    onRecord: () -> Unit,
    onVideoTap: (CoreVideoMetadata) -> Unit
) {
    // The recap = the host's closing wrap-up, the event's public/shareable video.
    // Host arms the recap bridge then records; everyone can view it once posted.
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        when {
            recapVideo != null -> {
                item(key = "recap") { MomentVideoCard(recapVideo, "RECAP", blue, onTap = { onVideoTap(recapVideo) }) }
                if (isHost) item(key = "rerecord") {
                    TextButton(onClick = { if (vm.armRecap(event)) onRecord() }) {
                        Text("Re-record wrap-up", color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
            isHost -> item(key = "record") {
                Column(Modifier.padding(top = 24.dp)) {
                    Text("Wrap it up", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Record a closing video — it becomes the event's public, shareable video.",
                        color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Button(
                        onClick = { if (vm.armRecap(event)) onRecord() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = blue)
                    ) { Text("Record wrap-up", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
            else -> item(key = "none") {
                Column(Modifier.padding(top = 24.dp)) {
                    Text("No recap yet.", color = Color.White.copy(alpha = 0.4f))
                    Text(
                        "When the host posts a wrap-up, it becomes the shareable event video.",
                        color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * A labelled 9:16 autoplaying event video card — the pre-event promo teaser
 * ([EventsViewModel.loadPromo]) or the live moment ([EventsViewModel.loadLiveMoment]),
 * iOS parity with EventHubView.promoCard / the live DiscoveryCard. `managed = false`
 * keeps this player independent of the main feed's VideoManager so it doesn't
 * steal/lose the active slot. Muted preview — tap opens the fullscreen engagement
 * deck (with sound), so a silent inline preview avoids double audio.
 */
@Composable
private fun MomentVideoCard(video: CoreVideoMetadata, label: String, labelColor: Color, onTap: () -> Unit, subLabel: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (subLabel != null) Text(subLabel, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            VideoPlayerComposable(
                video = video,
                isActive = true,
                muted = true,
                managed = false,
                modifier = Modifier.fillMaxSize()
            )
            // Transparent tap layer above the player — the player swallows its
            // own gestures, so this guarantees a tap opens fullscreen.
            Box(Modifier.matchParentSize().clickable { onTap() })
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
