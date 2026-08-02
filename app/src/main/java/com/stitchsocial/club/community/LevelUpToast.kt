package com.stitchsocial.club.community

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Somewhere for a level-up to land (iOS parity with LevelUpToast.swift).
 *
 * CommunityXPService has always computed the level-up correctly and published it
 * to `lastLevelUp`. Nothing in the entire app read it. Same for
 * `lastBadgeUnlock`. So a member crossed a threshold, unlocked a real feature,
 * and the app said nothing at all — the whole progression loop was invisible at
 * the exact moment it should have paid off.
 *
 * This is the listener. It also names what the level actually UNLOCKED, because
 * a number going up is not a reward; the thing it opens is.
 */
@Composable
fun LevelUpToast(
    event: LevelUpEvent,
    onDismiss: () -> Unit
) {
    // LIVE perks only — see CommunityFeatureGate.isLive.
    val unlocked = remember(event.newLevel) {
        CommunityFeatureGate.live.firstOrNull { it.requiredLevel == event.newLevel }
    }

    LaunchedEffect(event.id) {
        // Long enough to read the unlock line, short enough not to sit on the
        // content the member just earned.
        delay(3500)
        onDismiss()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C1C))
            .clickable { onDismiss() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Color(0xFFE91E63), Color(0xFF9C27B0)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${event.newLevel}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(Modifier.width(12.dp))

        Column {
            Text(
                "Level ${event.newLevel}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                unlocked?.let { "Unlocked · ${it.displayName}" }
                    ?: "Level ${event.oldLevel} → ${event.newLevel}",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * Drop into any community surface to show level-ups as they happen.
 *
 * Takes the service rather than an event so the caller doesn't have to
 * remember to clear it — forgetting that is how a toast ends up showing once
 * and never again, or showing forever.
 */
@Composable
fun BoxScope.LevelUpToastHost(xpService: CommunityXPService) {
    val event by xpService.lastLevelUp.collectAsState()

    AnimatedVisibility(
        visible = event != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        event?.let {
            LevelUpToast(event = it) { xpService.clearLastLevelUp() }
        }
    }
}
