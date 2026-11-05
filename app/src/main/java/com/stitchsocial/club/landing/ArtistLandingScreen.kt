package com.stitchsocial.club.landing

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.stitchsocial.club.FollowManager
import com.stitchsocial.club.birthday.pauseFeedPlayback
import com.stitchsocial.club.challenge.GiveawayResultsView
import com.stitchsocial.club.events.LocationService
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.AccountStanding
import com.stitchsocial.club.services.BackgroundPostManager
import com.stitchsocial.club.services.BlockService
import com.stitchsocial.club.services.TeenSafety
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.views.ProfileModerationSheets
import com.stitchsocial.club.views.TipButton
import com.stitchsocial.club.views.buildStitchChain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * An artist's Landing, as the fan sees it. Five tabs under a hero that plays like
 * a music app: picking a track changes the song where it stands, and at the end
 * of a song the next one starts. The song's thread is the fan mail, and its
 * challenge, if any, is the giveaway. Tour holds the dates, show card, perks,
 * rewards and moments.
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
    onOpenVideo: (CoreVideoMetadata) -> Unit,
    onOpenThread: (threadID: String, videoID: String) -> Unit,
    asProfile: Boolean
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
    var trackVideos by remember(l.landingID) { mutableStateOf<Map<String, CoreVideoMetadata>>(emptyMap()) }
    /** Drops on each song, by the song's video id. */
    var replies by remember(l.landingID) { mutableStateOf<Map<String, List<CoreVideoMetadata>>>(emptyMap()) }
    // Someone who scanned a code at the venue came for the show.
    var tab by remember(l.landingID) { mutableStateOf(if (spot != null) ArtistTab.TOUR else ArtistTab.MUSIC) }
    /** The song in the hero. Null is the lead song, songThread. */
    var nowPlayingID by remember(l.landingID) { mutableStateOf<String?>(null) }
    val songPaused = MusicSession.current != null && !MusicSession.isAudible
    val song = remember { MusicSession.start(context); MusicSession.song!! }
    /** The saved place on this device has been read, once per visit. */
    var placeRestored by remember(l.landingID) { mutableStateOf(false) }
    var startingGiveaway by remember { mutableStateOf(false) }
    var showingWinners by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showingReport by remember { mutableStateOf(false) }
    var showingBlock by remember { mutableStateOf(false) }
    val scroll = remember(tab) { ScrollState(0) }
    val covered by LandingPresenter.covered
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

    suspend fun loadSong() {
        val found = LinkedHashMap<String, CoreVideoMetadata>()
        l.release?.tracks.orEmpty().mapNotNull { it.videoID }.distinct().forEach { id ->
            LandingService.songHook(id)?.let { found[id] = it }
        }
        trackVideos = found
        val song = l.songThread ?: return
        val video = found[song.videoID] ?: LandingService.songHook(song.videoID)
        hook = video
        if (video != null) replies = replies + (video.id to LandingService.songReplies(video))
    }

    /** What the hero shows: the song picked from the list, else the lead song. */
    val heroVideo = nowPlayingID?.let { trackVideos[it] } ?: hook
    val heroReplies = heroVideo?.let { replies[it.id] }.orEmpty()
    val songTitle = l.release?.title ?: l.songThread?.title?.takeIf { it.isNotBlank() } ?: l.name
    val heroTitle = l.release?.tracks?.firstOrNull { it.videoID != null && it.videoID == nowPlayingID }?.title ?: songTitle

    LaunchedEffect(l.landingID, l.songThread?.videoID, l.release, teenBlocked) {
        if (!teenBlocked) loadSong()
    }
    fun nowPlaying(video: CoreVideoMetadata) = MusicSession.Song(
        landingID = l.landingID,
        artistName = l.name,
        slug = l.slug,
        videoID = video.id,
        title = l.release?.tracks?.firstOrNull { it.videoID == video.id }?.title
            ?: if (video.id == l.songThread?.videoID) songTitle else video.title,
        artworkURL = video.thumbnailURL.ifBlank { l.coverURL ?: "" }.ifBlank { null },
        videoURL = video.playbackURL
    )
    LaunchedEffect(heroVideo?.id) {
        val video = heroVideo ?: return@LaunchedEffect
        var startMs = 0L
        if (!placeRestored) {
            placeRestored = true
            val playing = MusicSession.current
            if (playing != null && playing.landingID == l.landingID) {
                // Back from the bar: this page's song is already going.
                if (playing.videoID != video.id && trackVideos.containsKey(playing.videoID)) {
                    nowPlayingID = playing.videoID
                    return@LaunchedEffect
                }
            } else {
                song.savedPlace(l.landingID)?.let { (savedID, ms) ->
                    if (savedID == video.id) {
                        startMs = ms
                    } else if (trackVideos.containsKey(savedID)) {
                        // Back to the song that was playing. This runs again for it.
                        placeRestored = false
                        nowPlayingID = savedID
                        return@LaunchedEffect
                    }
                }
            }
        }
        if (!teenBlocked && video.videoURL.isNotBlank()) {
            // In tracklist order, for when a song ends after the viewer has left the page.
            val queue = l.release?.tracks.orEmpty().mapNotNull { it.videoID }.mapNotNull { trackVideos[it] }.map { nowPlaying(it) }
            MusicSession.play(nowPlaying(video), queue, startMs)
        }
        replies = replies + (video.id to LandingService.songReplies(video))
    }
    // The next song came up on its own.
    val playingID = MusicSession.current?.takeIf { it.landingID == l.landingID }?.videoID
    LaunchedEffect(playingID) {
        if (playingID != null && playingID != heroVideo?.id && trackVideos.containsKey(playingID)) nowPlayingID = playingID
    }
    DisposableEffect(Unit) {
        MusicSession.isOnArtistPage = true
        onDispose { MusicSession.isOnArtistPage = false }
    }
    // Something picked from this page takes over until it closes.
    if (covered) HoldsMusic()

    // A drop lands in the background after the recorder closes.
    val lastPosted by BackgroundPostManager.lastCreatedVideoID.collectAsState()
    LaunchedEffect(lastPosted) {
        if (lastPosted == null || teenBlocked) return@LaunchedEffect
        loadSong()
        heroVideo?.let { replies = replies + (it.id to LandingService.songReplies(it)) }
    }
    // Sound from here on, so nothing else plays underneath.
    LaunchedEffect(Unit) { pauseFeedPlayback(context) }

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

    /** Picking the song already playing pauses or resumes it. Picking another changes the hero where it stands. */
    fun select(track: LandingRelease.Track) {
        val id = track.videoID?.takeIf { trackVideos.containsKey(it) } ?: return
        if (id == heroVideo?.id) {
            MusicSession.togglePlay()
            return
        }
        nowPlayingID = id
        scope.launch { scroll.animateScrollTo(0) }
    }

    /** Opens the recorder on the song. While a giveaway runs, the drop is also the entry. */
    fun drop(on: CoreVideoMetadata) {
        nowPlayingID = on.id
        tab = ArtistTab.FAN_MAIL
        val challenge = on.challenge
        if (challenge != null && challenge.isActive) PendingEntryHashtag.arm(challenge.hashtag, on.threadID ?: on.id)
        else PendingEntryHashtag.clear()
        onStitch(on)
    }

    @Composable
    fun Hero() {
        val line = l.release?.let { release ->
            val songs = release.tracks.size
            listOfNotNull(
                release.dateLabel,
                if (songs > 0) "$songs ${if (songs == 1) "song" else "songs"}" else null
            ).joinToString(" · ").takeIf { it.isNotEmpty() }
        } ?: if (l.release == null && stops.isNotEmpty()) "${stops.size} ${if (stops.size == 1) "date" else "dates"} left on tour" else null
        val tabsOpen = l.isLive && !teenBlocked
        ArtistHero(
            landing = l,
            video = heroVideo?.takeUnless { teenBlocked },
            title = heroTitle,
            line = line,
            song = song.takeUnless { teenBlocked },
            showsBar = tabsOpen,
            chain = if (tabsOpen) buildStitchChain(heroVideo, heroReplies) else emptyList(),
            onTapLink = { link -> heroVideo?.let { onOpenThread(it.threadID ?: it.id, link.id) } },
            onOpenSlot = heroVideo?.takeUnless { isSelf }?.let { song -> { drop(song) } }
        )
    }

    @Composable
    fun MusicTab() {
        Hero()
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isSelf) {
                ArtistNotice("This is your page as fans see it.", Icons.Default.Person)
            } else {
                val song = heroVideo
                when {
                    !following -> ArtistMetalButton("GET IN") { followManager.toggleFollow(l.businessID) }
                    song != null -> {
                        ArtistMetalButton("DROP YOUR VIDEO") { drop(song) }
                        Text(
                            "Your video lands on $heroTitle's thread.",
                            fontSize = 12.sp, color = colors.textSecondary, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> ArtistMetalButton("FOLLOWING") { followManager.toggleFollow(l.businessID) }
                }
            }
            hook?.challenge?.takeIf { it.isActive }?.let { ArtistGiveawayStrip(it) { tab = ArtistTab.GIVEAWAY } }
            l.release?.tracks?.takeIf { it.isNotEmpty() }?.let { tracks ->
                ArtistTrackList(
                    tracks = tracks,
                    isPlayable = { it.videoID != null && trackVideos.containsKey(it.videoID) },
                    isCurrent = { it.videoID != null && it.videoID == heroVideo?.id },
                    paused = songPaused,
                    onSelect = { select(it) }
                )
            }
            // A tip is always on a video: the song's hook, else the newest post.
            if (!isSelf) (hook ?: ownPosts.firstOrNull())?.let { video ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .border(1.dp, colors.hairline, RoundedCornerShape(14.dp)),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TipButton(
                        videoID = video.id,
                        creatorID = video.creatorID,
                        currentUserID = uid,
                        coinTotal = video.coinTotal + sessionTips,
                        isSelfTip = video.creatorID == uid,
                        onAction = null,
                        onTipSent = { sessionTips += it }
                    )
                }
            }
        }
    }

    @Composable
    fun FanMailTab() {
        val song = heroVideo
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ArtistTabTitle(
                "Fan Mail",
                when {
                    song == null -> "Fan mail opens when ${l.name} drops a video."
                    isSelf -> "Videos fans dropped on $heroTitle."
                    else -> "Drop a video on $heroTitle. Fans and ${l.name} can see it."
                }
            )
            if (song != null) {
                ArtistHookCard(song, heroTitle, heroReplies.size) { onOpenVideo(song) }
                if (!isSelf) LandingButton("Drop your video", onClick = { drop(song) })
                if (heroReplies.isEmpty()) Text("No drops yet. Be the first.", fontSize = 13.sp, color = colors.textSecondary)
                else ArtistVideoGrid(heroReplies, onOpenVideo)
            }
        }
    }

    @Composable
    fun GiveawayTab() {
        val lead = hook
        val challenge = lead?.challenge
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ArtistTabTitle("Giveaway")
            when {
                lead != null && challenge != null -> ArtistGiveawayCard(
                    challenge = challenge,
                    artistName = l.name,
                    songTitle = songTitle,
                    myStatus = replies[lead.id]?.firstOrNull { it.creatorID == uid && it.challengeStatus != null }?.challengeStatus,
                    canEnter = !isSelf,
                    onEnter = { drop(lead) },
                    onSeeWinners = { showingWinners = true }
                )
                isSelf && lead != null -> {
                    ArtistNotice("No giveaway running. Start one and fans enter by dropping a video on $songTitle.", Icons.Default.ConfirmationNumber)
                    LandingButton("Start a giveaway", onClick = { startingGiveaway = true })
                }
                else -> ArtistNotice("No giveaway running right now. Follow ${l.name} to hear about the next one.", Icons.Default.ConfirmationNumber)
            }
        }
    }

    @Composable
    fun VideosTab() {
        val song = heroVideo
        val videos = ownPosts.filter { it.id != song?.id }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ArtistTabTitle("Videos")
            if (song != null) ArtistHookCard(song, heroTitle, heroReplies.size) { onOpenVideo(song) }
            if (videos.isNotEmpty()) ArtistVideoGrid(videos, onOpenVideo)
            else if (song == null) Text("Nothing posted yet.", fontSize = 13.sp, color = colors.textSecondary)
        }
    }

    @Composable
    fun TourTab() {
        Box(Modifier.padding(horizontal = 16.dp)) { ArtistTabTitle("Tour") }
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
    }

    val showsTabs = l.isLive && !teenBlocked
    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .then(if (!showsTabs || tab == ArtistTab.MUSIC) Modifier else Modifier.statusBarsPadding())
                    .padding(bottom = 24.dp)
            ) {
                if (!showsTabs) {
                    Hero()
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (teenBlocked) ArtistNotice("This artist isn't available on your account.", Icons.Default.Lock)
                        else ArtistNotice("This Landing isn't active right now.", Icons.Default.PauseCircle)
                    }
                } else when (tab) {
                    ArtistTab.MUSIC -> MusicTab()
                    ArtistTab.FAN_MAIL -> FanMailTab()
                    ArtistTab.GIVEAWAY -> GiveawayTab()
                    ArtistTab.VIDEOS -> VideosTab()
                    ArtistTab.TOUR -> TourTab()
                }
            }
            if (showsTabs) ArtistTabBar(tab) { tab = it }
        }

        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ArtistCircleButton(
                content = { Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = colors.textPrimary, modifier = Modifier.size(18.dp)) },
                onClick = onDismiss
            )
            Spacer(Modifier.weight(1f))
            if (tab == ArtistTab.MUSIC && hook != null && showsTabs) {
                ArtistCircleButton(
                    content = {
                        Icon(
                            if (MusicSession.heroMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (MusicSession.heroMuted) "Turn sound on" else "Turn sound off",
                            tint = colors.textPrimary, modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = { MusicSession.setMuted(!MusicSession.heroMuted) }
                )
            }
            ArtistCircleButton(
                content = { Icon(Icons.Default.Share, contentDescription = "Share", tint = colors.textPrimary, modifier = Modifier.size(18.dp)) },
                onClick = { share() }
            )
            if (asProfile) {
                Box {
                    ArtistCircleButton(
                        content = { Icon(Icons.Default.MoreHoriz, contentDescription = "More", tint = colors.textPrimary, modifier = Modifier.size(22.dp)) },
                        onClick = { menuOpen = true }
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Report") }, onClick = { menuOpen = false; showingReport = true })
                        DropdownMenuItem(
                            text = { Text("Block", color = colors.destructive) },
                            onClick = { menuOpen = false; showingBlock = true }
                        )
                    }
                }
            }
        }

        if (asProfile) {
            ProfileModerationSheets(
                targetUserID = l.businessID,
                targetUsername = hook?.creatorName ?: l.name,
                showReport = showingReport,
                showBlockConfirm = showingBlock,
                onDismissReport = { showingReport = false },
                onDismissBlock = { showingBlock = false }
            )
            // Blocked from here: the page has nothing left to show this viewer.
            val blockedIDs by BlockService.shared.blockedUserIds.collectAsState()
            LaunchedEffect(blockedIDs) { if (l.businessID in blockedIDs) onDismiss() }
        }

        if (startingGiveaway) hook?.let { lead ->
            ArtistStartGiveawaySheet(hook = lead, onStarted = { loadSong() }, onDismiss = { startingGiveaway = false })
        }
        if (showingWinners) hook?.let { lead ->
            lead.challenge?.let { GiveawayResultsView(lead.id, it) { showingWinners = false } }
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
