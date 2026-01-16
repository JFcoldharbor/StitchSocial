/*
 * VideoTrimmerView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Video Trimmer with RangeSlider
 * Uses Material3 RangeSlider for reliable drag handling
 */

package com.stitchsocial.club

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTrimmerView(
    editState: VideoEditState,
    exoPlayer: ExoPlayer,
    onEditStateChange: (VideoEditState) -> Unit
) {
    // Range slider state (0f to 1f)
    var sliderPosition by remember {
        mutableStateOf(0f..1f)
    }

    // Sync with editState when duration loads
    LaunchedEffect(editState.videoDuration) {
        if (editState.videoDuration > 0) {
            val startRatio = (editState.trimStartTime / editState.videoDuration).toFloat().coerceIn(0f, 1f)
            val endRatio = (editState.trimEndTime / editState.videoDuration).toFloat().coerceIn(0f, 1f)
            sliderPosition = startRatio..endRatio
            println("✂️ TRIMMER: Synced to ${editState.trimStartTime}s - ${editState.trimEndTime}s (duration: ${editState.videoDuration}s)")
        }
    }

    // Calculate times from slider position
    val startTime = sliderPosition.start * editState.videoDuration
    val endTime = sliderPosition.endInclusive * editState.videoDuration
    val duration = endTime - startTime

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Instructions
        Text(
            text = "Drag the handles to trim your video",
            color = Color.Gray,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 20.dp)
        )

        // Range Slider
        RangeSlider(
            value = sliderPosition,
            onValueChange = { range ->
                // Ensure minimum 5% duration (prevents handles from overlapping)
                if (range.endInclusive - range.start >= 0.05f) {
                    sliderPosition = range
                    println("✂️ TRIMMER: Dragging ${range.start} - ${range.endInclusive}")
                }
            },
            onValueChangeFinished = {
                // Commit changes when user releases
                val newStartTime = sliderPosition.start * editState.videoDuration
                val newEndTime = sliderPosition.endInclusive * editState.videoDuration

                editState.updateTrimRange(newStartTime, newEndTime)
                onEditStateChange(editState)

                // Seek player to start of trimmed region
                exoPlayer.seekTo((newStartTime * 1000).toLong())

                println("✂️ TRIMMER: Committed ${newStartTime}s - ${newEndTime}s")
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color.Cyan,
                activeTrackColor = Color.Cyan,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        )

        // The RangeSlider above already shows the selected region visually

        Spacer(modifier = Modifier.height(8.dp))

        // Time display
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TimeDisplay(
                label = "Start",
                time = startTime,
                icon = Icons.Filled.ArrowBack
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(Color.Gray.copy(alpha = 0.3f))
            )

            TimeDisplay(
                label = "End",
                time = endTime,
                icon = Icons.Filled.ArrowForward
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(Color.Gray.copy(alpha = 0.3f))
            )

            TimeDisplay(
                label = "Duration",
                time = duration,
                icon = Icons.Filled.Schedule
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Reset button
        OutlinedButton(
            onClick = {
                sliderPosition = 0f..1f
                editState.updateTrimRange(0.0, editState.videoDuration)
                onEditStateChange(editState)
                exoPlayer.seekTo(0)
                println("✂️ TRIMMER: Reset to full duration")
            },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFFFF9500)
            ),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFFFF9500), Color(0xFFFF9500))
                )
            ),
            shape = RoundedCornerShape(50)
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Reset",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// MARK: - Time Display

@Composable
private fun TimeDisplay(
    label: String,
    time: Double,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = formatTime(time),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// MARK: - Helper Functions

private fun formatTime(time: Double): String {
    if (time.isNaN() || time < 0) return "0:00.0"
    val minutes = (time / 60).toInt()
    val seconds = (time % 60).toInt()
    val tenths = ((time % 1) * 10).toInt()
    return String.format("%d:%02d.%d", minutes, seconds, tenths)
}