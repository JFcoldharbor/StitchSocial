/*
 * VideoWatermarkService.kt - VIDEO WATERMARK SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services - Video Watermarking
 * Burns "@username · StitchSocial" onto video frames using MediaCodec.
 * Matches iOS VideoWatermarkService.swift behavior.
 *
 * Pipeline: Decode source → draw watermark via Canvas → encode → mux output
 *
 * CACHING: Paint objects created once and reused across all frames.
 * Output file uses "StitchWM_" prefix for targeted cleanup.
 */

package com.stitchsocial.club.services

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

class VideoWatermarkService private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: VideoWatermarkService? = null

        fun getInstance(context: Context): VideoWatermarkService {
            return instance ?: synchronized(this) {
                instance ?: VideoWatermarkService(context.applicationContext).also { instance = it }
            }
        }

        private const val TIMEOUT_US = 10_000L
    }

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    data class WatermarkResult(
        val success: Boolean,
        val outputFile: File? = null,
        val error: String? = null
    )

    // MARK: - Export With Watermark

    suspend fun exportWithWatermark(
        sourceFile: File,
        creatorUsername: String
    ): WatermarkResult = withContext(Dispatchers.IO) {
        try {
            _isProcessing.value = true
            println("🎨 WATERMARK: Starting watermark export for @$creatorUsername")

            val sourceUri = Uri.fromFile(sourceFile)
            val outputFile = File(context.cacheDir, "StitchWM_${UUID.randomUUID()}.mp4")

            // Extract source video info
            val extractor = MediaExtractor()
            extractor.setDataSource(context, sourceUri, null)

            val videoTrackIndex = findTrack(extractor, "video/")
            val audioTrackIndex = findTrack(extractor, "audio/")

            if (videoTrackIndex < 0) {
                extractor.release()
                return@withContext WatermarkResult(false, error = "No video track")
            }

            val videoFormat = extractor.getTrackFormat(videoTrackIndex)
            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME)
                ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val frameRate = try {
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            } catch (_: Exception) { 30 }
            val bitRate = try {
                videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
            } catch (_: Exception) { 8_000_000 }

            val scale = minOf(width, height) / 1080f

            // CACHING: Create paint objects once, reuse every frame
            val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 20f * scale
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setShadowLayer(4f * scale, 1f, 1f, Color.argb(204, 0, 0, 0))
            }

            val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 22f * scale
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            val logoBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 40f * scale, 40f * scale,
                    Color.rgb(0, 229, 255), Color.rgb(124, 77, 255),
                    Shader.TileMode.CLAMP
                )
            }

            // Setup encoder
            val outputFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = encoder.createInputSurface()
            encoder.start()

            // Setup decoder with output to encoder surface
            val decoder = MediaCodec.createDecoderByType(mime)
            // We'll decode to a surface, then draw watermark, then feed to encoder
            // Actually, for watermark overlay we need to:
            // 1. Decode to an ImageReader or Bitmap
            // 2. Draw watermark on Canvas
            // 3. Draw result onto encoder surface

            // Simpler approach: decode to bitmap via MediaMetadataRetriever frame-by-frame
            // is too slow. Use surface-to-surface with an intermediate OpenGL or
            // use the decode→software bitmap→draw→encode approach.

            // Decode to software bitmap approach:
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false

            // Process video track
            extractor.selectTrack(videoTrackIndex)

            val inputBuffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Feed input to decoder
                if (!inputDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val buf = decoder.getInputBuffer(inputBufIndex) ?: continue
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleFlags = extractor.sampleFlags
                            val codecFlags = if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else { 0 }
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize,
                                extractor.sampleTime, codecFlags)
                            extractor.advance()
                        }
                    }
                }

                // Get decoded output
                val outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        // Signal encoder end
                        encoder.signalEndOfInputStream()
                        decoder.releaseOutputBuffer(outputBufIndex, false)
                        outputDone = true
                    } else {
                        // Get decoded frame as Image (API 21+)
                        val image = decoder.getOutputImage(outputBufIndex)
                        if (image != null) {
                            // Convert YUV to Bitmap
                            val bitmap = yuvImageToBitmap(image, width, height)
                            image.close()

                            // Draw watermark onto bitmap
                            val canvas = Canvas(bitmap)
                            drawWatermark(canvas, width, height, scale,
                                creatorUsername, watermarkPaint, logoPaint, logoBgPaint)

                            // Feed watermarked frame to encoder via surface
                            val encoderCanvas = encoderSurface.lockCanvas(null)
                            encoderCanvas.drawBitmap(bitmap, 0f, 0f, null)
                            encoderSurface.unlockCanvasAndPost(encoderCanvas)

                            bitmap.recycle()
                        }

                        decoder.releaseOutputBuffer(outputBufIndex, false)
                    }
                }

                // Drain encoder output
                drainEncoder(encoder, muxer, bufferInfo, muxerVideoTrack, muxerStarted) { track, started ->
                    muxerVideoTrack = track
                    muxerStarted = started
                }
            }

            // Drain remaining encoder output
            drainEncoderFinal(encoder, muxer, bufferInfo, muxerVideoTrack)

            // Copy audio track (passthrough — no re-encoding)
            if (audioTrackIndex >= 0) {
                extractor.unselectTrack(videoTrackIndex)
                extractor.selectTrack(audioTrackIndex)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val audioFormat = extractor.getTrackFormat(audioTrackIndex)
                muxerAudioTrack = muxer.addTrack(audioFormat)
                if (!muxerStarted) {
                    muxer.start()
                    muxerStarted = true
                }

                val audioBuf = ByteBuffer.allocate(256 * 1024)
                val audioInfo = MediaCodec.BufferInfo()

                while (true) {
                    audioInfo.size = extractor.readSampleData(audioBuf, 0)
                    if (audioInfo.size < 0) break
                    audioInfo.presentationTimeUs = extractor.sampleTime
                    audioInfo.flags = extractor.sampleFlags
                    audioInfo.offset = 0
                    muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                    extractor.advance()
                }
            }

            // Cleanup
            decoder.stop()
            decoder.release()
            encoder.stop()
            encoder.release()
            muxer.stop()
            muxer.release()
            extractor.release()
            encoderSurface.release()

            _isProcessing.value = false
            println("✅ WATERMARK: Export complete — ${outputFile.name}")
            WatermarkResult(success = true, outputFile = outputFile)

        } catch (e: Exception) {
            _isProcessing.value = false
            println("❌ WATERMARK: Failed — ${e.message}")
            WatermarkResult(success = false, error = e.message)
        }
    }

    // MARK: - Draw Watermark

    private fun drawWatermark(
        canvas: Canvas,
        width: Int, height: Int, scale: Float,
        creatorUsername: String,
        textPaint: Paint, logoPaint: Paint, logoBgPaint: Paint
    ) {
        val padding = 24f * scale

        // Logo square (top-left)
        val logoSize = 40f * scale
        val logoRadius = 10f * scale
        val logoRect = RectF(padding, padding, padding + logoSize, padding + logoSize)
        canvas.drawRoundRect(logoRect, logoRadius, logoRadius, logoBgPaint)
        canvas.drawText("S", padding + logoSize / 2, padding + logoSize * 0.7f, logoPaint)

        // Username text next to logo
        val textX = padding + logoSize + 10f * scale
        val textY = padding + logoSize * 0.65f
        canvas.drawText("@$creatorUsername", textX, textY, textPaint)
    }

    // MARK: - YUV to Bitmap conversion

    private fun yuvImageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, out)

        val bytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Make mutable for drawing
        return bitmap.copy(Bitmap.Config.ARGB_8888, true)
    }

    // MARK: - Encoder drain helpers

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        currentTrack: Int,
        muxerStarted: Boolean,
        onTrackReady: (Int, Boolean) -> Unit
    ) {
        var track = currentTrack
        var started = muxerStarted

        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!started) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                    onTrackReady(track, started)
                }
            } else if (outIndex >= 0) {
                val buf = encoder.getOutputBuffer(outIndex) ?: break
                if (bufferInfo.size > 0 && started) {
                    buf.position(bufferInfo.offset)
                    buf.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(track, buf, bufferInfo)
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else {
                break
            }
        }
    }

    private fun drainEncoderFinal(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        track: Int
    ) {
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outIndex >= 0) {
                val buf = encoder.getOutputBuffer(outIndex) ?: break
                if (bufferInfo.size > 0) {
                    buf.position(bufferInfo.offset)
                    buf.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(track, buf, bufferInfo)
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else {
                break
            }
        }
    }

    // MARK: - Helpers

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    // MARK: - Cleanup

    fun cleanupTempFiles() {
        context.cacheDir.listFiles()?.filter { it.name.startsWith("StitchWM_") }?.forEach {
            try { it.delete() } catch (_: Exception) {}
        }
    }
}