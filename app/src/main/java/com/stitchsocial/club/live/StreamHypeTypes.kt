package com.stitchsocial.club.live

import com.google.firebase.Timestamp
import java.util.UUID

/**
 * Hype taxonomy — mirrors iOS `StreamHypeType` enum byte-for-byte (raw strings
 * + coin costs + multipliers). Both platforms write the same enum into the
 * `hypeEvents` subcollection, so a creator on iOS sees hypes sent from Android
 * viewers and vice versa.
 *
 * Coin costs and revenue splits are fixed at the protocol layer — changing
 * them on one platform would mean both apps disagree on what a "Super Hype"
 * costs. Keep this in sync with `LiveStream.swift:526` (StreamHypeType).
 */
enum class StreamHypeType(
    val raw: String,
    val displayName: String,
    val emoji: String,
    val coinCost: Int,
    /// Creator's cut of the coin cost (0.0–1.0). Platform keeps the rest.
    val creatorRevenuePercent: Double,
    /// XP multiplier applied to viewer for `multiplierDurationSeconds`.
    val xpMultiplier: Int,
    val multiplierDurationSeconds: Int,
) {
    SUPER_HYPE(
        raw = "superHype",
        displayName = "Super Hype",
        emoji = "🔥",
        coinCost = 5,
        creatorRevenuePercent = 0.70,
        xpMultiplier = 2,
        multiplierDurationSeconds = 600,
    ),
    MEGA_HYPE(
        raw = "megaHype",
        displayName = "Mega Hype",
        emoji = "⚡",
        coinCost = 15,
        creatorRevenuePercent = 0.70,
        xpMultiplier = 5,
        multiplierDurationSeconds = 600,
    ),
    ULTRA_HYPE(
        raw = "ultraHype",
        displayName = "Ultra Hype",
        emoji = "💎",
        coinCost = 50,
        creatorRevenuePercent = 0.75,
        xpMultiplier = 10,
        multiplierDurationSeconds = 900,
    ),
    GIFT_SUB(
        raw = "giftSub",
        displayName = "Gift Sub",
        emoji = "🎁",
        coinCost = 25,
        creatorRevenuePercent = 0.70,
        xpMultiplier = 1,
        multiplierDurationSeconds = 0,
    ),
    SPOTLIGHT(
        raw = "spotlight",
        displayName = "Spotlight",
        emoji = "📌",
        coinCost = 10,
        creatorRevenuePercent = 0.70,
        xpMultiplier = 1,
        multiplierDurationSeconds = 0,
    ),
    BOOST_STREAM(
        raw = "boostStream",
        displayName = "Boost Stream",
        emoji = "🚀",
        coinCost = 100,
        // Platform keeps more here to fund the discovery push that "boost" buys.
        creatorRevenuePercent = 0.50,
        xpMultiplier = 1,
        multiplierDurationSeconds = 0,
    );

    val creatorRevenue: Int get() = (coinCost * creatorRevenuePercent).toInt()

    companion object {
        const val XP_PER_COIN_SPENT: Int = 20

        fun fromRaw(raw: String?): StreamHypeType? =
            entries.firstOrNull { it.raw == raw }
    }
}

/**
 * A single hype broadcast event. Persisted in
 * `communities/{creatorID}/streams/{streamID}/hypeEvents/{eventID}` so both
 * platforms can listen and surface alerts.
 */
data class StreamHypeEvent(
    val id: String,
    val streamID: String,
    val communityID: String,
    val senderID: String,
    val senderUsername: String,
    val senderLevel: Int,
    val hypeType: StreamHypeType,
    val createdAt: Timestamp,
) {
    val coinCost: Int get() = hypeType.coinCost
    val xpMultiplier: Int get() = hypeType.xpMultiplier

    fun toFirestore(): Map<String, Any> = mapOf(
        "id" to id,
        "streamID" to streamID,
        "communityID" to communityID,
        "senderID" to senderID,
        "senderUsername" to senderUsername,
        "senderLevel" to senderLevel,
        "hypeType" to hypeType.raw,
        "coinCost" to hypeType.coinCost,
        "xpMultiplier" to hypeType.xpMultiplier,
        "createdAt" to createdAt,
    )

    companion object {
        fun create(
            streamID: String,
            communityID: String,
            senderID: String,
            senderUsername: String,
            senderLevel: Int,
            hypeType: StreamHypeType,
        ): StreamHypeEvent = StreamHypeEvent(
            id = UUID.randomUUID().toString(),
            streamID = streamID,
            communityID = communityID,
            senderID = senderID,
            senderUsername = senderUsername,
            senderLevel = senderLevel,
            hypeType = hypeType,
            createdAt = Timestamp.now(),
        )

        fun fromDoc(id: String, data: Map<String, Any?>): StreamHypeEvent? {
            val senderID = data["senderID"] as? String ?: return null
            val type = StreamHypeType.fromRaw(data["hypeType"] as? String) ?: return null
            return StreamHypeEvent(
                id = id,
                streamID = data["streamID"] as? String ?: "",
                communityID = data["communityID"] as? String ?: "",
                senderID = senderID,
                senderUsername = data["senderUsername"] as? String ?: "",
                senderLevel = (data["senderLevel"] as? Number)?.toInt() ?: 0,
                hypeType = type,
                createdAt = data["createdAt"] as? Timestamp ?: Timestamp.now(),
            )
        }
    }
}

/// Local-only multiplier state. Reset on stream end.
data class ActiveXPMultiplier(
    val multiplier: Int = 1,
    val expiresAt: Long = 0,
) {
    val isActive: Boolean
        get() = multiplier > 1 && System.currentTimeMillis() < expiresAt

    fun applied(hype: StreamHypeType): ActiveXPMultiplier {
        if (hype.xpMultiplier <= 1) return this
        val newExpiry = System.currentTimeMillis() + hype.multiplierDurationSeconds * 1000L
        // Higher multiplier wins; same multiplier extends.
        return when {
            !isActive || hype.xpMultiplier > multiplier ->
                ActiveXPMultiplier(hype.xpMultiplier, newExpiry)
            hype.xpMultiplier == multiplier ->
                copy(expiresAt = newExpiry)
            else -> this
        }
    }
}
