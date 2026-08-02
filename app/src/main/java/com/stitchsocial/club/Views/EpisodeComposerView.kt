package com.stitchsocial.club.views

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.services.EpisodeFinalizeService
import kotlinx.coroutines.launch
import java.util.UUID

private object COMP {
    val bg = Color(0xFF0B0B0D)
    val card = Color(0xFF141418)
    val pink = Color(0xFFE91E63)
    val cyan = Color(0xFF22D3EE)
    val hairline = Color(0x1FFFFFFF)
}

private enum class ComposerStep(val label: String) {
    VIDEO("1/3 VIDEO"), CUTS("2/3 CUTS"), DETAILS("3/3 RELEASE")
}

/** One cut, before it becomes an upload. */
private data class EditorSegment(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var startMs: Long,
    var endMs: Long,
    /** true = the creator placed this cut, false = auto-split. */
    var locked: Boolean = false
)

/**
 * Episode Composer — surface 2 of the creator flow (iOS parity with
 * EpisodeComposerView, design_handoff_show_flow §2).
 *
 * Three clear steps instead of the old editor's four competing save buttons:
 * pick the video, place the cuts, set the release. Nothing goes live until the
 * creator says so on the last step.
 *
 * The cut step is the reason this exists. Splitting used to mean reading flat
 * coloured blocks that told you how many parts you had and nothing about what
 * was in them; the filmstrip shows real frames, so a cut can be placed where
 * something actually changes.
 */
@Composable
fun EpisodeComposerView(
    showId: String,
    seasonId: String,
    episodeNumber: Int,
    creatorID: String,
    creatorName: String,
    onDismiss: () -> Unit,
    onPublished: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(ComposerStep.VIDEO) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var durationMs by remember { mutableStateOf(0L) }
    var segments by remember { mutableStateOf<List<EditorSegment>>(emptyList()) }
    var frames by remember { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }

    var title by remember { mutableStateOf("Episode $episodeNumber") }
    var description by remember { mutableStateOf("") }
    var isFree by remember { mutableStateOf(false) }
    var freePreview by remember { mutableStateOf("1") }

    var phase by remember { mutableStateOf<EpisodeFinalizeService.Phase?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        sourceUri = uri
        // Duration drives every cut position, so it's read before anything else.
        val retriever = MediaMetadataRetriever()
        durationMs = runCatching {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        }.getOrDefault(0L)
        runCatching { retriever.release() }

        // Start as ONE segment covering the whole video. Auto-splitting on
        // import guesses at structure the creator hasn't decided yet; one part
        // that they cut is honest about what the editor knows.
        segments = listOf(EditorSegment(title = "Part 1", startMs = 0, endMs = durationMs))
        step = ComposerStep.CUTS
    }

    LaunchedEffect(sourceUri, durationMs) {
        val uri = sourceUri ?: return@LaunchedEffect
        frames = FilmstripGenerator().load(context, uri, durationMs)
    }

    Column(Modifier.fillMaxSize().background(COMP.bg)) {

        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 44.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Close, "Close", tint = Color.White,
                modifier = Modifier.size(22.dp).clickable { onDismiss() }
            )
            Spacer(Modifier.width(12.dp))
            Text(step.label, color = COMP.cyan, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        }

        when (step) {
            ComposerStep.VIDEO -> VideoStep { picker.launch(PickVisualMediaRequest()) }

            ComposerStep.CUTS -> CutsStep(
                frames = frames,
                durationMs = durationMs,
                segments = segments,
                onSegmentsChanged = { segments = it },
                onBack = { step = ComposerStep.VIDEO },
                onNext = { step = ComposerStep.DETAILS }
            )

            ComposerStep.DETAILS -> DetailsStep(
                title = title, onTitle = { title = it },
                description = description, onDescription = { description = it },
                isFree = isFree, onIsFree = { isFree = it },
                freePreview = freePreview, onFreePreview = { freePreview = it },
                segmentCount = segments.size,
                phase = phase,
                error = error,
                onBack = { step = ComposerStep.CUTS },
                onPublish = { publish ->
                    val uri = sourceUri ?: return@DetailsStep
                    scope.launch {
                        error = null
                        val episodeID = UUID.randomUUID().toString()
                        val result = EpisodeFinalizeService(context).finalize(
                            EpisodeFinalizeService.Input(
                                episodeID = episodeID,
                                sourceUri = uri,
                                segments = segments.map {
                                    EpisodeFinalizeService.Segment(
                                        title = it.title,
                                        startMs = it.startMs,
                                        endMs = it.endMs,
                                        locked = it.locked
                                    )
                                },
                                title = title,
                                description = description,
                                creatorID = creatorID,
                                creatorName = creatorName,
                                coverImageURL = null,
                                contentType = "series",
                                showId = showId,
                                seasonId = seasonId,
                                isFree = isFree,
                                freeSegmentCount = if (isFree) segments.size
                                                   else freePreview.toIntOrNull() ?: 0,
                                status = if (publish) "published" else "draft",
                                totalDuration = durationMs / 1000.0
                            )
                        ) { phase = it }

                        result.onSuccess { onPublished(it) }
                            .onFailure { error = it.message ?: "Publish failed" }
                    }
                }
            )
        }
    }
}

@Composable
private fun VideoStep(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Pick your video", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "One recording. You'll cut it into parts on the next step.",
            color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onPick,
            colors = ButtonDefaults.buttonColors(containerColor = COMP.pink),
            shape = RoundedCornerShape(24.dp)
        ) { Text("Choose from library", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun CutsStep(
    frames: List<android.graphics.Bitmap>,
    durationMs: Long,
    segments: List<EditorSegment>,
    onSegmentsChanged: (List<EditorSegment>) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    // Boundaries are derived from the segments rather than stored alongside
    // them — two sources for the same truth is how a dragged cut ends up
    // disagreeing with the part list.
    val boundaries = segments.dropLast(1).map { it.endMs }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Place your cuts", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Drag a line to move a cut. Tap the video to add one.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))

            SegmentFilmstripLane(
                frames = frames,
                durationMs = durationMs,
                boundariesMs = boundaries,
                onBoundaryMoved = { index, newMs ->
                    val updated = segments.toMutableList()
                    // A cut can't cross its neighbours, or a segment inverts and
                    // the export range becomes negative.
                    val lower = updated[index].startMs + 500
                    val upper = updated[index + 1].endMs - 500
                    if (lower >= upper) return@SegmentFilmstripLane
                    val clamped = newMs.coerceIn(lower, upper)
                    updated[index] = updated[index].copy(endMs = clamped, locked = true)
                    updated[index + 1] = updated[index + 1].copy(startMs = clamped, locked = true)
                    onSegmentsChanged(updated)
                }
            )

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    // Split the LONGEST part — splitting the last one produces
                    // slivers once you've already cut a few times.
                    val idx = segments.indices.maxByOrNull { segments[it].endMs - segments[it].startMs }
                        ?: return@TextButton
                    val seg = segments[idx]
                    val mid = (seg.startMs + seg.endMs) / 2
                    if (mid - seg.startMs < 1000 || seg.endMs - mid < 1000) return@TextButton
                    val updated = segments.toMutableList()
                    updated[idx] = seg.copy(endMs = mid)
                    updated.add(idx + 1, EditorSegment(
                        title = "Part ${segments.size + 1}", startMs = mid, endMs = seg.endMs
                    ))
                    onSegmentsChanged(updated.mapIndexed { i, s -> s.copy(title = "Part ${i + 1}") })
                }) { Text("Add cut", color = COMP.cyan) }

                TextButton(onClick = {
                    onSegmentsChanged(
                        listOf(EditorSegment(title = "Part 1", startMs = 0, endMs = durationMs))
                    )
                }) { Text("Start over", color = Color.White.copy(alpha = 0.5f)) }
            }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            itemsIndexed(segments, key = { _, s -> s.id }) { i, seg ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(COMP.card, RoundedCornerShape(8.dp))
                        .border(0.5.dp, COMP.hairline, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = seg.title,
                        onValueChange = { newTitle ->
                            val updated = segments.toMutableList()
                            updated[i] = seg.copy(title = newTitle)
                            onSegmentsChanged(updated)
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = COMP.pink,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "%.1fs".format((seg.endMs - seg.startMs) / 1000.0),
                        color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack) { Text("Back", color = Color.White.copy(alpha = 0.6f)) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onNext,
                enabled = segments.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = COMP.pink),
                shape = RoundedCornerShape(22.dp)
            ) { Text("Next", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun DetailsStep(
    title: String, onTitle: (String) -> Unit,
    description: String, onDescription: (String) -> Unit,
    isFree: Boolean, onIsFree: (Boolean) -> Unit,
    freePreview: String, onFreePreview: (String) -> Unit,
    segmentCount: Int,
    phase: EpisodeFinalizeService.Phase?,
    error: String?,
    onBack: () -> Unit,
    onPublish: (Boolean) -> Unit
) {
    val busy = phase is EpisodeFinalizeService.Phase.Splitting ||
               phase is EpisodeFinalizeService.Phase.Uploading ||
               phase is EpisodeFinalizeService.Phase.Saving

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Release", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = title, onValueChange = onTitle, label = { Text("Episode title") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = COMP.pink
            )
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = description, onValueChange = onDescription, label = { Text("Description") },
            modifier = Modifier.fillMaxWidth().height(100.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = COMP.pink
            )
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = isFree, onCheckedChange = onIsFree)
            Spacer(Modifier.width(10.dp))
            Text("Free episode", color = Color.White, fontSize = 14.sp)
        }

        if (!isFree) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = freePreview,
                onValueChange = { onFreePreview(it.filter(Char::isDigit)) },
                label = { Text("Free preview parts") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = COMP.pink
                )
            )
            Text(
                "Anyone can watch the first ${freePreview.toIntOrNull() ?: 0} of $segmentCount parts.",
                color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        // Determinate where possible — an indeterminate spinner on a multi-minute
        // upload is how a creator concludes the app hung and kills it.
        phase?.let { p ->
            val label = when (p) {
                is EpisodeFinalizeService.Phase.Splitting -> "Cutting part ${p.index} of ${p.total}"
                is EpisodeFinalizeService.Phase.Uploading -> "Uploading part ${p.index} of ${p.total}"
                EpisodeFinalizeService.Phase.Saving -> "Saving"
                is EpisodeFinalizeService.Phase.Done -> "Published ${p.segments} parts"
                is EpisodeFinalizeService.Phase.Failed -> p.message
            }
            Text(label, color = COMP.cyan, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = COMP.pink)
            Spacer(Modifier.height(12.dp))
        }

        error?.let {
            Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack, enabled = !busy) {
                Text("Back", color = Color.White.copy(alpha = 0.6f))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onPublish(false) }, enabled = !busy) {
                Text("Save draft", color = Color.White.copy(alpha = 0.7f))
            }
            Button(
                onClick = { onPublish(true) },
                enabled = !busy && title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = COMP.pink),
                shape = RoundedCornerShape(22.dp)
            ) { Text("Publish", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}
