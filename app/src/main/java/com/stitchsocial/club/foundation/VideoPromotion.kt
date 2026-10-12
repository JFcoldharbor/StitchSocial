/*
 * VideoPromotion.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation — the paid run attached to a post (PROMOTED_POSTS_CONTRACT).
 *
 * `videos/{id}.promotion` exists only while a promotion or ad campaign is
 * attached; the server writes and clears it (firestore.rules refuses it from a
 * client). While it is "promoted now" the post carries the Sponsored label
 * everywhere it renders, and the full-screen views add the CTA button.
 */
package com.stitchsocial.club.foundation

import com.google.firebase.Timestamp
import java.util.Collections
import java.util.Date

data class VideoPromotion(
    val orderID: String = "",
    /** "promotion" | "ad_campaign" */
    val kind: String = "promotion",
    val brandID: String = "",
    /** "scheduled" | "active" | "paused" */
    val status: String = "",
    val startAt: Date? = null,
    /** Null for ad campaigns, which run until their budget ends. */
    val endAt: Date? = null,
    val ctaText: String = "",
    val ctaURL: String = "",
    /** "link" | "landing_claim" */
    val ctaKind: String = "link",
    val landingSlug: String? = null,
    /** "auto" | "fill" | "fit" */
    val fit: String = "auto"
) {
    /**
     * Active or paused, and not past its end. "scheduled" also counts once its
     * start has passed, because the server only flips it hourly.
     */
    fun isLive(now: Date = Date()): Boolean {
        val started = when (status) {
            "active", "paused" -> true
            "scheduled" -> startAt?.let { !now.before(it) } ?: false
            else -> false
        }
        if (!started) return false
        val end = endAt ?: return true
        return now.before(end)
    }

    /** The CTA claims a Landing's welcome perk in-app rather than opening a URL. */
    val isLandingClaim: Boolean get() = ctaKind == "landing_claim" && !landingSlug.isNullOrBlank()

    /** Where the CTA goes outside the app (landing_claim falls back to the /l link). */
    val resolvedCtaURL: String
        get() = if (isLandingClaim && ctaURL.isBlank()) "https://stitchsocial.me/l/$landingSlug" else ctaURL

    val displayCtaText: String get() = ctaText.ifBlank { "Learn More" }

    companion object {
        /** Lenient decode of the `promotion` map. Null when absent or unusable. */
        fun from(raw: Any?): VideoPromotion? {
            val m = raw as? Map<*, *> ?: return null
            fun str(k: String) = (m[k] as? String)?.takeIf { it.isNotBlank() }
            fun date(k: String): Date? = when (val v = m[k]) {
                is Timestamp -> v.toDate()
                is Date -> v
                else -> null
            }
            val status = str("status") ?: return null
            return VideoPromotion(
                orderID = str("orderID") ?: "",
                kind = str("kind") ?: "promotion",
                brandID = str("brandID") ?: "",
                status = status,
                startAt = date("startAt"),
                endAt = date("endAt"),
                ctaText = str("ctaText") ?: "",
                ctaURL = str("ctaURL") ?: "",
                ctaKind = str("ctaKind") ?: "link",
                landingSlug = str("landingSlug"),
                fit = MediaFit.normalizedFit(m["fit"])
            )
        }
    }
}

/**
 * One app session's de-duplication between the Discovery ad position and the
 * organic feeds: a post shown as the ad isn't shown again organically, and a
 * post already seen organically isn't used for the ad position.
 */
object PromotedSessionDedupe {
    private val shownAsAd: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val shownOrganically: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun markAd(videoID: String) { if (videoID.isNotBlank()) shownAsAd.add(videoID) }
    fun markOrganic(videoID: String) { if (videoID.isNotBlank()) shownOrganically.add(videoID) }

    fun wasShownAsAd(videoID: String): Boolean = shownAsAd.contains(videoID)
    fun wasShownOrganically(videoID: String): Boolean = shownOrganically.contains(videoID)
}
