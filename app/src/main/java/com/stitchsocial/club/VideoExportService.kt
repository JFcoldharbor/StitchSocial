/*
 * VideoExportService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services - Video Export & Processing
 * Dependencies: VideoEditState, MediaCodec
 * Features: Apply trim, filters, captions, export with progress
 * PHASE 4 UPDATE: Passthrough mode for unedited videos, better bitrate control
 *
 * Exact translation from iOS VideoExportService.swift
 */

package com.stitchsocial.club

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult as TransformerExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.stitchsocial.club.BuildConfig

/**
 * Handles video export with all edits applied
 * PHASE 4: Added passthrough mode to prevent quality loss when no edits are made
 */
class VideoExportService private constructor(private val context: Context) {
    
    // MARK: - Singleton
    
    companion object {
        @Volatile
        private var instance: VideoExportService? = null
        
        fun getInstance(context: Context): VideoExportService {
            return instance ?: synchronized(this) {
                instance ?: VideoExportService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // MARK: - State
    
    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting
    
    private val _exportProgress = MutableStateFlow(0.0)
    val exportProgress: StateFlow<Double> = _exportProgress
    
    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError
    
    private val _exportMode = MutableStateFlow(ExportMode.UNKNOWN)
    val exportMode: StateFlow<ExportMode> = _exportMode
    
    // MARK: - Export Mode
    
    enum class ExportMode(val displayName: String) {
        UNKNOWN("unknown"),
        PASSTHROUGH("passthrough"),      // No re-encoding, just copy
        TRIM_ONLY("trim_only"),          // Only trim, minimal quality loss
        FULL_PROCESS("full_process")     // Re-encode with filters/captions
    }
    
    // MARK: - Export Result
    
    data class ExportResult(
        val videoUri: Uri,
        val thumbnailUri: Uri
    )
    
    // MARK: - Public Interface
    
    /**
     * Export video with all edits applied
     * PHASE 4: Now detects if edits were made and uses passthrough when possible
     */
    suspend fun exportVideo(editState: VideoEditState): ExportResult = withContext(Dispatchers.IO) {
        _isExporting.value = true
        _exportProgress.value = 0.0
        _exportError.value = null
        _exportMode.value = ExportMode.UNKNOWN
        
        try {
            // Determine export mode based on edits
            val mode = determineExportMode(editState)
            _exportMode.value = mode
            
            if (BuildConfig.DEBUG) { println("🎬 VIDEO EXPORT: Using mode: ${mode.displayName}") }
            
            val outputUri: Uri = when (mode) {
                ExportMode.PASSTHROUGH -> {
                    // No edits - just copy the file (zero quality loss)
                    passthroughExport(editState.videoUri)
                }
                ExportMode.TRIM_ONLY -> {
                    // Only trim - use passthrough preset (minimal quality loss)
                    trimOnlyExport(
                        sourceUri = editState.videoUri,
                        trimStartMs = (editState.trimStartTime * 1000).toLong(),
                        trimEndMs = (editState.trimEndTime * 1000).toLong()
                    )
                }
                ExportMode.FULL_PROCESS, ExportMode.UNKNOWN -> {
                    // Full re-encode with filters/captions
                    fullProcessExport(editState)
                }
            }
            
            // Generate thumbnail from output
            val thumbnailUri = generateThumbnail(outputUri, editState.trimmedDuration)
            
            // Log quality comparison
            logQualityComparison(editState.videoUri, outputUri)
            
            if (BuildConfig.DEBUG) { println("✅ VIDEO EXPORT: Complete - ${outputUri.lastPathComponent} (mode: ${mode.displayName})") }
            
            _isExporting.value = false
            _exportProgress.value = 1.0
            
            ExportResult(videoUri = outputUri, thumbnailUri = thumbnailUri)
            
        } catch (e: Exception) {
            _exportError.value = e.message
            _isExporting.value = false
            if (BuildConfig.DEBUG) { println("❌ VIDEO EXPORT: Failed - ${e.message}") }
            throw e
        }
    }
    
    // MARK: - Export Mode Detection
    
    private fun determineExportMode(editState: VideoEditState): ExportMode {
        val hasFilter = editState.selectedFilter != null && editState.selectedFilter != VideoFilter.NONE
        val hasCaptions = editState.captions.isNotEmpty()
        val hasTrim = hasActualTrim(editState)
        
        if (BuildConfig.DEBUG) { println("🔍 EXPORT MODE CHECK:") }
        if (BuildConfig.DEBUG) { println("   Has filter: $hasFilter") }
        if (BuildConfig.DEBUG) { println("   Has captions: $hasCaptions") }
        if (BuildConfig.DEBUG) { println("   Has trim: $hasTrim") }
        
        // If filters or captions, must do full processing
        if (hasFilter || hasCaptions) {
            return ExportMode.FULL_PROCESS
        }
        
        // If only trim, use trim-only mode
        if (hasTrim) {
            return ExportMode.TRIM_ONLY
        }
        
        // No edits at all - pure passthrough
        return ExportMode.PASSTHROUGH
    }
    
    private fun hasActualTrim(editState: VideoEditState): Boolean {
        // Check if trim start is not at beginning
        if (editState.trimStartTime > 0.1) {
            return true
        }
        
        // Check if trim end is different from video duration
        if (editState.videoDuration > 0 && editState.trimEndTime < editState.videoDuration - 0.1) {
            return true
        }
        
        return false
    }
    
    // MARK: - Passthrough Export
    
    private suspend fun passthroughExport(sourceUri: Uri): Uri = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) { println("📋 EXPORT: Passthrough mode - copying file directly") }
        
        _exportProgress.value = 0.2
        
        val outputFile = createTemporaryVideoFile()
        
        // Copy the file directly
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        
        _exportProgress.value = 1.0
        
        if (BuildConfig.DEBUG) { println("✅ EXPORT: Passthrough complete - zero quality loss") }
        Uri.fromFile(outputFile)
    }
    
    // MARK: - Trim Only Export
    
    private suspend fun trimOnlyExport(
        sourceUri: Uri,
        trimStartMs: Long,
        trimEndMs: Long
    ): Uri = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) { println("✂️ EXPORT: Trim-only mode - using MediaMuxer") }
        
        val outputFile = createTemporaryVideoFile()
        
        val extractor = MediaExtractor()
        extractor.setDataSource(context, sourceUri, null)
        
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        try {
            val trackIndexMap = mutableMapOf<Int, Int>()
            
            // Add all tracks to muxer
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val muxerTrackIndex = muxer.addTrack(format)
                trackIndexMap[i] = muxerTrackIndex
            }
            
            muxer.start()
            
            // Process each track
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            
            val startTimeUs = trimStartMs * 1000
            val endTimeUs = trimEndMs * 1000
            val totalDurationUs = endTimeUs - startTimeUs
            
            for (trackIndex in 0 until extractor.trackCount) {
                extractor.selectTrack(trackIndex)
                extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                
                val muxerTrackIndex = trackIndexMap[trackIndex] ?: continue
                
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    
                    if (bufferInfo.size < 0) break
                    
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs > endTimeUs) break
                    
                    // Adjust presentation time
                    bufferInfo.presentationTimeUs = sampleTimeUs - startTimeUs
                    bufferInfo.flags = extractor.sampleFlags
                    
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    
                    // Update progress
                    val progress = ((sampleTimeUs - startTimeUs).toDouble() / totalDurationUs).coerceIn(0.0, 1.0)
                    _exportProgress.value = progress
                    
                    extractor.advance()
                }
                
                extractor.unselectTrack(trackIndex)
            }
            
            _exportProgress.value = 1.0
            
        } finally {
            extractor.release()
            muxer.stop()
            muxer.release()
        }
        
        if (BuildConfig.DEBUG) { println("✅ EXPORT: Trim-only complete - minimal quality loss") }
        Uri.fromFile(outputFile)
    }
    
    // MARK: - Full Process Export

    private suspend fun fullProcessExport(editState: VideoEditState): Uri = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) { println("🎨 EXPORT: Full process mode") }

        val hasCaptions = editState.captions.isNotEmpty() && editState.captions.any { it.text.isNotBlank() }

        // No captions to burn in → fall back to compressor (with trim if needed).
        // Filter rendering is intentionally NOT implemented (broken on iOS too,
        // UI hidden from Android picker — see VideoEditState.EditTab).
        if (!hasCaptions) {
            return@withContext compressOnly(editState)
        }

        // Captions present → use AndroidX media3 Transformer with a BitmapOverlay
        // per caption, time-bound to its [startTime, endTime] window. This bakes
        // the caption pixels into the MP4 frame-by-frame and is far simpler than
        // rolling our own GL pipeline.
        return@withContext transformWithCaptions(editState)
    }

    private suspend fun compressOnly(editState: VideoEditState): Uri = withContext(Dispatchers.IO) {
        val compressor = FastVideoCompressor.getInstance(context)
        val result = if (editState.hasTrimEdits) {
            compressor.compressWithTrim(
                sourceUri = editState.videoUri,
                startTimeMs = (editState.trimStartTime * 1000).toLong(),
                endTimeMs = (editState.trimEndTime * 1000).toLong(),
                targetSizeMB = 50.0
            )
        } else {
            compressor.compress(
                sourceUri = editState.videoUri,
                targetSizeMB = 50.0,
                preserveResolution = true
            ) { progress ->
                _exportProgress.value = progress * 0.9
            }
        }
        _exportProgress.value = 1.0
        if (BuildConfig.DEBUG) { println("✅ EXPORT: Compress-only complete") }
        result.outputUri
    }

    @OptIn(UnstableApi::class)
    private suspend fun transformWithCaptions(editState: VideoEditState): Uri = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) { println("🎨 EXPORT: Transformer with ${editState.captions.size} caption overlay(s)") }

        // Resolve the rendered video size so caption bitmaps scale correctly.
        // VideoEditState.videoSize is the displayed (post-rotation) size, so
        // it can be used directly without re-applying preferredTransform.
        val width = editState.videoSize.width.toInt().coerceAtLeast(1)
        val height = editState.videoSize.height.toInt().coerceAtLeast(1)

        val overlays = CaptionBurnIn.buildOverlays(
            captions = editState.captions,
            videoWidthPx = width,
            videoHeightPx = height
        )
        if (overlays.isEmpty()) {
            return@withContext compressOnly(editState)
        }

        val overlayEffect = OverlayEffect(ImmutableList.copyOf(overlays))
        val effects = Effects(/* audioProcessors */ emptyList(), /* videoEffects */ listOf<Effect>(overlayEffect))

        val mediaItemBuilder = MediaItem.Builder().setUri(editState.videoUri)
        if (editState.hasTrimEdits) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((editState.trimStartTime * 1000).toLong())
                    .setEndPositionMs((editState.trimEndTime * 1000).toLong())
                    .build()
            )
        }
        val editedMediaItem = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(effects)
            .setRemoveAudio(false)
            .build()

        val outputFile = createTemporaryVideoFile()

        // Run Transformer on the main looper (its requirement) and bridge the
        // listener callbacks into our coroutine via suspendCancellableCoroutine.
        suspendCancellableCoroutine<Uri> { continuation ->
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: TransformerExportResult) {
                            _exportProgress.value = 1.0
                            if (BuildConfig.DEBUG) { println("✅ EXPORT: Transformer complete (${outputFile.length() / 1024} KB)") }
                            if (continuation.isActive) {
                                continuation.resume(Uri.fromFile(outputFile))
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: TransformerExportResult,
                            exportException: ExportException
                        ) {
                            if (BuildConfig.DEBUG) { println("❌ EXPORT: Transformer failed — ${exportException.message}") }
                            if (continuation.isActive) {
                                continuation.resumeWithException(exportException)
                            }
                        }
                    })
                    .build()

                continuation.invokeOnCancellation {
                    mainHandler.post { transformer.cancel() }
                }

                transformer.start(editedMediaItem, outputFile.absolutePath)

                // Coarse progress polling — Transformer doesn't expose a
                // continuous progress stream, but getProgress() can be polled.
                pollTransformerProgress(transformer, mainHandler, continuation)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun pollTransformerProgress(
        transformer: Transformer,
        handler: android.os.Handler,
        continuation: kotlinx.coroutines.CancellableContinuation<Uri>
    ) {
        val holder = androidx.media3.transformer.ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                if (!continuation.isActive) return
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _exportProgress.value = (holder.progress / 100.0).coerceIn(0.0, 0.99)
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    handler.postDelayed(this, 200)
                }
            }
        }
        handler.postDelayed(tick, 200)
    }
    
    // MARK: - Thumbnail Generation
    
    private suspend fun generateThumbnail(videoUri: Uri, duration: Double): Uri = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, videoUri)

            // Sample 1.0s, 25%, 50% — pick the brightest frame so we don't
            // pin a black poster from the camera's autoexposure window.
            val candidatesUs = mutableListOf<Long>()
            val durationUs = (duration * 1_000_000).toLong()
            if (durationUs > 0) {
                val oneSecond = 1_000_000L
                val quarter = durationUs / 4
                val mid = durationUs / 2
                candidatesUs.add(if (durationUs > oneSecond * 2) oneSecond else mid)
                if (quarter !in candidatesUs && quarter > 0) candidatesUs.add(quarter)
                if (mid !in candidatesUs && mid > 0) candidatesUs.add(mid)
            } else {
                candidatesUs.add(1_000_000L)
            }

            var best: Bitmap? = null
            var bestBrightness = -1.0

            for (sampleUs in candidatesUs) {
                val frame: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        sampleUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        1080, 1920
                    )
                } else {
                    retriever.getFrameAtTime(sampleUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                if (frame == null) continue
                val brightness = thumbnailLuminance(frame)
                if (brightness > bestBrightness) {
                    best?.recycle()
                    best = frame
                    bestBrightness = brightness
                } else {
                    frame.recycle()
                }
            }

            val chosen = best ?: throw VideoExportError.ThumbnailGenerationFailed

            val thumbnailFile = createTemporaryImageFile()
            FileOutputStream(thumbnailFile).use { output ->
                chosen.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            chosen.recycle()

            Uri.fromFile(thumbnailFile)

        } finally {
            retriever.release()
        }
    }

    /** Cheap average-luminance estimate (0..255). Downsamples to 16×16. */
    private fun thumbnailLuminance(bitmap: Bitmap): Double {
        val w = 16
        val h = 16
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled != bitmap) scaled.recycle()
        var sum = 0L
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            sum += r * 76L + g * 150L + b * 29L
        }
        return sum.toDouble() / (pixels.size.toDouble() * 255.0)
    }
    
    // MARK: - Quality Logging
    
    private fun logQualityComparison(originalUri: Uri, exportedUri: Uri) {
        try {
            val originalSize = getFileSize(originalUri)
            val exportedSize = getFileSize(exportedUri)
            
            val ratio = if (originalSize > 0) exportedSize.toDouble() / originalSize.toDouble() else 1.0
            
            if (BuildConfig.DEBUG) { println("📊 EXPORT QUALITY:") }
            if (BuildConfig.DEBUG) { println("   Original: ${formatFileSize(originalSize)}") }
            if (BuildConfig.DEBUG) { println("   Exported: ${formatFileSize(exportedSize)}") }
            if (BuildConfig.DEBUG) { println("   Size Ratio: ${String.format("%.1f%%", ratio * 100)}") }
            if (BuildConfig.DEBUG) { println("   Mode: ${_exportMode.value.displayName}") }
            
            when (_exportMode.value) {
                ExportMode.PASSTHROUGH -> println("   ✅ Zero quality loss (passthrough)")
                ExportMode.TRIM_ONLY -> println("   ✅ Minimal quality loss (muxer copy)")
                else -> println("   ⚠️ Re-encoded (necessary for filters/captions)")
            }
            
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("⚠️ EXPORT: Could not compare file sizes") }
        }
    }
    
    // MARK: - Helper Methods
    
    private fun getFileSize(uri: Uri): Long {
        return context.contentResolver.openFileDescriptor(uri, "r")?.use {
            it.statSize
        } ?: 0L
    }
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
    
    private fun createTemporaryVideoFile(): File {
        val cacheDir = context.cacheDir
        return File(cacheDir, "processed_${UUID.randomUUID()}.mp4")
    }
    
    private fun createTemporaryImageFile(): File {
        val cacheDir = context.cacheDir
        return File(cacheDir, "thumbnail_${UUID.randomUUID()}.jpg")
    }
}

// MARK: - URI Extension

private val Uri.lastPathComponent: String
    get() = lastPathSegment ?: "unknown"

// MARK: - Errors

sealed class VideoExportError : Exception() {
    object NoVideoTrack : VideoExportError()
    object ExportSessionCreationFailed : VideoExportError()
    data class ExportFailed(override val message: String) : VideoExportError()
    object ExportCancelled : VideoExportError()
    object ThumbnailGenerationFailed : VideoExportError()
}