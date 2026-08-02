/*
 * CommunityDrillDownViews.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Detail views opened from CommunityDetailV2View
 *
 * Mirrors iOS LeaderboardSort.swift — three full-screen overlays:
 *  1. MemberLeaderboardViewV2 — sortable list (level / hypes given / hypes received)
 *  2. BadgeGalleryViewV2 — grid of all badges, earned vs locked
 *  3. HighlightPlayerViewV2 — placeholder for stream replay playback
 *
 * Caller is CommunityDetailV2View. Each takes onDismiss to close.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.community.CommunityBadgeDefinition
import com.stitchsocial.club.community.CommunityMembership

// ─────────────────────────────────────────────────────────────────────────────
// Shared design tokens — same palette as CommunityDetailV2View
// ─────────────────────────────────────────────────────────────────────────────

private object DD {
    val bg = Color(0xFF0F0B1E)
    val card = Color(0xFF1A1432)
    val cardBorder = Color.White.copy(alpha = 0.08f)
    val cyan = Color(0xFF00D4FF)
    val purple = Color(0xFF8B5CF6)
    val pink = Color(0xFFEC4899)
    val orange = Color(0xFFF59E0B)
    val gold = Color(0xFFFFD700)
    val red = Color(0xFFEF4444)
    val txt = Color(0xFFF1F5F9)
    val txt2 = Color(0xFF94A3B8)
    val txt3 = Color(0xFF64748B)
}

enum class LeaderboardSortV2(val label: String) {
    LEVEL("Level"),
    HYPES_GIVEN("Hype Given"),
    HYPES_RECEIVED("Hype Received");
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Member Leaderboard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MemberLeaderboardViewV2(
    topMembers: List<CommunityMembership>,
    initialSort: LeaderboardSortV2,
    onDismiss: () -> Unit,
) {
    var sort by remember { mutableStateOf(initialSort) }

    val sorted = remember(topMembers, sort) {
        when (sort) {
            LeaderboardSortV2.LEVEL -> topMembers.sortedByDescending { it.level }
            LeaderboardSortV2.HYPES_GIVEN -> topMembers.sortedByDescending { it.totalHypesGiven }
            LeaderboardSortV2.HYPES_RECEIVED -> topMembers.sortedByDescending { it.totalHypesReceived }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(DD.bg)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = DD.txt2,
                modifier = Modifier.size(20.dp).clickable { onDismiss() },
            )
            Spacer(Modifier.weight(1f))
            Text("Leaderboard", color = DD.txt, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
        }

        // Sort selector
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LeaderboardSortV2.entries.forEach { option ->
                val isActive = option == sort
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isActive) DD.cyan.copy(alpha = 0.15f) else DD.card)
                        .border(
                            0.5.dp,
                            if (isActive) DD.cyan.copy(alpha = 0.4f) else DD.cardBorder,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { sort = option }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.label,
                        color = if (isActive) DD.cyan else DD.txt2,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // List
        if (sorted.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No members yet", color = DD.txt3, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sorted, key = { it.id }) { member ->
                    val rank = sorted.indexOf(member) + 1
                    LeaderboardRow(rank = rank, member = member, sort = sort)
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(
    rank: Int,
    member: CommunityMembership,
    sort: LeaderboardSortV2,
) {
    val rankDisplay = when (rank) {
        1 -> "🥇"
        2 -> "🥈"
        3 -> "🥉"
        else -> rank.toString()
    }
    val rankColor = when (rank) {
        1 -> DD.gold
        2 -> DD.txt2
        3 -> DD.orange
        else -> DD.txt3
    }
    val statText = when (sort) {
        LeaderboardSortV2.LEVEL -> "Lv ${member.level}"
        LeaderboardSortV2.HYPES_GIVEN -> "${member.totalHypesGiven} 🔥"
        LeaderboardSortV2.HYPES_RECEIVED -> "${member.totalHypesReceived} 🔥"
    }
    val statColor = when (sort) {
        LeaderboardSortV2.LEVEL -> DD.cyan
        else -> DD.orange
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DD.card)
            .border(0.5.dp, DD.cardBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            if (rank <= 3) {
                Text(rankDisplay, fontSize = 22.sp)
            } else {
                Text(
                    "#$rank",
                    color = rankColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Avatar circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(DD.purple.copy(alpha = 0.5f), DD.pink.copy(alpha = 0.5f))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                member.username.take(1).uppercase(),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "@${member.username}",
                color = DD.txt,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Lv ${member.level} · ${member.totalHypesReceived} 🔥 received",
                color = DD.txt3,
                fontSize = 10.sp,
            )
        }

        Text(
            statText,
            color = statColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Badge Gallery
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun BadgeGalleryViewV2(
    currentLevel: Int,
    earnedBadgeIDs: List<String>,
    /**
     * Level for the PERK ladder, which is not the same question as badges.
     * Badges are earned, so they read the raw level; perks can be granted, so an
     * owner holding privileges genuinely has them and shouldn't be shown a list
     * of locks for things they can already do. Defaults to currentLevel so every
     * other caller is unaffected.
     */
    featureLevel: Int = currentLevel,
    onDismiss: () -> Unit,
) {
    val allBadges = remember { CommunityBadgeDefinition.allBadges }
    val earnedSet = remember(earnedBadgeIDs) { earnedBadgeIDs.toSet() }
    val earnedCount = allBadges.count { it.level <= currentLevel || earnedSet.contains(it.id) }
    val totalCount = allBadges.size

    Column(modifier = Modifier.fillMaxSize().background(DD.bg)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = DD.txt2,
                modifier = Modifier.size(20.dp).clickable { onDismiss() },
            )
            Spacer(Modifier.weight(1f))
            Text("Badge Gallery", color = DD.txt, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
        }

        // Progress
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("⭐ Lv $currentLevel", color = DD.gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(DD.cardBorder),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(earnedCount.toFloat() / totalCount.coerceAtLeast(1))
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(listOf(DD.gold, DD.orange))
                        ),
                )
            }
            Text(
                "$earnedCount / $totalCount",
                color = DD.txt2,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // The perk ladder. Badges are cosmetic; this is the part that says what
        // levelling actually GETS you, and it lists only perks that exist.
        Spacer(Modifier.height(8.dp))
        com.stitchsocial.club.community.FeatureUnlockLadder(currentLevel = featureLevel)
        Spacer(Modifier.height(8.dp))

        // Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(allBadges) { badge ->
                val isEarned = badge.level <= currentLevel || earnedSet.contains(badge.id)
                BadgeCell(badge = badge, isEarned = isEarned)
            }
        }
    }
}

@Composable
private fun BadgeCell(badge: CommunityBadgeDefinition, isEarned: Boolean) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isEarned) DD.card else DD.card.copy(alpha = 0.5f))
            .border(
                0.5.dp,
                if (isEarned) DD.gold.copy(alpha = 0.4f) else DD.cardBorder,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Real artwork, not the placeholder emoji. Locked badges get the
        // handoff's dimmed + desaturated + dashed-ring treatment so they still
        // read as a specific thing you haven't earned.
        com.stitchsocial.club.community.CommunityLevelBadgeArt(
            badgeID = badge.id,
            size = 44.dp,
            locked = !isEarned,
            fallbackEmoji = badge.emoji,
        )
        Text(
            badge.name,
            color = if (isEarned) DD.txt else DD.txt3,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            "Lv ${badge.level}",
            color = if (isEarned) DD.gold else DD.txt3,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Highlight Player (placeholder)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HighlightPlayerViewV2(
    communityName: String,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(20.dp).clickable { onDismiss() },
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$communityName's Highlights",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Stream Replays",
                    color = DD.txt2,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
        }

        // Placeholder card
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            DD.purple.copy(alpha = 0.3f),
                            DD.cyan.copy(alpha = 0.2f),
                            DD.pink.copy(alpha = 0.2f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(DD.cyan.copy(alpha = 0.3f))
                        .border(2.dp, DD.cyan, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Highlights from $communityName",
                    color = DD.txt2,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Coming soon — stream replays will appear here",
                    color = DD.txt3,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
