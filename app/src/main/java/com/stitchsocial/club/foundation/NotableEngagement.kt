/*
 * NotableEngagement.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 2: Data Models - Notable Engagement & Social Signal Types
 * Dependencies: Foundation only
 * Features: Records high-tier hypes for megaphone distribution
 * MATCHES iOS NotableEngagement.swift
 */

package com.stitchsocial.club.foundation

import java.util.Date

// MARK: - Notable Engagement Record

/**
 * Written to videos/{videoID}/notableEngagements/{docID}
 * when a Partner+ tier user hypes a video
 */
data class NotableEngagement(
    val id: String,
    val engagerID: String,
    val engagerName: String,
    val engagerTier: String,
    val engagerProfileImageURL: String?,
    val videoID: String,
    val videoCreatorID: String,
    val hypeWeight: Int,
    val cloutAwarded: Int,
    val createdAt: Date
) {
    companion object {
        val MEGAPHONE_TIERS = setOf(
            "partner", "elite", "ambassador", "legendary", "topCreator", "coFounder", "founder"
        )

        fun isMegaphoneTier(tier: String): Boolean = MEGAPHONE_TIERS.contains(tier)
    }
}

// MARK: - Social Signal (Feed Injection Record)

/**
 * Written to users/{followerID}/socialSignals/{docID}
 * One per follower of the engager, for feed injection
 */
data class SocialSignal(
    val id: String,
    val videoID: String,
    val videoCreatorID: String,
    val videoCreatorName: String,
    val videoTitle: String,
    val videoThumbnailURL: String?,
    val engagerID: String,
    val engagerName: String,
    val engagerTier: String,
    val engagerProfileImageURL: String?,
    val hypeWeight: Int,
    val createdAt: Date,
    var impressionCount: Int = 0,
    var dismissed: Boolean = false,
    var engagedWith: Boolean = false,
    var lastImpressionAt: Date? = null
)