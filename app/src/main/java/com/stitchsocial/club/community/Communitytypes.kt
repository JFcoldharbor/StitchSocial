/*
 * CommunityTypes.kt - COMMUNITY SYSTEM DATA MODELS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Community, Membership, Posts, XP, Badges, DMs, Global XP
 * Port of: Community.swift (iOS)
 *
 * CACHING NOTES:
 * - CommunityBadgeDefinition.allBadges: Static list, cache at app launch, never refetch
 * - CommunityXPCurve.xpRequired(): Pure math, cache as lookup table on launch (1000 entries)
 * - CommunityMembership XP/level: Cache per-session, refresh on post/hype actions
 * - CommunityPost feed: Cursor-paginated, cache first 20 locally with 2-min TTL
 * - GlobalCommunityXP tap multiplier: Cache on login, refresh every 15 min
 * - Community list: Cache on first load with 5-min TTL
 * - Add all above to OptimizationConfig under "Community Cache Policy"
 */

package com.stitchsocial.club.community

import com.stitchsocial.club.foundation.UserTier
import java.util.Date
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// MARK: - Community (One Per Influencer+ Creator)

/** Firestore: communities/{creatorID} */
data class Community(
    val id: String,                         // Same as creatorID
    val creatorID: String,
    val creatorUsername: String,
    val creatorDisplayName: String,
    val creatorTier: String,                // UserTier rawValue
    var displayName: String,
    var description: String = "",
    var memberCount: Int = 0,
    var totalPosts: Int = 0,
    var isActive: Boolean = true,
    var profileImageURL: String? = null,
    var bannerImageURL: String? = null,
    var pinnedPostID: String? = null,
    val createdAt: Date = Date(),
    var updatedAt: Date = Date()
) {
    companion object {
        fun canCreateCommunity(tier: UserTier): Boolean {
            return when (tier) {
                UserTier.INFLUENCER, UserTier.AMBASSADOR, UserTier.ELITE,
                UserTier.PARTNER, UserTier.LEGENDARY, UserTier.TOP_CREATOR,
                UserTier.FOUNDER, UserTier.CO_FOUNDER -> true
                else -> false
            }
        }

        fun fromFirestore(id: String, data: Map<String, Any>): Community {
            return Community(
                id = id,
                creatorID = data["creatorID"] as? String ?: id,
                creatorUsername = data["creatorUsername"] as? String ?: "",
                creatorDisplayName = data["creatorDisplayName"] as? String ?: "",
                creatorTier = data["creatorTier"] as? String ?: "rookie",
                displayName = data["displayName"] as? String ?: "",
                description = data["description"] as? String ?: "",
                memberCount = (data["memberCount"] as? Number)?.toInt() ?: 0,
                totalPosts = (data["totalPosts"] as? Number)?.toInt() ?: 0,
                isActive = data["isActive"] as? Boolean ?: true,
                profileImageURL = data["profileImageURL"] as? String,
                bannerImageURL = data["bannerImageURL"] as? String,
                pinnedPostID = data["pinnedPostID"] as? String,
                createdAt = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date(),
                updatedAt = (data["updatedAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()
            )
        }
    }

    fun toFirestore(): Map<String, Any> = buildMap {
        put("id", id)
        put("creatorID", creatorID)
        put("creatorUsername", creatorUsername)
        put("creatorDisplayName", creatorDisplayName)
        put("creatorTier", creatorTier)
        put("displayName", displayName)
        put("description", description)
        put("memberCount", memberCount)
        put("totalPosts", totalPosts)
        put("isActive", isActive)
        profileImageURL?.let { put("profileImageURL", it) }
        bannerImageURL?.let { put("bannerImageURL", it) }
        pinnedPostID?.let { put("pinnedPostID", it) }
        put("createdAt", com.google.firebase.Timestamp(createdAt))
        put("updatedAt", com.google.firebase.Timestamp(updatedAt))
    }
}

// MARK: - Community Membership

/** Firestore: communities/{creatorID}/members/{userID} */
data class CommunityMembership(
    val id: String,                         // Same as userID
    val userID: String,
    val communityID: String,
    val username: String,
    val displayName: String,
    var subscriptionTier: String = "supporter",
    var localXP: Int = 0,
    var level: Int = 1,
    var earnedBadgeIDs: List<String> = emptyList(),
    var isModerator: Boolean = false,
    var isBanned: Boolean = false,
    var lastActiveAt: Date = Date(),
    var joinedAt: Date = Date(),
    var totalPosts: Int = 0,
    var totalReplies: Int = 0,
    var totalHypesGiven: Int = 0,
    var totalHypesReceived: Int = 0,
    var streamsAttended: Int = 0,
    var dailyLoginStreak: Int = 0,
    var lastDailyLoginAt: Date? = null
) {
    // Level gate checks
    val canPostVideoClips: Boolean get() = level >= 20
    val canDMCreator: Boolean get() = level >= 25
    val canAccessPrivateLive: Boolean get() = level >= 50
    val canBeNominatedMod: Boolean get() = level >= 100
    val canCoHostLive: Boolean get() = level >= 850

    fun isUnlocked(feature: CommunityFeatureGate): Boolean = level >= feature.requiredLevel

    companion object {
        fun fromFirestore(data: Map<String, Any>): CommunityMembership {
            return CommunityMembership(
                id = data["id"] as? String ?: data["userID"] as? String ?: "",
                userID = data["userID"] as? String ?: "",
                communityID = data["communityID"] as? String ?: "",
                username = data["username"] as? String ?: "",
                displayName = data["displayName"] as? String ?: "",
                subscriptionTier = data["subscriptionTier"] as? String ?: "supporter",
                localXP = (data["localXP"] as? Number)?.toInt() ?: 0,
                level = (data["level"] as? Number)?.toInt() ?: 1,
                earnedBadgeIDs = (data["earnedBadgeIDs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                isModerator = data["isModerator"] as? Boolean ?: false,
                isBanned = data["isBanned"] as? Boolean ?: false,
                lastActiveAt = (data["lastActiveAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date(),
                joinedAt = (data["joinedAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date(),
                totalPosts = (data["totalPosts"] as? Number)?.toInt() ?: 0,
                totalReplies = (data["totalReplies"] as? Number)?.toInt() ?: 0,
                totalHypesGiven = (data["totalHypesGiven"] as? Number)?.toInt() ?: 0,
                totalHypesReceived = (data["totalHypesReceived"] as? Number)?.toInt() ?: 0,
                streamsAttended = (data["streamsAttended"] as? Number)?.toInt() ?: 0,
                dailyLoginStreak = (data["dailyLoginStreak"] as? Number)?.toInt() ?: 0,
                lastDailyLoginAt = (data["lastDailyLoginAt"] as? com.google.firebase.Timestamp)?.toDate()
            )
        }
    }

    fun toFirestore(): Map<String, Any> = buildMap {
        put("id", id); put("userID", userID); put("communityID", communityID)
        put("username", username); put("displayName", displayName)
        put("subscriptionTier", subscriptionTier)
        put("localXP", localXP); put("level", level)
        put("earnedBadgeIDs", earnedBadgeIDs)
        put("isModerator", isModerator); put("isBanned", isBanned)
        put("lastActiveAt", com.google.firebase.Timestamp(lastActiveAt))
        put("joinedAt", com.google.firebase.Timestamp(joinedAt))
        put("totalPosts", totalPosts); put("totalReplies", totalReplies)
        put("totalHypesGiven", totalHypesGiven); put("totalHypesReceived", totalHypesReceived)
        put("streamsAttended", streamsAttended); put("dailyLoginStreak", dailyLoginStreak)
        lastDailyLoginAt?.let { put("lastDailyLoginAt", com.google.firebase.Timestamp(it)) }
    }
}

// MARK: - Community Feature Gates

enum class CommunityFeatureGate(val requiredLevel: Int, val displayName: String) {
    PROFILE_BORDER(3, "Profile Border"),
    CUSTOM_FLAIR(5, "Custom Flair Color"),
    REACTION_EMOTES(10, "Reaction Emotes"),
    NAME_HIGHLIGHT(15, "Name Highlighted"),
    VIDEO_CLIPS(20, "Post Video Clips"),
    DM_CREATOR(25, "DM Creator"),
    EXCLUSIVE_EMOTES(30, "Exclusive Emotes"),
    PRIORITY_QA(40, "Priority Q&A"),
    PRIVATE_LIVE(50, "Private Live Access"),
    MAIN_FEED_BADGE(75, "Main Feed Badge"),
    MOD_ELIGIBLE(100, "Mod Nomination"),
    ANIMATED_BORDER(150, "Animated Border"),
    CUSTOM_TITLE(200, "Custom Title"),
    EARLY_ACCESS(300, "Early Content Access"),
    MERCH_DISCOUNT(400, "Merch Discount"),
    ANIMATED_BADGE(500, "Animated Badge"),
    ENTRANCE_ANIMATION(600, "Entrance Animation"),
    VOICE_CHAT(750, "Voice Chat"),
    CO_HOST_LIVE(850, "Co-Host Lives"),
    COMMUNITY_WALL(950, "Community Wall"),
    IMMORTAL_STATUS(1000, "Immortal Status");
}

// MARK: - Community XP Curve

/** Static XP calculation — CACHE AS LOOKUP TABLE ON APP LAUNCH */
object CommunityXPCurve {
    fun xpRequired(level: Int): Int {
        if (level <= 1) return 0
        return when (level) {
            in 2..20 -> level * 50
            in 21..100 -> (50.0 * level.toDouble().pow(1.5)).toInt()
            in 101..500 -> (50.0 * level.toDouble().pow(1.8)).toInt()
            else -> (50.0 * level.toDouble().pow(2.0)).toInt()
        }
    }

    fun totalXPForLevel(level: Int): Int {
        if (level <= 1) return 0
        var total = 0
        for (l in 2..level) total += xpRequired(l)
        return total
    }

    fun levelFromXP(xp: Int): Int {
        var level = 1
        var accumulated = 0
        while (level < 1000) {
            val needed = xpRequired(level + 1)
            if (accumulated + needed > xp) break
            accumulated += needed
            level++
        }
        return level
    }

    fun progressToNextLevel(currentXP: Int): Double {
        val currentLevel = levelFromXP(currentXP)
        if (currentLevel >= 1000) return 1.0
        val currentLevelTotal = totalXPForLevel(currentLevel)
        val nextLevelTotal = totalXPForLevel(currentLevel + 1)
        val range = nextLevelTotal - currentLevelTotal
        if (range <= 0) return 0.0
        val progress = currentXP - currentLevelTotal
        return min(1.0, max(0.0, progress.toDouble() / range.toDouble()))
    }
}

// MARK: - XP Source Actions

enum class CommunityXPSource(val key: String, val xpAmount: Int, val displayName: String) {
    TEXT_POST("text_post", 10, "Posted"),
    VIDEO_POST("video_post", 25, "Video Post"),
    REPLY("reply", 5, "Replied"),
    RECEIVED_HYPE("received_hype", 3, "Received Hype"),
    GAVE_HYPE("gave_hype", 1, "Gave Hype"),
    ATTENDED_LIVE("attended_live", 50, "Attended Live"),
    DAILY_LOGIN("daily_login", 15, "Daily Login"),
    SPENT_HYPE_COIN("spent_coin", 20, "Spent HypeCoin"),
    SPENT_HYPE_RATING("spent_hype_rating", 2, "Hype Action");

    companion object {
        fun fromKey(key: String): CommunityXPSource? = entries.find { it.key == key }
    }
}

// MARK: - Community Badge Definitions (25 Badges)

/** Static badge definitions — CACHE AT APP LAUNCH, NEVER REFETCH */
data class CommunityBadgeDefinition(
    val id: String,
    val level: Int,
    val name: String,
    val emoji: String,
    val description: String,
    val rewardDescription: String
) {
    companion object {
        val allBadges: List<CommunityBadgeDefinition> = listOf(
            CommunityBadgeDefinition("badge_01", 1, "Welcome", "\uD83D\uDC4B", "Joined the community", "Community profile created"),
            CommunityBadgeDefinition("badge_02", 3, "New Face", "\uD83D\uDFE2", "Getting started", "Profile border in community"),
            CommunityBadgeDefinition("badge_03", 5, "Colorful", "\uD83C\uDFA8", "Finding your style", "Custom flair color picker"),
            CommunityBadgeDefinition("badge_04", 8, "Chatterbox", "\uD83D\uDCAC", "Active in discussions", "Reaction emote pack 1"),
            CommunityBadgeDefinition("badge_05", 10, "Regular", "\uD83D\uDD01", "Consistent presence", "Name highlighted in posts"),
            CommunityBadgeDefinition("badge_06", 13, "Expressive", "\uD83C\uDFAD", "Engaging communicator", "Animated emote pack"),
            CommunityBadgeDefinition("badge_07", 15, "Clipper", "\uD83D\uDCF9", "Video contributor", "Post video clips"),
            CommunityBadgeDefinition("badge_08", 18, "Rising", "\uD83C\uDF1F", "On the way up", "Glow effect on username"),
            CommunityBadgeDefinition("badge_09", 20, "Connected", "✉\uFE0F", "Building relationships", "DM creator unlocked"),
            CommunityBadgeDefinition("badge_10", 25, "Dedicated", "\uD83D\uDD25", "Committed member", "Exclusive emote pack 2"),
            CommunityBadgeDefinition("badge_11", 30, "Sharpshooter", "\uD83C\uDFAF", "Precision engagement", "Priority in Q&A queues"),
            CommunityBadgeDefinition("badge_12", 40, "Guardian", "\uD83D\uDEE1\uFE0F", "Community protector", "Report/flag priority"),
            CommunityBadgeDefinition("badge_13", 50, "Inner Circle", "\uD83D\uDD34", "Trusted member", "Private live access"),
            CommunityBadgeDefinition("badge_14", 75, "Superfan", "⭐", "Above and beyond", "Badge visible on main feed"),
            CommunityBadgeDefinition("badge_15", 100, "Centurion", "\uD83D\uDC51", "Elite status", "Mod nomination eligible"),
            CommunityBadgeDefinition("badge_16", 150, "Diamond", "\uD83D\uDC8E", "Rare dedication", "Animated profile border"),
            CommunityBadgeDefinition("badge_17", 200, "Pillar", "\uD83C\uDFDB\uFE0F", "Community foundation", "Custom community title"),
            CommunityBadgeDefinition("badge_18", 300, "Eagle", "\uD83E\uDD85", "Soaring above", "Early access to creator content"),
            CommunityBadgeDefinition("badge_19", 400, "Warlord", "⚔\uFE0F", "Battle tested", "Exclusive merch discount"),
            CommunityBadgeDefinition("badge_20", 500, "Mythic", "\uD83D\uDC09", "Legendary status", "Animated badge + sound effect"),
            CommunityBadgeDefinition("badge_21", 600, "Transcendent", "\uD83C\uDF00", "Beyond mortal", "Custom entrance animation"),
            CommunityBadgeDefinition("badge_22", 750, "Oracle", "\uD83D\uDD2E", "All-seeing", "Direct voice chat with creator"),
            CommunityBadgeDefinition("badge_23", 850, "Cosmic", "\uD83E\uDE90", "Universe-level", "Co-host live streams"),
            CommunityBadgeDefinition("badge_24", 950, "Eternal", "⚡", "Timeless presence", "Name on community wall"),
            CommunityBadgeDefinition("badge_25", 1000, "Immortal", "\uD83C\uDFC6", "Maximum dedication", "Custom badge + creator collab invite")
        )

        fun badgesEarned(atLevel: Int): List<CommunityBadgeDefinition> = allBadges.filter { it.level <= atLevel }
        fun nextBadge(afterLevel: Int): CommunityBadgeDefinition? = allBadges.firstOrNull { it.level > afterLevel }
    }
}

// MARK: - Community Post

/** Firestore: communities/{creatorID}/posts/{postID} */
data class CommunityPost(
    val id: String = UUID.randomUUID().toString(),
    val communityID: String,
    val authorID: String,
    val authorUsername: String,
    val authorDisplayName: String,
    var authorLevel: Int,
    var authorBadgeIDs: List<String> = emptyList(),
    val isCreatorPost: Boolean,
    val postType: CommunityPostType,
    var body: String,
    var videoLinkID: String? = null,
    var videoThumbnailURL: String? = null,
    var hypeCount: Int = 0,
    var replyCount: Int = 0,
    var isPinned: Boolean = false,
    var isAutoGenerated: Boolean = false,
    val createdAt: Date = Date(),
    var updatedAt: Date = Date()
) {
    companion object {
        fun fromFirestore(id: String, data: Map<String, Any>): CommunityPost {
            return CommunityPost(
                id = id,
                communityID = data["communityID"] as? String ?: "",
                authorID = data["authorID"] as? String ?: "",
                authorUsername = data["authorUsername"] as? String ?: "",
                authorDisplayName = data["authorDisplayName"] as? String ?: "",
                authorLevel = (data["authorLevel"] as? Number)?.toInt() ?: 0,
                authorBadgeIDs = (data["authorBadgeIDs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                isCreatorPost = data["isCreatorPost"] as? Boolean ?: false,
                postType = CommunityPostType.fromKey(data["postType"] as? String ?: "text"),
                body = data["body"] as? String ?: "",
                videoLinkID = data["videoLinkID"] as? String,
                videoThumbnailURL = data["videoThumbnailURL"] as? String,
                hypeCount = (data["hypeCount"] as? Number)?.toInt() ?: 0,
                replyCount = (data["replyCount"] as? Number)?.toInt() ?: 0,
                isPinned = data["isPinned"] as? Boolean ?: false,
                isAutoGenerated = data["isAutoGenerated"] as? Boolean ?: false,
                createdAt = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date(),
                updatedAt = (data["updatedAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()
            )
        }
    }

    fun toFirestore(): Map<String, Any> = buildMap {
        put("id", id); put("communityID", communityID); put("authorID", authorID)
        put("authorUsername", authorUsername); put("authorDisplayName", authorDisplayName)
        put("authorLevel", authorLevel); put("authorBadgeIDs", authorBadgeIDs)
        put("isCreatorPost", isCreatorPost); put("postType", postType.key)
        put("body", body); put("hypeCount", hypeCount); put("replyCount", replyCount)
        put("isPinned", isPinned); put("isAutoGenerated", isAutoGenerated)
        videoLinkID?.let { put("videoLinkID", it) }
        videoThumbnailURL?.let { put("videoThumbnailURL", it) }
        put("createdAt", com.google.firebase.Timestamp(createdAt))
        put("updatedAt", com.google.firebase.Timestamp(updatedAt))
    }
}

enum class CommunityPostType(val key: String, val displayName: String) {
    TEXT("text", "Text Post"),
    VIDEO_LINK("video_link", "Video Link"),
    VIDEO_CLIP("video_clip", "Video Clip"),
    POLL("poll", "Poll"),
    AUTO_VIDEO_ANNOUNCEMENT("auto_video_announcement", "New Video");

    companion object {
        fun fromKey(key: String): CommunityPostType = entries.find { it.key == key } ?: TEXT
    }
}

// MARK: - Community Reply

/** Firestore: communities/{creatorID}/posts/{postID}/replies/{replyID} */
data class CommunityReply(
    val id: String = UUID.randomUUID().toString(),
    val postID: String,
    val communityID: String,
    val authorID: String,
    val authorUsername: String,
    val authorDisplayName: String,
    var authorLevel: Int,
    val isCreatorReply: Boolean,
    var body: String,
    var hypeCount: Int = 0,
    val createdAt: Date = Date()
) {
    companion object {
        fun fromFirestore(id: String, data: Map<String, Any>): CommunityReply {
            return CommunityReply(
                id = id,
                postID = data["postID"] as? String ?: "",
                communityID = data["communityID"] as? String ?: "",
                authorID = data["authorID"] as? String ?: "",
                authorUsername = data["authorUsername"] as? String ?: "",
                authorDisplayName = data["authorDisplayName"] as? String ?: "",
                authorLevel = (data["authorLevel"] as? Number)?.toInt() ?: 0,
                isCreatorReply = data["isCreatorReply"] as? Boolean ?: false,
                body = data["body"] as? String ?: "",
                hypeCount = (data["hypeCount"] as? Number)?.toInt() ?: 0,
                createdAt = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()
            )
        }
    }

    fun toFirestore(): Map<String, Any> = buildMap {
        put("id", id); put("postID", postID); put("communityID", communityID)
        put("authorID", authorID); put("authorUsername", authorUsername)
        put("authorDisplayName", authorDisplayName); put("authorLevel", authorLevel)
        put("isCreatorReply", isCreatorReply); put("body", body); put("hypeCount", hypeCount)
        put("createdAt", com.google.firebase.Timestamp(createdAt))
    }
}

// MARK: - Community Post Hype

/** Firestore: communities/{creatorID}/posts/{postID}/hypes/{userID} */
data class CommunityPostHype(
    val id: String,
    val postID: String,
    val userID: String,
    val communityID: String,
    val createdAt: Date = Date()
) {
    fun toFirestore(): Map<String, Any> = mapOf(
        "id" to id, "postID" to postID, "userID" to userID,
        "communityID" to communityID,
        "createdAt" to com.google.firebase.Timestamp(createdAt)
    )
}

// MARK: - XP Transaction Log

/** Firestore: communities/{creatorID}/members/{userID}/xpLog/{logID}
 *  BATCHING: Accumulated locally, flushed every 30s or on app background */
data class CommunityXPTransaction(
    val id: String = UUID.randomUUID().toString(),
    val communityID: String,
    val userID: String,
    val source: String,
    val amount: Int,
    val newTotalXP: Int,
    val newLevel: Int,
    val leveledUp: Boolean,
    val badgeUnlocked: String? = null,
    val createdAt: Date = Date()
) {
    fun toFirestore(): Map<String, Any> = buildMap {
        put("id", id); put("communityID", communityID); put("userID", userID)
        put("source", source); put("amount", amount)
        put("newTotalXP", newTotalXP); put("newLevel", newLevel)
        put("leveledUp", leveledUp)
        badgeUnlocked?.let { put("badgeUnlocked", it) }
        put("createdAt", com.google.firebase.Timestamp(createdAt))
    }
}

// MARK: - Community List Item (Lightweight for List View)

/** Denormalized for fast list rendering — avoids N+1 reads
 *  CACHING: Cache entire list with 5-min TTL on first community tab load */
data class CommunityListItem(
    val id: String,
    val creatorUsername: String,
    val creatorDisplayName: String,
    val creatorTier: String,
    val profileImageURL: String?,
    var memberCount: Int,
    var userLevel: Int,
    var userXP: Int,
    var unreadCount: Int,
    var lastActivityPreview: String,
    var lastActivityAt: Date,
    var isCreatorLive: Boolean,
    var isVerified: Boolean
)

// MARK: - Community DM

/** Firestore: communities/{creatorID}/dms/{conversationID}/messages/{messageID} */
data class CommunityDM(
    val id: String = UUID.randomUUID().toString(),
    val communityID: String,
    val senderID: String,
    val senderUsername: String,
    val recipientID: String,
    val body: String,
    var isRead: Boolean = false,
    val createdAt: Date = Date()
)

data class CommunityDMConversation(
    val id: String,
    val communityID: String,
    val memberID: String,
    val memberUsername: String,
    val memberDisplayName: String,
    var memberLevel: Int,
    val creatorID: String,
    var lastMessage: String = "",
    var lastMessageAt: Date = Date(),
    var unreadCountMember: Int = 0,
    var unreadCountCreator: Int = 0
)

// MARK: - Global Community XP

/** Firestore: users/{userID}/globalCommunityXP
 *  CACHING: Cache on login, refresh every 15 min */
data class GlobalCommunityXP(
    val userID: String,
    var totalGlobalXP: Int = 0,
    var globalLevel: Int = 1,
    var permanentCloutBonus: Int = 0,
    var tapMultiplierBonus: Int = 0,
    var communitiesActive: Int = 0,
    var lastCalculatedAt: Date = Date()
) {
    companion object {
        fun cloutBonusForLevel(level: Int): Int = when (level) {
            10 -> 50; 25 -> 150; 50 -> 500; 75 -> 1000
            100 -> 2000; 150 -> 3500; 200 -> 5000; else -> 0
        }

        fun tapMultiplierForLevel(level: Int): Int = when {
            level < 10 -> 0; level < 25 -> 1; level < 50 -> 2
            level < 75 -> 3; level < 100 -> 4; else -> 5
        }

        const val LOCAL_TO_GLOBAL_RATE = 0.25

        fun globalContribution(fromLocalXP: Int): Int = (fromLocalXP * LOCAL_TO_GLOBAL_RATE).toInt()

        fun fromFirestore(data: Map<String, Any>): GlobalCommunityXP {
            return GlobalCommunityXP(
                userID = data["userID"] as? String ?: "",
                totalGlobalXP = (data["totalGlobalXP"] as? Number)?.toInt() ?: 0,
                globalLevel = (data["globalLevel"] as? Number)?.toInt() ?: 1,
                permanentCloutBonus = (data["permanentCloutBonus"] as? Number)?.toInt() ?: 0,
                tapMultiplierBonus = (data["tapMultiplierBonus"] as? Number)?.toInt() ?: 0,
                communitiesActive = (data["communitiesActive"] as? Number)?.toInt() ?: 0,
                lastCalculatedAt = (data["lastCalculatedAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()
            )
        }
    }

    fun toFirestore(): Map<String, Any> = mapOf(
        "userID" to userID, "totalGlobalXP" to totalGlobalXP,
        "globalLevel" to globalLevel, "permanentCloutBonus" to permanentCloutBonus,
        "tapMultiplierBonus" to tapMultiplierBonus, "communitiesActive" to communitiesActive,
        "lastCalculatedAt" to com.google.firebase.Timestamp(lastCalculatedAt)
    )
}

// MARK: - Community Tap Usage (Local Cache Only)

/** Local cache only — tracks daily bonus tap usage per community
 *  CACHING: Store as local dictionary, reset at midnight, NO Firestore reads per tap */
data class CommunityTapUsage(
    val communityID: String,
    val date: String,               // "yyyy-MM-dd" for daily reset
    var bonusTapsUsed: Int,
    val bonusTapsAllowed: Int
) {
    val hasRemainingBonusTaps: Boolean get() = bonusTapsUsed < bonusTapsAllowed
    val remainingBonusTaps: Int get() = max(0, bonusTapsAllowed - bonusTapsUsed)
}

// MARK: - Video Announcement Payload

data class VideoAnnouncementPayload(
    val videoID: String,
    val videoTitle: String,
    val thumbnailURL: String?,
    val creatorID: String,
    val communityID: String,
    val createdAt: Date = Date()
) {
    val postBody: String get() = "\uD83C\uDFAC New drop: \"$videoTitle\" — Watch now and discuss!"
}

// MARK: - Community Status

sealed class CommunityStatus {
    object NotCreated : CommunityStatus()
    data class Inactive(val data: Community) : CommunityStatus()
    data class Active(val data: Community) : CommunityStatus()

    val exists: Boolean get() = this !is NotCreated
    val isActive: Boolean get() = this is Active
    val community: Community? get() = when (this) {
        is Inactive -> data
        is Active -> data
        else -> null
    }
}

// MARK: - Errors

sealed class CommunityError(override val message: String) : Exception(message) {
    object InsufficientTier : CommunityError("You need Influencer tier or higher to create a community")
    object CommunityAlreadyExists : CommunityError("You already have a community")
    object CommunityNotFound : CommunityError("Community not found or inactive")
    object SubscriptionRequired : CommunityError("You need an active subscription to join this community")
    object AlreadyMember : CommunityError("You're already a member of this community")
    object NotMember : CommunityError("You're not a member of this community")
    object LevelTooLow : CommunityError("Level requirement not met")
    object Banned : CommunityError("You've been banned from this community")
    object CreatorOnly : CommunityError("Only the creator can perform this action")
}

sealed class CommunityFeedError(override val message: String) : Exception(message) {
    object CommunityNotFound : CommunityFeedError("Community not found")
    data class LevelTooLow(val required: Int) : CommunityFeedError("You need level $required to do this")
    object AlreadyHyped : CommunityFeedError("You already hyped this post")
    object PostNotFound : CommunityFeedError("Post not found")
}

// MARK: - Result Types

data class XPAwardResult(
    val xpAwarded: Int,
    val newTotalXP: Int,
    val oldLevel: Int,
    val newLevel: Int,
    val leveledUp: Boolean,
    val newBadges: List<CommunityBadgeDefinition>,
    val globalXPContribution: Int
)

data class LevelUpEvent(
    val id: String = UUID.randomUUID().toString(),
    val userID: String,
    val communityID: String,
    val oldLevel: Int,
    val newLevel: Int,
    val timestamp: Date = Date()
)

data class DailyLoginResult(
    val awarded: Boolean,
    val xpAmount: Int,
    val streak: Int,
    val message: String
)

data class BadgeCheckResult(
    val totalEarned: Int,
    val totalAvailable: Int,
    val newlyEligible: List<CommunityBadgeDefinition>,
    val nextBadge: CommunityBadgeDefinition?,
    val levelsToNextBadge: Int
) {
    val progress: Double get() = if (totalAvailable > 0) totalEarned.toDouble() / totalAvailable else 0.0
}

data class GlobalXPSummary(
    val totalXP: Int,
    val level: Int,
    val cloutBonus: Int,
    val tapMultiplier: Int,
    val communitiesActive: Int,
    val progress: Double,
    val xpToNext: Int,
    val nextCloutMilestone: Pair<Int, Int>?,
    val nextTapUpgrade: Pair<Int, Int>?
)

// NOTE: SubscriptionTier lives in com.stitchsocial.club.foundation — do NOT duplicate here