package com.stitchsocial.club.foundation

/**
 * Media fit — when a post fills the screen and when it is shown whole.
 *
 * One decision, written identically on iOS, Android and the web
 * (MEDIA_FIT_CONTRACT):
 *   - an override of "fill" or "fit" wins;
 *   - otherwise (auto) a shape of width ÷ height <= 0.6 fills, as today,
 *     and anything wider fits.
 *
 * Standard 9:16 phone video is 0.5625, so it keeps filling. 4:5, 3:4, 2:3,
 * square and landscape all fit — drawn as large as possible inside a box
 * (the band, or the sponsored clear area) over a blurred copy of itself.
 *
 * Layer 1: no Android or Compose dependencies.
 */
enum class MediaFitMode(val rawValue: String) {
    FILL("fill"),
    FIT("fit")
}

object MediaFit {
    /** Anything up to this shape fills the screen in auto. */
    const val FILL_CUTOFF = 0.6

    /** What a missing or unusable shape means: a 9:16 phone video. */
    const val DEFAULT_ASPECT = 9.0 / 16.0

    /** Side inset of the sponsored / brand-ad clear area, in dp. */
    const val CLEAR_AREA_SIDE_INSET_DP = 12f

    /** Width the backdrop is shrunk to before it is drawn back up, in px. */
    const val BACKDROP_WIDTH_PX = 24

    /** Black drawn over the backdrop. */
    const val BACKDROP_DIM = 0.30f

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
