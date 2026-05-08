/*
 * Badges.kt — Android port of iOS BadgeCategory.swift catalog.
 *
 * Source of truth for badge metadata on Android. Awards happen server-
 * side in the onUserStatsChanged Cloud Function — this file only models
 * the catalog so the UI can render names / categories / rarities for
 * earned and locked badges.
 *
 * Skipped vs iOS:
 *   • Social-signal badges (4 kinds × 4 grades) — they have their own
 *     evaluator on iOS; Android can render them when/if we port that.
 *   • Auto-derived season checks — seasonal badges show up in the catalog
 *     but Android doesn't filter by active season yet.
 */

package com.stitchsocial.club.foundation

import androidx.compose.ui.graphics.Color
import java.util.Date

// ─────────────────────────────────────────────
// Rarity
// ─────────────────────────────────────────────

enum class BadgeRarity(val rawValue: Int) {
    COMMON(0),
    UNCOMMON(1),
    RARE(2),
    EPIC(3),
    LEGENDARY(4);

    val label: String get() = when (this) {
        COMMON -> "Common"
        UNCOMMON -> "Uncommon"
        RARE -> "Rare"
        EPIC -> "Epic"
        LEGENDARY -> "Legendary"
    }

    val uiColor: Color get() = when (this) {
        COMMON    -> Color(0xFF9CA3AF)
        UNCOMMON  -> Color(0xFF4ADE80)
        RARE      -> Color(0xFF60A5FA)
        EPIC      -> Color(0xFFC084FC)
        LEGENDARY -> Color(0xFFFBBF24)
    }

    val ringOpacity: Float get() = when (this) {
        COMMON -> 0.18f; UNCOMMON -> 0.25f
        RARE -> 0.30f;   EPIC -> 0.36f;     LEGENDARY -> 0.44f
    }

    val glowRadius: Float get() = when (this) {
        COMMON -> 4f; UNCOMMON -> 7f
        RARE -> 10f;  EPIC -> 14f;  LEGENDARY -> 20f
    }
}

// ─────────────────────────────────────────────
// Category
// ─────────────────────────────────────────────

enum class BadgeCategoryV2(val rawValue: String, val displayName: String) {
    SEASONAL("seasonal", "Seasonal"),
    HYPE_MASTER("hype_master", "Hype Master"),
    COOL_VILLAIN("cool_villain", "Cool Villain"),
    CREATOR("creator", "Creator"),
    ENGAGEMENT("engagement", "Engagement"),
    REPUTATION("reputation", "Reputation"),
    SOCIAL("social", "Social"),
    SOCIAL_SIGNAL("social_signal", "Signal"),
    SPECIAL("special", "Special");

    val uiAccent: Color get() = when (this) {
        SEASONAL      -> Color(0xFFF97316)
        HYPE_MASTER   -> Color(0xFFEAB308)
        COOL_VILLAIN  -> Color(0xFFA855F7)
        CREATOR       -> Color(0xFF3B82F6)
        ENGAGEMENT    -> Color(0xFF22C55E)
        REPUTATION    -> Color(0xFFEF4444)
        SOCIAL        -> Color(0xFF06B6D4)
        SOCIAL_SIGNAL -> Color(0xFF38BDF8)
        SPECIAL       -> Color(0xFFF5C842)
    }
}

// ─────────────────────────────────────────────
// Definition + Earned
// ─────────────────────────────────────────────

data class BadgeRequirements(
    val minXP: Int = 0,
    val minHypesGiven: Int = 0,
    val minCoolsGiven: Int = 0,
    val minPosts: Int = 0,
    val minFollowers: Int = 0,
    val minHypesReceived: Int = 0,
    val minClout: Int = 0,
    val minCoinsGiven: Int = 0,
    val minSubscriptionsGiven: Int = 0,
    val minSubscribersEarned: Int = 0,
    val requiredTier: String? = null,
    val seasonRequired: String? = null,
    val isManuallyAwarded: Boolean = false
)

data class BadgeDefinition(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val category: BadgeCategoryV2,
    val rarity: BadgeRarity,
    val requirements: BadgeRequirements,
    val grantsSeasonalBoost: Boolean = false
)

data class EarnedBadge(
    val id: String,
    val earnedAt: Date,
    val isPinned: Boolean = false,
    val isNew: Boolean = false
)

data class BadgeProgress(
    val id: String,
    val definition: BadgeDefinition,
    val progressFraction: Float,
    val currentValue: Int,
    val targetValue: Int
)

// ─────────────────────────────────────────────
// Catalog (mirrors iOS BadgeDefinition.allBadges)
// ─────────────────────────────────────────────

object BadgeCatalog {

    val all: List<BadgeDefinition> = buildList {
        // ── Seasonal ──────────────────────────────────────────
        add(BadgeDefinition("halloween_pumpkin", "Pumpkin King",
            "Collected during Halloween. Grants 20% hype boost while active.",
            "🎃", BadgeCategoryV2.SEASONAL, BadgeRarity.RARE,
            BadgeRequirements(minXP = 500, seasonRequired = "halloween"),
            grantsSeasonalBoost = true))
        add(BadgeDefinition("halloween_ghost", "Ghost Mode",
            "The silent haunter. 50 cools during Halloween.",
            "👻", BadgeCategoryV2.SEASONAL, BadgeRarity.EPIC,
            BadgeRequirements(minCoolsGiven = 50, seasonRequired = "halloween"),
            grantsSeasonalBoost = true))
        add(BadgeDefinition("christmas_elf", "Hype Elf",
            "Spread holiday hype. 25% boost active.",
            "🎄", BadgeCategoryV2.SEASONAL, BadgeRarity.RARE,
            BadgeRequirements(minHypesGiven = 100, seasonRequired = "christmas"),
            grantsSeasonalBoost = true))
        add(BadgeDefinition("christmas_legend", "Santa's Favorite",
            "Top 1% hype giver in December.",
            "🎅", BadgeCategoryV2.SEASONAL, BadgeRarity.LEGENDARY,
            BadgeRequirements(minXP = 5000, minHypesGiven = 500, seasonRequired = "christmas"),
            grantsSeasonalBoost = true))
        add(BadgeDefinition("summer_vibe", "Summer Vibe",
            "Kept the hype alive all summer.",
            "🌊", BadgeCategoryV2.SEASONAL, BadgeRarity.UNCOMMON,
            BadgeRequirements(minHypesGiven = 50, seasonRequired = "summer"),
            grantsSeasonalBoost = true))
        add(BadgeDefinition("new_year_blast", "New Year Blaster",
            "Rang in the new year with maximum hype.",
            "🎆", BadgeCategoryV2.SEASONAL, BadgeRarity.RARE,
            BadgeRequirements(minXP = 1000, seasonRequired = "newYear"),
            grantsSeasonalBoost = true))

        // ── Hype Master ───────────────────────────────────────
        add(BadgeDefinition("hype_initiate", "Hype Initiate",
            "Gave 100 hypes. The journey begins.", "🔥",
            BadgeCategoryV2.HYPE_MASTER, BadgeRarity.COMMON,
            BadgeRequirements(minHypesGiven = 100)))
        add(BadgeDefinition("hype_master", "Hype Master",
            "Gave 1,000 hypes. You fuel the platform.", "⚡",
            BadgeCategoryV2.HYPE_MASTER, BadgeRarity.RARE,
            BadgeRequirements(minXP = 2000, minHypesGiven = 1000)))
        add(BadgeDefinition("hype_overlord", "Hype Overlord",
            "10,000 hypes given. You ARE the hype.", "👑",
            BadgeCategoryV2.HYPE_MASTER, BadgeRarity.LEGENDARY,
            BadgeRequirements(minXP = 10000, minHypesGiven = 10000, minClout = 50000)))

        // ── Cool Villain ──────────────────────────────────────
        add(BadgeDefinition("cool_villain_rookie", "Petty Villain",
            "Dropped 50 cools. Chaos is your thing.", "😈",
            BadgeCategoryV2.COOL_VILLAIN, BadgeRarity.COMMON,
            BadgeRequirements(minCoolsGiven = 50)))
        add(BadgeDefinition("cool_villain_mid", "Cooldown Commander",
            "500 cools given. The platform respects your no.", "🦹",
            BadgeCategoryV2.COOL_VILLAIN, BadgeRarity.RARE,
            BadgeRequirements(minXP = 1500, minCoolsGiven = 500)))
        add(BadgeDefinition("cool_villain_legend", "The Villain Era",
            "5,000 cools. You are the anti-hype.", "💀",
            BadgeCategoryV2.COOL_VILLAIN, BadgeRarity.LEGENDARY,
            BadgeRequirements(minXP = 8000, minCoolsGiven = 5000, minClout = 20000)))

        // ── Creator ───────────────────────────────────────────
        add(BadgeDefinition("first_post", "First Drop",
            "Posted your first video.", "🎬",
            BadgeCategoryV2.CREATOR, BadgeRarity.COMMON,
            BadgeRequirements(minPosts = 1)))
        add(BadgeDefinition("content_grinder", "Content Grinder",
            "50 posts. The grind is real.", "📹",
            BadgeCategoryV2.CREATOR, BadgeRarity.UNCOMMON,
            BadgeRequirements(minXP = 1000, minPosts = 50)))
        add(BadgeDefinition("prolific_creator", "Prolific Creator",
            "100 posts. You never stop.", "🎥",
            BadgeCategoryV2.CREATOR, BadgeRarity.RARE,
            BadgeRequirements(minXP = 3000, minPosts = 100)))

        // ── Engagement ────────────────────────────────────────
        add(BadgeDefinition("xp_climber", "XP Climber",
            "Reached 1,000 XP.", "📈",
            BadgeCategoryV2.ENGAGEMENT, BadgeRarity.UNCOMMON,
            BadgeRequirements(minXP = 1000)))
        add(BadgeDefinition("clout_earner", "Clout Earner",
            "10,000 clout accumulated.", "💎",
            BadgeCategoryV2.ENGAGEMENT, BadgeRarity.RARE,
            BadgeRequirements(minXP = 2000, minClout = 10000)))
        add(BadgeDefinition("clout_champion", "Clout Champion",
            "100,000 clout. Undeniable.", "🏆",
            BadgeCategoryV2.ENGAGEMENT, BadgeRarity.LEGENDARY,
            BadgeRequirements(minXP = 20000, minClout = 100000)))

        // ── Big Tipper chain ──────────────────────────────────
        add(BadgeDefinition("tipper", "Tipper",
            "Tipped 500 HypeCoins to other creators.", "💸",
            BadgeCategoryV2.ENGAGEMENT, BadgeRarity.COMMON,
            BadgeRequirements(minCoinsGiven = 500)))
        add(BadgeDefinition("big_tipper", "Big Tipper",
            "Tipped 5,000 HypeCoins. You back your creators.", "🤑",
            BadgeCategoryV2.ENGAGEMENT, BadgeRarity.RARE,
            BadgeRequirements(minCoinsGiven = 5000)))
        add(BadgeDefinition("whale", "Whale",
            "Tipped 50,000 HypeCoins. The platform lives because of you.", "🐋",
            BadgeCategoryV2.ENGAGEMENT, BadgeRarity.LEGENDARY,
            BadgeRequirements(minCoinsGiven = 50000)))

        // ── Social ────────────────────────────────────────────
        add(BadgeDefinition("networker", "Networker",
            "100 followers.", "🤝",
            BadgeCategoryV2.SOCIAL, BadgeRarity.COMMON,
            BadgeRequirements(minFollowers = 100)))
        add(BadgeDefinition("popular", "Popular",
            "1,000 followers.", "🌟",
            BadgeCategoryV2.SOCIAL, BadgeRarity.UNCOMMON,
            BadgeRequirements(minFollowers = 1000)))
        add(BadgeDefinition("influencer_badge", "Influencer",
            "10,000 followers. You're the real deal.", "💫",
            BadgeCategoryV2.SOCIAL, BadgeRarity.EPIC,
            BadgeRequirements(minXP = 5000, minFollowers = 10000)))
        add(BadgeDefinition("first_sub", "First Sub",
            "Subscribed to your first creator.", "🎟",
            BadgeCategoryV2.SOCIAL, BadgeRarity.COMMON,
            BadgeRequirements(minSubscriptionsGiven = 1)))
        add(BadgeDefinition("loyal_supporter", "Loyal Supporter",
            "Subscribed to 5 creators. True community member.", "💪",
            BadgeCategoryV2.SOCIAL, BadgeRarity.UNCOMMON,
            BadgeRequirements(minSubscriptionsGiven = 5)))
        add(BadgeDefinition("super_fan", "Super Fan",
            "Subscribed to 10 creators. You are the backbone.", "🫶",
            BadgeCategoryV2.SOCIAL, BadgeRarity.RARE,
            BadgeRequirements(minSubscriptionsGiven = 10)))

        // ── Creator — subscriber earned ───────────────────────
        add(BadgeDefinition("first_subscriber", "First Subscriber",
            "Someone believed in you enough to subscribe.", "🌟",
            BadgeCategoryV2.CREATOR, BadgeRarity.COMMON,
            BadgeRequirements(minSubscribersEarned = 1)))
        add(BadgeDefinition("growing_community", "Growing Community",
            "50 subscribers. Your community is real.", "🌱",
            BadgeCategoryV2.CREATOR, BadgeRarity.UNCOMMON,
            BadgeRequirements(minSubscribersEarned = 50)))
        add(BadgeDefinition("subscriber_king", "Subscriber King",
            "500 subscribers. You built something.", "👑",
            BadgeCategoryV2.CREATOR, BadgeRarity.EPIC,
            BadgeRequirements(minSubscribersEarned = 500)))

        // ── Reputation / Tier ─────────────────────────────────
        add(BadgeDefinition("tier_rookie", "Rookie",
            "Welcome to StitchSocial. Your journey starts here.", "🌱",
            BadgeCategoryV2.REPUTATION, BadgeRarity.COMMON,
            BadgeRequirements(requiredTier = "rookie")))
        add(BadgeDefinition("tier_rising", "Rising",
            "Reached Rising tier. 1,000 clout strong.", "📶",
            BadgeCategoryV2.REPUTATION, BadgeRarity.UNCOMMON,
            BadgeRequirements(requiredTier = "rising")))
        add(BadgeDefinition("tier_veteran", "Veteran",
            "Reached Veteran tier.", "🎖",
            BadgeCategoryV2.REPUTATION, BadgeRarity.UNCOMMON,
            BadgeRequirements(requiredTier = "veteran")))
        add(BadgeDefinition("tier_influencer", "Influencer",
            "Reached Influencer tier. 10,000 clout.", "💫",
            BadgeCategoryV2.REPUTATION, BadgeRarity.RARE,
            BadgeRequirements(requiredTier = "influencer")))
        add(BadgeDefinition("tier_ambassador", "Ambassador",
            "Reached Ambassador tier. 15,000 clout.", "🌐",
            BadgeCategoryV2.REPUTATION, BadgeRarity.RARE,
            BadgeRequirements(requiredTier = "ambassador")))
        add(BadgeDefinition("tier_elite", "Elite",
            "Reached Elite tier.", "🔱",
            BadgeCategoryV2.REPUTATION, BadgeRarity.EPIC,
            BadgeRequirements(requiredTier = "elite")))
        add(BadgeDefinition("tier_partner", "Partner",
            "Reached Partner tier. 50,000 clout.", "🤝",
            BadgeCategoryV2.REPUTATION, BadgeRarity.EPIC,
            BadgeRequirements(requiredTier = "partner")))
        add(BadgeDefinition("tier_legendary", "Legendary Status",
            "Reached Legendary tier.", "⚜",
            BadgeCategoryV2.REPUTATION, BadgeRarity.LEGENDARY,
            BadgeRequirements(requiredTier = "legendary")))
        add(BadgeDefinition("tier_top_creator", "Top Creator",
            "Reached Top Creator tier. 500,000 clout.", "🚀",
            BadgeCategoryV2.REPUTATION, BadgeRarity.LEGENDARY,
            BadgeRequirements(requiredTier = "top_creator")))
        add(BadgeDefinition("tier_founder_crest", "Founder Crest",
            "The architect. Built this from nothing.", "🏛",
            BadgeCategoryV2.REPUTATION, BadgeRarity.LEGENDARY,
            BadgeRequirements(isManuallyAwarded = true)))

        // ── Special ───────────────────────────────────────────
        add(BadgeDefinition("founder_badge", "Founder",
            "One of the original founders.", "🛡",
            BadgeCategoryV2.SPECIAL, BadgeRarity.LEGENDARY,
            BadgeRequirements(isManuallyAwarded = true)))
        add(BadgeDefinition("beta_tester", "Beta Tester",
            "Helped shape the platform before launch.", "🧪",
            BadgeCategoryV2.SPECIAL, BadgeRarity.EPIC,
            BadgeRequirements(isManuallyAwarded = true)))
        add(BadgeDefinition("early_adopter", "Early Adopter",
            "Joined StitchSocial in its founding era (before July 2026).", "🌅",
            BadgeCategoryV2.SPECIAL, BadgeRarity.RARE,
            BadgeRequirements(isManuallyAwarded = true)))
    }

    private val byID: Map<String, BadgeDefinition> = all.associateBy { it.id }

    fun find(id: String): BadgeDefinition? = byID[id]
}
