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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID

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
            
            println("🎬 VIDEO EXPORT: Using mode: ${mode.displayName}")
            
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
            
            println("✅ VIDEO EXPORT: Complete - ${outputUri.lastPathComponent} (mode: ${mode.displayName})")
            
            _isExporting.value = false
            _exportProgress.value = 1.0
            
            ExportResult(videoUri = outputUri, thumbnailUri = thumbnailUri)
            
        } catch (e: Exception) {
            _exportError.value = e.message
            _isExporting.value = false
            println("❌ VIDEO EXPORT: Failed - ${e.message}")
            throw e
        }
    }
    
    // MARK: - Export Mode Detection
    
    private fun determineExportMode(editState: VideoEditState): ExportMode {
        val hasFilter = editState.selectedFilter != null && editState.selectedFilter != VideoFilter.NONE
        val hasCaptions = editState.captions.isNotEmpty()
        val hasTrim = hasActualTrim(editState)
        
        println("🔍 EXPORT MODE CHECK:")
        println("   Has filter: $hasFilter")
        println("   Has captions: $hasCaptions")
        println("   Has trim: $hasTrim")
        
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
        println("📋 EXPORT: Passthrough mode - copying file directly")
        
        _exportProgress.value = 0.2
        
        val outputFile = createTemporaryVideoFile()
        
        // Copy the file directly
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        
        _exportProgress.value = 1.0
        
        println("✅ EXPORT: Passthrough complete - zero quality loss")
        Uri.fromFile(outputFile)
    }
    
    // MARK: - Trim Only Export
    
    private suspend fun trimOnlyExport(
        sourceUri: Uri,
        trimStartMs: Long,
        trimEndMs: Long
    ): Uri = withContext(Dispatchers.IO) {
        println("✂️ EXPORT: Trim-only mode - using MediaMuxer")
        
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
        
        println("✅ EXPORT: Trim-only complete - minimal quality loss")
        Uri.fromFile(outputFile)
    }
    
    // MARK: - Full Process Export
    
    private suspend fun fullProcessExport(editState: VideoEditState): Uri = withContext(Dispatchers.IO) {
        println("🎨 EXPORT: Full process mode - re-encoding with edits")
        
        val compressor = FastVideoCompressor.getInstance(context)
        
        // Use compressor with trim if needed
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
                _exportProgress.value = progress * 0.9  // Reserve 10% for finalization
            }
        }
        
        _exportProgress.value = 0.95
        
        // If we have filters or captions, we would apply them here
        // For now, just return the compressed video
        // TODO: Implement filter and caption rendering
        
        if (editState.hasFilterEdits) {
            println("⚠️ EXPORT: Filter application not yet implemented")
        }
        
        if (editState.hasCaptionEdits) {
            println("⚠️ EXPORT: Caption burning not yet implemented")
        }
        
        _exportProgress.value = 1.0
        
        println("✅ EXPORT: Full process complete")
        result.outputUri
    }
    
    // MARK: - Thumbnail Generation
    
    private suspend fun generateThumbnail(videoUri: Uri, duration: Double): Uri = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        
        try {
            retriever.setDataSource(context, videoUri)
            
            // Generate at 0.5 seconds or 10% in, whichever is smaller
            val thumbnailTimeUs = (minOf(0.5, duration * 0.1) * 1_000_000).toLong()
            
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    thumbnailTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    1080,
                    1920
                )
            } else {
                retriever.getFrameAtTime(
                    thumbnailTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            } ?: throw VideoExportError.ThumbnailGenerationFailed
            
            // Save to file
            val thumbnailFile = createTemporaryImageFile()
            FileOutputStream(thumbnailFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            bitmap.recycle()
            
            Uri.fromFile(thumbnailFile)
            
        } finally {
            retriever.release()
        }
    }
    
    // MARK: - Quality Logging
    
    private fun logQualityComparison(originalUri: Uri, exportedUri: Uri) {
        try {
            val originalSize = getFileSize(originalUri)
            val exportedSize = getFileSize(exportedUri)
            
            val ratio = if (originalSize > 0) exportedSize.toDouble() / originalSize.toDouble() else 1.0
            
            println("📊 EXPORT QUALITY:")
            println("   Original: ${formatFileSize(originalSize)}")
            println("   Exported: ${formatFileSize(exportedSize)}")
            println("   Size Ratio: ${String.format("%.1f%%", ratio * 100)}")
            println("   Mode: ${_exportMode.value.displayName}")
            
            when (_exportMode.value) {
                ExportMode.PASSTHROUGH -> println("   ✅ Zero quality loss (passthrough)")
                ExportMode.TRIM_ONLY -> println("   ✅ Minimal quality loss (muxer copy)")
                else -> println("   ⚠️ Re-encoded (necessary for filters/captions)")
            }
            
        } catch (e: Exception) {
            println("⚠️ EXPORT: Could not compare file sizes")
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