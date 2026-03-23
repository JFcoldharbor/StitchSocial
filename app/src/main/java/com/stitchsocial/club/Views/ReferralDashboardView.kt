/*
 * ReferralDashboardView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Ambassador Referral Dashboard
 * Mirrors: ReferralDashboardView (ReferralButton.swift iOS) — FULL PARITY
 * Dependencies: ReferralService, StitchColors
 *
 * CACHING: Stats loaded once on composition via hasFetchedOnce guard.
 * getUserReferralStats = 1 user doc read + 1 referrals query (max 10 docs).
 * Refreshes only after generateReferralLink (share action) — same as iOS.
 */

package com.stitchsocial.club.views

import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.services.ReferralInfo
import com.stitchsocial.club.services.ReferralService
import com.stitchsocial.club.services.ReferralStats
import com.stitchsocial.club.services.ReferralStatus
import com.stitchsocial.club.ui.theme.StitchColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ReferralDashboardView(
    userID: String,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val referralService = remember { ReferralService() }

    var stats by remember { mutableStateOf<ReferralStats?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isGeneratingLink by remember { mutableStateOf(false) }
    var hasFetchedOnce by remember { mutableStateOf(false) }
    var codeCopied by remember { mutableStateOf(false) }
    var showingError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Load once on appear — mirrors iOS .task { guard !hasFetchedOnce }
    LaunchedEffect(Unit) {
        if (!hasFetchedOnce) {
            isLoading = true
            try {
                stats = referralService.getUserReferralStats(userID)
            } catch (e: Exception) {
                println("⚠️ REFERRAL DASHBOARD: Failed to load stats — ${e.message}")
            } finally {
                isLoading = false
                hasFetchedOnce = true
            }
        }
    }

    fun handleShare() {
        scope.launch {
            isGeneratingLink = true
            try {
                val link = referralService.generateReferralLink(userID)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link.shareText)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share your invite code"))

                // Refresh stats after share — mirrors iOS handleShare()
                stats = referralService.getUserReferralStats(userID)
            } catch (e: Exception) {
                errorMessage = "Failed to generate link: ${e.message}"
                showingError = true
            } finally {
                isGeneratingLink = false
            }
        }
    }

    fun copyCode() {
        val code = stats?.referralCode ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Referral Code", code)
        clipboard.setPrimaryClip(clip)
        codeCopied = true
        scope.launch {
            kotlinx.coroutines.delay(2000)
            codeCopied = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Purple gradient accent — mirrors iOS RadialGradient overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF9C27B0).copy(alpha = 0.25f), Color.Transparent),
                        radius = 600f
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {

            // Nav bar
            ReferralNavBar(onDismiss = onDismiss)

            if (isLoading && !hasFetchedOnce) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF9C27B0), modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(4.dp))

                    // Code card
                    ReferralCodeCard(
                        stats = stats,
                        codeCopied = codeCopied,
                        onCopy = { copyCode() }
                    )

                    // Stats grid
                    ReferralStatsGrid(stats = stats)

                    // Rewards progress
                    RewardsProgressCard(stats = stats)

                    // How it works
                    HowItWorksCard()

                    // Recent referrals
                    val referrals = stats?.recentReferrals
                    if (!referrals.isNullOrEmpty()) {
                        RecentReferralsCard(referrals = referrals)
                    }

                    // Share CTA
                    ShareCTAButton(isGenerating = isGeneratingLink, onShare = { handleShare() })

                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        }
    }

    if (showingError) {
        AlertDialog(
            onDismissRequest = { showingError = false },
            title = { Text("Error", color = Color.White) },
            text = { Text(errorMessage, color = StitchColors.textSecondary) },
            confirmButton = { TextButton(onClick = { showingError = false }) { Text("OK", color = StitchColors.primary) } },
            containerColor = StitchColors.surface
        )
    }
}

// MARK: - Nav Bar

@Composable
private fun ReferralNavBar(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
        }

        Text("Ambassador", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Spacer(modifier = Modifier.size(36.dp))
    }
}

// MARK: - Code Card

@Composable
private fun ReferralCodeCard(
    stats: ReferralStats?,
    codeCopied: Boolean,
    onCopy: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(Color(0xFF9C27B0).copy(0.4f), StitchColors.primary.copy(0.2f), Color(0xFF9C27B0).copy(0.1f))
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "YOUR INVITE CODE",
                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = Color.Gray, letterSpacing = 2.sp
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stats?.referralCode ?: "—",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (codeCopied) Color.Green.copy(0.15f) else Color(0xFF9C27B0).copy(0.15f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onCopy() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (codeCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = if (codeCopied) Color.Green else Color(0xFF9C27B0),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (!stats?.referralCode.isNullOrBlank()) {
                Text(
                    "Friends enter this code when signing up",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = Color.Gray, textAlign = TextAlign.Center
                )
            }
        }
    }
}

// MARK: - Stats Grid

@Composable
private fun ReferralStatsGrid(stats: ReferralStats?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ReferralStatCard(
            value = "${stats?.totalReferrals ?: 0}",
            label = "Total Invites",
            icon = Icons.Default.People,
            color = StitchColors.primary,
            modifier = Modifier.weight(1f)
        )
        ReferralStatCard(
            value = "${stats?.monthlyReferrals ?: 0}",
            label = "This Month",
            icon = Icons.Default.CalendarMonth,
            color = Color.Green,
            modifier = Modifier.weight(1f)
        )
        ReferralStatCard(
            value = "+${stats?.cloutEarned ?: 0}",
            label = "Clout Earned",
            icon = Icons.Default.Bolt,
            color = Color(0xFF9C27B0),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ReferralStatCard(
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.Gray, textAlign = TextAlign.Center)
    }
}

// MARK: - Rewards Progress

@Composable
private fun RewardsProgressCard(stats: ReferralStats?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Reward Progress", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (stats?.rewardsMaxed == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = Color.Green, modifier = Modifier.size(14.dp))
                    Text("Maxed!", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Green)
                }
            }
        }

        // Clout progress bar
        RewardProgressRow(
            icon = Icons.Default.Bolt,
            label = "Clout",
            current = stats?.cloutEarned ?: 0,
            total = 1000,
            color = Color(0xFF9C27B0),
            suffix = ""
        )

        // Hype bonus bar
        val hypePct = (stats?.hypeRatingBonus ?: 0.0) * 100
        RewardProgressRow(
            icon = Icons.Default.LocalFireDepartment,
            label = "Hype Bonus",
            current = (hypePct * 10).toInt(),
            total = 100,
            color = Color(0xFFFF9500),
            suffix = String.format("+%.1f%%", hypePct)
        )
    }
}

@Composable
private fun RewardProgressRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    current: Int,
    total: Int,
    color: Color,
    suffix: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.8f))
            }
            val isMaxed = current >= total
            Text(
                text = if (suffix.isEmpty()) "$current/$total" else suffix,
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (isMaxed) Color.Green else color
            )
        }

        val fraction = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
        Box(
            modifier = Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.6f))), RoundedCornerShape(4.dp))
            )
        }
    }
}

// MARK: - How It Works

@Composable
private fun HowItWorksCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("How It Works", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)

        HowItWorksStep("1", "Share your code with friends")
        HowItWorksStep("2", "They enter it when signing up")
        HowItWorksStep("3", "You both get rewards — they auto-follow you")
    }
}

@Composable
private fun HowItWorksStep(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(28.dp).background(Color(0xFF9C27B0).copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(number, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF9C27B0))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.8f))
    }
}

// MARK: - Recent Referrals

@Composable
private fun RecentReferralsCard(referrals: List<ReferralInfo>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recent Referrals", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("${referrals.size}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        }

        referrals.take(5).forEach { referral ->
            ReferralRow(referral = referral)
        }
    }
}

@Composable
private fun ReferralRow(referral: ReferralInfo) {
    val isCompleted = referral.status == ReferralStatus.COMPLETED
    val statusColor = if (isCompleted) Color.Green else Color.Yellow

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).background(statusColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCompleted) Icons.Default.Check else Icons.Default.Schedule,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(14.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = referral.refereeUsername ?: referral.refereeID?.take(8) ?: "User",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White
                )
                Text(
                    text = timeAgo(referral.createdAt),
                    fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.Gray
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (referral.cloutAwarded > 0) {
                Text(
                    "+${referral.cloutAwarded}",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9C27B0)
                )
            }

            Text(
                text = referral.status.displayName,
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = statusColor,
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

// MARK: - Share CTA

@Composable
private fun ShareCTAButton(isGenerating: Boolean, onShare: () -> Unit) {
    Button(
        onClick = onShare,
        enabled = !isGenerating,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(listOf(Color(0xFF9C27B0), Color(0xFF9C27B0).copy(alpha = 0.7f))),
                    RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Text(
                    "Share Your Invite Code",
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White
                )
            }
        }
    }
}

// MARK: - Helper

private fun timeAgo(date: Date): String {
    val diff = System.currentTimeMillis() - date.time
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}