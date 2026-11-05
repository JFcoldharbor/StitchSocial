package com.stitchsocial.club.landing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.text.format.DateFormat
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.stitchsocial.club.birthday.pauseFeedPlayback
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.AccountStanding
import com.stitchsocial.club.services.SearchService
import com.stitchsocial.club.services.TeenSafety
import com.stitchsocial.club.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Stitch Picks page. The main video sits on top, with the night's games to
 * swipe under it. Tapping a quarter on a game plays the host's moment in the main
 * video's spot.
 *
 * iOS parity with Landing/PicksLandingView.swift.
 *
 * Layer 5: Views. PicksPageModel is the state it draws from.
 */

private val picksGold = Color(0xFFE8C872)
private val picksWarning = Color(0xFFFB923C)
private val picksOnAccent = Color(0xFF06202A)
private val picksHeroFallback = Color(0xFF1E1E2B)

private enum class PicksTab(val label: String) {
    TONIGHT("Tonight"), STITCHES("Stitches"), RECORD("Record")
}

@Stable
private class PicksPageModel(val landingID: String) {
    var page by mutableStateOf<PicksPage?>(null)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var message by mutableStateOf<String?>(null)

    /** A listed night opened as a recap. Null shows tonight. */
    var recapID by mutableStateOf<String?>(null)
        private set
    /** The recap's page, null while it loads. */
    var recap by mutableStateOf<PicksPage?>(null)
        private set

    /** Tonight's own night just shows tonight. */
    fun openRecap(nightID: String) {
        recap = null
        recapID = nightID.takeIf { it != page?.tonightID }
    }

    fun closeRecap() {
        recapID = null
        recap = null
    }

    /** Read once. A finished night doesn't change, so it isn't polled. */
    suspend fun loadRecap(nightID: String) {
        try {
            val loaded = PicksService.page(landingID, nightID)
            if (recapID != nightID) return
            // Never show a different night under this one's name. A server that
            // doesn't know nightID yet answers with tonight.
            if (loaded.night?.id != nightID) {
                closeRecap()
                message = "That night isn't available yet."
                return
            }
            // It became tonight's night since the list was read.
            if (loaded.night?.isTonight == true || loaded.night?.id == loaded.tonightID) closeRecap() else recap = loaded
        } catch (e: PicksError) {
            currentCoroutineContext().ensureActive()
            if (recapID == nightID) {
                closeRecap()
                message = e.message
            }
        }
    }

    suspend fun refresh() {
        try {
            page = PicksService.page(landingID)
        } catch (e: PicksError) {
            // A poll cut short by leaving the page isn't a failure to report.
            currentCoroutineContext().ensureActive()
            if (page == null) message = e.message
        }
        isLoading = false
    }

    /** Null when the server refused, and then the reason is in [message]. */
    suspend fun beginVideo(role: String, slate: String): PicksVideoStart? = try {
        PicksService.beginVideo(landingID, role, slate).also { PicksRecordingLimit.set(it.seconds) }
    } catch (e: PicksError) {
        message = e.message
        null
    }

    suspend fun respond(accept: Boolean) {
        try {
            PicksService.respondToInvite(landingID, accept)
            refresh()
        } catch (e: PicksError) {
            message = e.message
        }
    }

    suspend fun invite(userID: String, coHost: Boolean): Boolean = try {
        if (coHost) PicksService.inviteCoHost(landingID, userID) else PicksService.invite(landingID, userID)
        true
    } catch (e: PicksError) {
        message = e.message
        false
    }

    suspend fun removeCoHost(userID: String) {
        try {
            PicksService.removeCoHost(landingID, userID)
            refresh()
        } catch (e: PicksError) {
            message = e.message
        }
    }

    suspend fun removeHost() {
        try {
            PicksService.removeHost(landingID)
            refresh()
        } catch (e: PicksError) {
            message = e.message
        }
    }
}

/** Whether the app is on screen, so nothing plays or polls in the background. */
@Composable
private fun rememberAppIsOnScreen(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var onScreen by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            onScreen = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return onScreen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PicksLandingScreen(
    landing: Landing,
    onDismiss: () -> Unit,
    onRecord: () -> Unit,
    onStitch: (CoreVideoMetadata) -> Unit,
    onOpenVideo: (CoreVideoMetadata) -> Unit
) {
    val colors = AppTheme.colors
    HoldsMusic()

    // The server refuses a teen every call on this page, approved account or not,
    // so there's no account check to make the way an artist's page has.
    if (remember { TeenSafety.viewerIsTeen }) {
        Column(
            modifier = Modifier.fillMaxSize().background(colors.bg).statusBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PicksBackButton(onDismiss)
            ArtistNotice("Stitch Picks isn't available on this account.", Icons.Default.Lock)
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(landing.landingID) { PicksPageModel(landing.landingID) }
    val covered by LandingPresenter.covered
    val onScreen = rememberAppIsOnScreen()

    var tab by remember { mutableStateOf(PicksTab.TONIGHT) }
    var slate by remember { mutableStateOf("main") }
    // A recap is read only. Nothing on it acts on a night, since the server's
    // card and video calls are always for tonight.
    val recapID = model.recapID
    val isRecap = recapID != null
    val shownPage = if (isRecap) model.recap else model.page
    // A new night might not have a late slate.
    LaunchedEffect(shownPage?.night?.hasLateSlate) {
        if (shownPage?.night?.hasLateSlate != true) slate = "main"
    }
    var videoPaused by remember { mutableStateOf(false) }
    var videoMuted by remember { mutableStateOf(false) }
    var playingMoment by remember { mutableStateOf<Pair<PicksMoment, String>?>(null) }
    var showingUpload by remember { mutableStateOf(false) }
    // After the lock, an upload is a swap of a player listed out.
    var uploadIsSwap by remember { mutableStateOf(false) }
    var floorGame by remember { mutableStateOf<PicksGame?>(null) }
    var openCard by remember { mutableStateOf<PicksCard?>(null) }
    var showingInvite by remember { mutableStateOf(false) }
    var showingCoHostInvite by remember { mutableStateOf(false) }
    var startingVideo by remember { mutableStateOf(false) }
    // The recorder was asked for, and hasn't come up yet.
    var recorderAsked by remember { mutableStateOf(false) }
    var recorderUp by remember { mutableStateOf(false) }

    val page = shownPage
    val night = page?.night
    val readOnly = isRecap || night?.isTonight == false
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val myCard = uid?.let { id -> night?.cards?.firstOrNull { it.userID == id && it.slate == slate } }

    // Sound from here on, so nothing else plays underneath.
    LaunchedEffect(Unit) { pauseFeedPlayback(context) }

    // The page reads through the server, so it asks again while it's up. Coming
    // back from the recorder or a video starts this over, which refreshes.
    LaunchedEffect(landing.landingID, covered, onScreen, isRecap) {
        if (covered || !onScreen || isRecap) return@LaunchedEffect
        while (true) {
            model.refresh()
            delay(30_000)
        }
    }

    // Opening or leaving a recap starts the night's video and takes over.
    LaunchedEffect(recapID) {
        playingMoment = null
        videoPaused = false
        floorGame = null
        openCard = null
        showingUpload = false
        recapID?.let { model.loadRecap(it) }
    }
    // System back from a recap goes to tonight, not off the page.
    BackHandler(enabled = isRecap && !covered) { model.closeRecap() }

    LaunchedEffect(covered) {
        if (covered) {
            if (recorderAsked) {
                recorderAsked = false
                recorderUp = true
            }
        } else if (recorderUp) {
            recorderUp = false
            PicksRecordingLimit.set(null)
        }
    }
    DisposableEffect(Unit) {
        onDispose { PicksRecordingLimit.set(null) }
    }

    fun record(role: String) {
        // Suspended: the shared sheet explains, and no video is promised to the page.
        if (readOnly || startingVideo || !AccountStanding.guardPosting()) return
        startingVideo = true
        scope.launch {
            val start = model.beginVideo(role, slate)
            val replyTo = start?.replyToVideoID
            when {
                start == null -> Unit
                role == "card" && replyTo != null -> {
                    val parent = PicksService.video(replyTo)
                    if (parent == null) {
                        PicksRecordingLimit.set(null)
                        model.message = PicksError("").message
                    } else {
                        PendingEntryHashtag.clear()
                        recorderAsked = true
                        onStitch(parent)
                    }
                }
                else -> {
                    recorderAsked = true
                    onRecord()
                }
            }
            startingVideo = false
        }
    }

    fun openVideo(videoID: String?) {
        videoID ?: return
        scope.launch { PicksService.video(videoID)?.let(onOpenVideo) }
    }

    CompositionLocalProvider(LocalContentColor provides colors.textPrimary) {
        Column(Modifier.fillMaxSize().background(colors.bg).statusBarsPadding()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (isRecap) {
                    RecapBanner(night) { model.closeRecap() }
                }
                when {
                    isRecap && page == null -> Box(
                        Modifier.fillMaxWidth().padding(top = 160.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = LandingColors.accent) }
                    model.isLoading && page == null -> Box(
                        Modifier.fillMaxWidth().padding(top = 200.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = LandingColors.accent) }
                    !isRecap && page?.pendingInvite == true -> InviteAnswer(
                        coHost = page?.pendingInviteRole == "cohost",
                        onClose = onDismiss,
                        onAnswer = { accept -> scope.launch { model.respond(accept) } }
                    )
                    else -> when (tab) {
                        PicksTab.TONIGHT -> TonightTab(
                            page = page,
                            slate = slate,
                            readOnly = readOnly,
                            myCard = myCard,
                            playingMoment = playingMoment,
                            videoAllowed = !covered && onScreen,
                            videoPaused = videoPaused,
                            videoMuted = videoMuted,
                            onTogglePaused = { videoPaused = !videoPaused },
                            onToggleMuted = { videoMuted = !videoMuted },
                            onSlate = {
                                slate = it
                                playingMoment = null
                                videoPaused = false
                            },
                            onClose = onDismiss,
                            onHost = { showingInvite = true },
                            onCoHost = { showingCoHostInvite = true },
                            onMainVideo = { playingMoment = null },
                            onPlayMoment = { moment, quarter ->
                                // The whole conversation plays, from its first take.
                                val first = night?.conversation(moment)?.firstOrNull() ?: moment
                                playingMoment = first to quarter
                                videoPaused = false
                            },
                            onTakeEnded = {
                                playingMoment?.let { (take, quarter) ->
                                    val takes = night?.conversation(take).orEmpty()
                                    val next = takes.getOrNull(takes.indexOfFirst { it.id == take.id } + 1)
                                    if (next != null) playingMoment = next to quarter
                                }
                            },
                            onFloor = { floorGame = it },
                            onUpload = {
                                if (readOnly) return@TonightTab
                                uploadIsSwap = night?.isLocked(slate) == true
                                showingUpload = true
                            },
                            onRecordRole = { record(it) },
                            onOpenCard = { openCard = it }
                        )
                        PicksTab.STITCHES -> StitchesTab(night, onOpen = { openVideo(it.videoID) })
                        PicksTab.RECORD -> RecordTab(model.page) { past ->
                            model.openRecap(past.id)
                            tab = PicksTab.TONIGHT
                        }
                    }
                }
            }
            PicksTabBar(tab) { tab = it }
        }

        model.message?.let { text ->
            AlertDialog(
                onDismissRequest = { model.message = null },
                title = { Text("Stitch Picks") },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = { model.message = null }) { Text("OK") } }
            )
        }
        if (showingUpload && !readOnly) {
            PicksUploadSheet(
                landingID = landing.landingID,
                isHost = night?.isHostTonight == true,
                slate = slate,
                isSwap = uploadIsSwap,
                onDismiss = {
                    showingUpload = false
                    scope.launch { model.refresh() }
                }
            )
        }
        floorGame?.let { game -> PicksFloorSheet(game) { floorGame = null } }
        openCard?.let { opened ->
            night?.let { current ->
                // The tapped card is from an earlier poll. The open slip follows the night.
                val card = current.cards.firstOrNull { it.id == opened.id } ?: opened
                PicksCardSheet(card, current) { openCard = null }
            }
        }
        if (showingInvite && !readOnly) {
            PicksInviteSheet(
                model = model,
                currentHost = page?.hostName,
                onDismiss = {
                    showingInvite = false
                    scope.launch { model.refresh() }
                }
            )
        }
        if (showingCoHostInvite && !readOnly) {
            PicksInviteSheet(
                model = model,
                currentHost = null,
                coHost = true,
                onDismiss = {
                    showingCoHostInvite = false
                    scope.launch { model.refresh() }
                }
            )
        }
    }
}

@Composable
private fun PicksBackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.ArrowBackIosNew, contentDescription = "Back",
            tint = AppTheme.colors.textPrimary, modifier = Modifier.size(18.dp)
        )
    }
}

// MARK: - Tonight

@Composable
private fun TonightTab(
    page: PicksPage?,
    slate: String,
    /** A recap: nothing here acts on the night. */
    readOnly: Boolean,
    myCard: PicksCard?,
    playingMoment: Pair<PicksMoment, String>?,
    videoAllowed: Boolean,
    videoPaused: Boolean,
    videoMuted: Boolean,
    onTogglePaused: () -> Unit,
    onToggleMuted: () -> Unit,
    onSlate: (String) -> Unit,
    onClose: () -> Unit,
    onHost: () -> Unit,
    onCoHost: () -> Unit,
    onMainVideo: () -> Unit,
    onPlayMoment: (PicksMoment, String) -> Unit,
    onTakeEnded: () -> Unit,
    onFloor: (PicksGame) -> Unit,
    onUpload: () -> Unit,
    onRecordRole: (String) -> Unit,
    onOpenCard: (PicksCard) -> Unit
) {
    val colors = AppTheme.colors
    val night = page?.night
    Column(Modifier.fillMaxWidth()) {
        PicksHero(
            page, slate, readOnly, playingMoment, videoAllowed, videoPaused, videoMuted,
            onTogglePaused, onToggleMuted, onClose, onHost, onCoHost, onMainVideo, onTakeEnded
        )
        if (night != null && night.hasLateSlate && !night.hidden) SlatePicker(night, slate, onSlate)
        if (night != null && night.games.isNotEmpty()) {
            SectionHeader(if (readOnly) "Games" else "Tonight's games", if (night.games.size > 1) "Swipe for the next game" else null)
            GameCards(night, playingMoment, onPlayMoment, onFloor)
        }
        when {
            night != null && !night.hidden -> {
                if (!readOnly) {
                    PromptBanner(page, night, myCard) { onRecordRole("moment") }
                    ActionButton(night, slate, myCard, onUpload, onRecordRole)
                    SwapButton(night, slate, myCard, onUpload)
                }
                night.hostCard(slate)?.let { host ->
                    key(night.id, slate) { HostCardSection(night, slate, host) }
                }
                SectionHeader(
                    if (night.isLocked(slate)) "Most picks right" else "Tonight's picks",
                    if (readOnly || night.status == "final") null else "${night.cards.count { it.slate == slate }} in so far"
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val others = night.cards.filter { !it.isHost && it.slate == slate }
                    if (others.isEmpty()) {
                        Text(
                            when {
                                readOnly -> "No one else's picks."
                                night.isLocked(slate) -> "No one else's picks tonight."
                                else -> "No one else's picks in yet."
                            },
                            fontSize = 13.sp, color = colors.textSecondary
                        )
                    }
                    others.forEach { card -> key(card.id) { CardRow(card, night) { onOpenCard(card) } } }
                }
            }
            night == null -> Text(
                "No games today. The next slate shows up here.",
                fontSize = 14.sp, color = colors.textSecondary, modifier = Modifier.padding(16.dp)
            )
            else -> Text(
                "This night's videos aren't available. The results stay in Record.",
                fontSize = 14.sp, color = colors.textSecondary, modifier = Modifier.padding(16.dp)
            )
        }
        Text(
            "For fun. No money and no prizes here. Not advice. Picks are read from each person's sheet and not verified. Not for teens.",
            fontSize = 11.sp, color = colors.textSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
        )
    }
}

@Composable
private fun PicksHero(
    page: PicksPage?,
    slate: String,
    readOnly: Boolean,
    playingMoment: Pair<PicksMoment, String>?,
    videoAllowed: Boolean,
    videoPaused: Boolean,
    videoMuted: Boolean,
    onTogglePaused: () -> Unit,
    onToggleMuted: () -> Unit,
    onClose: () -> Unit,
    onHost: () -> Unit,
    onCoHost: () -> Unit,
    onMainVideo: () -> Unit,
    onTakeEnded: () -> Unit
) {
    val colors = AppTheme.colors
    val night = page?.night
    val url = (playingMoment?.first?.videoURL ?: night?.videoURL(slate))?.takeIf { it.isNotBlank() }
    // A take plays through to the next one. The last take, and the main video, loop as before.
    val takes = playingMoment?.first?.let { take -> night?.conversation(take) ?: listOf(take) }.orEmpty()
    val takeNumber = playingMoment?.first?.let { take -> takes.indexOfFirst { it.id == take.id } + 1 } ?: 0
    val loops = playingMoment == null || takeNumber >= takes.size
    // A recap has that night's host. Today's host and co-hosts may not have been on it.
    val host = night?.hostName?.takeIf { it.isNotEmpty() }
        ?: if (night?.hostName == null && !readOnly) page?.hostName?.takeIf { it.isNotEmpty() } else null
    val coHosts = if (readOnly) emptyList() else page?.coHosts.orEmpty()

    Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds().background(picksHeroFallback)) {
        if (url != null) {
            // Off the page under the recorder or another video, and with the app away.
            if (videoAllowed) PicksHeroVideo(url, videoPaused, videoMuted, loops, onTakeEnded, Modifier.fillMaxSize())
        } else {
            Text(
                when {
                    night?.hidden == true -> "This night's videos aren't available."
                    readOnly -> "No video for this night"
                    else -> "The host's video shows here"
                },
                fontSize = 13.sp, color = colors.textSecondary,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.7f),
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f)
                )
            )
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PicksBackButton(onClose)
                Spacer(Modifier.weight(1f))
                if (!readOnly && page?.isHost == true) {
                    Box(
                        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(onClick = onCoHost),
                        contentAlignment = Alignment.Center
                    ) { Text("Co-host", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(12.dp))
                }
                if (!readOnly && page?.isAdmin == true) {
                    Box(
                        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(onClick = onHost),
                        contentAlignment = Alignment.Center
                    ) { Text("Host", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Text(
                when {
                    host == null -> "By Stitch Social"
                    coHosts.isEmpty() -> "By Stitch Social · Hosted by $host"
                    else -> "By Stitch Social · Hosted by $host · with ${coHosts.joinToString { it.name }}"
                },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                (night?.title ?: "Stitch Picks").uppercase(),
                fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = picksGold
            )
            Spacer(Modifier.weight(1f))
            playingMoment?.first?.let { take ->
                val speaker = listOfNotNull(
                    take.speakerName,
                    if (takes.size > 1) "Take $takeNumber of ${takes.size}" else null
                ).joinToString(" · ")
                if (speaker.isNotEmpty()) {
                    Text(speaker, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Measured after the buttons, so a long badge wraps and can't squeeze them out.
                Box(Modifier.weight(1f)) {
                    heroBadge(night, slate, playingMoment)?.let { badge ->
                        Text(
                            badge,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.bg)
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
                if (playingMoment != null) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clip(CircleShape)
                            .background(colors.bg)
                            .clickable(onClick = onMainVideo)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Main video", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
                if (url != null) {
                    HeroControl(
                        if (videoPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        if (videoPaused) "Play" else "Pause",
                        onTogglePaused
                    )
                    HeroControl(
                        if (videoMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (videoMuted) "Turn sound on" else "Mute",
                        onToggleMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroControl(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(colors.bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
    }
}

/**
 * The hero's video on a loop, with sound unless muted. Its own player because the
 * hero can pause and mute, and BirthdayLoopingVideo can't.
 */
@Composable
private fun PicksHeroVideo(
    url: String,
    paused: Boolean,
    muted: Boolean,
    loops: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val ended by rememberUpdatedState(onEnded)
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = if (loops) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else 1f
            playWhenReady = !paused
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.stop()
            player.release()
        }
    }
    SideEffect {
        player.repeatMode = if (loops) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = if (muted) 0f else 1f
        player.playWhenReady = !paused
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier
    )
}

@Composable
private fun SlatePicker(night: PicksNight, slate: String, onSlate: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlateButton("Main slate", night.locked, null, slate == "main", Modifier.weight(1f)) { onSlate("main") }
        SlateButton("Late slate", night.lateLocked, night.lateTipAt, slate == "late", Modifier.weight(1f)) { onSlate("late") }
    }
}

@Composable
private fun SlateButton(
    title: String,
    locked: Boolean,
    tip: Date?,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val tint = if (selected) picksOnAccent else colors.textPrimary
    Column(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) LandingColors.accent else colors.surface)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tint)
        Text(
            when {
                locked -> "Locked"
                // The phone's own 12 or 24 hour setting.
                tip != null -> "Locks ${DateFormat.getTimeFormat(context).format(tip)}"
                else -> "Locks at first tip"
            },
            fontSize = 11.sp, color = tint
        )
    }
}

private fun heroBadge(night: PicksNight?, slate: String, playingMoment: Pair<PicksMoment, String>?): String? {
    if (playingMoment != null) {
        val (moment, quarter) = playingMoment
        val right = moment.right ?: return null
        val total = moment.total ?: return null
        return "$right OF $total AT END OF $quarter"
    }
    night ?: return null
    if (!night.isLocked(slate)) return if (slate == "late") "LOCKS AT LATE TIP" else "LOCKS AT FIRST TIP"
    val host = night.hostCard(slate) ?: return null
    return if (night.status == "final") "${host.right} OF ${host.total} RIGHT" else "${host.right} OF ${host.total} RIGHT SO FAR"
}

@Composable
private fun GameCards(
    night: PicksNight,
    playingMoment: Pair<PicksMoment, String>?,
    onPlayMoment: (PicksMoment, String) -> Unit,
    onFloor: (PicksGame) -> Unit
) {
    // Narrow phones still see the next card's edge.
    val cardWidth = minOf(322, LocalConfiguration.current.screenWidthDp - 50).dp
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(night.games, key = { it.id }) { game ->
            GameCard(game, night, playingMoment, onPlayMoment, onFloor, Modifier.width(cardWidth))
        }
    }
}

@Composable
private fun GameCard(
    game: PicksGame,
    night: PicksNight,
    playingMoment: Pair<PicksMoment, String>?,
    onPlayMoment: (PicksMoment, String) -> Unit,
    onFloor: (PicksGame) -> Unit,
    modifier: Modifier
) {
    val colors = AppTheme.colors
    val hasPicks = night.cards.any { card -> card.isHost && card.picks.any { it.gameID == game.id } }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ScoreColumn(game.away, if (game.hasStarted) game.awayScore.toString() else "–", Modifier.weight(1f))
            Text(
                game.clock,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LandingColors.accent,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
            )
            ScoreColumn(game.home, if (game.hasStarted) game.homeScore.toString() else "–", Modifier.weight(1f))
        }
        if (game.hasStarted) {
            Text(
                "Fouls ${game.awayFouls}–${game.homeFouls} · Timeouts ${game.awayTimeouts}–${game.homeTimeouts}",
                fontSize = 11.sp, color = colors.textSecondary
            )
        }
        if (hasPicks) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Q1", "Q2", "Q3", "Q4").forEach { quarter ->
                    QuarterChip(quarter, game, night, playingMoment, onPlayMoment, Modifier.weight(1f))
                }
            }
        } else {
            Box(Modifier.heightIn(min = 46.dp), contentAlignment = Alignment.Center) {
                Text("No picks on the main card in this game", fontSize = 12.sp, color = colors.textSecondary)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .alpha(if (game.hasStarted) 1f else 0.4f)
                .clickable(enabled = game.hasStarted) { onFloor(game) },
            contentAlignment = Alignment.Center
        ) {
            Text("Who's on the floor", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LandingColors.accent)
        }
    }
}

@Composable
private fun ScoreColumn(team: String, score: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(team, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textSecondary)
        Text(score, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun QuarterChip(
    quarter: String,
    game: PicksGame,
    night: PicksNight,
    playingMoment: Pair<PicksMoment, String>?,
    onPlayMoment: (PicksMoment, String) -> Unit,
    modifier: Modifier
) {
    val colors = AppTheme.colors
    val moment = night.moment(game.id, quarter)
    val takes = moment?.let { night.conversation(it).size } ?: 0
    val isOn = moment != null && playingMoment?.first?.conversationKey == moment.conversationKey && playingMoment.second == quarter
    val tint = when {
        isOn -> picksOnAccent
        moment == null -> colors.textSecondary
        else -> colors.textPrimary
    }
    val label = if (moment == null) "No $quarter moment for ${game.name}" else "Play $quarter moment for ${game.name}"
    Column(
        modifier = modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isOn) LandingColors.accent else colors.surfaceStrong)
            .clickable(enabled = moment != null) { moment?.let { onPlayMoment(it, quarter) } }
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(quarter, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tint)
        Text(
            when {
                moment == null -> "None"
                takes > 1 -> "$takes takes"
                else -> moment.lengthText
            },
            fontSize = 11.sp, color = tint
        )
    }
}

@Composable
private fun PromptBanner(page: PicksPage?, night: PicksNight, myCard: PicksCard?, onRecord: () -> Unit) {
    val isCoHost = page?.isCoHost == true
    val prompt = night.openPrompt
    if ((!night.isHostTonight && !isCoHost) || prompt == null || prompt.breaks.isEmpty()) return
    val colors = AppTheme.colors
    val breaks = prompt.breaks
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val hasTakes = prompt.takes > 0
    val lastWasMine = hasTakes && uid != null && prompt.lastSpeakerID == uid
    // Whoever can answer my take: a co-host for the host, the host for a co-host.
    val otherHost = if (night.isHostTonight) {
        page?.coHosts?.singleOrNull()?.name?.takeIf { it.isNotBlank() }
    } else {
        night.hostName?.takeIf { it.isNotBlank() }
    } ?: "Your co-host"
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (breaks.size > 1) "${breaks.size} GAMES AT A BREAK" else "GAME AT A BREAK",
            fontSize = 22.sp, fontWeight = FontWeight.ExtraBold
        )
        Text(
            breaks.joinToString(". ") { "${it.game} finished ${it.quarter}" },
            fontSize = 13.sp, color = colors.textSecondary
        )
        myCard?.let {
            Text("Your ${it.slate} card: ${it.right} of ${it.total} right so far", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        when {
            !hasTakes -> {
                ArtistMetalButton("RECORD A MOMENT · UP TO 1:00", onRecord)
                Text("Or skip it. Nothing shows for this break.", fontSize = 12.sp, color = colors.textSecondary)
            }
            lastWasMine -> {
                Text("Your take is up. $otherHost can answer.", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                ArtistMetalButton("ADD A TAKE · UP TO 1:00", onRecord)
            }
            else -> {
                Text(
                    "${prompt.lastSpeakerName ?: "Your co-host"} posted a moment. Answer it.",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                ArtistMetalButton("REPLY · UP TO 1:00", onRecord)
            }
        }
    }
}

@Composable
private fun ActionButton(
    night: PicksNight,
    slate: String,
    myCard: PicksCard?,
    onUpload: () -> Unit,
    onRecord: (String) -> Unit
) {
    // Picks are locked, but the host's video can still go up.
    val locked = night.isLocked(slate)
    val slateVideoID = night.videoID(slate)
    if (locked && !(night.isHostTonight && slateVideoID == null)) return
    val colors = AppTheme.colors
    Box(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            locked ->
                ArtistMetalButton("RECORD YOUR VIDEO · UP TO 2:00") { onRecord("main") }
            myCard == null ->
                ArtistMetalButton(
                    when {
                        !night.isHostTonight -> "STITCH YOUR PICKS"
                        slate == "late" -> "ADD YOUR LATE CARD"
                        else -> "ADD YOUR MAIN CARD"
                    },
                    onUpload
                )
            night.isHostTonight && slateVideoID == null ->
                ArtistMetalButton("RECORD YOUR VIDEO · UP TO 2:00") { onRecord("main") }
            !night.isHostTonight && myCard.videoID == null ->
                // A late stitch goes on the main video until there's a late one.
                if (slateVideoID == null && night.mainVideoID == null) {
                    Text(
                        "You're in. You can record once the host posts tonight's video.",
                        fontSize = 13.sp, color = colors.textSecondary
                    )
                } else {
                    ArtistMetalButton("RECORD YOUR VIDEO") { onRecord("card") }
                }
            else -> Text(
                if (slate == "late") "You're all set. Late picks lock when the first late game tips." else "You're all set. Picks lock when the first game tips.",
                fontSize = 13.sp, color = colors.textSecondary
            )
        }
    }
}

/**
 * After the lock, a card with a player listed out, in a game that hasn't started,
 * can swap that pick by uploading the updated sheet.
 */
@Composable
private fun SwapButton(night: PicksNight, slate: String, myCard: PicksCard?, onUpload: () -> Unit) {
    if (!night.isLocked(slate) || myCard == null) return
    val canSwap = myCard.picks.any { pick ->
        pick.isListedOut && night.games.firstOrNull { it.id == pick.gameID }?.hasStarted == false
    }
    if (!canSwap) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ArtistMetalButton("SWAP AN OUT PLAYER", onUpload)
        Text(
            "Upload your updated sheet. Only a player listed out can change, and only for a game that hasn't started.",
            fontSize = 12.sp, color = AppTheme.colors.textSecondary
        )
    }
}

private fun picksPrefs(context: Context) = context.getSharedPreferences("stitch_picks", Context.MODE_PRIVATE)

/** The host's main or late card, which folds down to one line. Remembered per night and slate. */
@Composable
private fun HostCardSection(night: PicksNight, slate: String, host: PicksCard) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val prefKey = "picksCollapsed_${night.id}_$slate"
    var collapsed by remember(prefKey) {
        mutableStateOf(runCatching { picksPrefs(context).getBoolean(prefKey, false) }.getOrDefault(false))
    }
    val title = if (slate == "late") "Late card" else "Main card"
    val name = night.hostName?.takeIf { it.isNotBlank() } ?: host.username
    val summary = buildString {
        // Before anything settles, "0 of 5 right" reads like a bad night.
        if (host.right + host.wrong == 0) append("$name · ${host.picks.size} picks")
        else {
            append("$name · ${host.right} of ${host.total} right")
            if (host.open > 0) append(" · ${host.open} still going")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable {
                collapsed = !collapsed
                runCatching { picksPrefs(context).edit().putBoolean(prefKey, collapsed).apply() }
            }
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (collapsed) "$title, $summary. Show card" else "$title. Hide card"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (collapsed) summary else title,
            fontSize = 15.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(22.dp)
        )
    }
    AnimatedVisibility(
        visible = !collapsed,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        key(host.id) { PicksSlipCard(host, night, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String, note: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.weight(1f))
        if (note != null) {
            Text(note, fontSize = 12.sp, color = AppTheme.colors.textSecondary, modifier = Modifier.alignByBaseline())
        }
    }
}

@Composable
private fun CardRow(card: PicksCard, night: PicksNight, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val locked = night.isLocked(card.slate)
    val detail = when {
        !locked -> "${card.picks.size} picks"
        card.open > 0 -> "${card.open} ${if (card.open == 1) "pick" else "picks"} still to play"
        card.wrong == 0 -> "All ${card.right} right"
        else -> "${card.wrong} wrong"
    }
    val statusColor = when {
        !locked -> colors.textSecondary
        card.open > 0 -> LandingColors.accent
        card.wrong == 0 && card.total > 0 -> picksGold
        else -> colors.textPrimary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.surfaceStrong),
            contentAlignment = Alignment.Center
        ) { Text(card.username.take(2).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (card.isHost) "${card.username}'s ${card.slate} card" else "@${card.username}",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            Text(detail, fontSize = 12.sp, color = colors.textSecondary)
        }
        Text(
            if (locked) "${card.right} OF ${card.total}" else "NOT STARTED",
            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = statusColor
        )
    }
}

// MARK: - Stitches

@Composable
private fun StitchesTab(night: PicksNight?, onOpen: (PicksCard) -> Unit) {
    val colors = AppTheme.colors
    val cards = night?.cards.orEmpty().filter { it.videoID != null }
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 56.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("STITCHES", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        if (night?.hidden == true) {
            Text("This night's videos aren't available.", fontSize = 14.sp, color = colors.textSecondary)
        } else if (cards.isEmpty()) {
            Text("No stitches yet.", fontSize = 14.sp, color = colors.textSecondary)
        }
        if (night != null) cards.forEach { card -> key(card.id) { CardRow(card, night) { onOpen(card) } } }
    }
}

// MARK: - Record

@Composable
private fun RecordTab(page: PicksPage?, onOpenNight: (PicksPastNight) -> Unit) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 56.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            page?.hostName ?: "Stitch Picks",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary
        )
        Text(
            "${page?.recordRight ?: 0} OF ${page?.recordTotal ?: 0}",
            fontSize = 50.sp, lineHeight = 54.sp, fontWeight = FontWeight.ExtraBold, color = picksGold
        )
        Text("main card picks right, none hidden", fontSize = 14.sp)
        Text("Past nights", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        if (page?.recentNights.isNullOrEmpty()) {
            Text("Finished nights show here.", fontSize = 13.sp, color = colors.textSecondary)
        }
        page?.recentNights.orEmpty().forEach { past ->
            key(past.id) { PastNightRow(past) { onOpenNight(past) } }
        }
    }
}

/** A finished night. Tapping it opens the night as a recap. */
@Composable
private fun PastNightRow(past: PicksPastNight, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val date = picksDate(past.date, "EEE, MMM d") ?: past.date
    val result = listOfNotNull(
        "${past.right} of ${past.total} right",
        past.games?.takeIf { it > 0 }?.let { if (it == 1) "1 game" else "$it games" }
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${past.title}, $date, $result. Open recap"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PicksThumb(past.thumb, Modifier.width(48.dp).height(64.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(past.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                past.hostName?.takeIf { it.isNotBlank() }?.let { "$date · $it" } ?: date,
                fontSize = 12.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(result, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = picksGold)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = colors.textSecondary, modifier = Modifier.size(20.dp)
        )
    }
}

/** A night's video thumbnail, or a drawn stand-in in the page's colors. */
@Composable
internal fun PicksThumb(url: String?, modifier: Modifier) {
    Box(modifier.background(picksCoverGradient), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.PlayArrow, contentDescription = null,
            tint = picksGold.copy(alpha = 0.7f), modifier = Modifier.size(20.dp)
        )
        if (url != null) {
            AsyncImage(
                model = url, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** The page's own colors, for a night with no thumbnail. */
internal val picksCoverGradient = Brush.linearGradient(
    listOf(Color(0xFF0E3A47), picksHeroFallback, Color(0xFF3A2F12))
)

/** "2026-11-04" in [pattern], in the phone's language. Null if it can't be read. */
internal fun picksDate(date: String, pattern: String): String? = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}.getOrNull()

/** Over a recap: which night it is, and the way back. */
@Composable
private fun RecapBanner(night: PicksNight?, onBack: () -> Unit) {
    val colors = AppTheme.colors
    val text = listOfNotNull(
        "Recap",
        night?.date?.let { picksDate(it, "EEEE, MMM d") },
        if (night?.status == "final") "Final" else null
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Back to tonight", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LandingColors.accent)
        }
    }
}

// MARK: - The host's invite

@Composable
private fun InviteAnswer(coHost: Boolean, onClose: () -> Unit, onAnswer: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PicksBackButton(onClose)
        Text(
            if (coHost) "CO-HOST STITCH PICKS?" else "HOST STITCH PICKS?",
            fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.ExtraBold, color = picksGold
        )
        Text(
            if (coHost) "You're invited to join the host. Your videos post to the Stitch Picks page with your name on them." else "Stitch Picks invited you to host. Your videos post to the Stitch Picks page with your name on them.",
            fontSize = 14.sp
        )
        if (!coHost) InviteLine("Before first tip", "One video, up to 2:00, on one main card with 5 picks or more")
        InviteLine("When a quarter ends", "One moment, up to 1:00, for every game at a break. Skip any you like.")
        if (!coHost) InviteLine("Your main card locks at first tip", "Right and wrong picks both stay on the record")
        InviteLine("In your videos", "No app names, promo codes, screenshots or dollar talk")
        ArtistMetalButton("ACCEPT") { onAnswer(true) }
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onAnswer(false) },
            contentAlignment = Alignment.Center
        ) {
            Text("Not now", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
        }
    }
}

@Composable
private fun InviteLine(title: String, detail: String) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, fontSize = 12.sp, color = colors.textSecondary)
    }
}

// MARK: - Tabs

@Composable
private fun PicksTabBar(selected: PicksTab, onSelect: (PicksTab) -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        PicksTab.entries.forEach { item ->
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 50.dp).clickable { onSelect(item) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    item.label,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (item == selected) LandingColors.accent else colors.textSecondary
                )
            }
        }
    }
}

// MARK: - Who's on the floor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PicksFloorSheet(game: PicksGame, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bg, contentColor = colors.textPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(game.name, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(game.clock, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LandingColors.accent)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FloorTeam(game.away, game.awayFloor, Modifier.weight(1f))
                FloorTeam(game.home, game.homeFloor, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FloorTeam(code: String, floor: List<PicksGame.Player>, modifier: Modifier) {
    val colors = AppTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(code, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textSecondary)
        if (floor.isEmpty()) Text("Not available yet", fontSize = 12.sp, color = colors.textSecondary)
        floor.forEach { p ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surface)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.points} pts · ${p.fouls} fouls", fontSize = 11.sp, color = colors.textSecondary)
            }
        }
    }
}

// MARK: - One card's picks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PicksCardSheet(card: PicksCard, night: PicksNight, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bg, contentColor = colors.textPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            PicksSlipCard(card, night)
        }
    }
}

// MARK: - Inviting a host

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PicksInviteSheet(model: PicksPageModel, currentHost: String?, coHost: Boolean = false, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val search = remember { SearchService() }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BasicUserInfo>>(emptyList()) }
    var sentTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        if (query.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        results = search.searchUsers(query, 20)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bg,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(if (coHost) "INVITE A CO-HOST" else "INVITE A HOST", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            if (coHost) {
                model.page?.coHosts.orEmpty().forEach { person ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Co-hosting: ${person.name}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable {
                                scope.launch { model.removeCoHost(person.id) }
                            },
                            contentAlignment = Alignment.Center
                        ) { Text("Remove", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            if (!currentHost.isNullOrEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Hosting now: $currentHost", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable {
                            scope.launch {
                                model.removeHost()
                                onDismiss()
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) { Text("Remove", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Text(if (coHost) "Any Stitch user can join you" else "Any Stitch user can host", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
            PicksField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search by name or handle",
                modifier = Modifier.fillMaxWidth(),
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
            )
            results.forEach { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(user.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("@${user.username}", fontSize = 12.sp, color = colors.textSecondary)
                    }
                    val invited = sentTo == user.id
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .widthIn(min = 44.dp)
                            .clickable(enabled = !invited) {
                                scope.launch { if (model.invite(user.id, coHost)) sentTo = user.id }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (invited) "Invited" else "Invite",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (invited) colors.textSecondary else LandingColors.accent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PicksField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    style: TextStyle = TextStyle(fontSize = 14.sp),
    keyboard: KeyboardOptions = KeyboardOptions.Default
) {
    val colors = AppTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(LandingColors.accent),
        keyboardOptions = keyboard,
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceStrong)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = colors.textSecondary), maxLines = 1)
                inner()
            }
        }
    )
}

// MARK: - Uploading a sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PicksUploadSheet(landingID: String, isHost: Boolean, slate: String, isSwap: Boolean, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picks by remember { mutableStateOf<List<PicksPick>>(emptyList()) }
    var isWorking by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // The host's 5-pick minimum is for the card before the lock, not a swap.
    val canSave = picks.isNotEmpty() && (!isHost || isSwap || picks.size >= 5) && !isWorking

    fun read(uri: Uri) {
        errorText = null
        isWorking = true
        scope.launch {
            val jpeg = picksDownsizedJpeg(context, uri)
            if (jpeg == null) {
                errorText = "We couldn't open that picture."
            } else {
                try {
                    picks = PicksService.readSheet(landingID, jpeg)
                } catch (e: PicksError) {
                    errorText = e.message
                }
            }
            isWorking = false
        }
    }

    fun save() {
        errorText = null
        isWorking = true
        scope.launch {
            try {
                PicksService.submit(landingID, slate, picks)
                onDismiss()
            } catch (e: PicksError) {
                errorText = e.message
            }
            isWorking = false
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) read(uri)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bg,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                when {
                    picks.isNotEmpty() -> "CHECK YOUR PICKS"
                    isSwap -> "UPLOAD YOUR UPDATED SHEET"
                    else -> "UPLOAD YOUR SHEET"
                },
                fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold
            )
            Text(
                if (isSwap) {
                    "Upload your updated sheet. Only a player listed out can change, and only for a game that hasn't started."
                } else {
                    "We read your PrizePicks or Underdog sheet. Fix anything we got wrong. " +
                        if (slate == "late") "This card is for the late games, and locks when the first one tips." else "Your picks lock when the first game tips."
                },
                fontSize = 13.sp, color = colors.textSecondary
            )
            if (isHost && !isSwap) {
                Text("Your card needs at least 5 picks.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            ArtistMetalButton(if (picks.isEmpty()) "CHOOSE A SCREENSHOT" else "CHOOSE A DIFFERENT SCREENSHOT") {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (isWorking) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LandingColors.accent)
                }
            }
            errorText?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = picksWarning) }
            picks.forEach { pick ->
                key(pick.id) {
                    PickEditor(
                        pick = pick,
                        onChange = { changed -> picks = picks.map { if (it.id == changed.id) changed else it } },
                        onRemove = { picks = picks.filterNot { it.id == pick.id } }
                    )
                }
            }
            if (picks.isNotEmpty()) {
                Text(
                    "Only the players and numbers are kept. Amounts and payouts are ignored, and your sheet is never shown or saved.",
                    fontSize = 12.sp, color = colors.textSecondary
                )
                Box(Modifier.alpha(if (canSave) 1f else 0.4f)) {
                    ArtistMetalButton("LOOKS RIGHT") { if (canSave) save() }
                }
            }
        }
    }
}

@Composable
private fun PickEditor(pick: PicksPick, onChange: (PicksPick) -> Unit, onRemove: () -> Unit) {
    val colors = AppTheme.colors
    var lineText by remember {
        mutableStateOf(pick.line?.let { if (it == Math.rint(it)) it.toLong().toString() else it.toString() } ?: "")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PicksField(
                value = pick.player,
                onValueChange = { onChange(pick.copy(player = it)) },
                placeholder = "Player",
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close, contentDescription = "Remove ${pick.player}",
                    tint = colors.textPrimary, modifier = Modifier.size(18.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.width(130.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceStrong)) {
                SideChoice("More", pick.side == "over", Modifier.weight(1f)) { onChange(pick.copy(side = "over")) }
                SideChoice("Less", pick.side == "under", Modifier.weight(1f)) { onChange(pick.copy(side = "under")) }
            }
            PicksField(
                value = lineText,
                onValueChange = { text ->
                    val clean = text.replace(',', '.')
                    if (clean.all { it.isDigit() || it == '.' } && clean.count { it == '.' } <= 1) {
                        lineText = clean
                        onChange(pick.copy(line = clean.toDoubleOrNull()))
                    }
                },
                placeholder = "Number",
                modifier = Modifier.width(66.dp),
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            PicksField(
                value = pick.statText,
                onValueChange = { onChange(pick.copy(statText = it)) },
                placeholder = "Stat",
                style = TextStyle(fontSize = 13.sp),
                modifier = Modifier.weight(1f)
            )
        }
        pick.problemText?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = picksWarning) }
    }
}

@Composable
private fun SideChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) LandingColors.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) picksOnAccent else AppTheme.colors.textPrimary
        )
    }
}

/**
 * Small enough to read well and send quickly: longest side 1600, JPEG at 80%,
 * as base64 for the callable. Only in memory. Null if the picture won't open.
 */
private suspend fun picksDownsizedJpeg(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > 1600) {
                val scale = 1600f / longest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
