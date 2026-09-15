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
import com.stitchsocial.club.BuildConfig

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
    
    /** Target file size in bytes (default 20MB, matches compress() targetSizeMB default) */
    var targetFileSizeBytes: Long = 20 * 1024 * 1024
    
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
        targetSizeMB: Double = 20.0,
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
            
            if (BuildConfig.DEBUG) { println("🎬 FAST COMPRESS: Source ${formatBytes(sourceInfo.fileSize)} → Target ${formatBytes(targetBytes)}") }
            if (BuildConfig.DEBUG) { println("🎬 FAST COMPRESS: Duration ${String.format("%.1f", sourceInfo.duration)}s, Resolution ${sourceInfo.resolution.width.toInt()}x${sourceInfo.resolution.height.toInt()}") }
            
            // Step 2: Check if compression is needed
            if (sourceInfo.fileSize <= targetBytes) {
                if (BuildConfig.DEBUG) { println("✅ FAST COMPRESS: Already under target, copying file") }
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
            
            if (BuildConfig.DEBUG) { println("🎬 FAST COMPRESS: Using ${settings.codec} @ ${settings.bitrate / 1000}kbps, ${settings.resolution.width.toInt()}x${settings.resolution.height.toInt()}") }
            
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
            
            if (BuildConfig.DEBUG) { println("✅ FAST COMPRESS: ${formatBytes(sourceInfo.fileSize)} → ${formatBytes(outputSize)} in ${processingTime}ms") }
            if (BuildConfig.DEBUG) { println("✅ FAST COMPRESS: ${String.format("%.1f", result.compressionRatio)}x compression ratio") }
            
            return@withContext result
            
        } catch (e: Exception) {
            _currentPhase.value = CompressionPhase.FAILED
            _lastError.value = e.message
            _isCompressing.value = false
            if (BuildConfig.DEBUG) { println("❌ FAST COMPRESS: ${e.message}") }
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
        targetSizeMB: Double = 20.0
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
        
        // Force H.264 (video/avc). The previous code preferred HEVC for ~40%
        // better compression, but HEVC-in-MP4 produced black-screen playback
        // on web players / CDN-transcoded variants — same root cause as the
        // iOS passthrough preset bug. Larger files in exchange for universal
        // decode. The isHEVCEncoderAvailable() helper is kept around for
        // future opt-in (e.g. a "high efficiency" toggle for power creators).
        val useHEVC = false
        val codec = "video/avc"
        
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

        // Read the source audio format up front. MediaMuxer requires every
        // addTrack() to land before start(), and the video track can only be added
        // once the encoder reports its output format — so the audio format has to
        // already be in hand at that moment.
        val audioFormat = if (audioTrackIndex >= 0) {
            extractor.getTrackFormat(audioTrackIndex)
        } else {
            null
        }

        val startTimeUs = (trimStartMs ?: 0L) * 1000
        val endTimeUs = (trimEndMs ?: (sourceInfo.duration * 1000).toLong()) * 1000

        // Create muxer
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            // Transcode video
            val audioMuxerTrack = transcodeVideo(
                extractor = extractor,
                muxer = muxer,
                videoTrackIndex = videoTrackIndex,
                audioFormat = audioFormat,
                settings = settings,
                startTimeUs = startTimeUs,
                endTimeUs = endTimeUs,
                progressCallback = progressCallback
            )

            // Remux the audio untouched. Without this the muxer only ever received
            // the encoder's video track, so everything this compressor produced was
            // silent — and since VideoExportService always takes FULL_PROCESS, that
            // is every post without captions.
            if (audioMuxerTrack >= 0) {
                copyAudio(
                    sourceUri = sourceUri,
                    muxer = muxer,
                    muxerTrackIndex = audioMuxerTrack,
                    startTimeUs = startTimeUs,
                    endTimeUs = endTimeUs
                )
            }
        } finally {
            extractor.release()
            muxer.stop()
            muxer.release()
        }
        
        Uri.fromFile(outputFile)
    }
    
    /**
     * Encodes the video track into [muxer] and, when [audioFormat] is non-null,
     * adds the audio track alongside it so the caller can remux the audio after.
     *
     * @return the muxer track index for audio, or -1 when the source has none.
     */
    private fun transcodeVideo(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        videoTrackIndex: Int,
        audioFormat: MediaFormat?,
        settings: CompressionSettings,
        startTimeUs: Long,
        endTimeUs: Long,
        progressCallback: (Double) -> Unit
    ): Int {
        extractor.selectTrack(videoTrackIndex)
        val inputFormat = extractor.getTrackFormat(videoTrackIndex)

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
        var audioMuxerTrackIndex = -1
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
                            // Both tracks must be added before start(). This is the
                            // only point where the encoder's format is known, so the
                            // audio track goes in here too.
                            if (audioFormat != null) {
                                audioMuxerTrackIndex = muxer.addTrack(audioFormat)
                            }
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

        return audioMuxerTrackIndex
    }

    /**
     * Remuxes the source audio into an already-started [muxer] without re-encoding.
     *
     * Uses its own MediaExtractor: the caller's still has the video track selected,
     * and selecting a second track on it would interleave both into one read loop.
     *
     * Sample timestamps are written through unchanged so they stay on the same
     * timeline as the encoder's output, which carries the source presentation times
     * straight through the decode-to-surface path. A trimmed clip therefore starts
     * at a non-zero offset on both tracks, which keeps them in sync.
     */
    private fun copyAudio(
        sourceUri: Uri,
        muxer: MediaMuxer,
        muxerTrackIndex: Int,
        startTimeUs: Long,
        endTimeUs: Long
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, sourceUri, null)

            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack == -1) return

            extractor.selectTrack(audioTrack)
            if (startTimeUs > 0) {
                extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val maxInputSize = extractor.getTrackFormat(audioTrack)
                .takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }
                ?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                ?.coerceAtLeast(64 * 1024)
                ?: (256 * 1024)
            val buffer = ByteBuffer.allocate(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endTimeUs) break

                if (sampleTime >= startTimeUs) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTime
                    // MediaExtractor reports SAMPLE_FLAG_*; the muxer wants
                    // BUFFER_FLAG_*. Only the sync-frame bit matters for audio.
                    bufferInfo.flags =
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                }

                if (!extractor.advance()) break
            }
        } catch (e: Exception) {
            // A source with unreadable audio should still produce a usable video
            // rather than failing the whole export.
            if (BuildConfig.DEBUG) { println("🔊 COMPRESS: audio remux failed - ${e.message}") }
        } finally {
            extractor.release()
        }
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