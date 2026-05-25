package com.stitchsocial.club.live

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Per-stream video-comment queue manager. Mirrors iOS `StreamQueueService` —
 * same Firestore paths, same status enum, same PiP mirror fields written
 * onto the parent stream doc.
 *
 * Architecture:
 *  - **Viewer** path: `submitVideoComment` writes a new comment doc.
 *  - **Creator** path: `listenToQueue` subscribes to pending entries;
 *    `acceptComment` flips status + populates the PiP mirror fields on the
 *    stream doc; `rejectComment` flips status (no mirror). `replayUsedComment`
 *    re-broadcasts an already-displayed clip without going through accept again.
 *  - PiP mirror writes are token-stable: every accept / replay rotates
 *    `pipPlaybackToken`, which the viewer's ExoPlayer listens for to seek to 0.
 */
class StreamQueueService private constructor() {

    companion object {
        @Volatile private var INSTANCE: StreamQueueService? = null
        fun getInstance(): StreamQueueService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StreamQueueService().also { INSTANCE = it }
            }

        private const val TAG = "StreamQueue"
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private var queueListener: ListenerRegistration? = null

    // ── Published state (collected by creator screen) ───────────────────────

    /** Pending clips, ordered priority-first then oldest-first. */
    private val _pendingComments = MutableStateFlow<List<VideoComment>>(emptyList())
    val pendingComments: StateFlow<List<VideoComment>> = _pendingComments.asStateFlow()

    /**
     * Already-broadcast clips, kept in memory (not Firestore-derived) so the
     * carousel can keep showing them with a "✓ used" badge instead of dropping
     * them. Resets on stream end.
     */
    private val _displayedComments = MutableStateFlow<List<VideoComment>>(emptyList())
    val displayedComments: StateFlow<List<VideoComment>> = _displayedComments.asStateFlow()

    /** Currently-broadcasting clip, or null. Drives the floating PiP overlay. */
    private val _activePiP = MutableStateFlow<VideoComment?>(null)
    val activePiP: StateFlow<VideoComment?> = _activePiP.asStateFlow()

    /** Changes on every accept / replay so the player can seek-to-zero. */
    private val _pipPlaybackToken = MutableStateFlow("")
    val pipPlaybackToken: StateFlow<String> = _pipPlaybackToken.asStateFlow()

    val queueCount: Int get() = _pendingComments.value.size

    // ── Paths (must match iOS) ──────────────────────────────────────────────

    private fun commentsPath(communityID: String, streamID: String) =
        "communities/$communityID/streams/$streamID/videoComments"

    private fun streamDoc(communityID: String, streamID: String) =
        "communities/$communityID/streams/$streamID"

    // ─────────────────────────────────────────────────────────────────────────
    // Viewer-side: submit a new video comment. Caller has already uploaded
    // the clip to Storage and resolved both URLs.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun submitVideoComment(
        streamID: String,
        communityID: String,
        authorID: String,
        authorUsername: String,
        authorDisplayName: String,
        authorLevel: Int,
        videoURL: String,
        thumbnailURL: String? = null,
        durationSeconds: Int,
        caption: String = "",
        isPriority: Boolean = false,
        priorityCoinsCost: Int = 0,
    ): VideoComment? {
        require(authorLevel >= VideoComment.MINIMUM_LEVEL) {
            "Author level $authorLevel below minimum ${VideoComment.MINIMUM_LEVEL}"
        }
        val clamped = durationSeconds.coerceAtMost(VideoComment.maxClipSeconds(authorLevel))
        val id = UUID.randomUUID().toString()
        val now = Timestamp.now()

        val payload: Map<String, Any> = mutableMapOf<String, Any>(
            "id" to id,
            "streamID" to streamID,
            "communityID" to communityID,
            "authorID" to authorID,
            "authorUsername" to authorUsername,
            "authorDisplayName" to authorDisplayName,
            "authorLevel" to authorLevel,
            "videoURL" to videoURL,
            "durationSeconds" to clamped,
            "caption" to caption,
            "isPriority" to isPriority,
            "priorityCoinsCost" to priorityCoinsCost,
            "status" to VideoCommentStatus.PENDING.raw,
            "submittedAt" to now,
        ).also { if (thumbnailURL != null) it["thumbnailURL"] = thumbnailURL }

        return runCatching {
            db.collection(commentsPath(communityID, streamID))
                .document(id)
                .set(payload)
                .await()
            VideoComment.fromDoc(id, payload).also {
                Log.d(TAG, "📹 submitted @$authorUsername (Lv $authorLevel)")
            }
        }.getOrElse { err ->
            Log.w(TAG, "submitVideoComment failed: ${err.localizedMessage}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Creator-side: subscribe to the pending queue.
    // ─────────────────────────────────────────────────────────────────────────

    fun listenToQueue(communityID: String, streamID: String) {
        removeQueueListener()
        queueListener = db.collection(commentsPath(communityID, streamID))
            .whereEqualTo("status", VideoCommentStatus.PENDING.raw)
            .orderBy("isPriority", Query.Direction.DESCENDING)
            .orderBy("submittedAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "queue listener error: ${err.localizedMessage}")
                    return@addSnapshotListener
                }
                val docs = snap?.documents.orEmpty()
                val comments = docs.mapNotNull { d ->
                    val data = d.data ?: return@mapNotNull null
                    VideoComment.fromDoc(d.id, data)
                }
                _pendingComments.value = comments

                // Auto-prefetch each pending clip to the local cache so accept
                // → PiP is instant. Cheap dedup via StreamClipCache.
                for (c in comments) {
                    StreamClipCache.prefetch(c.videoURL)
                }
            }
    }

    fun removeQueueListener() {
        queueListener?.remove()
        queueListener = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Creator-side: accept a comment. Mirrors iOS resilience — Firestore commit
    // failure no longer blocks the PiP from firing locally.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun acceptComment(
        commentID: String,
        communityID: String,
        streamID: String,
    ) {
        Log.d(TAG, "📥 accept starting — comment=$commentID stream=$streamID")

        runCatching {
            val batch = db.batch()
            val commentRef = db.collection(commentsPath(communityID, streamID)).document(commentID)
            batch.update(
                commentRef,
                mapOf(
                    "status" to VideoCommentStatus.ACCEPTED.raw,
                    "reviewedAt" to Timestamp.now(),
                )
            )
            val streamRef = db.document(streamDoc(communityID, streamID))
            batch.update(
                streamRef,
                mapOf("acceptedVideoComments" to FieldValue.increment(1))
            )
            batch.commit().await()
            Log.d(TAG, "✅ accept Firestore commit ok")
        }.onFailure { Log.w(TAG, "⚠️ accept commit failed: ${it.localizedMessage}. Continuing local.") }

        // Resolve the comment from local cache OR direct Firestore fetch — same
        // three-tier fallback as iOS so listener races don't strand the PiP.
        var found: VideoComment? = _pendingComments.value.firstOrNull { it.id == commentID }
            ?: _displayedComments.value.firstOrNull { it.id == commentID }
        if (found == null) {
            Log.d(TAG, "🔎 accept cache miss — fetching from Firestore")
            runCatching {
                val snap = db.collection(commentsPath(communityID, streamID))
                    .document(commentID).get().await()
                val data = snap.data
                if (data != null) found = VideoComment.fromDoc(snap.id, data)
            }
        }

        val accepted = found ?: run {
            Log.w(TAG, "❌ accept: comment $commentID not found in cache OR Firestore — PiP cannot fire")
            return
        }

        // Local activation
        _activePiP.value = accepted
        val token = UUID.randomUUID().toString()
        _pipPlaybackToken.value = token

        // Move from pending → displayed
        _pendingComments.value = _pendingComments.value.filterNot { it.id == commentID }
        if (_displayedComments.value.none { it.id == commentID }) {
            _displayedComments.value = _displayedComments.value + accepted
        }

        // Sync mirror to stream doc for viewers
        syncPipToStream(accepted, token)
        Log.d(TAG, "🎬 accept activePiP set — url=${accepted.videoURL.takeLast(40)}")
    }

    suspend fun rejectComment(
        commentID: String,
        communityID: String,
        streamID: String,
    ) {
        runCatching {
            db.collection(commentsPath(communityID, streamID))
                .document(commentID)
                .update(
                    mapOf(
                        "status" to VideoCommentStatus.REJECTED.raw,
                        "reviewedAt" to Timestamp.now(),
                    )
                ).await()
        }.onFailure { Log.w(TAG, "reject failed: ${it.localizedMessage}") }
        _pendingComments.value = _pendingComments.value.filterNot { it.id == commentID }
        Log.d(TAG, "❌ rejected $commentID")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PiP control (creator-only). Replay rotates the token, dismiss clears the
    // mirror fields so viewers drop their overlay too.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun replayPiP() {
        val pip = _activePiP.value ?: return
        val token = UUID.randomUUID().toString()
        _pipPlaybackToken.value = token
        syncPipToStream(pip, token)
    }

    suspend fun replayUsedComment(comment: VideoComment) {
        _activePiP.value = comment
        val token = UUID.randomUUID().toString()
        _pipPlaybackToken.value = token
        syncPipToStream(comment, token)
    }

    suspend fun dismissPiP() {
        val pip = _activePiP.value ?: return
        _activePiP.value = null
        _pipPlaybackToken.value = ""
        clearPipFromStream(pip.communityID, pip.streamID)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mirror writes — see iOS StreamQueueService.syncPipToStream for design.
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun syncPipToStream(comment: VideoComment, token: String) {
        runCatching {
            db.document(streamDoc(comment.communityID, comment.streamID))
                .update(
                    mapOf(
                        "activePipCommentID" to comment.id,
                        "activePipVideoURL" to comment.videoURL,
                        "activePipAuthorUsername" to comment.authorUsername,
                        "activePipAuthorLevel" to comment.authorLevel,
                        "activePipDurationSeconds" to comment.durationSeconds,
                        "pipPlaybackToken" to token,
                        "pipUpdatedAt" to Timestamp.now(),
                    )
                ).await()
        }.onFailure { Log.w(TAG, "syncPipToStream failed: ${it.localizedMessage}") }
    }

    private suspend fun clearPipFromStream(communityID: String, streamID: String) {
        runCatching {
            db.document(streamDoc(communityID, streamID))
                .update(
                    mapOf(
                        "activePipCommentID" to FieldValue.delete(),
                        "activePipVideoURL" to FieldValue.delete(),
                        "activePipAuthorUsername" to FieldValue.delete(),
                        "activePipAuthorLevel" to FieldValue.delete(),
                        "activePipDurationSeconds" to FieldValue.delete(),
                        "pipPlaybackToken" to FieldValue.delete(),
                    )
                ).await()
        }.onFailure { Log.w(TAG, "clearPipFromStream failed: ${it.localizedMessage}") }
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    fun onStreamEnd() {
        removeQueueListener()
        _pendingComments.value = emptyList()
        _displayedComments.value = emptyList()
        _activePiP.value = null
        _pipPlaybackToken.value = ""
        StreamClipCache.purge()
    }
}
