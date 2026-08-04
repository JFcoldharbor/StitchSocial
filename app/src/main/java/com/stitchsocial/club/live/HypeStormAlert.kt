package com.stitchsocial.club.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Transient banner that slides in from the left when a hype fires. Mirrors
 * iOS `HypeStormOverlay` — shows the sender + the hype emoji/name. Auto-
 * dismisses after [displayDurationMs] ms.
 *
 * The viewer + creator screens both render this overlay; the
 * `StreamCoinService.lastHypeAlert` StateFlow is shared, so both surfaces
 * stay in sync.
 */
@Composable
fun HypeStormAlert(
    event: HypeAlertMirror?,
    modifier: Modifier = Modifier,
    displayDurationMs: Long = 3200L,
    onTimeout: () -> Unit = {},
) {
    var visibleEvent by remember { mutableStateOf<HypeAlertMirror?>(null) }

    LaunchedEffect(event?.alertID) {
        val incoming = event ?: return@LaunchedEffect
        visibleEvent = incoming
        delay(displayDurationMs)
        visibleEvent = null
        onTimeout()
    }

    Box(
        modifier = modifier.fillMaxSize().padding(top = 80.dp, start = 12.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        AnimatedVisibility(
            visible = visibleEvent != null,
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
        ) {
            visibleEvent?.let { evt -> HypeAlertCard(evt) }
        }
    }
}

@Composable
private fun HypeAlertCard(event: HypeAlertMirror) {
    val tint = when (event.hypeType) {
        StreamHypeType.SUPER_HYPE -> Color(0xFFFF4F8B)
        StreamHypeType.MEGA_HYPE -> Color(0xFFFFB300)
        StreamHypeType.ULTRA_HYPE -> Color(0xFF00D4FF)
        StreamHypeType.GIFT_SUB -> Color(0xFF9D5BFF)
        StreamHypeType.SPOTLIGHT -> Color(0xFF34D399)
        StreamHypeType.BOOST_STREAM -> Color(0xFFEC4899)
        null -> Color(0xFFEC4899)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(tint.copy(alpha = 0.35f), tint.copy(alpha = 0.10f))
                )
            )
            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(event.hypeType?.emoji ?: "\uD83D\uDD25", fontSize = 22.sp)
        Column {
            Text(
                "@${event.senderUsername}",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "sent ${event.hypeType?.displayName ?: "Hype"}${if (event.coins > 0) " · ${event.coins}" else ""}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val mult = event.hypeType?.xpMultiplier ?: 1
                if (mult > 1) {
                    Text(
                        "×$mult XP",
                        color = tint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}
