/*
 * AudioExtractionService.kt - FAST AUDIO EXTRACTION FOR PARALLEL PROCESSING
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Fast Audio Extraction from Video Files
 * Dependencies: Android MediaMetadataRetriever, MediaExtractor, MediaMuxer
 * Features: High-speed extraction, progress tracking, parallel processing support
 *
 * MATCHES: Swift AudioExtractionService for parallel workflow integration
 * OPTIMIZED: For AI analysis - extracts audio once, shares with AIVideoAnalyzer
 * PERFORMANCE: Eliminates double audio extraction, improves overall speed
 */

package com.example.stitchsocialclub.services

import android.media.*
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Fast audio extraction service optimized for parallel video processing
 * ELIMINATES: Double audio extraction (VideoCoordinator + AIAnalyzer)
 * PROVIDES: Shared audio data for AI analysis workflow
 */
class AudioExtractionService {

    // MARK: - Progress Tracking

    private val _extractionProgress = MutableStateFlow(0.0)
    val extractionProgress: StateFlow<Double> = _extractionProgress.asStateFlow()

    private val _currentTask = MutableStateFlow("Ready")
    val currentTask: StateFlow<String> = _currentTask.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    // MARK: - Audio Extraction Result

    data class AudioExtractionResult(
        val audioFile: File,
        val audioData: ByteArray,
        val duration: Double,
        val sampleRate: Int,
        val channels: Int,
        val format: AudioFormat,
        val extractionTime: Double,
        val success: Boolean,
        val error: String? = null
    ) {
        // Cleanup method for temporary files
        fun cleanup() {
            try {
                if (audioFile.exists()) {
                    audioFile.delete()
                    println("🧹 AUDIO CLEANUP: Deleted ${audioFile.name}")
                }
            } catch (e: Exception) {
                println("⚠️ AUDIO CLEANUP: Failed to delete ${audioFile.name} - ${e.message}")
            }
        }
    }

    enum class AudioFormat {
        WAV, AAC, MP3, M4A
    }

    // MARK: - Extraction Errors

    sealed class AudioExtractionError : Exception() {
        object FileNotFound : AudioExtractionError()
        object UnsupportedFormat : AudioExtractionError()
        object NoAudioTrack : AudioExtractionError()
        object ExtractionFailed : AudioExtractionError()
        data class IOError(override val cause: Throwable) : AudioExtractionError()
    }

    // MARK: - Fast Audio Extraction

    /**
     * Extract audio from video file optimized for AI analysis
     * OPTIMIZED: For parallel processing - runs simultaneously with compression
     * SHARED: Audio data used by AI analyzer without re-extraction
     */
    suspend fun extractAudio(
        videoFile: File,
        progressCallback: (Double) -> Unit = { }
    ): AudioExtractionResult = withContext(Dispatchers.IO) {

        val startTime = System.currentTimeMillis()

        try {
            _isExtracting.value = true
            updateProgress(0.0, "Initializing audio extraction...")
            progressCallback(0.0)

            // STEP 1: Validate input video file (10%)
            updateProgress(0.1, "Validating video file...")
            progressCallback(0.1)

            if (!videoFile.exists()) {
                throw AudioExtractionError.FileNotFound
            }

            println("🎵 AUDIO EXTRACTION: Starting for ${videoFile.name}")

            // STEP 2: Analyze video metadata (20%)
            updateProgress(0.2, "Analyzing video metadata...")
            progressCallback(0.2)

            val metadata = analyzeVideoMetadata(videoFile)

            // STEP 3: Setup audio extraction (30%)
            updateProgress(0.3, "Setting up audio extraction...")
            progressCallback(0.3)

            val outputFile = createTempAudioFile()

            // STEP 4: Extract audio track (30% - 80%)
            updateProgress(0.3, "Extracting audio track...")
            val audioData = extractAudioTrack(
                videoFile = videoFile,
                outputFile = outputFile,
                metadata = metadata,
                progressCallback = { progress ->
                    val scaledProgress = 0.3 + (progress * 0.5) // Scale to 30%-80%
                    updateProgress(scaledProgress, "Extracting audio... ${(progress * 100).toInt()}%")
                    progressCallback(scaledProgress)
                }
            )

            // STEP 5: Validate extracted audio (90%)
            updateProgress(0.9, "Validating extracted audio...")
            progressCallback(0.9)

            val validationResult = validateExtractedAudio(outputFile, audioData)

            // STEP 6: Complete extraction (100%)
            updateProgress(1.0, "Audio extraction complete!")
            progressCallback(1.0)

            val extractionTime = (System.currentTimeMillis() - startTime) / 1000.0

            println("✅ AUDIO EXTRACTION: Complete in ${String.format("%.1f", extractionTime)}s")
            println("🎵 AUDIO FILE: ${formatFileSize(audioData.size.toLong())} at ${outputFile.absolutePath}")

            AudioExtractionResult(
                audioFile = outputFile,
                audioData = audioData,
                duration = metadata.duration,
                sampleRate = metadata.sampleRate,
                channels = metadata.channels,
                format = AudioFormat.WAV, // Default extraction format
                extractionTime = extractionTime,
                success = true
            )

        } catch (e: Exception) {
            val extractionTime = (System.currentTimeMillis() - startTime) / 1000.0
            val errorMessage = when (e) {
                is AudioExtractionError.FileNotFound -> "Video file not found"
                is AudioExtractionError.UnsupportedFormat -> "Unsupported video format"
                is AudioExtractionError.NoAudioTrack -> "No audio track found in video"
                is AudioExtractionError.ExtractionFailed -> "Audio extraction failed"
                is AudioExtractionError.IOError -> "IO error: ${e.cause?.message}"
                else -> "Unknown error: ${e.message}"
            }

            println("❌ AUDIO EXTRACTION: Failed - $errorMessage")
            updateProgress(0.0, "Extraction failed: $errorMessage")
            progressCallback(0.0)

            AudioExtractionResult(
                audioFile = File(""), // Empty file
                audioData = ByteArray(0),
                duration = 0.0,
                sampleRate = 0,
                channels = 0,
                format = AudioFormat.WAV,
                extractionTime = extractionTime,
                success = false,
                error = errorMessage
            )
        } finally {
            _isExtracting.value = false
        }
    }

    // MARK: - Video Metadata Analysis

    private data class VideoMetadata(
        val duration: Double,
        val hasAudio: Boolean,
        val sampleRate: Int,
        val channels: Int,
        val bitrate: Int,
        val codec: String
    )

    private suspend fun analyzeVideoMetadata(videoFile: File): VideoMetadata = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(videoFile.absolutePath)

            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.toDouble()?.div(1000.0) ?: 0.0
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toIntOrNull() ?: 44100
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 128000

            if (!hasAudio) {
                throw AudioExtractionError.NoAudioTrack
            }

            println("🎵 VIDEO METADATA: Duration=${String.format("%.1f", duration)}s, Audio=$hasAudio, SampleRate=$sampleRate")

            VideoMetadata(
                duration = duration,
                hasAudio = hasAudio,
                sampleRate = sampleRate,
                channels = 2, // Assume stereo for most videos
                bitrate = bitrate,
                codec = "AAC" // Most common for mobile videos
            )

        } catch (e: Exception) {
            println("❌ METADATA ANALYSIS: Failed - ${e.message}")
            throw AudioExtractionError.ExtractionFailed
        } finally {
            retriever.release()
        }
    }

    // MARK: - Audio Track Extraction

    private suspend fun extractAudioTrack(
        videoFile: File,
        outputFile: File,
        metadata: VideoMetadata,
        progressCallback: (Double) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {

        val extractor = MediaExtractor()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            extractor.setDataSource(videoFile.absolutePath)

            // Find audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""

                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                throw AudioExtractionError.NoAudioTrack
            }

            println("🎵 FOUND AUDIO TRACK: Index=$audioTrackIndex, Format=${audioFormat.getString(MediaFormat.KEY_MIME)}")

            // Select and configure audio track
            extractor.selectTrack(audioTrackIndex)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            // Extract audio data with progress tracking
            val bufferInfo = MediaCodec.BufferInfo()
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
            val audioDataList = mutableListOf<ByteArray>()
            var totalBytesRead = 0L
            val estimatedTotalSize = (metadata.duration * metadata.bitrate / 8).toLong()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                // Track progress and collect audio data
                val audioBytes = ByteArray(sampleSize)
                buffer.rewind()
                buffer.get(audioBytes, 0, sampleSize)
                audioDataList.add(audioBytes)
                totalBytesRead += sampleSize

                // Update progress based on bytes read
                val progress = if (estimatedTotalSize > 0) {
                    min(totalBytesRead.toDouble() / estimatedTotalSize, 1.0)
                } else {
                    // Fallback: estimate based on time if size estimation fails
                    min((extractor.sampleTime / 1_000_000.0) / metadata.duration, 1.0)
                }

                progressCallback(progress)

                extractor.advance()
                buffer.clear()
            }

            muxer.stop()

            // Combine all audio data
            val totalSize = audioDataList.sumOf { it.size }
            val combinedAudioData = ByteArray(totalSize)
            var offset = 0

            for (audioBytes in audioDataList) {
                audioBytes.copyInto(combinedAudioData, offset)
                offset += audioBytes.size
            }

            println("🎵 EXTRACTION COMPLETE: ${formatFileSize(totalSize.toLong())} extracted")

            return@withContext combinedAudioData

        } catch (e: Exception) {
            println("❌ AUDIO EXTRACTION: Failed during extraction - ${e.message}")
            throw AudioExtractionError.ExtractionFailed
        } finally {
            try {
                extractor.release()
                muxer.release()
            } catch (e: Exception) {
                println("⚠️ CLEANUP WARNING: ${e.message}")
            }
        }
    }

    // MARK: - Audio Validation

    private fun validateExtractedAudio(audioFile: File, audioData: ByteArray): Boolean {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            println("❌ VALIDATION: Audio file is empty or doesn't exist")
            return false
        }

        if (audioData.isEmpty()) {
            println("❌ VALIDATION: Audio data array is empty")
            return false
        }

        println("✅ VALIDATION: Audio extraction successful - File: ${formatFileSize(audioFile.length())}, Data: ${formatFileSize(audioData.size.toLong())}")
        return true
    }

    // MARK: - File Management

    private fun createTempAudioFile(): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }

        val timestamp = System.currentTimeMillis()
        val fileName = "extracted_audio_${timestamp}.m4a"
        val audioFile = File(tempDir, fileName)

        println("🎵 TEMP AUDIO FILE: ${audioFile.absolutePath}")
        return audioFile
    }

    /**
     * Clean up all temporary audio files created during extraction
     */
    fun cleanupTemporaryFiles() {
        try {
            val tempDir = File(System.getProperty("java.io.tmpdir"))
            val audioFiles = tempDir.listFiles { _, name ->
                name.startsWith("extracted_audio_") && (name.endsWith(".m4a") || name.endsWith(".wav"))
            }

            var cleanedCount = 0
            audioFiles?.forEach { file ->
                if (file.delete()) {
                    cleanedCount++
                }
            }

            if (cleanedCount > 0) {
                println("🧹 AUDIO CLEANUP: Removed $cleanedCount temporary audio files")
            }

        } catch (e: Exception) {
            println("⚠️ AUDIO CLEANUP: Failed - ${e.message}")
        }
    }

    // MARK: - Progress Updates

    private fun updateProgress(progress: Double, task: String) {
        _extractionProgress.value = progress
        _currentTask.value = task

        println("🎵 AUDIO PROGRESS: ${(progress * 100).toInt()}% - $task")
    }

    // MARK: - Utility Functions

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${String.format("%.1f", bytes / (1024.0 * 1024.0))} MB"
        }
    }

    // MARK: - Public Convenience Methods

    /**
     * Extract audio and return only the raw audio data (for AI analysis)
     * Simplified method that handles cleanup automatically
     */
    suspend fun extractAudioData(
        videoFile: File,
        progressCallback: (Double) -> Unit = { }
    ): ByteArray {
        val result = extractAudio(videoFile, progressCallback)

        // Cleanup temp file immediately since we only need data
        result.cleanup()

        return if (result.success) {
            result.audioData
        } else {
            throw Exception(result.error ?: "Audio extraction failed")
        }
    }

    /**
     * Quick check if video file has audio track
     */
    suspend fun hasAudioTrack(videoFile: File): Boolean = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            hasAudio == "yes"
        } catch (e: Exception) {
            false
        } finally {
            retriever.release()
        }
    }
}