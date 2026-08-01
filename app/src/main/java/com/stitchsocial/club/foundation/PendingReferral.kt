package com.stitchsocial.club.foundation

import android.content.Context
import android.content.SharedPreferences

/**
 * Holds a referral code between the link tap and the signup (iOS parity with
 * Referral/PendingReferral.swift).
 *
 * A referral link is tapped by someone who does NOT have an account. The tap
 * opens the app, then they read the login screen, then they choose Google or
 * email, then they pick a username. The code has to survive all of that — and
 * survive the process being killed partway through, which on Android is
 * routine rather than exceptional.
 *
 * Hence SharedPreferences rather than an in-memory object. This is the one
 * piece of deep-link state that must outlive the process, so it deliberately
 * does NOT follow the [com.stitchsocial.club.events.EventDeepLink] pattern —
 * an event hand-off only has to survive a tab switch.
 */
object PendingReferral {

    private const val PREFS = "stitch_referral"
    private const val KEY_CODE = "pendingReferralCode"
    private const val KEY_CAPTURED_AT = "pendingReferralCapturedAt"

    /**
     * Codes expire 30 days after they're issued (ReferralService's
     * `referralExpirationDays`). Holding one locally past that just means
     * prefilling a field the server will reject.
     */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Stash a code from an incoming link. Uppercased on the way in because
     * `processReferralSignup` uppercases before matching, and links get
     * lowercased in transit by some clients.
     */
    fun capture(context: Context, code: String) {
        val cleaned = code.trim().uppercase()
        if (cleaned.isEmpty()) return
        prefs(context).edit()
            .putString(KEY_CODE, cleaned)
            .putLong(KEY_CAPTURED_AT, System.currentTimeMillis())
            .apply()
    }

    /** The stored code, if there is one and it hasn't gone stale. */
    fun code(context: Context): String? {
        val p = prefs(context)
        val stored = p.getString(KEY_CODE, null)?.takeIf { it.isNotBlank() } ?: return null
        val capturedAt = p.getLong(KEY_CAPTURED_AT, 0L)
        if (capturedAt > 0L && System.currentTimeMillis() - capturedAt > MAX_AGE_MS) {
            clear(context)
            return null
        }
        return stored
    }

    /** Clear once redeemed, or once we know it never will be. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CODE).remove(KEY_CAPTURED_AT).apply()
    }
}
