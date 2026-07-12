/*
 * GiveawayResultsView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views — Challenge/Giveaway results bottom sheet (iOS parity).
 * Opened by tapping the ContestHUD on a challenge thread head (ThreadView).
 *
 * States:
 *   active    → big live countdown + entries/qualified/winners stat row + rules
 *               + "provably fair" entry hint
 *   drawing   → progress + "draw runs within 15 minutes"
 *   completed → winner cards (videos where challengeThreadID == headId AND
 *               challengeStatus == "won") + stat row + seed footer; or a
 *               "No entries this time" empty state.
 */
package com.stitchsocial.club.challenge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val SheetBg = Color(0xFF14141F)
private val Gold = Color(0xFFFFD700)
private val Cyan = Color(0xFF00D9F2)

/**
 * Giveaway results bottom sheet — dark, iOS parity. The server owns state
 * transitions; this sheet only renders the head video's `challenge` map plus
 * (when completed) the winner entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiveawayResultsView(
    headVideoID: String,
    challenge: Challenge,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: trophy + prize
            Icon(
                Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = Gold,
                modifier = Modifier.size(36.dp)
            )
            Text(
                challenge.prize,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                "#${challenge.hashtag}",
                color = Cyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            when (challenge.state) {
                ChallengeState.ACTIVE -> ActiveContent(challenge)
                ChallengeState.DRAWING -> DrawingContent()
                ChallengeState.COMPLETED -> CompletedContent(headVideoID, challenge)
            }
        }
    }
}

// ===== ACTIVE =====

@Composable
private fun ActiveContent(challenge: Challenge) {
    // Big live countdown — 1s tick to the deadline.
    var remainingMs by remember(challenge.deadline) { mutableStateOf(challenge.timeRemainingMs) }
    LaunchedEffect(challenge.deadline) {
        while (true) {
            remainingMs = challenge.timeRemainingMs
            delay(1000)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Timer,
                contentDescription = null,
                tint = Cyan,
                modifier = Modifier.size(22.dp)
            )
            Text(
                if (remainingMs <= 0) "Ended" else formatBigCountdown(remainingMs),
                color = Cyan,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "until the draw",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }

    StatRow(challenge)

    // Rules
    Text(
        challenge.ruleSummary,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 14.sp,
        textAlign = TextAlign.Center
    )

    Text(
        "Enter by replying to this video. Winners drawn at the deadline with a published seed — provably fair.",
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        lineHeight = 17.sp
    )
}

// ===== DRAWING =====

@Composable
private fun DrawingContent() {
    Spacer(Modifier.height(8.dp))
    CircularProgressIndicator(color = Gold)
    Text(
        "Drawing winners…",
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        "The deadline has passed — the draw runs within 15 minutes.",
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
        textAlign = TextAlign.Center
    )
}

// ===== COMPLETED =====

@Composable
private fun CompletedContent(headVideoID: String, challenge: Challenge) {
    var winners by remember(headVideoID) { mutableStateOf<List<ChallengeWinner>?>(null) }
    LaunchedEffect(headVideoID) {
        winners = ChallengeService.fetchWinners(headVideoID)
    }

    when (val w = winners) {
        null -> {
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(color = Gold)
        }
        else -> {
            if (w.isEmpty()) {
                // No winners — nobody entered / qualified.
                Text(
                    "No entries this time",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Nobody made it to the draw. A lower qualification bar usually pulls more entries next time.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
            } else {
                Text(
                    if (w.size == 1) "Winner" else "Winners",
                    color = Gold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    w.forEach { winner -> WinnerCard(winner) }
                }
            }

            StatRow(challenge)

            // Provably-fair footer
            challenge.drawSeed?.let { seed ->
                Text(
                    "Provably fair draw · seed $seed",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Gold-tinted winner card: thumbnail + @creatorName + trophy. */
@Composable
private fun WinnerCard(winner: ChallengeWinner) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Gold.copy(alpha = 0.12f))
            .border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 9:16 thumbnail
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 66.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.4f))
        ) {
            if (winner.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = winner.thumbnailURL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Text(
            "@${winner.creatorName}",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Text("🏆", fontSize = 20.sp)
    }
}

// ===== SHARED =====

/** Entries / qualified / winners stat row. */
@Composable
private fun StatRow(challenge: Challenge) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        SheetStat(count = challenge.entryCount, label = "entries")
        SheetStat(count = challenge.qualifierCount, label = "qualified")
        SheetStat(count = challenge.winnerCount, label = if (challenge.winnerCount == 1) "winner" else "winners")
    }
}

@Composable
private fun SheetStat(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$count",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp
        )
    }
}

/** Big-countdown format: "3d 4h 5m" / "4h 5m 6s" / "5m 6s". */
private fun formatBigCountdown(ms: Long): String {
    val totalSec = ms / 1000
    val days = totalSec / 86400
    val hours = (totalSec % 86400) / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return when {
        days > 0 -> "${days}d ${hours}h ${mins}m"
        hours > 0 -> "${hours}h ${mins}m ${secs}s"
        else -> "${mins}m ${secs}s"
    }
}
