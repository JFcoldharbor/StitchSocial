package com.stitchsocial.club.challenge

import java.util.Date

/**
 * Challenge / Giveaway models (iOS parity — see project memory project_stitch_challenge).
 * DRAW mode: enter by posting a video with the set hashtag onto the challenge thread;
 * hit the metric threshold to QUALIFY, then a random seeded draw of `winnerCount` among
 * qualifiers at the deadline. Server-authoritative — the client only writes the creator's
 * config onto the thread-head video. Pure Kotlin (no Firebase); the decoder in VideoService
 * converts Firestore Timestamps to Date.
 */

enum class ChallengeScope(val raw: String) {
    ANYONE("anyone"), FOLLOWERS("followers"), COMMUNITY("community");
    companion object { fun from(s: String?) = entries.firstOrNull { it.raw == s } ?: ANYONE }
}

enum class ChallengeMetric(val raw: String) {
    HYPES("hypes"), SHARES("shares");
    val displayName: String get() = if (this == HYPES) "hypes" else "shares"
    companion object { fun from(s: String?) = entries.firstOrNull { it.raw == s } ?: HYPES }
}

enum class ChallengeState(val raw: String) {
    ACTIVE("active"), DRAWING("drawing"), COMPLETED("completed");
    companion object { fun from(s: String?) = entries.firstOrNull { it.raw == s } ?: ACTIVE }
}

enum class ChallengeEntryStatus(val raw: String) {
    ENTERED("entered"), QUALIFIED("qualified"), WON("won");
    companion object { fun from(s: String?) = entries.firstOrNull { it.raw == s } }
}

/** Challenge config + live counters (config locked once posted; counters/state server-owned). */
data class Challenge(
    val prize: String,
    val hashtag: String,          // normalized lowercase, no leading '#'
    val scope: ChallengeScope,
    val communityID: String?,
    val metric: ChallengeMetric,
    val threshold: Int,
    val winnerCount: Int,
    val deadline: Date,
    val createdAt: Date,
    val state: ChallengeState,
    val entryCount: Int,
    val qualifierCount: Int,
    val drawSeed: String?
) {
    val isActive: Boolean get() = state == ChallengeState.ACTIVE && deadline.after(Date())
    val timeRemainingMs: Long get() = (deadline.time - System.currentTimeMillis()).coerceAtLeast(0L)
    val ruleSummary: String get() {
        val w = if (winnerCount == 1) "1 winner" else "$winnerCount winners"
        return "Hit $threshold ${metric.displayName} to qualify · $w"
    }
}

/** Config the composer collects; the service expands it into a full Challenge on write. */
data class ChallengeDraft(
    val prize: String = "",
    val hashtag: String = "",
    val scope: ChallengeScope = ChallengeScope.ANYONE,
    val communityID: String? = null,
    val metric: ChallengeMetric = ChallengeMetric.HYPES,
    val threshold: Int = 100,
    val winnerCount: Int = 1,
    val deadline: Date = Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000)
) {
    val normalizedHashtag: String get() {
        var h = hashtag.trim()
        if (h.startsWith("#")) h = h.substring(1)
        return h.lowercase()
    }

    /** The composer's upload gate. */
    val isValid: Boolean get() =
        prize.trim().isNotEmpty() && normalizedHashtag.isNotEmpty() && threshold > 0 &&
            winnerCount >= 1 && deadline.after(Date()) &&
            (scope != ChallengeScope.COMMUNITY || !communityID.isNullOrEmpty())

    /** What's still missing (drives the "finish setup" hint under a disabled Post). */
    val missingFields: List<String> get() {
        val m = mutableListOf<String>()
        if (prize.trim().isEmpty()) m.add("Prize")
        if (normalizedHashtag.isEmpty()) m.add("Entry hashtag")
        if (threshold <= 0) m.add("Qualification threshold")
        if (winnerCount < 1) m.add("Number of winners")
        if (!deadline.after(Date())) m.add("A future deadline")
        if (scope == ChallengeScope.COMMUNITY && communityID.isNullOrEmpty()) m.add("A community")
        return m
    }
}
