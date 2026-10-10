package com.stitchsocial.club.landing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.ui.theme.DismissX
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Profile → Perks: every ticket the guest holds, grouped Active / Ready /
 * Used & expired. An active ticket opens straight back into the full-screen
 * view, which is how it "stays reachable" while the guest is at the counter.
 */
@Composable
fun MyPerksScreen(onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val perks by remember { LandingService.myPerks() }.collectAsState(initial = null)
    var confirmPerk by remember { mutableStateOf<Perk?>(null) }
    var activating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Re-group as tickets run out without waiting for a snapshot.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); tick = System.currentTimeMillis() } }

    val now = Date(tick)
    val all = perks.orEmpty()
    val active = all.filter { it.effectiveStatus(now) == "active" }
    val ready = all.filter { it.effectiveStatus(now) == "issued" }
    val spent = all.filter { it.effectiveStatus(now) in setOf("used", "expired", "void") }

    Column(Modifier.fillMaxSize().background(colors.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            DismissX(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart), showsScrim = false)
            Text(
                "My perks", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        when {
            perks == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LandingColors.accent)
            }
            all.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No perks yet. Scan a Stitch code at a restaurant to get one.",
                    color = colors.textSecondary, textAlign = TextAlign.Center
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                section("Active", active) { perk ->
                    PerkRow(perk, now, action = "Show ticket") { LandingPresenter.activePerk.value = perk }
                }
                section("Ready", ready) { perk ->
                    val usable = perk.isUsable(now)
                    PerkRow(perk, now, action = if (usable) "Use now" else null) { confirmPerk = perk }
                }
                section("Used & expired", spent) { perk -> PerkRow(perk, now, action = null) {} }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }

    confirmPerk?.let { perk ->
        AlertDialog(
            onDismissRequest = { if (!activating) confirmPerk = null },
            title = { Text("Use it now?") },
            text = { Text("It stays active for 15 minutes.") },
            confirmButton = {
                TextButton(enabled = !activating, onClick = {
                    activating = true
                    scope.launch {
                        runCatching { LandingService.activate(perk.perkID) }
                            .onSuccess { LandingPresenter.activePerk.value = it ?: perk }
                            .onFailure { message = "Couldn't start this perk. Try again." }
                        activating = false
                        confirmPerk = null
                    }
                }) { Text(if (activating) "Starting…" else "Use now") }
            },
            dismissButton = { TextButton(enabled = !activating, onClick = { confirmPerk = null }) { Text("Not yet") } }
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    perks: List<Perk>,
    row: @Composable (Perk) -> Unit
) {
    if (perks.isEmpty()) return
    item(key = "h_$title") {
        Text(
            title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            color = AppTheme.colors.textSecondary, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
        )
    }
    items(perks, key = { it.perkID }) { row(it) }
}

@Composable
private fun PerkRow(perk: Perk, now: Date, action: String?, onAction: () -> Unit) {
    val colors = AppTheme.colors
    val day = SimpleDateFormat("MMM d", Locale.US)
    val time = SimpleDateFormat("h:mm a", Locale.US)
    val status = perk.effectiveStatus(now)
    val detail = when {
        status == "active" -> perk.activeUntil?.let { "Active until ${time.format(it)}" } ?: "Active now"
        perk.isWaiting(now) -> "Good from your next visit"
        status == "issued" -> perk.expiresAt?.let { "Use by ${day.format(it)}" } ?: "Ready"
        status == "used" -> perk.usedAt?.let { "Used ${day.format(it)}" } ?: "Used"
        status == "expired" -> "Expired"
        else -> "No longer valid"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LandingLogo(perk.landingName, perk.landingLogoURL, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${perk.landingName} · ${if (perk.isWelcome) "Welcome" else "Moment"}",
                fontSize = 12.sp, color = colors.textSecondary, maxLines = 1
            )
            Text("Free ${perk.title}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary, maxLines = 1)
            Text(detail, fontSize = 12.sp, color = if (status == "active") LandingColors.done else colors.textSecondary)
        }
        if (action != null) {
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LandingColors.accent, contentColor = LandingColors.onAccent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) { Text(action, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        }
    }
}
