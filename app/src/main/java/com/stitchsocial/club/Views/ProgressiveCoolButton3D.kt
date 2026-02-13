/*
 * ProgressiveCoolButton3D.kt - COOL BUTTON
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * MATCHES iOS ProgressiveCoolButton:
 * - Self-engagement blocking (founders exempt)
 * - Engagement cap enforcement
 * - Troll detection (matches iOS > 10 threshold)
 * - Error/warning overlays
 * - 3D press + shimmer animation
 */

package com.stitchsocial.club.views

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.stitchsocial.club.foundation.EngagementConfig
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProgressiveCoolButton3D(
    videoID: String,
    creatorID: String,
    userTier: UserTier,
    coolCount: Int,
    currentUserID: String,
    viewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val isProcessing by viewModel.isProcessing.collectAsState()

    // Get engagement state from viewModel
    val engagementState = remember(videoID, currentUserID) {
        viewModel.getEngagementState(videoID, currentUserID)
    }

    var isPressed by remember { mutableStateOf(false) }
    var buttonPosition by remember { mutableStateOf(Offset.Zero) }

    var localTapIncrement by rememberSaveable(videoID) { mutableIntStateOf(0) }
    val displayCount = coolCount + localTapIncrement

    var userTapCount by rememberSaveable(videoID) { mutableIntStateOf(0) }

    // Error/warning state
    var showingError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showingTrollWarning by remember { mutableStateOf(false) }
    var showingCapWarning by remember { mutableStateOf(false) }

    // Shimmer animation (matches iOS)
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerPhase"
    )

    // Self-engagement check (matches iOS)
    val isSelfEngagement = currentUserID == creatorID
    val isFounderTier = userTier == UserTier.FOUNDER || userTier == UserTier.CO_FOUNDER
    val shouldBlockSelfEngagement = isSelfEngagement && !isFounderTier

    // Cap checks (matches iOS)
    val hasHitEngagementCap = engagementState?.hasHitEngagementCap() ?: false

    // Troll detection (matches iOS > 10 threshold)
    val coolEngagements = engagementState?.coolEngagements ?: 0

    // Disable logic (matches iOS)
    val isDisabled = shouldBlockSelfEngagement || hasHitEngagementCap

    val isFirstEngagement = userTapCount == 0
    val buttonScale = if (isPressed) 0.9f else 1f

    // Border color (matches iOS)
    val borderColor = when {
        shouldBlockSelfEngagement -> Color.Gray
        hasHitEngagementCap -> Color.Red
        isProcessing -> Color(0xFF00BFFF)
        else -> Color(0xFF00BFFF)
    }

    val bgColors = when {
        shouldBlockSelfEngagement -> listOf(Color.Gray.copy(alpha = 0.4f), Color.Gray.copy(alpha = 0.3f), Color.Black.copy(alpha = 0.8f))
        hasHitEngagementCap -> listOf(Color.Red.copy(alpha = 0.4f), Color(0xFF00BFFF).copy(alpha = 0.3f), Color.Black.copy(alpha = 0.8f))
        else -> listOf(Color.Black.copy(alpha = 0.4f), Color(0xFF00BFFF).copy(alpha = 0.3f), Color.Black.copy(alpha = 0.8f))
    }

    val showSlashedIcon = shouldBlockSelfEngagement || hasHitEngagementCap

    fun showError(message: String) {
        errorMessage = message
        showingError = true
        scope.launch {
            delay(3000)
            showingError = false
        }
    }

    // Troll check (matches iOS: totalCools > 10)
    fun shouldShowTrollWarning(): Boolean {
        return coolEngagements > 10
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .scale(buttonScale)
                .graphicsLayer {
                    rotationX = if (isPressed) -5f else 0f
                    alpha = if (isDisabled) 0.5f else 1f
                }
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInRoot()
                    buttonPosition = Offset(
                        x = position.x + coordinates.size.width / 2f,
                        y = position.y
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Shadow layers for depth (blue tint)
            for (layer in 0 until 4) {
                Canvas(
                    modifier = Modifier
                        .size(42.dp)
                        .offset(x = (layer * 1.5).dp, y = (layer * 1.5).dp)
                ) {
                    drawCircle(
                        color = Color(0xFF000044).copy(alpha = (0.3f - layer * 0.075f).coerceAtLeast(0f)),
                        radius = size.minDimension / 2
                    )
                }
            }

            // Main button background
            Canvas(modifier = Modifier.size(42.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(colors = bgColors),
                    radius = size.minDimension / 2
                )
            }

            // Border with shimmer
            val borderAlpha = (0.7f + kotlin.math.sin(shimmerPhase) * 0.3f).coerceIn(0f, 1f)
            Canvas(modifier = Modifier.size(42.dp)) {
                drawCircle(
                    color = borderColor.copy(alpha = borderAlpha),
                    radius = (size.minDimension - 3.dp.toPx()) / 2,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = if (isProcessing) 2.0.dp.toPx() else 1.5.dp.toPx()
                    )
                )
            }

            // Snowflake icon
            Icon(
                imageVector = if (showSlashedIcon) Icons.Default.DoNotDisturb else Icons.Default.AcUnit,
                contentDescription = "Cool",
                tint = if (shouldBlockSelfEngagement) Color.Gray else Color.White,
                modifier = Modifier.size(20.dp)
            )

            // Self-engagement overlay (matches iOS)
            if (shouldBlockSelfEngagement) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .scale(1.1f)
                        .background(Color.Gray.copy(alpha = 0.3f), CircleShape)
                        .border(2.dp, Color.Gray, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonOff,
                        contentDescription = "Self-engagement blocked",
                        tint = Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Troll warning overlay
            if (showingTrollWarning) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .scale(1.2f)
                        .background(Color.Red.copy(alpha = 0.3f), CircleShape)
                        .border(2.dp, Color.Red, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Troll warning",
                        tint = Color.Red,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Engagement cap warning overlay
            if (showingCapWarning) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .scale(1.2f)
                        .background(Color(0xFFFF6B00).copy(alpha = 0.3f), CircleShape)
                        .border(2.dp, Color(0xFFFF6B00), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PanTool,
                        contentDescription = "Engagement cap",
                        tint = Color(0xFFFF6B00),
                        modifier = Modifier.size(16.dp)
                    )
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
                        enabled = !isProcessing && !isDisabled,
                        onClick = {
                            println("🔵 COOL TAP FIRED - videoID: $videoID, disabled: $isDisabled, selfBlock: $shouldBlockSelfEngagement, engCap: $hasHitEngagementCap, creatorID: $creatorID, currentUserID: $currentUserID, tier: $userTier")

                            if (shouldBlockSelfEngagement) {
                                showError("You can't cool your own content")
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                return@clickable
                            }

                            if (hasHitEngagementCap) {
                                showingCapWarning = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showError("Maximum engagements reached for this video!")
                                scope.launch {
                                    delay(2000)
                                    showingCapWarning = false
                                }
                                return@clickable
                            }

                            if (shouldShowTrollWarning()) {
                                showingTrollWarning = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showError("Excessive cooling detected - please engage thoughtfully")
                                scope.launch {
                                    delay(2000)
                                    showingTrollWarning = false
                                }
                                return@clickable
                            }

                            isPressed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            localTapIncrement += 1
                            userTapCount++

                            // Spawn floating snowflake (uses actual FloatingIconManager API)
                            val isFounderFirstTap = isFirstEngagement &&
                                    (userTier == UserTier.FOUNDER || userTier == UserTier.CO_FOUNDER)
                            val isPremiumFirstTap = isFirstEngagement &&
                                    EngagementConfig.hasFirstTapBonus(userTier)

                            iconManager.spawnCoolIcon(
                                from = buttonPosition,
                                userTier = userTier,
                                isFounderFirstTap = isFounderFirstTap || isPremiumFirstTap
                            )

                            // Process via viewModel (passes creatorID)
                            viewModel.onCoolTap(videoID, userTier, creatorID)

                            scope.launch {
                                delay(100)
                                isPressed = false
                            }
                        }
                    )
            )
        }

        // Error message overlay
        if (showingError) {
            Box(
                modifier = Modifier
                    .offset(y = 8.dp)
                    .background(Color.Red.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(text = errorMessage, fontSize = 10.sp, color = Color.White)
            }
        }

        // Cool count below button
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatCoolCount(displayCount),
            fontSize = 12.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatCoolCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}