/*
 * PromoVideoExporter.kt - 30-SECOND PROMO VIDEO WITH HEAT SYSTEM
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services - Promo Video Export
 * Creates a 30s promo clip from any video with:
 * - Counter accumulation (0 → final value per stat)
 * - Heat system (cyan → orange → blue → fire)
 * - Ember particles during HYPE + TEMPERATURE
 * - Temperature emoji slam + letter-by-letter type-in
 * - 3s branded end card
 *
 * Runs the source through a Media3 Transformer composition (the same idiom as
 * VideoWatermarkService): the first 27s of the clip, then 3s of the bundled
 * end screen, with one full-frame BitmapOverlay redrawn per output frame.
 * Sources shorter than 27s loop so every stat gets its full slot (iOS
 * stretches the clip instead).
 *
 * NOTE: this replaces a MediaCodec version that drew the overlay but never
 * encoded a frame, so it always "succeeded" with an empty file.
 *
 * Promo temp files prefixed "StitchPromo_", cleaned after share dismisses.
 * Counter text values pre-computed as String array — not recalculated per frame.
 * Ember positions pre-calculated at init and reused across frames.
 */

package com.stitchsocial.club.services

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.*
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.R

@OptIn(UnstableApi::class)
class PromoVideoExporter private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: PromoVideoExporter? = null

        fun getInstance(context: Context): PromoVideoExporter {
            return instance ?: synchronized(this) {
                instance ?: PromoVideoExporter(context.applicationContext).also { instance = it }
            }
        }

        // Timing
        private const val PROMO_DURATION_MS = 30_000L
        private const val CLIP_DURATION_MS = 27_000L
        private const val END_CARD_DURATION_MS = 3_000L
        private const val SLOT_DURATION_MS = 6_750L  // 27s / 4 stats
        private const val COUNTER_DURATION_MS = 3_000L
        private const val COUNTER_STEPS = 20
        private const val FPS = 30

        // Below this a looped source is just a stutter — fall back to a regular share.
        private const val MIN_SOURCE_MS = 1_000L
    }

    // State
    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress

    // MARK: - Data

    data class PromoStats(
        val viewCount: Int,
        val hypeCount: Int,
        val coolCount: Int,
        val temperature: String  // "hot", "warm", "cold"
    )

    data class PromoResult(
        val success: Boolean,
        val outputFile: File? = null,
        val error: String? = null
    )

    // Pre-computed ember data (CACHING: calculated once, reused every frame)
    private data class EmberParticle(
        val xPercent: Float,
        val size: Float,
        val riseDurationMs: Long,
        val delayMs: Long,
        val color: Int
    )

    private val emberParticles: List<EmberParticle> = (0 until 12).map {
        EmberParticle(
            xPercent = (5 + Math.random() * 90).toFloat(),
            size = (3f + Math.random().toFloat() * 4f),
            riseDurationMs = (2500 + (Math.random() * 1500)).toLong(),
            delayMs = (Math.random() * 2000).toLong(),
            color = listOf(
                Color.rgb(255, 102, 0),
                Color.rgb(255, 135, 0),
                Color.rgb(255, 170, 0),
                Color.rgb(255, 68, 0),
                Color.rgb(255, 204, 0)
            )[it % 5]
        )
    }

    // MARK: - Export

    /**
     * Build the promo from a local [sourceUri]. Never throws — a failed export
     * returns success = false so the caller can fall back to a regular share.
     */
    suspend fun exportPromo(
        sourceUri: Uri,
        creatorUsername: String,
        stats: PromoStats,
        onProgress: (Double) -> Unit = {}
    ): PromoResult = withContext(Dispatchers.IO) {
        _isExporting.value = true
        _progress.value = 0.0
        try {
            val probe = probeSource(sourceUri)
            val sourceMs = probe.durationUs / 1000
            if (sourceMs < MIN_SOURCE_MS) {
                return@withContext PromoResult(success = false, error = "Video too short for a promo")
            }
            val (outW, outH) = probe.outputResolution()

            // Effects go on every item: Media3 1.4 ignores composition-level
            // video effects for a single sequence. Each item gets its own
            // overlay instance (GL texture) sharing one renderer.
            val renderer = PromoFrameRenderer(outW, outH, creatorUsername, stats)
            fun itemEffects() = Effects(
                emptyList(),
                listOf<Effect>(
                    Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT),
                    OverlayEffect(ImmutableList.of(PromoOverlay(renderer)))
                )
            )

            // Main clip: first 27s, looped when the source is shorter.
            val clipItems = mutableListOf<EditedMediaItem>()
            var remainingMs = CLIP_DURATION_MS
            while (remainingMs > 0) {
                val takeMs = min(remainingMs, sourceMs)
                clipItems.add(clippedItem(sourceUri, takeMs, itemEffects()))
                remainingMs -= takeMs
            }

            val endUri = Uri.parse("android.resource://${context.packageName}/${R.raw.stitch_end_screen}")
            val endItem = clippedItem(endUri, END_CARD_DURATION_MS, itemEffects())

            val sequence = EditedMediaItemSequence(clipItems + endItem)

            val composition = Composition.Builder(listOf(sequence))
                // Silent sources: synthesize an audio track so the sequence's
                // tracks line up with the end screen's.
                .experimentalSetForceAudioTrack(true)
                .build()

            val outputFile = createTempFile()
            if (BuildConfig.DEBUG) {
                println("🎬 PROMO: Starting export — source ${sourceMs}ms → canvas ${outW}x$outH, ${clipItems.size} clip(s)")
            }

            val error = runTransformer(composition, outputFile) { p ->
                _progress.value = p
                onProgress(p)
            }

            if (error != null || !outputFile.exists() || outputFile.length() == 0L) {
                outputFile.delete()
                if (BuildConfig.DEBUG) { println("❌ PROMO: Export failed — ${error?.message}") }
                return@withContext PromoResult(success = false, error = error?.message ?: "Empty promo output")
            }

            _progress.value = 1.0
            if (BuildConfig.DEBUG) { println("✅ PROMO: Export completed — ${outputFile.name} (${outputFile.length() / 1024}KB)") }
            PromoResult(success = true, outputFile = outputFile)

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("❌ PROMO: Export failed — ${e.message}") }
            PromoResult(success = false, error = e.message)
        } finally {
            _isExporting.value = false
        }
    }

    private fun clippedItem(uri: Uri, durationMs: Long, effects: Effects): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setEndPositionMs(durationMs)
                    .build()
            )
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(false)
            .setEffects(effects)
            .build()
    }

    /** Runs the export; returns null on success or the failure. */
    private suspend fun runTransformer(
        composition: Composition,
        outputFile: File,
        onProgress: (Double) -> Unit
    ): ExportException? = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            val transformer = Transformer.Builder(context)
                // Pin H.264 so HEVC sources still play on web and older Android.
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (cont.isActive) cont.resume(exportException)
                    }
                })
                .build()

            cont.invokeOnCancellation {
                mainHandler.post { runCatching { transformer.cancel() } }
            }

            transformer.start(composition, outputFile.absolutePath)

            // Media3 has no continuous progress stream — poll getProgress.
            val holder = ProgressHolder()
            val tick = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    val state = transformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress((holder.progress / 100.0).coerceIn(0.0, 0.99))
                    }
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        mainHandler.postDelayed(this, 200)
                    }
                }
            }
            mainHandler.postDelayed(tick, 200)
        }
    }

    // Stat slot definition (inner classes can't hold nested classes)
    private data class StatDef(
        val counterSteps: List<String>,
        val label: String,
        val color: Int,
        val isTemperature: Boolean = false,
        val tempEmoji: String = "",
        val tempText: String = ""
    )

    // MARK: - Overlay

    /**
     * Full-frame overlay. Media3 re-uploads the bitmap when its generation id
     * changes, so redrawing the one bitmap in place is enough; it is only
     * redrawn when the output frame index changes.
     */
    private class PromoOverlay(private val renderer: PromoFrameRenderer) : BitmapOverlay() {
        private var lastFrame = -1L

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val frame = presentationTimeUs * FPS / 1_000_000
            if (frame != lastFrame) {
                lastFrame = frame
                renderer.draw(presentationTimeUs / 1000)
            }
            return renderer.bitmap
        }
    }

    /** Draws one promo frame (stats, heat, embers, end card) into [bitmap]. */
    private inner class PromoFrameRenderer(
        private val width: Int,
        private val height: Int,
        private val creatorUsername: String,
        private val stats: PromoStats
    ) {
        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)
        private val scale = min(width, height) / 1080f

        // Pre-compute counter values (CACHING: compute once, index per frame)
        private val tempInfo = getTemperatureDisplay(stats.temperature)

        private val statDefs = listOf(
            StatDef(preComputeCounter(stats.viewCount), "VIEWS", Color.WHITE),
            StatDef(preComputeCounter(stats.hypeCount), "🔥 HYPE", Color.rgb(255, 69, 69)),
            StatDef(preComputeCounter(stats.coolCount), "❄️ COOL", Color.rgb(69, 186, 255)),
            StatDef(emptyList(), "TEMPERATURE", tempInfo.color, true, tempInfo.emoji, tempInfo.text)
        )

        // Paint objects (CACHING: reuse across frames)
        private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textSize = 168f * scale
            isFakeBoldText = true
            setShadowLayer(30f * scale, 0f, 6f * scale, Color.argb(153, 0, 0, 0))
        }

        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = 43f * scale
        }

        private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f * scale
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            color = Color.WHITE
            setShadowLayer(3f * scale, 1f, 1f, Color.argb(204, 0, 0, 0))
        }

        private val dimPaint = Paint().apply {
            color = Color.argb(115, 0, 0, 0)  // 0.45 alpha
        }

        private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 24f * scale
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            color = Color.argb(77, 255, 255, 255)  // 0.3 alpha
        }

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 4f * scale
            strokeCap = Paint.Cap.ROUND
        }

        private val emberPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val endCardBgPaint = Paint().apply {
            color = Color.argb(179, 0, 0, 0)  // 0.7 alpha
        }

        private val endCardTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 64f * scale
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            color = Color.WHITE
        }

        private val endCardUserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 36f * scale
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            color = Color.argb(153, 255, 255, 255)
        }

        private val watermarkText = "@$creatorUsername · StitchSocial"

        fun draw(timeMs: Long) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            if (timeMs >= CLIP_DURATION_MS) {
                drawEndCard(canvas, width, height, scale,
                    creatorUsername, endCardBgPaint, endCardTitlePaint, endCardUserPaint)
                return
            }

            // Dark tint so the stats read over any footage
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

            drawHeatGlow(canvas, width, height, timeMs, stats.temperature)
            drawEmbers(canvas, width, height, scale, timeMs, emberPaint)

            canvas.drawText(watermarkText, 32f * scale, 64f * scale, watermarkPaint)

            // Current stat — visible for the slot minus a 1.75s gap at the end
            val slotIndex = (timeMs / SLOT_DURATION_MS).toInt().coerceIn(0, 3)
            val slotLocalMs = timeMs - (slotIndex * SLOT_DURATION_MS)
            if (slotLocalMs < (SLOT_DURATION_MS - 1750)) {
                val stat = statDefs[slotIndex]
                if (stat.isTemperature) {
                    drawTemperatureStat(
                        canvas, width, height, scale,
                        slotLocalMs, stat.tempEmoji, stat.tempText, stat.color,
                        numberPaint, labelPaint, linePaint
                    )
                } else {
                    drawCounterStat(
                        canvas, width, height, scale,
                        slotLocalMs, stat.counterSteps, stat.label, stat.color,
                        numberPaint, labelPaint, linePaint
                    )
                }
            }

            canvas.drawText("STITCHSOCIAL", width / 2f, height - 32f * scale, brandPaint)
        }
    }

    // MARK: - Source probe

    private data class SourceProbe(
        val durationUs: Long,
        val width: Int,
        val height: Int,
        val rotation: Int
    ) {
        /** Even canvas preserving the displayed aspect, capped to 1080×1920. */
        fun outputResolution(): Pair<Int, Int> {
            val (dw, dh) = if (rotation == 90 || rotation == 270) height to width else width to height
            if (dw <= 0 || dh <= 0) return 1080 to 1920
            val scale = minOf(1080f / dw, 1920f / dh, 1f)
            val w = (dw * scale).toInt().let { if (it % 2 == 0) it else it - 1 }.coerceAtLeast(2)
            val h = (dh * scale).toInt().let { if (it % 2 == 0) it else it - 1 }.coerceAtLeast(2)
            return w to h
        }
    }

    private fun probeSource(uri: Uri): SourceProbe {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fun key(k: Int) = retriever.extractMetadata(k)?.toIntOrNull() ?: 0
            SourceProbe(
                durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L) * 1000L,
                width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            )
        } catch (e: Exception) {
            SourceProbe(0L, 0, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    // MARK: - Draw Counter Stat

    private fun drawCounterStat(
        canvas: Canvas,
        width: Int, height: Int, scale: Float,
        slotLocalMs: Long,
        counterSteps: List<String>,
        label: String,
        color: Int,
        numberPaint: Paint,
        labelPaint: Paint,
        linePaint: Paint
    ) {
        // Determine which counter step to show
        val counterStartMs = 400L  // after slam-in
        val counterLocalMs = (slotLocalMs - counterStartMs).coerceAtLeast(0)
        val stepIndex = if (slotLocalMs < counterStartMs) {
            0
        } else {
            ((counterLocalMs.toFloat() / COUNTER_DURATION_MS) * COUNTER_STEPS).toInt()
                .coerceIn(0, COUNTER_STEPS)
        }

        val displayText = counterSteps.getOrElse(stepIndex) { counterSteps.lastOrNull() ?: "0" }
        val centerX = width / 2f
        val centerY = height / 2f - 20 * scale

        // Number
        numberPaint.color = color
        canvas.drawText(displayText, centerX, centerY, numberPaint)

        // Label
        labelPaint.color = Color.argb(179, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawText(label, centerX, centerY + 60 * scale, labelPaint)

        // Accent line
        linePaint.color = Color.argb(128, Color.red(color), Color.green(color), Color.blue(color))
        val lineWidth = 90f * scale
        canvas.drawLine(
            centerX - lineWidth / 2, centerY + 85 * scale,
            centerX + lineWidth / 2, centerY + 85 * scale,
            linePaint
        )
    }

    // MARK: - Draw Temperature Stat

    private fun drawTemperatureStat(
        canvas: Canvas,
        width: Int, height: Int, scale: Float,
        slotLocalMs: Long,
        emoji: String,
        text: String,
        color: Int,
        numberPaint: Paint,
        labelPaint: Paint,
        linePaint: Paint
    ) {
        val centerX = width / 2f
        val centerY = height / 2f - 40 * scale

        // Emoji slams in at 300ms
        if (slotLocalMs >= 300) {
            numberPaint.color = Color.WHITE
            numberPaint.textSize = 120f * scale
            canvas.drawText(emoji, centerX, centerY, numberPaint)
            numberPaint.textSize = 168f * scale  // reset
        }

        // Text types in letter by letter starting at 1000ms, 200ms per letter
        if (slotLocalMs >= 1000) {
            val lettersShown = ((slotLocalMs - 1000) / 200).toInt().coerceIn(0, text.length)
            if (lettersShown > 0) {
                val partial = text.substring(0, lettersShown)
                val typePaint = Paint(labelPaint).apply {
                    this.color = color
                    textSize = 72f * scale
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    letterSpacing = 0.15f
                }
                canvas.drawText(partial, centerX, centerY + 90 * scale, typePaint)
            }
        }

        // "TEMPERATURE" label
        labelPaint.color = Color.argb(128, Color.red(color), Color.green(color), Color.blue(color))
        labelPaint.textSize = 34f * scale
        canvas.drawText("TEMPERATURE", centerX, centerY + 140 * scale, labelPaint)
        labelPaint.textSize = 43f * scale  // reset

        // Accent line
        linePaint.color = Color.argb(128, Color.red(color), Color.green(color), Color.blue(color))
        val lineWidth = 90f * scale
        canvas.drawLine(
            centerX - lineWidth / 2, centerY + 158 * scale,
            centerX + lineWidth / 2, centerY + 158 * scale,
            linePaint
        )
    }

    // MARK: - Heat Glow

    private fun drawHeatGlow(
        canvas: Canvas, width: Int, height: Int,
        timeMs: Long, temperature: String
    ) {
        val slotIndex = (timeMs / SLOT_DURATION_MS).toInt().coerceIn(0, 3)
        val glowHeight = (height * 0.45f).toInt()
        val top = height - glowHeight

        val glowColor = when (slotIndex) {
            0 -> Color.argb(20, 0, 229, 255)       // Cyan
            1 -> Color.argb(46, 255, 136, 0)        // Orange
            2 -> Color.argb(31, 69, 186, 255)       // Blue
            3 -> when (temperature.lowercase()) {    // Temperature
                "hot" -> Color.argb(64, 255, 69, 0)
                "warm" -> Color.argb(46, 255, 153, 0)
                "cold" -> Color.argb(38, 77, 153, 255)
                else -> Color.argb(26, 255, 255, 255)
            }
            else -> Color.TRANSPARENT
        }

        val gradient = LinearGradient(
            0f, top.toFloat(), 0f, height.toFloat(),
            Color.TRANSPARENT, glowColor,
            Shader.TileMode.CLAMP
        )
        val glowPaint = Paint().apply { shader = gradient }
        canvas.drawRect(0f, top.toFloat(), width.toFloat(), height.toFloat(), glowPaint)
    }

    // MARK: - Embers

    private fun drawEmbers(
        canvas: Canvas, width: Int, height: Int, scale: Float,
        timeMs: Long, paint: Paint
    ) {
        val slotIndex = (timeMs / SLOT_DURATION_MS).toInt()
        // Only during HYPE (slot 1) and TEMPERATURE (slot 3)
        if (slotIndex != 1 && slotIndex != 3) return

        val slotStartMs = slotIndex * SLOT_DURATION_MS
        val slotLocalMs = timeMs - slotStartMs

        for (ember in emberParticles) {
            val localMs = slotLocalMs - ember.delayMs
            if (localMs < 0 || localMs > ember.riseDurationMs) continue

            val progress = localMs.toFloat() / ember.riseDurationMs
            val x = (ember.xPercent / 100f) * width
            val y = height - (progress * height * 1.1f)
            val alpha = when {
                progress < 0.1f -> (progress / 0.1f * 200).toInt()
                progress > 0.7f -> ((1f - progress) / 0.3f * 150).toInt()
                else -> 200
            }.coerceIn(0, 255)

            paint.color = Color.argb(alpha, Color.red(ember.color), Color.green(ember.color), Color.blue(ember.color))
            canvas.drawCircle(x, y, ember.size * scale, paint)
        }
    }

    // MARK: - End Card

    private fun drawEndCard(
        canvas: Canvas, width: Int, height: Int, scale: Float,
        creatorUsername: String,
        bgPaint: Paint, titlePaint: Paint, userPaint: Paint
    ) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawText("STITCHSOCIAL", width / 2f, height / 2f - 10 * scale, titlePaint)
        canvas.drawText("@$creatorUsername", width / 2f, height / 2f + 40 * scale, userPaint)
    }

    // MARK: - Helpers

    /** Pre-compute counter display strings using easeOutExpo curve (CACHING) */
    private fun preComputeCounter(targetValue: Int): List<String> {
        return (0..COUNTER_STEPS).map { step ->
            val progress = step.toDouble() / COUNTER_STEPS
            val eased = if (progress == 1.0) 1.0 else 1.0 - 2.0.pow(-10.0 * progress)
            val currentValue = (eased * targetValue).toInt()
            formatNumber(currentValue)
        }
    }

    private fun formatNumber(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    data class TempDisplay(val emoji: String, val text: String, val color: Int)

    private fun getTemperatureDisplay(temp: String): TempDisplay {
        return when (temp.lowercase()) {
            "hot" -> TempDisplay("\uD83D\uDD25", "HOT", Color.rgb(255, 170, 0))
            "warm" -> TempDisplay("☀\uFE0F", "WARM", Color.rgb(255, 204, 51))
            "cold" -> TempDisplay("❄\uFE0F", "COLD", Color.rgb(128, 199, 255))
            else -> TempDisplay("\uD83C\uDF21\uFE0F", temp.uppercase(), Color.WHITE)
        }
    }

    private fun createTempFile(): File {
        return File(context.cacheDir, "StitchPromo_${UUID.randomUUID()}.mp4")
    }

    // MARK: - Cleanup

    fun cleanupTempFiles() {
        context.cacheDir.listFiles()?.filter { it.name.startsWith("StitchPromo_") }?.forEach {
            it.delete()
        }
    }
}