package com.stitchsocial.club.services

/**
 * One answer to "may this viewer be shown this", used by every surface —
 * iOS parity (TeenSafety.swift).
 *
 * Android had no teen filtering of any kind. The word teenSafe did not appear
 * in the Kotlin codebase: not read, not written, not considered. What kept
 * minors safe here was the gate in MainActivity, which replaced the whole app
 * with a waiting screen — safe precisely because nothing behind it was.
 *
 * Opening that gate means every surface needs the rule, so the rule is stated
 * once and every decode path asks it.
 *
 * What it trusts: videos/{id}.teenSafe, written by the moderation pipeline from
 * the labels AND the author — a teen author or an account approved to address
 * teens, a passing scan, and no teen-unsafe label. Not the old field of the
 * same name on iOS, which the uploading client wrote from its own age.
 *
 * It fails closed: an absent field is not safe, and a viewer whose age could not
 * be read is treated as a teen until it can.
 */
object TeenSafety {

    /**
     * The viewer's lane, cached for the session.
     *
     * Defaults to teen rather than adult on purpose. The window between launch
     * and the first read is short, and being briefly over-cautious costs an
     * adult a few videos, while the opposite costs a child something that
     * cannot be taken back.
     */
    @Volatile
    private var lane: String = "teen"

    val viewerIsTeen: Boolean
        get() = lane == "teen"

    /** Called once the age gate has read the user document — it does that read
     *  anyway, so this costs nothing extra. */
    fun setLane(ageGroup: String?) {
        lane = when (ageGroup) {
            "adult" -> "adult"
            "teen" -> "teen"
            // "blocked" never reaches a feed, and an unknown lane is not an
            // adult one.
            else -> "teen"
        }
    }

    /** Signed out: assume nothing until the next account says otherwise. */
    fun reset() { lane = "teen" }

    /** The gate, for a raw Firestore document. */
    fun allows(data: Map<String, Any?>?): Boolean {
        if (!viewerIsTeen) return true
        return data?.get("teenSafe") == true
    }

    /**
     * Whether a teen may be shown this account at all — in search, in
     * suggestions, on a leaderboard. Another teen, or an account approved to
     * address them.
     */
    @Suppress("UNCHECKED_CAST")
    fun allowsAccount(data: Map<String, Any?>?): Boolean {
        if (!viewerIsTeen) return true
        if (data == null) return false
        val privacy = data["privacySettings"] as? Map<String, Any?>
        if (privacy?.get("ageGroup") == "teen") return true
        return data["teenApproved"] == true
    }
}
