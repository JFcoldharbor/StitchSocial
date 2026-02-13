/*
 * SpinOffCountIndicator.kt - SPIN-OFF COUNT BUTTON
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: UI Components - Spin-off engagement indicator
 * Dependencies: Compose UI
 * Features: Shows spin-off count, opens spin-offs list sheet when tapped
 *
 * PURPOSE: Engagement button in video overlay sidebar (like hype/cool/share)
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree

/**
 * Spin-off count indicator button
 * Only shown when video.spinOffCount > 0
 */
@Composable
fun SpinOffCountIndicator(
    count: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onTap)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon container
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0x33FF9500))  // 20% opacity amber
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AccountTree,  // Branch/split icon
                contentDescription = "Spin-offs",
                tint = Color(0xFFFF9500),  // Amber/Orange
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Count text
        Text(
            text = formatCount(count),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

/**
 * Compact horizontal version for tighter layouts
 */
@Composable
fun SpinOffCountIndicatorCompact(
    count: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0x33FF9500))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.AccountTree,
            contentDescription = "Spin-offs",
            tint = Color(0xFFFF9500),
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = formatCount(count),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * Styled version with custom colors
 */
@Composable
fun SpinOffCountIndicatorStyled(
    count: Int,
    backgroundColor: Color = Color(0x33FF9500),
    iconColor: Color = Color(0xFFFF9500),
    textColor: Color = Color.White,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onTap)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AccountTree,
                contentDescription = "Spin-offs",
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = formatCount(count),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

/**
 * Format count for display (e.g., 1.2K, 15M)
 */
private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 10_000 -> String.format("%.1fK", count / 1000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1000.0)
        else -> count.toString()
    }
}