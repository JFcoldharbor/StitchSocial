/*
 * CollageConfiguration.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Data model + state for the Thread Collage share feature. Mirrors iOS
 * CollageConfiguration.swift; behavioral parity includes the post-fix
 * trim semantics:
 *
 *   • User-trimmed durations are authoritative — the service does NOT
 *     re-distribute allocations on every clip add/remove. Initial seeds
 *     only run for clips that haven't been touched yet.
 *   • Total collage duration is the SUM of clip durations. If that sum
 *     exceeds contentDuration (60s - watermark), Build is gated behind
 *     a "trim X seconds off any clip" alert.
 *   • 2-second clip minimum (matches the trim view's drag-handle floor).
 *
 * Phase 1 / Series of 5: data + service only. No UI, no build pipeline.
 */

package com.stitchsocial.club.services

import com.stitchsocial.club.foundation.CoreVideoMetadata

// ── Configuration ──────────────────────────────────────────────────────

/**
 * Tunable settings for collage composition + watermark end card.
 * Defaults target a 60-second portrait share.
 */
data class CollageConfiguration(
    /** Total collage duration in seconds (default 60). */
    var totalDuration: Double = 60.0,

    /** How the service seeds initial durations for newly added clips. */
    var timeStrategy: TimeStrategy = TimeStrategy.MAIN_WEIGHTED,

    /**
     * Minimum seconds any single clip can occupy. Matches the 2-second
     * floor inside ClipTrimView so the two layers don't disagree.
     */
    var minimumClipDuration: Double = 2.0,

    /**
     * Maximum seconds the main clip can occupy in the MAIN_WEIGHTED seed.
     * Retained as a seeding hint only — once the user trims, this cap is
     * no longer enforced against their choice.
     */
    var maximumMainClipDuration: Double = 20.0,

    /** Transition between clips. */
    var transitionType: CollageTransition = CollageTransition.CROSS_DISSOLVE,

    /** Transition duration in seconds (consumed from total, not added). */
    var transitionDuration: Double = 0.5,

    /** Creator handle to render on the watermark. The service stores this
     *  WITHOUT a leading "@" — the rendering layer prepends exactly one. */
    var creatorUsername: String = "",

    /** Watermark end-card duration in seconds (included in totalDuration). */
    var watermarkDuration: Double = 3.0,

    /** Watermark text size at 1080p; scaled proportionally at other sizes. */
    var watermarkFontSize: Float = 42.0f,

    /** Branding text shown below the username on the end card. */
    var brandingText: String = "StitchSocial",

    /** Output resolution preset. */
    var outputResolution: OutputResolution = OutputResolution.HD_1080P,

    /** Encoded video bitrate. */
    var videoBitRate: Int = 8_000_000,

    /** Encoded audio bitrate. */
    var audioBitRate: Int = 128_000,

    /** Frame rate. */
    var frameRate: Int = 30,
) {
    /** Seconds available for actual clip content after the watermark card. */
    val contentDuration: Double get() = totalDuration - watermarkDuration
}

// ── Enums ───────────────────────────────────────────────────────────────

enum class TimeStrategy(val displayName: String) {
    EQUAL("Equal"),
    MAIN_WEIGHTED("Main Featured"),
    PROPORTIONAL("Proportional"),
}

enum class CollageTransition(val displayName: String) {
    CUT("Cut"),
    CROSS_DISSOLVE("Dissolve"),
}

enum class OutputResolution(val displayName: String, val width: Int, val height: Int) {
    HD_720P("720p", 720, 1280),
    HD_1080P("1080p", 1080, 1920),
}

// ── Clip Model ──────────────────────────────────────────────────────────

/**
 * A single clip selected for the collage. `allocatedDuration == 0` is
 * treated as "needs a default seed" — the service writes a sensible value
 * the first time it sees the clip. Once the user trims (even to a value
 * matching the seed), the value persists across subsequent state changes.
 */
data class CollageClip(
    val id: String,
    val videoMetadata: CoreVideoMetadata,
    /** Filled when the asset is loaded in Phase 4. */
    var contentUri: android.net.Uri? = null,
    /** Real source duration. Defaults to videoMetadata.duration, may be 0
     *  when Firestore didn't carry the field — Phase 2's trim view probes
     *  the real value from the loaded media and writes it back here. */
    var originalDuration: Double,
    /** How many seconds of the source to include in the collage. */
    var allocatedDuration: Double = 0.0,
    /** Where inside the source to start. */
    var trimStart: Double = 0.0,
    val isMainClip: Boolean,
) {
    /** Computed end of the trim window inside the source clip. */
    val trimEnd: Double
        get() = (trimStart + allocatedDuration).coerceAtMost(originalDuration)
}

// ── State ───────────────────────────────────────────────────────────────

sealed class CollageState {
    object Idle : CollageState()
    object SelectingClips : CollageState()
    object LoadingAssets : CollageState()
    object Composing : CollageState()
    object AddingWatermark : CollageState()
    data class Exporting(val progress: Double) : CollageState()
    data class Completed(val uri: android.net.Uri) : CollageState()
    data class Failed(val error: String) : CollageState()
}
