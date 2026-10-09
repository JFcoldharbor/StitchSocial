/*
 * BrandDealSplit.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Config — how a brand deal's payout is split, by rank.
 *
 * The creator's share grows with rank, like the ad revenue share: Influencer
 * 65/35 up to Top Creator 92/8. Display only — the server computes every
 * payout (BRAND_DEAL_CREATOR_SHARE in StitchSocialFunctions/index.js) and locks
 * a creator's split onto the deal when the brand accepts them. Keyed by the
 * rank's stored value, so it doesn't depend on either UserTier enum here.
 * Mirrors iOS BrandDealSplit.
 */

package com.stitchsocial.club.services

import kotlin.math.roundToInt

object BrandDealSplit {
    const val LOWEST_CREATOR_SHARE = 0.65
    const val HIGHEST_CREATOR_SHARE = 0.92

    private val byTier = mapOf(
        "influencer" to 0.65,
        "ambassador" to 0.80,
        "elite" to 0.82,
        "partner" to 0.85,
        "legendary" to 0.88,
        "top_creator" to 0.92,
        "founder" to 0.92,
        "co_founder" to 0.92,
    )

    /** The creator's share for a rank, or null below Influencer (marketplace closed). */
    fun creatorShare(tierRaw: String?): Double? = byTier[tierRaw?.lowercase()]

    /** "80%" — for labels. */
    fun percent(share: Double): String = "${(share * 100).roundToInt()}%"
}
