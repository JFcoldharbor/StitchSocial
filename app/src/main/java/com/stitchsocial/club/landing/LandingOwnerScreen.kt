package com.stitchsocial.club.landing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stitchsocial.club.services.BrandPortal
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.ui.theme.DismissX
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Setup and full editing live in the brand portal (the web wizard). */
const val LANDING_WEB_URL = "https://stitchsocial.me/brand/landing"

/**
 * The business owner's Landing tab: status and the pause switch, today's
 * numbers, the approvals queue for the TV, and links out to the web for
 * editing and the QR kit.
 *
 * A business without a Landing gets "Open your Landing", which opens the
 * web setup wizard (find → verify by phone → offers → live). Android does
 * not carry an in-app copy of that wizard: it is a phone-call verification
 * flow that already exists on the web, and the portal is where brands manage
 * everything else (BrandPortal).
 */
@Composable
fun LandingOwnerScreen(onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf<LandingOwnerData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var moments by remember { mutableStateOf<List<PendingMoment>>(emptyList()) }
    var busyStatus by remember { mutableStateOf(false) }
    var busyQR by remember { mutableStateOf(false) }
    var busyMoment by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        failed = false
        runCatching { LandingService.owner() }
            .onSuccess { owner ->
                data = owner
                moments = owner.landing?.let { l ->
                    runCatching { LandingService.pendingMoments(l.landingID) }.getOrDefault(emptyList())
                } ?: emptyList()
            }
            // not-found is how a business with no Landing yet answers.
            .onFailure { e ->
                if ((e as? LandingException)?.status == "NOT_FOUND") data = LandingOwnerData(null, null, 0, LandingStats(), LandingStats())
                else failed = true
            }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(colors.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            DismissX(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart), showsScrim = false)
            Text(
                "Your Landing", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        val landing = data?.landing
        when {
            loading && data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LandingColors.accent)
            }
            failed -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Couldn't load your Landing.", color = colors.textSecondary)
                TextButton(onClick = { scope.launch { load() } }) { Text("Try again") }
            }
            landing == null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
            ) {
                Text("Open your Landing", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
                Text(
                    "Give guests a free item for following you, and another for posting a moment from your tables. " +
                        "Setup takes a few minutes: find your restaurant, verify by phone, set your offers.",
                    fontSize = 14.sp, color = colors.textSecondary, textAlign = TextAlign.Center
                )
                LandingButton("Open your Landing", onClick = { BrandPortal.open(context, LANDING_WEB_URL) })
            }
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Status + pause.
                OwnerCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LandingLogo(landing.name, landing.logoURL, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(landing.name, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
                            val (label, tint) = when (landing.status) {
                                "live" -> "Live" to LandingColors.done
                                "paused" -> "Paused" to Color(0xFFFFB020)
                                "removed" -> "Removed by Stitch" to colors.destructive
                                else -> "Draft · finish setup on the web" to colors.textSecondary
                            }
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tint)
                        }
                        if (landing.status == "live" || landing.status == "paused") {
                            Switch(
                                checked = landing.status == "live",
                                enabled = !busyStatus,
                                onCheckedChange = { live ->
                                    busyStatus = true
                                    scope.launch {
                                        runCatching { LandingService.setStatus(landing.landingID, live) }
                                            .onSuccess { data = data?.copy(landing = landing.copy(status = if (live) "live" else "paused")) }
                                            .onFailure { message = "Couldn't change the status. Try again." }
                                        busyStatus = false
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = LandingColors.done)
                            )
                        }
                    }
                    data?.accessCode?.let {
                        Text(
                            "Access number on your signs: $it",
                            fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }

                // Today's numbers.
                data?.let { d ->
                    OwnerCard {
                        Text("Today", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
                        Spacer(Modifier.height(10.dp))
                        StatGrid(
                            listOf(
                                "Scans" to d.todayStats.scans,
                                "Welcome claims" to d.todayStats.welcomeClaims,
                                "Check-ins" to d.todayStats.checkIns,
                                "Moments" to d.todayStats.posts,
                                "Perks used" to d.todayStats.welcomeUsed + d.todayStats.postUsed,
                                "Returns" to d.todayStats.returns
                            )
                        )
                        Text(
                            "This week: ${d.weekStats.welcomeClaims} claims · ${d.weekStats.posts} moments · ${d.weekStats.returns} returns",
                            fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }

                // Approvals queue.
                OwnerCard {
                    Text("For your TV", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
                    Text(
                        "Approve a moment to play it on your screens. You can't hide posts; report anything that breaks the rules.",
                        fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )
                    if (moments.isEmpty()) {
                        Text("Nothing waiting.", fontSize = 13.sp, color = colors.textSecondary)
                    }
                    moments.forEach { m ->
                        MomentApprovalRow(
                            moment = m,
                            busy = busyMoment == m.videoID,
                            onApprove = {
                                busyMoment = m.videoID
                                scope.launch {
                                    runCatching { LandingService.moderate(m.videoID, approve = true) }
                                        .onSuccess { moments = moments.map { if (it.videoID == m.videoID) it.copy(landingScreen = "approved") else it } }
                                        .onFailure { message = "Couldn't approve. Try again." }
                                    busyMoment = null
                                }
                            },
                            onReport = {
                                busyMoment = m.videoID
                                scope.launch {
                                    runCatching { LandingService.moderate(m.videoID, approve = false) }
                                        .onSuccess { moments = moments.filterNot { it.videoID == m.videoID } }
                                        .onFailure { message = "Couldn't report. Try again." }
                                    busyMoment = null
                                }
                            }
                        )
                    }
                }

                // Out to the web.
                LandingButton(
                    "Download QR kit",
                    busy = busyQR,
                    onClick = {
                        busyQR = true
                        scope.launch {
                            runCatching { LandingService.qrKitURL(landing.landingID) }
                                .onSuccess { url -> if (url != null) BrandPortal.open(context, url) else message = "Couldn't make the QR kit." }
                                .onFailure { message = "Couldn't make the QR kit. Try again." }
                            busyQR = false
                        }
                    }
                )
                OutlinedButton(
                    onClick = { BrandPortal.open(context, LANDING_WEB_URL) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Edit on the web", color = colors.textPrimary, fontWeight = FontWeight.Bold) }
                if ((data?.screenCount ?: 0) == 0) {
                    Text(
                        "Pair a TV at stitchsocial.me/tv, then enter its code on the web.",
                        fontSize = 12.sp, color = colors.textSecondary, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
}

@Composable
private fun OwnerCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun StatGrid(stats: List<Pair<String, Int>>) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text("$value", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
                        Text(label, fontSize = 12.sp, color = colors.textSecondary, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MomentApprovalRow(moment: PendingMoment, busy: Boolean, onApprove: () -> Unit, onReport: () -> Unit) {
    val colors = AppTheme.colors
    val time = moment.createdAt?.let { SimpleDateFormat("h:mm a", Locale.US).format(it) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF24324A))
        ) {
            moment.thumbnailURL?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f)) {
            Text("@${moment.creatorName}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1)
            Text(
                listOfNotNull(moment.landingSpot?.let { spotLabel(it) }, time).joinToString(" · "),
                fontSize = 12.sp, color = colors.textSecondary
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LandingColors.accent)
        } else if (moment.landingScreen == "approved") {
            Text("On TV", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LandingColors.done)
        } else {
            TextButton(onClick = onReport) { Text("Report", color = colors.textSecondary, fontSize = 13.sp) }
            Button(
                onClick = onApprove,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LandingColors.accent, contentColor = LandingColors.onAccent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("Approve", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
    }
}
