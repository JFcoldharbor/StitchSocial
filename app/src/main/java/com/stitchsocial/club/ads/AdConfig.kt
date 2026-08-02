package com.stitchsocial.club.ads

import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.UserTier

/**
 * AdMob identifiers and the revenue split (Android parity with iOS AdConfig).
 *
 * The Android app is a SEPARATE AdMob app from iOS with its own ids — reusing
 * the iOS ones fails at init.
 */
object AdConfig {

    /** Declared in AndroidManifest as com.google.android.gms.ads.APPLICATION_ID. */
    const val APPLICATION_ID = "ca-app-pub-1280726013478181~6950973573"

    /**
     * Google's own always-fill test unit for native advanced. Used for EVERY
     * unit in DEBUG.
     *
     * THIS IS NOT A CONVENIENCE. Loading, viewing or tapping your own LIVE ads
     * — including during development — is invalid traffic, and the penalty is
     * account suspension, not a warning. Routing DEBUG through the test unit
     * makes that impossible to do by accident.
     */
    private const val TEST_NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"

    private fun unit(live: String): String =
        if (BuildConfig.DEBUG) TEST_NATIVE_UNIT else live

    object NativeUnit {
        /**
         * The one live Android unit so far ("Stitch"). Placed on the community
         * partner card, which is CREATOR-OWNED and split by tier — matching the
         * iOS ownership map, where community inventory pays the creator whose
         * room hosts it.
         */
        val community: String get() = unit("ca-app-pub-1280726013478181/6460284914")
    }

    /** Who earns from an impression. */
    enum class OwnerType(val raw: String) {
        /** A creator's content hosts it — split by their tier. */
        CREATOR("thread"),
        /** No creator's content hosts it — Stitch keeps 100%. */
        HOUSE("house")
    }

    /**
     * Creator's share of an impression on their content, by tier. Identical
     * table to iOS — the payout trigger is shared, so a divergence here pays
     * Android creators differently for the same rank.
     */
    fun creatorShare(tier: UserTier?): Double = when (tier) {
        UserTier.ROOKIE -> 0.10
        UserTier.RISING -> 0.12
        UserTier.VETERAN -> 0.15
        UserTier.INFLUENCER -> 0.20
        UserTier.AMBASSADOR -> 0.35
        UserTier.ELITE -> 0.45
        UserTier.PARTNER -> 0.50
        UserTier.LEGENDARY -> 0.55
        UserTier.TOP_CREATOR, UserTier.FOUNDER, UserTier.CO_FOUNDER -> 0.65
        // Businesses BUY inventory, they don't earn from it. A business account
        // that somehow owns a slot must not be paid out of the pool.
        UserTier.BUSINESS -> 0.0
        null -> 0.0
    }

    /** Who an impression pays, resolved at render time. */
    data class ImpressionOwner(
        val type: OwnerType,
        val creatorID: String,
        val tier: UserTier?
    ) {
        val creatorShare: Double
            get() = if (type == OwnerType.HOUSE) 0.0 else creatorShare(tier)
    }
}
