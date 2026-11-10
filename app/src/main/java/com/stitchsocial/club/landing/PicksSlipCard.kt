package com.stitchsocial.club.landing

import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A card of picks, live. Picks sit under their game's score and clock, and each
 * one has its number and a bar that fills as the stat comes in. Bars fill when
 * the card appears, numbers roll, and a pick pops when it settles.
 *
 * Numbers only: how many are right, never what anything pays.
 *
 * iOS parity with Landing/PicksSlipView.swift. The card is dark on both themes,
 * as iOS is, so its text colors are fixed here and not taken from AppTheme.
 *
 * Layer 5: Views.
 */

private object SlipColor {
    val right = Color(0xFF5BE3A6)
    val wrong = Color(0xFFFF8A7A)
    val close = Color(0xFFF5B84A)
    val live = Color(0xFF7CC4FF)
    val track = Color(0xFF1E2530)
    val card = Color(0xFF161B23)
    val line = Color(0xFF222A36)
    val ringTrack = Color(0xFF2A3240)
    val allRight = Color(0xFFE8C872)
    val onBadge = Color(0xFF0E1116)
    val text = Color(0xFFF4F4F8)
    val textMuted = Color(0xFFC9C9D6)
}

// SwiftUI's easing curves and springs, so the two apps move alike. A spring of
// response r is a stiffness of (2π / r)². The threshold is small because the
// values run 0 to 1, and the default would make a bar jump at the end.
private val easeOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val easeInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val barFirstSpring = spring(dampingRatio = 0.85f, stiffness = 48.7f, visibilityThreshold = 0.001f)
private val barChangeSpring = spring(dampingRatio = 0.8f, stiffness = 80.6f, visibilityThreshold = 0.001f)
private val revealSpring = spring(dampingRatio = 0.85f, stiffness = 195f, visibilityThreshold = 0.001f)
private val popSpring = spring(dampingRatio = 0.45f, stiffness = 438.6f, visibilityThreshold = 0.001f)

/** "Remove animations" (animator scale 0) is Android's Reduce Motion. */
@Composable
private fun rememberMotionOff(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private class SlipGroup(val key: String, val game: PicksGame?, val picks: List<PicksPick>)

/** Picks under their game, live games first, then upcoming, then finished. */
private fun slipGroups(card: PicksCard, night: PicksNight): List<SlipGroup> {
    val order = mapOf("live" to 0, "upcoming" to 1, "final" to 2)
    return card.picks.groupBy { it.gameID ?: "" }
        .map { (id, picks) -> SlipGroup(id, night.games.firstOrNull { it.id == id }, picks) }
        .sortedWith(compareBy({ order[it.game?.state ?: ""] ?: 3 }, { it.game?.id ?: "" }))
}

/**
 * A pick's id is new on every read, so rows are keyed on what the pick is. The
 * page polls, and a row has to outlive a poll for its bar to move from where it
 * was and for it to see its result change.
 */
private fun PicksPick.rowKey(): String = "$player|$statText|$side|$line"

@Composable
internal fun PicksSlipCard(card: PicksCard, night: PicksNight, modifier: Modifier = Modifier, title: String? = null) {
    val groups = slipGroups(card, night)
    val isLive = groups.any { it.game?.state == "live" } && card.open > 0
    val shape = RoundedCornerShape(22.dp)
    val context = LocalContext.current
    val locksAt = if (card.slate == "late") night.lateLocksAt else night.locksAt
    val summary = when {
        !night.isLocked(card.slate) -> "${card.picks.size} picks · Locks ${locksAt?.let { android.text.format.DateFormat.getTimeFormat(context).format(it) } ?: "after tip"}"
        card.open > 0 -> "${card.right} right · ${card.wrong} wrong · ${card.open} to go"
        else -> "${card.right} right · ${card.wrong} wrong"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlipColor.card, shape)
            .border(1.dp, if (isLive) SlipColor.wrong.copy(alpha = 0.35f) else SlipColor.line, shape)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title ?: if (card.isHost) "${card.username}'s ${card.slate} card" else "@${card.username}",
                    fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = SlipColor.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(summary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SlipColor.textMuted)
                if (isLive) SlipLiveBadge()
            }
            Spacer(Modifier.width(8.dp))
            SlipRing(card.right, card.total, finished = card.open == 0 && night.isLocked(card.slate))
        }

        groups.forEachIndexed { index, group ->
            key(group.key) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(SlipColor.line))
                group.game?.let { SlipGameHeader(it) }
                SlipGroupRows(group, index)
            }
        }

        card.swapsText?.let { text ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(SlipColor.line))
            Text(
                text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SlipColor.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

/** One game's picks, coming in a beat after the game above them. */
@Composable
private fun SlipGroupRows(group: SlipGroup, index: Int) {
    val motionOff = rememberMotionOff()
    val reveal = remember { Animatable(if (motionOff) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (motionOff) return@LaunchedEffect
        delay(index * 70L)
        reveal.animateTo(1f, revealSpring)
    }
    Column(
        Modifier.graphicsLayer {
            alpha = reveal.value.coerceIn(0f, 1f)
            translationY = (1f - reveal.value) * 10.dp.toPx()
        }
    ) {
        group.picks.forEach { pick ->
            key(pick.rowKey()) { SlipPickRow(pick, group.game) }
        }
    }
}

/** A number that rolls when it changes. */
@Composable
private fun SlipNumber(text: String, fontSize: TextUnit, color: Color) {
    val motionOff = rememberMotionOff()
    val lineHeight = fontSize * 1.2f
    if (motionOff) {
        Text(
            text, fontSize = fontSize, lineHeight = lineHeight, fontWeight = FontWeight.ExtraBold,
            color = color, softWrap = false
        )
        return
    }
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(tween(250)) { it } + fadeIn(tween(250))) togetherWith
                (slideOutVertically(tween(250)) { -it } + fadeOut(tween(250))) using
                SizeTransform(clip = false)
        },
        contentAlignment = Alignment.Center,
        label = "slipNumber"
    ) { shown ->
        Text(
            shown, fontSize = fontSize, lineHeight = lineHeight, fontWeight = FontWeight.ExtraBold,
            color = color, softWrap = false
        )
    }
}

/** A red dot that keeps pulsing while a game is on. */
@Composable
private fun SlipLiveBadge() {
    val motionOff = rememberMotionOff()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(contentAlignment = Alignment.Center) {
            val ring = Modifier.size(14.dp)
            if (motionOff) {
                Box(
                    ring
                        .graphicsLayer { scaleX = 0.6f; scaleY = 0.6f }
                        .background(SlipColor.wrong.copy(alpha = 0.4f), CircleShape)
                )
            } else {
                val pulse by rememberInfiniteTransition(label = "slipLive").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1400, easing = easeOut), RepeatMode.Restart),
                    label = "slipLivePulse"
                )
                Box(
                    ring
                        .graphicsLayer {
                            val scale = 0.6f + 0.7f * pulse
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - pulse
                        }
                        .background(SlipColor.wrong.copy(alpha = 0.4f), CircleShape)
                )
            }
            Box(Modifier.size(7.dp).background(SlipColor.wrong, CircleShape))
        }
        Text(
            "LIVE", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp, color = SlipColor.wrong
        )
    }
}

/** How many are right, in a ring that sweeps round as they land. */
@Composable
private fun SlipRing(right: Int, total: Int, finished: Boolean) {
    val motionOff = rememberMotionOff()
    val fraction = if (total > 0) right.toFloat() / total else 0f
    val color = if (finished && right == total && total > 0) SlipColor.allRight else SlipColor.right

    val shown = remember { Animatable(if (motionOff) fraction else 0f) }
    var hasSwept by remember { mutableStateOf(false) }
    LaunchedEffect(fraction) {
        if (motionOff) {
            shown.snapTo(fraction)
        } else {
            shown.animateTo(fraction, tween(if (hasSwept) 500 else 700, easing = easeInOut))
        }
        hasSwept = true
    }

    Box(
        modifier = Modifier
            .size(70.dp)
            .clearAndSetSemantics { contentDescription = "$right of $total right" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 6.dp.toPx()
            drawArc(SlipColor.ringTrack, 0f, 360f, useCenter = false, style = Stroke(width))
            val sweep = 360f * maxOf(0.02f, shown.value)
            // The glow needs a native paint: Compose's own drawArc can't cast a shadow.
            drawIntoCanvas { canvas ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = width
                    strokeCap = Paint.Cap.ROUND
                    this.color = color.toArgb()
                    setShadowLayer(6.dp.toPx(), 0f, 0f, color.copy(alpha = 0.5f).toArgb())
                }
                canvas.nativeCanvas.drawArc(RectF(0f, 0f, size.width, size.height), -90f, sweep, false, paint)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SlipNumber("$right/$total", 20.sp, SlipColor.text)
            Text(
                "RIGHT", fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold,
                color = SlipColor.textMuted, modifier = Modifier.offset(y = (-2).dp)
            )
        }
    }
}

@Composable
private fun SlipGameHeader(game: PicksGame) {
    val isLive = game.state == "live"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlipColor.track.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isLive) Box(Modifier.size(7.dp).background(SlipColor.wrong, CircleShape))
        SlipTeam(game.away, game.awayScore, game.hasStarted, leading = game.awayScore > game.homeScore)
        Text("@", fontSize = 11.sp, color = SlipColor.textMuted)
        SlipTeam(game.home, game.homeScore, game.hasStarted, leading = game.homeScore > game.awayScore)
        Spacer(Modifier.weight(1f))
        Text(
            game.clock, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
            color = if (isLive) SlipColor.wrong else SlipColor.textMuted
        )
    }
}

@Composable
private fun SlipTeam(code: String, score: Int, hasStarted: Boolean, leading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(code, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SlipColor.textMuted)
        if (hasStarted) SlipNumber(score.toString(), 15.sp, if (leading) SlipColor.text else SlipColor.textMuted)
    }
}

@Composable
private fun SlipPickRow(pick: PicksPick, game: PicksGame?) {
    val motionOff = rememberMotionOff()

    // How far the stat has come towards the number.
    val line = pick.line
    val actual = pick.actual
    val progress = if (line != null && line > 0 && actual != null) {
        minOf(1.0, actual / line).toFloat()
    } else {
        if (pick.result == "right") 1f else 0f
    }
    val color = when (pick.result) {
        "right" -> SlipColor.right
        "wrong" -> SlipColor.wrong
        "push", "void" -> SlipColor.textMuted
        null -> if (pick.listedOut) SlipColor.close else if (pick.side == "under" && progress >= 0.8f) SlipColor.close else SlipColor.live
        // A "less than" pick getting close to its number is the one to watch.
        else -> if (pick.side == "under" && progress >= 0.8f) SlipColor.close else SlipColor.live
    }
    val stateWord = when (pick.result) {
        "right" -> "RIGHT"
        "wrong" -> "WRONG"
        "push", "void" -> "OUT"
        null -> if (pick.listedOut) "Listed out" else if (game?.hasStarted == true) "LIVE" else "SOON"
        else -> if (game?.hasStarted == true) "LIVE" else "SOON"
    }

    val pop = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    var seenResult by remember { mutableStateOf(pick.result) }
    LaunchedEffect(pick.result) {
        if (pick.result == seenResult) return@LaunchedEffect
        seenResult = pick.result
        // A change cut short by another one can't leave the badge blown up.
        pop.snapTo(1f)
        flash.snapTo(0f)
        if (pick.result == null || motionOff) return@LaunchedEffect
        launch { pop.animateTo(1.45f, popSpring) }
        launch { flash.animateTo(0.22f, tween(250, easing = easeOut)) }
        delay(700)
        launch { pop.animateTo(1f, tween(500, easing = easeOut)) }
        flash.animateTo(0f, tween(500, easing = easeOut))
    }

    Column(
        modifier = Modifier
            // iOS has 16 around the row and pulls the flash 8 back out into it.
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .drawBehind {
                if (flash.value > 0f) {
                    drawRoundRect(
                        color.copy(alpha = flash.value.coerceIn(0f, 1f)),
                        cornerRadius = CornerRadius(10.dp.toPx())
                    )
                }
            }
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(
                    pick.player, pick.lineText, pick.stateText, if (pick.swappedIn) "Swapped in" else null
                ).joinToString(", ")
            },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SlipHeadshot(pick.athleteID, pick.player, pick.result, pop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    pick.player, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SlipColor.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    game?.let {
                        Text(
                            it.name, fontSize = 12.sp, color = SlipColor.textMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    if (pick.swappedIn) {
                        Text(
                            "Swapped in", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SlipColor.text,
                            maxLines = 1,
                            modifier = Modifier
                                .background(SlipColor.track, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            SlipLinePill(pick)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 34.dp)
                    .heightIn(min = 24.dp)
                    .background(SlipColor.track, CircleShape)
                    .border(1.5.dp, color.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                SlipNumber(actual?.let(::numberText) ?: "–", 16.sp, SlipColor.text)
            }
            SlipBar(progress, color, Modifier.weight(1f))
            Box(Modifier.widthIn(min = 46.dp), contentAlignment = Alignment.CenterEnd) {
                Text(stateWord, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = color)
            }
        }
    }
}

/** Fills from empty when it first shows, and from where it was after that. */
@Composable
private fun SlipBar(progress: Float, color: Color, modifier: Modifier) {
    val motionOff = rememberMotionOff()
    val shown = remember { Animatable(if (motionOff) progress else 0f) }
    var hasFilled by remember { mutableStateOf(false) }
    LaunchedEffect(progress) {
        if (motionOff) {
            shown.snapTo(progress)
        } else {
            shown.animateTo(progress, if (hasFilled) barChangeSpring else barFirstSpring)
        }
        hasFilled = true
    }
    Canvas(modifier.height(8.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(SlipColor.track, cornerRadius = radius)
        // A spring runs a little past its value, and the bar stays on its track.
        val width = size.width * shown.value.coerceIn(0f, 1f)
        if (width > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color), endX = width),
                size = Size(width, size.height),
                cornerRadius = radius
            )
        }
    }
}

@Composable
private fun SlipLinePill(pick: PicksPick) {
    Row(
        modifier = Modifier
            // A misread stat gets cut short, and can't push the player's name off.
            .widthIn(max = 180.dp)
            .background(SlipColor.track, RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            if (pick.side == "under") Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
            contentDescription = null, tint = SlipColor.text, modifier = Modifier.size(13.dp)
        )
        Text(
            pick.line?.let(::numberText) ?: "?",
            fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = SlipColor.text
        )
        Text(
            pick.statText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SlipColor.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The player's picture, or their initials if there isn't one. A settled pick gets
 * a check or a cross that pops in.
 */
@Composable
private fun SlipHeadshot(athleteID: String?, name: String, result: String?, pop: Animatable<Float, *>) {
    val motionOff = rememberMotionOff()
    val initials = name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }
    val settled = result == "right" || result == "wrong"

    Box(Modifier.size(44.dp), contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier.fillMaxSize().clip(CircleShape).background(SlipColor.track),
            contentAlignment = Alignment.Center
        ) {
            Text(initials, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SlipColor.textMuted)
            if (athleteID != null) {
                AsyncImage(
                    model = "https://a.espncdn.com/i/headshots/nba/players/full/$athleteID.png",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        AnimatedVisibility(
            visible = settled,
            enter = if (motionOff) EnterTransition.None else scaleIn(tween(250)) + fadeIn(tween(250)),
            exit = ExitTransition.None
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    }
                    .background(if (result == "right") SlipColor.right else SlipColor.wrong, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (result == "right") Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null, tint = SlipColor.onBadge, modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
