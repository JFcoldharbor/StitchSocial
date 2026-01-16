/*
 * VideoReviewView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Video Review Screen
 * Dependencies: VideoEditState, LocalDraftManager, VideoExportService
 * Features: Trim, filter, caption editors with live preview
 *
 * Exact translation from iOS VideoReviewView.swift
 */

package com.stitchsocial.club

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VideoReviewView(
    initialState: VideoEditState,
    onContinueToThread: (VideoEditState) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // State management
    var editState by remember { mutableStateOf(initialState) }
    var selectedTab by remember { mutableStateOf(EditTab.TRIM) }
    var isPlaying by remember { mutableStateOf(true) }
    var showingCancelAlert by remember { mutableStateOf(false) }
    var showingExportError by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    // Video player
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(initialState.videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }

    // Load video properties
    LaunchedEffect(initialState.videoUri) {
        loadVideoProperties(context, initialState.videoUri) { duration, width, height ->
            editState = editState.copy(
                videoDuration = duration,
                videoWidth = width,
                videoHeight = height,
                trimEndTime = duration
            )
        }
    }

    // Auto-save draft periodically
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000) // 30 seconds
            LocalDraftManager.getInstance(context).saveDraft(editState)
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Full-screen video preview
        VideoPreviewLayer(
            exoPlayer = exoPlayer,
            isPlaying = isPlaying,
            onTapToToggle = {
                isPlaying = !isPlaying
                if (isPlaying) exoPlayer.play() else exoPlayer.pause()
            }
        )

        // Overlaid top toolbar
        TopToolbar(
            onClose = { showingCancelAlert = true },
            onSave = {
                coroutineScope.launch {
                    LocalDraftManager.getInstance(context).saveDraft(editState)
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
        )

        // Bottom edit panel
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // Edit content area with gradient background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f),
                                Color.Black
                            )
                        )
                    )
            ) {
                EditContentArea(
                    selectedTab = selectedTab,
                    editState = editState,
                    exoPlayer = exoPlayer,
                    onEditStateChange = { editState = it }
                )
            }

            // Edit tab bar
            EditTabBar(
                selectedTab = selectedTab,
                onTabSelect = { selectedTab = it }
            )

            // Bottom action bar
            BottomActionBar(
                duration = editState.trimmedDuration,
                isProcessing = editState.isProcessing,
                onContinue = {
                    coroutineScope.launch {
                        if (editState.hasEdits && !editState.isProcessingComplete) {
                            editState.startProcessing()
                            try {
                                val result = VideoExportService.getInstance(context)
                                    .exportVideo(editState)
                                editState.finishProcessing(result.videoUri, result.thumbnailUri)
                            } catch (e: Exception) {
                                exportError = e.message
                                showingExportError = true
                                return@launch
                            }
                        }
                        onContinueToThread(editState)
                    }
                }
            )
        }

        // Processing overlay
        if (editState.isProcessing) {
            ProcessingOverlay(progress = editState.processingProgress)
        }
    }

    // Cancel alert dialog
    if (showingCancelAlert) {
        AlertDialog(
            onDismissRequest = { showingCancelAlert = false },
            title = { Text("Cancel Editing?") },
            text = { Text("Your edits will be lost if you haven't saved a draft.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showingCancelAlert = false
                        onCancel()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showingCancelAlert = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }

    // Export error dialog
    if (showingExportError) {
        AlertDialog(
            onDismissRequest = { showingExportError = false },
            title = { Text("Export Error") },
            text = { Text(exportError ?: "Failed to process video") },
            confirmButton = {
                TextButton(onClick = { showingExportError = false }) {
                    Text("OK")
                }
            }
        )
    }
}

// MARK: - Video Preview Layer

@Composable
private fun VideoPreviewLayer(
    exoPlayer: ExoPlayer,
    isPlaying: Boolean,
    onTapToToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onTapToToggle() }
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// MARK: - Top Toolbar

@Composable
private fun TopToolbar(
    onClose: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Close button
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }

        Text(
            text = "Edit Video",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        // Save button
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.5f),
            modifier = Modifier.clickable { onSave() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Save",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// MARK: - Edit Content Area

@Composable
private fun EditContentArea(
    selectedTab: EditTab,
    editState: VideoEditState,
    exoPlayer: ExoPlayer,
    onEditStateChange: (VideoEditState) -> Unit
) {
    when (selectedTab) {
        EditTab.TRIM -> VideoTrimmerView(
            editState = editState,
            exoPlayer = exoPlayer,
            onEditStateChange = onEditStateChange
        )
        EditTab.FILTERS -> FilterPickerView(
            editState = editState,
            onEditStateChange = onEditStateChange
        )
        EditTab.CAPTIONS -> CaptionEditorView(
            editState = editState,
            exoPlayer = exoPlayer,
            onEditStateChange = onEditStateChange
        )
    }
}

// MARK: - Edit Tab Bar

@Composable
private fun EditTabBar(
    selectedTab: EditTab,
    onTabSelect: (EditTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.9f))
    ) {
        EditTab.allCases.forEach { tab ->
            val isSelected = selectedTab == tab

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelect(tab) }
                    .background(
                        if (isSelected) Color.Cyan.copy(alpha = 0.2f) else Color.Transparent
                    )
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = when (tab) {
                        EditTab.TRIM -> Icons.Filled.ContentCut
                        EditTab.FILTERS -> Icons.Filled.FilterAlt
                        EditTab.CAPTIONS -> Icons.Filled.Subtitles
                    },
                    contentDescription = tab.title,
                    tint = if (isSelected) Color.Cyan else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = tab.title,
                    color = if (isSelected) Color.Cyan else Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// MARK: - Bottom Action Bar

@Composable
private fun BottomActionBar(
    duration: Double,
    isProcessing: Boolean,
    onContinue: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.9f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Duration display
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = formatDuration(duration),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Continue button
        Button(
            onClick = onContinue,
            enabled = !isProcessing,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Cyan
            ),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
            modifier = Modifier.shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(50),
                ambientColor = Color.Cyan.copy(alpha = 0.4f)
            )
        ) {
            Text(
                text = "Continue",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ArrowForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// MARK: - Processing Overlay

@Composable
private fun ProcessingOverlay(progress: Double) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.9f)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LinearProgressIndicator(
                    progress = { progress.toFloat() },
                    modifier = Modifier.width(200.dp),
                    color = Color.Cyan
                )
                Text(
                    text = "Processing...",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        }
    }
}

// MARK: - Helper Functions

private fun formatDuration(duration: Double): String {
    val minutes = (duration / 60).toInt()
    val seconds = (duration % 60).toInt()
    return String.format("%d:%02d", minutes, seconds)
}

private suspend fun loadVideoProperties(
    context: android.content.Context,
    uri: Uri,
    onLoaded: (Double, Int, Int) -> Unit
) {
    try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        val durationMs = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
        val duration = durationMs / 1000.0

        val width = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 1080

        val height = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 1920

        // Check for rotation
        val rotation = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
        )?.toIntOrNull() ?: 0

        val (finalWidth, finalHeight) = if (rotation == 90 || rotation == 270) {
            Pair(height, width)
        } else {
            Pair(width, height)
        }

        retriever.release()
        onLoaded(duration, finalWidth, finalHeight)

        println("📐 VIDEO PROPS: ${finalWidth}x${finalHeight}, ${duration}s, rotation: $rotation")

    } catch (e: Exception) {
        println("⚠️ VIDEO PROPS: Failed to load - ${e.message}")
        onLoaded(0.0, 1080, 1920)
    }
}