package com.stitchsocial.club.services

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.stitchsocial.club.BuildConfig

/**
 * The real display shape of a video file: width ÷ height AFTER rotation.
 *
 * Android uploads used to write a hard-coded 9/16 for every video, so a
 * landscape import was recorded as phone-shaped and cropped to ~25% of its
 * width in every feed. This reads the container header (no decode), so it
 * costs a few milliseconds.
 *
 * A phone recording is usually stored 1920×1080 with a 90° rotation tag;
 * the rotation is what makes it portrait, so it has to be applied.
 *
 * Returns null when the file can't be read — callers keep their default.
 */
object VideoAspectProbe {

    /** From a local file path (a "file://" prefix is tolerated). */
    fun aspectRatio(path: String): Double? {
        val clean = path.removePrefix("file://")
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(clean)
            fromRetriever(retriever)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("📐 ASPECT: could not probe $clean - ${e.message}") }
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** From a content:// or file:// Uri. */
    fun aspectRatio(context: Context, uri: Uri): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fromRetriever(retriever)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("📐 ASPECT: could not probe $uri - ${e.message}") }
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun fromRetriever(retriever: MediaMetadataRetriever): Double? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val quarterTurn = ((rotation % 360) + 360) % 180 == 90
        val displayWidth = if (quarterTurn) height else width
        val displayHeight = if (quarterTurn) width else height
        val aspect = displayWidth.toDouble() / displayHeight.toDouble()
        if (BuildConfig.DEBUG) { println("📐 ASPECT: ${width}x$height rot $rotation -> $aspect") }
        return aspect
    }
}
