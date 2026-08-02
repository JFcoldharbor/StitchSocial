package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.foundation.ReleaseCadence
import com.stitchsocial.club.foundation.ScheduleService
import com.stitchsocial.club.foundation.Show
import com.stitchsocial.club.foundation.ShowTag
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.services.ShowService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private object SET {
    val bg = Color(0xFF0B0B0D)
    val card = Color(0xFF141418)
    val pink = Color(0xFFE91E63)
    val cyan = Color(0xFF22D3EE)
    val hairline = Color(0x1FFFFFFF)
}

/**
 * Show settings — design_handoff_show_flow screen 1c-3. The tab that absorbs
 * everything the old editor put above the fold, and what replaces it.
 *
 * Four cards, in the doc's order: cover + title + description; RELEASE RHYTHM;
 * ACCESS · SET ONCE; DISCOVERY.
 *
 * What this deliberately DROPS from ShowEditorOverlay, per the handoff:
 *
 * - The Save button, and the schedule write that fired on every cadence tap.
 *   Everything debounces into one autosave and the header says so.
 * - The Status picker. Show status is DERIVED — one or more published episodes
 *   means live — and is not creator-editable. It was the third of three
 *   overlapping publish states.
 * - The seasons accordion and episode list. That's the Hub's job now.
 *
 * NOT built: the Schedule | Library | Show segmented control drawn at the top of
 * 1c-3. Schedule is the phase-2 release calendar and Library is the Hub;
 * rendering a three-tab control where two tabs don't exist would be worse than
 * the push navigation the Hub already uses.
 */
@Composable
fun ShowSettingsView(
    initial: Show,
    /** A brand-new show has no doc yet, so it gets an explicit "Create show"
     *  action instead of autosave — there's nothing to autosave into. */
    isNew: Boolean,
    /** Drives the per-episode access-override line. Callers without them pass empty. */
    episodes: List<VideoCollection> = emptyList(),
    onSaved: (Show) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(initial) }
    var saveTick by remember { mutableStateOf(0) }
    var savedLabel by remember { mutableStateOf(if (isNew) "" else "All changes saved") }

    // 600ms debounce, per the handoff. Replaces BOTH the Save button and the
    // write-on-every-cadence-tap.
    LaunchedEffect(saveTick) {
        if (isNew || saveTick == 0) return@LaunchedEffect
        savedLabel = "Saving…"
        delay(600)
        runCatching { ShowService.shared.saveShow(show) }
            .onSuccess { savedLabel = "All changes saved"; onSaved(show) }
            .onFailure { savedLabel = "Not saved — ${it.message ?: "try again"}" }
    }

    fun edit(block: (Show) -> Show) {
        show = block(show)
        saveTick++
    }

    Column(Modifier.fillMaxSize().background(SET.bg)) {

        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 44.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ArrowBack, "Back", tint = Color.White,
                modifier = Modifier.size(22.dp).clickable { onDismiss() }
            )
            Spacer(Modifier.weight(1f))
            // The autosave indicator IS the save button's replacement — without
            // it, removing the button reads as "my edits aren't being kept".
            if (!isNew) {
                Text(savedLabel, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            // ── Cover + title + description ──────────────────────────────
            SettingsCard {
                OutlinedTextField(
                    value = show.title,
                    onValueChange = { v -> edit { it.copy(title = v) } },
                    label = { Text("Show title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = show.description,
                    onValueChange = { v -> edit { it.copy(description = v) } },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    colors = fieldColors()
                )
            }

            // ── RELEASE RHYTHM ───────────────────────────────────────────
            SectionLabel("RELEASE RHYTHM")
            SettingsCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ReleaseCadence.WEEKLY, ReleaseCadence.BIWEEKLY,
                        ReleaseCadence.MONTHLY, ReleaseCadence.ONE_OFF
                    ).forEach { cad ->
                        val selected = show.releaseCadence == cad.rawValue
                        Text(
                            cad.displayName,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .background(
                                    if (selected) SET.pink else Color.White.copy(alpha = 0.06f),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { edit { it.copy(releaseCadence = cad.rawValue) } }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }

                // The CONSEQUENCE of the cadence, not just its name — the same
                // reason the Hub shows the next drop date.
                // Roll the next three slots forward by feeding each one back in
                // as an already-scheduled date. ScheduleService only answers
                // "next open slot", and re-implementing cadence maths here would
                // be a second copy that drifts from the one that actually
                // schedules episodes.
                val slots = remember(show.releaseCadence, show.releaseWeekday, show.releaseHour) {
                    val cfg = show.scheduleConfig
                    if (cfg == null) emptyList() else buildList {
                        // Walk minDate forward past each slot we've taken.
                        // nextAvailableSlot answers "next open slot from here",
                        // so advancing the floor is how you get the following
                        // one without a second copy of the cadence maths.
                        var floor = java.util.Date()
                        repeat(3) {
                            val next = ScheduleService.nextAvailableSlot(
                                cfg, emptyList(), minDate = floor
                            ) ?: return@buildList
                            add(next)
                            floor = java.util.Date(next.time + 1000)
                        }
                    }
                }
                if (slots.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    val fmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault())
                    Text("NEXT DROPS", color = Color.White.copy(alpha = 0.4f),
                        fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    slots.forEach {
                        Text(fmt.format(it), color = SET.cyan.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                }
            }

            // ── ACCESS · SET ONCE ────────────────────────────────────────
            SectionLabel("ACCESS · SET ONCE")
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = show.isFree,
                        onCheckedChange = { v -> edit { it.copy(isFree = v) } }
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Free show", color = Color.White, fontSize = 14.sp)
                        Text(
                            "Everyone can watch every episode.",
                            color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp
                        )
                    }
                }

                // Name the overrides rather than letting a per-episode setting
                // silently contradict the show-level one.
                val overrides = episodes.count { it.isFree != show.isFree }
                if (overrides > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$overrides episode${if (overrides == 1) "" else "s"} override this.",
                        color = SET.cyan.copy(alpha = 0.85f), fontSize = 11.sp
                    )
                }
            }

            // ── DISCOVERY ────────────────────────────────────────────────
            SectionLabel("DISCOVERY")
            SettingsCard {
                Text("Tags help people find the show.",
                    color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShowTag.entries.take(5).forEach { tag ->
                        val on = show.tags.contains(tag)
                        Text(
                            tag.displayName,
                            color = if (on) Color.White else Color.White.copy(alpha = 0.55f),
                            fontSize = 10.sp,
                            modifier = Modifier
                                .background(
                                    if (on) SET.pink else Color.White.copy(alpha = 0.06f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    edit {
                                        it.copy(
                                            tags = if (on) it.tags - tag else it.tags + tag
                                        )
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (isNew) {
                // Explicit action, because there's no document to autosave into
                // yet. Disabled with a reason rather than silently inert — a
                // dead button with no explanation is what made "tapping Create
                // Show did nothing" a bug report on iOS.
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { ShowService.shared.saveShow(show) }
                                .onSuccess { onSaved(show) }
                                .onFailure { savedLabel = it.message ?: "Couldn't create the show" }
                        }
                    },
                    enabled = show.title.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = SET.pink),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Create show", color = Color.White, fontWeight = FontWeight.Bold) }

                if (show.title.isBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Give the show a title first.",
                        color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp
                    )
                }
                savedLabel.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        text,
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SET.card, RoundedCornerShape(12.dp))
            .border(0.5.dp, SET.hairline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = SET.pink,
    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
    cursorColor = SET.pink
)
