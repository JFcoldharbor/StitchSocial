/*
 * HypeCoinPackage.kt - HYPE COIN CURRENCY SYSTEM MODELS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Coin packages, balances, transactions, cash out
 * Dependencies: UserTier (for revenue share)
 * Features: Purchase packages, coin value conversion, revenue split, cash out limits
 *
 * EXACT PORT: HypeCoinModels.swift
 */

package com.stitchsocial.club.foundation

import java.util.Date

// MARK: - Coin Packages (Purchase Options)

enum class HypeCoinPackage(val value: String) {
    STARTER("starter"),
    BASIC("basic"),
    PLUS("plus"),
    PRO("pro"),
    MAX("max");

    val coins: Int
        get() = when (this) {
            STARTER -> 100
            BASIC -> 250
            PLUS -> 500
            PRO -> 1000
            MAX -> 2500
        }

    val price: Double
        get() = when (this) {
            STARTER -> 1.99
            BASIC -> 4.99
            PLUS -> 9.99
            PRO -> 19.99
            MAX -> 49.99
        }

    val cashValue: Double
        get() = coins.toDouble() / 100.0

    val displayName: String
        get() = when (this) {
            STARTER -> "Starter"
            BASIC -> "Basic"
            PLUS -> "Plus"
            PRO -> "Pro"
            MAX -> "Max"
        }

    /** Web purchase URL path */
    val webPath: String
        get() = "/purchase/coins/$value"

    companion object {
        fun fromRawValue(value: String): HypeCoinPackage? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

// MARK: - Coin Value

object HypeCoinValue {
    /** 100 coins = $1.00 cash value */
    const val COINS_PER_DOLLAR: Int = 100

    fun toDollars(coins: Int): Double = coins.toDouble() / COINS_PER_DOLLAR.toDouble()

    fun toCoins(dollars: Double): Int = (dollars * COINS_PER_DOLLAR).toInt()
}

// MARK: - User Coin Balance

data class HypeCoinBalance(
    val userID: String,
    var availableCoins: Int = 0,
    var pendingCoins: Int = 0,
    var lifetimeEarned: Int = 0,
    var lifetimeSpent: Int = 0,
    var lastUpdated: Date = Date()
) {
    val totalCoins: Int get() = availableCoins + pendingCoins

    val cashValue: Double get() = HypeCoinValue.toDollars(availableCoins)
}

// MARK: - Coin Transaction

data class CoinTransaction(
    val id: String,
    val userID: String,
    val type: CoinTransactionType,
    val amount: Int,
    val balanceAfter: Int,
    val relatedUserID: String? = null,
    val relatedSubscriptionID: String? = null,
    val description: String,
    val createdAt: Date
)

enum class CoinTransactionType(val value: String) {
    PURCHASE("purchase"),
    SUBSCRIPTION_RECEIVED("sub_received"),
    SUBSCRIPTION_SENT("sub_sent"),
    TIP_RECEIVED("tip_received"),
    TIP_SENT("tip_sent"),
    CASH_OUT("cash_out"),
    REFUND("refund"),
    BONUS("bonus");

    companion object {
        fun fromRawValue(value: String): CoinTransactionType? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

// MARK: - Cash Out

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
    val createdAt: Date,
    var processedAt: Date? = null,
    var failureReason: String? = null
)

enum class CashOutStatus(val value: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed");

    companion object {
        fun fromRawValue(value: String): CashOutStatus? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

enum class PayoutMethod(val value: String) {
    BANK_TRANSFER("bank_transfer"),
    PAYPAL("paypal"),
    STRIPE("stripe");

    companion object {
        fun fromRawValue(value: String): PayoutMethod? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

// MARK: - Revenue Split (Cash Out)

object SubscriptionRevenueShare {

    fun creatorShare(tier: UserTier): Double = when (tier) {
        UserTier.ROOKIE -> 0.70
        UserTier.RISING -> 0.75
        UserTier.VETERAN -> 0.80
        UserTier.INFLUENCER -> 0.85
        UserTier.AMBASSADOR -> 0.87
        UserTier.ELITE -> 0.90
        UserTier.PARTNER -> 0.92
        UserTier.LEGENDARY -> 0.95
        UserTier.TOP_CREATOR -> 0.97
        UserTier.FOUNDER, UserTier.CO_FOUNDER -> 1.00
    }

    fun platformShare(tier: UserTier): Double = 1.0 - creatorShare(tier)

    /** Calculate cash out amounts: Pair(creatorCut, platformCut) */
    fun calculateCashOut(coins: Int, tier: UserTier): Pair<Double, Double> {
        val totalValue = HypeCoinValue.toDollars(coins)
        val creatorCut = totalValue * creatorShare(tier)
        val platformCut = totalValue * platformShare(tier)
        return Pair(creatorCut, platformCut)
    }
}

// MARK: - Minimum Cash Out

object CashOutLimits {
    const val MINIMUM_COINS: Int = 1000      // $10 minimum
    const val MAXIMUM_PER_DAY: Int = 100000  // $1000/day
}