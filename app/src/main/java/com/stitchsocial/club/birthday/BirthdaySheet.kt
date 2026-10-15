package com.stitchsocial.club.birthday

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.services.TeenSafety
import com.stitchsocial.club.ui.theme.DismissX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * BirthdaySheet.kt — the Birthday sheet. iOS parity with Birthday/BirthdaySheet.swift.
 *
 * Presented by MainScreen (BirthdaySheetHost) from BirthdayCenter.isPresenting:
 * on launch/foreground when the server says autoPresent, from the profile
 * banner, and from a birthday_self_* notification.
 *
 * Driven by the server's phase:
 *  - choose: the six styles as a 3×2 grid of stills (their own photo, from
 *    birthday_preview), then Customize. A rendered-but-unapproved card shows
 *    above the grid as "Review your card". Not now / Skip this year.
 *  - set:    the approved card — Preview it, or Change (back to the grid,
 *    re-render, re-approve).
 *  - live:   wishes are coming in — See your wishes opens the thread.
 *  - thanks: after the day — the server's button opens the thread.
 *
 * The header is always copy.headline + copy.subline, verbatim. Rendering and
 * Preview & approve run in a full-screen dialog over the sheet (iOS
 * fullScreenCover). Both are their own windows, so the feed's SurfaceViews
 * can't draw through them the way they can through a Compose overlay.
 *
 * Layer 8: Views
 */

/**
 * Mounted once in MainScreen's signed-in branch. Nothing unless the center
 * says the sheet is up, and never in the teen lane.
 */
@Composable
fun BirthdaySheetHost(currentUser: BasicUserInfo) {
    if (!BirthdayCenter.isPresenting) return
    if (TeenSafety.viewerIsTeen) {
        // Never shown to a teen; close if anything got this far.
        LaunchedEffect(Unit) { BirthdayCenter.isPresenting = false }
        return
    }
    val context = LocalContext.current
    // MainScreen also stops Discovery while this is up (see TabContent); this
    // catches Home and anything that started a player on its own.
    LaunchedEffect(Unit) { pauseFeedPlayback(context) }
    BirthdaySheet(user = currentUser, onClose = { BirthdayCenter.isPresenting = false })
}

private sealed class SheetStep {
    object Overview : SheetStep()
    /** The grid again, from "Change" on a card that's already set. */
    object Styles : SheetStep()
    data class Customize(val style: BirthdayStyle) : SheetStep()
}

private sealed class SheetCover {
    data class Render(val request: BirthdayRenderRequest) : SheetCover()
    data class Review(val videoURL: String, val notify: BirthdayNotify) : SheetCover()
    data class Watch(val videoURL: String) : SheetCover()
}

@Composable
private fun BirthdaySheet(user: BasicUserInfo, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val status = BirthdayCenter.status

    var step by remember { mutableStateOf<SheetStep>(SheetStep.Overview) }
    var selectedStyle by remember { mutableStateOf(status?.card?.style ?: BirthdayStyle.CONFETTI) }
    var preview by remember { mutableStateOf<BirthdayPreview?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf<SheetCover?>(null) }
    var confirmingSkip by remember { mutableStateOf(false) }
    var busyAction by remember { mutableStateOf<BirthdayService.DismissMode?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoadingStatus by remember { mutableStateOf(BirthdayCenter.status == null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onClose() }
    }

    val wantsPreview = step != SheetStep.Overview || status?.phase == BirthdayPhase.CHOOSE

    LaunchedEffect(Unit) {
        if (BirthdayCenter.status != null) return@LaunchedEffect
        isLoadingStatus = true
        BirthdayCenter.refresh(user.id, isTeen = TeenSafety.viewerIsTeen)
        isLoadingStatus = false
        BirthdayCenter.status?.card?.style?.let { selectedStyle = it }
    }

    LaunchedEffect(wantsPreview) {
        if (!wantsPreview || preview != null) return@LaunchedEffect
        try {
            preview = BirthdayService.preview()
            previewFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            previewFailed = true
        }
    }

    fun goToCustomize() { step = SheetStep.Customize(selectedStyle) }

    fun backToStyles() {
        cover = null
        step = if (BirthdayCenter.status?.phase == BirthdayPhase.CHOOSE) SheetStep.Overview else SheetStep.Styles
    }

    /** Close the cover, then the sheet. */
    fun finish() {
        cover = null
        step = SheetStep.Overview
        close()
    }

    fun dismissOffer(mode: BirthdayService.DismissMode) {
        busyAction = mode
        scope.launch {
            try {
                BirthdayService.dismiss(mode)
                BirthdayCenter.refreshAfterAction()
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = BirthdayService.message(e)
            }
            busyAction = null
        }
    }

    val isCustomizing = step is SheetStep.Customize
    // Back on Customize goes back to the styles rather than closing everything.
    BackHandler(enabled = isCustomizing) { backToStyles() }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = Color.Black,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !isCustomizing)
    ) {
        AnimatedContent(
            targetState = step,
            // Only Customize slides; Overview ↔ Styles is the same screen.
            contentKey = { it is SheetStep.Customize },
            transitionSpec = {
                if (targetState is SheetStep.Customize) {
                    slideInHorizontally(tween(250)) { it } togetherWith slideOutHorizontally(tween(250)) { -it }
                } else {
                    slideInHorizontally(tween(250)) { -it } togetherWith slideOutHorizontally(tween(250)) { it }
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "birthdayStep"
        ) { current ->
            if (current is SheetStep.Customize) {
                BirthdayCustomizeView(
                    style = current.style,
                    preview = preview,
                    profileImageURL = user.profileImageURL,
                    initials = birthdayInitials(user.displayName, user.username),
                    initialNotify = status?.card?.notify ?: BirthdayNotify.ALL,
                    onBack = { backToStyles() },
                    onMake = { request -> cover = SheetCover.Render(request) }
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        SheetHeader(status = status, onClose = { close() })
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(22.dp)
                        ) {
                            when {
                                status != null -> PhaseBody(
                                    status = status,
                                    step = step,
                                    preview = preview,
                                    previewFailed = previewFailed,
                                    selectedStyle = selectedStyle,
                                    onSelectStyle = {
                                        selectedStyle = it
                                        view.birthdaySelectionHaptic()
                                    },
                                    onCover = { cover = it }
                                )
                                isLoadingStatus -> Box(
                                    Modifier.fillMaxWidth().padding(top = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                                }
                                else -> Text(
                                    "Couldn't load your birthday card. Check your connection and try again.",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                    if (status != null) {
                        SheetFooter(
                            status = status,
                            step = step,
                            selectedStyle = selectedStyle,
                            busyAction = busyAction,
                            onCustomize = { goToCustomize() },
                            onLater = { dismissOffer(BirthdayService.DismissMode.LATER) },
                            onSkipYear = { confirmingSkip = true },
                            onPreview = { url -> cover = SheetCover.Watch(url) },
                            onChange = { step = SheetStep.Styles },
                            onClose = { close() }
                        )
                    }
                }
            }
        }

        cover?.let { c ->
            Dialog(
                onDismissRequest = { },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                when (c) {
                    is SheetCover.Render -> BirthdayRenderView(
                        request = c.request,
                        onChange = { backToStyles() },
                        onFinished = { finish() }
                    )
                    is SheetCover.Review -> BirthdayApproveView(
                        videoURL = c.videoURL,
                        notify = c.notify,
                        onChange = { backToStyles() },
                        onFinished = { finish() }
                    )
                    is SheetCover.Watch -> BirthdayPreviewPlayer(
                        videoURL = c.videoURL,
                        onClose = { cover = null }
                    )
                }
            }
        }

        if (confirmingSkip) {
            AlertDialog(
                onDismissRequest = { confirmingSkip = false },
                title = { Text("Skip your card this year?") },
                text = { Text("Nothing will post, and we won't ask again until next year.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingSkip = false
                        dismissOffer(BirthdayService.DismissMode.YEAR)
                    }) { Text("Skip this year", color = Color(0xFFFF453A)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingSkip = false }) { Text("Keep it") }
                }
            )
        }

        errorMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { errorMessage = null },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } }
            )
        }
    }
}

// MARK: - Header

@Composable
private fun SheetHeader(status: BirthdayStatus?, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        BirthdayConfetti(
            count = 34,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                // Fades out toward the bottom so the pieces fall into the body.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn
                    )
                }
        )
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DismissX(onClick = onClose, modifier = Modifier.padding(top = 18.dp))
            Text(
                status?.copy?.headline?.takeIf { it.isNotEmpty() } ?: "🎂",
                style = TextStyle(
                    brush = Brush.horizontalGradient(listOf(Color.White, BirthdayPalette.pink.copy(alpha = 0.95f))),
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.8).sp
                ),
                modifier = Modifier.semantics { heading() }
            )
            val subline = status?.copy?.subline.orEmpty()
            if (subline.isNotEmpty()) {
                Text(subline, color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp, lineHeight = 22.sp)
            }
        }
    }
}

// MARK: - Body

@Composable
private fun PhaseBody(
    status: BirthdayStatus,
    step: SheetStep,
    preview: BirthdayPreview?,
    previewFailed: Boolean,
    selectedStyle: BirthdayStyle,
    onSelectStyle: (BirthdayStyle) -> Unit,
    onCover: (SheetCover) -> Unit
) {
    when {
        step == SheetStep.Styles || status.phase == BirthdayPhase.CHOOSE -> {
            val url = status.card.videoURL
            if (status.phase == BirthdayPhase.CHOOSE && status.card.status == BirthdayCard.Status.READY && url != null) {
                ReviewRow(status.card) { onCover(SheetCover.Review(url, status.card.notify)) }
            }
            StyleGrid(status.styles, preview, previewFailed, selectedStyle, onSelectStyle)
        }
        status.phase == BirthdayPhase.SET -> CardHero(status.card, onCover)
        status.phase == BirthdayPhase.LIVE || status.phase == BirthdayPhase.THANKS -> {
            CardHero(status.card, onCover)
            if (status.phase == BirthdayPhase.LIVE && status.wishCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = BirthdayPalette.gold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${status.wishCount} ${if (status.wishCount == 1) "wish" else "wishes"} so far",
                        color = BirthdayPalette.gold, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        else -> Text(
            "There's nothing to set up right now. We'll let you know the week before your birthday.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 15.sp
        )
    }
}

@Composable
private fun StyleGrid(
    styles: List<BirthdayStyle>,
    preview: BirthdayPreview?,
    previewFailed: Boolean,
    selected: BirthdayStyle,
    onSelect: (BirthdayStyle) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("PICK A STYLE")
        styles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { style ->
                    val isSelected = style == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { onSelect(style) }
                            .semantics {
                                contentDescription = style.displayName
                                this.selected = isSelected
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BirthdayCardThumb(
                            url = preview?.previews?.get(style),
                            tint = style.placeholderTint,
                            isSelected = isSelected,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            style.displayName,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
                // Keeps a short last row's tiles the same width as the rest.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(2.dp))
        }
        if (previewFailed) {
            Text(
                "Previews didn't load, but every style still works.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }
    }
}

/** A rendered card that was never approved. */
@Composable
private fun ReviewRow(card: BirthdayCard, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BirthdayPalette.cardFill)
            .border(1.dp, BirthdayPalette.pink.copy(alpha = 0.35f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BirthdayCardThumb(
            url = card.thumbnailURL,
            tint = card.style?.placeholderTint ?: BirthdayPalette.pink,
            cornerRadius = 10.dp,
            modifier = Modifier.height(86.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Review your card", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                card.style?.let { "${it.displayName} · ready to approve" } ?: "Ready to approve",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 13.sp
            )
        }
        // White play on a magenta disc: the glyph's cut-out shows the white dot under it.
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White))
            Icon(Icons.Default.PlayCircle, null, tint = BirthdayPalette.magenta, modifier = Modifier.size(30.dp))
        }
    }
}

// MARK: - Card hero (set / live / thanks)

@Composable
private fun CardHero(card: BirthdayCard, onCover: (SheetCover) -> Unit) {
    val url = card.videoURL
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .height(340.dp)
                .shadow(24.dp, RoundedCornerShape(22.dp), spotColor = BirthdayPalette.magenta.copy(alpha = 0.35f))
                .clip(RoundedCornerShape(22.dp))
                .clickable(enabled = url != null, role = Role.Button, onClickLabel = "Play") {
                    if (url != null) onCover(SheetCover.Watch(url))
                }
                .semantics { contentDescription = "Your birthday card" },
            contentAlignment = Alignment.Center
        ) {
            BirthdayCardThumb(
                url = card.thumbnailURL,
                tint = card.style?.placeholderTint ?: BirthdayPalette.pink,
                cornerRadius = 22.dp,
                modifier = Modifier.height(340.dp)
            )
            if (url != null) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}

// MARK: - Footer

@Composable
private fun SheetFooter(
    status: BirthdayStatus,
    step: SheetStep,
    selectedStyle: BirthdayStyle,
    busyAction: BirthdayService.DismissMode?,
    onCustomize: () -> Unit,
    onLater: () -> Unit,
    onSkipYear: () -> Unit,
    onPreview: (String) -> Unit,
    onChange: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            step == SheetStep.Styles ->
                BirthdayPrimaryButton(title = "Use ${selectedStyle.displayName}", onClick = onCustomize)

            status.phase == BirthdayPhase.CHOOSE -> {
                BirthdayPrimaryButton(
                    title = status.copy.primary.ifEmpty { "Choose a style" },
                    onClick = onCustomize
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LinkButton(
                        title = status.copy.secondary ?: "Not now",
                        busy = busyAction == BirthdayService.DismissMode.LATER,
                        enabled = busyAction == null,
                        onClick = onLater
                    )
                    LinkButton(
                        title = "Skip this year",
                        busy = busyAction == BirthdayService.DismissMode.YEAR,
                        enabled = busyAction == null,
                        onClick = onSkipYear
                    )
                }
            }

            status.phase == BirthdayPhase.SET -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val url = status.card.videoURL
                BirthdaySecondaryButton(
                    title = "Preview",
                    icon = Icons.Default.PlayArrow,
                    enabled = url != null,
                    modifier = Modifier.weight(1f)
                ) { if (url != null) onPreview(url) }
                BirthdaySecondaryButton(
                    title = "Change",
                    icon = Icons.Default.Palette,
                    modifier = Modifier.weight(1f),
                    onClick = onChange
                )
            }

            status.phase == BirthdayPhase.LIVE ->
                BirthdayPrimaryButton(title = "See your wishes") { BirthdayCenter.openThread(status.card.videoID) }

            status.phase == BirthdayPhase.THANKS ->
                BirthdayPrimaryButton(title = status.copy.primary.ifEmpty { "See your wishes" }) {
                    BirthdayCenter.openThread(status.card.videoID)
                }

            else -> BirthdaySecondaryButton(title = "Close", onClick = onClose)
        }
    }
}

@Composable
private fun LinkButton(title: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title,
            color = Color.White.copy(alpha = if (busy) 0f else 0.65f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun SectionLabel(title: String) {
    Text(
        title,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}
