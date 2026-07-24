package com.stitchsocial.club.events

/**
 * Carries "this recording is an event moment" from the Event Hub into the
 * background post pipeline (iOS parity with ios/Events/EventMomentBridge.swift).
 *
 * The composer hands the upload off to BackgroundPostManager and only learns the
 * real video id in its onCompleted callback — after the video document exists.
 * The Hub can't attach the moment itself, so it arms this bridge before opening
 * the recorder; ThreadComposer takes the ref at queue time (so an unrelated
 * later post can't inherit it) and attaches the moment once the id comes back —
 * the same follow-up pattern as challenge/announcement/event-draft attach.
 *
 * A process-scoped `object` so the ref survives the recorder covering the Hub.
 */
object EventMomentBridge {

    data class Ref(
        val eventID: String,
        val agendaItemID: String,   // the target slot: host fills it, or a POV hangs off it
        val hostUserID: String,
        val hostUsername: String,
        val isPOV: Boolean,         // true = on-site guest POV stitch; false = host fills the slot
        val isRecap: Boolean,       // true = the host's closing wrap-up video (the public artifact)
        val isPromo: Boolean        // true = the host's pre-event promo/teaser (discoverable)
    )

    var pending: Ref? = null
        private set

    /** Host arms this before recording — the video fills the given agenda slot. */
    fun armHostMoment(eventID: String, agendaItemID: String, hostUserID: String, hostUsername: String) {
        pending = Ref(eventID, agendaItemID, hostUserID, hostUsername, isPOV = false, isRecap = false, isPromo = false)
    }

    /** Host arms this before recording the pre-event promo/teaser. */
    fun armPromo(eventID: String, hostUserID: String, hostUsername: String) {
        pending = Ref(eventID, "", hostUserID, hostUsername, isPOV = false, isRecap = false, isPromo = true)
    }

    /** Host arms this before recording the closing recap (becomes event.recapVideoID). */
    fun armRecap(eventID: String, hostUserID: String, hostUsername: String) {
        pending = Ref(eventID, "", hostUserID, hostUsername, isPOV = false, isRecap = true, isPromo = false)
    }

    /** On-site guest arms this before stitching — a POV attached to the live moment's thread. */
    fun armPOV(eventID: String, agendaItemID: String, userID: String, username: String) {
        pending = Ref(eventID, agendaItemID, userID, username, isPOV = true, isRecap = false, isPromo = false)
    }

    /** Consumed exactly once, at queue time. Returns null (and clears) when nothing is armed. */
    fun take(): Ref? {
        val r = pending
        pending = null
        return r
    }

    /** Clear any armed ref — the Hub disarms on open so a stale arm never lingers across sessions. */
    fun disarm() { pending = null }
}
