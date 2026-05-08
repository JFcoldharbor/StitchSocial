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
                            .border(
                                2.dp,
                                if (eb.isNew) def.rarity.uiColor else Color.Transparent,
                                CircleShape
                            ),
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

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "$totalEarned ${if (totalEarned == 1) "Badge" else "Badges"}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                if (newCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFFB923C))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text("$newCount new", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
            Text(
                if (totalEarned == 0) "Tap to see what's available" else "View collection",
                fontSize = 10.sp,
                color = Color.Gray
            )
        }

        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
    }
}
