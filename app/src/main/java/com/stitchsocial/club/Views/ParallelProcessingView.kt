/*
 * ParallelProcessingView.kt - HEAT SYSTEM PROGRESS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Parallel Processing Progress Display
 * 4-phase heat system matching iOS ThreadComposer:
 *   Phase 1 (0-30%): Cyan ring, calm
 *   Phase 2 (30-70%): Orange ring, warm glow, embers rise
 *   Phase 3 (70-99%): Red/fire ring throbs, more embers, glowing bar
 *   Phase 4 (100%): Green checkmark, confetti, "THREAD LIVE"
 *
 * CACHING: Ember positions pre-calculated once via remember.
 * Confetti spawns once on phase 4 entry. Timer cleaned on dispose.
 */

package com.stitchsocial.club

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.stitchsocial.club.coordination.NavigationCoordinator
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun ParallelProcessingView(
    navigationCoordinator: NavigationCoordinator,
    modifier: Modifier = Modifier
) {
    val videoCoordinator = navigationCoordinator.exposedVideoCoordinator

    val audioProgress by videoCoordinator.audioExtractionProgress.collectAsState()
    val compressionProgress by videoCoordinator.compressionProgress.collectAsState()
    val aiProgress by videoCoordinator.aiAnalysisProgress.collectAsState()
    val overallProgress by videoCoordinator.parallelProgress.collectAsState()
    val aiResult by videoCoordinator.lastAIResult.collectAsState()

    val phase = remember(overallProgress) {
        when {
            overallProgress >= 1.0 -> 4
            overallProgress >= 0.7 -> 3
            overallProgress >= 0.3 -> 2
            else -> 1
        }
    }

    // Transition on complete
    LaunchedEffect(audioProgress, compressionProgress, aiProgress, aiResult) {
        val allComplete = audioProgress >= 0.99 && compressionProgress >= 0.99 && aiProgress >= 0.99
        if (allComplete && aiResult != null) {
            Log.d("PARALLEL", "✅ All tasks complete")
            delay(1200) // Show phase 4 briefly
            navigationCoordinator.onParallelProcessingComplete()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
    ) {
        // Heat glow from bottom (phase 2+)
        if (phase >= 2) {
            HeatGlowBackground(phase = phase)
        }

        // Ember particles (phase 2-3)
        if (phase in 2..3) {
            EmberParticles(intensity = if (phase == 3) 1f else 0.5f)
        }

        // Confetti (phase 4)
        if (phase == 4) {
            ConfettiOverlay()
        }

        // Progress card
        HeatProgressCard(
            phase = phase,
            overallProgress = overallProgress,
            currentTask = when {
                aiProgress < 0.99 -> "AI Analysis..."
                compressionProgress < 0.99 -> "Compressing video..."
                audioProgress < 0.99 -> "Extracting audio..."
                aiResult == null -> "Waiting for AI..."
                else -> "Complete!"
            },
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

// MARK: - Heat Progress Card

@Composable
private fun HeatProgressCard(
    phase: Int,
    overallProgress: Double,
    currentTask: String,
    modifier: Modifier = Modifier
) {
    val phaseColors = when (phase) {
        1 -> listOf(Color.Cyan, Color(0xFF2196F3))
        2 -> listOf(Color(0xFFFF8800), Color(0xFFFF4400))
        3 -> listOf(Color(0xFFFF4400), Color(0xFFFF8800))
        4 -> listOf(Color(0xFF00CC66), Color.Cyan)
        else -> listOf(Color.Cyan, Color(0xFF2196F3))
    }

    val primaryColor = phaseColors[0]

    // Throb animation for phase 3
    val throbScale by animateFloatAsState(
        targetValue = if (phase == 3) 1.05f else 1f,
        animationSpec = if (phase == 3) infiniteRepeatable(
            tween(600, easing = EaseInOut), RepeatMode.Reverse
        ) else tween(300),
        label = "throb"
    )

    val borderAlpha = when (phase) {
        2 -> 0.2f
        3 -> 0.3f
        else -> 0.05f
    }

    Surface(
        modifier = modifier.padding(32.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color.Black.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, primaryColor.copy(alpha = borderAlpha)),
        shadowElevation = if (phase == 3) 16.dp else 4.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (phase == 4) {
                // Complete checkmark
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF00CC66), Color.Cyan))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Text(
                    text = "THREAD LIVE!",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00CC66)
                )

                Text(
                    text = "Your content is now visible",
                    fontSize = 14.sp,
                    color = Color(0xFF00CC66).copy(alpha = 0.7f)
                )
            } else {
                // Progress ring
                Box(
                    modifier = Modifier.size(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(110.dp)) {
                        // Background ring
                        drawArc(
                            color = Color.White.copy(alpha = 0.08f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // Progress arc
                        drawArc(
                            brush = Brush.sweepGradient(phaseColors),
                            startAngle = -90f,
                            sweepAngle = (overallProgress * 360).toFloat(),
                            useCenter = false,
                            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    Text(
                        text = "${(overallProgress * 100).roundToInt()}%",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (phase == 3) primaryColor else Color.White
                    )
                }

                // Status text
                Text(
                    text = when (phase) {
                        1 -> "Processing..."
                        2 -> "\uD83D\uDD25 Heating up..."
                        3 -> "\uD83D\uDD25\uD83D\uDD25 Almost there!"
                        else -> "Processing..."
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (phase >= 2) primaryColor else Color.White.copy(alpha = 0.9f)
                )

                // Task pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.15f))
                ) {
                    Text(
                        text = currentTask,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                // Progress bar pill
                Box(
                    modifier = Modifier
                        .width(240.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(overallProgress.toFloat().coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(6.dp))
                            .background(Brush.horizontalGradient(phaseColors))
                    )
                }
            }
        }
    }
}

// MARK: - Heat Glow Background

@Composable
private fun HeatGlowBackground(phase: Int) {
    val config = LocalConfiguration.current
    val screenHeight = config.screenHeightDp.dp

    val glowColor = when (phase) {
        2 -> Color(0xFFFF8800).copy(alpha = 0.12f)
        3 -> Color(0xFFFF4400).copy(alpha = 0.25f)
        4 -> Color(0xFF00CC66).copy(alpha = 0.1f)
        else -> Color.Transparent
    }

    val pulseAlpha by animateFloatAsState(
        targetValue = if (phase == 3) 1f else 0.7f,
        animationSpec = if (phase >= 2) infiniteRepeatable(
            tween(1200, easing = EaseInOut), RepeatMode.Reverse
        ) else tween(500),
        label = "glowPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenHeight * 0.45f)
                .align(Alignment.BottomCenter)
                .graphicsLayer { alpha = pulseAlpha }
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, glowColor)
                    )
                )
        )
    }
}

// MARK: - Ember Particles

@Composable
private fun EmberParticles(intensity: Float) {
    // CACHING: Pre-calculate ember data once
    val embers = remember {
        val count = if (intensity > 0.7f) 8 else 4
        (0 until count).map {
            EmberData(
                xPercent = Random.nextFloat(),
                size = 3f + Random.nextFloat() * 4f,
                duration = 2000 + Random.nextInt(1500),
                delay = Random.nextInt(1500),
                color = listOf(
                    Color(0xFFFF6600), Color(0xFFFF8800),
                    Color(0xFFFFAA00), Color(0xFFFF4400), Color(0xFFFFCC00)
                ).random()
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "embers")

    embers.forEachIndexed { index, ember ->
        val progress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(ember.duration, delayMillis = ember.delay, easing = LinearEasing),
                RepeatMode.Restart
            ),
            label = "ember_$index"
        )

        val alpha = when {
            progress < 0.1f -> progress / 0.1f * 0.8f
            progress > 0.7f -> (1f - progress) / 0.3f * 0.5f
            else -> 0.8f
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = ember.xPercent * size.width
            val y = size.height - (progress * size.height * 1.1f)
            drawCircle(
                color = ember.color.copy(alpha = alpha),
                radius = ember.size,
                center = Offset(x, y)
            )
        }
    }
}

private data class EmberData(
    val xPercent: Float,
    val size: Float,
    val duration: Int,
    val delay: Int,
    val color: Color
)

// MARK: - Confetti Overlay

@Composable
private fun ConfettiOverlay() {
    // CACHING: Spawn confetti once on appear
    val pieces = remember {
        val colors = listOf(
            Color.Cyan, Color(0xFFFF6600), Color(0xFF7C4DFF),
            Color(0xFFFFCC00), Color(0xFF00CC66), Color(0xFFFF4081), Color(0xFF2196F3)
        )
        (0 until 20).map {
            ConfettiPiece(
                xPercent = Random.nextFloat(),
                color = colors.random(),
                size = 6f + Random.nextFloat() * 4f,
                duration = 2000 + Random.nextInt(1500),
                rotation = 360f + Random.nextFloat() * 720f
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "confetti")

    pieces.forEachIndexed { index, piece ->
        val progress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(piece.duration, easing = LinearEasing),
                RepeatMode.Restart
            ),
            label = "confetti_$index"
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = piece.xPercent * size.width
            val y = progress * (size.height + 40f) - 10f
            val alpha = if (progress > 0.8f) (1f - progress) / 0.2f else 1f

            rotate(degrees = progress * piece.rotation, pivot = Offset(x, y)) {
                drawRect(
                    color = piece.color.copy(alpha = alpha),
                    topLeft = Offset(x - piece.size / 2, y - piece.size * 0.3f),
                    size = androidx.compose.ui.geometry.Size(piece.size, piece.size * 0.6f)
                )
            }
        }
    }
}

private data class ConfettiPiece(
    val xPercent: Float,
    val color: Color,
    val size: Float,
    val duration: Int,
    val rotation: Float
)