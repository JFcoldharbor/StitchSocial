/*
 * VideoWatermarkService.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Layer 4: Services — burns the share watermark + end screen into a video,
 * the Android port of iOS VideoWatermarkService. Runs the local (already
 * downloaded) MP4 through a Media3 Transformer composition that bakes in:
 *
 *   • a jumping watermark (Stitch logo + @username + "StitchSocial") that
 *     hops between 3 corners every 2s over the main clip — iOS parity
 *     (WatermarkPosition topLeft / rightMiddle / bottomLeft),
 *   • a concatenated end screen (res/raw/stitch_end_screen.mp4, the same
 *     asset as the iOS bundle) with the creator's @username over it.
 *
 * iOS ships NO sound asset (its addSound path no-ops), so we match by not
 * adding one. The composition/overlay idiom mirrors ThreadCollageService +
 * CaptionBurnIn — the two proven Transformer patterns already in this app.
 *
 * NOTE: this replaces a dead hand-rolled MediaCodec (decode → JPEG-per-frame
 * → redraw → encode) implementation that was never wired into anything. The
 * Transformer path is GPU-composited, faster, and matches the iOS look.
 */

package com.stitchsocial.club.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
object VideoWatermarkService {

    private const val TAG = "WATERMARK"

    // iOS: jumpInterval = 2.0s, discrete jump between 3 zones.
    private const val JUMP_INTERVAL_US = 2_000_000L

    /**
     * Burn the watermark + end screen into [sourceFile] and return a file://
     * Uri of the exported MP4 in cacheDir. [onProgress] receives 0.0..1.0.
     * Throws on Transformer failure (caller falls back to the raw file).
     */
    suspend fun exportWithWatermark(
        context: Context,
        sourceFile: File,
        creatorUsername: String,
        onProgress: (Double) -> Unit = {}
    ): Uri = withContext(Dispatchers.IO) {
        val probe = probeSource(sourceFile)
        val mainDurationUs = probe.durationUs
        val (outW, outH) = probe.outputResolution()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "🎬 export: src=${probe.width}x${probe.height} rot=${probe.rotation} " +
                "dur=${mainDurationUs / 1000}ms → canvas ${outW}x$outH")
        }

        // Main clip (keep audio) + concatenated bundled end screen (keep its audio).
        val mainItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(sourceFile)))
            .setRemoveAudio(false)
            .build()

        val endUri = Uri.parse("android.resource://${context.packageName}/${R.raw.stitch_end_screen}")
        val endItem = EditedMediaItem.Builder(MediaItem.fromUri(endUri))
            .setRemoveAudio(false)
            .build()

        val sequence = EditedMediaItemSequence(listOf(mainItem, endItem))

        // Composition-level output canvas — SCALE_TO_FIT reconciles the main
        // clip and the end screen even if their resolutions differ.
        val presentation = Presentation.createForWidthAndHeight(
            outW, outH, Presentation.LAYOUT_SCALE_TO_FIT
        )

        // Overlays (composition timeline is continuous across the sequence, so
        // presentationTimeUs >= mainDurationUs == "we're on the end screen").
        val overlays = mutableListOf<TextureOverlay>()
        overlays.add(JumpingWatermarkOverlay(
            bitmap = renderWatermarkBitmap(context, creatorUsername, outH),
            mainDurationUs = mainDurationUs
        ))
        if (creatorUsername.isNotBlank()) {
            overlays.add(EndScreenUsernameOverlay(
                bitmap = renderEndScreenUsernameBitmap(creatorUsername, outW, outH),
                mainDurationUs = mainDurationUs
            ))
        }
        val overlayEffect = OverlayEffect(ImmutableList.copyOf(overlays))

        val videoEffects: List<Effect> = listOf(presentation, overlayEffect)
        val composition = Composition.Builder(listOf(sequence))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val outputFile = File(context.cacheDir, "StitchWM_${UUID.randomUUID()}.mp4")

        suspendCancellableCoroutine<Uri> { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "✅ watermark export complete — ${outputFile.length() / 1024} KB")
                            }
                            onProgress(1.0)
                            if (cont.isActive) cont.resume(Uri.fromFile(outputFile))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (BuildConfig.DEBUG) {
                                Log.w(TAG, "❌ watermark export failed — ${exportException.message}")
                            }
                            if (cont.isActive) cont.resumeWithException(exportException)
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
    }

    /** Best-effort cleanup of watermark temp files (StitchWM_ prefix). */
    fun cleanupTempFiles(context: Context) {
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("StitchWM_") }
            ?.forEach { runCatching { it.delete() } }
    }

    // ── Source probe ──────────────────────────────────────────────────

    private data class SourceProbe(
        val durationUs: Long,
        val width: Int,
        val height: Int,
        val rotation: Int
    ) {
        /** Displayed dimensions accounting for rotation. */
        private fun displaySize(): Pair<Int, Int> =
            if (rotation == 90 || rotation == 270) height to width else width to height

        /**
         * Even output canvas that preserves the source aspect, capped to a
         * 1080×1920 box. Falls back to portrait 1080×1920 if probe was empty.
         */
        fun outputResolution(): Pair<Int, Int> {
            val (dw, dh) = displaySize()
            if (dw <= 0 || dh <= 0) return 1080 to 1920
            val scale = minOf(1080f / dw, 1920f / dh, 1f)
            val w = (dw * scale).toInt().let { if (it % 2 == 0) it else it - 1 }.coerceAtLeast(2)
            val h = (dh * scale).toInt().let { if (it % 2 == 0) it else it - 1 }.coerceAtLeast(2)
            return w to h
        }
    }

    private fun probeSource(file: File): SourceProbe {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            fun key(k: Int) = retriever.extractMetadata(k)?.toIntOrNull() ?: 0
            SourceProbe(
                durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L) * 1000L,
                width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "probe failed — ${e.message}")
            SourceProbe(0L, 0, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ── Jumping watermark overlay ─────────────────────────────────────

    /**
     * Logo + @username watermark that jumps between 3 zones every 2s over the
     * main clip and is hidden (alpha 0) once the end screen starts. Anchor
     * space matches CaptionBurnIn: x/y in -1..1, (-1,-1) = bottom-left,
     * (1,1) = top-right of the frame.
     */
    private class JumpingWatermarkOverlay(
        private val bitmap: Bitmap,
        private val mainDurationUs: Long
    ) : BitmapOverlay() {

        // 3 zones — iOS topLeft / rightMiddle / bottomLeft. Each pins the
        // overlay's matching edge ~8% inside the frame edge.
        private val zones: List<OverlaySettings> = listOf(
            zone(bg = -0.92f to 0.92f, overlay = -1f to 1f),   // topLeft
            zone(bg = 0.92f to 0f, overlay = 1f to 0f),        // rightMiddle
            zone(bg = -0.92f to -0.92f, overlay = -1f to -1f)  // bottomLeft
        )
        private val hidden: OverlaySettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(-1f, 1f)
            .setBackgroundFrameAnchor(-0.92f, 0.92f)
            .setAlphaScale(0f)
            .build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            if (mainDurationUs > 0 && presentationTimeUs >= mainDurationUs) return hidden
            val index = ((presentationTimeUs / JUMP_INTERVAL_US) % zones.size).toInt()
            return zones[index]
        }

        private fun zone(bg: Pair<Float, Float>, overlay: Pair<Float, Float>): OverlaySettings =
            OverlaySettings.Builder()
                .setBackgroundFrameAnchor(bg.first, bg.second)
                .setOverlayFrameAnchor(overlay.first, overlay.second)
                .setAlphaScale(1f)
                .build()
    }

    /** Centered @username shown only during the end screen. */
    private class EndScreenUsernameOverlay(
        private val bitmap: Bitmap,
        private val mainDurationUs: Long
    ) : BitmapOverlay() {

        private val visible: OverlaySettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(0f, -0.15f) // slightly below center, under the logo
            .setAlphaScale(1f)
            .build()
        private val hidden: OverlaySettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(0f, -0.15f)
            .setAlphaScale(0f)
            .build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            if (mainDurationUs > 0 && presentationTimeUs >= mainDurationUs) visible else hidden
    }

    // ── Bitmap renderers ──────────────────────────────────────────────

    /**
     * Watermark = [logo] @username / StitchSocial, transparent background with
     * a drop shadow so it reads on any footage. Sizes scale with canvas height
     * (iOS scales off min-dimension/1080; height is fine for portrait).
     */
    private fun renderWatermarkBitmap(
        context: Context,
        creatorUsername: String,
        canvasHeight: Int
    ): Bitmap {
        val display = if (creatorUsername.isBlank()) "StitchSocial" else "@$creatorUsername"

        val logoSize = canvasHeight * 0.05f
        val userFont = canvasHeight * 0.024f
        val brandFont = userFont * 0.62f
        val gap = canvasHeight * 0.012f
        val pad = canvasHeight * 0.01f

        val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = userFont
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f, 0f, 2f, AndroidColor.argb(200, 0, 0, 0))
        }
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(230, 255, 255, 255)
            textSize = brandFont
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(5f, 0f, 1f, AndroidColor.argb(180, 0, 0, 0))
        }

        val userBounds = Rect().also { userPaint.getTextBounds(display, 0, display.length, it) }
        val brandStr = "StitchSocial"
        val brandBounds = Rect().also { brandPaint.getTextBounds(brandStr, 0, brandStr.length, it) }

        val textWidth = maxOf(userBounds.width(), brandBounds.width()).toFloat()
        val textBlockHeight = userBounds.height() + gap + brandBounds.height()

        val contentHeight = maxOf(logoSize, textBlockHeight)
        val bmpW = (pad + logoSize + gap + textWidth + pad).toInt().coerceAtLeast(2)
        val bmpH = (pad + contentHeight + pad).toInt().coerceAtLeast(2)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Logo (left), forced white via SRC_IN so a monochrome mask reads
        // correctly — the drawable is tinted white everywhere else in the app.
        val logo = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.stitchsociallogo)
        }.getOrNull()
        val logoTop = (bmpH - logoSize) / 2f
        if (logo != null) {
            val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(AndroidColor.WHITE, PorterDuff.Mode.SRC_IN)
            }
            canvas.drawBitmap(
                logo,
                Rect(0, 0, logo.width, logo.height),
                RectF(pad, logoTop, pad + logoSize, logoTop + logoSize),
                logoPaint
            )
        }

        // Text block (right of logo), vertically centered.
        val textX = pad + logoSize + gap
        val blockTop = (bmpH - textBlockHeight) / 2f
        val userBaseline = blockTop + userBounds.height()
        canvas.drawText(display, textX, userBaseline, userPaint)
        val brandBaseline = userBaseline + gap + brandBounds.height()
        canvas.drawText(brandStr, textX, brandBaseline, brandPaint)

        return bitmap
    }

    /** Large centered @username for the end screen (transparent background). */
    private fun renderEndScreenUsernameBitmap(
        creatorUsername: String,
        canvasWidth: Int,
        canvasHeight: Int
    ): Bitmap {
        val text = "@$creatorUsername"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = canvasHeight * 0.032f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(8f, 0f, 2f, AndroidColor.argb(200, 0, 0, 0))
        }
        val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        val pad = (canvasHeight * 0.02f).toInt()
        val bmpW = (bounds.width() + pad * 2).coerceIn(2, canvasWidth)
        val bmpH = (bounds.height() + pad * 2).coerceAtLeast(2)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baseline = bmpH / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, bmpW / 2f, baseline, paint)
        return bitmap
    }
}
