package com.stitchsocial.club.landing

import com.google.firebase.Timestamp
import java.time.Instant
import java.util.Date

/**
 * Stitch Landings — a restaurant's permanent page in Stitch (LANDINGS_CONTRACT).
 *
 * A guest scans the table QR, claims the welcome perk in one tap, then checks
 * in and posts a moment for the post perk. Perks are spent with Use now: a
 * 15-minute active ticket the server or cashier looks at.
 *
 * Every value here arrives from one of two places — a callable (times as ISO
 * strings) or a Firestore read (times as Timestamps) — so the parsers accept
 * both rather than each screen guessing which it got.
 *
 * Layer 1: Foundation
 */

/** One offer on a Landing. `title` is the item ("taco"); the screens add "Free …". */
data class LandingOffer(
    val title: String,
    val smallPrint: String = "",
    val expiryDays: Int = 7,
    /** Post offer only: "tonight" | "next_visit". */
    val usableFrom: String = "tonight",
    val active: Boolean = true
) {
    val isNextVisit: Boolean get() = usableFrom == "next_visit"

    companion object {
        fun from(raw: Any?): LandingOffer? {
            val m = raw as? Map<*, *> ?: return null
            val title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return LandingOffer(
                title = title,
                smallPrint = m["smallPrint"] as? String ?: "",
                expiryDays = (m["expiryDays"] as? Number)?.toInt() ?: 7,
                usableFrom = m["usableFrom"] as? String ?: "tonight",
                active = m["active"] as? Boolean ?: true
            )
        }
    }
}

/** One rung of a Landing's visit card (addendum 2): the reward at visit `visits`. */
data class VisitReward(val visits: Int, val title: String) {
    companion object {
        fun from(raw: Any?): VisitReward? {
            val m = raw as? Map<*, *> ?: return null
            val visits = (m["visits"] as? Number)?.toInt() ?: return null
            val title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return VisitReward(visits, title)
        }

        /** Sorted ascending, unique visit counts — the server's order, enforced. */
        fun list(raw: Any?): List<VisitReward> =
            (raw as? List<*>).orEmpty().mapNotNull { from(it) }.distinctBy { it.visits }.sortedBy { it.visits }
    }
}

/**
 * One stop on an artist Landing's tour. Comes from landing_resolve only: raw
 * landings/{id} reads lack dayKey, opensAt and closesAt. The window runs 06:00
 * to 06:00 in the venue's time zone, so a late show is one visit.
 */
data class TourStop(
    val stopID: String,
    /** "YYYY-MM-DD" in the venue's time zone. */
    val date: String,
    val timeZone: String = "",
    val venue: String = "",
    val city: String = "",
    val country: String = "",
    val dayKey: String = "",
    val opensAt: Date? = null,
    val closesAt: Date? = null
) {
    companion object {
        fun from(raw: Any?): TourStop? {
            val m = raw as? Map<*, *> ?: return null
            return TourStop(
                stopID = (m["stopID"] as? String)?.takeIf { it.isNotBlank() } ?: return null,
                date = (m["date"] as? String)?.takeIf { it.isNotBlank() } ?: return null,
                timeZone = m["timeZone"] as? String ?: "",
                venue = m["venue"] as? String ?: "",
                city = m["city"] as? String ?: "",
                country = m["country"] as? String ?: "",
                dayKey = m["dayKey"] as? String ?: "",
                opensAt = landingDate(m["opensAt"]),
                closesAt = landingDate(m["closesAt"])
            )
        }
    }
}

/** The song an artist Landing's fans reply to. */
data class SongThread(val videoID: String, val title: String) {
    companion object {
        fun from(raw: Any?): SongThread? {
            val m = raw as? Map<*, *> ?: return null
            val id = (m["videoID"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return SongThread(id, m["title"] as? String ?: "")
        }
    }
}

data class Landing(
    val landingID: String,
    val slug: String,
    val name: String,
    val neighborhood: String = "",
    val address: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusM: Int = 120,
    val hours: String = "",
    val logoURL: String? = null,
    val coverURL: String? = null,
    /** "sit_down" | "counter" | "counter_pickup" */
    val serviceStyle: String = "sit_down",
    val welcomeOffer: LandingOffer? = null,
    val postOffer: LandingOffer? = null,
    /** The visit card ladder (addendum 2); empty means no visit card. */
    val visitRewards: List<VisitReward> = emptyList(),
    /** "draft" | "live" | "paused" | "removed" */
    val status: String = "live",
    val businessID: String = "",
    val verified: Boolean = false,
    /** "artist", or null for a restaurant. */
    val kind: String? = null,
    val tourStops: List<TourStop> = emptyList(),
    val songThread: SongThread? = null,
    val spotifyURL: String? = null,
    val appleMusicURL: String? = null,
    /** The show open right now; both are null when none is. */
    val currentStopID: String? = null,
    val currentDayKey: String? = null
) {
    val isLive: Boolean get() = status == "live"
    val isCounter: Boolean get() = serviceStyle != "sit_down"
    val isArtist: Boolean get() = kind == "artist"

    companion object {
        fun from(raw: Any?, fallbackID: String? = null): Landing? {
            val m = raw as? Map<*, *> ?: return null
            val id = (m["landingID"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackID ?: return null
            return Landing(
                landingID = id,
                slug = m["slug"] as? String ?: "",
                name = m["name"] as? String ?: "",
                neighborhood = m["neighborhood"] as? String ?: "",
                address = m["address"] as? String ?: "",
                lat = (m["lat"] as? Number)?.toDouble(),
                lng = (m["lng"] as? Number)?.toDouble(),
                radiusM = (m["radiusM"] as? Number)?.toInt() ?: 120,
                hours = m["hours"] as? String ?: "",
                logoURL = (m["logoURL"] as? String)?.takeIf { it.isNotBlank() },
                coverURL = (m["coverURL"] as? String)?.takeIf { it.isNotBlank() },
                serviceStyle = m["serviceStyle"] as? String ?: "sit_down",
                welcomeOffer = LandingOffer.from(m["welcomeOffer"]),
                postOffer = LandingOffer.from(m["postOffer"]),
                visitRewards = VisitReward.list(m["visitRewards"]),
                status = m["status"] as? String ?: "live",
                businessID = m["businessID"] as? String ?: "",
                verified = m["verified"] as? Boolean ?: false,
                kind = (m["kind"] as? String)?.takeIf { it.isNotBlank() },
                tourStops = (m["tourStops"] as? List<*>).orEmpty().mapNotNull { TourStop.from(it) },
                songThread = SongThread.from(m["songThread"]),
                spotifyURL = ((m["streamLinks"] as? Map<*, *>)?.get("spotify") as? String)?.takeIf { it.isNotBlank() },
                appleMusicURL = ((m["streamLinks"] as? Map<*, *>)?.get("appleMusic") as? String)?.takeIf { it.isNotBlank() },
                currentStopID = (m["currentStopID"] as? String)?.takeIf { it.isNotBlank() },
                currentDayKey = (m["currentDayKey"] as? String)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

/** perks/{perkID} — read straight from Firestore, or returned by a callable. */
data class Perk(
    val perkID: String,
    val landingID: String,
    val landingName: String = "",
    val landingLogoURL: String? = null,
    val userID: String = "",
    /** "welcome" | "post" | "visit" */
    val kind: String = "welcome",
    val title: String = "",
    val smallPrint: String = "",
    /** "issued" | "active" | "used" | "expired" | "void" */
    val status: String = "issued",
    val issuedAt: Date? = null,
    val expiresAt: Date? = null,
    val usableFrom: Date? = null,
    val activatedAt: Date? = null,
    val activeUntil: Date? = null,
    val usedAt: Date? = null,
    val videoID: String? = null,
    /** Visit perks only: the visit number that earned it. */
    val milestone: Int? = null,
    /** "artist" on an artist Landing's perks; absent on a restaurant's. */
    val landingKind: String? = null
) {
    val isArtist: Boolean get() = landingKind == "artist"

    /** "Free taco". An artist's show reward can be an experience, so it stands alone. */
    val displayTitle: String
        get() = if (isArtist && kind == "visit") title.replaceFirstChar { it.uppercase() } else "Free $title"
    val isWelcome: Boolean get() = kind == "welcome"
    val isPost: Boolean get() = kind == "post"
    val isVisit: Boolean get() = kind == "visit"

    /** "Welcome perk" / "Moment perk" / "Visit 5 reward". */
    val kindLabel: String get() = when {
        isWelcome -> "Welcome perk"
        isVisit -> {
            val unit = if (isArtist) "Show" else "Visit"
            milestone?.let { "$unit $it reward" } ?: "$unit reward"
        }
        else -> "Moment perk"
    }

    /**
     * The status as of now, not as of the last server sweep. The expire job is
     * hourly, so an active ticket whose 15 minutes ran out is used, and an
     * issued perk past its date is expired, whatever the doc still says.
     */
    fun effectiveStatus(now: Date = Date()): String = when {
        status == "active" && activeUntil != null && !now.before(activeUntil) -> "used"
        status == "issued" && expiresAt != null && !now.before(expiresAt) -> "expired"
        else -> status
    }

    fun isActive(now: Date = Date()) = effectiveStatus(now) == "active"

    /** Issued and inside its window — Use now is allowed. */
    fun isUsable(now: Date = Date()) =
        effectiveStatus(now) == "issued" && !isBeforeUsableFrom(now)

    /** Issued but not yet usable: a next-visit post perk on the night it was earned. */
    fun isWaiting(now: Date = Date()) =
        effectiveStatus(now) == "issued" && isBeforeUsableFrom(now)

    /**
     * usableFrom is the server's clock and `now` is the phone's. A phone a few
     * seconds slow read a just-claimed perk as "not yet" and greyed out Use now,
     * with nothing to re-check it. The server still has the final say.
     */
    private fun isBeforeUsableFrom(now: Date) =
        usableFrom != null && now.time + CLOCK_SKEW_MS < usableFrom.time

    companion object {
        private const val CLOCK_SKEW_MS = 5 * 60 * 1000L

        fun from(raw: Any?, fallbackID: String? = null): Perk? {
            val m = raw as? Map<*, *> ?: return null
            val id = (m["perkID"] as? String)?.takeIf { it.isNotBlank() }
                ?: (m["id"] as? String)?.takeIf { it.isNotBlank() }
                ?: fallbackID ?: return null
            return Perk(
                perkID = id,
                landingID = m["landingID"] as? String ?: "",
                landingName = m["landingName"] as? String ?: "",
                landingLogoURL = (m["landingLogoURL"] as? String)?.takeIf { it.isNotBlank() },
                userID = m["userID"] as? String ?: "",
                kind = m["kind"] as? String ?: "welcome",
                title = m["title"] as? String ?: "",
                smallPrint = m["smallPrint"] as? String ?: "",
                status = m["status"] as? String ?: "issued",
                issuedAt = landingDate(m["issuedAt"]),
                expiresAt = landingDate(m["expiresAt"]),
                usableFrom = landingDate(m["usableFrom"]),
                activatedAt = landingDate(m["activatedAt"]),
                activeUntil = landingDate(m["activeUntil"]),
                usedAt = landingDate(m["usedAt"]),
                videoID = m["videoID"] as? String,
                milestone = (m["milestone"] as? Number)?.toInt(),
                landingKind = (m["landingKind"] as? String)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

/** One rung as landing_member reports it for the signed-in guest. */
data class MemberReward(val visits: Int, val title: String, val earned: Boolean)

/** landing_member: the guest's visit card at one Landing (addendum 2). */
data class LandingMember(
    val visitDays: Int = 0,
    val checkedInToday: Boolean = false,
    val nextReward: VisitReward? = null,
    val rewards: List<MemberReward> = emptyList(),
    /** Artist Landings: the stop the guest is checked in at right now. */
    val checkedInStopID: String? = null
) {
    companion object {
        fun from(raw: Map<*, *>): LandingMember = LandingMember(
            visitDays = (raw["visitDays"] as? Number)?.toInt() ?: 0,
            checkedInToday = raw["checkedInToday"] as? Boolean ?: false,
            nextReward = VisitReward.from(raw["nextReward"]),
            rewards = (raw["rewards"] as? List<*>).orEmpty().mapNotNull { r ->
                val m = r as? Map<*, *> ?: return@mapNotNull null
                val v = VisitReward.from(m) ?: return@mapNotNull null
                MemberReward(v.visits, v.title, m["earned"] as? Boolean ?: false)
            }.sortedBy { it.visits },
            checkedInStopID = (raw["checkedInStopID"] as? String)?.takeIf { it.isNotBlank() }
        )
    }
}

/** landing_checkIn's answer: the visit, plus the visit card's count (addendum 2). */
data class CheckInResult(
    val visitID: String,
    val visitDays: Int?,
    val newVisit: Boolean,
    val perkIssued: Perk?,
    /** Artist Landings: the show this check-in counted for. */
    val stopID: String? = null,
    val dayKey: String? = null
)

/** One row of landing_nearby. */
data class NearbyLanding(
    val landingID: String,
    val slug: String,
    val name: String,
    val logoURL: String?,
    val distanceM: Double,
    val inside: Boolean,
    /** "artist", or null for a restaurant. Artist rows are the stop open now. */
    val kind: String? = null,
    val stopID: String? = null,
    val venue: String? = null
) {
    val isArtist: Boolean get() = kind == "artist"

    companion object {
        fun from(raw: Any?): NearbyLanding? {
            val m = raw as? Map<*, *> ?: return null
            return NearbyLanding(
                landingID = m["landingID"] as? String ?: return null,
                slug = m["slug"] as? String ?: return null,
                name = m["name"] as? String ?: "",
                logoURL = (m["logoURL"] as? String)?.takeIf { it.isNotBlank() },
                distanceM = (m["distanceM"] as? Number)?.toDouble() ?: 0.0,
                inside = m["inside"] as? Boolean ?: false,
                kind = (m["kind"] as? String)?.takeIf { it.isNotBlank() },
                stopID = (m["stopID"] as? String)?.takeIf { it.isNotBlank() },
                venue = (m["venue"] as? String)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

/** One row of landing_search (addendum 1): a live Landing matched by name. */
data class LandingSearchResult(
    val landingID: String,
    val slug: String,
    val name: String,
    val neighborhood: String?,
    val logoURL: String?
) {
    companion object {
        fun from(raw: Any?): LandingSearchResult? {
            val m = raw as? Map<*, *> ?: return null
            return LandingSearchResult(
                landingID = m["landingID"] as? String ?: return null,
                slug = m["slug"] as? String ?: return null,
                name = m["name"] as? String ?: "",
                neighborhood = (m["neighborhood"] as? String)?.takeIf { it.isNotBlank() },
                logoURL = (m["logoURL"] as? String)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

/** landingStats counters, today or summed over the week. */
data class LandingStats(
    val scans: Int = 0,
    val signups: Int = 0,
    val welcomeClaims: Int = 0,
    val welcomeUsed: Int = 0,
    val checkIns: Int = 0,
    val posts: Int = 0,
    val postPerks: Int = 0,
    val postUsed: Int = 0,
    val returns: Int = 0
) {
    companion object {
        fun from(raw: Any?): LandingStats {
            val m = raw as? Map<*, *> ?: return LandingStats()
            fun n(k: String) = (m[k] as? Number)?.toInt() ?: 0
            return LandingStats(
                n("scans"), n("signups"), n("welcomeClaims"), n("welcomeUsed"),
                n("checkIns"), n("posts"), n("postPerks"), n("postUsed"), n("returns")
            )
        }
    }
}

/** landing_owner: everything the owner's Landing tab shows. */
data class LandingOwnerData(
    val landing: Landing?,
    val accessCode: String?,
    val screenCount: Int,
    val todayStats: LandingStats,
    val weekStats: LandingStats,
    /** Artist Landings: each stop's private access number, by stopID. */
    val stopAccessCodes: Map<String, String> = emptyMap()
)

/** One row of landing_pendingMoments — the approvals queue. */
data class PendingMoment(
    val videoID: String,
    val thumbnailURL: String?,
    val creatorName: String,
    val landingSpot: String?,
    val createdAt: Date?,
    /** "pending" | "approved" */
    val landingScreen: String
) {
    companion object {
        fun from(raw: Any?): PendingMoment? {
            val m = raw as? Map<*, *> ?: return null
            return PendingMoment(
                videoID = m["videoID"] as? String ?: return null,
                thumbnailURL = (m["thumbnailURL"] as? String)?.takeIf { it.isNotBlank() },
                creatorName = m["creatorName"] as? String ?: "",
                landingSpot = (m["landingSpot"] as? String)?.takeIf { it.isNotBlank() },
                createdAt = landingDate(m["createdAt"]),
                landingScreen = m["landingScreen"] as? String ?: "pending"
            )
        }
    }
}

/**
 * Timestamp (Firestore), Date, ISO string (callables), or the
 * `{_seconds, _nanoseconds}` map a callable produces when a raw Timestamp
 * slips through un-serialised.
 */
internal fun landingDate(raw: Any?): Date? = when (raw) {
    null -> null
    is Timestamp -> raw.toDate()
    is Date -> raw
    is String -> runCatching { Date.from(Instant.parse(raw)) }.getOrNull()
    is Number -> Date(raw.toLong())
    is Map<*, *> -> ((raw["_seconds"] ?: raw["seconds"]) as? Number)?.let { Date(it.toLong() * 1000) }
    else -> null
}
