package com.stitchsocial.club.landing

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.events.LocationService
import kotlinx.coroutines.launch
import java.util.Date

/**
 * First-launch routing: a guest who scanned a table QR without the app should
 * land on that restaurant once they've signed up — not on Discovery, wondering
 * where their free taco went.
 *
 * Runs once per install, after sign-up and the age gate (MainScreen composes
 * this only in its "main UI up" branch). In order, first answer wins:
 *   1. the Play Install Referrer (`landing=<slug>&t=<spot>`, LandingReferrer);
 *   2. landing_claimHandoff — the scan the website recorded from this IP;
 *   3. location: landing_nearby, and a Landing whose radius contains the guest.
 *      Permission is only asked here, after 1 and 2 missed, and only on a
 *      fresh account; when a match comes back the guest is told by name where
 *      they are before it opens;
 *   4. otherwise the nearest Landing becomes a Home banner.
 *
 * Accounts that existed before this build skip 2–4: someone updating the app
 * at home is not standing in a restaurant, and a location prompt on update
 * is the fastest way to a "Don't allow" that sticks.
 */
@Composable
fun LandingArrival(accountCreatedAt: Date?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationService = remember { LocationService(context) }

    var askLocation by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<NearbyLanding?>(null) }

    suspend fun matchByLocation() {
        val fix = locationService.fetchCurrentLocation() ?: return
        val nearby = runCatching { LandingService.nearby(fix.lat, fix.lng, fix.accuracyMeters.toDouble()) }
            .getOrDefault(emptyList())
        val inside = nearby.firstOrNull { it.inside }
        if (inside != null) found = inside
        else nearby.firstOrNull()?.let { LandingPresenter.homeBanner.value = it }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) scope.launch { matchByLocation() }
    }

    LaunchedEffect(Unit) {
        if (LandingReferrer.hasRouted(context)) return@LaunchedEffect

        // 1. Install referrer.
        LandingReferrer.pending(context)?.let { (slug, spot) ->
            if (BuildConfig.DEBUG) { println("🍽️ LANDING: first launch → referrer $slug") }
            LandingReferrer.clearPending(context)
            LandingReferrer.markRouted(context)
            LandingPresenter.open(LandingPresenter.Screen.Guest(slug, spot, LandingSource.HANDOFF))
            return@LaunchedEffect
        }

        // Only a just-made account goes further (see above).
        val fresh = accountCreatedAt != null &&
            System.currentTimeMillis() - accountCreatedAt.time < 48L * 60 * 60 * 1000
        LandingReferrer.markRouted(context)
        if (!fresh) return@LaunchedEffect

        // 2. Web scan handoff.
        runCatching { LandingService.claimHandoff() }.getOrNull()?.let { (slug, spot) ->
            if (BuildConfig.DEBUG) { println("🍽️ LANDING: first launch → handoff $slug") }
            LandingPresenter.open(LandingPresenter.Screen.Guest(slug, spot, LandingSource.HANDOFF))
            return@LaunchedEffect
        }

        // 3. Location. Already granted: just look. Otherwise explain first.
        if (locationService.hasPermission()) matchByLocation() else askLocation = true
    }

    if (askLocation) {
        AlertDialog(
            onDismissRequest = { askLocation = false },
            title = { Text("At a restaurant?") },
            text = {
                Text(
                    "If you scanned a Stitch code at a restaurant, share your location once and we'll " +
                        "find it and hand you its welcome perk. We only check while you're on this screen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askLocation = false
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }) { Text("Find it") }
            },
            dismissButton = { TextButton(onClick = { askLocation = false }) { Text("Not now") } }
        )
    }

    found?.let { landing ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("You're at ${landing.name}") },
            text = { Text("Claim your welcome perk and see what's on tonight.") },
            confirmButton = {
                TextButton(onClick = {
                    found = null
                    LandingPresenter.open(LandingPresenter.Screen.Guest(landing.slug, null, LandingSource.HANDOFF))
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text("Later") } }
        )
    }
}

/** Step 4: the nearest Landing, offered on Home until dismissed. */
@Composable
fun LandingHomeBanner(landing: NearbyLanding, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF15151F))
            .border(1.dp, LandingColors.accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable {
                LandingPresenter.homeBanner.value = null
                LandingPresenter.open(LandingPresenter.Screen.Guest(landing.slug, null, LandingSource.NEARBY))
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LandingLogo(landing.name, landing.logoURL, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(landing.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFF4F4F8), maxLines = 1)
            Text(
                "${distanceLabel(landing.distanceM)} away · free perk when you visit",
                fontSize = 12.sp, color = Color(0xFFA3A3B8), maxLines = 1
            )
        }
        IconButton(onClick = { LandingPresenter.homeBanner.value = null }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color(0xFFA3A3B8), modifier = Modifier.size(16.dp))
        }
    }
}

private const val NEAR_YOU_TTL_MS = 5L * 60 * 1000
private var nearYouCache: Pair<Long, List<NearbyLanding>>? = null

/**
 * Discovery's "Near you" row of live Landings. Shown only when location is
 * ALREADY granted — this row never asks — and hidden when there are none.
 */
@Composable
fun NearYouLandingsRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locationService = remember { LocationService(context) }
    var landings by remember { mutableStateOf(nearYouCache?.second ?: emptyList()) }

    LaunchedEffect(Unit) {
        if (!locationService.hasPermission()) { landings = emptyList(); return@LaunchedEffect }
        // Discovery remounts on every tab switch; one lookup per few minutes is plenty.
        nearYouCache?.let { (at, cached) ->
            if (System.currentTimeMillis() - at < NEAR_YOU_TTL_MS) { landings = cached; return@LaunchedEffect }
        }
        val fix = locationService.fetchCurrentLocation() ?: return@LaunchedEffect
        val result = runCatching { LandingService.nearby(fix.lat, fix.lng, fix.accuracyMeters.toDouble()) }
            .getOrNull() ?: return@LaunchedEffect
        nearYouCache = System.currentTimeMillis() to result
        landings = result
    }
    if (landings.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Near you", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(landings, key = { it.landingID }) { l ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                        .clickable { LandingPresenter.open(LandingPresenter.Screen.Guest(l.slug, null, LandingSource.NEARBY)) }
                        .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LandingLogo(l.name, l.logoURL, 26.dp)
                    Text(l.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                    Text(distanceLabel(l.distanceM), fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/**
 * Own profile: "My perks" once the guest holds any, and — for a business —
 * the Landing tab (or "Open your Landing" when there isn't one yet; the owner
 * screen sorts out which).
 */
@Composable
fun ProfileLandingEntries(isBusiness: Boolean, modifier: Modifier = Modifier) {
    val perks by remember { LandingService.myPerks() }.collectAsState(initial = emptyList())
    val activeCount = perks.count { it.isActive() }
    val readyCount = perks.count { it.effectiveStatus() == "issued" }
    if (perks.isEmpty() && !isBusiness) return

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (perks.isNotEmpty()) {
            ProfileEntryChip(
                title = "My perks",
                detail = when {
                    activeCount > 0 -> "$activeCount active"
                    readyCount > 0 -> "$readyCount ready"
                    else -> "History"
                },
                highlight = activeCount > 0 || readyCount > 0,
                modifier = Modifier.weight(1f)
            ) { LandingPresenter.open(LandingPresenter.Screen.MyPerks) }
        }
        if (isBusiness) {
            ProfileEntryChip(
                title = "Landing",
                detail = "Status, numbers, TV",
                highlight = false,
                modifier = Modifier.weight(1f)
            ) { LandingPresenter.open(LandingPresenter.Screen.Owner) }
        }
    }
}

/**
 * Someone else's business profile: a Landing tab when that restaurant has a
 * live Landing. Opens it with source "profile". Renders nothing otherwise.
 */
@Composable
fun RestaurantProfileLandingEntry(businessID: String, modifier: Modifier = Modifier) {
    var landing by remember(businessID) { mutableStateOf<Landing?>(null) }
    LaunchedEffect(businessID) { landing = LandingService.liveLandingFor(businessID) }
    val l = landing ?: return
    val offer = l.welcomeOffer?.takeIf { it.active }
    ProfileEntryChip(
        title = "Landing",
        detail = offer?.let { "Free ${it.title} with any order" } ?: "See what's on tonight",
        highlight = offer != null,
        modifier = modifier.fillMaxWidth()
    ) {
        LandingPresenter.open(LandingPresenter.Screen.Guest(l.slug, null, LandingSource.PROFILE))
    }
}

@Composable
private fun ProfileEntryChip(title: String, detail: String, highlight: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val colors = com.stitchsocial.club.ui.theme.AppTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, if (highlight) LandingColors.accent.copy(alpha = 0.7f) else colors.hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        Text(detail, fontSize = 12.sp, color = if (highlight) LandingColors.accent else colors.textSecondary)
    }
}
