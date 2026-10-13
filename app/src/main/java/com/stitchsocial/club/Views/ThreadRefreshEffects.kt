package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stitchsocial.club.services.ThreadRefreshBus

/**
 * One-shot refresh triggers for watching screens. No listeners, no timers:
 * the screen re-reads its thread once when
 *  - the viewer's own reply to it publishes ([ThreadRefreshBus]), and
 *  - the app comes back to the foreground while the screen is composed.
 * (Opening the screen is the initial load, which every caller already does.)
 */

/**
 * Runs [onResume] each time the host returns to the foreground — ON_RESUME
 * after the first one, which is just the screen appearing and is covered by
 * the screen's own initial load.
 */
@Composable
fun OnAppForeground(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onResume)
    DisposableEffect(lifecycleOwner) {
        // addObserver replays ON_RESUME at once when the host is already
        // resumed, so the first one is always the screen appearing.
        var sawFirst = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (sawFirst) latest() else sawFirst = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Runs [onReply] for every own-reply publish that [matches] this screen. */
@Composable
fun OnOwnReplyPublished(
    matches: (ThreadRefreshBus.OwnReplyPublished) -> Boolean,
    onReply: suspend (ThreadRefreshBus.OwnReplyPublished) -> Unit
) {
    val latestMatches by rememberUpdatedState(matches)
    val latestOnReply by rememberUpdatedState(onReply)
    LaunchedEffect(Unit) {
        ThreadRefreshBus.events.collect { event ->
            if (latestMatches(event)) {
                runCatching { latestOnReply(event) }
            }
        }
    }
}

/**
 * A counter that ticks once per refresh trigger — an own reply that [matches]
 * or a return to the foreground. Key a LaunchedEffect on it to re-read.
 */
@Composable
fun rememberThreadRefreshTick(
    matches: (ThreadRefreshBus.OwnReplyPublished) -> Boolean
): Int {
    val tick = remember { mutableIntStateOf(0) }
    OnOwnReplyPublished(matches = matches) { tick.intValue++ }
    OnAppForeground { tick.intValue++ }
    return tick.intValue
}

/** Small "Under review" label for the author's own reply while moderation runs. */
@Composable
fun UnderReviewChip(fontSize: TextUnit = 10.sp, modifier: Modifier = Modifier) {
    Text(
        text = "Under review",
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFFF8C00).copy(alpha = 0.75f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
