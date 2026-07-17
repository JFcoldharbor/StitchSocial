/*
 * ProfileBadgePreviewRow.kt — mirrors iOS ProfileBadgePreviewRow.
 *
 * Compact pinned-first row that lives in ProfileHeader. Shows up to 5
 * earned badge emojis as overlapping bubbles, an "X Badges" + "N new"
 * label, and a chevron. Tapping opens the full BadgePageView.
 *
 * Zero extra Firestore reads: pulls from BadgeService.shared's already-
 * attached listener for this user.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.stitchsocial.club.foundation.BadgeCatalog
import com.stitchsocial.club.foundation.EarnedBadge
import com.stitchsocial.club.services.BadgeService

@Composable
fun ProfileBadgePreviewRow(
    userID: String,
    isOwner: Boolean,
    onTapView: () -> Unit
) {
    val service = remember { BadgeService.shared }
    val earnedByUser by service.earnedByUser.collectAsState()
    val earned = earnedByUser[userID].orEmpty()

    DisposableEffect(userID) {
        service.listenForBadges(userID)
        onDispose { if (!isOwner) service.stopListening(userID) }
    }

    // Pinned first, then by recency.
    val preview: List<EarnedBadge> = remember(earned) {
        val pinned = earned.filter { it.isPinned }
        val rest   = earned.filter { !it.isPinned }.sortedByDescending { it.earnedAt }
        (pinned + rest).take(5)
    }
    val newCount = earned.count { it.isNew }
    val totalEarned = earned.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onTapView() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Stack of overlapping bubble previews.
        Row(modifier = Modifier.height(34.dp)) {
            preview.forEachIndexed { idx, eb ->
                val def = BadgeCatalog.find(eb.id)
                if (def != null) {
                    Box(
                        modifier = Modifier
                            .zIndex((preview.size - idx).toFloat())
                            .offset(x = (-6 * idx).dp)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(2.dp, Color.Black, CircleShape),  // neutral separator, no rarity color
                        contentAlignment = Alignment.Center
                    ) {
                        Text(def.emoji, fontSize = 16.sp)
                    }
                }
            }
            if (totalEarned > 5) {
                Box(
                    modifier = Modifier
                        .zIndex(0f)
                        .offset(x = (-6 * preview.size).dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+${totalEarned - 5}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Pulsing red dot when there are unseen badges (clears on opening the
        // page). Replaces the "N Badges / N new" label + colored rings — iOS parity.
        if (newCount > 0) {
            val transition = rememberInfiniteTransition(label = "badgeDot")
            val pulseScale by transition.animateFloat(
                initialValue = 0.9f, targetValue = 2.2f,
                animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing)),
                label = "scale"
            )
            val pulseAlpha by transition.animateFloat(
                initialValue = 0.6f, targetValue = 0f,
                animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing)),
                label = "alpha"
            )
            Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(7.dp).scale(pulseScale).alpha(pulseAlpha).clip(CircleShape).background(Color.Red))
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.Red))
            }
        }

        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
    }
}

/**
 * ProfileBadgeStack — compact under-avatar badge cluster (mirrors iOS
 * ProfileBadgeStack). Up to 3 overlapping bubbles sized to tuck beneath the
 * 76dp avatar, with a small "N" pill when there are unseen badges. No full-width
 * card / chevron — that was the old preview row. Tapping opens BadgePageView.
 */
@Composable
fun ProfileBadgeStack(
    userID: String,
    isOwner: Boolean,
    onTapView: () -> Unit
) {
    val service = remember { BadgeService.shared }
    val earnedByUser by service.earnedByUser.collectAsState()
    val earned = earnedByUser[userID].orEmpty()

    DisposableEffect(userID) {
        service.listenForBadges(userID)
        onDispose { if (!isOwner) service.stopListening(userID) }
    }

    if (earned.isEmpty()) return

    val preview: List<EarnedBadge> = remember(earned) {
        val pinned = earned.filter { it.isPinned }
        val rest   = earned.filter { !it.isPinned }.sortedByDescending { it.earnedAt }
        (pinned + rest).take(3)
    }
    val newCount = earned.count { it.isNew }
    val totalEarned = earned.size

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onTapView() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Overlapping bubble cluster (compact — sits under the avatar).
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            preview.forEachIndexed { idx, eb ->
                val def = BadgeCatalog.find(eb.id)
                if (def != null) {
                    Box(
                        modifier = Modifier
                            .zIndex((preview.size - idx).toFloat())
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1C1C1E))
                            .border(2.dp, Color.Black, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(def.emoji, fontSize = 13.sp)
                    }
                }
            }
            if (totalEarned > 3) {
                Box(
                    modifier = Modifier
                        .zIndex(0f)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1C1C1E))
                        .border(2.dp, Color.Black, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+${totalEarned - 3}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // Pulsing red dot when there are unseen badges — NO number (iOS parity).
        if (newCount > 0) {
            Spacer(Modifier.width(6.dp))
            val transition = rememberInfiniteTransition(label = "badgeStackDot")
            val pulseScale by transition.animateFloat(
                initialValue = 0.9f, targetValue = 2.2f,
                animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing)),
                label = "scale"
            )
            val pulseAlpha by transition.animateFloat(
                initialValue = 0.6f, targetValue = 0f,
                animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing)),
                label = "alpha"
            )
            Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(7.dp).scale(pulseScale).alpha(pulseAlpha).clip(CircleShape).background(Color.Red))
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.Red))
            }
        }
    }
}
