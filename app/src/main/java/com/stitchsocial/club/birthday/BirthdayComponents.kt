package com.stitchsocial.club.birthday

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.stitchsocial.club.MainActivity
import com.stitchsocial.club.services.TeenSafety
import com.stitchsocial.club.views.fixedSp
import java.util.Date
import kotlin.math.sin

/*
 * BirthdayComponents.kt — the small birthday pieces other screens use.
 * iOS parity with Birthday/BirthdayComponents.swift.
 *
 *  - BirthdayConfetti: the looping confetti behind the sheet header and the
 *    Rendering screen. One Canvas however many pieces fall, and it holds
 *    still when animations are turned off system-wide.
 *  - BirthdayRing: the 🎂 ring on an avatar while users/{id}.birthdayUntil is
 *    in the future. Reads only what the avatar's user doc already carried —
 *    no read of its own.
 *  - BirthdayProfileBanner: the compact "🎂 Your birthday is Friday" row on
 *    your own profile, from the cached status.
 *  - BirthdayCardThumb: a rounded 9:16 tile for a card or a style still.
 *  - BirthdayLoopingVideo: a card on loop with sound — a 10-second card
 *    doesn't need the feed's player stack.
 *
 * Layer 8: Views
 */

// MARK: - Confetti

private class ConfettiPiece(
    val x: Float,        // 0..1 across
    val offset: Float,   // 0..1 phase in the fall
    val speed: Float,    // multiplier on fallDuration
    val size: Float,     // dp
    val spin: Float,
    val sway: Float,     // dp
    val colorIndex: Int,
    val isDot: Boolean
)

/** Deterministic, so the field doesn't reshuffle on every recomposition. Same seed as iOS. */
private fun confettiPieces(count: Int): List<ConfettiPiece> {
    var seed: ULong = 0x5EEDB1D7UL
    fun next(): Float {
        seed = seed * 6364136223846793005UL + 1442695040888963407UL
        return ((seed shr 33).toDouble() / (UInt.MAX_VALUE shr 1).toDouble()).toFloat()
    }
    val colors = BirthdayPalette.confettiColors.size
    return List(count) {
        ConfettiPiece(
            x = next(), offset = next(), speed = 0.7f + next() * 0.6f,
            size = 5f + next() * 6f, spin = (next() - 0.5f) * 6f,
            sway = 6f + next() * 14f,
            colorIndex = (next() * colors).toInt(),
            isDot = next() > 0.7f
        )
    }
}

/** "Remove animations" (animator scale 0) is Android's Reduce Motion. */
private fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun BirthdayConfetti(
    modifier: Modifier = Modifier,
    count: Int = 46,
    /** Seconds for one piece to cross the whole height. */
    fallDuration: Double = 5.5
) {
    val context = LocalContext.current
    val still = remember { animationsOff(context) }
    val pieces = remember(count) { confettiPieces(count) }
    var t by remember { mutableDoubleStateOf(1.3) }

    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> t = 1.3 + (now - start) / 1_000_000_000.0 }
        }
    }

    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        val time = t
        val colors = BirthdayPalette.confettiColors
        val pad = 20.dp.toPx()
        for (p in pieces) {
            val progress = ((time / (fallDuration * p.speed) + p.offset) % 1.0).toFloat()
            val y = -pad + progress * (size.height + pad * 2)
            val x = p.x * size.width + (sin(time * 1.3 + p.offset * 12) * p.sway.dp.toPx()).toFloat()
            val color = colors[p.colorIndex.coerceAtMost(colors.size - 1)].copy(alpha = 0.85f)
            val s = p.size.dp.toPx()
            val degrees = Math.toDegrees(time * p.spin + p.offset * 6).toFloat()
            translate(x, y) {
                rotate(degrees, pivot = Offset.Zero) {
                    if (p.isDot) {
                        drawCircle(color, radius = s / 2, center = Offset.Zero)
                    } else {
                        drawRoundRect(
                            color,
                            topLeft = Offset(-s / 2, -s / 4),
                            size = Size(s, s / 2),
                            cornerRadius = CornerRadius(1.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Avatar ring

/**
 * The 🎂 ring while `until` is in the future. `diameter` is the avatar's own
 * size; the ring sits just outside it, drawn past this box's bounds so the
 * avatar's layout doesn't move.
 *
 * Checked at composition. A ring that outlives the day by a few minutes until
 * the next recomposition is fine; a timer per avatar in a feed is not.
 *
 * @param onTap set where the ring opens the birthday post, like a story ring
 *   (the profile header, when users/{id}.birthdayVideoID is there). Null keeps
 *   it visual only — feed avatars still open the profile.
 */
@Composable
fun BirthdayRing(
    until: Date?,
    diameter: Dp,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val active = !TeenSafety.viewerIsTeen && until != null && until.after(Date())
    if (!active) {
        Box(modifier) { content() }
        return
    }
    val ringWidth = max(2.dp, diameter * 0.05f)
    val badge = max(14.dp, diameter * 0.34f)
    val tappable = if (onTap != null) {
        Modifier.clickable(onClickLabel = "Open birthday post", role = Role.Button, onClick = onTap)
    } else Modifier
    Box(
        modifier = modifier.then(tappable).drawWithContent {
            drawContent()
            val w = ringWidth.toPx()
            val outer = (diameter + ringWidth * 3).toPx()
            drawCircle(
                brush = Brush.sweepGradient(BirthdayPalette.ringColors, center),
                radius = outer / 2 - w / 2,
                center = center,
                style = Stroke(w)
            )
        },
        contentAlignment = Alignment.Center
    ) {
        content()
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = badge * 0.15f, y = -badge * 0.15f)
                .size(badge)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.75f))
                .border(1.dp, BirthdayPalette.pink, CircleShape)
                .semantics { contentDescription = "Birthday today" },
            contentAlignment = Alignment.Center
        ) {
            Text("🎂", fontSize = (badge.value * 0.5f).fixedSp())
        }
    }
}

// MARK: - Profile banner

/** Your own profile, while the cached status has something to show. */
@Composable
fun BirthdayProfileBanner(modifier: Modifier = Modifier) {
    val status = BirthdayCenter.status
    if (TeenSafety.viewerIsTeen || status == null || status.phase == null || status.copy.headline.isEmpty()) return
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(BirthdayPalette.magenta.copy(alpha = 0.9f), BirthdayPalette.violet.copy(alpha = 0.85f))
                )
            )
            .clickable(onClickLabel = "Opens your birthday card", role = Role.Button) { BirthdayCenter.present() }
    ) {
        BirthdayConfetti(Modifier.matchParentSize().alpha(0.5f), count = 14, fallDuration = 7.0)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                status.copy.headline,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ChevronRight, null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// MARK: - 9:16 tile

/**
 * A card thumbnail or a style still, 9:16 with rounded corners. A tinted
 * placeholder (with a gentle shimmer) until the image arrives.
 *
 * Sized by the caller: a fixed height gives the width, a fixed width (a grid
 * cell) gives the height.
 */
@Composable
fun BirthdayCardThumb(
    url: String?,
    modifier: Modifier = Modifier,
    tint: Color = BirthdayPalette.pink,
    label: String? = null,
    isSelected: Boolean = false,
    cornerRadius: Dp = 14.dp
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.45f), BirthdayPalette.cardFill)))
            .border(
                if (isSelected) 3.dp else 1.dp,
                if (isSelected) BirthdayPalette.pink else Color.White.copy(alpha = 0.1f),
                shape
            )
    ) {
        BirthdayShimmer(Modifier.matchParentSize())
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
        if (label != null) {
            Text(
                label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 6f)
                ),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            )
        }
        if (isSelected) {
            // White check on a pink disc: the glyph's cut-out shows the white dot under it.
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(13.dp).clip(CircleShape).background(Color.White))
                Icon(Icons.Default.CheckCircle, null, tint = BirthdayPalette.pink, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun BirthdayShimmer(modifier: Modifier) {
    val context = LocalContext.current
    if (remember { animationsOff(context) }) return
    val phase by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerPhase"
    )
    BoxWithConstraints(modifier) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(maxWidth * 0.6f)
                .offset(x = maxWidth * phase * 1.4f)
                .background(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.08f), Color.Transparent))
                )
        )
    }
}

// MARK: - Buttons

/** The sheet's filled magenta button. */
@Composable
fun BirthdayPrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = BirthdayPalette.magenta.copy(alpha = 0.45f),
                spotColor = BirthdayPalette.magenta.copy(alpha = 0.45f)
            )
            .clip(shape)
            .background(BirthdayPalette.magenta)
            .clickable(enabled = !isBusy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isBusy) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The outlined companion to the primary button. */
@Composable
fun BirthdaySecondaryButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// MARK: - Playback

/**
 * The card on loop, with sound, aspect-filled. Its own small ExoPlayer —
 * released when it leaves.
 */
@Composable
fun BirthdayLoopingVideo(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 1f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose {
            player.stop()
            player.release()
        }
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

/** Feed players stop, so two soundtracks never overlap (iOS pauseAllPlayers). */
internal fun pauseFeedPlayback(context: Context) {
    runCatching {
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(MainActivity.ACTION_PAUSE_ALL_VIDEOS))
    }
}

internal fun View.birthdaySuccessHaptic() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY
    )
}

internal fun View.birthdaySelectionHaptic() {
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

/** "JG" for the avatar fallback; "S" when there's nothing to go on. */
internal fun birthdayInitials(displayName: String?, username: String?): String {
    val source = displayName?.takeIf { it.isNotBlank() } ?: username.orEmpty()
    val letters = source.split(" ").filter { it.isNotEmpty() }.take(2).map { it.first() }.joinToString("")
    return letters.ifEmpty { "S" }.uppercase()
}
