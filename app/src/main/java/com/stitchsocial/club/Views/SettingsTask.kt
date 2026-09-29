package com.stitchsocial.club.views

import androidx.compose.ui.graphics.Color

/**
 * A thing that blocks something, and is therefore worth interrupting for.
 * iOS parity (Views/SettingsTask.swift).
 *
 * The rule from the handoff, and the reason this type is small: the To-finish
 * card appears ONLY for tasks that block something — a payout that cannot be
 * sent, an email that cannot be confirmed. Never for tips, feature promos or
 * suggestions. Everything else in Settings is in a fixed place that never
 * moves, and this is the one thing on the screen allowed to appear and
 * disappear.
 *
 * The design names five task types. Two can actually be produced by this app
 * and the other three are declared here and never returned, deliberately:
 *
 *   · twoFactor      — there is no multi-factor enrolment in this app
 *   · taxInfo        — Stripe owns KYC and tax forms
 *   · failedPayment  — subscriptions are paid in Hype Coins, so no charge fails
 *
 * They are listed rather than omitted so that wiring one later is a line in
 * the producer rather than a new type, a new row and a new badge.
 */
enum class SettingsTaskKind {
    EMAIL_UNVERIFIED,
    PAYOUT_METHOD_MISSING,
    TWO_FACTOR,
    TAX_INFO,
    FAILED_PAYMENT,
}

/**
 * @param destination which permanent row this deep-links to, so tapping the
 *   pill lands on the row that fixes it rather than on a page about it.
 */
data class SettingsTask(
    val kind: SettingsTaskKind,
    val title: String,
    val reason: String,
    val actionLabel: String,
    val destination: SettingsDestination,
) {
    companion object {
        fun emailUnverified(email: String) = SettingsTask(
            kind = SettingsTaskKind.EMAIL_UNVERIFIED,
            title = "Verify your email",
            reason = if (email.isBlank()) "We can't confirm it's you" else "Sent to $email",
            actionLabel = "Verify",
            destination = SettingsDestination.ACCOUNTS_CENTER,
        )

        fun payoutMethodMissing() = SettingsTask(
            kind = SettingsTaskKind.PAYOUT_METHOD_MISSING,
            title = "Add a payout method",
            reason = "You can't be paid until this is set",
            actionLabel = "Add",
            destination = SettingsDestination.EARNINGS,
        )
    }
}

/** The permanent rows a task can point at. */
enum class SettingsDestination {
    ACCOUNTS_CENTER,
    NOTIFICATIONS,
    PRIVACY,
    SUBSCRIPTIONS,
    CREATOR_STUDIO,
    EARNINGS,
    AMBASSADOR,
    APPEARANCE,
    PLAYBACK,
    HELP,
    TERMS,
}

/**
 * Design tokens for Settings, from the handoff. Kept together so a row cannot
 * quietly invent its own red.
 */
object SettingsTokens {
    val destructive = Color(0xFFE0183C)
    val brand = Color(0xFFF0245F)
    val link = Color(0xFF0E9CC0)

    // Icon tiles: 30dp, radius 8, white glyph.
    val tileNotifications = Color(0xFFFF3B30)
    val tilePrivacy = Color(0xFF1C1C1E)
    val tileSubscriptions = Color(0xFFF0245F)
    val tileCreatorStudio = Color(0xFFF0245F)
    val tileEarnings = Color(0xFF16A34A)
    val tileAmbassador = Color(0xFFA855F7)
    val tileAppearance = Color(0xFF5856D6)
    val tilePlayback = Color(0xFF0E9CC0)
    val tileHelp = Color(0xFF8E8E93)
}
