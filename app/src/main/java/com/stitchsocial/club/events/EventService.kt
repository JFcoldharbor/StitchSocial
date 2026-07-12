package com.stitchsocial.club.events

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.VideoServiceImpl
import kotlinx.coroutines.tasks.await
import java.util.Date

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
}
