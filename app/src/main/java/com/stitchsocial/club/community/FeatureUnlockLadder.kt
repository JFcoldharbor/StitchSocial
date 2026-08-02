package com.stitchsocial.club.community

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The perk ladder (iOS parity with FeatureUnlockLadder).
 *
 * Every piece of this already existed on Android — the gates, their display
 * names, and the two functions in CommunityXPService that compute unlocked/next.
 * Nothing rendered them, so a member hit a wall (video clips are level 20) with
 * no indication the wall existed or what it took to pass it.
 *
 * @param currentLevel the membership's EFFECTIVE feature level — callers pass
 *   effectiveFeatureLevel, so an owner holding privileges sees their room's perks
 *   as unlocked rather than being told to grind for what they already have.
 */
@Composable
fun FeatureUnlockLadder(currentLevel: Int) {

    // Only perks that actually exist. A ladder listing 21 unlocks when 2 are
    // real isn't a motivator, it's a list of broken promises the member
    // discovers one level at a time.
    val gates = remember { CommunityFeatureGate.live.sortedBy { it.requiredLevel } }
    val next = gates.firstOrNull { it.requiredLevel > currentLevel }
    val gold = Color(0xFFFFC043)
    val textSecondary = Color.White.copy(alpha = 0.55f)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

        Text(
            "WHAT YOU UNLOCK",
            color = textSecondary,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.7.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // The next rung, called out — a ladder with no "you are here" is a list,
        // not a motivator.
        next?.let { gate ->
            val away = gate.requiredLevel - currentLevel
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(gold.copy(alpha = 0.08f))
                    .border(1.dp, gold.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(gold.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.LockOpen, null, tint = gold, modifier = Modifier.size(13.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Next: ${gate.displayName}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (away == 1) "1 level away · unlocks at Lv ${gate.requiredLevel}"
                        else "$away levels away · unlocks at Lv ${gate.requiredLevel}",
                        color = textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Column {
            gates.forEach { gate ->
                val unlocked = currentLevel >= gate.requiredLevel
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        if (unlocked) Icons.Default.CheckCircle else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (unlocked) gold else textSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        gate.displayName,
                        color = if (unlocked) Color.White else textSecondary,
                        fontSize = 12.5.sp,
                        fontWeight = if (unlocked) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Lv ${gate.requiredLevel}",
                        color = textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
