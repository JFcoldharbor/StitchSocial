package com.stitchsocial.club.events

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Event posts v1 (iOS parity — shared Firestore backend, no server work).
 *
 * Doc schema on a thread-HEAD video:
 *   isEvent: Bool (top-level)
 *   eventStartAt: Timestamp (top-level, denormalized copy of event.startAt for querying)
 *   event: { name, startAt, endAt?, venueName, city, rsvpURL?, createdAt }
 *
 * Layer 1: Foundation — pure Kotlin data class (Firestore decode lives in
 * VideoService.convertFirebaseToVideoMetadata via [fromMap]).
 */
data class StitchEvent(
    val name: String,
    val startAt: Date,
    val endAt: Date? = null,
    val venueName: String = "",
    val city: String = "",
    val rsvpURL: String? = null,
    val createdAt: Date = Date()
) {
    /** Events without an explicit end run 3 hours from start. */
    val effectiveEnd: Date get() = endAt ?: Date(startAt.time + DEFAULT_DURATION_MS)

    val isUpcoming: Boolean get() = System.currentTimeMillis() < startAt.time

    /** start <= now < effectiveEnd */
    val isHappeningNow: Boolean
        get() {
            val now = System.currentTimeMillis()
            return now >= startAt.time && now < effectiveEnd.time
        }

    val hasEnded: Boolean get() = System.currentTimeMillis() >= effectiveEnd.time

    /** Milliseconds until start (negative once started). */
    val timeUntilStart: Long get() = startAt.time - System.currentTimeMillis()

    /** "Fri, Aug 14 · 8:00 PM" */
    val dateLine: String
        get() = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(startAt)

    /** "The Loft · Atlanta" (skips blank parts) */
    val whereLine: String
        get() = listOf(venueName, city).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        const val DEFAULT_DURATION_MS: Long = 3L * 60 * 60 * 1000  // 3h

        /**
         * Compact countdown text for the status chip: "2d 4h", "3h 12m", "9m", "45s".
         */
        fun countdownText(remainingMs: Long): String {
            if (remainingMs <= 0) return "now"
            val totalSeconds = remainingMs / 1000
            val days = totalSeconds / 86_400
            val hours = (totalSeconds % 86_400) / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return when {
                days > 0 -> "${days}d ${hours}h"
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m"
                else -> "${seconds}s"
            }
        }

        /**
         * Decode the nested `event` map off a video doc (Firestore Timestamps -> Date).
         * Returns null for non-event videos or malformed maps (name/startAt required).
         */
        fun fromMap(map: Map<*, *>?): StitchEvent? {
            if (map == null) return null
            val name = map["name"] as? String ?: return null
            val startAt = (map["startAt"] as? com.google.firebase.Timestamp)?.toDate() ?: return null
            return StitchEvent(
                name = name,
                startAt = startAt,
                endAt = (map["endAt"] as? com.google.firebase.Timestamp)?.toDate(),
                venueName = map["venueName"] as? String ?: "",
                city = map["city"] as? String ?: "",
                rsvpURL = (map["rsvpURL"] as? String)?.takeIf { it.isNotBlank() },
                createdAt = (map["createdAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()
            )
        }
    }
}

/**
 * Composer-side draft for attaching an event to a new thread head.
 * The ThreadComposer wires this up later — EventService.attachEvent(headVideoID, draft)
 * is the only client write.
 */
data class EventDraft(
    val name: String = "",
    val startAt: Date = defaultStart(),
    val endAt: Date? = null,
    val venueName: String = "",
    val city: String = "",
    val rsvpURL: String = ""
) {
    val missingFields: List<String>
        get() = buildList {
            if (name.isBlank()) add("event name")
            if (venueName.isBlank()) add("venue")
            if (city.isBlank()) add("city")
            if (startAt.time <= System.currentTimeMillis()) add("a future start time")
        }

    val isValid: Boolean get() = missingFields.isEmpty()

    companion object {
        /** Default start: tomorrow at 7:00 PM local. */
        fun defaultStart(): Date = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 19)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
    }
}
