/*
 * BoostCalculator.kt - STITCH SOCIAL BOOST ECONOMICS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Canonical boost economics — pure functions, no I/O. Port of iOS
 * BoostCalculator.swift (interest-streak spec §9/§10). Single source of truth
 * so the engine, UI, and backend never disagree.
 *
 * Two dials:
 *   MAGNITUDE — how much a boost adds to discoverabilityScore (0..1).
 *   DURATION  — how long it stays active before falling back to earned score.
 */

package com.stitchsocial.club.engagement

import java.util.Date
import kotlin.math.log10
import kotlin.math.min

object BoostCalculator {

    // MARK: - Magnitude (§9.2 / §9.3)
    const val coinBoostCoeff = 0.03      // paid = coeff * log10(1 + coins)
    const val coinBoostCap = 0.15        // max paid magnitude
    const val coinBoostMinCoins = 20     // activation floor — below this, no boost
    const val freePromote = 0.10         // weekly streak grant (== existing isPromoted)
    const val totalBoostCap = 0.20       // hard ceiling on ALL boost combined

    /** Paid-boost magnitude from pooled coins. 0 below the activation floor. */
    fun paidMagnitude(coins: Int): Double {
        if (coins < coinBoostMinCoins) return 0.0
        return min(coinBoostCap, coinBoostCoeff * log10(coins.toDouble() + 1.0))
    }

    /** Combined author/post boost magnitude (free promote + paid), capped. */
    fun combinedMagnitude(coins: Int, hasFreePromote: Boolean): Double {
        val free = if (hasFreePromote) freePromote else 0.0
        return min(totalBoostCap, free + paidMagnitude(coins))
    }

    // MARK: - Duration (§10.1 / §10.2)

    /** Coin-boost duration in MINUTES (diminishing, capped 60). 0 below floor. */
    fun coinBoostMinutes(coins: Int): Int {
        if (coins < coinBoostMinCoins) return 0
        return when {
            coins >= 1000 -> 60
            coins >= 100 -> 30
            else -> 10                    // 20..99
        }
    }

    /** Weekly streak free-boost duration in DAYS by streak length. */
    fun streakBoostDays(streak: Int): Int = when {
        streak < 7 -> 0
        streak < 14 -> 2
        streak < 21 -> 4
        else -> 7
    }

    // MARK: - Live boost (magnitude active right now)

    /** Boost magnitude to add to a post's discoverabilityScore at [now]. 0 once
     *  both windows have expired — boosts are never permanent. */
    fun activeMagnitude(
        coins: Int,
        boostExpiresAt: Date?,
        freeBoostExpiresAt: Date?,
        now: Date = Date()
    ): Double {
        val paidActive = boostExpiresAt?.let { now.before(it) } ?: false
        val freeActive = freeBoostExpiresAt?.let { now.before(it) } ?: false
        return combinedMagnitude(if (paidActive) coins else 0, freeActive)
    }
}
