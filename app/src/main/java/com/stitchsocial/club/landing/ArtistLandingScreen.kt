package com.stitchsocial.club.landing

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.stitchsocial.club.FollowManager
import com.stitchsocial.club.events.LocationService
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.AccountStanding
import com.stitchsocial.club.services.TeenSafety
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.views.TipButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * An artist's Landing, as the fan sees it: one permanent page whose place moves
 * with the tour. Header, date row, show card (check in), the two perks, the
 * song thread, the shows ladder, the big-screen note, then moments.
 *
 * The server decides which show is open. currentStopID and currentDayKey are
 * null between shows, and check-in stays closed then. The post perk's id uses
 * currentDayKey, because a stop's day isn't New York's.
 *
 * Layer 5: Views
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistLandingScreen(
    initial: Landing,
    spot: String?,
    source: String,
    autoClaim: Boolean,
    onDismiss: () -> Unit,
    onRecord: () -> Unit,
    onStitch: (CoreVideoMetadata) -> Unit,
    onOpenVideo: (CoreVideoMetadata) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val locationService = remember { LocationService(context) }
    val followManager = remember { FollowManager(context) }

    var l by remember(initial.landingID) { mutableStateOf(initial) }
    // A teen sees only the header until the account is approved for them.
    // Blocked from the start, so nothing shows or claims before the read answers.
    var teenBlocked by remember(initial.landingID) { mutableStateOf(TeenSafety.viewerIsTeen) }
    var teenChecked by remember(initial.landingID) { mutableStateOf(!TeenSafety.viewerIsTeen) }
    LaunchedEffect(initial.landingID, initial.businessID) {
        if (!teenChecked) {
            teenBlocked = !LandingService.accountAllowsTeens(initial.businessID)
            teenChecked = true
        }
    }
    val allPerks by remember { LandingService.myPerks() }.collectAsState(initial = emptyList())
    val posted by LandingPostRouter.posted.collectAsState()
    val followStates by followManager.followingStates.collectAsState()

    val stops = remember(l.tourStops) {
        val now = Date()
        l.tourStops.filter { it.closesAt == null || it.closesAt.after(now) }.sortedBy { it.date }
    }
    var selectedID by remember(l.landingID) { mutableStateOf(l.currentStopID ?: stops.firstOrNull()?.stopID) }
    val selected = stops.firstOrNull { it.stopID == selectedID } ?: stops.firstOrNull()
    val currentStop = stops.firstOrNull { it.stopID == l.currentStopID }
    val isSelectedOpen = selected != null && selected.stopID == currentStop?.stopID

    var hook by remember(l.landingID) { mutableStateOf<CoreVideoMetadata?>(null) }
    var replies by remember(l.landingID) { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var moments by remember(l.landingID) { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var ownPosts by remember(l.landingID) { mutableStateOf<List<CoreVideoMetadata>>(emptyList()) }
    var member by remember(l.landingID) { mutableStateOf<LandingMember?>(null) }
    var checkedInStopID by remember(l.landingID) { mutableStateOf<String?>(null) }
    var sessionTips by remember(l.landingID) { mutableIntStateOf(0) }

    var claiming by remember { mutableStateOf(false) }
    var checkingIn by remember { mutableStateOf(false) }
    var showAccessEntry by remember { mutableStateOf(false) }
    var recordAfterCheckIn by remember { mutableStateOf(false) }
    var rewardPerk by remember { mutableStateOf<Perk?>(null) }
    var confirmPerk by remember { mutableStateOf<Perk?>(null) }
    var activating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val myPerks = remember(allPerks, l.landingID) { allPerks.filter { it.landingID == l.landingID } }
    val welcome = myPerks.firstOrNull { it.isWelcome }
    val todayPostID = l.currentDayKey?.let { LandingService.postPerkID(l.landingID, uid, it) }
    val postPerk = myPerks.firstOrNull { it.perkID == todayPostID }
        ?: myPerks.firstOrNull { it.isPost && it.effectiveStatus() in setOf("issued", "active") }
    val rewardTickets = myPerks.filter { it.isVisit && it.effectiveStatus() in setOf("issued", "active") }
    val postedVideoID = posted[l.landingID]
    val unlocking = postedVideoID != null && todayPostID != null && myPerks.none { it.perkID == todayPostID }
    val checkedIn = currentStop != null &&
        (checkedInStopID == currentStop.stopID || member?.checkedInStopID == currentStop.stopID)
    val isSelf = uid == l.businessID
    val following = followStates[l.businessID] == true

    LaunchedEffect(l.landingID, l.songThread?.videoID, teenBlocked) {
        if (teenBlocked) return@LaunchedEffect
        val song = l.songThread ?: return@LaunchedEffect
        val video = LandingService.songHook(song.videoID) ?: return@LaunchedEffect
        hook = video
        replies = LandingService.songReplies(video)
    }
    LaunchedEffect(l.landingID, currentStop?.stopID, postedVideoID, teenBlocked) {
        if (teenBlocked) return@LaunchedEffect
        val opensAt = currentStop?.opensAt
        val closesAt = currentStop?.closesAt
        moments = if (opensAt != null && closesAt != null) LandingService.moments(l.landingID, opensAt, closesAt) else emptyList()
        ownPosts = LandingService.artistPosts(l.businessID)
    }
    suspend fun refreshMember() {
        if (uid.isEmpty()) return
        runCatching { LandingService.member(l.landingID) }.onSuccess { member = it }
    }
    LaunchedEffect(l.landingID, postedVideoID) { refreshMember() }
    // Claiming follows on the server, so the button re-reads after a claim.
    LaunchedEffect(l.businessID, welcome?.perkID) {
        if (!isSelf && l.businessID.isNotBlank()) followManager.refreshFollowState(l.businessID)
    }

    fun explain(e: Throwable): String = when ((e as? LandingException)?.reason) {
        "device_limit" -> "This phone already claimed this perk."
        "offer_inactive" -> "This offer isn't running right now."
        "landing_not_live" -> "This Landing isn't active right now."
        "no_show_today" -> "There's no show open right now. Check-in opens on show day."
        "not_on_site" -> "We couldn't confirm you're at the show."
        "bad_access_number" -> "That number doesn't match this show."
        else -> if ((e as? LandingException)?.status == "RESOURCE_EXHAUSTED")
            "Too many tries. Try again in an hour."
        else "Something went wrong. Try again."
    }

    // The page may have been open since before the window changed.
    fun refreshLanding() {
        scope.launch {
            runCatching { LandingService.resolve(l.slug) }.getOrNull()?.takeIf { it.isArtist }?.let { l = it }
        }
    }

    fun failCheckIn(e: Throwable) {
        if ((e as? LandingException)?.reason == "no_show_today") refreshLanding()
        message = explain(e)
    }

    fun claim() {
        if (teenBlocked) return
        claiming = true
        scope.launch {
            runCatching { LandingService.claimWelcome(context, l.landingID, spot, source) }
                .onFailure { e -> if ((e as? LandingException)?.reason != "already_claimed") message = explain(e) }
            claiming = false
        }
    }

    var autoClaimed by remember(l.landingID) { mutableStateOf(false) }
    LaunchedEffect(l.landingID, teenChecked, teenBlocked) {
        if (!autoClaim || autoClaimed || !teenChecked || teenBlocked) return@LaunchedEffect
        autoClaimed = true
        if (!l.isLive || l.welcomeOffer?.active != true) return@LaunchedEffect
        val held = runCatching { LandingService.myPerks().first() }.getOrDefault(emptyList())
            .any { it.landingID == l.landingID && it.isWelcome }
        if (!held) claim()
    }

    fun startRecording(visitID: String) {
        LandingPostRouter.arm(l.landingID, spot, visitID)
        onRecord()
    }

    fun afterCheckIn(result: CheckInResult) {
        checkedInStopID = result.stopID ?: l.currentStopID
        // The window moved while the page was open: the post perk needs the new day.
        if (result.dayKey != null && result.dayKey != l.currentDayKey) refreshLanding()
        result.perkIssued?.let { rewardPerk = it }
        scope.launch { refreshMember() }
        if (recordAfterCheckIn) startRecording(result.visitID)
    }

    fun checkInWithLocation() {
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
                    l.landingID, spot,
                    lat = fix.lat, lng = fix.lng, accuracyM = fix.accuracyMeters.toDouble()
                )
            }.onSuccess { result ->
                checkingIn = false
                afterCheckIn(result)
            }.onFailure { e ->
                checkingIn = false
                if ((e as? LandingException)?.reason == "not_on_site") showAccessEntry = true else failCheckIn(e)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) checkInWithLocation() else showAccessEntry = true
    }

    fun beginCheckIn(recordAfter: Boolean) {
        recordAfterCheckIn = recordAfter
        if (locationService.hasPermission()) checkInWithLocation()
        else permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    // Already checked in: the visit id is known, so no second location prompt.
    fun record() {
        if (!AccountStanding.guardPosting()) return
        val dayKey = l.currentDayKey
        if (checkedIn && dayKey != null) startRecording(LandingService.visitID(l.landingID, uid, dayKey))
        else beginCheckIn(recordAfter = true)
    }

    fun openLink(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme != "https" || uri.host !in setOf("open.spotify.com", "music.apple.com")) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    fun share() {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "https://stitchsocial.me/l/${l.slug}")
                    },
                    null
                )
            )
        }
    }

    fun use(perk: Perk) {
        if (perk.isActive()) LandingPresenter.activePerk.value = perk else confirmPerk = perk
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
        ) {
            ArtistHeader(
                landing = l,
                stopsLeft = stops.size,
                onBack = onDismiss,
                onShare = { share() },
                actions = {
                    if (teenBlocked) {
                        Spacer(Modifier.weight(1f))
                    } else if (!isSelf) {
                        LandingButton(
                            text = if (following) "Following" else "Follow",
                            onClick = { followManager.toggleFollow(l.businessID) },
                            container = if (following) colors.surfaceStrong else LandingColors.accent,
                            content = if (following) colors.textPrimary else LandingColors.onAccent,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    // A tip is always on a video: the song's hook, else the newest post.
                    (hook ?: ownPosts.firstOrNull())?.takeUnless { teenBlocked }?.let { video ->
                        TipButton(
                            videoID = video.id,
                            creatorID = video.creatorID,
                            currentUserID = uid,
                            coinTotal = video.coinTotal + sessionTips,
                            isSelfTip = isSelf || video.creatorID == uid,
                            onAction = null,
                            onTipSent = { sessionTips += it }
                        )
                    }
                }
            )

            if (teenBlocked) {
                NotActive("This artist isn't available on your account.")
                return@Column
            }

            if (!l.isLive) {
                NotActive("This Landing isn't active right now.")
                return@Column
            }

            if (stops.isNotEmpty()) {
                StopRow(stops = stops, selectedID = selected?.stopID, onSelect = { selectedID = it })
            }
            if (selected != null) {
                ShowCard(
                    stop = selected,
                    isOpen = isSelectedOpen,
                    // The artist has nothing to check in to or claim: it's their page.
                    isOwner = isSelf,
                    checkedIn = isSelectedOpen && checkedIn,
                    checkingIn = checkingIn && !recordAfterCheckIn,
                    onCheckIn = { beginCheckIn(recordAfter = false) },
                    onAccessNumber = {
                        recordAfterCheckIn = false
                        showAccessEntry = true
                    }
                )
            } else {
                NotActive("No upcoming shows right now.")
            }

            if (!isSelf) ArtistPerks(
                landing = l,
                isOpen = currentStop != null,
                welcome = welcome,
                postPerk = postPerk,
                unlocking = unlocking,
                claiming = claiming,
                recording = checkingIn && recordAfterCheckIn,
                onClaim = { claim() },
                onUse = { use(it) },
                onRecord = { record() }
            )
            rewardTickets.forEach { perk ->
                PerkTicket(
                    label = "Show ${perk.milestone ?: ""} reward",
                    perk = perk,
                    detail = perk.expiresAt?.let { "Use by ${shortDate(it)}" } ?: "",
                    useLabel = "Use now",
                    footnote = "Tap when you pick it up. It stays active for 15 minutes.",
                    onUse = { use(it) }
                )
            }

            SongThreadCard(
                landing = l,
                hook = hook,
                replies = replies,
                onOpen = onOpenVideo,
                onReply = onStitch,
                onLink = { openLink(it) }
            )

            if (l.visitRewards.isNotEmpty()) {
                val shows = member?.visitDays ?: 0
                VisitCard(
                    landing = l,
                    member = member,
                    checkingIn = false,
                    onCheckIn = {},
                    title = "Keep coming back · $shows ${if (shows == 1) "show" else "shows"}",
                    unit = "show",
                    showCheckIn = false
                )
            }

            Column(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("On the big screen", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
                Text(
                    "Approved moments may play on the screen at ${selected?.venue?.takeIf { it.isNotBlank() } ?: "the show"}. Moments posted for a perk are labeled.",
                    fontSize = 13.sp, color = colors.textSecondary
                )
            }

            if (moments.isNotEmpty()) {
                MomentStrip(
                    title = "Tonight at ${currentStop?.venue ?: l.name}",
                    count = "${moments.size} ${if (moments.size == 1) "moment" else "moments"}",
                    videos = moments,
                    onOpen = onOpenVideo,
                    showSpots = false
                )
            }
            if (ownPosts.isNotEmpty()) {
                MomentStrip(title = "From ${l.name}", count = null, videos = ownPosts, onOpen = onOpenVideo, showSpots = false)
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

        if (showAccessEntry) {
            AccessNumberDialog(
                landingName = l.name,
                title = "Enter your show number",
                body = "It's the 4-digit number on your wristband or the signs at ${currentStop?.venue ?: "the venue"}.",
                onSubmit = { code, onResult ->
                    scope.launch {
                        runCatching { LandingService.checkIn(l.landingID, spot, accessCode = code) }
                            .onSuccess { result ->
                                onResult(null)
                                showAccessEntry = false
                                afterCheckIn(result)
                            }
                            .onFailure { e ->
                                if ((e as? LandingException)?.reason == "no_show_today") refreshLanding()
                                onResult(explain(e))
                            }
                    }
                },
                onDismiss = { showAccessEntry = false }
            )
        }

        rewardPerk?.let { perk ->
            ModalBottomSheet(onDismissRequest = { rewardPerk = null }, containerColor = colors.surface) {
                VisitRewardSheet(
                    perk = perk,
                    unit = "Show",
                    hint = "Tap Use now when you pick it up. It stays active for 15 minutes.",
                    onUse = {
                        rewardPerk = null
                        confirmPerk = perk
                    },
                    onLater = { rewardPerk = null }
                )
            }
        }
    }
}

private fun shortDate(date: Date): String = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(date)

private fun stopDate(stop: TourStop): LocalDate? = runCatching { LocalDate.parse(stop.date) }.getOrNull()

// MARK: - Header

@Composable
private fun ArtistHeader(
    landing: Landing,
    stopsLeft: Int,
    onBack: () -> Unit,
    onShare: () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    val colors = AppTheme.colors
    Box(modifier = Modifier.fillMaxWidth().height(440.dp).background(LandingColors.coverFallback)) {
        (landing.coverURL ?: landing.logoURL)?.let { image ->
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f),
                    0.2f to Color.Transparent,
                    0.45f to Color.Transparent,
                    0.72f to colors.bg.copy(alpha = 0.92f),
                    0.82f to colors.bg
                )
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (stopsLeft > 0) {
                Text(
                    "ARTIST · $stopsLeft ${if (stopsLeft == 1) "DATE" else "DATES"} LEFT ON TOUR",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = colors.textSecondary
                )
            }
            Text(
                landing.name.uppercase(),
                fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Black,
                color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = actions)
        }
    }
}

// MARK: - Dates

@Composable
private fun StopRow(stops: List<TourStop>, selectedID: String?, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 14.dp)
    ) {
        items(stops, key = { it.stopID }) { stop ->
            StopChip(stop, selected = stop.stopID == selectedID) { onSelect(stop.stopID) }
        }
    }
}

@Composable
private fun StopChip(stop: TourStop, selected: Boolean, onTap: () -> Unit) {
    val colors = AppTheme.colors
    val date = stopDate(stop)
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(shape)
            .background(if (selected) LandingColors.accent.copy(alpha = 0.16f) else colors.surface)
            .border(1.dp, if (selected) LandingColors.accent else colors.hairline, shape)
            .clickable(onClick = onTap)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            date?.format(DateTimeFormatter.ofPattern("MMM", Locale.US))?.uppercase() ?: "",
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (selected) LandingColors.accent else colors.textSecondary
        )
        Text(date?.dayOfMonth?.toString() ?: "", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
        Text(stop.city, fontSize = 11.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ShowCard(
    stop: TourStop,
    isOpen: Boolean,
    checkedIn: Boolean,
    checkingIn: Boolean,
    onCheckIn: () -> Unit,
    onAccessNumber: () -> Unit,
    isOwner: Boolean = false
) {
    val colors = AppTheme.colors
    val date = stopDate(stop)?.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (isOpen) LandingPill("TONIGHT", Color(0xFFE5342B), Color.White)
        else LandingPill("UPCOMING", colors.surfaceStrong, colors.textPrimary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stop.venue, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
            Text(
                listOfNotNull(date, listOf(stop.city, stop.country).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                fontSize = 14.sp, color = colors.textSecondary
            )
        }
        when {
            isOwner -> Text("This is your page. Fans check in and claim perks here.", fontSize = 13.sp, color = colors.textSecondary)
            checkedIn -> LandingPill("✓ Checked in at ${stop.venue}", LandingColors.doneSurface, LandingColors.done)
            isOpen -> {
                LandingButton("I'm here. Check in", onClick = onCheckIn, busy = checkingIn)
                TextButton(onClick = onAccessNumber, modifier = Modifier.fillMaxWidth()) {
                    Text("Location off? Use the number on your wristband", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LandingColors.accent)
                }
            }
            else -> LandingButton("Check-in opens on show day", onClick = {}, enabled = false)
        }
    }
}

// MARK: - Perks

@Composable
private fun ArtistPerks(
    landing: Landing,
    isOpen: Boolean,
    welcome: Perk?,
    postPerk: Perk?,
    unlocking: Boolean,
    claiming: Boolean,
    recording: Boolean,
    onClaim: () -> Unit,
    onUse: (Perk) -> Unit,
    onRecord: () -> Unit
) {
    val colors = AppTheme.colors
    val welcomeOffer = landing.welcomeOffer?.takeIf { it.active }
    val postOffer = landing.postOffer?.takeIf { it.active }
    val spent = setOf("used", "expired", "void")

    if (welcome == null && welcomeOffer != null) {
        Column(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val where = if (isOpen) "tonight" else "on this tour"
            Text(
                if (postOffer != null) "2 free things $where" else "A free thing $where",
                fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary
            )
            StepRow(
                badge = { StepBadge("1", active = true) },
                title = "Free ${welcomeOffer.title} when you follow",
                subtitle = welcomeOffer.smallPrint.ifBlank { "One tap to claim. Use it in person at the show." }
            )
            LandingButton("Claim your free ${welcomeOffer.title}", onClick = onClaim, busy = claiming)
            Text(
                "Claiming follows ${landing.name}. One per fan for the whole tour.",
                fontSize = 12.sp, color = colors.textSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (postOffer != null) {
                HorizontalDivider(color = colors.hairline)
                StepRow(
                    badge = { StepBadge(null, active = false) },
                    title = "Free ${postOffer.title} when you post a moment",
                    subtitle = "Check in at the show, then post from inside the venue.",
                    muted = true
                )
            }
        }
        return
    }

    if (welcome != null) {
        if (welcome.effectiveStatus() in spent) SpentTicket(label = "Welcome perk", perk = welcome)
        else PerkTicket(
            label = "Welcome perk",
            perk = welcome,
            detail = welcome.expiresAt?.let { "Use by ${shortDate(it)}" } ?: "Use it in person at the show",
            useLabel = "Use now",
            footnote = "Tap when you pick it up. It stays active for 15 minutes.",
            onUse = onUse
        )
    }

    when {
        postPerk != null ->
            if (postPerk.effectiveStatus() in spent) SpentTicket(label = "Moment perk", perk = postPerk)
            else PerkTicket(
                label = "Moment perk",
                perk = postPerk,
                detail = if (postPerk.isWaiting()) "Good from your next show" else
                    postPerk.expiresAt?.let { "Use by ${shortDate(it)}" } ?: "Ready to use",
                useLabel = if (postPerk.isWaiting()) "Good from your next show" else "Use now",
                footnote = null,
                onUse = onUse
            )
        postOffer != null -> Column(
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
                    Text("Free ${postOffer.title} when you post a moment", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
                    Text(
                        if (isOpen) "We'll confirm you're at the show, then the camera opens."
                        else "Check in at the show, then post from inside the venue.",
                        fontSize = 13.sp, color = colors.textSecondary
                    )
                }
            }
            when {
                unlocking -> LandingButton("Unlocking…", onClick = {}, busy = true)
                isOpen -> LandingButton("Post a moment", onClick = onRecord, busy = recording)
                else -> LandingButton("Opens on show day", onClick = {}, enabled = false)
            }
        }
    }
}

// MARK: - Song thread

@Composable
private fun SongThreadCard(
    landing: Landing,
    hook: CoreVideoMetadata?,
    replies: List<CoreVideoMetadata>,
    onOpen: (CoreVideoMetadata) -> Unit,
    onReply: (CoreVideoMetadata) -> Unit,
    onLink: (String) -> Unit
) {
    val colors = AppTheme.colors
    val hasLinks = landing.spotifyURL != null || landing.appleMusicURL != null
    if (hook == null && !hasLinks) return
    Column(modifier = Modifier.padding(top = 14.dp)) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (hook != null) "The song thread" else "Listen", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
                if (hook != null) Text("Hear the hook. Reply with your own video.", fontSize = 13.sp, color = colors.textSecondary)
            }
            if (hook != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(LandingColors.coverFallback)
                        .clickable { onOpen(hook) }
                ) {
                    if (hook.thumbnailURL.isNotBlank()) {
                        AsyncImage(
                            model = hook.thumbnailURL,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                    Box(
                        modifier = Modifier.align(Alignment.Center).size(58.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play the hook", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            landing.songThread?.title?.takeIf { it.isNotBlank() } ?: hook.title,
                            fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        if (replies.isNotEmpty()) {
                            LandingPill(
                                when {
                                    replies.size >= 50 -> "50+ replies"
                                    replies.size == 1 -> "1 reply"
                                    else -> "${replies.size} replies"
                                },
                                Color.Black.copy(alpha = 0.7f), Color.White
                            )
                        }
                    }
                }
                LandingButton("Reply with video", onClick = { onReply(hook) })
            }
            if (hasLinks) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    landing.spotifyURL?.let { url ->
                        OutlinedButton(onClick = { onLink(url) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                            Text("Spotify", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                    landing.appleMusicURL?.let { url ->
                        OutlinedButton(onClick = { onLink(url) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                            Text("Apple Music", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (replies.isNotEmpty()) {
            MomentStrip(title = "Fan replies", count = null, videos = replies, onOpen = onOpen, showSpots = false)
        }
    }
}
