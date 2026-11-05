package com.stitchsocial.club.landing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.auth.FirebaseAuth
import com.stitchsocial.club.birthday.BirthdayLoopingVideo
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
    suspend fun beginVideo(role: String): PicksVideoStart? = try {
        PicksService.beginVideo(landingID, role).also { PicksRecordingLimit.set(it.seconds) }
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
    var playingMoment by remember { mutableStateOf<Pair<PicksMoment, String>?>(null) }
    var showingUpload by remember { mutableStateOf(false) }
    var floorGame by remember { mutableStateOf<PicksGame?>(null) }
    var openCard by remember { mutableStateOf<PicksCard?>(null) }
    var showingInvite by remember { mutableStateOf(false) }
    var showingCoHostInvite by remember { mutableStateOf(false) }
    var startingVideo by remember { mutableStateOf(false) }
    // The recorder was asked for, and hasn't come up yet.
    var recorderAsked by remember { mutableStateOf(false) }
    var recorderUp by remember { mutableStateOf(false) }

    val page = model.page
    val night = page?.night
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val myCard = uid?.let { id -> night?.cards?.firstOrNull { it.id == id } }

    // Sound from here on, so nothing else plays underneath.
    LaunchedEffect(Unit) { pauseFeedPlayback(context) }

    // The page reads through the server, so it asks again while it's up. Coming
    // back from the recorder or a video starts this over, which refreshes.
    LaunchedEffect(landing.landingID, covered, onScreen) {
        if (covered || !onScreen) return@LaunchedEffect
        while (true) {
            model.refresh()
            delay(30_000)
        }
    }

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
        if (startingVideo || !AccountStanding.guardPosting()) return
        startingVideo = true
        scope.launch {
            val start = model.beginVideo(role)
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
                when {
                    model.isLoading && page == null -> Box(
                        Modifier.fillMaxWidth().padding(top = 200.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = LandingColors.accent) }
                    page?.pendingInvite == true -> InviteAnswer(
                        coHost = page?.pendingInviteRole == "cohost",
                        onClose = onDismiss,
                        onAnswer = { accept -> scope.launch { model.respond(accept) } }
                    )
                    else -> when (tab) {
                        PicksTab.TONIGHT -> TonightTab(
                            page = page,
                            myCard = myCard,
                            playingMoment = playingMoment,
                            videoAllowed = !covered && onScreen,
                            onClose = onDismiss,
                            onHost = { showingInvite = true },
                            onCoHost = { showingCoHostInvite = true },
                            onMainVideo = { playingMoment = null },
                            onPlayMoment = { moment, quarter -> playingMoment = moment to quarter },
                            onFloor = { floorGame = it },
                            onUpload = { showingUpload = true },
                            onRecordRole = { record(it) },
                            onOpenCard = { openCard = it }
                        )
                        PicksTab.STITCHES -> StitchesTab(night, onOpen = { openVideo(it.videoID) })
                        PicksTab.RECORD -> RecordTab(page)
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
        if (showingUpload) {
            PicksUploadSheet(
                landingID = landing.landingID,
                isHost = night?.isHostTonight == true,
                onDismiss = {
                    showingUpload = false
                    scope.launch { model.refresh() }
                }
            )
        }
        floorGame?.let { game -> PicksFloorSheet(game) { floorGame = null } }
        openCard?.let { card -> PicksCardSheet(card) { openCard = null } }
        if (showingInvite) {
            PicksInviteSheet(
                model = model,
                currentHost = page?.hostName,
                onDismiss = {
                    showingInvite = false
                    scope.launch { model.refresh() }
                }
            )
        }
        if (showingCoHostInvite) {
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
    myCard: PicksCard?,
    playingMoment: Pair<PicksMoment, String>?,
    videoAllowed: Boolean,
    onClose: () -> Unit,
    onHost: () -> Unit,
    onCoHost: () -> Unit,
    onMainVideo: () -> Unit,
    onPlayMoment: (PicksMoment, String) -> Unit,
    onFloor: (PicksGame) -> Unit,
    onUpload: () -> Unit,
    onRecordRole: (String) -> Unit,
    onOpenCard: (PicksCard) -> Unit
) {
    val colors = AppTheme.colors
    val night = page?.night
    Column(Modifier.fillMaxWidth()) {
        PicksHero(page, playingMoment, videoAllowed, onClose, onHost, onCoHost, onMainVideo)
        if (night != null && night.games.isNotEmpty()) {
            SectionHeader("Tonight's games", if (night.games.size > 1) "Swipe for the next game" else null)
            GameCards(night, playingMoment, onPlayMoment, onFloor)
        }
        when {
            night != null && !night.hidden -> {
                PromptBanner(night, page?.isCoHost == true, myCard) { onRecordRole("moment") }
                ActionButton(night, myCard, onUpload, onRecordRole)
                SectionHeader(
                    if (night.locked) "Most picks right" else "Tonight's picks",
                    if (night.status == "final") "Videos hide 24 hours after the last game" else "${night.cards.size} in so far"
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (night.cards.isEmpty()) {
                        Text("No picks in yet.", fontSize = 13.sp, color = colors.textSecondary)
                    }
                    night.cards.forEach { card -> CardRow(card, night) { onOpenCard(card) } }
                }
            }
            night == null -> Text(
                "No games today. The next slate shows up here.",
                fontSize = 14.sp, color = colors.textSecondary, modifier = Modifier.padding(16.dp)
            )
            else -> Text(
                "Tonight's videos are hidden. The results stay in Record.",
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
    playingMoment: Pair<PicksMoment, String>?,
    videoAllowed: Boolean,
    onClose: () -> Unit,
    onHost: () -> Unit,
    onCoHost: () -> Unit,
    onMainVideo: () -> Unit
) {
    val colors = AppTheme.colors
    val night = page?.night
    val url = (playingMoment?.first?.videoURL ?: night?.mainVideoURL)?.takeIf { it.isNotBlank() }
    val host = night?.hostName?.takeIf { it.isNotEmpty() } ?: if (night?.hostName == null) page?.hostName?.takeIf { it.isNotEmpty() } else null

    Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds().background(picksHeroFallback)) {
        if (url != null) {
            // Off the page under the recorder or another video, and with the app away.
            if (videoAllowed) BirthdayLoopingVideo(url, Modifier.fillMaxSize())
        } else {
            Text(
                if (night?.hidden == true) "Tonight's videos are hidden" else "The host's video shows here",
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
                if (page?.isHost == true) {
                    Box(
                        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(onClick = onCoHost),
                        contentAlignment = Alignment.Center
                    ) { Text("Co-host", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(12.dp))
                }
                if (page?.isAdmin == true) {
                    Box(
                        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(onClick = onHost),
                        contentAlignment = Alignment.Center
                    ) { Text("Host", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Text(
                when {
                    host == null -> "By Stitch Social"
                    page?.coHosts.isNullOrEmpty() -> "By Stitch Social · Hosted by $host"
                    else -> "By Stitch Social · Hosted by $host · with ${page?.coHosts?.joinToString { it.name }}"
                },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                (night?.title ?: "Stitch Picks").uppercase(),
                fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = picksGold
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                heroBadge(night, playingMoment)?.let { badge ->
                    Text(
                        badge,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.bg)
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
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
            }
        }
    }
}

private fun heroBadge(night: PicksNight?, playingMoment: Pair<PicksMoment, String>?): String? {
    if (playingMoment != null) {
        val (moment, quarter) = playingMoment
        val right = moment.right ?: return null
        val total = moment.total ?: return null
        return "$right OF $total AT END OF $quarter"
    }
    night ?: return null
    if (!night.locked) return "LOCKS AT FIRST TIP"
    val host = night.cards.firstOrNull { it.isHost } ?: return null
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
    val hasPicks = night.cards.firstOrNull { it.isHost }?.picks?.any { it.gameID == game.id } == true
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
    val isOn = moment != null && playingMoment?.first?.id == moment.id && playingMoment.second == quarter
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
        Text(moment?.lengthText ?: "None", fontSize = 11.sp, color = tint)
    }
}

@Composable
private fun PromptBanner(night: PicksNight, isCoHost: Boolean, myCard: PicksCard?, onRecord: () -> Unit) {
    if ((!night.isHostTonight && !isCoHost) || night.promptBreaks.isEmpty()) return
    val colors = AppTheme.colors
    val breaks = night.promptBreaks
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
            Text("Your main card: ${it.right} of ${it.total} right so far", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        ArtistMetalButton("RECORD A MOMENT · UP TO 1:00", onRecord)
        Text("Or skip it. Nothing shows for this break.", fontSize = 12.sp, color = colors.textSecondary)
    }
}

@Composable
private fun ActionButton(
    night: PicksNight,
    myCard: PicksCard?,
    onUpload: () -> Unit,
    onRecord: (String) -> Unit
) {
    // Picks are locked, but the host's video can still go up.
    if (night.locked && !(night.isHostTonight && night.mainVideoID == null)) return
    val colors = AppTheme.colors
    Box(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            night.locked ->
                ArtistMetalButton("RECORD YOUR VIDEO · UP TO 2:00") { onRecord("main") }
            myCard == null ->
                ArtistMetalButton(if (night.isHostTonight) "ADD YOUR MAIN CARD" else "STITCH YOUR PICKS", onUpload)
            night.isHostTonight && night.mainVideoID == null ->
                ArtistMetalButton("RECORD YOUR VIDEO · UP TO 2:00") { onRecord("main") }
            !night.isHostTonight && myCard.videoID == null ->
                if (night.mainVideoID == null) {
                    Text(
                        "You're in. You can record once the host posts tonight's video.",
                        fontSize = 13.sp, color = colors.textSecondary
                    )
                } else {
                    ArtistMetalButton("RECORD YOUR VIDEO") { onRecord("card") }
                }
            else -> Text(
                "You're all set. Picks lock when the first game tips.",
                fontSize = 13.sp, color = colors.textSecondary
            )
        }
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
    val detail = when {
        !night.locked -> "${card.picks.size} picks"
        card.open > 0 -> "${card.open} ${if (card.open == 1) "pick" else "picks"} still to play"
        card.wrong == 0 -> "All ${card.right} right"
        else -> "${card.wrong} wrong"
    }
    val statusColor = when {
        !night.locked -> colors.textSecondary
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
                if (card.isHost) "${card.username}'s main card" else "@${card.username}",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            Text(detail, fontSize = 12.sp, color = colors.textSecondary)
        }
        Text(
            if (night.locked) "${card.right} OF ${card.total}" else "NOT STARTED",
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
            Text("Tonight's videos are hidden.", fontSize = 14.sp, color = colors.textSecondary)
        } else if (cards.isEmpty()) {
            Text("No stitches yet.", fontSize = 14.sp, color = colors.textSecondary)
        }
        if (night != null) cards.forEach { card -> CardRow(card, night) { onOpen(card) } }
    }
}

// MARK: - Record

@Composable
private fun RecordTab(page: PicksPage?) {
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
        Text("Videos hide 24 hours after each night. Results stay.", fontSize = 12.sp, color = colors.textSecondary)
        Text("Past nights", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        if (page?.recentNights.isNullOrEmpty()) {
            Text("Finished nights show here.", fontSize = 13.sp, color = colors.textSecondary)
        }
        page?.recentNights.orEmpty().forEach { past ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(past.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        past.hostName?.let { "${past.date} · $it" } ?: past.date,
                        fontSize = 12.sp, color = colors.textSecondary
                    )
                }
                Text("${past.right} OF ${past.total}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
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
            if (coHost) "You're invited to join the host. Your videos post to the Stitch Picks page with your name on them, and hide 24 hours after the night ends." else "Stitch Picks invited you to host. Your videos post to the Stitch Picks page with your name on them, and hide 24 hours after the night ends.",
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
private fun PicksCardSheet(card: PicksCard, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bg, contentColor = colors.textPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("@${card.username}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${card.right} of ${card.total} right", fontSize = 14.sp, color = LandingColors.accent)
            card.picks.forEach { pick ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(pick.player, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            pick.stateText,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = if (pick.result == "right") LandingColors.accent else colors.textSecondary
                        )
                    }
                    Text(pick.lineText, fontSize = 12.sp, color = colors.textSecondary)
                }
            }
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
private fun PicksUploadSheet(landingID: String, isHost: Boolean, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picks by remember { mutableStateOf<List<PicksPick>>(emptyList()) }
    var isWorking by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val canSave = picks.isNotEmpty() && (!isHost || picks.size >= 5) && !isWorking

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
                PicksService.submit(landingID, picks)
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
                if (picks.isEmpty()) "UPLOAD YOUR SHEET" else "CHECK YOUR PICKS",
                fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold
            )
            Text(
                "We read your PrizePicks or Underdog sheet. Fix anything we got wrong. Your picks lock when the first game tips.",
                fontSize = 13.sp, color = colors.textSecondary
            )
            if (isHost) {
                Text("Your main card needs at least 5 picks.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
