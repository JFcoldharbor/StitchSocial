package com.stitchsocial.club.events

import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Layer 1: Models — the first-class Event (Concept B rebuild, iOS parity with
 * ios/Events/EventEntity.swift).
 *
 * An Event is a NOUN: a live multi-POV thread on a clock rail, not the v1
 * "adjective on one video" ([StitchEvent]/[EventDraft], now the promo layer).
 * This entity lives at `events/{eventId}` plus its attendees/agenda/timeline/
 * giveaways subcollections.
 *
 * Pure Kotlin — Firestore decode via [fromMap]/companion helpers; write maps are
 * built in EventService (matches the CommunityService convention).
 */

// MARK: - Enums

/** How the event goes live. `auto` = a pre-recorded opener publishes at doors; `manual` = host taps Go Live. */
enum class EventStartMode(val raw: String) {
    AUTO("auto"),
    MANUAL("manual");

    companion object {
        fun fromRaw(v: String?): EventStartMode = entries.firstOrNull { it.raw == v } ?: MANUAL
    }
}

/** Derived lifecycle — never stored, always computed from the clock + opener. */
enum class EventLifecycle { UPCOMING, LIVE, ENDED }

/** RSVP intent recorded on the attendee doc. */
enum class EventRSVPStatus(val raw: String) {
    GOING("going"),
    NOT_INTERESTED("notInterested");

    companion object {
        fun fromRaw(v: String?): EventRSVPStatus = entries.firstOrNull { it.raw == v } ?: NOT_INTERESTED
    }
}

/** How giveaway entries are counted from the geofence-verified attendee pool. */
enum class GiveawayEntryRule(val raw: String, val displayName: String) {
    ATTENDED("attended", "Everyone at the venue"),
    POSTED("posted", "Anyone who posted a POV"),
    WEIGHTED("weighted", "More POVs = more chances");

    companion object {
        fun fromRaw(v: String?): GiveawayEntryRule = entries.firstOrNull { it.raw == v } ?: ATTENDED
    }
}

enum class GiveawayState(val raw: String) {
    OPEN("open"),
    DRAWN("drawn");

    companion object {
        fun fromRaw(v: String?): GiveawayState = entries.firstOrNull { it.raw == v } ?: OPEN
    }
}

/** Kind of a timeline moment on the clock rail. */
enum class EventMomentKind(val raw: String) {
    OPENER("opener"),
    BEAT("beat"),
    HOST_VIDEO("hostVideo");

    companion object {
        fun fromRaw(v: String?): EventMomentKind = entries.firstOrNull { it.raw == v } ?: HOST_VIDEO
    }
}

// MARK: - Event (events/{eventId})

data class StitchEventEntity(
    val id: String,
    val hostUserID: String,
    val hostUsername: String,
    val name: String,
    // Place — coordinates are REQUIRED for the geofence (v1 only had strings).
    val venueName: String,
    val city: String,
    val venueLat: Double,
    val venueLng: Double,
    val geofenceRadiusMeters: Double,
    // Clock — the rail runs doors -> close.
    val doorsAt: Date,
    val closeAt: Date,
    val startMode: EventStartMode,
    val promoVideoID: String? = null,
    val openerVideoID: String? = null,
    val recapVideoID: String? = null,
    // Denormalized media for the hero card. iOS already writes all three
    // (EventService.swift) — Android just never decoded them, which is why the
    // events list had no cover art to show.
    val promoThumbnailURL: String? = null,  // promo thumb for the hero still
    val promoVideoURL: String? = null,      // promo playback URL (card autoplay)
    val coverImageURL: String? = null,      // chosen cover photo, preferred over promo
    val attendeeAnchorsAllowed: Boolean = false,
    val goingCount: Int = 0,
    val postCount: Int = 0,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
) {
    /**
     * Live only once the opener exists AND we're inside the window — a passed
     * door time with no opener stays UPCOMING (no empty live rooms).
     */
    val lifecycle: EventLifecycle
        get() {
            val now = System.currentTimeMillis()
            return when {
                now >= closeAt.time -> EventLifecycle.ENDED
                openerVideoID != null && now >= doorsAt.time -> EventLifecycle.LIVE
                else -> EventLifecycle.UPCOMING
            }
        }

    val isLive: Boolean get() = lifecycle == EventLifecycle.LIVE
    val isUpcoming: Boolean get() = lifecycle == EventLifecycle.UPCOMING
    val hasEnded: Boolean get() = lifecycle == EventLifecycle.ENDED

    /** 0..1 position on the doors->close clock rail (the signature UI dot). */
    val clockProgress: Double
        get() {
            val total = (closeAt.time - doorsAt.time).toDouble()
            if (total <= 0) return 0.0
            val elapsed = (System.currentTimeMillis() - doorsAt.time).toDouble()
            return (elapsed / total).coerceIn(0.0, 1.0)
        }

    /** "Sat · 7:00 PM" */
    val whenLine: String
        get() = SimpleDateFormat("EEE · h:mm a", Locale.getDefault()).format(doorsAt)

    /** "Ponce City Market · Atlanta" */
    val whereLine: String
        get() = listOf(venueName, city).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * Presence test (Phase 3). Subtracts the fix's own error so a real attendee
     * indoors (GPS ±20–65m) isn't falsely blocked.
     */
    fun isWithinGeofence(lat: Double, lng: Double, horizontalAccuracy: Float, applyAccuracyBuffer: Boolean = true): Boolean {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat, lng, venueLat, venueLng, results)
        val distance = results[0]
        val effective = if (applyAccuracyBuffer) distance - maxOf(0f, horizontalAccuracy) else distance
        return effective <= geofenceRadiusMeters
    }

    companion object {
        fun fromMap(id: String, map: Map<String, Any?>?): StitchEventEntity? {
            if (map == null) return null
            val doors = (map["doorsAt"] as? Timestamp)?.toDate() ?: return null
            val close = (map["closeAt"] as? Timestamp)?.toDate() ?: return null
            return StitchEventEntity(
                id = id,
                hostUserID = map["hostUserID"] as? String ?: return null,
                hostUsername = map["hostUsername"] as? String ?: "",
                name = map["name"] as? String ?: "",
                venueName = map["venueName"] as? String ?: "",
                city = map["city"] as? String ?: "",
                venueLat = (map["venueLat"] as? Number)?.toDouble() ?: 0.0,
                venueLng = (map["venueLng"] as? Number)?.toDouble() ?: 0.0,
                geofenceRadiusMeters = (map["geofenceRadiusMeters"] as? Number)?.toDouble() ?: 150.0,
                doorsAt = doors,
                closeAt = close,
                startMode = EventStartMode.fromRaw(map["startMode"] as? String),
                promoVideoID = (map["promoVideoID"] as? String)?.takeIf { it.isNotBlank() },
                openerVideoID = (map["openerVideoID"] as? String)?.takeIf { it.isNotBlank() },
                recapVideoID = (map["recapVideoID"] as? String)?.takeIf { it.isNotBlank() },
                promoThumbnailURL = (map["promoThumbnailURL"] as? String)?.takeIf { it.isNotBlank() },
                promoVideoURL = (map["promoVideoURL"] as? String)?.takeIf { it.isNotBlank() },
                coverImageURL = (map["coverImageURL"] as? String)?.takeIf { it.isNotBlank() },
                attendeeAnchorsAllowed = map["attendeeAnchorsAllowed"] as? Boolean ?: false,
                goingCount = (map["goingCount"] as? Number)?.toInt() ?: 0,
                postCount = (map["postCount"] as? Number)?.toInt() ?: 0,
                createdAt = (map["createdAt"] as? Timestamp)?.toDate() ?: Date(),
                updatedAt = (map["updatedAt"] as? Timestamp)?.toDate() ?: Date()
            )
        }
    }
}

// MARK: - Attendee (events/{eventId}/attendees/{userId})

data class EventAttendee(
    val id: String,                 // == userID
    val userID: String,
    val username: String,
    val status: EventRSVPStatus,
    val onsite: Boolean = false,
    val verifiedOnsite: Boolean = false,
    val povCount: Int? = null,
    val lastSeenAt: Date = Date(),
    val joinedAt: Date = Date()
) {
    val isGoing: Boolean get() = status == EventRSVPStatus.GOING

    companion object {
        fun fromMap(id: String, map: Map<String, Any?>?): EventAttendee? {
            if (map == null) return null
            return EventAttendee(
                id = id,
                userID = map["userID"] as? String ?: id,
                username = map["username"] as? String ?: "",
                status = EventRSVPStatus.fromRaw(map["status"] as? String),
                onsite = map["onsite"] as? Boolean ?: false,
                verifiedOnsite = map["verifiedOnsite"] as? Boolean ?: false,
                povCount = (map["povCount"] as? Number)?.toInt(),
                lastSeenAt = (map["lastSeenAt"] as? Timestamp)?.toDate() ?: Date(),
                joinedAt = (map["joinedAt"] as? Timestamp)?.toDate() ?: Date()
            )
        }
    }
}

// MARK: - Giveaway (events/{eventId}/giveaways/{giveawayId})

data class EventGiveaway(
    val id: String,
    val eventID: String,
    val prize: String,
    val winnerCount: Int,
    val entryRule: GiveawayEntryRule,
    val state: GiveawayState,
    val drawSeed: String? = null,
    val winnerUserIDs: List<String>? = null,
    val winnerUsernames: List<String>? = null,
    val createdAt: Date = Date()
) {
    val isDrawn: Boolean get() = state == GiveawayState.DRAWN

    companion object {
        fun fromMap(id: String, eventID: String, map: Map<String, Any?>?): EventGiveaway? {
            if (map == null) return null
            @Suppress("UNCHECKED_CAST")
            return EventGiveaway(
                id = id,
                eventID = eventID,
                prize = map["prize"] as? String ?: "",
                winnerCount = (map["winnerCount"] as? Number)?.toInt() ?: 1,
                entryRule = GiveawayEntryRule.fromRaw(map["entryRule"] as? String),
                state = GiveawayState.fromRaw(map["state"] as? String),
                drawSeed = (map["drawSeed"] as? String)?.takeIf { it.isNotBlank() },
                winnerUserIDs = map["winnerUserIDs"] as? List<String>,
                winnerUsernames = map["winnerUsernames"] as? List<String>,
                createdAt = (map["createdAt"] as? Timestamp)?.toDate() ?: Date()
            )
        }
    }
}

/** A giveaway the host lines up while creating the event (prior to it starting). */
data class GiveawayDraft(
    val id: String = UUID.randomUUID().toString(),
    val prize: String,
    val winnerCount: Int = 1,
    val entryRule: GiveawayEntryRule = GiveawayEntryRule.ATTENDED
)

// MARK: - Agenda item (events/{eventId}/agenda/{itemId})

data class EventAgendaItem(
    val id: String,
    val eventID: String,
    val scheduledTime: Date,
    val title: String,
    val momentVideoID: String? = null,
    val momentThreadID: String? = null,
    val thumbnailURL: String? = null,
    val caption: String? = null,
    val durationSeconds: Double? = null,
    val povCount: Int? = null,
    val createdAt: Date = Date()
) {
    val isFilled: Boolean get() = momentVideoID != null

    val timeLabel: String
        get() = SimpleDateFormat("h:mm a", Locale.getDefault()).format(scheduledTime)

    val durationLabel: String?
        get() {
            val s = durationSeconds ?: return null
            if (s <= 0) return null
            val m = s.toInt() / 60
            val sec = s.toInt() % 60
            return String.format(Locale.US, "%d:%02d", m, sec)
        }

    companion object {
        fun fromMap(id: String, eventID: String, map: Map<String, Any?>?): EventAgendaItem? {
            if (map == null) return null
            val scheduled = (map["scheduledTime"] as? Timestamp)?.toDate() ?: return null
            return EventAgendaItem(
                id = id,
                eventID = eventID,
                scheduledTime = scheduled,
                title = map["title"] as? String ?: "",
                momentVideoID = (map["momentVideoID"] as? String)?.takeIf { it.isNotBlank() },
                momentThreadID = (map["momentThreadID"] as? String)?.takeIf { it.isNotBlank() },
                thumbnailURL = (map["thumbnailURL"] as? String)?.takeIf { it.isNotBlank() },
                caption = (map["caption"] as? String)?.takeIf { it.isNotBlank() },
                durationSeconds = (map["durationSeconds"] as? Number)?.toDouble(),
                povCount = (map["povCount"] as? Number)?.toInt(),
                createdAt = (map["createdAt"] as? Timestamp)?.toDate() ?: Date()
            )
        }
    }
}

/** Sorted by time. */
fun List<EventAgendaItem>.byTime(): List<EventAgendaItem> = sortedBy { it.scheduledTime }

/**
 * Time-based "now" slot: the latest item whose scheduled time has arrived.
 * Used for the agenda's schedule marker and for finding where to record next
 * (recordableSlot) — NOT for live/lock state (see [liveMomentItem]).
 */
fun List<EventAgendaItem>.liveItem(now: Date = Date()): EventAgendaItem? =
    byTime().lastOrNull { it.scheduledTime <= now }

/**
 * The LIVE moment = the newest FILLED slot (the most recent moment actually
 * posted). This — not [liveItem] — drives what's stitchable, the live hero, and
 * the lock state. An empty slot the host just created by tapping Go Live isn't
 * filled, so it can't steal liveness or prematurely lock the previous moment;
 * only recording a new moment in full replaces the live one (and locks the prior).
 */
fun List<EventAgendaItem>.liveMomentItem(): EventAgendaItem? =
    filter { it.isFilled }.byTime().lastOrNull()

// MARK: - Creation draft (collected by the create flow)

/**
 * Minimal config to spin up an Event. Unlike v1 [EventDraft] (strings only), this
 * carries the venue COORDINATES so a geofence can exist.
 */
data class EventCreateDraft(
    var name: String = "",
    var venueName: String = "",
    var city: String = "",
    var venueLat: Double = 0.0,
    var venueLng: Double = 0.0,
    var hasVenuePin: Boolean = false,
    var doorsAt: Date = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 24) }.time,
    var durationHours: Double = 3.0,
    var geofenceRadiusMeters: Double = 150.0,
    var startMode: EventStartMode = EventStartMode.MANUAL
) {
    val closeAt: Date get() = Date(doorsAt.time + (durationHours * 3600 * 1000).toLong())

    val isValid: Boolean
        get() = name.isNotBlank() && venueName.isNotBlank() && city.isNotBlank() &&
            hasVenuePin && doorsAt.time > System.currentTimeMillis()

    val missingFields: List<String>
        get() = buildList {
            if (name.isBlank()) add("event name")
            if (venueName.isBlank()) add("venue")
            if (city.isBlank()) add("city")
            if (!hasVenuePin) add("map pin (for the geofence)")
            if (doorsAt.time <= System.currentTimeMillis()) add("future start")
        }

    companion object {
        /** Prefill from an existing event (edit flow). */
        fun fromEntity(e: StitchEventEntity): EventCreateDraft = EventCreateDraft(
            name = e.name,
            venueName = e.venueName,
            city = e.city,
            venueLat = e.venueLat,
            venueLng = e.venueLng,
            hasVenuePin = true,
            doorsAt = e.doorsAt,
            durationHours = ((e.closeAt.time - e.doorsAt.time) / 3_600_000.0).coerceIn(1.0, 12.0),
            geofenceRadiusMeters = e.geofenceRadiusMeters.coerceIn(100.0, 300.0),
            startMode = e.startMode
        )
    }
}
