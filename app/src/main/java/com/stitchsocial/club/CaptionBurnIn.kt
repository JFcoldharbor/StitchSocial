/*
 * CaptionBurnIn.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Renders VideoCaption objects to bitmaps and builds the AndroidX media3
 * OverlayEffect used by VideoExportService to bake captions into the
 * exported MP4. Style is intentionally a SINGLE hardcoded look matching
 * iOS's stripped-down standard caption (white text on black 60% pill,
 * bold 22pt, centered horizontally, position picked by user).
 *
 * No per-caption styling. No animations. No word-level timing. Position
 * is the only user-facing control. Mirror of:
 *   ios: ContextualVideoOverlay → StandardCaptionText (white/black pill)
 *   ios: VideoExportService+TextOverlays.buildCaptionLayer (export side)
 */

package com.stitchsocial.club

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings

@OptIn(UnstableApi::class)
object CaptionBurnIn {

    // Render constants — match iOS standard style.
    // Font sized relative to video height so the look is consistent
    // across resolutions. iOS uses 22pt at ~812pt screen height; we
    // scale that to the actual rendered video height.
    private const val IOS_REFERENCE_FONT_PT = 22f
    private const val IOS_REFERENCE_HEIGHT_PT = 812f

    // Pill padding (in scaled px) — matches iOS hPad=16 vPad=8.
    private const val H_PAD_RATIO = 16f / IOS_REFERENCE_HEIGHT_PT
    private const val V_PAD_RATIO = 8f / IOS_REFERENCE_HEIGHT_PT

    // Max caption width is 85% of the video width (iOS matches).
    private const val MAX_TEXT_WIDTH_FRAC = 0.85f

    /**
     * Build the list of timed BitmapOverlays for the given captions.
     * Empty list means "no captions to burn in" — caller should skip the
     * overlay effect entirely in that case.
     *
     * Position is read from each caption's `position` field, matching
     * Android's per-caption data model (the editor sets this when the
     * caption is created/edited).
     */
    fun buildOverlays(
        captions: List<VideoCaption>,
        videoWidthPx: Int,
        videoHeightPx: Int
    ): List<BitmapOverlay> {
        if (captions.isEmpty() || videoWidthPx <= 0 || videoHeightPx <= 0) return emptyList()

        return captions.mapNotNull { caption ->
            val text = caption.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val bitmap = renderCaptionBitmap(text, videoWidthPx, videoHeightPx)
            TimedCaptionOverlay(
                bitmap = bitmap,
                startTimeUs = (caption.startTime * 1_000_000.0).toLong(),
                endTimeUs = ((caption.startTime + caption.duration) * 1_000_000.0).toLong(),
                position = caption.position
            )
        }
    }

    /**
     * Renders a caption to a Bitmap using the standard iOS style.
     * The bitmap dimensions are kept tight to the text + pill so the
     * overlay positioning is predictable.
     */
    private fun renderCaptionBitmap(
        text: String,
        videoWidthPx: Int,
        videoHeightPx: Int
    ): Bitmap {
        val fontPx = (videoHeightPx * (IOS_REFERENCE_FONT_PT / IOS_REFERENCE_HEIGHT_PT))
        val hPadPx = videoHeightPx * H_PAD_RATIO
        val vPadPx = videoHeightPx * V_PAD_RATIO
        val maxTextWidth = videoWidthPx * MAX_TEXT_WIDTH_FRAC - hPadPx * 2

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = fontPx
            isSubpixelText = true
        }

        // Measure text — wrap if too wide.
        val lines = wrapText(text, textPaint, maxTextWidth)
        val lineHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
        val textHeight = lineHeight * lines.size

        val measuredWidths = lines.map { textPaint.measureText(it) }
        val widestLine = measuredWidths.maxOrNull() ?: 0f

        val pillWidth = (widestLine + hPadPx * 2).coerceAtMost(videoWidthPx * 0.92f)
        val pillHeight = textHeight + vPadPx * 2

        val bitmap = Bitmap.createBitmap(
            pillWidth.toInt().coerceAtLeast(1),
            pillHeight.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)

        // Pill background — black @ 60%, fully rounded (matches iOS pill).
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(153, 0, 0, 0) // 60% black
        }
        val cornerRadius = pillHeight / 2f
        canvas.drawRoundRect(
            RectF(0f, 0f, pillWidth, pillHeight),
            cornerRadius,
            cornerRadius,
            bgPaint
        )

        // Text — vertically centered in pill, horizontally centered per line.
        val baselineFromTop = vPadPx - textPaint.fontMetrics.ascent
        for ((index, line) in lines.withIndex()) {
            val lineWidth = measuredWidths[index]
            val x = (pillWidth - lineWidth) / 2f
            val y = baselineFromTop + index * lineHeight
            canvas.drawText(line, x, y, textPaint)
        }

        return bitmap
    }

    /**
     * Naive greedy word-wrap. Doesn't break individual words, even if
     * they exceed maxWidth — for caption lengths this is fine.
     */
    private fun wrapText(text: String, paint: TextPaint, maxWidth: Float): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)

        val words = text.split(" ")
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                out.add(current.toString())
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        // Cap to 3 lines like iOS lineLimit(3); join the rest into the last.
        return if (out.size <= 3) out else out.take(2) + listOf(out.drop(2).joinToString(" "))
    }

    /**
     * BitmapOverlay subclass that's only visible during a specific time
     * window. media3's `BitmapOverlay.getBitmap(...)` returns a non-null
     * Bitmap, so we control visibility via the alpha scale on the
     * per-frame OverlaySettings — alpha 1f when in the window, 0f outside.
     */
    private class TimedCaptionOverlay(
        private val bitmap: Bitmap,
        private val startTimeUs: Long,
        private val endTimeUs: Long,
        private val position: CaptionPosition
    ) : BitmapOverlay() {

        private val anchorY: Float = anchorYFor(position)

        // Anchor coordinate space in media3 Transformer: x/y range -1..1.
        // (-1, -1) = bottom-left of frame, (1, 1) = top-right. (0, 0) center.
        // We center horizontally (x=0) and pick y from CaptionPosition.
        private val visibleSettings: OverlaySettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, anchorY)
            .setBackgroundFrameAnchor(0f, anchorY)
            .setAlphaScale(1f)
            .build()

        private val hiddenSettings: OverlaySettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, anchorY)
            .setBackgroundFrameAnchor(0f, anchorY)
            .setAlphaScale(0f)
            .build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            return if (presentationTimeUs in startTimeUs..endTimeUs) visibleSettings else hiddenSettings
        }

        private fun anchorYFor(position: CaptionPosition): Float {
            // CaptionPosition.safeOffset on iOS is a fraction-from-top:
            //   .top    -> 0.15
            //   .center -> 0.50
            //   .bottom -> 0.68
            // Convert to media3 anchor (1 = top, -1 = bottom):
            //   anchorY = 1 - 2 * fractionFromTop
            val fractionFromTop = when (position) {
                CaptionPosition.TOP -> 0.15f
                CaptionPosition.CENTER -> 0.50f
                CaptionPosition.BOTTOM -> 0.68f
            }
            return 1f - 2f * fractionFromTop
        }
    }
}
