/*
 * CommunityHomeModels.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Models - the community home page's server-built extras.
 *
 * Decoded from the `community_home` callable. Every field is optional on the
 * wire and decodes to a safe default, so a partial or newer response can never
 * fail the whole page — the same lesson as CommunityMembership on iOS, where one
 * required field nobody wrote emptied the leaderboard.
 */

package com.stitchsocial.club.community

/** community_home({communityID}) */
data class CommunityHome(
    /** Thumbnail of the creator's latest top-level video. */
    val coverURL: String? = null,
    /** Members active in the last 7 days; null when the server couldn't say. */
    val activeThisWeek: Int? = null,
    val interests: CommunityInterests = CommunityInterests(),
    /** Present only when the caller owns the community. */
    val owner: CommunityHomeOwner? = null,
) {
    companion object {
        fun from(raw: Map<String, Any?>): CommunityHome = CommunityHome(
            coverURL = (raw["coverURL"] as? String)?.takeIf { it.isNotBlank() },
            activeThisWeek = (raw["activeThisWeek"] as? Number)?.toInt(),
            interests = CommunityInterests.from(raw["interests"].asMap()),
            owner = raw["owner"].asMap()?.let { CommunityHomeOwner.from(it) },
        )
    }
}

/** "Into right now" — each key present only when there is something to show. */
data class CommunityInterests(
    val picks: InterestPicks? = null,
    val hyped: List<InterestHypedVideo> = emptyList(),
    val watching: List<InterestWatching> = emptyList(),
) {
    val isEmpty: Boolean get() = picks == null && hyped.isEmpty() && watching.isEmpty()

    companion object {
        fun from(raw: Map<String, Any?>?): CommunityInterests {
            if (raw == null) return CommunityInterests()
            return CommunityInterests(
                picks = raw["picks"].asMap()?.let { InterestPicks.from(it) },
                hyped = raw["hyped"].asMapList().mapNotNull { InterestHypedVideo.from(it) },
                watching = raw["watching"].asMapList().mapNotNull { InterestWatching.from(it) },
            )
        }
    }
}

data class InterestPicks(
    val lastNight: PicksNight? = null,
    val weekCards: Int = 0,
    val weekCashed: Int = 0,
    val landingSlug: String? = null,
) {
    companion object {
        fun from(raw: Map<String, Any?>) = InterestPicks(
            lastNight = raw["lastNight"].asMap()?.let { PicksNight.from(it) },
            weekCards = (raw["weekCards"] as? Number)?.toInt() ?: 0,
            weekCashed = (raw["weekCashed"] as? Number)?.toInt() ?: 0,
            landingSlug = (raw["landingSlug"] as? String)?.takeIf { it.isNotBlank() },
        )
    }
}

data class PicksNight(
    /** "YYYY-MM-DD" */
    val date: String = "",
    val right: Int = 0,
    val total: Int = 0,
    /** true = right, false = wrong, in card order. */
    val results: List<Boolean> = emptyList(),
) {
    companion object {
        fun from(raw: Map<String, Any?>) = PicksNight(
            date = raw["date"] as? String ?: "",
            right = (raw["right"] as? Number)?.toInt() ?: 0,
            total = (raw["total"] as? Number)?.toInt() ?: 0,
            results = (raw["results"] as? List<*>)?.mapNotNull {
                when (it as? String) {
                    "right" -> true
                    "wrong" -> false
                    else -> null
                }
            } ?: emptyList(),
        )
    }
}

data class InterestHypedVideo(
    val videoID: String,
    val thumbnailURL: String?,
    val title: String,
) {
    companion object {
        fun from(raw: Map<String, Any?>): InterestHypedVideo? {
            val id = (raw["videoID"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return InterestHypedVideo(
                videoID = id,
                thumbnailURL = (raw["thumbnailURL"] as? String)?.takeIf { it.isNotBlank() },
                title = raw["title"] as? String ?: "",
            )
        }
    }
}

data class InterestWatching(
    val showID: String,
    val title: String,
    val coverURL: String?,
    val collectionID: String?,
    val episodeTitle: String,
    val season: Int?,
    val episode: Int?,
    val percent: Double,
) {
    /** "Season 2 · Ep 4", omitting whichever part is missing; null when both are. */
    val seasonEpisodeLabel: String?
        get() = listOfNotNull(season?.let { "Season $it" }, episode?.let { "Ep $it" })
            .joinToString(" · ")
            .takeIf { it.isNotEmpty() }

    companion object {
        fun from(raw: Map<String, Any?>): InterestWatching? {
            val showID = (raw["showID"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return InterestWatching(
                showID = showID,
                title = raw["title"] as? String ?: "",
                coverURL = (raw["coverURL"] as? String)?.takeIf { it.isNotBlank() },
                collectionID = (raw["collectionID"] as? String)?.takeIf { it.isNotBlank() },
                episodeTitle = raw["episodeTitle"] as? String ?: "",
                season = (raw["season"] as? Number)?.toInt(),
                episode = (raw["episode"] as? Number)?.toInt(),
                percent = (raw["percent"] as? Number)?.toDouble() ?: 0.0,
            )
        }
    }
}

/** Owner-only block of community_home. */
data class CommunityHomeOwner(
    val newMembers: Int = 0,
    val posts: Int = 0,
    val activeMembers: Int = 0,
    val interestCards: InterestCardSettings = InterestCardSettings(),
) {
    companion object {
        fun from(raw: Map<String, Any?>): CommunityHomeOwner {
            val week = raw["week"].asMap()
            return CommunityHomeOwner(
                newMembers = (week?.get("newMembers") as? Number)?.toInt() ?: 0,
                posts = (week?.get("posts") as? Number)?.toInt() ?: 0,
                activeMembers = (week?.get("activeMembers") as? Number)?.toInt() ?: 0,
                interestCards = InterestCardSettings.from(raw["interestCards"].asMap()),
            )
        }
    }
}

/**
 * Which "Into right now" cards the owner shows. Defaults: picks on, the
 * other two off — the same defaults the server applies to an unset field.
 */
data class InterestCardSettings(
    val picks: Boolean = true,
    val hyped: Boolean = false,
    val watching: Boolean = false,
) {
    fun toPayload(): Map<String, Any> = mapOf("picks" to picks, "hyped" to hyped, "watching" to watching)

    companion object {
        fun from(raw: Map<String, Any?>?): InterestCardSettings = InterestCardSettings(
            picks = raw?.get("picks") as? Boolean ?: true,
            hyped = raw?.get("hyped") as? Boolean ?: false,
            watching = raw?.get("watching") as? Boolean ?: false,
        )
    }
}

// ── Decoding helpers ─────────────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

private fun Any?.asMapList(): List<Map<String, Any?>> =
    (this as? List<*>)?.mapNotNull { it.asMap() } ?: emptyList()
