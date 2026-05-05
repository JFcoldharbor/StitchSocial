/*
 * ReactionCompositor.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Merges a camera-only recording with a source video into a single
 * split-screen MP4 matching the user's chosen ReactionLayout. Mirrors
 * iOS ReactionCompositor.compositeWithVideo conceptually, but uses
 * AndroidX media3 Transformer's Composition API (parallel sequences +
 * VideoCompositorSettings) instead of a hand-rolled GL pipeline.
 *
 * R3 SCOPE — basic split-screen / PiP. Does NOT yet handle:
 *   • Source pause/freeze windows (R4)
 *   • Source clipping to camera duration (assumes source ≥ camera)
 *   • Looping source if shorter than camera
 *   • Per-zone front/back camera (R5)
 */

package com.stitchsocial.club

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.stitchsocial.club.views.ReactionLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
object ReactionCompositor {

    // Output canvas. Portrait 1080x1920 to match iOS render size and the
    // app's edge-to-edge layout. If the input cameras / sources are at
    // different resolutions, Transformer scales them per OverlaySettings
    // before compositing.
    private const val OUT_W = 1080
    private const val OUT_H = 1920

    /**
     * Composite camera + source into a single MP4 matching `layout` and
     * `cameraIsTop`. Runs Transformer on the main looper; suspends until
     * the export completes (or fails).
     */
    suspend fun composite(
        context: Context,
        cameraUri: Uri,
        sourceUri: Uri,
        layout: ReactionLayout,
        cameraIsTop: Boolean,
        sourceStartMs: Long = 0L,
        keepSourceAudio: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Uri = withContext(Dispatchers.IO) {
        val outputFile = File(context.cacheDir, "reaction_${UUID.randomUUID()}.mp4")

        // Build two parallel sequences — Composition runs them simultaneously
        // on the same output timeline. Camera's audio is the master; source
        // audio is muted in this MVP so we don't get two voices fighting.
        val cameraItem = EditedMediaItem.Builder(MediaItem.fromUri(cameraUri))
            .setRemoveAudio(false)
            .build()
        // Apply the user's scrub offset to the source via ClippingConfiguration
        // so the merged output starts the source at the same frame the
        // preview was on when recording began.
        val sourceMediaItem = MediaItem.Builder()
            .setUri(sourceUri)
            .apply {
                if (sourceStartMs > 0L) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(sourceStartMs)
                            .build()
                    )
                }
            }
            .build()
        val sourceItem = EditedMediaItem.Builder(sourceMediaItem)
            .setRemoveAudio(!keepSourceAudio)
            .build()

        // Order matters for audio: the FIRST sequence's audio is used.
        val cameraSequence = EditedMediaItemSequence(cameraItem)
        val sourceSequence = EditedMediaItemSequence(sourceItem)

        val composition = Composition.Builder(listOf(cameraSequence, sourceSequence))
            .setVideoCompositorSettings(buildCompositorSettings(layout, cameraIsTop))
            .build()

        suspendCancellableCoroutine { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            println("🎬 REACTION COMP: complete — ${outputFile.length() / 1024} KB")
                            if (continuation.isActive) continuation.resume(Uri.fromFile(outputFile))
                        }
                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            println("🎬 REACTION COMP: failed — ${exportException.message}")
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    .build()

                continuation.invokeOnCancellation {
                    mainHandler.post { try { transformer.cancel() } catch (_: Exception) {} }
                }

                transformer.start(composition, outputFile.absolutePath)

                // Coarse progress polling — Transformer.getProgress() reads
                // ProgressHolder which fills with 0–100. Call onProgress
                // every ~200ms while running.
                val holder = androidx.media3.transformer.ProgressHolder()
                val tick = object : Runnable {
                    override fun run() {
                        if (!continuation.isActive) return
                        val state = transformer.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((holder.progress / 100f).coerceIn(0f, 0.99f))
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

    // ───── Layout → VideoCompositorSettings ──────────────────────────────
    //
    // VideoCompositorSettings.getOverlaySettings(inputId, presentationTimeUs)
    // returns per-input placement on the output canvas. inputId is the
    // index of the EditedMediaItemSequence in the Composition's list:
    //   0 = camera sequence
    //   1 = source sequence
    //
    // OverlaySettings anchor coords range -1..1 over the OUTPUT frame:
    //   (-1, -1) = bottom-left, (1, 1) = top-right, (0, 0) = center.
    // setScale(sx, sy) scales the input. Half-height = scale.y = 0.5.

    private fun buildCompositorSettings(
        layout: ReactionLayout,
        cameraIsTop: Boolean
    ): VideoCompositorSettings {
        return when (layout) {
            ReactionLayout.SPLIT_50_50 -> splitSettings(topFrac = 0.5f, cameraIsTop = cameraIsTop)
            ReactionLayout.SPLIT_70_30 -> splitSettings(topFrac = 0.7f, cameraIsTop = cameraIsTop)
            ReactionLayout.SPLIT_30_70 -> splitSettings(topFrac = 0.3f, cameraIsTop = cameraIsTop)
            ReactionLayout.PIP -> pipSettings(cameraIsTop = cameraIsTop)
        }
    }

    /**
     * Vertical split. topFrac is the height fraction of the top zone
     * (0.5 = even split, 0.7 = top is 70% tall, etc.). cameraIsTop picks
     * which input ID lives in which zone.
     */
    private fun splitSettings(topFrac: Float, cameraIsTop: Boolean): VideoCompositorSettings {
        // Convert "fraction of output height" to anchor coords.
        // Top-zone center y in anchor space: 1 - topFrac (since y goes -1 bottom .. 1 top).
        // Bottom-zone center y in anchor space: topFrac - 1.
        val topAnchorY = 1f - topFrac
        val bottomAnchorY = topFrac - 1f

        val cameraSettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, if (cameraIsTop) topAnchorY else bottomAnchorY)
            .setBackgroundFrameAnchor(0f, 0f)
            .setScale(1f, if (cameraIsTop) topFrac else (1f - topFrac))
            .setAlphaScale(1f)
            .build()
        val sourceSettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, if (cameraIsTop) bottomAnchorY else topAnchorY)
            .setBackgroundFrameAnchor(0f, 0f)
            .setScale(1f, if (cameraIsTop) (1f - topFrac) else topFrac)
            .setAlphaScale(1f)
            .build()

        return object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = Size(OUT_W, OUT_H)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                return if (inputId == 0) cameraSettings else sourceSettings
            }
        }
    }

    /**
     * PiP — one input fills the whole frame (background), the other is a
     * small bubble in the top-right. Bubble is ~25% width, 25% height.
     */
    private fun pipSettings(cameraIsTop: Boolean): VideoCompositorSettings {
        val bubbleScaleX = 0.25f
        val bubbleScaleY = 0.25f
        // Bubble anchor: top-right with a small inset.
        val bubbleAnchorX = 0.7f
        val bubbleAnchorY = 0.7f

        val backgroundSettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(0f, 0f)
            .setScale(1f, 1f)
            .setAlphaScale(1f)
            .build()
        val bubbleSettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(bubbleAnchorX, bubbleAnchorY)
            .setBackgroundFrameAnchor(0f, 0f)
            .setScale(bubbleScaleX, bubbleScaleY)
            .setAlphaScale(1f)
            .build()

        // cameraIsTop=true → camera is the small bubble, source fills.
        // cameraIsTop=false → source is the bubble, camera fills.
        return object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = Size(OUT_W, OUT_H)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                return when {
                    inputId == 0 && cameraIsTop -> bubbleSettings
                    inputId == 0 && !cameraIsTop -> backgroundSettings
                    inputId == 1 && cameraIsTop -> backgroundSettings
                    else -> bubbleSettings
                }
            }
        }
    }
}
