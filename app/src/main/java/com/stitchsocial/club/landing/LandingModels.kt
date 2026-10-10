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
    /** "draft" | "live" | "paused" | "removed" */
    val status: String = "live",
    val businessID: String = "",
    val verified: Boolean = false
) {
    val isLive: Boolean get() = status == "live"
    val isCounter: Boolean get() = serviceStyle != "sit_down"

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
                status = m["status"] as? String ?: "live",
                businessID = m["businessID"] as? String ?: "",
                verified = m["verified"] as? Boolean ?: false
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
    /** "welcome" | "post" */
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
    val videoID: String? = null
) {
    val isWelcome: Boolean get() = kind == "welcome"

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
        effectiveStatus(now) == "issued" && (usableFrom == null || !now.before(usableFrom))

    /** Issued but not yet usable: a next-visit post perk on the night it was earned. */
    fun isWaiting(now: Date = Date()) =
        effectiveStatus(now) == "issued" && usableFrom != null && now.before(usableFrom)

    companion object {
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
                videoID = m["videoID"] as? String
            )
        }
    }
}

/** One row of landing_nearby. */
data class NearbyLanding(
    val landingID: String,
    val slug: String,
    val name: String,
    val logoURL: String?,
    val distanceM: Double,
    val inside: Boolean
) {
    companion object {
        fun from(raw: Any?): NearbyLanding? {
            val m = raw as? Map<*, *> ?: return null
            return NearbyLanding(
                landingID = m["landingID"] as? String ?: return null,
                slug = m["slug"] as? String ?: return null,
                name = m["name"] as? String ?: "",
                logoURL = (m["logoURL"] as? String)?.takeIf { it.isNotBlank() },
                distanceM = (m["distanceM"] as? Number)?.toDouble() ?: 0.0,
                inside = m["inside"] as? Boolean ?: false
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
    val weekStats: LandingStats
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
