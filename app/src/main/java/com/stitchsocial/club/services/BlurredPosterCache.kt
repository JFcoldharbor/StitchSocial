package com.stitchsocial.club.services

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Scale
import coil.size.Size
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.MediaFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The fit-mode backdrop: a poster or image shrunk to ~24 px wide, cached per URL.
 *
 * Drawn back up to full screen with bilinear filtering, a 24-px image IS the
 * blur — the same look on every phone. Compose's Modifier.blur does nothing
 * below API 31, so it can't be used here. A tiny box-blur pass on the shrunk
 * bitmap softens the bilinear grid so it reads as a blur, not a mosaic.
 *
 * Never a second video decoder: videos use their poster/thumbnail.
 *
 * Layer 2 (cache). Each entry is a few KB, so the bound is generous.
 */
object BlurredPosterCache {

    private val cache = LruCache<String, Bitmap>(96)

    /** Already built, or null. Cheap — for the first frame. */
    fun peek(url: String): Bitmap? = cache.get(url)

    /** Builds (or returns) the shrunk bitmap for [url]. Null when the image won't load. */
    suspend fun load(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }

        val request = ImageRequest.Builder(context)
            .data(url)
            // Let the decoder subsample instead of inflating a full-size poster.
            .size(Size(Dimension(MediaFit.BACKDROP_WIDTH_PX * 4), Dimension.Undefined))
            .scale(Scale.FIT)
            .allowHardware(false)
            .build()

        val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
        val drawable = (result as? SuccessResult)?.drawable ?: return null

        val shrunk = withContext(Dispatchers.Default) {
            runCatching {
                val source = drawable.toBitmap()
                val w = MediaFit.BACKDROP_WIDTH_PX
                val h = (w * source.height.toFloat() / source.width.coerceAtLeast(1)).toInt().coerceIn(1, w * 4)
                boxBlur(Bitmap.createScaledBitmap(source, w, h, true))
            }.getOrNull()
        } ?: return null

        cache.put(url, shrunk)
        if (BuildConfig.DEBUG) { println("🌫️ BACKDROP: built ${shrunk.width}x${shrunk.height} for $url") }
        return shrunk
    }

    /** One 3×3 box-blur pass. The bitmap is ~24×43, so this is nothing. */
    private fun boxBlur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val input = IntArray(w * h)
        src.getPixels(input, 0, w, 0, 0, w, h)
        val output = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val c = input[yy * w + xx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        n++
                    }
                }
                output[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return Bitmap.createBitmap(output, w, h, Bitmap.Config.ARGB_8888)
    }
}
