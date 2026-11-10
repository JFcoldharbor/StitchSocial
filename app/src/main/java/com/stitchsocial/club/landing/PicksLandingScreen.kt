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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import com.stitchsocial.club.views.StitchChainRail
import com.stitchsocial.club.views.StitchChainRailLink
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.foundation.border
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
import com.stitchsocial.club.FollowManager
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
    // The hero's reel item, by video ID so a new take on a poll doesn't move it.
    var reelVideoID by remember { mutableStateOf<String?>(null) }
    // Which way the last move went, so the next video slides in from that side.
    var reelForward by remember { mutableStateOf(true) }
    // The quarter tapped for a conversation, for its badge when it covers more than one.
    var tappedQuarter by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showingUpload by remember { mutableStateOf(false) }
    // After the lock, an upload is a swap of a player listed out.
    var uploadIsSwap by remember { mutableStateOf(false) }
    // Which of your cards the upload is for: 1, or the host's 2 or 3.
    var uploadCard by remember { mutableStateOf(1) }
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
    // Following the show is following its account, the page's businessID. The
    // account itself gets no button.
    val showAccount = landing.businessID.ifBlank { PicksDash.accountID }
    val followManager = remember { FollowManager(context) }
    val followStates by followManager.followingStates.collectAsState()
    val followLoading by followManager.loadingStates.collectAsState()
    LaunchedEffect(landing.businessID) { PicksDash.noteAccount(landing.businessID) }
    LaunchedEffect(showAccount, uid) {
        if (uid != null && uid != showAccount) followManager.refreshFollowState(showAccount)
    }
    // FollowManager flips the pill first and puts it back if the write fails.
    DisposableEffect(followManager, showAccount) {
        followManager.onFollowStateChanged = { id, _ -> if (id == showAccount) PicksDash.followChanged() }
        followManager.onFollowError = { id, _ ->
            if (id == showAccount) model.message = "Couldn't update Following. Try again."
        }
        onDispose {
            followManager.onFollowStateChanged = null
            followManager.onFollowError = null
        }
    }
    // Null hides the pill: signed out, the show's own account, or not read yet.
    val followingShow = if (uid == null || uid == showAccount) null else followStates[showAccount]
    val toggleFollow: () -> Unit = {
        if (showAccount !in followLoading) followManager.toggleFollow(showAccount)
    }
    // The host can have up to 3 cards on a slate. This is card 1, or the lowest there is.
    val myCard = uid?.let { id -> night?.cards?.filter { it.userID == id && it.slate == slate }?.minByOrNull { it.cardIndex } }

    val reel = remember(night, slate) { night?.reel(slate).orEmpty() }
    val reelIndex = reel.indexOfFirst { it.videoID == reelVideoID }.coerceAtLeast(0)
    val reelItem = reel.getOrNull(reelIndex)
    // A new slate or night starts at its first video.
    LaunchedEffect(night?.id, slate) { reelVideoID = null }
    // Held by ID from then on, so a video added in front can't move it.
    LaunchedEffect(reelVideoID, reelItem?.videoID) {
        if (reelVideoID == null) reelVideoID = reelItem?.videoID
    }
    fun showReel(index: Int) {
        val target = reel.getOrNull(index) ?: return
        if (target.videoID == reelItem?.videoID) return
        reelForward = index > reelIndex
        reelVideoID = target.videoID
        videoPaused = false
    }

    // Sound from here on, so nothing else plays underneath.
    LaunchedEffect(Unit) { pauseFeedPlayback(context) }
    // A host video is claimed by the server a moment after it posts. Look again
    // then, so it's on the page without waiting for the next poll.
    val lastPosted by com.stitchsocial.club.services.BackgroundPostManager.lastCreatedVideoID.collectAsState()
    LaunchedEffect(lastPosted) {
        if (lastPosted == null) return@LaunchedEffect
        kotlinx.coroutines.delay(2_000)
        model.refresh()
        kotlinx.coroutines.delay(5_000)
        model.refresh()
    }

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
        reelVideoID = null
        tappedQuarter = null
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

    /**
     * A host or co-host answering one of the page's videos. It's an ordinary
     * stitch, and the server moves it onto the page as the next take.
     */
    fun replyTo(videoID: String) {
        if (readOnly || startingVideo || !AccountStanding.guardPosting()) return
        startingVideo = true
        scope.launch {
            val parent = PicksService.video(videoID)
            if (parent == null) {
                model.message = PicksError("").message
            } else {
                // The server takes a reply of up to 2:00.
                PicksRecordingLimit.set(120)
                PendingEntryHashtag.clear()
                recorderAsked = true
                onStitch(parent)
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
                            reel = reel,
                            reelIndex = reelIndex,
                            reelForward = reelForward,
                            tappedQuarter = tappedQuarter,
                            videoAllowed = !covered && onScreen,
                            videoPaused = videoPaused,
                            videoMuted = videoMuted,
                            onTogglePaused = { videoPaused = !videoPaused },
                            onToggleMuted = { videoMuted = !videoMuted },
                            onSlate = {
                                slate = it
                                reelVideoID = null
                                videoPaused = false
                            },
                            onClose = onDismiss,
                            onHost = { showingInvite = true },
                            onCoHost = { showingCoHostInvite = true },
                            onShowReel = { showReel(it) },
                            onPlayMoment = { moment, quarter ->
                                // The break's conversation, from its first take.
                                tappedQuarter = moment.conversationKey to quarter
                                val index = reel.indexOfFirst { it.conversationKey == moment.conversationKey }
                                if (index >= 0) showReel(index)
                                videoPaused = false
                            },
                            onOpenSlot = run {
                                val canReply = !readOnly && (page?.isHost == true || page?.isCoHost == true || night?.isHostTonight == true)
                                // As the stitch button: a fan with a card and no video, before the lock. A co-host after it too.
                                val canStitchCard = !readOnly && night != null && (!night.isLocked(slate) || page?.isCoHost == true) && !night.isHostTonight &&
                                    myCard != null && myCard.videoID == null && (night.videoID(slate) != null || night.mainVideoID != null)
                                val current = reelItem
                                when {
                                    current == null -> null
                                    canReply -> { { replyTo(current.videoID) } }
                                    canStitchCard -> { { record("card") } }
                                    else -> null
                                }
                            },
                            onFloor = { floorGame = it },
                            onUpload = { card ->
                                if (readOnly) return@TonightTab
                                uploadCard = card
                                // After the lock, an upload to a card you have is a swap. A co-host's
                                // first card can still go in then.
                                uploadIsSwap = night?.isLocked(slate) == true &&
                                    night.cards.any { it.userID == uid && it.slate == slate && it.cardIndex == card }
                                showingUpload = true
                            },
                            onRecordRole = { record(it) },
                            onOpenCard = { openCard = it },
                            followingShow = followingShow,
                            onToggleFollow = toggleFollow
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
                card = uploadCard,
                onlyUnstarted = !uploadIsSwap && night?.isLocked(slate) == true,
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
    reel: List<PicksReelItem>,
    reelIndex: Int,
    reelForward: Boolean,
    tappedQuarter: Pair<String, String>?,
    videoAllowed: Boolean,
    videoPaused: Boolean,
    videoMuted: Boolean,
    onTogglePaused: () -> Unit,
    onToggleMuted: () -> Unit,
    onSlate: (String) -> Unit,
    onClose: () -> Unit,
    onHost: () -> Unit,
    onCoHost: () -> Unit,
    onShowReel: (Int) -> Unit,
    onPlayMoment: (PicksMoment, String) -> Unit,
    onOpenSlot: (() -> Unit)?,
    onFloor: (PicksGame) -> Unit,
    /** Takes the card the upload is for: 1, 2 or 3. */
    onUpload: (Int) -> Unit,
    onRecordRole: (String) -> Unit,
    onOpenCard: (PicksCard) -> Unit,
    /** Null hides the Follow pill. */
    followingShow: Boolean? = null,
    onToggleFollow: () -> Unit = {}
) {
    val colors = AppTheme.colors
    val night = page?.night
    Column(Modifier.fillMaxWidth()) {
        PicksHero(
            page, slate, readOnly, reel, reelIndex, reelForward, tappedQuarter, videoAllowed, videoPaused, videoMuted,
            onTogglePaused, onToggleMuted, onClose, onHost, onCoHost, onShowReel, onOpenSlot,
            followingShow, onToggleFollow
        )
        if (night != null && night.hasLateSlate && !night.hidden) SlatePicker(night, slate, onSlate)
        if (night != null && night.games.isNotEmpty()) {
            SectionHeader(if (readOnly) "Games" else "Tonight's games", if (night.games.size > 1) "Swipe for the next game" else null)
            GameCards(night, reel.getOrNull(reelIndex), onPlayMoment, onFloor)
        }
        when {
            night != null && !night.hidden -> {
                val hostCards = night.hostCards(slate)
                if (!readOnly) {
                    // The host's cards count together.
                    val myScore = if (night.isHostTonight) night.hostTotal(slate) else myCard
                    PromptBanner(page, night, myScore, if (night.isHostTonight) hostCards.size else 1) { onRecordRole("moment") }
                    SplitBanner(night)
                    ActionButton(night, slate, myCard, page?.isCoHost == true, { onUpload(1) }, onRecordRole)
                    SwapButton(night, slate, if (night.isHostTonight) hostCards else listOfNotNull(myCard), onUpload)
                }
                hostCards.forEach { host ->
                    key(night.id, slate, host.cardIndex) { HostCardSection(night, slate, host, hostCards.size) }
                }
                if (!readOnly && night.isHostTonight && !night.isLocked(slate) && hostCards.size in 1 until MAX_HOST_CARDS) {
                    val next = (1..MAX_HOST_CARDS).first { i -> hostCards.none { it.cardIndex == i } }
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), contentAlignment = Alignment.Center) {
                        ArtistMetalButton("ADD ANOTHER CARD") { onUpload(next) }
                    }
                }
                SectionHeader(
                    if (night.isLocked(slate)) "Most picks right" else "Tonight's picks",
                    // People, not cards: the host's 3 cards are one of them.
                    if (readOnly || night.status == "final") null
                    else "${night.cards.filter { it.slate == slate }.distinctBy { it.userID }.size} in so far"
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
    reel: List<PicksReelItem>,
    reelIndex: Int,
    reelForward: Boolean,
    tappedQuarter: Pair<String, String>?,
    videoAllowed: Boolean,
    videoPaused: Boolean,
    videoMuted: Boolean,
    onTogglePaused: () -> Unit,
    onToggleMuted: () -> Unit,
    onClose: () -> Unit,
    onHost: () -> Unit,
    onCoHost: () -> Unit,
    onShow: (Int) -> Unit,
    /** Null hides the rail's open slot. */
    onOpenSlot: (() -> Unit)?,
    /** Null hides the Follow pill. */
    followingShow: Boolean? = null,
    onToggleFollow: () -> Unit = {}
) {
    val colors = AppTheme.colors
    val night = page?.night
    // The slate's video, its replies, then each break's takes. Each plays through
    // to the next, and the last one loops as before.
    val item = reel.getOrNull(reelIndex)
    val loops = reelIndex >= reel.lastIndex
    val speakerName = item?.let {
        if (it.moment == null) night?.hostName?.takeIf { n -> n.isNotBlank() } else it.moment.speakerName
    }
    // A recap has that night's host. Today's host and co-hosts may not have been on it.
    val host = night?.hostName?.takeIf { it.isNotEmpty() }
        ?: if (night?.hostName == null && !readOnly) page?.hostName?.takeIf { it.isNotEmpty() } else null
    val coHosts = if (readOnly) emptyList() else page?.coHosts.orEmpty()
    val showsRail = item != null && (reel.size > 1 || onOpenSlot != null)
    // Words stop short of the rail.
    val railReserve = if (showsRail) 72.dp else 0.dp

    // Read when a swipe ends, not when the gesture started.
    val count by rememberUpdatedState(reel.size)
    val index by rememberUpdatedState(reelIndex)
    val show by rememberUpdatedState(onShow)

    Box(
        Modifier
            .fillMaxWidth()
            .height(PICKS_HERO_HEIGHT)
            .clipToBounds()
            .background(picksHeroFallback)
            // Horizontal only, so the page still scrolls and the buttons still tap.
            .pointerInput(Unit) {
                val threshold = 50.dp.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            total <= -threshold && index < count - 1 -> show(index + 1)
                            total >= threshold && index > 0 -> show(index - 1)
                        }
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        total += amount
                    }
                )
            }
            .semantics {
                if (item != null && reel.size > 1) {
                    contentDescription = listOfNotNull("Video ${reelIndex + 1} of ${reel.size}", speakerName).joinToString(", ")
                }
                customActions = buildList {
                    if (reelIndex < reel.lastIndex) add(CustomAccessibilityAction("Next video") { onShow(reelIndex + 1); true })
                    if (reelIndex > 0) add(CustomAccessibilityAction("Previous video") { onShow(reelIndex - 1); true })
                }
            }
    ) {
        if (item != null) {
            // Off the page under the recorder or another video, and with the app away.
            if (videoAllowed) {
                // One player for the reel. The video sliding out shows its thumbnail.
                val player = rememberPicksHeroPlayer(item.videoURL, videoPaused, videoMuted, loops) {
                    if (index < count - 1) show(index + 1)
                }
                AnimatedContent(
                    targetState = item,
                    contentKey = { it.videoID },
                    transitionSpec = {
                        val side = if (reelForward) 1 else -1
                        slideInHorizontally { it * side } togetherWith slideOutHorizontally { -it * side }
                    },
                    label = "picksReel",
                    modifier = Modifier.fillMaxSize()
                ) { shown ->
                    // Host videos are vertical and a face sits in the top third, so the
                    // video is laid out at full 9:16 and slid up, not centre-cropped.
                    BoxWithConstraints(Modifier.fillMaxSize().background(picksHeroFallback)) {
                        val videoHeight = maxWidth * (16f / 9f)
                        val frameHeight = videoHeight.coerceAtLeast(maxHeight)
                        val shift = ((videoHeight - maxHeight) * PICKS_HERO_FACE_BIAS).coerceAtLeast(0.dp)
                        val frame = Modifier.fillMaxWidth().requiredHeight(frameHeight)
                            .align(Alignment.TopCenter).offset(y = -shift + (frameHeight - maxHeight) / 2)
                        if (shown.videoID == item.videoID) {
                            PicksHeroSurface(player, frame)
                        } else if (shown.thumbnailURL != null) {
                            AsyncImage(
                                model = shown.thumbnailURL, contentDescription = null,
                                contentScale = ContentScale.Crop, modifier = frame
                            )
                        }
                    }
                }
            }
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
                // Light at the top for the buttons, darker low where the words are.
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.22f to Color.Transparent,
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.8f)
                )
            )
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PicksBackButton(onClose)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ReelIndicator(reel.size, reelIndex)
                }
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
            // The face is in the top of the shot, so the words sit in the lower third.
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(end = railReserve), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The main video says who's speaking only once there are replies to it.
                if (item != null && (item.moment != null || item.takeCount > 1)) {
                    val speaker = listOfNotNull(
                        speakerName,
                        if (item.takeCount > 1) "Take ${item.takeNumber} of ${item.takeCount}" else null
                    ).joinToString(" · ")
                    if (speaker.isNotEmpty()) {
                        Text(speaker, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    when {
                        host == null -> "By Stitch Social"
                        coHosts.isEmpty() -> "By Stitch Social · Hosted by $host"
                        else -> "By Stitch Social · Hosted by $host · with ${coHosts.joinToString { it.name }}"
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2
                )
                // The title gives way so the pill always fits beside it.
                Row(
                    Modifier.padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        (night?.title ?: "Stitch Picks").uppercase(),
                        fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, color = picksGold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    followingShow?.let { PicksFollowPill(it, onToggleFollow) }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Measured after the buttons, so a long badge wraps and can't squeeze them out.
                Box(Modifier.weight(1f)) {
                    heroBadge(night, slate, item, tappedQuarter)?.let { badge ->
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
                if (reelIndex > 0) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clip(CircleShape)
                            .background(colors.bg)
                            .clickable { onShow(0) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Main video", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
                if (item != null) {
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
        // The reel as a thread, above the pause and mute buttons. A tile plays that
        // video here, and the open slot records onto the one playing.
        if (showsRail) {
            val chain = remember(reel) { reel.map { StitchChainRailLink(it.videoID, it.thumbnailURL) } }
            StitchChainRail(
                chain = chain,
                currentID = item.videoID,
                onTapLink = { link -> reel.indexOfFirst { it.videoID == link.id }.takeIf { it >= 0 }?.let(onShow) },
                onTapOpenSlot = onOpenSlot,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 64.dp)
            )
        }
    }
}

/** Dots for a short reel, the current one wider and brighter, or "3 / 12" for a long one. */
@Composable
private fun ReelIndicator(count: Int, index: Int) {
    if (count <= 1) return
    // The hero says which video this is, so these stay out of the way of a screen reader.
    if (count <= 8) {
        Row(
            modifier = Modifier.clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(count) { i ->
                val on = i == index
                val width by animateDpAsState(if (on) 16.dp else 6.dp, label = "reelDot")
                Box(
                    Modifier.size(width, 6.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = if (on) 1f else 0.45f))
                )
            }
        }
    } else {
        Text(
            "${index + 1} / $count",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier
                .clearAndSetSemantics {}
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** Follow the show: a gold outline to follow, a quiet fill with a check once following. */
@Composable
private fun PicksFollowPill(following: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .clip(shape)
            .then(
                if (following) Modifier.background(Color.White.copy(alpha = 0.18f))
                else Modifier.background(Color.Black.copy(alpha = 0.25f)).border(1.dp, picksGold, shape)
            )
            .clickable(onClickLabel = if (following) "Unfollow Stitch Picks" else "Follow Stitch Picks", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (following) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Text(
            if (following) "Following" else "Follow",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (following) Color.White else picksGold,
            maxLines = 1
        )
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

/** Tall enough for a head and shoulders out of a vertical video. */
private val PICKS_HERO_HEIGHT = 440.dp
/** How far down a tall video the window sits: 0 is the top, 0.5 the centre. */
private const val PICKS_HERO_FACE_BIAS = 0.2f

/**
 * The hero's player, with sound unless muted. One for the whole reel: a new item
 * swaps what it plays. Its own player because the hero can pause and mute, and
 * BirthdayLoopingVideo can't.
 */
@Composable
private fun rememberPicksHeroPlayer(
    url: String,
    paused: Boolean,
    muted: Boolean,
    loops: Boolean,
    onEnded: () -> Unit
): ExoPlayer {
    val context = LocalContext.current
    val ended by rememberUpdatedState(onEnded)
    val player = remember { ExoPlayer.Builder(context).build() }
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
    LaunchedEffect(player, url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }
    SideEffect {
        player.repeatMode = if (loops) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = if (muted) 0f else 1f
        player.playWhenReady = !paused
    }
    return player
}

@Composable
private fun PicksHeroSurface(player: ExoPlayer, modifier: Modifier) {
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
        onRelease = { it.player = null },
        modifier = modifier
    )
}

@Composable
private fun SlatePicker(night: PicksNight, slate: String, onSlate: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlateButton("Main slate", night.locked, night.locksAt, slate == "main", Modifier.weight(1f)) { onSlate("main") }
        SlateButton("Late slate", night.lateLocked, night.lateLocksAt, slate == "late", Modifier.weight(1f)) { onSlate("late") }
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
                else -> "Locks 10 min after first tip"
            },
            fontSize = 11.sp, color = tint
        )
    }
}

private fun heroBadge(night: PicksNight?, slate: String, item: PicksReelItem?, tappedQuarter: Pair<String, String>?): String? {
    val moment = item?.moment
    if (moment != null) {
        val right = moment.right ?: return null
        val total = moment.total ?: return null
        // A reply to the main video isn't at a break.
        if (moment.isVideoReply) return "$right OF $total RIGHT"
        val quarter = tappedQuarter?.takeIf { it.first == moment.conversationKey }?.second
            ?: moment.breaks.firstOrNull()?.quarter
        return if (quarter.isNullOrEmpty()) "$right OF $total RIGHT" else "$right OF $total AT END OF $quarter"
    }
    night ?: return null
    if (!night.isLocked(slate)) return if (slate == "late") "LOCKS AT LATE TIP" else "LOCKS AT FIRST TIP"
    // All the host's cards on the slate, together.
    val host = night.hostTotal(slate) ?: return null
    return if (night.status == "final") "${host.right} OF ${host.total} RIGHT" else "${host.right} OF ${host.total} RIGHT SO FAR"
}

@Composable
private fun GameCards(
    night: PicksNight,
    current: PicksReelItem?,
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
            GameCard(game, night, current, onPlayMoment, onFloor, Modifier.width(cardWidth))
        }
    }
}

@Composable
private fun GameCard(
    game: PicksGame,
    night: PicksNight,
    current: PicksReelItem?,
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
                    QuarterChip(quarter, game, night, current, onPlayMoment, Modifier.weight(1f))
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
    current: PicksReelItem?,
    onPlayMoment: (PicksMoment, String) -> Unit,
    modifier: Modifier
) {
    val colors = AppTheme.colors
    val moment = night.moment(game.id, quarter)
    val takes = moment?.let { night.conversation(it).size } ?: 0
    // On while the hero plays a take from this break.
    val isOn = moment != null && current?.moment?.let { take ->
        take.conversationKey == moment.conversationKey && take.breaks.any { it.gameID == game.id && it.quarter == quarter }
    } == true
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

/** Where you and the other host disagree, before either of you talks about it. */
@Composable
private fun SplitBanner(night: PicksNight) {
    if (night.splits.isEmpty()) return
    val colors = AppTheme.colors
    val amber = Color(0xFFF5B84A)
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(amber.copy(alpha = 0.10f))
            .border(1.dp, amber.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (night.splits.size == 1) "YOU SPLIT ON 1 PICK" else "YOU SPLIT ON ${night.splits.size} PICKS",
            fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = amber
        )
        night.splits.forEach { split ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(split.player, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("${split.who}: ${split.theirs}", fontSize = 12.5.sp, color = colors.textSecondary)
                Text("You: ${split.mine}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("Your script brings it up.", fontSize = 11.5.sp, color = colors.textSecondary)
    }
}

@Composable
private fun PromptBanner(page: PicksPage?, night: PicksNight, myCard: PicksCard?, cardCount: Int, onRecord: () -> Unit) {
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
            Text("Your ${it.slate} ${if (cardCount > 1) "cards" else "card"}: ${it.right} of ${it.total} right so far", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
    isCoHost: Boolean,
    onUpload: () -> Unit,
    onRecord: (String) -> Unit
) {
    // Picks are locked, but the host's video can still go up.
    val locked = night.isLocked(slate)
    val slateVideoID = night.videoID(slate)
    // A co-host can still add their one card after the lock, and record its video.
    val coHostAfterLock = locked && isCoHost && !night.isHostTonight
    if (coHostAfterLock && myCard?.videoID != null) return
    if (locked && !(night.isHostTonight && slateVideoID == null) && !coHostAfterLock) return
    val colors = AppTheme.colors
    if (coHostAfterLock && myCard == null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ArtistMetalButton("ADD YOUR CARD", onUpload)
            Text("Only picks in games that haven't started.", fontSize = 12.sp, color = colors.textSecondary)
        }
        return
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            locked && night.isHostTonight ->
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
private fun SwapButton(night: PicksNight, slate: String, myCards: List<PicksCard>, onUpload: (Int) -> Unit) {
    if (!night.isLocked(slate)) return
    // The host can have up to 3 cards. Each swap goes to its own card.
    val eligible = myCards.filter { card ->
        card.picks.any { pick ->
            pick.isListedOut && night.games.firstOrNull { it.id == pick.gameID }?.hasStarted == false
        }
    }
    if (eligible.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (eligible.size == 1) {
            ArtistMetalButton("SWAP AN OUT PLAYER") { onUpload(eligible[0].cardIndex) }
        } else {
            eligible.forEach { card ->
                key(card.cardIndex) { ArtistMetalButton("SWAP ON CARD ${card.cardIndex}") { onUpload(card.cardIndex) } }
            }
        }
        Text(
            "Upload your updated sheet. Only a player listed out can change, and only for a game that hasn't started.",
            fontSize = 12.sp, color = AppTheme.colors.textSecondary
        )
    }
}

private fun picksPrefs(context: Context) = context.getSharedPreferences("stitch_picks", Context.MODE_PRIVATE)

/** The host's cards per slate. Everyone else has 1. The server holds the same limit. */
private const val MAX_HOST_CARDS = 3

/**
 * One of the host's cards, which folds down to one line. Remembered per night,
 * slate and card. Card 1 keeps the key it had before the host could have more.
 */
@Composable
private fun HostCardSection(night: PicksNight, slate: String, host: PicksCard, cardCount: Int) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val prefKey = if (host.cardIndex == 1) "picksCollapsed_${night.id}_$slate" else "picksCollapsed_${night.id}_${slate}_${host.cardIndex}"
    var collapsed by remember(prefKey) {
        mutableStateOf(runCatching { picksPrefs(context).getBoolean(prefKey, false) }.getOrDefault(false))
    }
    val title = when {
        cardCount > 1 -> "Card ${host.cardIndex}"
        slate == "late" -> "Late card"
        else -> "Main card"
    }
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
        key(host.id) {
            PicksSlipCard(
                host, night, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                title = if (cardCount > 1) "${host.username}'s ${host.slate} card ${host.cardIndex}" else null
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (card.isHost) "${card.username}'s ${card.slate} card" else "@${card.username}",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (card.isCoHost) {
                    Text(
                        "Co-host", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = LandingColors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(LandingColors.accent.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
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
        if (!coHost) InviteLine("Before first tip", "One video, up to 2:00, on up to 3 cards of 2 picks or more")
        InviteLine("When a quarter ends", "One moment, up to 1:00, for every game at a break. Skip any you like.")
        if (!coHost) InviteLine("Your main card locks 10 minutes after first tip", "Right and wrong picks both stay on the record")
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
private fun PicksUploadSheet(
    landingID: String,
    isHost: Boolean,
    slate: String,
    isSwap: Boolean,
    /** 1, or the host's card 2 or 3. */
    card: Int,
    /** A co-host's card after the lock: only games that haven't started. */
    onlyUnstarted: Boolean,
    onDismiss: () -> Unit
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picks by remember { mutableStateOf<List<PicksPick>>(emptyList()) }
    var isWorking by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // The host's 5-pick minimum is across all their cards, checked by the server when the video starts.
    val canSave = picks.isNotEmpty() && !isWorking

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
                PicksService.submit(landingID, slate, picks, card)
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
                    card > 1 -> "UPLOAD CARD $card"
                    else -> "UPLOAD YOUR SHEET"
                },
                fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold
            )
            Text(
                if (isSwap) {
                    "Upload your updated sheet. Only a player listed out can change, and only for a game that hasn't started."
                } else if (onlyUnstarted) {
                    "We read your PrizePicks or Underdog sheet. Fix anything we got wrong. Only picks in games that haven't started."
                } else {
                    "We read your PrizePicks or Underdog sheet. Fix anything we got wrong. " +
                        if (slate == "late") "This card is for the late games, and locks 10 minutes after the first one tips." else "Your picks lock 10 minutes after the first game tips."
                },
                fontSize = 13.sp, color = colors.textSecondary
            )
            if (isHost && !isSwap) {
                Text("Each card needs at least 2 picks.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
