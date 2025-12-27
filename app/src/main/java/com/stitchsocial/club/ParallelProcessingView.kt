/*
 * ParallelProcessingView.kt - RACE CONDITION FIXED
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Parallel Processing Progress Display
 * Dependencies: NavigationCoordinator (Layer 6), VideoCoordinator progress StateFlows
 * Features: Real-time 3-task progress, AI result validation before transition
 *
 * FIXED: Waits for AI result to exist before transitioning to ThreadComposer
 */

package com.stitchsocial.club.views

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.stitchsocial.club.coordination.NavigationCoordinator

@Composable
fun ParallelProcessingView(
    navigationCoordinator: NavigationCoordinator,
    modifier: Modifier = Modifier
) {
    val videoCoordinator = navigationCoordinator.exposedVideoCoordinator

    // Collect progress states
    val audioProgress by videoCoordinator.audioExtractionProgress.collectAsState()
    val compressionProgress by videoCoordinator.compressionProgress.collectAsState()
    val aiProgress by videoCoordinator.aiAnalysisProgress.collectAsState()
    val overallProgress by videoCoordinator.parallelProgress.collectAsState()
    val aiResult by videoCoordinator.lastAIResult.collectAsState()

    // FIXED: Wait for ALL tasks AND AI result before transitioning
    LaunchedEffect(audioProgress, compressionProgress, aiProgress, aiResult) {
        val allTasksComplete = audioProgress >= 0.99 &&
                compressionProgress >= 0.99 &&
                aiProgress >= 0.99
        val currentAIResult = aiResult // Capture to local variable
        val hasAIResult = currentAIResult != null

        if (allTasksComplete && hasAIResult) {
            Log.d("PARALLEL", "✅ All tasks complete with AI result")
            Log.d("PARALLEL", "   Audio: ${(audioProgress * 100).toInt()}%")
            Log.d("PARALLEL", "   Compression: ${(compressionProgress * 100).toInt()}%")
            Log.d("PARALLEL", "   AI: ${(aiProgress * 100).toInt()}%")
            Log.d("PARALLEL", "   AI Result: ${currentAIResult?.title}")

            delay(800) // Brief pause to show 100%

            Log.d("PARALLEL", "🎯 Transitioning to ThreadComposer")
            navigationCoordinator.onParallelProcessingComplete()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.85f)
                .padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Text(
                    text = "Processing Video",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                // Current phase text
                Text(
                    text = when {
                        aiProgress < 0.99 -> "AI Analysis in progress..."
                        compressionProgress < 0.99 -> "Compressing video..."
                        audioProgress < 0.99 -> "Extracting audio..."
                        aiResult == null -> "Waiting for AI result..."
                        else -> "Complete!"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Individual progress bars
                ProgressItem(
                    label = "Audio Extraction",
                    progress = audioProgress.toFloat(),
                    color = Color(0xFF4CAF50)
                )

                ProgressItem(
                    label = "Video Compression",
                    progress = compressionProgress.toFloat(),
                    color = Color(0xFF2196F3)
                )

                ProgressItem(
                    label = "AI Analysis",
                    progress = aiProgress.toFloat(),
                    color = Color(0xFFFF9800)
                )

                // AI Result indicator
                if (aiResult != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "✓",
                            color = Color(0xFF4CAF50),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "AI Result Ready",
                            color = Color(0xFF4CAF50),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Overall progress
                LinearProgressIndicator(
                    progress = overallProgress.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = Color(0xFF00D4FF),
                    trackColor = Color.Gray.copy(alpha = 0.3f)
                )

                Text(
                    text = "Overall: ${(overallProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ProgressItem(
    label: String,
    progress: Float,
    color: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = color,
            trackColor = Color.Gray.copy(alpha = 0.3f)
        )
    }
}