/*
 * CaptionEditorView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Caption Editor with Text Overlay
 * Dependencies: VideoEditState
 * Features: Add captions, position, style, timing
 *
 * Exact translation from iOS CaptionEditorView.swift
 */

package com.stitchsocial.club

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.ExoPlayer

@Composable
fun CaptionEditorView(
    editState: VideoEditState,
    exoPlayer: ExoPlayer,
    onEditStateChange: (VideoEditState) -> Unit
) {
    var showingAddCaption by remember { mutableStateOf(false) }
    var editingCaption by remember { mutableStateOf<VideoCaption?>(null) }
    var currentPlaybackTime by remember { mutableStateOf(0.0) }
    
    // Track playback position
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPlaybackTime = exoPlayer.currentPosition / 1000.0
            kotlinx.coroutines.delay(100)
        }
    }
    
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Content area
        if (editState.captions.isEmpty()) {
            EmptyStateView(modifier = Modifier.weight(1f))
        } else {
            CaptionList(
                captions = editState.captions,
                onTapCaption = { caption -> editingCaption = caption },
                onSeekCaption = { caption ->
                    val seekMs = (caption.startTime * 1000).toLong()
                    exoPlayer.seekTo(seekMs)
                    exoPlayer.play()
                },
                modifier = Modifier.weight(1f)
            )
        }
        
        // Add caption button
        Button(
            onClick = { showingAddCaption = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent
            ),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Cyan, Color.Blue)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Add Caption",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
    
    // Add caption dialog
    if (showingAddCaption) {
        CaptionEditDialog(
            caption = null,
            currentTime = currentPlaybackTime,
            maxDuration = editState.trimmedDuration,
            onSave = { newCaption ->
                editState.addCaption(newCaption)
                onEditStateChange(editState)
                showingAddCaption = false
            },
            onDelete = null,
            onDismiss = { showingAddCaption = false }
        )
    }
    
    // Edit caption dialog
    editingCaption?.let { caption ->
        CaptionEditDialog(
            caption = caption,
            currentTime = currentPlaybackTime,
            maxDuration = editState.trimmedDuration,
            onSave = { updatedCaption ->
                editState.updateCaption(caption.id) { updatedCaption }
                onEditStateChange(editState)
                editingCaption = null
            },
            onDelete = {
                editState.removeCaption(caption.id)
                onEditStateChange(editState)
                editingCaption = null
            },
            onDismiss = { editingCaption = null }
        )
    }
}

// MARK: - Empty State View

@Composable
private fun EmptyStateView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color.Cyan.copy(alpha = 0.1f))
                    .blur(15.dp)
            )
            Icon(
                imageVector = Icons.Filled.Subtitles,
                contentDescription = null,
                tint = Color.Cyan.copy(alpha = 0.8f),
                modifier = Modifier.size(48.dp)
            )
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "No Captions",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Add captions to make your video accessible",
                color = Color.Gray,
                fontSize = 15.sp
            )
        }
    }
}

// MARK: - Caption List

@Composable
private fun CaptionList(
    captions: List<VideoCaption>,
    onTapCaption: (VideoCaption) -> Unit,
    onSeekCaption: (VideoCaption) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(captions) { caption ->
            CaptionRow(
                caption = caption,
                onTap = { onTapCaption(caption) },
                onSeek = { onSeekCaption(caption) }
            )
        }
    }
}

// MARK: - Caption Row

@Composable
private fun CaptionRow(
    caption: VideoCaption,
    onTap: () -> Unit,
    onSeek: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() },
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play button
            IconButton(
                onClick = onSeek,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Cyan.copy(alpha = 0.2f))
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayCircleFilled,
                    contentDescription = "Play",
                    tint = Color.Cyan,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            // Caption info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = caption.text,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Time range
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "${formatTime(caption.startTime)} - ${formatTime(caption.endTime)}",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    
                    // Position badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Cyan.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = caption.position.displayName,
                            color = Color.Cyan,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            
            // Edit indicator
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Edit",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// MARK: - Caption Edit Dialog

@Composable
private fun CaptionEditDialog(
    caption: VideoCaption?,
    currentTime: Double,
    maxDuration: Double,
    onSave: (VideoCaption) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(caption?.text ?: "") }
    var startTime by remember { mutableStateOf(caption?.startTime ?: currentTime) }
    var duration by remember { mutableStateOf(caption?.duration ?: 3.0) }
    var position by remember { mutableStateOf(caption?.position ?: CaptionPosition.CENTER) }
    var style by remember { mutableStateOf(caption?.style ?: CaptionStyle.STANDARD) }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C1C1E)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.Gray)
                    }
                    
                    Text(
                        text = if (caption == null) "Add Caption" else "Edit Caption",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    TextButton(
                        onClick = {
                            val newCaption = VideoCaption(
                                id = caption?.id ?: java.util.UUID.randomUUID().toString(),
                                text = text,
                                startTime = startTime,
                                duration = duration,
                                position = position,
                                style = style
                            )
                            onSave(newCaption)
                        },
                        enabled = text.isNotBlank()
                    ) {
                        Text("Save", color = if (text.isNotBlank()) Color.Cyan else Color.Gray)
                    }
                }
                
                // Text input
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Caption Text",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Enter caption text...", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Cyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                
                // Position picker
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Position",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CaptionPosition.allCases.forEach { pos ->
                            Button(
                                onClick = { position = pos },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (position == pos) Color.Cyan else Color.White.copy(alpha = 0.1f)
                                ),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                Text(
                                    text = pos.displayName,
                                    color = if (position == pos) Color.White else Color.Gray,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                
                // Style picker
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Style",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CaptionStyle.allCases.forEach { captionStyle ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { style = captionStyle },
                                shape = RoundedCornerShape(10.dp),
                                color = if (style == captionStyle) Color.Cyan.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (style == captionStyle) Icons.Filled.CheckCircle else Icons.Filled.Circle,
                                        contentDescription = null,
                                        tint = if (style == captionStyle) Color.Cyan else Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = captionStyle.displayName,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Timing controls
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Start time
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Start Time: ${formatTime(startTime)}",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = startTime.toFloat(),
                            onValueChange = { startTime = it.toDouble() },
                            valueRange = 0f..maxDuration.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Cyan,
                                activeTrackColor = Color.Cyan
                            )
                        )
                    }
                    
                    // Duration
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Duration: ${String.format("%.1fs", duration)}",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = duration.toFloat(),
                            onValueChange = { duration = it.toDouble() },
                            valueRange = 0.5f..10f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Cyan,
                                activeTrackColor = Color.Cyan
                            )
                        )
                    }
                }
                
                // Delete button (if editing)
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.Red
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.horizontalGradient(listOf(Color.Red.copy(alpha = 0.5f), Color.Red.copy(alpha = 0.5f)))
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete Caption",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Helper Functions

private fun formatTime(time: Double): String {
    val minutes = (time / 60).toInt()
    val seconds = (time % 60).toInt()
    return String.format("%d:%02d", minutes, seconds)
}