/*
 * ThreadCollageService.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Mirror of iOS ThreadCollageService.swift with the post-fix trim
 * semantics baked in from day one:
 *
 *   • User-trimmed clip durations are never overwritten by a re-allocator.
 *   • seedDefaultsForNewClips() only touches clips with allocatedDuration
 *     == 0 (newly added). Existing values are preserved across add/remove.
 *   • totalCollageDuration is the SUM of clip durations. isOverBudget
 *     and excessSeconds drive the Build-button alert in the selection
 *     view (Phase 3).
 *   • When CoreVideoMetadata.creatorName is empty, the service falls
 *     back to a Firestore lookup of users/{creatorID}.username so the
 *     watermark doesn't render as just "@".
 *
 * Phase 1 / Series of 5: state + selection + budget tracking + username
 * fallback only. Asset loading and the Media3 Transformer composition
 * pipeline land in Phase 4.
 */

package com.stitchsocial.club.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.CoreVideoMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ThreadCollageService {

    companion object {
        private const val TAG = "ThreadCollageService"

        /** Max response clips a user can add alongside the main clip. */
        const val MAX_RESPONSE_CLIPS = 5

        /** Default seed duration for a freshly added response clip.
         *  Small enough that stacking responses doesn't immediately blow the
         *  budget without user input. */
        private const val DEFAULT_RESPONSE_SEED_SECONDS = 8.0
    }

    // ── State ──────────────────────────────────────────────────────────

    var configuration: CollageConfiguration = CollageConfiguration()
        private set

    private val _state = MutableStateFlow<CollageState>(CollageState.Idle)
    val state: StateFlow<CollageState> = _state.asStateFlow()

    private val _selectedClips = MutableStateFlow<List<CollageClip>>(emptyList())
    val selectedClips: StateFlow<List<CollageClip>> = _selectedClips.asStateFlow()

    /** Mirrored as a flow so the selection view's running-total badge
     *  recomputes when any clip's allocatedDuration changes. */
    private val _totalCollageDuration = MutableStateFlow(0.0)
    val totalCollageDuration: StateFlow<Double> = _totalCollageDuration.asStateFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance("stitchfin") }

    // ── Derived budget state ───────────────────────────────────────────

    /** True when the sum of clip durations exceeds the content budget. */
    val isOverBudget: Boolean
        get() = _totalCollageDuration.value > configuration.contentDuration

    /** Seconds the user must trim off (any clip) before Build can run.
     *  Rounded up so "0.4s over" surfaces as "1s over" in the alert. */
    val excessSeconds: Int
        get() = maxOf(0, kotlin.math.ceil(_totalCollageDuration.value - configuration.contentDuration).toInt())

    /** Build is allowed when there's at least one main clip; over-budget is
     *  surfaced via alert at tap time, not by disabling the button. */
    val canBuildCollage: Boolean
        get() = _selectedClips.value.any { it.isMainClip }

    // ── Clip Selection ─────────────────────────────────────────────────

    /**
     * Set the parent/main video for the collage. Replaces any existing
     * main clip; preserves trim state on existing response clips.
     */
    fun setMainVideo(video: CoreVideoMetadata) {
        val clip = CollageClip(
            id = video.id,
            videoMetadata = video,
            originalDuration = video.duration,
            isMainClip = true,
        )

        // Drop any prior main clip but keep responses.
        val newList = _selectedClips.value.filterNot { it.isMainClip }.toMutableList()
        newList.add(0, clip)
        _selectedClips.value = newList

        // The watermark layer prepends its own "@"; store the raw username
        // without one. Strips any stray leading "@" that may have crept
        // in via a different code path.
        configuration.creatorUsername = video.creatorName
            .trim()
            .removePrefix("@")

        _state.value = CollageState.SelectingClips
        seedDefaultsForNewClips()
        recomputeTotal()

        // Watermark fallback. If the video doc didn't carry a creatorName
        // (older posts, missed backfill), look up users/{creatorID}.username
        // so the rendered watermark doesn't read as just "@".
        if (configuration.creatorUsername.isEmpty()) {
            serviceScope.launch {
                fetchUsername(video.creatorID)?.let { username ->
                    configuration.creatorUsername = username
                }
            }
        }
    }

    /**
     * Toggle a response clip in/out of the collage. Returns false if the
     * user is at the response cap and tried to add another; true otherwise.
     * User trim state on OTHER clips is never touched here.
     */
    fun toggleResponseVideo(video: CoreVideoMetadata): Boolean {
        val current = _selectedClips.value.toMutableList()
        val existing = current.indexOfFirst { it.id == video.id && !it.isMainClip }

        return if (existing >= 0) {
            // Remove.
            current.removeAt(existing)
            _selectedClips.value = current
            seedDefaultsForNewClips()  // no-op for existing clips, defensive only
            recomputeTotal()
            true
        } else {
            val responseCount = current.count { !it.isMainClip }
            if (responseCount >= MAX_RESPONSE_CLIPS) return false

            current.add(
                CollageClip(
                    id = video.id,
                    videoMetadata = video,
                    originalDuration = video.duration,
                    isMainClip = false,
                )
            )
            _selectedClips.value = current
            seedDefaultsForNewClips()
            recomputeTotal()
            true
        }
    }

    /** True if the given video is currently in the selection (any role). */
    fun isSelected(videoID: String): Boolean =
        _selectedClips.value.any { it.id == videoID }

    /** Number of response clips currently selected (excludes main). */
    val responseClipCount: Int
        get() = _selectedClips.value.count { !it.isMainClip }

    // ── Trim Persistence ───────────────────────────────────────────────

    /**
     * Called from ClipTrimView (Phase 2) when the user taps Done.
     * Writes trimStart + allocatedDuration to the matching clip. Subsequent
     * clip add/remove will NOT overwrite these values.
     */
    fun applyTrim(clipID: String, trimStart: Double, allocatedDuration: Double) {
        val current = _selectedClips.value.toMutableList()
        val idx = current.indexOfFirst { it.id == clipID }
        if (idx < 0) return

        current[idx] = current[idx].copy(
            trimStart = trimStart.coerceAtLeast(0.0),
            allocatedDuration = allocatedDuration.coerceAtLeast(0.0),
        )
        _selectedClips.value = current
        recomputeTotal()
    }

    /**
     * Called from ClipTrimView when the trim view discovers the real
     * source duration from the loaded media (the
     * CoreVideoMetadata.duration field was missing or stale). Writes the
     * value into the clip so the budget badge and trim handles agree.
     */
    fun updateOriginalDuration(clipID: String, duration: Double) {
        if (duration <= 0.0) return
        val current = _selectedClips.value.toMutableList()
        val idx = current.indexOfFirst { it.id == clipID }
        if (idx < 0) return

        current[idx] = current[idx].copy(originalDuration = duration)
        _selectedClips.value = current
        seedDefaultsForNewClips()  // re-seed if allocatedDuration was 0
        recomputeTotal()
    }

    // ── Seeding (NOT a re-allocator) ──────────────────────────────────

    /**
     * Seed defaults on any clip with allocatedDuration == 0 (newly added,
     * or just had its originalDuration filled in). Existing user trims
     * are preserved.
     */
    private fun seedDefaultsForNewClips() {
        val current = _selectedClips.value.toMutableList()
        var changed = false
        for (i in current.indices) {
            val clip = current[i]
            if (clip.allocatedDuration > 0.0) continue
            if (clip.originalDuration <= 0.0) continue  // wait for real duration

            val seed = if (clip.isMainClip) {
                // Main fills the content budget by default; user trims down.
                minOf(clip.originalDuration, configuration.contentDuration)
            } else {
                minOf(clip.originalDuration, DEFAULT_RESPONSE_SEED_SECONDS)
            }
            current[i] = clip.copy(allocatedDuration = seed)
            changed = true
        }
        if (changed) _selectedClips.value = current
    }

    private fun recomputeTotal() {
        _totalCollageDuration.value =
            _selectedClips.value.sumOf { it.allocatedDuration }
    }

    // ── Cancel / Reset ────────────────────────────────────────────────

    fun cancel() {
        _selectedClips.value = emptyList()
        _state.value = CollageState.Idle
        configuration = CollageConfiguration()
        recomputeTotal()
    }

    // ── Build Pipeline (Media3 Transformer) ───────────────────────────

    /**
     * Compose every selected clip (trimmed per allocatedDuration) into a
     * single MP4 with a corner watermark. Suspends until the export
     * finishes or fails. Updates `state` along the way:
     *
     *   LoadingAssets → Composing → Exporting(progress) → Completed(uri)
     *
     * Throws on cancellation or transformer failure.
     */
    @OptIn(UnstableApi::class)
    suspend fun buildCollage(context: Context): Uri = withContext(Dispatchers.IO) {
        val clips = _selectedClips.value
        require(clips.any { it.isMainClip }) { "Main clip is required" }
        require(!isOverBudget) {
            "Collage is ${excessSeconds}s over the ${configuration.contentDuration.toInt()}s budget"
        }

        _state.value = CollageState.LoadingAssets

        // Build EditedMediaItem per clip. ClippingConfiguration handles
        // trim — the iOS fix-equivalent path: trimStart/allocatedDuration
        // ARE the source of truth; the allocator never reshuffles them.
        val editedItems = clips.map { clip ->
            val startMs = (clip.trimStart * 1000).toLong()
            val endMs = ((clip.trimStart + clip.allocatedDuration) * 1000).toLong()
            val mediaItem = MediaItem.Builder()
                .setUri(clip.videoMetadata.videoURL)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(false)
                .build()
        }

        _state.value = CollageState.Composing

        // One sequential sequence: clips play one after another.
        val sequence = EditedMediaItemSequence(editedItems)

        // Output canvas + watermark. Both wrapped in a Composition-level
        // OverlayEffect / Presentation so they apply to the entire export.
        val (outW, outH) = configuration.outputResolution.let { it.width to it.height }
        val outputPresentation = Presentation.createForWidthAndHeight(
            outW, outH, Presentation.LAYOUT_SCALE_TO_FIT
        )
        val watermarkBitmap = renderWatermarkBitmap(
            username = configuration.creatorUsername,
            brandText = configuration.brandingText,
            canvasWidth = outW,
            canvasHeight = outH,
        )
        val watermarkOverlay = BitmapOverlay.createStaticBitmapOverlay(
            watermarkBitmap,
            OverlaySettings.Builder()
                // Anchor bottom-right of output, with the overlay's
                // bottom-right edge meeting that anchor.
                .setBackgroundFrameAnchor(0.92f, -0.92f)
                .setOverlayFrameAnchor(1f, -1f)
                .build()
        )
        val overlayEffect = OverlayEffect(ImmutableList.of(watermarkOverlay))

        // Effects() takes List<Effect> for the video list. Kotlin infers
        // ImmutableList.of(outputPresentation, overlayEffect) as
        // ImmutableList<GlEffect> because that's the most-specific common
        // ancestor — neither the right collection type nor the right
        // generic. Explicit listOf<Effect>(...) widens both.
        val videoEffects: List<Effect> = listOf(outputPresentation, overlayEffect)
        val composition = Composition.Builder(listOf(sequence))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val outputFile = File(context.cacheDir, "collage_${UUID.randomUUID()}.mp4")

        _state.value = CollageState.Exporting(0.0)

        val resultUri = suspendCancellableCoroutine<Uri> { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "✅ build complete — ${outputFile.length() / 1024} KB")
                            }
                            if (cont.isActive) cont.resume(Uri.fromFile(outputFile))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (BuildConfig.DEBUG) {
                                Log.w(TAG, "❌ build failed — ${exportException.message}")
                            }
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                cont.invokeOnCancellation {
                    mainHandler.post {
                        runCatching { transformer.cancel() }
                    }
                }

                transformer.start(composition, outputFile.absolutePath)

                // Poll Transformer.getProgress every 200ms while running.
                val holder = ProgressHolder()
                val tick = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        val state = transformer.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val fraction = (holder.progress / 100.0).coerceIn(0.0, 0.99)
                            _state.value = CollageState.Exporting(fraction)
                        }
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            mainHandler.postDelayed(this, 200)
                        }
                    }
                }
                mainHandler.postDelayed(tick, 200)
            }
        }

        _state.value = CollageState.Completed(resultUri)
        resultUri
    }

    // ── Watermark Bitmap Renderer ─────────────────────────────────────

    /**
     * Render the watermark to a Bitmap once. Two-line layout:
     *   • "@username" — large, bold
     *   • brandingText — smaller, dimmer
     *
     * IMPORTANT: the "@" is prepended HERE, exactly once. The
     * configuration's creatorUsername field stores the raw value (no "@");
     * the iOS bug where "@@" showed up came from a caller and a renderer
     * both prepending the prefix. Anchored visually so the BitmapOverlay's
     * bottom-right corner sits ~8% from the output edge.
     */
    private fun renderWatermarkBitmap(
        username: String,
        brandText: String,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Bitmap {
        val display = if (username.isEmpty()) brandText else "@$username"

        // Watermark size scales with the output canvas height.
        val baseFont = canvasHeight * 0.024f      // ~26pt at 1080p
        val brandFont = baseFont * 0.55f          // ~14pt
        val padding = baseFont * 0.6f
        val gap = baseFont * 0.25f

        val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = baseFont
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f, 0f, 2f, AndroidColor.argb(180, 0, 0, 0))
        }
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(200, 255, 255, 255)
            textSize = brandFont
            typeface = Typeface.DEFAULT
            setShadowLayer(3f, 0f, 1f, AndroidColor.argb(150, 0, 0, 0))
        }

        // Measure both lines so the bitmap snugly contains them.
        val userBounds = Rect().also { userPaint.getTextBounds(display, 0, display.length, it) }
        val brandBounds = Rect().also { brandPaint.getTextBounds(brandText, 0, brandText.length, it) }
        val contentWidth = maxOf(userBounds.width(), brandBounds.width())
        val contentHeight = userBounds.height() + gap.toInt() + brandBounds.height()

        val bmpWidth = (contentWidth + padding * 2).toInt().coerceAtLeast(64)
        val bmpHeight = (contentHeight + padding * 2).toInt().coerceAtLeast(64)

        val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Faint dark plate behind the text for legibility on any background.
        val plate = Paint().apply {
            color = AndroidColor.argb(95, 0, 0, 0)
        }
        canvas.drawRoundRect(
            0f, 0f, bmpWidth.toFloat(), bmpHeight.toFloat(),
            padding * 0.6f, padding * 0.6f, plate
        )

        // Draw username (baseline = top + padding + textHeight).
        val userBaseline = padding + userBounds.height()
        canvas.drawText(display, padding, userBaseline, userPaint)
        // Draw branding below.
        val brandBaseline = userBaseline + gap + brandBounds.height()
        canvas.drawText(brandText, padding, brandBaseline, brandPaint)

        return bitmap
    }

    // ── Watermark Username Fallback ───────────────────────────────────

    /**
     * Look up users/{userID}.username from Firestore. Returns the raw value
     * with any stray leading "@" stripped, or null if missing.
     */
    private suspend fun fetchUsername(userID: String): String? = withContext(Dispatchers.IO) {
        if (userID.isEmpty()) return@withContext null
        runCatching {
            val snap = db.collection("users").document(userID).get().await()
            val data = snap.data ?: return@runCatching null
            val raw = (data["username"] as? String)
                ?: (data["displayName"] as? String)
                ?: return@runCatching null
            val cleaned = raw.trim().removePrefix("@")
            if (cleaned.isEmpty()) null else cleaned
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w(TAG, "fetchUsername failed for $userID — ${it.message}")
        }.getOrNull()
    }
}
