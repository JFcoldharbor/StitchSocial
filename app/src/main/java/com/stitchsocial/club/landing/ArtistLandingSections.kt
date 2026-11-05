package com.stitchsocial.club.landing

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.stitchsocial.club.camera.ChallengeConfigSection
import com.stitchsocial.club.challenge.Challenge
import com.stitchsocial.club.challenge.ChallengeDraft
import com.stitchsocial.club.challenge.ChallengeEntryStatus
import com.stitchsocial.club.challenge.ChallengeScope
import com.stitchsocial.club.challenge.ChallengeService
import com.stitchsocial.club.challenge.ChallengeState
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.views.StitchChainRail
import com.stitchsocial.club.views.StitchChainRailLink
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The pieces of an artist's page that only draw: tabs, hero, tracklist, giveaway
 * and the song cards. ArtistLandingScreen holds the state.
 *
 * Layer 5: Views
 */
internal enum class ArtistTab(val label: String) {
    MUSIC("Music"), FAN_MAIL("Fan Mail"), GIVEAWAY("Giveaway"), VIDEOS("Videos"), TOUR("Tour")
}

internal val artistGold = listOf(Color(0xFFF9E6AE), Color(0xFFD9A441), Color(0xFFFFF5D2), Color(0xFFB8862F))
private val artistMetal = arrayOf(
    0f to Color(0xFF4A4A4E),
    0.45f to Color(0xFF77777C),
    0.58f to Color(0xFF9A9A9F),
    0.72f to Color(0xFF3E3E42),
    1f to Color(0xFF5A5A5E)
)

internal fun landingDay(date: Date): String = SimpleDateFormat("MMM d", Locale.US).format(date)

@Composable
internal fun ArtistTabBar(selected: ArtistTab, onSelect: (ArtistTab) -> Unit) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.bg).navigationBarsPadding()) {
        HorizontalDivider(color = colors.hairline)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            ArtistTab.entries.forEach { item ->
                Box(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable { onSelect(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        item.label,
                        fontSize = 13.sp,
                        fontWeight = if (item == selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (item == selected) colors.textPrimary else colors.textSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
internal fun ArtistCircleButton(content: @Composable () -> Unit, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(AppTheme.colors.bg.copy(alpha = 0.6f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

// MARK: - Hero

@OptIn(UnstableApi::class)
@Composable
private fun SongVideo(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = modifier
    )
}

private fun songTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** Drag anywhere along it to scan through the song. */
@Composable
private fun SongProgressBar(song: SongController, modifier: Modifier = Modifier) {
    var scanning by remember { mutableStateOf<Float?>(null) }
    val duration = song.durationMs.toFloat()
    val shown = scanning ?: song.positionMs.toFloat()
    val label = TextStyle(
        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 3f)
    )
    Row(
        modifier = modifier.semantics { contentDescription = "Song position" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(songTime(shown.toLong()), style = label)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(song) {
                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f) * song.durationMs
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (song.durationMs <= 0) return@awaitEachGesture
                        down.consume()
                        scanning = at(down.position.x)
                        do {
                            val change = awaitPointerEvent().changes.first()
                            change.consume()
                            if (change.pressed) scanning = at(change.position.x)
                        } while (change.pressed)
                        scanning?.let { song.seek(it.toLong()) }
                        scanning = null
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val fraction = if (duration > 0) (shown / duration).coerceIn(0f, 1f) else 0f
            val knob = if (scanning != null) 16.dp else 10.dp
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
            Box(Modifier.width(maxWidth * fraction).height(3.dp).clip(CircleShape).background(Color.White))
            Box(Modifier.offset(x = maxWidth * fraction - knob / 2).size(knob).clip(CircleShape).background(Color.White))
        }
        Text(songTime(song.durationMs), style = label)
    }
}

@Composable
internal fun ArtistHero(
    landing: Landing,
    video: CoreVideoMetadata?,
    title: String,
    line: String?,
    song: SongController?,
    showsBar: Boolean,
    chain: List<StitchChainRailLink>,
    onTapLink: (StitchChainRailLink) -> Unit,
    onOpenSlot: (() -> Unit)?
) {
    val colors = AppTheme.colors
    val config = LocalConfiguration.current
    val limit = config.screenHeightDp * 0.62f
    val aspect = video?.aspectRatio ?: 0.0
    // As tall as the video, so nothing in it gets cropped, up to a limit that
    // leaves the title and the main button on the first screen.
    val height = if (aspect > 0) minOf(config.screenWidthDp / aspect.toFloat(), limit) else limit
    val picture = video?.thumbnailURL?.takeIf { it.isNotBlank() } ?: landing.coverURL

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(height.dp).clipToBounds().background(LandingColors.coverFallback)) {
            if (song != null && video != null) SongVideo(song.player, Modifier.fillMaxSize())
            // On top until the song's first frame, so a change of song never goes black.
            if (picture != null && (song == null || song.renderedVideoID != video?.id)) {
                AsyncImage(
                    model = picture, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to colors.bg.copy(alpha = 0.45f),
                        0.12f to Color.Transparent,
                        0.92f to Color.Transparent,
                        1f to colors.bg
                    )
                )
            )
            // Scan through the song, in the fade above the name. It stops short of
            // the rail, so the two never share a touch.
            if (song != null && video != null && showsBar) {
                SongProgressBar(
                    song,
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = if (chain.size > 1) 72.dp else 16.dp, bottom = 2.dp)
                )
            }
            if (video != null && chain.size > 1) {
                StitchChainRail(
                    chain = chain,
                    currentID = video.id,
                    onTapLink = onTapLink,
                    onTapOpenSlot = onOpenSlot,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 20.dp)
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (landing.release != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(landing.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    if (landing.verified) VerifiedSeal(14)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val upper = title.uppercase()
                val size = when {
                    upper.length <= 12 -> 54
                    upper.length <= 20 -> 42
                    else -> 32
                }
                Text(
                    upper,
                    style = TextStyle(
                        brush = Brush.linearGradient(artistGold, Offset.Zero, Offset(1000f, 0f)),
                        fontSize = size.sp,
                        lineHeight = (size + 2).sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (landing.release == null && landing.verified) VerifiedSeal(22)
            }
            if (line != null) Text(line, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.textSecondary)
        }
    }
}

@Composable
private fun VerifiedSeal(size: Int) {
    Icon(Icons.Default.Verified, contentDescription = "Verified", tint = LandingColors.accent, modifier = Modifier.size(size.dp))
}

// MARK: - Controls

@Composable
internal fun ArtistMetalButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(*artistMetal))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = Color.White)
    }
}

@Composable
internal fun ArtistNotice(text: String, icon: ImageVector) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
    }
}

@Composable
internal fun ArtistTabTitle(title: String, detail: String? = null) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black, color = colors.textPrimary)
        if (detail != null) Text(detail, fontSize = 14.sp, color = colors.textSecondary)
    }
}

// MARK: - Tracklist

@Composable
internal fun ArtistTrackList(
    tracks: List<LandingRelease.Track>,
    isPlayable: (LandingRelease.Track) -> Boolean,
    isCurrent: (LandingRelease.Track) -> Boolean,
    paused: Boolean,
    onSelect: (LandingRelease.Track) -> Unit
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Tracklist", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        tracks.forEachIndexed { index, track ->
            val playable = isPlayable(track)
            val current = isCurrent(track)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .clickable(enabled = playable) { onSelect(track) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("${index + 1}", fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.width(22.dp))
                Text(
                    track.title,
                    fontSize = 16.sp,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    color = if (current) LandingColors.accent else colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (playable) {
                    val pausing = current && !paused
                    Icon(
                        if (pausing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "${if (pausing) "Pause" else "Play"} ${track.title}",
                        tint = LandingColors.accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// MARK: - The song

@Composable
internal fun ArtistHookCard(video: CoreVideoMetadata, title: String, drops: Int, onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF24324A))
            .clickable(onClick = onOpen)
    ) {
        if (video.thumbnailURL.isNotBlank()) {
            AsyncImage(
                model = video.thumbnailURL, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
        Box(
            modifier = Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play $title", tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            LandingPill("$drops ${if (drops == 1) "drop" else "drops"}", Color.Black.copy(alpha = 0.75f), Color.White)
        }
    }
}

@Composable
internal fun ArtistVideoGrid(videos: List<CoreVideoMetadata>, onOpen: (CoreVideoMetadata) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        videos.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { video ->
                    MomentCard(video, showSpot = false, modifier = Modifier.weight(1f).aspectRatio(2f / 3f)) { onOpen(video) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// MARK: - Giveaway

@Composable
internal fun ArtistGiveawayStrip(challenge: Challenge, onTap: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
            .clickable(onClick = onTap)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.ConfirmationNumber, contentDescription = null, tint = LandingColors.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "WIN ${challenge.prize.uppercase()}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text("Ends ${landingDay(challenge.deadline)}. Tap for the rules.", fontSize = 12.sp, color = colors.textSecondary)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun ArtistGiveawayCard(
    challenge: Challenge,
    artistName: String,
    songTitle: String,
    myStatus: ChallengeEntryStatus?,
    canEnter: Boolean,
    onEnter: () -> Unit,
    onSeeWinners: () -> Unit
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (challenge.isActive) "WIN" else "ENDED",
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp, color = LandingColors.accent
            )
            Text(challenge.prize, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black, color = colors.textPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            GiveawayStat("${challenge.entryCount}", "entered")
            GiveawayStat("${challenge.qualifierCount}", "qualified")
            GiveawayStat("${challenge.winnerCount}", if (challenge.winnerCount == 1) "winner" else "winners")
        }
        if (challenge.isActive) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (challenge.scope == ChallengeScope.FOLLOWERS) GiveawayStep("Follow $artistName")
                GiveawayStep("Drop a video on $songTitle")
                GiveawayStep("Get ${challenge.threshold} ${challenge.metric.displayName} to qualify")
                GiveawayStep("Winners are drawn at random on ${landingDay(challenge.deadline)}")
            }
            Text("Free to enter. One entry per person.", fontSize = 12.sp, color = colors.textSecondary)
        }
        if (myStatus != null) {
            Text(
                when (myStatus) {
                    ChallengeEntryStatus.ENTERED -> "You're entered. Keep sharing your video to qualify."
                    ChallengeEntryStatus.QUALIFIED -> "You qualified. You're in the draw."
                    ChallengeEntryStatus.WON -> "You won!"
                },
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LandingColors.accent
            )
        } else if (challenge.isActive && canEnter) {
            ArtistMetalButton("DROP YOUR VIDEO TO ENTER", onEnter)
        }
        if (challenge.state == ChallengeState.COMPLETED) {
            LandingButton("See the winners", onClick = onSeeWinners)
        } else if (!challenge.isActive) {
            Text("Drawing winners now.", fontSize = 13.sp, color = colors.textSecondary)
        }
    }
}

@Composable
private fun GiveawayStat(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Black, color = AppTheme.colors.textPrimary)
        Text(label, fontSize = 12.sp, color = AppTheme.colors.textSecondary)
    }
}

@Composable
private fun GiveawayStep(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = LandingColors.accent, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 14.sp, color = AppTheme.colors.textPrimary)
    }
}

/** The composer's form, on a video that's already up. Launching tells every follower, so it asks first. */
@Composable
internal fun ArtistStartGiveawaySheet(hook: CoreVideoMetadata, onStarted: suspend () -> Unit, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(ChallengeDraft()) }
    var confirming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Start a giveaway", fontSize = 22.sp, fontWeight = FontWeight.Black, color = colors.textPrimary)
            ChallengeConfigSection(draft = draft, onDraftChange = { draft = it })
            failure?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFCA5A5)) }
            if (!draft.isValid) {
                Text("Still needed: ${draft.missingFields.joinToString(", ")}", fontSize = 13.sp, color = colors.textSecondary)
            }
            LandingButton("Start giveaway", onClick = { confirming = true }, enabled = draft.isValid, busy = saving)
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Start this giveaway?") },
            text = { Text("All your followers get a notification. The prize and rules can't change after this.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    saving = true
                    scope.launch {
                        runCatching { ChallengeService.attachChallenge(hook.id, draft) }
                            .onSuccess {
                                onStarted()
                                onDismiss()
                            }
                            .onFailure { failure = "Couldn't start it. Try again." }
                        saving = false
                    }
                }) { Text("Start and notify followers") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}
