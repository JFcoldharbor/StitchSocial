/*
 * ProgressiveCoolButton3D.kt - COOL BUTTON
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Simple: Tap = floating snowflake ❄️ + count updates
 * Includes anti-troll protection
 */

package com.stitchsocial.club.views

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.viewmodels.EngagementViewModel
import com.stitchsocial.club.viewmodels.FloatingIconManager
import com.stitchsocial.club.foundation.UserTier
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProgressiveCoolButton3D(
    videoID: String,
    userTier: UserTier,
    coolCount: Int,  // Video's initial cool count
    viewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val isProcessing by viewModel.isProcessing.collectAsState()
    val currentVideoState by viewModel.currentVideoState.collectAsState()

    var isPressed by remember { mutableStateOf(false) }
    var buttonPosition by remember { mutableStateOf(Offset.Zero) }

    // ❄️ PERSISTENT COUNT: Use rememberSaveable to survive recomposition
    var localTapIncrement by rememberSaveable(videoID) { mutableStateOf(0) }

    // Display count = passed-in count + local increment for immediate feedback
    val displayCount = coolCount + localTapIncrement

    // Track user's taps for first-tap detection
    var userTapCount by rememberSaveable(videoID) { mutableStateOf(0) }

    // Anti-troll (persistent to prevent circumvention by scrolling)
    var userCoolTaps by rememberSaveable(videoID) { mutableStateOf(0) }
    var showTrollWarning by remember { mutableStateOf(false) }
    val warningThreshold = 80
    val blockingThreshold = 100
    val isWarningLevel = userCoolTaps >= warningThreshold
    val isBlocked = userCoolTaps >= blockingThreshold

    val isFirstEngagement = userTapCount == 0
    val isFounderFirstTap = isFirstEngagement &&
            (userTier == UserTier.FOUNDER || userTier == UserTier.CO_FOUNDER)
    val isPremiumFirstTap = isFirstEngagement && userTier in listOf(
        UserTier.TOP_CREATOR, UserTier.LEGENDARY,
        UserTier.PARTNER, UserTier.ELITE, UserTier.AMBASSADOR
    )

    val buttonScale = if (isPressed) 0.85f else 1f
    val borderColor = when {
        isBlocked -> Color.Red
        isWarningLevel -> Color(0xFFFF6B00)
        else -> Color(0xFF00BFFF)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .scale(buttonScale)
                .graphicsLayer { rotationX = if (isPressed) 8f else 0f }
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInRoot()
                    buttonPosition = Offset(
                        x = position.x + coordinates.size.width / 2f,
                        y = position.y
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Shadow
            Canvas(
                modifier = Modifier
                    .size(52.dp)
                    .offset(x = 2.dp, y = 3.dp)
            ) {
                drawCircle(color = Color.Black.copy(alpha = 0.4f), radius = size.minDimension / 2)
            }

            // Background
            Canvas(modifier = Modifier.size(52.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF2A2A2A), Color(0xFF1A1A1A), Color(0xFF0A0A0A))
                    ),
                    radius = size.minDimension / 2
                )
            }

            // Border
            Canvas(modifier = Modifier.size(52.dp)) {
                drawCircle(
                    color = borderColor,
                    radius = (size.minDimension - 3.dp.toPx()) / 2,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx())
                )
            }

            // ❄️ Snowflake icon (or blocked)
            if (!isBlocked) {
                Icon(
                    imageVector = Icons.Default.AcUnit,
                    contentDescription = "Cool",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = "Blocked",
                    tint = Color.Red,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Warning badge
            if (isWarningLevel && !isBlocked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-20).dp)
                        .background(Color.Red.copy(alpha = 0.95f), CircleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${blockingThreshold - userCoolTaps}",
                        fontSize = 10.sp,
                        color = Color.White
                    )
                }
            }

            // Troll warning popup
            if (showTrollWarning) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-45).dp)
                        .background(Color.Red, shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "⚠️ Slow down!",
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
                LaunchedEffect(showTrollWarning) {
                    delay(2000)
                    showTrollWarning = false
                }
            }

            // Processing overlay
            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFF00BFFF),
                        strokeWidth = 2.dp
                    )
                }
            }

            // Clickable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .clickable(
                        enabled = !isProcessing && !isBlocked,
                        onClick = {
                            userCoolTaps++
                            if (userCoolTaps == warningThreshold) {
                                showTrollWarning = true
                            }

                            if (userCoolTaps < blockingThreshold) {
                                isPressed = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                                // ❄️ INCREMENT LOCAL TAP COUNT FOR IMMEDIATE VISUAL FEEDBACK
                                localTapIncrement += 1
                                userTapCount++

                                // ❄️ Spawn floating snowflake
                                iconManager.spawnCoolIcon(
                                    from = buttonPosition,
                                    userTier = userTier,
                                    isFounderFirstTap = isFounderFirstTap || isPremiumFirstTap
                                )

                                viewModel.onCoolTap(videoID, userTier)

                                scope.launch {
                                    delay(100)
                                    isPressed = false
                                }
                            }
                        }
                    )
            )
        }

        // Cool count below button
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatCount(displayCount),
            fontSize = 12.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}