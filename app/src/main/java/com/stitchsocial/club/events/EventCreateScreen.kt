package com.stitchsocial.club.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.ui.theme.StitchColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Layer 5: Host / edit an event (iOS parity with ios/Events/EventCreateView.swift).
 * Collects the Concept B essentials — name, venue, city, doors time, duration,
 * geofence radius, opener mode (+ prizes on create).
 *
 * NOTE (Phase 2): the precise venue MAP PIN lands in Phase 3 (Android has no
 * location stack yet). For now a typed venue marks the draft "pinned" with
 * placeholder coords so the create/edit/delete loop is testable; the geofence
 * gets real coordinates once the place picker exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventCreateScreen(
    vm: EventsViewModel,
    editing: StitchEventEntity? = null,
    onDismiss: () -> Unit,
) {
    val isEditing = editing != null
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(editing?.let { EventCreateDraft.fromEntity(it) } ?: EventCreateDraft()) }
    var giveaways by remember { mutableStateOf(listOf<GiveawayDraft>()) }
    var newPrize by remember { mutableStateOf("") }
    var newWinners by remember { mutableStateOf(1) }
    var newRule by remember { mutableStateOf(GiveawayEntryRule.ATTENDED) }
    var isSaving by remember { mutableStateOf(false) }
    val error by vm.errorMessage.collectAsState()

    val canSave = draft.name.isNotBlank() && draft.venueName.isNotBlank() &&
        draft.city.isNotBlank() && draft.hasVenuePin &&
        (isEditing || draft.doorsAt.time > System.currentTimeMillis())

    Scaffold(
        containerColor = StitchColors.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit event" else "Host an event", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.7f)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StitchColors.background)
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            field("Event name") {
                inputField(draft.name, "Founders Night") { draft = draft.copy(name = it) }
            }

            field("Venue") {
                inputField(draft.venueName, "Ponce City Market") {
                    // Typed venue → treat as pinned for now (real pin in Phase 3).
                    draft = draft.copy(venueName = it, hasVenuePin = it.isNotBlank())
                }
                Text(
                    "📍 Precise map pin + geofence coming soon",
                    color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp
                )
            }

            field("City") {
                inputField(draft.city, "Atlanta") { draft = draft.copy(city = it) }
            }

            field(dateHeader(draft.doorsAt)) {
                stepperRow(
                    onDay = { draft = draft.copy(doorsAt = draft.doorsAt.shift(Calendar.DAY_OF_YEAR, it)) },
                    onHour = { draft = draft.copy(doorsAt = draft.doorsAt.shift(Calendar.HOUR_OF_DAY, it)) }
                )
            }

            field("Runs for ${draft.durationHours.toInt()}h") {
                Slider(
                    value = draft.durationHours.toFloat(),
                    onValueChange = { draft = draft.copy(durationHours = it.toDouble()) },
                    valueRange = 1f..12f, steps = 10,
                    colors = SliderDefaults.colors(thumbColor = StitchColors.primary, activeTrackColor = StitchColors.primary)
                )
            }

            field("Geofence radius · ${draft.geofenceRadiusMeters.toInt()}m") {
                Slider(
                    value = draft.geofenceRadiusMeters.toFloat(),
                    onValueChange = { draft = draft.copy(geofenceRadiusMeters = it.toDouble()) },
                    valueRange = 100f..300f, steps = 7,
                    colors = SliderDefaults.colors(thumbColor = StitchColors.primary, activeTrackColor = StitchColors.primary)
                )
            }

            if (!isEditing) {
                field("Prizes (optional)") {
                    giveaways.forEach { g ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🎁 ${g.prize}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("· ${g.winnerCount}w · ${g.entryRule.displayName}", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { giveaways = giveaways.filter { it.id != g.id } }) { Text("✕", color = Color.White.copy(alpha = 0.4f)) }
                        }
                    }
                    inputField(newPrize, "Grand prize · Door prize") { newPrize = it }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GiveawayEntryRule.entries.forEach { rule ->
                            FilterChip(
                                selected = newRule == rule,
                                onClick = { newRule = rule },
                                label = { Text(rule.raw, fontSize = 11.sp) }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Winners: $newWinners", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        TextButton(onClick = { if (newWinners > 1) newWinners-- }) { Text("−", color = Color.White) }
                        TextButton(onClick = { if (newWinners < 20) newWinners++ }) { Text("+", color = Color.White) }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                val p = newPrize.trim()
                                if (p.isNotEmpty()) {
                                    giveaways = giveaways + GiveawayDraft(prize = p, winnerCount = newWinners, entryRule = newRule)
                                    newPrize = ""; newWinners = 1; newRule = GiveawayEntryRule.ATTENDED
                                }
                            },
                            enabled = newPrize.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = StitchColors.success)
                        ) { Text("Add", color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            error?.let { Text(it, color = Color.Red.copy(alpha = 0.9f), fontSize = 13.sp) }

            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        val ok = if (editing != null) vm.updateEvent(draft, editing.id)
                        else vm.createEvent(draft, giveaways)
                        isSaving = false
                        if (ok) onDismiss()
                    }
                },
                enabled = canSave && !isSaving,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, disabledContainerColor = Color.White.copy(alpha = 0.3f))
            ) {
                Text(
                    if (isSaving) (if (isEditing) "Saving…" else "Creating…") else (if (isEditing) "Save changes" else "Create event"),
                    color = Color.Black, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun field(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label.uppercase(), color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun inputField(value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = Color.White.copy(alpha = 0.3f)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.06f), unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
            focusedBorderColor = StitchColors.primary, unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
            cursorColor = StitchColors.primary
        )
    )
}

@Composable
private fun stepperRow(onDay: (Int) -> Unit, onHour: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("-1d" to { onDay(-1) }, "+1d" to { onDay(1) }, "-1h" to { onHour(-1) }, "+1h" to { onHour(1) }).forEach { (label, action) ->
            OutlinedButton(onClick = action) { Text(label, color = Color.White) }
        }
    }
}

private fun dateHeader(d: Date): String =
    "Doors open · " + SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(d)

private fun Date.shift(field: Int, amount: Int): Date =
    Calendar.getInstance().apply { time = this@shift; add(field, amount) }.time
