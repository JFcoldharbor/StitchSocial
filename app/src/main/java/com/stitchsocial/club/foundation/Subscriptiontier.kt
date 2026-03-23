/*
 * Subscriptiontier.kt - SUBSCRIPTION SYSTEM MODELS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * REWRITTEN: Removed old SUPPORTER/SUPER_FAN two-tier system.
 * Now matches iOS SubscriptionTier.swift exactly.
 *
 * Single subscription level per creator.
 * Pricing: CoinPriceTier (100/250/500/1000/2500 coins) — fixed by platform.
 * Perks: auto from CoinPriceTier, creator can customize Influencer+.
 * Cooldown: 60 days on perk changes.
 *
 * CACHING (add to CachingOptimization):
 *   - CreatorSubscriptionPlan: 10min TTL in SubscriptionService.creatorPlanCache
 *   - ActiveSubscription list: 5min TTL, invalidate on subscribe/cancel
 *   - isSubscribed per creator: 5min TTL in-memory map
 */

package com.stitchsocial.club.foundation

import java.util.Calendar
import java.util.Date
import java.util.UUID

// MARK: - Coin Price Tier

/**
 * Fixed price tiers fans choose when subscribing.
 * rawValue = Int coin cost — stored in Firestore as coinTier (Int).
 * Mirrors iOS CoinPriceTier exactly.
 */
enum class CoinPriceTier(val rawValue: Int) {
    STARTER(100),
    BASIC(250),
    PLUS(500),
    PRO(1000),
    MAX(2500);

    val displayName: String get() = when (this) {
        STARTER -> "Starter"
        BASIC   -> "Basic"
        PLUS    -> "Plus"
        PRO     -> "Pro"
        MAX     -> "Max"
    }

    val coinsDisplay: String get() = "$rawValue coins/month"

    companion object {
        fun fromRawValue(value: Int): CoinPriceTier? =
            entries.firstOrNull { it.rawValue == value }
    }
}

// MARK: - Subscription Perks

/**
 * Perks a subscriber gets — mirrors iOS SubscriptionPerk exactly.
 * Platform defaults assigned per CoinPriceTier in SubscriptionPerks object.
 */
enum class SubscriptionPerk(val value: String) {
    SUPPORT_BADGE("support_badge"),
    NO_ADS("no_ads"),
    PRIORITY_REPLIES("priority_replies"),
    EXCLUSIVE_CONTENT("exclusive_content"),
    DM_ACCESS("dm_access"),
    EARLY_ACCESS("early_access");

    val displayName: String get() = when (this) {
        SUPPORT_BADGE      -> "Supporter Badge"
        NO_ADS             -> "Ad-Free Viewing"
        PRIORITY_REPLIES   -> "Priority in Replies"
        EXCLUSIVE_CONTENT  -> "Exclusive Content"
        DM_ACCESS          -> "DM Access"
        EARLY_ACCESS       -> "Early Access"
    }

    val emoji: String get() = when (this) {
        SUPPORT_BADGE     -> "🏅"
        NO_ADS            -> "🚫"
        PRIORITY_REPLIES  -> "⬆️"
        EXCLUSIVE_CONTENT -> "🔒"
        DM_ACCESS         -> "💬"
        EARLY_ACCESS      -> "⚡"
    }

    companion object {
        fun fromRawValue(value: String): SubscriptionPerk? =
            entries.firstOrNull { it.value == value }
    }
}

// MARK: - Subscription Perks (Platform Defaults)

/**
 * Platform-default perk sets per CoinPriceTier.
 * Mirrors iOS SubscriptionPerks.perks(for:) exactly.
 * Creator can override via TierPricing.customPerks.
 */
object SubscriptionPerks {
    fun perks(tier: CoinPriceTier): List<SubscriptionPerk> = when (tier) {
        CoinPriceTier.STARTER -> listOf(
            SubscriptionPerk.SUPPORT_BADGE
        )
        CoinPriceTier.BASIC -> listOf(
            SubscriptionPerk.SUPPORT_BADGE,
            SubscriptionPerk.NO_ADS
        )
        CoinPriceTier.PLUS -> listOf(
            SubscriptionPerk.SUPPORT_BADGE,
            SubscriptionPerk.NO_ADS,
            SubscriptionPerk.PRIORITY_REPLIES
        )
        CoinPriceTier.PRO -> listOf(
            SubscriptionPerk.SUPPORT_BADGE,
            SubscriptionPerk.NO_ADS,
            SubscriptionPerk.PRIORITY_REPLIES,
            SubscriptionPerk.EXCLUSIVE_CONTENT,
            SubscriptionPerk.DM_ACCESS
        )
        CoinPriceTier.MAX -> listOf(
            SubscriptionPerk.SUPPORT_BADGE,
            SubscriptionPerk.NO_ADS,
            SubscriptionPerk.PRIORITY_REPLIES,
            SubscriptionPerk.EXCLUSIVE_CONTENT,
            SubscriptionPerk.DM_ACCESS,
            SubscriptionPerk.EARLY_ACCESS
        )
    }
}

// MARK: - Tier Pricing (Creator Custom Config)

/**
 * Stores per-tier perk overrides set by creator.
 * key = CoinPriceTier.rawValue.toString(), value = list of SubscriptionPerk.value
 * Price is NOT customizable — always CoinPriceTier.rawValue.
 * Mirrors iOS TierPricing exactly.
 *
 * CACHING: Stored in CreatorSubscriptionPlan, read from 10min cache.
 */
data class TierPricing(
    val customPerks: Map<String, List<String>> = emptyMap()
) {
    /** Price is fixed at CoinPriceTier.rawValue — not customizable */
    fun price(tier: CoinPriceTier): Int = tier.rawValue

    /** Returns perks for tier — custom config if set, platform default otherwise */
    fun perks(tier: CoinPriceTier): List<SubscriptionPerk> {
        val key = tier.rawValue.toString()
        val custom = customPerks[key]
        if (custom != null) {
            val parsed = custom.mapNotNull { SubscriptionPerk.fromRawValue(it) }
            val result = mutableListOf(SubscriptionPerk.SUPPORT_BADGE)
            result.addAll(parsed.filter { it != SubscriptionPerk.SUPPORT_BADGE })
            return result
        }
        return SubscriptionPerks.perks(tier)
    }

    fun toMap(): Map<String, Any> = mapOf("customPerks" to customPerks)

    companion object {
        fun fromMap(data: Map<String, Any>): TierPricing {
            val raw = data["customPerks"]
            if (raw !is Map<*, *>) return TierPricing()
            val parsed = mutableMapOf<String, List<String>>()
            for ((k, v) in raw) {
                val key = k as? String ?: continue
                val list = (v as? List<*>)?.mapNotNull { it as? String } ?: continue
                parsed[key] = list
            }
            return TierPricing(customPerks = parsed)
        }
    }
}

// MARK: - Creator Subscription Plan

/**
 * Firestore: creator_subscription_plans/{creatorID}
 * Mirrors iOS CreatorSubscriptionPlan exactly.
 * 60-day cooldown on perk changes.
 */
data class CreatorSubscriptionPlan(
    val id: String = "",
    val creatorID: String,
    var isEnabled: Boolean = false,
    var tierPricing: TierPricing = TierPricing(),
    var customWelcomeMessage: String? = null,
    var subscriberCount: Int = 0,
    var totalEarned: Int = 0,
    var lastPriceChangeAt: Date? = null,
    var nextPriceChangeAllowedAt: Date? = null,
    val createdAt: Date = Date(),
    var updatedAt: Date = Date()
) {
    fun price(tier: CoinPriceTier): Int = tier.rawValue

    val canChangePrice: Boolean
        get() {
            val next = nextPriceChangeAllowedAt ?: return true
            return Date() >= next
        }

    val daysUntilPriceChange: Int
        get() {
            val next = nextPriceChangeAllowedAt ?: return 0
            val diff = next.time - Date().time
            return maxOf(0, (diff / (1000 * 60 * 60 * 24)).toInt())
        }
}

// MARK: - Active Subscription

/**
 * Firestore: subscriptions/{subscriberID}_{creatorID}
 * Mirrors iOS ActiveSubscription exactly.
 * coinTier stored as Int rawValue in Firestore.
 */
data class ActiveSubscription(
    val id: String,
    val subscriberID: String,
    val creatorID: String,
    val coinTier: CoinPriceTier,
    val coinsPaid: Int,
    val status: SubscriptionStatus,
    val subscribedAt: Date,
    var currentPeriodStart: Date,
    var currentPeriodEnd: Date,
    var autoRenew: Boolean = true,
    var renewalCount: Int = 0
) {
    val isActive: Boolean
        get() = status == SubscriptionStatus.ACTIVE && Date().before(currentPeriodEnd)

    val daysRemaining: Int
        get() {
            val diff = currentPeriodEnd.time - Date().time
            return maxOf(0, (diff / (1000 * 60 * 60 * 24)).toInt())
        }

    val perks: List<SubscriptionPerk>
        get() = SubscriptionPerks.perks(coinTier)

    val totalPaid: Int
        get() = coinsPaid * maxOf(1, renewalCount)
}

// MARK: - Subscription Status

enum class SubscriptionStatus(val value: String) {
    ACTIVE("active"),
    EXPIRED("expired"),
    CANCELLED("cancelled"),
    PAUSED("paused");

    companion object {
        fun fromRawValue(value: String): SubscriptionStatus? =
            entries.firstOrNull { it.value == value }
    }
}

// MARK: - Subscription Check Result

/**
 * Mirrors iOS SubscriptionService.SubscriptionCheck exactly.
 * Used by AdService.checkSubscriptionStatus to gate ads.
 */
data class SubscriptionCheckResult(
    val isSubscribed: Boolean,
    val coinsPaid: Int = 0,
    val coinTier: CoinPriceTier? = null
) {
    /** Ad-free requires Plus (500) or higher */
    val hasNoAds: Boolean
        get() {
            val tier = coinTier ?: return false
            return SubscriptionPerks.perks(tier).contains(SubscriptionPerk.NO_ADS)
        }

    val hasDMAccess: Boolean
        get() {
            val tier = coinTier ?: return false
            return SubscriptionPerks.perks(tier).contains(SubscriptionPerk.DM_ACCESS)
        }

    val hasBadge: Boolean
        get() {
            val tier = coinTier ?: return false
            return SubscriptionPerks.perks(tier).contains(SubscriptionPerk.SUPPORT_BADGE)
        }

    companion object {
        val NONE = SubscriptionCheckResult(isSubscribed = false, coinsPaid = 0, coinTier = null)
    }
}

// MARK: - Subscription Event

data class SubscriptionEvent(
    val id: String,
    val subscriptionID: String,
    val subscriberID: String,
    val creatorID: String,
    val type: SubscriptionEventType,
    val coinTier: CoinPriceTier,
    val coinAmount: Int,
    val createdAt: Date
)

enum class SubscriptionEventType(val value: String) {
    NEW_SUBSCRIPTION("new"),
    RENEWAL("renewal"),
    UPGRADE("upgrade"),
    DOWNGRADE("downgrade"),
    CANCELLATION("cancellation"),
    EXPIRATION("expiration");

    companion object {
        fun fromRawValue(value: String): SubscriptionEventType? =
            entries.firstOrNull { it.value == value }
    }
}

// MARK: - Subscriber Info

data class SubscriberInfo(
    val id: String,
    val subscriberID: String,
    val username: String,
    val displayName: String,
    val profileImageURL: String? = null,
    val coinTier: CoinPriceTier,
    val subscribedAt: Date,
    val totalPaid: Int,
    val renewalCount: Int
)

// MARK: - Errors

sealed class SubscriptionError(message: String) : Exception(message) {
    object PlanNotFound         : SubscriptionError("Subscription plan not found")
    object AlreadySubscribed    : SubscriptionError("You're already subscribed")
    object InsufficientCoins    : SubscriptionError("Not enough Hype Coins")
    data class PriceCooldown(val daysLeft: Int) : SubscriptionError("Price locked for $daysLeft more days")
}