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

import com.stitchsocial.club.ui.theme.StitchColors
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import com.stitchsocial.club.BuildConfig

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
    // null = no panel open. Tapping a tool-rail icon opens its panel; tapping
    // the dimmed area outside (or the icon again) closes it. Mirrors iOS
    // EditPanel? activePanel pattern.
    var activePanel by remember { mutableStateOf<EditTab?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
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

    // Captions are now opt-in (mirrors iOS). Auto-generation on every review
    // entry was burning Cloud Function transcription costs on videos users
    // never wanted captioned. The CaptionEditorView empty state now offers a
    // "Generate captions" button that triggers AutoCaptionService on demand.

    // Auto-save draft periodically — persists the actual bytes into the drafts
    // dir (not the purgeable cacheDir) and re-points editState at the copy so
    // subsequent saves don't re-copy.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000) // 30 seconds
            editState = LocalDraftManager.getInstance(context).persistDraft(editState)
        }
    }

    // Exit-without-posting = AUTO-SAVE (no dialog, matches iOS). Copies the
    // recording bytes into the persistent drafts dir, writes a poster, then
    // leaves. Covers both the X button and the hardware back gesture.
    val autoSaveAndExit: () -> Unit = {
        coroutineScope.launch {
            LocalDraftManager.getInstance(context).persistDraft(editState)
            onCancel()
        }
    }
    androidx.activity.compose.BackHandler(enabled = true) { autoSaveAndExit() }

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
        // ── Full-screen video preview ──────────────────────────────
        VideoPreviewLayer(
            exoPlayer = exoPlayer,
            isPlaying = isPlaying,
            onTapToToggle = {
                isPlaying = !isPlaying
                if (isPlaying) exoPlayer.play() else exoPlayer.pause()
            }
        )

        // ── Top bar: cancel left, save right ───────────────────────
        TopToolbar(
            onClose = autoSaveAndExit,
            onSave = {
                coroutineScope.launch {
                    editState = LocalDraftManager.getInstance(context).persistDraft(editState)
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
        )

        // ── Right-side tool rail (vertical, mirrors iOS) ───────────
        ToolRail(
            activePanel = activePanel,
            onTabSelect = { tab ->
                activePanel = if (activePanel == tab) null else tab
                // Pause playback while editing — iOS does the same on
                // panel-open so the timeline doesn't scrub away under you.
                if (activePanel != null) {
                    exoPlayer.pause(); isPlaying = false
                }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp, bottom = 120.dp)
        )

        // ── Bottom Next button (no tab bar — iOS doesn't have one) ─
        NextButtonBar(
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
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp, start = 20.dp, end = 20.dp)
        )

        // ── Panel overlay (slides up from bottom when a tool tapped) ─
        AnimatedVisibility(
            visible = activePanel != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 220)
            ) + fadeIn(animationSpec = tween(220)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 220)
            ) + fadeOut(animationSpec = tween(220)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PanelOverlay(
                panel = activePanel ?: EditTab.TRIM,
                editState = editState,
                exoPlayer = exoPlayer,
                onEditStateChange = { editState = it },
                onDismiss = {
                    activePanel = null
                    exoPlayer.play(); isPlaying = true
                }
            )
        }

        // Processing overlay
        if (editState.isProcessing) {
            ProcessingOverlay(progress = editState.processingProgress)
        }
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
                    // Edge-to-edge fill — matches iOS .resizeAspectFill.
                    // RESIZE_MODE_FIT (default) letterboxes; RESIZE_MODE_ZOOM
                    // crops to fill the whole frame.
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setBackgroundColor(android.graphics.Color.BLACK)
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

// MARK: - Tool Rail (right side, mirrors iOS toolRail)

@Composable
private fun ToolRail(
    activePanel: EditTab?,
    onTabSelect: (EditTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        EditTab.allCases.forEach { tab ->
            val isActive = activePanel == tab
            ToolRailButton(
                icon = when (tab) {
                    EditTab.TRIM -> Icons.Filled.ContentCut
                    EditTab.CAPTIONS -> Icons.Filled.Subtitles
                },
                label = tab.title,
                isActive = isActive,
                onClick = { onTabSelect(tab) }
            )
        }
    }
}

@Composable
private fun ToolRailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) Color.White.copy(alpha = 0.95f)
                    else Color.Black.copy(alpha = 0.55f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color.Black else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = label,
            color = if (isActive) Color.White else Color.White.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// MARK: - Panel Overlay (slides up from bottom, mirrors iOS panelOverlay)

@Composable
private fun PanelOverlay(
    panel: EditTab,
    editState: VideoEditState,
    exoPlayer: ExoPlayer,
    onEditStateChange: (VideoEditState) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color(0xFF1A1A1A).copy(alpha = 0.96f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { /* swallow taps so they don't fall through to the video */ }
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
                    .clickable { onDismiss() }
            )
        }

        // Panel content
        Box(modifier = Modifier.weight(1f)) {
            when (panel) {
                EditTab.TRIM -> VideoTrimmerView(
                    editState = editState,
                    exoPlayer = exoPlayer,
                    onEditStateChange = onEditStateChange
                )
                EditTab.CAPTIONS -> CaptionEditorView(
                    editState = editState,
                    exoPlayer = exoPlayer,
                    onEditStateChange = onEditStateChange
                )
            }
        }
    }
}

// MARK: - Next Button Bar (mirrors iOS bottomBar)

@Composable
private fun NextButtonBar(
    duration: Double,
    isProcessing: Boolean,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Subtle duration chip on the left (small, glassy, matches iOS look)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = formatDuration(duration),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Prominent Next pill — white background, dark text, chevron
        Button(
            onClick = onContinue,
            enabled = !isProcessing,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                disabledContainerColor = Color.White.copy(alpha = 0.4f)
            ),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
            modifier = Modifier.shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(50),
                ambientColor = Color.White.copy(alpha = 0.3f)
            )
        ) {
            Text(
                text = "Next",
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(16.dp)
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
                    color = StitchColors.secondary
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

        if (BuildConfig.DEBUG) { println("📐 VIDEO PROPS: ${finalWidth}x${finalHeight}, ${duration}s, rotation: $rotation") }

    } catch (e: Exception) {
        if (BuildConfig.DEBUG) { println("⚠️ VIDEO PROPS: Failed to load - ${e.message}") }
        onLoaded(0.0, 1080, 1920)
    }
}