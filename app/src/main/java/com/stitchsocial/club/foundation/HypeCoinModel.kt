/*
 * HypeCoinModel.kt - HYPE COIN SYSTEM MODELS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - HypeCoin data models, packages, balance, transactions
 * Dependencies: UserTier.kt only
 *
 * EXACT PORT: HypeCoinPackage.swift (iOS)
 *
 * ANDROID DELTA:
 *   - Purchases handled via Google Play Billing (iOS uses Apple in-app purchase)
 *   - HypeCoinPackage includes Play Billing productId
 *
 * CACHING NOTE:
 *   - HypeCoinBalance should be cached in HypeCoinCoordinator (1-min TTL)
 *   - Add COIN_BALANCE to CacheType enum in CoreTypes.kt
 *   - Cache key: "coin_balance_{userID}"
 *   - Add to CachingServiceImpl / CacheOptimization file
 */

package com.stitchsocial.club.foundation

import java.util.Date

// ─────────────────────────────────────────────
// MARK: - Coin Packages (Purchase Options)
// ─────────────────────────────────────────────

/**
 * Five purchase tiers — mirrors iOS HypeCoinPackage exactly.
 * Android purchases via Google Play Billing (productId maps to Play Console SKU).
 * Coins are never sold on the web.
 *
 * BATCHING: All packages are static enum values — zero Firestore reads.
 */
enum class HypeCoinPackage(val rawValue: String) {
    STARTER("starter"),
    BASIC("basic"),
    PLUS("plus"),
    PRO("pro"),
    MAX("max");

    /** Coins granted on purchase — matches iOS exactly */
    val coins: Int
        get() = when (this) {
            STARTER -> 100
            BASIC   -> 275
            PLUS    -> 600
            PRO     -> 1_300
            MAX     -> 3_500
        }

    /** USD price — matches iOS exactly */
    val price: Double
        get() = when (this) {
            STARTER -> 1.99
            BASIC   -> 4.99
            PLUS    -> 9.99
            PRO     -> 19.99
            MAX     -> 59.99
        }

    val displayName: String
        get() = when (this) {
            STARTER -> "Starter"
            BASIC   -> "Basic"
            PLUS    -> "Plus"
            PRO     -> "Pro"
            MAX     -> "Max"
        }

    /**
     * Google Play Billing product ID.
     * Configure matching SKUs in Play Console → Monetize → Products → In-app products.
     * Convention: "hype_coins_{rawValue}" e.g. "hype_coins_starter"
     */
    val playBillingProductId: String get() = "hype_coins_$rawValue"

    companion object {
        fun fromRawValue(value: String): HypeCoinPackage? =
            entries.firstOrNull { it.rawValue == value }
    }
}

// ─────────────────────────────────────────────
// MARK: - Coin Value Conversion
// ─────────────────────────────────────────────

// (Removed) HypeCoinValue coin↔dollar converter — mirrors the iOS purge. The
// $0.01 peg lives server-side in the payout engine; the app never shows a
// per-coin dollar value, and there is no user cash-out.

// ─────────────────────────────────────────────
// MARK: - User Coin Balance
// ─────────────────────────────────────────────

/**
 * Firestore path: coin_balances/{userID}
 * CACHING: Cache in HypeCoinCoordinator, 1-min TTL.
 * Real-time Firestore listener keeps this fresh without polling.
 */
data class HypeCoinBalance(
    val userID: String,
    val availableCoins: Int = 0,
    val pendingCoins: Int = 0,       // Earned from subs/tips, not yet available
    val lifetimeEarned: Int = 0,
    val lifetimeSpent: Int = 0,
    val lastUpdated: Date = Date()
) {
    val totalCoins: Int get() = availableCoins + pendingCoins

    companion object {
        /** Empty balance for new users — avoids a Firestore read on first open */
        fun empty(userID: String) = HypeCoinBalance(userID = userID)

        /** Parse from Firestore document data map */
        fun fromMap(userID: String, data: Map<String, Any>): HypeCoinBalance = HypeCoinBalance(
            userID        = userID,
            availableCoins = (data["availableCoins"] as? Number)?.toInt() ?: 0,
            pendingCoins   = (data["pendingCoins"]   as? Number)?.toInt() ?: 0,
            lifetimeEarned = (data["lifetimeEarned"] as? Number)?.toInt() ?: 0,
            lifetimeSpent  = (data["lifetimeSpent"]  as? Number)?.toInt() ?: 0,
            lastUpdated    = (data["lastUpdated"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()
        )
    }
}

// ─────────────────────────────────────────────
// MARK: - Coin Transaction
// ─────────────────────────────────────────────

/**
 * Firestore path: coin_transactions/{transactionID}
 * CACHING: Fetch last 50 on wallet open, cache in WalletViewModel.
 * No TTL needed — transactions are immutable once written.
 */
data class CoinTransaction(
    val id: String,
    val userID: String,
    val type: CoinTransactionType,
    val amount: Int,                         // Negative = debit, positive = credit
    val balanceAfter: Int,
    val relatedUserID: String? = null,
    val relatedSubscriptionID: String? = null,
    val description: String,
    val createdAt: Date = Date()
) {
    companion object {
        fun fromMap(id: String, data: Map<String, Any>): CoinTransaction? {
            val typeStr = data["type"] as? String ?: return null
            val type = CoinTransactionType.fromRawValue(typeStr) ?: return null
            return CoinTransaction(
                id                    = id,
                userID                = data["userID"] as? String ?: return null,
                type                  = type,
                amount                = (data["amount"] as? Number)?.toInt() ?: 0,
                balanceAfter          = (data["balanceAfter"] as? Number)?.toInt() ?: 0,
                relatedUserID         = data["relatedUserID"] as? String,
                relatedSubscriptionID = data["relatedSubscriptionID"] as? String,
                description           = data["description"] as? String ?: "",
                createdAt             = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()
            )
        }
    }
}

/** Matches iOS CoinTransactionType raw values exactly — Firestore field parity */
enum class CoinTransactionType(val rawValue: String) {
    PURCHASE("purchase"),
    SUBSCRIPTION_RECEIVED("sub_received"),
    SUBSCRIPTION_SENT("sub_sent"),
    TIP_RECEIVED("tip_received"),
    TIP_SENT("tip_sent"),
    CASH_OUT("cash_out"),
    REFUND("refund"),
    BONUS("bonus"),
    BOOST_SPEND("boost_spend"),
    TIP_REVERSAL("tip_reversal"),       // Tip taken back (payment refunded)
    SUBSCRIPTION_REVERSAL("sub_reversal"),
    GIFT_REVERSAL("gift_reversal");

    companion object {
        fun fromRawValue(value: String): CoinTransactionType? =
            entries.firstOrNull { it.rawValue == value }
    }
}

// ─────────────────────────────────────────────
// MARK: - Cash Out Request
// ─────────────────────────────────────────────

/**
 * Firestore path: cash_out_requests/{requestID}
 * No caching needed — low frequency, always fetch fresh.
 */
data class CashOutRequest(
    val id: String,
    val userID: String,
    val coinAmount: Int,
    val userTier: UserTier,
    val creatorPercentage: Double,
    val creatorAmount: Double,
    val platformAmount: Double,
    val status: CashOutStatus,
    val payoutMethod: PayoutMethod,
    val createdAt: Date = Date(),
    val processedAt: Date? = null,
    val failureReason: String? = null
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "userID"             to userID,
            "coinAmount"         to coinAmount,
            "userTier"           to userTier.rawValue,
            "creatorPercentage"  to creatorPercentage,
            "creatorAmount"      to creatorAmount,
            "platformAmount"     to platformAmount,
            "status"             to status.rawValue,
            "payoutMethod"       to payoutMethod.rawValue,
            "createdAt"          to com.google.firebase.Timestamp(createdAt)
        )
        processedAt?.let { map["processedAt"] = com.google.firebase.Timestamp(it) }
        failureReason?.let { map["failureReason"] = it }
        return map
    }
}

enum class CashOutStatus(val rawValue: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed");

    companion object {
        fun fromRawValue(value: String): CashOutStatus? =
            entries.firstOrNull { it.rawValue == value }
    }
}

enum class PayoutMethod(val rawValue: String) {
    BANK_TRANSFER("bank_transfer"),
    PAYPAL("paypal"),
    STRIPE("stripe");

    companion object {
        fun fromRawValue(value: String): PayoutMethod? =
            entries.firstOrNull { it.rawValue == value }
    }
}

// ─────────────────────────────────────────────
// MARK: - Revenue Share (Cash Out Split)
// ─────────────────────────────────────────────

/**
 * The creator's share of subscriptions and tips, by rank. Mirrors
 * SUB_TIP_SHARE_BY_TIER in StitchSocialFunctions/index.js (what payouts
 * actually use, for earnings from the 2026-12 period on) and iOS
 * SubscriptionRevenueShare. Pure computation — zero Firestore reads.
 */
object SubscriptionRevenueShare {

    /** Earnings from this date on are paid by rank; shown under the table. */
    const val EFFECTIVE_LABEL = "Dec 1, 2026"

    /** Tier-based creator share — Influencer is 50/50, Ambassador 65%, top 95%. */
    fun creatorShare(tier: UserTier): Double = when (tier) {
        UserTier.ROOKIE      -> 0.30
        UserTier.RISING      -> 0.35
        UserTier.VETERAN     -> 0.40
        UserTier.INFLUENCER  -> 0.50
        UserTier.AMBASSADOR  -> 0.65
        UserTier.ELITE       -> 0.75
        UserTier.PARTNER     -> 0.82
        UserTier.LEGENDARY   -> 0.88
        UserTier.TOP_CREATOR -> 0.95
        UserTier.FOUNDER,
        UserTier.CO_FOUNDER  -> 0.95
        UserTier.BUSINESS    -> 0.00
    }

    /**
     * The private deal (customSubShare) in force now, or null. Reaching the
     * referral goal or being marked permanent keeps it; otherwise it lapses
     * at customSubShareExpiresAt. Same rules as activeSubShareDeal on the server.
     */
    fun activeDeal(
        customSubShare: Double?,
        customSubShareExpiresAt: Date? = null,
        customSubSharePermanent: Boolean = false,
        referralCount: Int = 0,
        referralGoal: Int? = null
    ): Double? {
        val custom = customSubShare ?: return null
        if (custom <= 0.0 || custom > 1.0) return null
        if (referralGoal != null && referralCount >= referralGoal) return custom
        if (customSubSharePermanent) return custom
        if (customSubShareExpiresAt != null && Date() >= customSubShareExpiresAt) return null
        return custom
    }

    /**
     * What pays: the higher of the rank share and an active deal. A deal never
     * lowers a creator's share. Business accounts don't earn.
     */
    fun effectiveCreatorShare(
        tier: UserTier,
        customSubShare: Double? = null,
        customSubShareExpiresAt: Date? = null,
        customSubSharePermanent: Boolean = false,
        referralCount: Int = 0,
        referralGoal: Int? = null,
        isBusiness: Boolean = false
    ): Double {
        if (isBusiness || tier == UserTier.BUSINESS) return 0.0
        val rank = creatorShare(tier)
        val deal = activeDeal(customSubShare, customSubShareExpiresAt, customSubSharePermanent, referralCount, referralGoal)
        return if (deal != null && deal > rank) deal else rank
    }

    /** True when the creator's private deal, not their rank, sets what they're paid. */
    fun dealPays(
        tier: UserTier,
        customSubShare: Double? = null,
        customSubShareExpiresAt: Date? = null,
        customSubSharePermanent: Boolean = false,
        referralCount: Int = 0,
        referralGoal: Int? = null,
        isBusiness: Boolean = false
    ): Boolean {
        if (isBusiness || tier == UserTier.BUSINESS) return false
        val deal = activeDeal(customSubShare, customSubShareExpiresAt, customSubSharePermanent, referralCount, referralGoal)
        return deal != null && deal > creatorShare(tier)
    }

    fun effectiveCreatorShare(user: BasicUserInfo): Double = effectiveCreatorShare(
        user.tier, user.customSubShare, user.customSubShareExpiresAt, user.customSubSharePermanent,
        user.referralCount, user.referralGoal, user.isBusiness
    )

    fun dealPays(user: BasicUserInfo): Boolean = dealPays(
        user.tier, user.customSubShare, user.customSubShareExpiresAt, user.customSubSharePermanent,
        user.referralCount, user.referralGoal, user.isBusiness
    )

    fun platformShare(tier: UserTier): Double = 1.0 - creatorShare(tier)

    /** "65%" — for labels. */
    fun percentLabel(share: Double): String = "${Math.round(share * 100)}%"

}

// ─────────────────────────────────────────────
// MARK: - Cash Out Limits
// ─────────────────────────────────────────────

/** Matches iOS CashOutLimits exactly */
object CashOutLimits {
    const val MINIMUM_COINS: Int = 1_000   // $10 minimum
    const val MAXIMUM_PER_DAY: Int = 100_000 // $1,000/day
}

// ─────────────────────────────────────────────
// MARK: - Tip Presets
// ─────────────────────────────────────────────

/**
 * Matches iOS TipPreset exactly.
 * BATCHING: Quick tips within 2s cooldown are batched by HypeCoinCoordinator.
 */
enum class TipPreset {
    SMALL, MEDIUM, LARGE, HUGE;

    val amount: Int
        get() = when (this) {
            SMALL  -> 10
            MEDIUM -> 50
            LARGE  -> 100
            HUGE   -> 500
        }

    val displayName: String
        get() = when (this) {
            SMALL  -> "Nice! 👍"
            MEDIUM -> "Love it! ❤️"
            LARGE  -> "Amazing! 🔥"
            HUGE   -> "Mind blown! 🤯"
        }

    val emoji: String
        get() = when (this) {
            SMALL  -> "👍"
            MEDIUM -> "❤️"
            LARGE  -> "🔥"
            HUGE   -> "🤯"
        }

    val coinDisplay: String get() = "$amount coins"
}

// ─────────────────────────────────────────────
// MARK: - Coin Errors
// ─────────────────────────────────────────────

sealed class CoinError(message: String) : Exception(message) {
    object InsufficientBalance : CoinError("Not enough Hype Coins")
    object BelowMinimumCashOut : CoinError(
        "Minimum cash out is ${CashOutLimits.MINIMUM_COINS} coins (\$${CashOutLimits.MINIMUM_COINS / 100})"
    )
    object PurchaseFailed  : CoinError("Purchase failed. Please try again.")
    object TransferFailed  : CoinError("Transfer failed. Please try again.")
    /** Server refused with coins_frozen — the account is frozen after a chargeback. */
    object CoinsFrozen     : CoinError("Coins are paused on this account. Contact support.")
    object BillingUnavailable : CoinError("Google Play Billing is not available on this device.")
}