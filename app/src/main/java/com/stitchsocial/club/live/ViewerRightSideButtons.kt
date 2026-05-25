package com.stitchsocial.club.live

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Vertical right-side button cluster — mirrors iOS `viewerRightSideButtons`.
 * TikTok / IG Live layout: hype, gifts, reply, close stacked top-down with
 * label captions underneath each icon.
 *
 * - **Hype** rapid-tap → callback fires every tap; UI shows pulsing count.
 * - **Gifts** → opens `HypePickerSheet`.
 * - **Reply** → opens `VideoCommentRecordSheet`. Disabled below Lv 5.
 * - **Close** → dismiss the viewer.
 */
@Composable
fun ViewerRightSideButtons(
    freeHypeCount: Int,
    canSubmitVideo: Boolean,
    onFreeHype: () -> Unit,
    onOpenGifts: () -> Unit,
    onOpenReply: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FreeHypeButton(count = freeHypeCount, onTap = onFreeHype)

        IconActionButton(
            emoji = "🎁",
            label = "Gifts",
            gradient = Brush.linearGradient(
                listOf(Color.Yellow.copy(alpha = 0.4f), Color(0xFFFF8C42).copy(alpha = 0.3f))
            ),
            onClick = onOpenGifts,
        )

        IconActionButton(
            icon = Icons.Default.VideoCall,
            label = "Reply",
            gradient = Brush.linearGradient(
                listOf(Color(0xFFEC4899).copy(alpha = 0.5f), Color(0xFF9D5BFF).copy(alpha = 0.5f))
            ),
            enabled = canSubmitVideo,
            onClick = onOpenReply,
        )

        // Smaller close button, white-tinted glass
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun FreeHypeButton(count: Int, onTap: () -> Unit) {
    // Pulse every 5th tap — same heuristic as iOS sendFreeHype.
    val pulse = count > 0 && count % 5 == 0
    val scale by animateFloatAsState(
        targetValue = if (pulse) 1.15f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "hypePulse",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(Color.Red.copy(alpha = 0.4f))
                    .clickable { onTap() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.LocalFireDepartment,
                    contentDescription = "Hype",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            if (count > 0) {
                Box(
                    modifier = Modifier
                        .offset(x = 16.dp, y = (-16).dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF8C42)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (count > 99) "99+" else count.toString(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Hype",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun IconActionButton(
    icon: ImageVector? = null,
    emoji: String? = null,
    label: String,
    gradient: Brush,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(gradient)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            } else if (emoji != null) {
                Text(emoji, fontSize = 22.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = Color.White.copy(alpha = if (enabled) 0.6f else 0.3f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
