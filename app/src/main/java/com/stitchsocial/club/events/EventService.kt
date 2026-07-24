package com.stitchsocial.club.events

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.VideoServiceImpl
import kotlinx.coroutines.tasks.await
import java.util.Date
import kotlin.random.Random

/**
 * Event posts v1 — client-only feature on the shared Firestore backend (iOS parity).
 *
 * attachEvent is the ONLY write: it stamps the thread-HEAD video with
 * isEvent + denormalized eventStartAt (queryable) + the nested event map.
 * The upcoming-events query is single-field (whereGreaterThan + orderBy on
 * eventStartAt) so it needs NO composite index; the "not ended / playable"
 * cut happens client-side.
 */
object EventService {

    private val db by lazy { FirebaseFirestore.getInstance("stitchfin") }
    private val videoService by lazy { VideoServiceImpl() }

    /** Attach an event to a freshly-created thread-head video (composer wires this later). */
    suspend fun attachEvent(headVideoID: String, draft: EventDraft) {
        val eventMap = mutableMapOf<String, Any>(
            "name" to draft.name.trim(),
            "startAt" to Timestamp(draft.startAt),
            "venueName" to draft.venueName.trim(),
            "city" to draft.city.trim(),
            "createdAt" to Timestamp(Date())
        )
        draft.endAt?.let { eventMap["endAt"] = Timestamp(it) }
        if (draft.rsvpURL.isNotBlank()) eventMap["rsvpURL"] = draft.rsvpURL.trim()

        db.collection("videos").document(headVideoID)
            .update(
                mapOf(
                    "isEvent" to true,
                    "eventStartAt" to Timestamp(draft.startAt),  // denormalized for the feed query
                    "event" to eventMap
                )
            )
            .await()

        if (BuildConfig.DEBUG) {
            println("📅 EVENT: attached to $headVideoID (${draft.name.trim()} @ ${draft.venueName.trim()}, ${draft.city.trim()})")
        }
    }

    /**
     * Upcoming + live event heads, soonest first.
     * Query window starts 3h back so no-endAt events stay visible while live;
     * client filter drops anything actually ended or without a playable URL.
     */
    suspend fun getUpcomingEvents(limit: Long = 40): List<CoreVideoMetadata> {
        return try {
            val windowStart = Timestamp(Date(System.currentTimeMillis() - StitchEvent.DEFAULT_DURATION_MS))
            val snapshot = db.collection("videos")
                .whereGreaterThan("eventStartAt", windowStart)
                .orderBy("eventStartAt", Query.Direction.ASCENDING)
                .limit(limit)
                .get()
                .await()

            val events = videoService.convertFirebaseToVideoMetadata(snapshot.documents)
                .filter { video ->
                    val ev = video.event ?: return@filter false
                    !ev.hasEnded && video.videoURL.isNotBlank() && !video.isDeleted
                }

            if (BuildConfig.DEBUG) {
                println("📅 EVENT: ${events.size} upcoming/live event(s) (${snapshot.size()} docs in window)")
            }
            events
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("📅 EVENT: upcoming query failed — ${e.message}") }
            emptyList()
        }
    }

    // ============================================================
    //  CONCEPT B — first-class events (events/{id} + subcollections)
    //  iOS parity: ios/Events/EventService.swift
    // ============================================================

    private object Col {
        const val EVENTS = "events"
        const val ATTENDEES = "attendees"
        const val AGENDA = "agenda"
        const val GIVEAWAYS = "giveaways"
        const val VIDEOS = "videos"
    }

    private fun eventsRef() = db.collection(Col.EVENTS)
    private fun attendeesRef(eventID: String) = eventsRef().document(eventID).collection(Col.ATTENDEES)
    private fun agendaRef(eventID: String) = eventsRef().document(eventID).collection(Col.AGENDA)
    private fun giveawaysRef(eventID: String) = eventsRef().document(eventID).collection(Col.GIVEAWAYS)

    // --- CRUD ---

    suspend fun createEvent(draft: EventCreateDraft, hostUserID: String, hostUsername: String): StitchEventEntity {
        if (hostUserID.isBlank()) throw EventException("You need to be signed in to host an event")
        if (!draft.isValid) throw EventException("Add your " + draft.missingFields.joinToString(", "))
        val ref = eventsRef().document()
        val now = Date()
        val event = StitchEventEntity(
            id = ref.id, hostUserID = hostUserID, hostUsername = hostUsername,
            name = draft.name.trim(), venueName = draft.venueName.trim(), city = draft.city.trim(),
            venueLat = draft.venueLat, venueLng = draft.venueLng,
            geofenceRadiusMeters = draft.geofenceRadiusMeters,
            doorsAt = draft.doorsAt, closeAt = draft.closeAt, startMode = draft.startMode,
            createdAt = now, updatedAt = now
        )
        ref.set(event.toFirestoreMap()).await()
        if (BuildConfig.DEBUG) println("📅 EVENT: created ${ref.id} — ${event.name} @ ${event.venueName}")
        return event
    }

    /** Host-only edit of the core details. Relaxes the future-start rule (may tidy a live/past event). */
    suspend fun updateEvent(eventID: String, draft: EventCreateDraft, hostUserID: String) {
        if (hostUserID.isBlank()) throw EventException("You need to be signed in to host an event")
        if (eventID.isBlank()) throw EventException("Missing event or account details")
        val name = draft.name.trim(); val venue = draft.venueName.trim(); val city = draft.city.trim()
        if (name.isBlank() || venue.isBlank() || city.isBlank() || !draft.hasVenuePin)
            throw EventException("Add your " + draft.missingFields.joinToString(", "))
        eventsRef().document(eventID).update(
            mapOf(
                "name" to name, "venueName" to venue, "city" to city,
                "venueLat" to draft.venueLat, "venueLng" to draft.venueLng,
                "geofenceRadiusMeters" to draft.geofenceRadiusMeters,
                "doorsAt" to Timestamp(draft.doorsAt), "closeAt" to Timestamp(draft.closeAt),
                "startMode" to draft.startMode.raw, "updatedAt" to Timestamp(Date())
            )
        ).await()
    }

    /** Host-only delete of the event doc. onEventDeleted CF recursively cleans subcollections. */
    suspend fun deleteEvent(eventID: String, hostUserID: String) {
        if (hostUserID.isBlank()) throw EventException("You need to be signed in to host an event")
        if (eventID.isBlank()) throw EventException("Missing event or account details")
        eventsRef().document(eventID).delete().await()
    }

    suspend fun fetchEvent(id: String): StitchEventEntity? {
        if (id.isBlank()) return null
        return try {
            val doc = eventsRef().document(id).get().await()
            StitchEventEntity.fromMap(doc.id, doc.data)
        } catch (e: Exception) { null }
    }

    /** Non-ended events (live + upcoming), soonest-doors first. Single-field query, no composite index. */
    suspend fun fetchEventRows(limit: Long = 60): List<StitchEventEntity> {
        val snap = eventsRef()
            .whereGreaterThan("closeAt", Timestamp(Date()))
            .orderBy("closeAt")
            .limit(limit)
            .get().await()
        return snap.documents.mapNotNull { StitchEventEntity.fromMap(it.id, it.data) }
            .sortedBy { it.doorsAt }
    }

    /** Events the caller hosts (all states) — for "My Events". */
    suspend fun fetchMyEvents(hostUserID: String, limit: Long = 50): List<StitchEventEntity> {
        if (hostUserID.isBlank()) return emptyList()
        val snap = eventsRef().whereEqualTo("hostUserID", hostUserID).limit(limit).get().await()
        return snap.documents.mapNotNull { StitchEventEntity.fromMap(it.id, it.data) }
            .sortedByDescending { it.doorsAt }
    }

    // --- RSVP ---

    suspend fun fetchMyRSVP(eventID: String, userID: String): EventAttendee? {
        if (eventID.isBlank() || userID.isBlank()) return null
        return try {
            val doc = attendeesRef(eventID).document(userID).get().await()
            EventAttendee.fromMap(doc.id, doc.data)
        } catch (e: Exception) { null }
    }

    /** Set Going / Not Interested; transactionally keeps goingCount in sync with the transition. */
    suspend fun setRSVP(eventID: String, status: EventRSVPStatus, userID: String, username: String) {
        if (eventID.isBlank() || userID.isBlank()) throw EventException("Missing event or account details")
        val eventRef = eventsRef().document(eventID)
        val attendeeRef = attendeesRef(eventID).document(userID)
        db.runTransaction { txn ->
            val existing = EventAttendee.fromMap(userID, txn.get(attendeeRef).data)
            val wasGoing = existing?.isGoing ?: false
            val willGo = status == EventRSVPStatus.GOING
            val delta = (if (willGo) 1 else 0) - (if (wasGoing) 1 else 0)
            txn.set(
                attendeeRef,
                mapOf(
                    "userID" to userID, "username" to username, "status" to status.raw,
                    "onsite" to (existing?.onsite ?: false),
                    "verifiedOnsite" to (existing?.verifiedOnsite ?: false),
                    "povCount" to existing?.povCount,
                    "lastSeenAt" to Timestamp(Date()),
                    "joinedAt" to Timestamp(existing?.joinedAt ?: Date())
                )
            )
            if (delta != 0) {
                txn.update(
                    eventRef,
                    mapOf(
                        "goingCount" to FieldValue.increment(delta.toLong()),
                        "updatedAt" to Timestamp(Date())
                    )
                )
            }
            null
        }.await()
    }

    /** Clear the caller's RSVP entirely (un-Going): delete the attendee doc + decrement goingCount. */
    suspend fun clearRSVP(eventID: String, userID: String) {
        if (eventID.isBlank() || userID.isBlank()) throw EventException("Missing event or account details")
        val eventRef = eventsRef().document(eventID)
        val attendeeRef = attendeesRef(eventID).document(userID)
        db.runTransaction { txn ->
            val existing = EventAttendee.fromMap(userID, txn.get(attendeeRef).data)
            if (existing != null) {
                txn.delete(attendeeRef)
                if (existing.isGoing) {
                    txn.update(
                        eventRef,
                        mapOf(
                            "goingCount" to FieldValue.increment(-1L),
                            "updatedAt" to Timestamp(Date())
                        )
                    )
                }
            }
            null
        }.await()
    }

    /** Record whether the caller is inside the venue geofence (their own attendee doc). */
    suspend fun setOnsite(eventID: String, userID: String, onsite: Boolean) {
        if (eventID.isBlank() || userID.isBlank()) throw EventException("Missing event or account details")
        attendeesRef(eventID).document(userID).set(
            mapOf("onsite" to onsite, "lastSeenAt" to Timestamp(Date())),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()
    }

    // --- Agenda (each slot is a host thread) ---

    suspend fun fetchAgenda(eventID: String): List<EventAgendaItem> {
        if (eventID.isBlank()) return emptyList()
        val snap = agendaRef(eventID).orderBy("scheduledTime").get().await()
        return snap.documents.mapNotNull { EventAgendaItem.fromMap(it.id, eventID, it.data) }
    }

    suspend fun addAgendaItem(eventID: String, title: String, scheduledTime: Date, hostUserID: String): EventAgendaItem {
        if (eventID.isBlank() || hostUserID.isBlank()) throw EventException("Missing event or account details")
        val trimmed = title.trim()
        if (trimmed.isBlank()) throw EventException("Add your agenda item title")
        val ref = agendaRef(eventID).document()
        ref.set(
            mapOf("scheduledTime" to Timestamp(scheduledTime), "title" to trimmed, "createdAt" to Timestamp(Date()))
        ).await()
        return EventAgendaItem(id = ref.id, eventID = eventID, scheduledTime = scheduledTime, title = trimmed, createdAt = Date())
    }

    suspend fun deleteAgendaItem(eventID: String, itemID: String) {
        if (eventID.isBlank() || itemID.isBlank()) throw EventException("Missing event or account details")
        agendaRef(eventID).document(itemID).delete().await()
    }

    /** Fill an agenda slot with a recorded video → live host thread. First fill sets openerVideoID (flips live). */
    suspend fun fillAgendaSlot(eventID: String, agendaItemID: String, video: CoreVideoMetadata, hostUserID: String) {
        if (eventID.isBlank() || agendaItemID.isBlank() || video.id.isBlank()) throw EventException("Missing event or account details")
        val eventRef = eventsRef().document(eventID)
        val itemRef = agendaRef(eventID).document(agendaItemID)
        val videoRef = db.collection(Col.VIDEOS).document(video.id)
        val threadID = video.threadID ?: video.id
        db.runTransaction { txn ->
            val hasOpener = txn.get(eventRef).getString("openerVideoID")?.isNotBlank() == true
            txn.update(
                itemRef,
                mapOf(
                    "momentVideoID" to video.id, "momentThreadID" to threadID,
                    "thumbnailURL" to video.thumbnailURL, "caption" to video.title,
                    "durationSeconds" to video.duration
                )
            )
            txn.update(videoRef, mapOf("eventId" to eventID))
            val eventUpdate = mutableMapOf<String, Any>(
                "postCount" to FieldValue.increment(1L),
                "updatedAt" to Timestamp(Date())
            )
            if (!hasOpener) eventUpdate["openerVideoID"] = video.id
            txn.update(eventRef, eventUpdate)
            null
        }.await()
    }

    /** Attach an on-site POV stitch to the slot it hangs off: tag the video + bump slot/attendee povCount. */
    suspend fun attachPOV(eventID: String, agendaItemID: String, videoID: String, posterUserID: String) {
        if (eventID.isBlank() || agendaItemID.isBlank() || videoID.isBlank()) throw EventException("Missing event or account details")
        val batch = db.batch()
        val videoRef = db.collection(Col.VIDEOS).document(videoID)
        batch.update(videoRef, mapOf("eventId" to eventID, "anchorMomentId" to agendaItemID))
        batch.update(agendaRef(eventID).document(agendaItemID), mapOf("povCount" to FieldValue.increment(1L)))
        if (posterUserID.isNotBlank()) {
            // set(merge), NOT update: the attendee doc may not exist yet (Join&stitch
            // races the RSVP write, or the guest never RSVP'd through the normal
            // path). `update` would throw NOT_FOUND and, because the batch is atomic,
            // sink the video-tag + povCount too — the POV would silently never attach.
            batch.set(
                attendeesRef(eventID).document(posterUserID),
                mapOf("povCount" to FieldValue.increment(1L), "verifiedOnsite" to true),
                com.google.firebase.firestore.SetOptions.merge()
            )
        }
        batch.commit().await()
    }

    /** Set the closing Recap video — the event's public, discoverable artifact. */
    suspend fun setRecap(eventID: String, video: CoreVideoMetadata) {
        if (eventID.isBlank() || video.id.isBlank()) throw EventException("Missing event or account details")
        val batch = db.batch()
        batch.update(db.collection(Col.VIDEOS).document(video.id), mapOf("eventId" to eventID, "isEventRecap" to true))
        batch.update(eventsRef().document(eventID), mapOf("recapVideoID" to video.id, "updatedAt" to Timestamp(Date())))
        batch.commit().await()
    }

    /** Set the pre-event promo/teaser — denormalizes the event's display info onto the video for the Discovery skin. */
    suspend fun setPromo(eventID: String, video: CoreVideoMetadata) {
        if (eventID.isBlank() || video.id.isBlank()) throw EventException("Missing event or account details")
        val event = fetchEvent(eventID)
        val batch = db.batch()
        val videoUpdate = mutableMapOf<String, Any>("eventId" to eventID, "isEventPromo" to true)
        if (event != null) {
            videoUpdate["isEvent"] = true
            videoUpdate["eventStartAt"] = Timestamp(event.doorsAt)
            videoUpdate["event"] = mapOf(
                "name" to event.name,
                "startAt" to Timestamp(event.doorsAt),
                "endAt" to Timestamp(event.closeAt),
                "venueName" to event.venueName,
                "city" to event.city,
                "createdAt" to Timestamp(Date())
            )
        }
        batch.update(db.collection(Col.VIDEOS).document(video.id), videoUpdate)
        batch.update(eventsRef().document(eventID), mapOf("promoVideoID" to video.id, "updatedAt" to Timestamp(Date())))
        batch.commit().await()
    }

    /**
     * Host "Make live again": bump a moment's scheduledTime to now so it becomes
     * the newest filled slot → the live moment. Recovers an accidental lock.
     */
    suspend fun reopenMoment(eventID: String, agendaItemID: String) {
        if (eventID.isBlank() || agendaItemID.isBlank()) throw EventException("Missing event or account details")
        agendaRef(eventID).document(agendaItemID)
            .update("scheduledTime", Timestamp(Date()))
            .await()
    }

    /** Resolve a single video doc — used to hydrate the promo/recap teaser for display. */
    suspend fun fetchVideo(id: String): CoreVideoMetadata? {
        if (id.isBlank()) return null
        return runCatching { videoService.getVideoById(id) }.getOrNull()
    }

    // --- Giveaways (event-level; entries = the verified-onsite attendee pool) ---

    suspend fun fetchGiveaways(eventID: String): List<EventGiveaway> {
        if (eventID.isBlank()) return emptyList()
        val snap = giveawaysRef(eventID).orderBy("createdAt").get().await()
        return snap.documents.mapNotNull { EventGiveaway.fromMap(it.id, eventID, it.data) }
    }

    suspend fun addGiveaway(eventID: String, prize: String, winnerCount: Int, entryRule: GiveawayEntryRule, hostUserID: String): EventGiveaway {
        if (eventID.isBlank() || hostUserID.isBlank()) throw EventException("Missing event or account details")
        val trimmed = prize.trim()
        if (trimmed.isBlank()) throw EventException("Add your prize")
        val ref = giveawaysRef(eventID).document()
        ref.set(
            mapOf(
                "prize" to trimmed, "winnerCount" to maxOf(1, winnerCount),
                "entryRule" to entryRule.raw, "state" to GiveawayState.OPEN.raw,
                "createdAt" to Timestamp(Date())
            )
        ).await()
        return EventGiveaway(
            id = ref.id, eventID = eventID, prize = trimmed, winnerCount = maxOf(1, winnerCount),
            entryRule = entryRule, state = GiveawayState.OPEN, createdAt = Date()
        )
    }

    suspend fun deleteGiveaway(eventID: String, giveawayID: String) {
        if (eventID.isBlank() || giveawayID.isBlank()) throw EventException("Missing event or account details")
        giveawaysRef(eventID).document(giveawayID).delete().await()
    }

    /** Draw winners from the qualified attendee pool per the entry rule. Seeded (recorded) random. */
    suspend fun drawGiveaway(eventID: String, giveaway: EventGiveaway, seed: String): EventGiveaway {
        if (eventID.isBlank() || giveaway.id.isBlank()) throw EventException("Missing event or account details")
        val snap = attendeesRef(eventID).whereEqualTo("status", EventRSVPStatus.GOING.raw).get().await()
        val attendees = snap.documents.mapNotNull { EventAttendee.fromMap(it.id, it.data) }

        val tickets = mutableListOf<Pair<String, String>>()
        for (a in attendees) {
            val pov = a.povCount ?: 0
            when (giveaway.entryRule) {
                GiveawayEntryRule.ATTENDED -> if (a.onsite || a.verifiedOnsite) tickets.add(a.userID to a.username)
                GiveawayEntryRule.POSTED -> if (pov >= 1) tickets.add(a.userID to a.username)
                GiveawayEntryRule.WEIGHTED -> repeat(pov) { tickets.add(a.userID to a.username) }
            }
        }

        val shuffled = tickets.shuffled(Random(seedToLong(seed)))
        val winners = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        for (t in shuffled) {
            if (t.first in seen) continue
            seen.add(t.first); winners.add(t)
            if (winners.size >= giveaway.winnerCount) break
        }

        val updated = giveaway.copy(
            state = GiveawayState.DRAWN, drawSeed = seed,
            winnerUserIDs = winners.map { it.first }, winnerUsernames = winners.map { it.second }
        )
        giveawaysRef(eventID).document(giveaway.id).set(
            mapOf(
                "prize" to updated.prize, "winnerCount" to updated.winnerCount,
                "entryRule" to updated.entryRule.raw, "state" to updated.state.raw,
                "drawSeed" to updated.drawSeed, "winnerUserIDs" to updated.winnerUserIDs,
                "winnerUsernames" to updated.winnerUsernames, "createdAt" to Timestamp(updated.createdAt)
            )
        ).await()
        return updated
    }
}

// MARK: - Errors + write helpers

class EventException(message: String) : Exception(message)

/** Deterministic Long seed (FNV-1a) so a draw is reproducible from its recorded seed string. */
private fun seedToLong(seed: String): Long {
    var h = 1469598103934665603UL
    for (b in seed.toByteArray()) { h = (h xor b.toUByte().toULong()) * 1099511628211UL }
    return h.toLong()
}

private fun StitchEventEntity.toFirestoreMap(): Map<String, Any?> = mapOf(
    "hostUserID" to hostUserID, "hostUsername" to hostUsername, "name" to name,
    "venueName" to venueName, "city" to city,
    "venueLat" to venueLat, "venueLng" to venueLng, "geofenceRadiusMeters" to geofenceRadiusMeters,
    "doorsAt" to Timestamp(doorsAt), "closeAt" to Timestamp(closeAt),
    "startMode" to startMode.raw,
    "promoVideoID" to promoVideoID, "openerVideoID" to openerVideoID, "recapVideoID" to recapVideoID,
    "attendeeAnchorsAllowed" to attendeeAnchorsAllowed,
    "goingCount" to goingCount, "postCount" to postCount,
    "createdAt" to Timestamp(createdAt), "updatedAt" to Timestamp(updatedAt)
)
