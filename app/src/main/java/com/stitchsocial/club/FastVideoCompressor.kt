/*
 * FastVideoCompressor.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services - CapCut-Style Fast Video Compression
 * Uses MediaCodec with hardware acceleration
 * Target: 150MB → 30MB in ~5-10 seconds
 *
 * Key Features:
 * 1. Hardware encoder access via MediaCodec
 * 2. Real-time bitrate control for target file size
 * 3. Single-pass encoding (faster than 2-pass)
 * 4. HEVC (H.265) for 40% better compression than H.264
 *
 * Exact translation from iOS FastVideoCompressor.swift
 */

package com.stitchsocial.club

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * CapCut-style fast video compression with hardware acceleration
 * Compresses 150MB → 30MB in ~5-10 seconds using HEVC + MediaCodec
 */
class FastVideoCompressor private constructor(private val context: Context) {
    
    // MARK: - Singleton
    
    companion object {
        @Volatile
        private var instance: FastVideoCompressor? = null
        
        fun getInstance(context: Context): FastVideoCompressor {
            return instance ?: synchronized(this) {
                instance ?: FastVideoCompressor(context.applicationContext).also { instance = it }
            }
        }
        
        /** Maximum file size allowed (100MB upload limit) */
        const val MAX_UPLOAD_SIZE: Long = 100 * 1024 * 1024
    }
    
    // MARK: - State
    
    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing
    
    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress
    
    private val _currentPhase = MutableStateFlow(CompressionPhase.IDLE)
    val currentPhase: StateFlow<CompressionPhase> = _currentPhase
    
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError
    
    // MARK: - Configuration
    
    /** Target file size in bytes (default 50MB for safe upload margin) */
    var targetFileSizeBytes: Long = 50 * 1024 * 1024
    
    /** Minimum bitrate floor (prevents unwatchable quality) */
    private val minBitrate: Int = 800_000  // 800 kbps
    
    /** Maximum bitrate ceiling */
    private val maxBitrate: Int = 8_000_000  // 8 Mbps
    
    // MARK: - Compression Phase
    
    enum class CompressionPhase(val displayName: String) {
        IDLE("Ready"),
        ANALYZING("Analyzing video..."),
        COMPRESSING("Compressing..."),
        FINALIZING("Finalizing..."),
        COMPLETE("Complete"),
        FAILED("Failed")
    }
    
    // MARK: - Compression Result
    
    data class CompressionResult(
        val outputUri: Uri,
        val originalSize: Long,
        val compressedSize: Long,
        val compressionRatio: Double,
        val duration: Double,
        val processingTimeMs: Long,
        val codec: String,
        val resolution: VideoSize,
        val bitrate: Int
    )
    
    // MARK: - Video Info
    
    private data class VideoInfo(
        val duration: Double,
        val fileSize: Long,
        val resolution: VideoSize,
        val bitrate: Double,
        val frameRate: Double,
        val hasAudio: Boolean
    )
    
    // MARK: - Compression Settings
    
    private data class CompressionSettings(
        val resolution: VideoSize,
        val bitrate: Int,
        val frameRate: Int,
        val keyFrameInterval: Int,
        val useHEVC: Boolean,
        val codec: String
    )
    
    // MARK: - Public Interface
    
    /**
     * Compress video to target size with hardware acceleration
     */
    suspend fun compress(
        sourceUri: Uri,
        targetSizeMB: Double = 50.0,
        preserveResolution: Boolean = false,
        trimStartMs: Long? = null,
        trimEndMs: Long? = null,
        progressCallback: ((Double) -> Unit)? = null
    ): CompressionResult = withContext(Dispatchers.IO) {
        
        val startTime = System.currentTimeMillis()
        
        _isCompressing.value = true
        _progress.value = 0.0
        _currentPhase.value = CompressionPhase.ANALYZING
        _lastError.value = null
        
        try {
            // Step 1: Analyze source video
            val sourceInfo = analyzeVideo(sourceUri)
            val targetBytes = (targetSizeMB * 1024 * 1024).toLong()
            
            println("🎬 FAST COMPRESS: Source ${formatBytes(sourceInfo.fileSize)} → Target ${formatBytes(targetBytes)}")
            println("🎬 FAST COMPRESS: Duration ${String.format("%.1f", sourceInfo.duration)}s, Resolution ${sourceInfo.resolution.width.toInt()}x${sourceInfo.resolution.height.toInt()}")
            
            // Step 2: Check if compression is needed
            if (sourceInfo.fileSize <= targetBytes) {
                println("✅ FAST COMPRESS: Already under target, copying file")
                val outputUri = copyToOutput(sourceUri)
                
                _isCompressing.value = false
                _currentPhase.value = CompressionPhase.COMPLETE
                _progress.value = 1.0
                
                return@withContext CompressionResult(
                    outputUri = outputUri,
                    originalSize = sourceInfo.fileSize,
                    compressedSize = sourceInfo.fileSize,
                    compressionRatio = 1.0,
                    duration = sourceInfo.duration,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    codec = "passthrough",
                    resolution = sourceInfo.resolution,
                    bitrate = sourceInfo.bitrate.toInt()
                )
            }
            
            // Step 3: Calculate optimal settings
            _currentPhase.value = CompressionPhase.COMPRESSING
            val settings = calculateOptimalSettings(
                sourceInfo = sourceInfo,
                targetBytes = targetBytes,
                preserveResolution = preserveResolution
            )
            
            println("🎬 FAST COMPRESS: Using ${settings.codec} @ ${settings.bitrate / 1000}kbps, ${settings.resolution.width.toInt()}x${settings.resolution.height.toInt()}")
            
            // Step 4: Perform hardware-accelerated compression
            val outputUri = performHardwareCompression(
                sourceUri = sourceUri,
                sourceInfo = sourceInfo,
                settings = settings,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs
            ) { progress ->
                _progress.value = progress
                progressCallback?.invoke(progress)
            }
            
            // Step 5: Verify output
            _currentPhase.value = CompressionPhase.FINALIZING
            val outputSize = getFileSize(outputUri)
            val processingTime = System.currentTimeMillis() - startTime
            
            _currentPhase.value = CompressionPhase.COMPLETE
            _progress.value = 1.0
            _isCompressing.value = false
            
            val result = CompressionResult(
                outputUri = outputUri,
                originalSize = sourceInfo.fileSize,
                compressedSize = outputSize,
                compressionRatio = sourceInfo.fileSize.toDouble() / outputSize.toDouble(),
                duration = sourceInfo.duration,
                processingTimeMs = processingTime,
                codec = settings.codec,
                resolution = settings.resolution,
                bitrate = settings.bitrate
            )
            
            println("✅ FAST COMPRESS: ${formatBytes(sourceInfo.fileSize)} → ${formatBytes(outputSize)} in ${processingTime}ms")
            println("✅ FAST COMPRESS: ${String.format("%.1f", result.compressionRatio)}x compression ratio")
            
            return@withContext result
            
        } catch (e: Exception) {
            _currentPhase.value = CompressionPhase.FAILED
            _lastError.value = e.message
            _isCompressing.value = false
            println("❌ FAST COMPRESS: ${e.message}")
            throw e
        }
    }
    
    /**
     * Quick compress for immediate use (optimized for speed over size)
     */
    suspend fun quickCompress(sourceUri: Uri): Uri {
        val result = compress(
            sourceUri = sourceUri,
            targetSizeMB = 80.0,  // Higher target = faster
            preserveResolution = false
        )
        return result.outputUri
    }
    
    /**
     * Compress with trim in single pass (most efficient)
     */
    suspend fun compressWithTrim(
        sourceUri: Uri,
        startTimeMs: Long,
        endTimeMs: Long,
        targetSizeMB: Double = 50.0
    ): CompressionResult {
        return compress(
            sourceUri = sourceUri,
            targetSizeMB = targetSizeMB,
            preserveResolution = false,
            trimStartMs = startTimeMs,
            trimEndMs = endTimeMs
        )
    }
    
    /**
     * Check if video needs compression
     */
    fun needsCompression(uri: Uri, maxSizeMB: Double = 100.0): Boolean {
        return try {
            val size = getFileSize(uri)
            size > (maxSizeMB * 1024 * 1024).toLong()
        } catch (e: Exception) {
            true
        }
    }
    
    // MARK: - Video Analysis
    
    private fun analyzeVideo(uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        
        return try {
            retriever.setDataSource(context, uri)
            
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val duration = durationMs / 1000.0
            
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 1080f
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 1920f
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            
            val frameRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.toDouble() ?: 30.0
            } else {
                30.0
            }
            
            val fileSize = getFileSize(uri)
            val bitrate = if (duration > 0) (fileSize * 8.0 / duration) else 0.0
            
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            
            // Apply rotation
            val resolution = if (rotation == 90 || rotation == 270) {
                VideoSize(height, width)
            } else {
                VideoSize(width, height)
            }
            
            VideoInfo(
                duration = duration,
                fileSize = fileSize,
                resolution = resolution,
                bitrate = bitrate,
                frameRate = frameRate,
                hasAudio = hasAudio
            )
            
        } finally {
            retriever.release()
        }
    }
    
    // MARK: - Settings Calculation
    
    private fun calculateOptimalSettings(
        sourceInfo: VideoInfo,
        targetBytes: Long,
        preserveResolution: Boolean
    ): CompressionSettings {
        
        // Calculate target bitrate based on file size and duration
        val durationWithMargin = sourceInfo.duration * 1.1  // 10% margin
        val audioBitrate = if (sourceInfo.hasAudio) 128_000 else 0
        val targetTotalBitrate = ((targetBytes * 8.0) / durationWithMargin).toInt()
        var targetVideoBitrate = targetTotalBitrate - audioBitrate
        
        // Clamp to reasonable range
        targetVideoBitrate = targetVideoBitrate.coerceIn(minBitrate, maxBitrate)
        
        // Determine resolution scaling
        var targetResolution = sourceInfo.resolution
        
        if (!preserveResolution) {
            // Scale down for very high bitrate requirements
            val compressionRatio = sourceInfo.fileSize.toDouble() / targetBytes.toDouble()
            
            targetResolution = when {
                compressionRatio > 5.0 -> scaleResolution(sourceInfo.resolution, 0.5f)
                compressionRatio > 3.0 -> scaleResolution(sourceInfo.resolution, 0.7f)
                compressionRatio > 2.0 -> scaleResolution(sourceInfo.resolution, 0.85f)
                else -> sourceInfo.resolution
            }
            
            // Ensure minimum resolution
            targetResolution = VideoSize(
                max(480f, targetResolution.width),
                max(480f, targetResolution.height)
            )
            
            // Ensure dimensions are even
            targetResolution = VideoSize(
                (targetResolution.width.toInt() and 0x7FFFFFFE).toFloat(),
                (targetResolution.height.toInt() and 0x7FFFFFFE).toFloat()
            )
        }
        
        // Check HEVC support
        val useHEVC = isHEVCEncoderAvailable()
        val codec = if (useHEVC) "video/hevc" else "video/avc"
        
        // Calculate frame rate
        val targetFrameRate = min(30, sourceInfo.frameRate.toInt())
        
        return CompressionSettings(
            resolution = targetResolution,
            bitrate = targetVideoBitrate,
            frameRate = targetFrameRate,
            keyFrameInterval = 1,  // 1 second
            useHEVC = useHEVC,
            codec = codec
        )
    }
    
    private fun scaleResolution(resolution: VideoSize, scale: Float): VideoSize {
        return VideoSize(
            resolution.width * scale,
            resolution.height * scale
        )
    }
    
    private fun isHEVCEncoderAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
            }
        } else {
            false
        }
    }
    
    // MARK: - Hardware Compression
    
    private suspend fun performHardwareCompression(
        sourceUri: Uri,
        sourceInfo: VideoInfo,
        settings: CompressionSettings,
        trimStartMs: Long?,
        trimEndMs: Long?,
        progressCallback: (Double) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        
        val outputFile = createOutputFile()
        
        val extractor = MediaExtractor()
        extractor.setDataSource(context, sourceUri, null)
        
        // Find video and audio tracks
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            
            if (mime.startsWith("video/") && videoTrackIndex == -1) {
                videoTrackIndex = i
            } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                audioTrackIndex = i
            }
        }
        
        if (videoTrackIndex == -1) {
            throw CompressionError.NoVideoTrack
        }
        
        // Create muxer
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        try {
            // Transcode video
            transcodeVideo(
                extractor = extractor,
                muxer = muxer,
                videoTrackIndex = videoTrackIndex,
                settings = settings,
                sourceInfo = sourceInfo,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                progressCallback = progressCallback
            )
            
        } finally {
            extractor.release()
            muxer.stop()
            muxer.release()
        }
        
        Uri.fromFile(outputFile)
    }
    
    private fun transcodeVideo(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        videoTrackIndex: Int,
        settings: CompressionSettings,
        sourceInfo: VideoInfo,
        trimStartMs: Long?,
        trimEndMs: Long?,
        progressCallback: (Double) -> Unit
    ) {
        extractor.selectTrack(videoTrackIndex)
        val inputFormat = extractor.getTrackFormat(videoTrackIndex)
        
        // Set up start/end times
        val startTimeUs = (trimStartMs ?: 0) * 1000
        val endTimeUs = (trimEndMs ?: (sourceInfo.duration * 1000).toLong()) * 1000
        
        if (startTimeUs > 0) {
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }
        
        // Create encoder format
        val outputFormat = MediaFormat.createVideoFormat(
            settings.codec,
            settings.resolution.width.toInt(),
            settings.resolution.height.toInt()
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.keyFrameInterval)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        
        // Create encoder
        val encoder = MediaCodec.createEncoderByType(settings.codec)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        
        val inputSurface = encoder.createInputSurface()
        encoder.start()
        
        // Create decoder
        val decoderMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
        val decoder = MediaCodec.createDecoderByType(decoderMime)
        decoder.configure(inputFormat, inputSurface, null, 0)
        decoder.start()
        
        // Muxer track
        var muxerTrackIndex = -1
        var muxerStarted = false
        
        val bufferInfo = MediaCodec.BufferInfo()
        val inputBuffer = ByteBuffer.allocate(1024 * 1024)
        
        var isDecoderDone = false
        var isEncoderDone = false
        
        val totalDurationUs = endTimeUs - startTimeUs
        
        while (!isEncoderDone) {
            // Feed decoder
            if (!isDecoderDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val decoderInputBuffer = decoder.getInputBuffer(inputBufferIndex)
                    val sampleSize = extractor.readSampleData(decoderInputBuffer!!, 0)
                    
                    if (sampleSize < 0 || extractor.sampleTime > endTimeUs) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isDecoderDone = true
                    } else {
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            
            // Drain decoder output to surface (which feeds encoder)
            var decoderOutputAvailable = true
            while (decoderOutputAvailable) {
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> decoderOutputAvailable = false
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { }
                    outputBufferIndex >= 0 -> {
                        val doRender = bufferInfo.size != 0
                        decoder.releaseOutputBuffer(outputBufferIndex, doRender)
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoder.signalEndOfInputStream()
                            isDecoderDone = true
                        }
                        
                        // Update progress
                        if (totalDurationUs > 0) {
                            val progress = ((bufferInfo.presentationTimeUs - startTimeUs).toDouble() / totalDurationUs).coerceIn(0.0, 0.95)
                            progressCallback(progress)
                        }
                    }
                }
            }
            
            // Drain encoder output
            var encoderOutputAvailable = true
            while (encoderOutputAvailable) {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutputAvailable = false
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outputBufferIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size > 0) {
                            if (muxerStarted) {
                                muxer.writeSampleData(muxerTrackIndex, encodedData!!, bufferInfo)
                            }
                        }
                        
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            isEncoderDone = true
                        }
                    }
                }
            }
        }
        
        progressCallback(1.0)
        
        decoder.stop()
        decoder.release()
        encoder.stop()
        encoder.release()
        inputSurface.release()
    }
    
    // MARK: - Helper Methods
    
    private fun getFileSize(uri: Uri): Long {
        return context.contentResolver.openFileDescriptor(uri, "r")?.use {
            it.statSize
        } ?: 0L
    }
    
    private fun copyToOutput(sourceUri: Uri): Uri {
        val outputFile = createOutputFile()
        
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        
        return Uri.fromFile(outputFile)
    }
    
    private fun createOutputFile(): File {
        val cacheDir = context.cacheDir
        return File(cacheDir, "compressed_${UUID.randomUUID()}.mp4")
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

// MARK: - Errors

sealed class CompressionError : Exception() {
    object NoVideoTrack : CompressionError()
    data class ReaderFailed(override val message: String) : CompressionError()
    data class WriterFailed(override val message: String) : CompressionError()
    data class FileTooLarge(override val message: String) : CompressionError()
    object Cancelled : CompressionError()
}