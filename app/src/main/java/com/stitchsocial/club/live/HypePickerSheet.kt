package com.stitchsocial.club.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.foundation.CoinError
import kotlinx.coroutines.launch

/**
 * Hype picker — viewer taps a hype to send coins to the creator. Mirrors
 * the iOS GiftTrayView grid: 2 columns × 3 rows, each cell shows the emoji,
 * display name, coin cost, and XP multiplier badge.
 *
 * Tap → fire send + close. Sends are async; the sheet shows a brief loading
 * state and surfaces "Insufficient coins" inline so the viewer can buy more.
 */
@Composable
fun HypePickerSheet(
    streamID: String,
    communityID: String,
    senderID: String,
    senderUsername: String,
    senderLevel: Int,
    onDismiss: () -> Unit,
    onInsufficientCoins: () -> Unit = {},
) {
    val coinService = remember { StreamCoinService.getInstance() }
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFF12141C))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Send a hype",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        "70% of the coin cost goes to the creator. Higher hypes give XP multipliers.",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable(enabled = !sending) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // Error banner
            errorText?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Red.copy(alpha = 0.15f))
                        .border(0.5.dp, Color.Red.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Text(msg, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
            }

            // Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 380.dp),
            ) {
                items(StreamHypeType.entries) { hype ->
                    HypeCard(
                        hype = hype,
                        enabled = !sending,
                        onTap = {
                            errorText = null
                            sending = true
                            scope.launch {
                                runCatching {
                                    coinService.sendHype(
                                        hypeType = hype,
                                        streamID = streamID,
                                        communityID = communityID,
                                        senderID = senderID,
                                        senderUsername = senderUsername,
                                        senderLevel = senderLevel,
                                    )
                                }.onSuccess {
                                    sending = false
                                    onDismiss()
                                }.onFailure { err ->
                                    sending = false
                                    when (err) {
                                        is CoinError.InsufficientBalance -> {
                                            errorText = "Not enough coins. Tap your balance to buy more."
                                            onInsufficientCoins()
                                        }
                                        else -> {
                                            errorText = err.localizedMessage
                                                ?: "Couldn't send hype. Try again."
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }

            if (sending) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        color = Color.Yellow,
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Sending…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun HypeCard(
    hype: StreamHypeType,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val tint = when (hype) {
        StreamHypeType.SUPER_HYPE -> Color(0xFFFF4F8B)
        StreamHypeType.MEGA_HYPE -> Color(0xFFFFB300)
        StreamHypeType.ULTRA_HYPE -> Color(0xFF00D4FF)
        StreamHypeType.GIFT_SUB -> Color(0xFF9D5BFF)
        StreamHypeType.SPOTLIGHT -> Color(0xFF34D399)
        StreamHypeType.BOOST_STREAM -> Color(0xFFEC4899)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(0.5.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onTap() }
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(hype.emoji, fontSize = 30.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            hype.displayName,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "🪙 ${hype.coinCost}",
                color = Color.Yellow,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            if (hype.xpMultiplier > 1) {
                Text(
                    "× ${hype.xpMultiplier} XP",
                    color = tint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .background(tint.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}
