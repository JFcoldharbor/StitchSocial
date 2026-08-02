package com.stitchsocial.club.live

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.stitchsocial.club.BuildConfig

/**
 * Singleton backing the live-stream module on Android. Mirrors the iOS
 * `LiveStreamService` — same Firestore paths, same field names — so a creator
 * on iOS and a viewer on Android (or vice versa) see the same source of truth.
 *
 * Surface:
 *  - [listenToStream] — viewer attaches a snapshot listener; updates
 *    [activeStream], [pipState], [viewerCount], [collectiveCoinsTotal],
 *    [isStreaming], and ticks [elapsedSeconds] every second.
 *  - [removeStreamListener] — detach + stop the timer.
 *  - [forceEndStream] — ghost-stream recovery; creator force-ends any stale
 *    live doc on their own community when the previous session crashed.
 *
 * Not yet ported (creator-only paths handled in Phase 3): `startStream`,
 * `endStream`, completion records, heartbeat, XP rollups.
 */
class LiveStreamService private constructor() {

    // ── Singleton (matches the iOS shared instance pattern) ─────────────────

    companion object {
        @Volatile private var INSTANCE: LiveStreamService? = null
        fun getInstance(): LiveStreamService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LiveStreamService().also { INSTANCE = it }
            }

        private const val TAG = "LiveStream"
    }

    // ── Firestore + coroutine scope ─────────────────────────────────────────

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Published state (collected by the viewer screen) ────────────────────

    private val _activeStream = MutableStateFlow<LiveStream?>(null)
    val activeStream: StateFlow<LiveStream?> = _activeStream.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _viewerCount = MutableStateFlow(0)
    val viewerCount: StateFlow<Int> = _viewerCount.asStateFlow()

    private val _collectiveCoinsTotal = MutableStateFlow(0)
    val collectiveCoinsTotal: StateFlow<Int> = _collectiveCoinsTotal.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private val _pipState = MutableStateFlow<PipMirrorState?>(null)
    val pipState: StateFlow<PipMirrorState?> = _pipState.asStateFlow()

    // ── Private ─────────────────────────────────────────────────────────────

    private var streamListener: ListenerRegistration? = null
    private var tickerJob: Job? = null

    // ── Paths (must match iOS) ──────────────────────────────────────────────

    private fun streamsPath(creatorID: String) =
        "communities/$creatorID/streams"

    private fun streamPath(creatorID: String, streamID: String) =
        "communities/$creatorID/streams/$streamID"

    // ─────────────────────────────────────────────────────────────────────────
    // Viewer-side: subscribe to the stream doc.
    //
    // Updates [activeStream], parses [pipState], starts the local elapsed
    // ticker so the timer UI advances every second. Multiple calls are safe —
    // the previous listener is detached first.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tell the community a stream just started (iOS parity with
     * LiveStreamService.sendGoLiveNotification).
     *
     * Best-effort: a failure here must never fail the stream itself, which is
     * already live by the time this runs. Capped at 200 members because this is
     * a client-side fan-out — beyond that it belongs in a Cloud Function. Same
     * limit iOS uses.
     */
    private suspend fun sendGoLiveNotification(
        creatorID: String,
        streamID: String,
        message: String
    ) {
        try {
            val communityDoc = db.collection("communities").document(creatorID).get().await()
            val displayName = communityDoc.getString("creatorDisplayName")
                ?: communityDoc.getString("creatorUsername")
                ?: "Creator"

            val membersSnap = db.collection("communities").document(creatorID)
                .collection("members")
                .limit(200)
                .get().await()

            val batch = db.batch()
            for (doc in membersSnap.documents) {
                val memberID = doc.id
                if (memberID == creatorID) continue

                val notifRef = db.collection("notifications").document()
                batch.set(notifRef, mapOf(
                    "id" to notifRef.id,
                    "recipientID" to memberID,
                    "senderID" to creatorID,
                    "type" to "go_live",
                    "title" to "\uD83D\uDD34 $displayName is LIVE!",
                    "message" to message.trim().ifBlank { "Tap to join the stream" },
                    "payload" to mapOf(
                        "communityID" to creatorID,
                        "streamID" to streamID
                    ),
                    "isRead" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                ))
            }
            batch.commit().await()
            if (BuildConfig.DEBUG) {
                println("go-live notification sent to ${membersSnap.size()} members")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("go-live notification failed - ${e.message}") }
        }
    }

    fun listenToStream(creatorID: String, streamID: String) {
        removeStreamListener()

        // Local 1Hz ticker — derives [elapsedSeconds] from the stream's
        // `startedAt` so both creator and viewer see the same clock without
        // additional Firestore reads.
        startTicker()

        streamListener = db.document(streamPath(creatorID, streamID))
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "stream listener error: ${error.localizedMessage}")
                    return@addSnapshotListener
                }
                val snap = snapshot ?: return@addSnapshotListener
                val raw = snap.data ?: emptyMap()

                LiveStream.fromDoc(snap.id, raw)?.let { stream ->
                    _activeStream.value = stream
                    _viewerCount.value = stream.viewerCount
                    _collectiveCoinsTotal.value = stream.totalCoinsSpent
                    _isStreaming.value = stream.status == StreamStatus.LIVE
                }

                // PiP mirror — separate parse since these fields aren't part
                // of the LiveStream codable; either all 6 are present (creator
                // just accepted a clip) or none are (creator never set / has
                // dismissed).
                val parsed = PipMirrorState.fromDoc(raw)
                _pipState.value = parsed

                // Viewer-side prefetch — pull the broadcasting clip onto disk
                // so any future recreate of the ExoPlayer serves locally.
                parsed?.let { StreamClipCache.prefetch(it.videoURL) }
            }
    }

    fun removeStreamListener() {
        streamListener?.remove()
        streamListener = null
        stopTicker()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Creator-side: start stream.
    //
    // Writes the stream doc + flips `isCreatorLive` on the community. Returns
    // the created LiveStream so the caller (creator screen) knows the
    // `streamID` to join Agora with.
    //
    // No tier validation here — caller should run `durationGate` upstream.
    // This is intentionally minimal to ship; XP completion records, heartbeat,
    // and daily caps land in the next chunk.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun startStream(
        creatorID: String,
        creatorUsername: String,
        creatorDisplayName: String,
        tier: StreamDurationTier = StreamDurationTier.SPARK,
        /**
         * What the creator wants their community to show up FOR. Blank falls
         * back to the generic line — a creator who just wants to start isn't
         * held up by a text field.
         */
        goLiveMessage: String = "",
    ): LiveStream? {
        val streamID = java.util.UUID.randomUUID().toString()
        val now = Timestamp.now()
        // FIX 2026-05-22: write the FULL schema iOS's Codable LiveStream
        // expects. Missing fields (peakViewerCount, totalHypeEvents,
        // totalVideoComments, extensionMinutes, maxDurationSeconds,
        // lastHeartbeatAt) caused iOS Codable to fail decode silently → iOS
        // viewer's `activeStream` stayed nil → elapsedSeconds frozen at 0.
        val payload: Map<String, Any> = mapOf(
            "id" to streamID,
            "creatorID" to creatorID,
            "communityID" to creatorID,
            "channelName" to streamID,
            "status" to StreamStatus.LIVE.raw,
            "durationTier" to tier.raw,
            "startedAt" to now,
            "lastHeartbeatAt" to now,
            "viewerCount" to 0,
            "peakViewerCount" to 0,
            "hypeCount" to 0,
            "totalHypeEvents" to 0,
            "totalCoinsSpent" to 0,
            "totalVideoComments" to 0,
            "acceptedVideoComments" to 0,
            "extensionMinutes" to 0,
            "maxDurationSeconds" to tier.durationSeconds,
            "creatorUsername" to creatorUsername,
            "creatorDisplayName" to creatorDisplayName,
        )

        return runCatching {
            db.document(streamPath(creatorID, streamID)).set(payload).await()
            db.collection("communities").document(creatorID).update(
                mapOf(
                    "isCreatorLive" to true,
                    "activeStreamID" to streamID,
                )
            ).await()

            // Members were NEVER told a stream started on Android — the whole
            // notification was missing, not just the message. Sent after the
            // community doc flips, so nobody is invited to a stream that isn't
            // discoverable yet.
            sendGoLiveNotification(creatorID, streamID, goLiveMessage)

            val stream = LiveStream.fromDoc(streamID, payload)
            _activeStream.value = stream
            _isStreaming.value = true
            startTicker()
            // FIX 2026-05-22: attach snapshot listener so the creator's UI
            // sees viewerCount / hypeCount / totalCoinsSpent updates as
            // viewers atomic-increment those fields. Without this the
            // creator's HUD stays at 0 forever — they only ever saw the
            // local payload they wrote during startup.
            listenToStream(creatorID = creatorID, streamID = streamID)
            Log.d(TAG, "✅ started ${tier.displayName} stream $streamID for $creatorID")
            stream
        }.getOrElse { err ->
            Log.w(TAG, "startStream failed: ${err.localizedMessage}")
            null
        }
    }

    /// Creator-side: gracefully end the stream. Flips status, clears the
    /// community's live flags, drops local state. Doesn't write completion
    /// records yet — those land with the XP rollup chunk.
    suspend fun endStream(creatorID: String) {
        val stream = _activeStream.value ?: return
        runCatching {
            db.document(streamPath(creatorID, stream.id)).update(
                mapOf(
                    "status" to StreamStatus.ENDED.raw,
                    "endedAt" to Timestamp.now(),
                    "viewerCount" to 0,
                )
            ).await()
            db.collection("communities").document(creatorID).update(
                mapOf(
                    "isCreatorLive" to false,
                    "activeStreamID" to FieldValue.delete(),
                )
            ).await()
        }.onFailure { Log.w(TAG, "endStream update failed: ${it.localizedMessage}") }

        _activeStream.value = null
        _isStreaming.value = false
        stopTicker()
        Log.d(TAG, "✅ ended stream ${stream.id}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ghost-stream recovery (creator-only utility, but lives here because the
    // viewer's community detail also depends on this clearing the `isCreatorLive`
    // flag if the creator's previous session crashed).
    //
    // Force-ends ANY stream doc for `creatorID` that's still marked LIVE.
    // Doesn't write a completion record — the creator didn't earn XP from a
    // session they didn't intentionally end.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun forceEndStream(creatorID: String) {
        val now = Timestamp.now()
        runCatching {
            val snap = db.collection(streamsPath(creatorID))
                .whereEqualTo("status", StreamStatus.LIVE.raw)
                .get()
                .await()

            for (doc in snap.documents) {
                doc.reference.update(
                    mapOf(
                        "status" to StreamStatus.ENDED.raw,
                        "endedAt" to now,
                        "viewerCount" to 0,
                        "forceEnded" to true,
                    )
                ).await()
            }

            db.collection("communities").document(creatorID)
                .update(
                    mapOf(
                        "isCreatorLive" to false,
                        "activeStreamID" to FieldValue.delete(),
                    )
                ).await()

            Log.d(TAG, "🧹 force-ended ${snap.documents.size} ghost stream(s) for $creatorID")
        }.onFailure { err ->
            Log.w(TAG, "forceEndStream failed: ${err.localizedMessage}")
        }

        // Drop local state if it happened to be tracking this creator.
        if (_activeStream.value?.creatorID == creatorID) {
            _activeStream.value = null
            _isStreaming.value = false
            stopTicker()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Viewer presence — atomic increment / decrement on the stream doc's
    // `viewerCount` field. Matches iOS `viewerJoined` / `viewerLeft`. Without
    // these, the creator's HUD viewer counter stays at 0 forever because the
    // stream doc never sees any join events.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun viewerJoined(creatorID: String, streamID: String, userID: String) {
        runCatching {
            db.document(streamPath(creatorID, streamID))
                .update(
                    mapOf(
                        "viewerCount" to FieldValue.increment(1),
                        "peakViewerCount" to FieldValue.increment(1),
                    )
                ).await()
            // Mirror to a viewers subcollection so the creator can list
            // viewers later (used by the @username chip in the chat row).
            db.collection("communities/$creatorID/streams/$streamID/viewers")
                .document(userID)
                .set(
                    mapOf(
                        "userID" to userID,
                        "joinedAt" to Timestamp.now(),
                    )
                ).await()
            Log.d(TAG, "👁 viewer $userID joined")
        }.onFailure { Log.w(TAG, "viewerJoined failed: ${it.localizedMessage}") }
    }

    suspend fun viewerLeft(creatorID: String, streamID: String, userID: String) {
        runCatching {
            db.document(streamPath(creatorID, streamID))
                .update("viewerCount", FieldValue.increment(-1))
                .await()
            db.collection("communities/$creatorID/streams/$streamID/viewers")
                .document(userID)
                .delete()
                .await()
            Log.d(TAG, "👋 viewer $userID left")
        }.onFailure { Log.w(TAG, "viewerLeft failed: ${it.localizedMessage}") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // One-shot fetch — used by the community detail screen to check if the
    // creator currently has a live stream without attaching a long listener.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun fetchActiveStream(creatorID: String): LiveStream? {
        return runCatching {
            val snap = db.collection(streamsPath(creatorID))
                .whereEqualTo("status", StreamStatus.LIVE.raw)
                .limit(1)
                .get()
                .await()
            snap.documents.firstOrNull()?.let { decode(it) }
        }.getOrNull()
    }

    private fun decode(doc: DocumentSnapshot): LiveStream? {
        val data = doc.data ?: return null
        return LiveStream.fromDoc(doc.id, data)
    }

    // ── Local ticker ────────────────────────────────────────────────────────

    private fun startTicker() {
        tickerJob?.cancel()
        Log.d(TAG, "⏱️ ticker started")
        tickerJob = scope.launch {
            var firstNonZero = false
            while (true) {
                val stream = _activeStream.value
                val elapsed = stream?.elapsedSeconds ?: 0
                _elapsedSeconds.value = elapsed
                if (elapsed > 0 && !firstNonZero) {
                    firstNonZero = true
                    Log.d(TAG, "⏱️ first non-zero tick — elapsed=$elapsed startedAt=${stream?.startedAt?.toDate()}")
                }
                if (stream == null) {
                    Log.d(TAG, "⏱️ tick: activeStream is null (snapshot hasn't decoded yet?)")
                }
                delay(1_000)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        _elapsedSeconds.value = 0
    }
}
