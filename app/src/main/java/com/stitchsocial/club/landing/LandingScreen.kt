package com.stitchsocial.club.landing

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.stitchsocial.club.events.LocationService
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.ui.theme.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The guest Landing page (LANDINGS_CONTRACT · App surfaces).
 *
 * Header, then the two-step checklist card, then "Tonight at …" (today's
 * moments tagged to the Landing), then the restaurant's own posts. The card
 * walks through: not claimed → claimed (Use now) → step 2 (check in, record)
 * → unlocking → both earned. Mockups: Main / Claimed / Counter / Earned.
 *
 * Perks are read live from Firestore, so the post perk the server issues
 * after an upload replaces "Unlocking…" the moment it exists.
 *
 * @param onRecord opens the app's recorder. LandingPostRouter is armed first,
 *   so the post that comes out of it carries this Landing's fields.
 * @param onOpenVideo plays a moment fullscreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandingScreen(
    slug: String,
    spot: String?,
    source: String = LandingSource.forLink(spot),
    autoClaim: Boolean = false,
    onDismiss: () -> Unit,
    onRecord: () -> Unit,
    onOpenVideo: (CoreVideoMetadata) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val locationService = remember { LocationService(context) }

    var landing by remember(slug) { mutableStateOf<Landing?>(null) }
    var loading by remember(slug) { mutableStateOf(true) }
    var loadFailed by remember(slug) { mutableStateOf(false) }

    val allPerks by remember { LandingService.myPerks() }.collectAsState(initial = emptyList())
    val posted by LandingPostRouter.posted.collectAsState()

    var moments by remember(slug) { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var ownPosts by remember(slug) { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }

    var claiming by remember { mutableStateOf(false) }
    var checkingIn by remember { mutableStateOf(false) }
    var showAccessEntry by remember { mutableStateOf(false) }
    var confirmPerk by remember { mutableStateOf<Perk?>(null) }
    var activating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(slug) {
        loading = true
        loadFailed = false
        landing = runCatching { LandingService.resolve(slug) }
            .onFailure { loadFailed = true }
            .getOrNull()
        loading = false
    }

    val l = landing
    val myPerks = remember(allPerks, l?.landingID) { allPerks.filter { it.landingID == l?.landingID } }
    val welcome = myPerks.firstOrNull { it.isWelcome }
    val todayPostID = l?.let { LandingService.todayPostPerkID(it.landingID, uid) }
    // Today's post perk first; otherwise the newest unspent one from an
    // earlier visit, which is the one the guest came back to use.
    val postPerk = myPerks.firstOrNull { it.perkID == todayPostID }
        ?: myPerks.firstOrNull { !it.isWelcome && it.effectiveStatus() in setOf("issued", "active") }
    val postedVideoID = l?.let { posted[it.landingID] }
    val unlocking = postedVideoID != null && myPerks.none { it.perkID == todayPostID }

    // Moments refresh when the guest's own post lands, so it shows up in the strip.
    LaunchedEffect(l?.landingID, postedVideoID) {
        val current = l ?: return@LaunchedEffect
        moments = LandingService.todaysMoments(current.landingID)
        ownPosts = LandingService.restaurantPosts(current.businessID)
    }

    fun explain(e: Throwable): String = when ((e as? LandingException)?.reason) {
        "device_limit" -> "This phone already claimed this perk."
        "not_new_customer" -> "This welcome offer is for first-time guests."
        "offer_inactive" -> "This offer isn't running right now."
        "landing_not_live" -> "This Landing isn't active right now."
        "not_on_site" -> "We couldn't confirm you're here."
        "bad_access_number" -> "That code doesn't match the sign."
        else -> if ((e as? LandingException)?.status == "RESOURCE_EXHAUSTED")
            "Too many tries. Try again in an hour."
        else "Something went wrong. Try again."
    }

    fun claim() {
        val current = l ?: return
        claiming = true
        scope.launch {
            runCatching { LandingService.claimWelcome(context, current.landingID, spot, source) }
                .onFailure { e ->
                    // Already claimed means the perk exists — the listener shows it.
                    if ((e as? LandingException)?.reason != "already_claimed") message = explain(e)
                }
            claiming = false
        }
    }

    // autoClaim (a sponsored card's CTA): claim once when the Landing is up,
    // unless the guest already holds the welcome perk. The perks listener
    // then swaps the claim card for the ticket. Keyed on slug, so a
    // recomposition never claims twice.
    var autoClaimed by remember(slug) { mutableStateOf(false) }
    LaunchedEffect(slug, l?.landingID) {
        val current = l ?: return@LaunchedEffect
        if (!autoClaim || autoClaimed) return@LaunchedEffect
        autoClaimed = true
        if (!current.isLive || current.welcomeOffer?.active != true) return@LaunchedEffect
        // The first real snapshot, not the listener's empty initial value.
        val held = runCatching { LandingService.myPerks().first() }.getOrDefault(emptyList())
            .any { it.landingID == current.landingID && it.isWelcome }
        if (!held) claim()
    }

    fun startRecording(visitID: String) {
        val current = l ?: return
        LandingPostRouter.arm(current.landingID, spot, visitID)
        onRecord()
    }

    // Record: check in by location first. Denied, or not on site, falls back
    // to the access number printed with the QR.
    fun checkInWithLocation() {
        val current = l ?: return
        checkingIn = true
        scope.launch {
            val fix = locationService.fetchCurrentLocation()
            if (fix == null) {
                checkingIn = false
                showAccessEntry = true
                return@launch
            }
            runCatching {
                LandingService.checkIn(
                    current.landingID, spot,
                    lat = fix.lat, lng = fix.lng, accuracyM = fix.accuracyMeters.toDouble()
                )
            }.onSuccess { visitID ->
                checkingIn = false
                startRecording(visitID)
            }.onFailure { e ->
                checkingIn = false
                if ((e as? LandingException)?.reason == "not_on_site") showAccessEntry = true
                else message = explain(e)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) checkInWithLocation() else showAccessEntry = true
    }

    fun record() {
        if (locationService.hasPermission()) checkInWithLocation()
        else permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    fun share() {
        val current = l ?: return
        val link = "https://stitchsocial.me/l/${current.slug}"
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    },
                    null
                )
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Top bar — back, LANDING, share (Main.dc).
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                }
                Text(
                    "LANDING",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { share() }, enabled = l != null) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LandingColors.accent)
                }
                l == null -> NotActive(
                    if (loadFailed) "Couldn't load this Landing. Check your connection." else "This Landing isn't active right now."
                )
                !l.isLive -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    LandingHeader(l)
                    NotActive("This Landing isn't active right now.")
                }
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = 32.dp)
                ) {
                    LandingHeader(l, following = welcome != null)

                    Checklist(
                        landing = l,
                        welcome = welcome,
                        postPerk = postPerk,
                        unlocking = unlocking,
                        claiming = claiming,
                        checkingIn = checkingIn,
                        onClaim = { claim() },
                        onUse = { perk ->
                            if (perk.isActive()) LandingPresenter.activePerk.value = perk
                            else confirmPerk = perk
                        },
                        onRecord = { record() },
                        onAccessNumber = { showAccessEntry = true }
                    )

                    if (moments.isNotEmpty()) {
                        MomentStrip(
                            title = "Tonight at ${l.name}",
                            count = "${moments.size} ${if (moments.size == 1) "moment" else "moments"}",
                            videos = moments,
                            onOpen = onOpenVideo
                        )
                    }
                    if (ownPosts.isNotEmpty()) {
                        MomentStrip(
                            title = "From ${l.name}",
                            count = null,
                            videos = ownPosts,
                            onOpen = onOpenVideo
                        )
                    }
                    Text(
                        "${l.name} may play your moment on its TVs. Moments posted for a perk are labeled.",
                        fontSize = 12.sp, color = colors.textSecondary, textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
                    )
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
                                .onSuccess { active ->
                                    LandingPresenter.activePerk.value = active ?: perk.copy(
                                        status = "active",
                                        activatedAt = Date(),
                                        activeUntil = Date(System.currentTimeMillis() + 15 * 60_000)
                                    )
                                }
                                .onFailure { message = explain(it) }
                            activating = false
                            confirmPerk = null
                        }
                    }) { Text(if (activating) "Starting…" else "Use now") }
                },
                dismissButton = { TextButton(enabled = !activating, onClick = { confirmPerk = null }) { Text("Not yet") } }
            )
        }

        if (showAccessEntry && l != null) {
            AccessNumberDialog(
                landingName = l.name,
                onSubmit = { code, onResult ->
                    scope.launch {
                        runCatching { LandingService.checkIn(l.landingID, spot, accessCode = code) }
                            .onSuccess { visitID ->
                                onResult(null)
                                showAccessEntry = false
                                startRecording(visitID)
                            }
                            .onFailure { onResult(explain(it)) }
                    }
                },
                onDismiss = { showAccessEntry = false }
            )
        }
    }
}

// MARK: - Header

@Composable
private fun LandingHeader(landing: Landing, following: Boolean = false) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(168.dp + 32.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(LandingColors.coverFallback)
        ) {
            if (landing.coverURL != null) {
                AsyncImage(
                    model = landing.coverURL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        LandingLogo(
            name = landing.name,
            logoURL = landing.logoURL,
            size = 72.dp,
            ring = colors.bg,
            modifier = Modifier.align(Alignment.BottomStart).offset(x = 16.dp)
        )
    }
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                landing.name,
                fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary,
                maxLines = 1, modifier = Modifier.weight(1f)
            )
            // Hours are free text, so this shows them rather than computing "open".
            if (landing.hours.isNotBlank()) {
                LandingPill(landing.hours, LandingColors.doneSurface, LandingColors.done)
            }
        }
        val sub = listOfNotNull(
            landing.neighborhood.takeIf { it.isNotBlank() },
            if (following) "Following" else null
        ).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, fontSize = 14.sp, color = colors.textSecondary)
    }
}

@Composable
private fun NotActive(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 16.sp, color = AppTheme.colors.textSecondary, textAlign = TextAlign.Center)
    }
}

// MARK: - Checklist

@Composable
private fun Checklist(
    landing: Landing,
    welcome: Perk?,
    postPerk: Perk?,
    unlocking: Boolean,
    claiming: Boolean,
    checkingIn: Boolean,
    onClaim: () -> Unit,
    onUse: (Perk) -> Unit,
    onRecord: () -> Unit,
    onAccessNumber: () -> Unit
) {
    val colors = AppTheme.colors
    val welcomeOffer = landing.welcomeOffer?.takeIf { it.active }
    val postOffer = landing.postOffer?.takeIf { it.active }

    // Step 1 not done yet: the single card from Main.dc.
    if (welcome == null && welcomeOffer != null) {
        Column(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (postOffer != null) "2 free things tonight" else "A free thing tonight",
                    fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (postOffer != null) Text("Step 1 of 2", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textSecondary)
            }
            StepRow(
                badge = { StepBadge("1", active = true) },
                title = "Free ${welcomeOffer.title}",
                subtitle = "One tap to claim. Tap Use now when you order."
            )
            LandingButton("Claim your free ${welcomeOffer.title}", onClick = onClaim, busy = claiming)
            Text(
                "Claiming follows ${landing.name}. Use it in person within 7 days.",
                fontSize = 12.sp, color = colors.textSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().offset(y = (-4).dp)
            )
            if (postOffer != null) {
                HorizontalDivider(color = colors.hairline)
                StepRow(
                    badge = { StepBadge(null, active = false) },
                    title = "Free ${postOffer.title}",
                    subtitle = "Post a moment while you're here.",
                    muted = true
                )
            }
        }
        return
    }

    // Claimed (or no welcome offer): progress, the ticket(s), step 2.
    val postEarned = postPerk != null
    val welcomeSpent = welcome != null && welcome.effectiveStatus() in setOf("used", "expired", "void")

    if (welcome != null && postOffer != null) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LandingPill("1 · Claimed", LandingColors.done, LandingColors.onAccent)
            Box(Modifier.weight(1f).height(2.dp).background(LandingColors.done))
            if (postEarned) LandingPill("2 · Earned", LandingColors.done, LandingColors.onAccent)
            else LandingPill("2 · Up next", LandingColors.accent, LandingColors.onAccent)
        }
    }

    if (postEarned && welcomeSpent) {
        // Earned.dc — both done.
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(LandingColors.doneSurface)
                .border(1.dp, Color(0xFF1F5A45), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = LandingColors.done)
            Column {
                Text("Both rewards earned", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFF4F4F8))
                Text("Your moment is posted and may play on ${possessive(landing.name)} TVs.", fontSize = 12.sp, color = Color(0xFFA7DCC7))
            }
        }
    }

    if (welcome != null) {
        if (welcomeSpent) SpentTicket(label = "Welcome perk", perk = welcome)
        else PerkTicket(
            label = if (landing.isCounter) "Welcome perk · claimed" else "Welcome perk",
            perk = welcome,
            detail = "With any order · good for 7 days",
            useLabel = if (landing.isCounter) "Use now at the register" else "Use now",
            footnote = "Tap when you order. It stays active for 15 minutes.",
            onUse = onUse
        )
    }

    when {
        postPerk != null -> {
            val status = postPerk.effectiveStatus()
            if (status in setOf("used", "expired", "void")) SpentTicket(label = "Moment perk", perk = postPerk)
            else PerkTicket(
                label = "Moment perk",
                perk = postPerk,
                detail = if (postPerk.isWaiting()) "Good from your next visit · for 7 days"
                else "Good tonight or any visit in the next 7 days",
                useLabel = if (postPerk.isWaiting()) "Good from your next visit" else "Use now",
                footnote = null,
                onUse = onUse
            )
        }
        postOffer != null -> PostStepCard(
            landing = landing,
            offer = postOffer,
            unlocking = unlocking,
            checkingIn = checkingIn,
            onRecord = onRecord,
            onAccessNumber = onAccessNumber
        )
    }
}

@Composable
private fun StepRow(badge: @Composable () -> Unit, title: String, subtitle: String, muted: Boolean = false) {
    val colors = AppTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        badge()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                color = if (muted) colors.textPrimary.copy(alpha = 0.75f) else colors.textPrimary
            )
            Text(subtitle, fontSize = 13.sp, color = colors.textSecondary)
        }
    }
}

@Composable
private fun StepBadge(number: String?, active: Boolean) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (active) LandingColors.accent else colors.surfaceStrong)
            .then(if (!active) Modifier.border(1.dp, colors.hairline, androidx.compose.foundation.shape.CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (number != null) Text(number, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = LandingColors.onAccent)
        else Icon(Icons.Default.Lock, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
    }
}

/**
 * The light ticket (Claimed.dc / Earned.dc). Inverted against the page —
 * textPrimary as the card, bg as the ink — so it reads as a ticket in either
 * theme.
 */
@Composable
private fun PerkTicket(
    label: String,
    perk: Perk,
    detail: String,
    useLabel: String,
    footnote: String?,
    onUse: (Perk) -> Unit
) {
    val colors = AppTheme.colors
    val ink = colors.bg
    val active = perk.isActive()
    val usable = perk.isUsable() || active
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.textPrimary)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = ink.copy(alpha = 0.6f))
            Text("Free ${perk.title}", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = ink)
            Text(detail, fontSize = 13.sp, color = ink.copy(alpha = 0.75f))
        }
        LandingButton(
            text = if (active) "Active now · show ticket" else useLabel,
            onClick = { onUse(perk) },
            container = ink,
            content = colors.textPrimary,
            enabled = usable
        )
        if (footnote != null && usable) {
            Text(
                footnote, fontSize = 12.sp, color = ink.copy(alpha = 0.75f), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().offset(y = (-4).dp)
            )
        }
    }
}

/** A used or expired perk, with the stamp (Earned.dc "USED 7:41 PM"). */
@Composable
private fun SpentTicket(label: String, perk: Perk) {
    val colors = AppTheme.colors
    val status = perk.effectiveStatus()
    val time = (perk.usedAt ?: perk.activeUntil)?.let { SimpleDateFormat("h:mm a", Locale.US).format(it) }
    val stamp = when (status) {
        "used" -> listOfNotNull("USED", time).joinToString(" ")
        "expired" -> "EXPIRED"
        else -> "VOID"
    }
    val stampColor = if (status == "used") LandingColors.done else colors.textSecondary
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = colors.textSecondary)
            Text("Free ${perk.title}", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary.copy(alpha = 0.75f))
        }
        Text(
            stamp,
            fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = stampColor,
            modifier = Modifier
                .rotate(-6f)
                .border(2.dp, stampColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** Step 2 (Claimed.dc / Counter.dc): post a moment for the post perk. */
@Composable
private fun PostStepCard(
    landing: Landing,
    offer: LandingOffer,
    unlocking: Boolean,
    checkingIn: Boolean,
    onRecord: () -> Unit,
    onAccessNumber: () -> Unit
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(2.dp, LandingColors.accent, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E2F36)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Videocam, contentDescription = null, tint = LandingColors.accent, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Post a moment, get free ${offer.title}" + if (offer.isNextVisit) " next visit" else "",
                    fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary
                )
                Text(
                    if (offer.isNextVisit) "Film your food once it's up. The reward waits in your perks for your next trip."
                    else "The plate arriving, your table, the vibe. It unlocks the second your upload finishes.",
                    fontSize = 13.sp, color = colors.textSecondary
                )
            }
        }
        if (unlocking) {
            LandingButton("Unlocking…", onClick = {}, busy = true)
        } else {
            LandingButton("Record a moment", onClick = onRecord, busy = checkingIn)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "We'll confirm you're at ${landing.name} first. No location?",
                    fontSize = 12.sp, color = colors.textSecondary, textAlign = TextAlign.Center
                )
                Text(
                    "Enter the code on the sign",
                    fontSize = 12.sp, color = LandingColors.accent, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(onClick = onAccessNumber).padding(vertical = 4.dp)
                )
            }
        }
    }
}

// MARK: - Moments

@Composable
private fun MomentStrip(title: String, count: String?, videos: List<CoreVideoMetadata>, onOpen: (CoreVideoMetadata) -> Unit) {
    val colors = AppTheme.colors
    Column(modifier = Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary, modifier = Modifier.weight(1f))
            if (count != null) Text(count, fontSize = 13.sp, color = colors.textSecondary)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(videos, key = { it.id }) { video -> MomentCard(video) { onOpen(video) } }
        }
    }
}

@Composable
private fun MomentCard(video: CoreVideoMetadata, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .width(112.dp)
            .height(168.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF24324A))
            .clickable(onClick = onTap)
    ) {
        if (video.thumbnailURL.isNotBlank()) {
            AsyncImage(
                model = video.thumbnailURL,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (video.incentivized) PostedForPerkChip()
            val handle = "@${video.creatorName}" + (video.landingSpot?.let { " · ${spotLabel(it)}" } ?: "")
            Text(handle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        }
    }
}

/** "Broritos" → "Broritos'", "Mia" → "Mia's". */
internal fun possessive(name: String): String = if (name.endsWith("s")) "$name'" else "$name's"

/** "6" → "Table 6"; anything with letters ("counter", "the pass") reads as is. */
internal fun spotLabel(spot: String): String =
    if (spot.all { it.isDigit() }) "Table $spot" else spot.replaceFirstChar { it.uppercase() }

// MARK: - Access number

@Composable
private fun AccessNumberDialog(
    landingName: String,
    onSubmit: (code: String, onResult: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Enter the code on the sign") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("It's the 4-digit number printed with the QR at $landingName.", fontSize = 14.sp)
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(4); error = null },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    placeholder = { Text("0000") }
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = code.length == 4 && !busy, onClick = {
                busy = true
                onSubmit(code) { err ->
                    busy = false
                    error = err
                }
            }) { Text(if (busy) "Checking…" else "Check in") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}
