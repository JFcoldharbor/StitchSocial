package com.stitchsocial.club.foundation

/**
 * Media fit — when a post fills the screen and when it is shown whole.
 *
 * Portrait and square media fills the phone and crops what doesn't fit. Anything
 * wider than tall fits on black, because filling a portrait screen with a 16:9
 * clip throws away about 74% of the frame.
 *
 * Sponsored video follows the same rule, full screen like any post. A flyer (a
 * still) is never cropped, so its words survive: it shows whole, as large as the
 * screen allows. Google's ads don't come through here. iOS draws the same rule
 * (MediaFit.swift).
 *
 * Layer 1: no Android or Compose dependencies.
 */
enum class MediaFitMode(val rawValue: String) {
    FILL("fill"),
    FIT("fit")
}

object MediaFit {
    /** Anything up to this shape fills the screen in auto. */
    const val FILL_CUTOFF = 1.0

    /** What a missing or unusable shape means: a 9:16 phone video. */
    const val DEFAULT_ASPECT = 9.0 / 16.0

    /** Null, NaN, infinite or <= 0 means 9/16. */
    fun sanitizedAspect(aspect: Double?): Double =
        if (aspect == null || aspect.isNaN() || aspect.isInfinite() || aspect <= 0.0) DEFAULT_ASPECT
        else aspect

    /** "fill" / "fit" pass through; anything else, "auto" and null included, is auto. */
    fun parseOverride(raw: Any?): MediaFitMode? = when ((raw as? String)?.trim()?.lowercase()) {
        "fill" -> MediaFitMode.FILL
        "fit" -> MediaFitMode.FIT
        else -> null
    }

    /** A sponsored video's stored fit, if it picked one. Otherwise null, the normal rule. */
    fun adVideoOverride(fit: String?): String? = parseOverride(fit)?.rawValue

    /** A flyer shows whole unless the ad asked to fill. */
    fun adStillOverride(fit: String?): String =
        if (parseOverride(fit) == MediaFitMode.FILL) "fill" else "fit"

    /** The stored `fit` value: "auto" | "fill" | "fit", defaulting to "auto". */
    fun normalizedFit(raw: Any?): String = parseOverride(raw)?.rawValue ?: "auto"

    /** mediaFitMode(aspect, override) from the contract. */
    fun mode(aspect: Double?, override: String?): MediaFitMode {
        parseOverride(override)?.let { return it }
        return if (sanitizedAspect(aspect) <= FILL_CUTOFF) MediaFitMode.FILL else MediaFitMode.FIT
    }

    /**
     * fitRect(aspect, box): the largest rect with [aspect] inside the box, centred
     * in it. Returned as (left, top, width, height) in the box's own units.
     */
    fun fitRect(aspect: Double?, left: Float, top: Float, width: Float, height: Float): FitRect {
        val a = sanitizedAspect(aspect).toFloat()
        if (width <= 0f || height <= 0f) return FitRect(left, top, 0f, 0f)
        var w = width
        var h = w / a
        if (h > height) {
            h = height
            w = h * a
        }
        return FitRect(left + (width - w) / 2f, top + (height - h) / 2f, w, h)
    }

    /** Numeric `aspectRatio` from a Firestore map — Long or Double, never forced. */
    fun aspectFrom(raw: Any?): Double? =
        (raw as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 }
}

data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)
