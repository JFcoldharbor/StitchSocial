package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.ui.theme.StitchColors

/**
 * StreakBanner — "streak or die" at-risk nudge (mirrors iOS StreakBanner).
 *
 * Deliberately high-contrast: a near-black card with a magenta hairline +
 * flame so it reads clearly over the dark video feed (the prior version was
 * too subtle). Mounted globally so it shows on every tab, not just home.
 */
@Composable
fun StreakBanner(
    hoursLeft: Int,
    current: Int,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val flame = StitchColors.primary
    val card = Color(0xFF141414)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(card)
            .border(1.5.dp, flame.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Flame glyph + current-streak count badge
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().clip(CircleShape).background(flame.copy(alpha = 0.16f)))
            Icon(Icons.Default.LocalFireDepartment, null, tint = flame, modifier = Modifier.size(24.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(flame)
                    .border(2.dp, card, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("$current", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Your streak ends in $hoursLeft hour${if (hoursLeft == 1) "" else "s"}",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White
            )
            Text(
                "Post or react to keep your $current-day flame",
                fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f)
            )
        }

        Text(
            "Keep it",
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier
                .clip(CircleShape)
                .background(flame)
                .clickable { onKeep() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )

        Icon(
            Icons.Default.Close, "Dismiss",
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier
                .size(18.dp)
                .clickable { onDismiss() }
        )
    }
}
