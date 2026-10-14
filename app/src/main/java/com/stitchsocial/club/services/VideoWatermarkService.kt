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
 *     asset as the iOS bundle) with the creator's @username over it, OR
 *   • an invite end card (Invite with this video, iOS parity): 3.5s of the
 *     clip's last frame, frozen and dimmed, with the creator's avatar, name,
 *     invite code and QR faded in over it. Save video never gets this.
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
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
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

    // Invite end card (iOS parity): 3.5s long, fades in over 0.3s.
    private const val INVITE_CARD_DURATION_MS = 3_500L
    private const val INVITE_CARD_FADE_US = 300_000L

    /**
     * What the invite end card shows. [link] is the full
     * https://stitchsocial.me/invite/CODE URL (the QR encodes it); [avatar] is
     * the creator's profile photo, or null for the pink initial circle.
     */
    data class InviteCard(
        val displayName: String,
        val code: String,
        val link: String,
        val avatar: Bitmap?
    )

    /**
     * Burn the watermark + end screen into [sourceFile] and return a file://
     * Uri of the exported MP4 in cacheDir. [onProgress] receives 0.0..1.0.
     * Throws on Transformer failure (caller falls back to the raw file).
     *
     * With [inviteCard], the regular end screen is replaced by the invite end
     * card; without it the output is exactly the Save video export.
     */
    suspend fun exportWithWatermark(
        context: Context,
        sourceFile: File,
        creatorUsername: String,
        inviteCard: InviteCard? = null,
        onProgress: (Double) -> Unit = {}
    ): Uri = withContext(Dispatchers.IO) {
        val probe = probeSource(sourceFile)
        val mainDurationUs = probe.durationUs
        val (outW, outH) = probe.outputResolution()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "🎬 export: src=${probe.width}x${probe.height} rot=${probe.rotation} " +
                "dur=${mainDurationUs / 1000}ms → canvas ${outW}x$outH")
        }

        // Effects go on each item: Media3 1.4 ignores composition-level video
        // effects for a single sequence, so the watermark was never burned in.
        // Timestamps stay continuous across the sequence, so
        // presentationTimeUs >= mainDurationUs == "we're on the end screen".
        // SCALE_TO_FIT reconciles the clip and end screen resolutions.
        val watermarkBitmap = renderWatermarkBitmap(context, creatorUsername, outH)
        val endUsernameBitmap = creatorUsername.takeIf { it.isNotBlank() && inviteCard == null }
            ?.let { renderEndScreenUsernameBitmap(it, outW, outH) }
        // Invite card: the frozen last frame and the card are each one
        // full-canvas bitmap, drawn over the end screen asset (which only
        // carries the timeline — it's fully covered and muted).
        val frozenBitmap = inviteCard?.let { renderFrozenFrameBitmap(sourceFile, probe, outW, outH) }
        val cardBitmap = inviteCard?.let { renderInviteCardBitmap(it, outW, outH) }
        fun itemEffects(muteAudio: Boolean = false): Effects {
            // Fresh overlay instances per item — each owns its GL texture.
            val overlays = mutableListOf<TextureOverlay>(
                JumpingWatermarkOverlay(watermarkBitmap, mainDurationUs)
            )
            endUsernameBitmap?.let { overlays.add(EndScreenUsernameOverlay(it, mainDurationUs)) }
            if (frozenBitmap != null && cardBitmap != null) {
                overlays.add(EndCardOverlay(frozenBitmap, mainDurationUs, fade = false))
                overlays.add(EndCardOverlay(cardBitmap, mainDurationUs, fade = true))
            }
            return Effects(
                if (muteAudio) listOf<AudioProcessor>(silencer()) else emptyList(),
                listOf<Effect>(
                    Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT),
                    OverlayEffect(ImmutableList.copyOf(overlays))
                )
            )
        }

        // Main clip (keep audio) + concatenated bundled end screen (keep its audio).
        val mainItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(sourceFile)))
            .setRemoveAudio(false)
            .setEffects(itemEffects())
            .build()

        val endUri = Uri.parse("android.resource://${context.packageName}/${R.raw.stitch_end_screen}")
        val endItems = if (inviteCard == null) {
            listOf(
                EditedMediaItem.Builder(MediaItem.fromUri(endUri))
                    .setRemoveAudio(false)
                    .setEffects(itemEffects())
                    .build()
            )
        } else {
            // The bundled end screen is ~2.85s, so repeat it (clipped) until
            // it spans the 3.5s card.
            val assetMs = endScreenDurationMs(context).takeIf { it > 0 } ?: INVITE_CARD_DURATION_MS
            val items = mutableListOf<EditedMediaItem>()
            var remainingMs = INVITE_CARD_DURATION_MS
            while (remainingMs > 0) {
                val takeMs = minOf(remainingMs, assetMs)
                val clipped = MediaItem.Builder()
                    .setUri(endUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs(takeMs).build()
                    )
                    .build()
                items.add(
                    EditedMediaItem.Builder(clipped)
                        .setRemoveAudio(false)
                        .setEffects(itemEffects(muteAudio = true))
                        .build()
                )
                remainingMs -= takeMs
            }
            items
        }

        val sequence = EditedMediaItemSequence(listOf(mainItem) + endItems)

        val composition = Composition.Builder(listOf(sequence))
            .apply {
                // Invite path only: a silent source would otherwise have no
                // audio track to line up with the (muted) end card items.
                if (inviteCard != null) experimentalSetForceAudioTrack(true)
            }
            .build()

        val outputFile = File(context.cacheDir, "StitchWM_${UUID.randomUUID()}.mp4")

        suspendCancellableCoroutine<Uri> { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val transformer = Transformer.Builder(context)
                    // Pin H.264. Without an explicit output MIME type Transformer keeps the
                    // source codec, so an HEVC recording stays HEVC — decodable on iOS but
                    // black on web players and older Android clients. See FastVideoCompressor.
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
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

    /** Length of the bundled end screen asset, 0 if it can't be read. */
    private fun endScreenDurationMs(context: Context): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            context.resources.openRawResourceFd(R.raw.stitch_end_screen).use { afd ->
                retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "end screen probe failed — ${e.message}")
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Zeroes the end screen asset's audio under the invite card (mono or stereo). */
    private fun silencer(): ChannelMixingAudioProcessor =
        ChannelMixingAudioProcessor().apply {
            for (n in 1..2) putChannelMixingMatrix(ChannelMixingMatrix(n, n, FloatArray(n * n)))
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

    /**
     * A full-canvas bitmap shown only once the main clip ends. [fade] ramps
     * its alpha 0→1 over the first 0.3s (the card); without it the bitmap is
     * opaque from the first end frame (the frozen last frame under the card).
     */
    private class EndCardOverlay(
        private val bitmap: Bitmap,
        private val mainDurationUs: Long,
        private val fade: Boolean
    ) : BitmapOverlay() {

        private val hidden: OverlaySettings = OverlaySettings.Builder().setAlphaScale(0f).build()
        private val visible: OverlaySettings = OverlaySettings.Builder().setAlphaScale(1f).build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            if (mainDurationUs <= 0 || presentationTimeUs < mainDurationUs) return hidden
            if (!fade) return visible
            val t = (presentationTimeUs - mainDurationUs).toFloat() / INVITE_CARD_FADE_US
            return if (t >= 1f) visible else OverlaySettings.Builder().setAlphaScale(t.coerceIn(0f, 1f)).build()
        }
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

    /**
     * The clip's last frame, scaled to fit the canvas on black (the same
     * letterboxing Presentation.LAYOUT_SCALE_TO_FIT gives the live clip).
     * Solid black if the frame can't be read.
     */
    private fun renderFrozenFrameBitmap(
        sourceFile: File,
        probe: SourceProbe,
        canvasWidth: Int,
        canvasHeight: Int
    ): Bitmap {
        val out = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(AndroidColor.BLACK)

        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = try {
            retriever.setDataSource(sourceFile.absolutePath)
            // A frame's length short of the end: asking for the exact duration
            // returns null on some decoders.
            retriever.getFrameAtTime(
                (probe.durationUs - 50_000L).coerceAtLeast(0L),
                MediaMetadataRetriever.OPTION_CLOSEST
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "last frame grab failed — ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }

        // The retriever normally returns frames already rotated; if this one
        // came back in storage orientation, rotate it ourselves.
        if (frame != null && (probe.rotation == 90 || probe.rotation == 270)) {
            val displayPortrait = probe.width > probe.height // stored landscape → shown portrait
            if (displayPortrait && frame.width > frame.height) {
                val m = Matrix().apply { postRotate(probe.rotation.toFloat()) }
                frame = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
            }
        }

        frame?.let { f ->
            val scale = minOf(canvasWidth.toFloat() / f.width, canvasHeight.toFloat() / f.height)
            val w = f.width * scale
            val h = f.height * scale
            val left = (canvasWidth - w) / 2f
            val top = (canvasHeight - h) / 2f
            canvas.drawBitmap(
                f,
                Rect(0, 0, f.width, f.height),
                RectF(left, top, left + w, top + h),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        }
        return out
    }

    /**
     * The invite card: a 72% black scrim over the whole canvas with, centered
     * in one column, avatar → name → "Join me on Stitch" → INVITE CODE + code
     * box → QR → link. Sizes are fractions of the canvas width (iOS parity),
     * shrunk uniformly if the column wouldn't fit a short (landscape) canvas.
     */
    private fun renderInviteCardBitmap(
        card: InviteCard,
        canvasWidth: Int,
        canvasHeight: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.argb(184, 0, 0, 0)) // ~72%

        val pink = AndroidColor.rgb(0xFF, 0x3D, 0x8B)
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val cx = canvasWidth / 2f

        fun textPaint(size: Float, color: Int, face: Typeface = bold) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = face
            textAlign = Paint.Align.CENTER
        }
        fun lineHeight(p: Paint) = p.descent() - p.ascent()

        // Natural layout at full scale, measured once to find the fit scale.
        val w = canvasWidth.toFloat()
        val natural = inviteCardHeight(w)
        val s = minOf(1f, canvasHeight * 0.92f / natural)
        val u = w * s

        val avatarD = 0.22f * u
        val gap = 0.03f * u
        val namePaint = textPaint(0.065f * u, AndroidColor.WHITE)
        val joinPaint = textPaint(0.055f * u, pink)
        val labelPaint = textPaint(0.03f * u, AndroidColor.argb(179, 255, 255, 255)).apply { letterSpacing = 0.15f }
        val codePaint = textPaint(0.09f * u, AndroidColor.BLACK, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
        val linkPaint = textPaint(0.035f * u, AndroidColor.argb(191, 255, 255, 255), Typeface.DEFAULT)
        val codePadH = 0.05f * u
        val codePadV = 0.02f * u
        val qrSide = 0.28f * u
        val qrMargin = 0.02f * u

        var y = (canvasHeight - natural * s) / 2f

        // Avatar: photo (center-cropped) or pink circle with the first letter,
        // inside a 4px white ring.
        val r = avatarD / 2f
        val acy = y + r
        val avatar = card.avatar
        if (avatar != null) {
            val crop = maxOf(avatarD / avatar.width, avatarD / avatar.height)
            val shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    postScale(crop, crop)
                    postTranslate(cx - avatar.width * crop / 2f, acy - avatar.height * crop / 2f)
                })
            }
            canvas.drawCircle(cx, acy, r, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        } else {
            canvas.drawCircle(cx, acy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pink })
            val initial = card.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "S"
            val initialPaint = textPaint(avatarD * 0.45f, AndroidColor.WHITE)
            canvas.drawText(initial, cx, acy - (initialPaint.descent() + initialPaint.ascent()) / 2f, initialPaint)
        }
        canvas.drawCircle(cx, acy, r - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = AndroidColor.WHITE
        })
        y += avatarD + gap

        // Display name, ellipsized to the canvas.
        val name = TextUtils.ellipsize(card.displayName, namePaint, w * 0.9f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(name, cx, y - namePaint.ascent(), namePaint)
        y += lineHeight(namePaint) + gap * 0.3f

        canvas.drawText("Join me on Stitch", cx, y - joinPaint.ascent(), joinPaint)
        y += lineHeight(joinPaint) + gap * 1.3f

        canvas.drawText("INVITE CODE", cx, y - labelPaint.ascent(), labelPaint)
        y += lineHeight(labelPaint) + gap * 0.4f

        // Code in a white rounded box.
        val codeW = codePaint.measureText(card.code) + codePadH * 2
        val codeH = lineHeight(codePaint) + codePadV * 2
        canvas.drawRoundRect(
            RectF(cx - codeW / 2f, y, cx + codeW / 2f, y + codeH),
            0.03f * u, 0.03f * u,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE }
        )
        canvas.drawText(card.code, cx, y + codePadV - codePaint.ascent(), codePaint)
        y += codeH + gap * 1.3f

        // QR on white with a small quiet margin; unfiltered so modules stay crisp.
        canvas.drawRect(
            RectF(cx - qrSide / 2f, y, cx + qrSide / 2f, y + qrSide),
            Paint().apply { color = AndroidColor.WHITE }
        )
        InviteService.qrBitmap(card.link)?.let { qr ->
            canvas.drawBitmap(
                qr,
                Rect(0, 0, qr.width, qr.height),
                RectF(cx - qrSide / 2f + qrMargin, y + qrMargin, cx + qrSide / 2f - qrMargin, y + qrSide - qrMargin),
                Paint()
            )
        }
        y += qrSide + gap * 0.6f

        val linkText = card.link.removePrefix("https://").removePrefix("http://")
        canvas.drawText(linkText, cx, y - linkPaint.ascent(), linkPaint)

        return bitmap
    }

    /** Height of the invite card column at full scale for canvas width [w]. */
    private fun inviteCardHeight(w: Float): Float {
        fun line(size: Float, face: Typeface = Typeface.DEFAULT_BOLD) =
            Paint().apply { textSize = size; typeface = face }.let { it.descent() - it.ascent() }
        val gap = 0.03f * w
        return 0.22f * w + gap +
            line(0.065f * w) + gap * 0.3f +
            line(0.055f * w) + gap * 1.3f +
            line(0.03f * w) + gap * 0.4f +
            line(0.09f * w, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)) + 0.04f * w + gap * 1.3f +
            0.28f * w + gap * 0.6f +
            line(0.035f * w, Typeface.DEFAULT)
    }
}
