/*
 * ThreadCollageSelectionView.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Main UI for the Thread Collage feature. Mirrors iOS
 * ThreadCollageSelectionView.swift. Hosts:
 *
 *   • Header with title + running-total budget badge (green / red)
 *   • Main video card (tap to open ClipTrimView)
 *   • Response clip picker (tap to add/remove; tap again to trim)
 *   • Build button that gates on isOverBudget — if over, shows the
 *     "trim X seconds off any clip" alert instead of starting the build
 *   • Full-screen overlay hosting ClipTrimView when a clip is tapped
 *
 * Phase 3 / Series of 5. Phase 4 implements buildCollage() on the
 * service; this view's Build button is the only thing that calls it.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ThreadData
import com.stitchsocial.club.services.CollageState
import com.stitchsocial.club.services.ThreadCollageService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Entry point for the collage flow. The caller provides a pre-loaded
 * ThreadData (parent + child videos) and a dismissal handler. The view
 * holds its own ThreadCollageService instance, scoped to the composition.
 */
@Composable
fun ThreadCollageSelectionView(
    threadData: ThreadData,
    onDismiss: () -> Unit,
    onExportComplete: (android.net.Uri) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { ThreadCollageService() }
    val state by service.state.collectAsState()
    val selectedClips by service.selectedClips.collectAsState()
    val totalDuration by service.totalCollageDuration.collectAsState()

    var trimEditingClipID by remember { mutableStateOf<String?>(null) }
    var showOverBudgetAlert by remember { mutableStateOf(false) }
    var buildError by remember { mutableStateOf<String?>(null) }

    // Set main video on first composition; never re-runs unless the
    // thread changes (which it shouldn't during this flow).
    LaunchedEffect(threadData.id) {
        service.setMainVideo(threadData.parentVideo)
    }

    // Watch for state transitions: pop the exported URI back to the caller
    // when build finishes; surface errors via alert.
    LaunchedEffect(state) {
        when (val s = state) {
            is CollageState.Completed -> onExportComplete(s.uri)
            is CollageState.Failed -> buildError = s.error
            else -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderBar(
                totalDuration = totalDuration,
                contentBudget = service.configuration.contentDuration,
                isOverBudget = service.isOverBudget,
                onClose = {
                    service.cancel()
                    onDismiss()
                },
            )

            // Scrollable body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                MainVideoSection(
                    mainClip = selectedClips.firstOrNull { it.isMainClip },
                    parentVideo = threadData.parentVideo,
                    onTap = { trimEditingClipID = threadData.parentVideo.id },
                )

                ResponsesSection(
                    candidates = threadData.childVideos,
                    selectedClips = selectedClips,
                    responseClipCount = service.responseClipCount,
                    maxResponses = ThreadCollageService.MAX_RESPONSE_CLIPS,
                    onTapVideo = { video ->
                        // If already selected, open trim. Otherwise toggle in.
                        if (service.isSelected(video.id)) {
                            trimEditingClipID = video.id
                        } else {
                            service.toggleResponseVideo(video)
                        }
                    },
                    onLongPressVideo = { video ->
                        // Long-press a selected response to remove it.
                        if (service.isSelected(video.id)) {
                            service.toggleResponseVideo(video)
                        }
                    },
                )

                Spacer(modifier = Modifier.height(60.dp))
            }

            BottomBar(
                canBuild = service.canBuildCollage,
                isOverBudget = service.isOverBudget,
                onBuild = {
                    if (service.isOverBudget) {
                        showOverBudgetAlert = true
                    } else {
                        scope.launch {
                            runCatching { service.buildCollage(context) }
                                .onFailure { buildError = it.message ?: "Unknown error" }
                        }
                    }
                },
            )
        }

        // Full-screen trim overlay
        trimEditingClipID?.let { clipID ->
            val clip = selectedClips.firstOrNull { it.id == clipID }
            if (clip != null) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    ClipTrimView(
                        clip = clip,
                        service = service,
                        onDone = { trimEditingClipID = null },
                        onRemove = if (clip.isMainClip) null else {
                            {
                                service.toggleResponseVideo(clip.videoMetadata)
                                trimEditingClipID = null
                            }
                        },
                    )
                }
            }
        }

        // Build-progress overlay
        when (val s = state) {
            is CollageState.LoadingAssets,
            is CollageState.Composing,
            is CollageState.AddingWatermark,
            is CollageState.Exporting -> {
                BuildProgressOverlay(state = s)
            }
            else -> Unit
        }
    }

    // Over-budget alert
    if (showOverBudgetAlert) {
        val excess = service.excessSeconds
        AlertDialog(
            onDismissRequest = { showOverBudgetAlert = false },
            title = { Text("Collage too long") },
            text = {
                val plural = if (excess == 1) "" else "s"
                Text(
                    "Your collage is $excess second$plural over the 60-second limit. " +
                    "Trim $excess second$plural off any clip to continue."
                )
            },
            confirmButton = {
                TextButton(onClick = { showOverBudgetAlert = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Build-failure alert
    buildError?.let { msg ->
        AlertDialog(
            onDismissRequest = { buildError = null },
            title = { Text("Build Failed") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { buildError = null }) { Text("OK") }
            }
        )
    }
}

// ── Header ──────────────────────────────────────────────────────────────

@Composable
private fun HeaderBar(
    totalDuration: Double,
    contentBudget: Double,
    isOverBudget: Boolean,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Thread Collage",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            // Running total — green / red based on isOverBudget.
            // Drives the "trim X seconds" alert on Build tap.
            val total = totalDuration.roundToInt()
            val max = contentBudget.roundToInt()
            Text(
                text = "${total}s / ${max}s",
                color = if (isOverBudget) Color.Red else Color.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Settings icon placeholder; sheet wired in a future pass.
        IconButton(
            onClick = { /* TODO: settings sheet */ },
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
        ) {
            Icon(Icons.Default.Tune, contentDescription = "Settings", tint = Color.White)
        }
    }
}

// ── Main Video Section ─────────────────────────────────────────────────

@Composable
private fun MainVideoSection(
    mainClip: com.stitchsocial.club.services.CollageClip?,
    parentVideo: CoreVideoMetadata,
    onTap: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Main Video",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .clickable { onTap() },
        ) {
            AsyncImage(
                model = parentVideo.thumbnailURL,
                contentDescription = parentVideo.title,
                modifier = Modifier.fillMaxSize(),
            )

            // Trim badge bottom-left
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.ContentCut,
                    contentDescription = null,
                    tint = Color.Cyan,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                val used = mainClip?.allocatedDuration?.roundToInt() ?: 0
                val total = mainClip?.originalDuration?.roundToInt() ?: 0
                Text(
                    text = "${used}s of ${total}s",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Tap hint top-right
            Text(
                text = "Tap to trim",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// ── Responses Section ──────────────────────────────────────────────────

@Composable
private fun ResponsesSection(
    candidates: List<CoreVideoMetadata>,
    selectedClips: List<com.stitchsocial.club.services.CollageClip>,
    responseClipCount: Int,
    maxResponses: Int,
    onTapVideo: (CoreVideoMetadata) -> Unit,
    onLongPressVideo: (CoreVideoMetadata) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Responses",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$responseClipCount / $maxResponses",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (candidates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No response clips in this thread.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                )
            }
        } else {
            // 2-column grid — capped height so the parent ScrollView still works.
            // The candidates list is bounded (a thread's children) so this is safe.
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 600.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false,
            ) {
                items(candidates, key = { it.id }) { video ->
                    val clip = selectedClips.firstOrNull { it.id == video.id && !it.isMainClip }
                    ResponseClipCard(
                        video = video,
                        selectedClip = clip,
                        onTap = { onTapVideo(video) },
                        onLongPress = { onLongPressVideo(video) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponseClipCard(
    video: CoreVideoMetadata,
    selectedClip: com.stitchsocial.club.services.CollageClip?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val isSelected = selectedClip != null
    Box(
        modifier = Modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onTap() }
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = Color.Cyan,
                        shape = RoundedCornerShape(10.dp)
                    )
                } else Modifier
            ),
    ) {
        AsyncImage(
            model = video.thumbnailURL,
            contentDescription = video.title,
            modifier = Modifier.fillMaxSize(),
        )

        if (isSelected) {
            // Trim badge bottom
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.ContentCut,
                    contentDescription = null,
                    tint = Color.Cyan,
                    modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                val used = selectedClip?.allocatedDuration?.roundToInt() ?: 0
                val total = selectedClip?.originalDuration?.roundToInt() ?: 0
                Text(
                    text = "${used}s of ${total}s",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Selected check top-right
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = Color.Cyan,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
            )
        } else {
            // "Tap to add" affordance
            Icon(
                Icons.Default.AddCircle,
                contentDescription = "Add",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
            )
        }
    }
}

// ── Bottom Bar ─────────────────────────────────────────────────────────

@Composable
private fun BottomBar(
    canBuild: Boolean,
    isOverBudget: Boolean,
    onBuild: () -> Unit,
) {
    val enabled = canBuild
    val visuallyDisabled = !canBuild || isOverBudget

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.95f))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { if (enabled) onBuild() },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (visuallyDisabled) Color.Gray.copy(alpha = 0.4f) else Color.Cyan,
                disabledContainerColor = Color.Gray.copy(alpha = 0.4f),
            ),
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Build Collage",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ── Build Progress Overlay ─────────────────────────────────────────────

@Composable
private fun BuildProgressOverlay(state: CollageState) {
    val label = when (state) {
        is CollageState.LoadingAssets -> "Loading clips…"
        is CollageState.Composing -> "Stitching clips…"
        is CollageState.AddingWatermark -> "Adding watermark…"
        is CollageState.Exporting -> "Exporting ${(state.progress * 100).toInt()}%"
        else -> "Working…"
    }
    val progress: Float? = (state as? CollageState.Exporting)?.progress?.toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = Color.Cyan)
            } else {
                CircularProgressIndicator(color = Color.Cyan)
            }
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
