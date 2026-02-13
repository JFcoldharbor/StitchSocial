/*
 * ThreadDepthBadge.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 7: UI Components - Thread Depth Badge
 * Displays conversation depth with branded styling
 */

package com.stitchsocial.club.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ThreadDepthBadge - Circular badge showing conversation depth
 *
 * @param depth The conversation depth (0 = parent, 1+ = child)
 * @param size Size of the badge (default 32.dp)
 * @param modifier Optional modifier
 */
@Composable
fun ThreadDepthBadge(
    depth: Int,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier
) {
    // Brand colors
    val brandCyan = Color(0xFF00D9F2)
    val brandPurple = Color(0xFF9966F2)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        brandCyan.copy(alpha = 0.2f),
                        brandPurple.copy(alpha = 0.2f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(brandCyan, brandPurple)
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$depth",
            fontSize = (size.value * 0.4375).sp, // Scale font with size
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * ThreadDepthBadge with custom colors
 */
@Composable
fun ThreadDepthBadge(
    depth: Int,
    size: Dp = 32.dp,
    backgroundColor: Color,
    borderColor: Color,
    textColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(
                width = 1.5.dp,
                color = borderColor,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$depth",
            fontSize = (size.value * 0.4375).sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}