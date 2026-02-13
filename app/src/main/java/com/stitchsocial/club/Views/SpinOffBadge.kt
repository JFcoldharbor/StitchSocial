/*
 * SpinOffBadge.kt - SPIN-OFF BADGE OVERLAY
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: UI Components - Spin-off attribution badge
 * Dependencies: Compose UI
 * Features: Shows "Responding to @username" on spin-off videos in feed
 *
 * PURPOSE: Visual indicator that a thread is a spin-off from another video
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SubdirectoryArrowLeft

/**
 * Badge shown on spin-off videos in the feed
 * Positioned at top-left of video player
 */
@Composable
fun SpinOffBadge(
    sourceCreatorName: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFF9500))  // Amber/Orange
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Reply arrow icon
        Icon(
            imageVector = Icons.Default.SubdirectoryArrowLeft,
            contentDescription = "Spin-off",
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Text
        Text(
            text = "Responding to @$sourceCreatorName",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Compact badge for smaller spaces
 */
@Composable
fun SpinOffBadgeCompact(
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFF9500))
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.SubdirectoryArrowLeft,
            contentDescription = "Spin-off",
            tint = Color.White,
            modifier = Modifier.size(12.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "Spin-off",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * Badge with custom styling options
 */
@Composable
fun SpinOffBadgeStyled(
    sourceCreatorName: String,
    backgroundColor: Color = Color(0xFFFF9500),
    textColor: Color = Color.White,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.SubdirectoryArrowLeft,
            contentDescription = "Spin-off",
            tint = textColor,
            modifier = Modifier.size(14.dp)
        )

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = "→ @$sourceCreatorName",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}