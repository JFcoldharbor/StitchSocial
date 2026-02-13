/*
 * ProgressiveHypeButton3D.kt - HYPE BUTTON
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * MATCHES iOS ProgressiveHypeButton:
 * - Self-engagement blocking (founders exempt)
 * - Clout cap + near-cap indicator
 * - Engagement cap enforcement
 * - Error/warning overlays
 * - 3D press + pulse animation
 */

package com.stitchsocial.club.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
fun ProgressiveHypeButton3D(
    videoID: String,
    creatorID: String,
    userTier: UserTier,
    hypeCount: Int,
    currentUserID: String,
    viewModel: EngagementViewModel,
    iconManager: FloatingIconManager,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val isProcessing by viewModel.isProcessing.collectAsState()
    val userHypeRating by viewModel.userHypeRating.collectAsState()

    // Get engagement state from viewModel
    val engagementState = remember(videoID, currentUserID) {
        viewModel.getEngagementState(videoID, currentUserID)
    }

    var isPressed by remember { mutableStateOf(false) }
    var buttonPosition by remember { mutableStateOf(Offset.Zero) }

    var localTapIncrement by rememberSaveable(videoID) { mutableIntStateOf(0) }
    val displayCount = hypeCount + localTapIncrement

    var userTapCount by rememberSaveable(videoID) { mutableIntStateOf(0) }

    // Error/warning state
    var showingError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showingCloutCapWarning by remember { mutableStateOf(false) }

    // Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(3140, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulsePhase"
    )

    // Self-engagement check (matches iOS)
    val isSelfEngagement = currentUserID == creatorID
    val isFounderTier = userTier == UserTier.FOUNDER || userTier == UserTier.CO_FOUNDER
    val shouldBlockSelfEngagement = isSelfEngagement && !isFounderTier

    // Cap checks (matches iOS)
    val hasHitCloutCap = engagementState?.hasHitCloutCap(userTier) ?: false
    val hasHitEngagementCap = engagementState?.hasHitEngagementCap() ?: false
    val isNearCloutCap = run {
        val state = engagementState ?: return@run false
        val remaining = state.getRemainingCloutAllowance(userTier)
        val max = EngagementConfig.getMaxCloutPerUserPerVideo(userTier)
        if (max <= 0) false else (remaining.toDouble() / max.toDouble()) < 0.2
    }

    // Disable logic (matches iOS)
    val isDisabled = shouldBlockSelfEngagement || hasHitCloutCap || hasHitEngagementCap
    val canEngage = userHypeRating >= 1.0 && !isDisabled

    val isFirstEngagement = userTapCount == 0
    val visualMultiplier = EngagementConfig.getVisualHypeMultiplier(userTier)

    val buttonScale = if (isPressed) 0.9f else 1f

    // Border color (matches iOS)
    val borderColor = when {
        shouldBlockSelfEngagement -> Color.Gray
        hasHitCloutCap -> Color.Red
        isProcessing -> Color(0xFFFF8C00)
        else -> Color(0xFFFF6B00)
    }

    val bgColors = when {
        shouldBlockSelfEngagement -> listOf(Color.Gray.copy(alpha = 0.4f), Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.9f))
        hasHitCloutCap -> listOf(Color.Red.copy(alpha = 0.4f), Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.9f))
        else -> listOf(Color.Black.copy(alpha = 0.4f), Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.9f))
    }

    val showSlashedIcon = shouldBlockSelfEngagement || hasHitCloutCap

    fun showError(message: String) {
        errorMessage = message
        showingError = true
        scope.launch {
            delay(2000)
            showingError = false
        }
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
                    rotationX = if (isPressed) 5f else 0f
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
            // Shadow layers for depth
            for (layer in 0 until 4) {
                Canvas(
                    modifier = Modifier
                        .size(42.dp)
                        .offset(x = (layer * 1.5).dp, y = (layer * 1.5).dp)
                ) {
                    drawCircle(
                        color = Color.Black.copy(alpha = (0.4f - layer * 0.1f).coerceAtLeast(0f)),
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

            // Border with pulse
            val borderAlpha = (0.8f + kotlin.math.sin(pulsePhase) * 0.2f).coerceIn(0f, 1f)
            Canvas(modifier = Modifier.size(42.dp)) {
                drawCircle(
                    color = borderColor.copy(alpha = borderAlpha),
                    radius = (size.minDimension - 3.dp.toPx()) / 2,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = if (isProcessing) 2.0.dp.toPx() else 1.5.dp.toPx()
                    )
                )
            }

            // Flame icon
            Icon(
                imageVector = if (showSlashedIcon) Icons.Default.DoNotDisturb else Icons.Default.LocalFireDepartment,
                contentDescription = "Hype",
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

            // Near cap indicator (matches iOS pulsing orange ring)
            if (isNearCloutCap && !hasHitCloutCap && !shouldBlockSelfEngagement) {
                val nearCapAlpha = (0.6f + kotlin.math.sin(pulsePhase * 3f) * 0.3f).coerceIn(0f, 1f)
                Canvas(modifier = Modifier.size(48.dp)) {
                    drawCircle(
                        color = Color(0xFFFF6B00).copy(alpha = nearCapAlpha),
                        radius = size.minDimension / 2,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
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
                        color = Color(0xFFFF8C00),
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
                            println("🔴 HYPE TAP FIRED - videoID: $videoID, disabled: $isDisabled, selfBlock: $shouldBlockSelfEngagement, cloutCap: $hasHitCloutCap, engCap: $hasHitEngagementCap, creatorID: $creatorID, currentUserID: $currentUserID, tier: $userTier")

                            if (shouldBlockSelfEngagement) {
                                showError("You can't hype your own content")
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                return@clickable
                            }

                            if (hasHitCloutCap) {
                                showingCloutCapWarning = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val maxClout = EngagementConfig.getMaxCloutPerUserPerVideo(userTier)
                                showError("You've given max clout ($maxClout) to this video!")
                                scope.launch {
                                    delay(2000)
                                    showingCloutCapWarning = false
                                }
                                return@clickable
                            }

                            if (hasHitEngagementCap) {
                                showError("Maximum engagements reached for this video")
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                return@clickable
                            }

                            if (!canEngage) {
                                showError("Hype rating too low - wait for regeneration")
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                return@clickable
                            }

                            isPressed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            localTapIncrement += visualMultiplier
                            userTapCount++

                            // Spawn floating flame (uses actual FloatingIconManager API)
                            val isFounderFirstTap = isFirstEngagement &&
                                    (userTier == UserTier.FOUNDER || userTier == UserTier.CO_FOUNDER)
                            val isPremiumFirstTap = isFirstEngagement &&
                                    EngagementConfig.hasFirstTapBonus(userTier)

                            iconManager.spawnHypeIcon(
                                from = buttonPosition,
                                userTier = userTier,
                                isFounderFirstTap = isFounderFirstTap || isPremiumFirstTap
                            )

                            // Process via viewModel (passes creatorID)
                            viewModel.onHypeTap(videoID, userTier, creatorID)

                            scope.launch {
                                delay(100)
                                isPressed = false

                                if (isNearCloutCap) {
                                    val remaining = engagementState?.getRemainingCloutAllowance(userTier) ?: 0
                                    showError("Only $remaining clout remaining for this video")
                                }
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

        // Hype count below button
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatHypeCount(displayCount),
            fontSize = 12.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatHypeCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}