/*
 * SubscriptionTier.kt - SUBSCRIPTION SYSTEM MODELS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Subscription tiers, plans, perks, events
 * Dependencies: None (Pure Kotlin)
 * Features: Tier definitions, perk enums, creator plans, active subscriptions
 *
 * EXACT PORT: SubscriptionTier.swift (SubscriptionModels)
 */

package com.stitchsocial.club.foundation

import java.util.Calendar
import java.util.Date
import java.util.UUID

// MARK: - Subscription Tier

enum class SubscriptionTier(val value: String) {
    SUPPORTER("supporter"),
    SUPER_FAN("super_fan");

    val displayName: String
        get() = when (this) {
            SUPPORTER -> "Supporter"
            SUPER_FAN -> "Super Fan"
        }

    val coinRange: IntRange
        get() = when (this) {
            SUPPORTER -> 150..250
            SUPER_FAN -> 300..500
        }

    val defaultCoins: Int
        get() = when (this) {
            SUPPORTER -> 200
            SUPER_FAN -> 400
        }

    val hypeBoost: Double
        get() = when (this) {
            SUPPORTER -> 0.05  // 5%
            SUPER_FAN -> 0.10  // 10%
        }

    val perks: List<SubscriptionPerk>
        get() = when (this) {
            SUPPORTER -> listOf(
                SubscriptionPerk.NO_ADS,
                SubscriptionPerk.TOP_SUPPORTER_BADGE,
                SubscriptionPerk.HYPE_BOOST_5
            )
            SUPER_FAN -> listOf(
                SubscriptionPerk.NO_ADS,
                SubscriptionPerk.TOP_SUPPORTER_BADGE,
                SubscriptionPerk.HYPE_BOOST_10,
                SubscriptionPerk.DM_ACCESS,
                SubscriptionPerk.COMMENT_ACCESS
            )
        }

    companion object {
        fun fromRawValue(value: String): SubscriptionTier? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

// MARK: - Subscription Perks

enum class SubscriptionPerk(val value: String) {
    NO_ADS("no_ads"),
    TOP_SUPPORTER_BADGE("top_supporter_badge"),
    HYPE_BOOST_5("hype_boost_5"),
    HYPE_BOOST_10("hype_boost_10"),
    DM_ACCESS("dm_access"),
    COMMENT_ACCESS("comment_access");

    val displayName: String
        get() = when (this) {
            NO_ADS -> "Ad-Free Viewing"
            TOP_SUPPORTER_BADGE -> "Top Supporter Badge"
            HYPE_BOOST_5 -> "5% Hype Boost"
            HYPE_BOOST_10 -> "10% Hype Boost"
            DM_ACCESS -> "DM Access"
            COMMENT_ACCESS -> "Comment on Videos"
        }

    val icon: String
        get() = when (this) {
            NO_ADS -> "visibility_off"
            TOP_SUPPORTER_BADGE -> "star"
            HYPE_BOOST_5, HYPE_BOOST_10 -> "local_fire_department"
            DM_ACCESS -> "message"
            COMMENT_ACCESS -> "chat_bubble"
        }

    companion object {
        fun fromRawValue(value: String): SubscriptionPerk? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

// MARK: - Creator Subscription Plan

data class CreatorSubscriptionPlan(
    val id: String = UUID.randomUUID().toString(),
    val creatorID: String,
    var isEnabled: Boolean = false,
    var supporterPrice: Int = SubscriptionTier.SUPPORTER.defaultCoins,
    var superFanPrice: Int = SubscriptionTier.SUPER_FAN.defaultCoins,
    var supporterEnabled: Boolean = true,
    var superFanEnabled: Boolean = true,
    var customWelcomeMessage: String? = null,
    var subscriberCount: Int = 0,
    var totalEarned: Int = 0,
    val createdAt: Date = Date(),
    var updatedAt: Date = Date()
) {
    fun priceForTier(tier: SubscriptionTier): Int = when (tier) {
        SubscriptionTier.SUPPORTER -> supporterPrice
        SubscriptionTier.SUPER_FAN -> superFanPrice
    }

    fun isTierEnabled(tier: SubscriptionTier): Boolean = when (tier) {
        SubscriptionTier.SUPPORTER -> supporterEnabled
        SubscriptionTier.SUPER_FAN -> superFanEnabled
    }
}

// MARK: - Active Subscription (Viewer → Creator)

data class ActiveSubscription(
    val id: String,
    val subscriberID: String,
    val creatorID: String,
    val tier: SubscriptionTier,
    val coinsPaid: Int,
    val status: SubscriptionStatus,
    val startedAt: Date,
    var expiresAt: Date,
    var renewalEnabled: Boolean,
    var renewalCount: Int
) {
    val isActive: Boolean
        get() = status == SubscriptionStatus.ACTIVE && Date().before(expiresAt)

    val daysRemaining: Int
        get() {
            val diff = expiresAt.time - Date().time
            val days = (diff / (1000 * 60 * 60 * 24)).toInt()
            return maxOf(0, days)
        }
}

// MARK: - Subscription Status

enum class SubscriptionStatus(val value: String) {
    ACTIVE("active"),
    EXPIRED("expired"),
    CANCELLED("cancelled"),
    PAUSED("paused");

    companion object {
        fun fromRawValue(value: String): SubscriptionStatus? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

// MARK: - Subscription Event

data class SubscriptionEvent(
    val id: String,
    val subscriptionID: String,
    val subscriberID: String,
    val creatorID: String,
    val type: SubscriptionEventType,
    val tier: SubscriptionTier,
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
        fun fromRawValue(value: String): SubscriptionEventType? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

// MARK: - Subscriber Info (for creator's subscriber list)

data class SubscriberInfo(
    val id: String,
    val subscriberID: String,
    val username: String,
    val displayName: String,
    val profileImageURL: String? = null,
    val tier: SubscriptionTier,
    val subscribedAt: Date,
    val totalPaid: Int,
    val renewalCount: Int
)

// MARK: - Subscription Check Result

data class SubscriptionCheckResult(
    val isSubscribed: Boolean,
    val tier: SubscriptionTier? = null,
    val perks: List<SubscriptionPerk> = emptyList(),
    val hypeBoost: Double = 0.0
) {
    val hasNoAds: Boolean get() = perks.contains(SubscriptionPerk.NO_ADS)
    val hasDMAccess: Boolean get() = perks.contains(SubscriptionPerk.DM_ACCESS)
    val hasCommentAccess: Boolean get() = perks.contains(SubscriptionPerk.COMMENT_ACCESS)
    val hasBadge: Boolean get() = perks.contains(SubscriptionPerk.TOP_SUPPORTER_BADGE)

    companion object {
        val NONE = SubscriptionCheckResult(
            isSubscribed = false,
            tier = null,
            perks = emptyList(),
            hypeBoost = 0.0
        )
    }
}