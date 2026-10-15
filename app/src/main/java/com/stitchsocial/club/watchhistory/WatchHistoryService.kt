/*
 * WatchHistoryService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services — the signed-in user's private watch history.
 *
 * One doc per video at users/{uid}/watch_history/{videoID}, owner-only in the
 * Firestore rules. The rules also reject any key outside the contract
 * (videoID, threadID, creatorID, creatorName, title, thumbnailURL, watchedAt,
 * watchCount), so record() writes exactly those and nothing else. A rewatch
 * merges onto the same doc: watchedAt moves to now and watchCount goes up.
 *
 * Two readers:
 *   - Discover's For You puts what you watched in the last 14 days behind
 *     everything you haven't (recentlyWatchedIDs / watchedIDs), so the same
 *     videos stop coming round again and again.
 *   - Settings → Watch history (WatchHistoryScreen) pages it newest first.
 *
 * Recording is fire-and-forget. A history write failing must never touch
 * playback or the view count, so errors only reach the debug log.
 */

package com.stitchsocial.club.watchhistory

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.CoreVideoMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

data class WatchHistoryEntry(
    val videoID: String,
    val threadID: String,
    val creatorID: String,
    val creatorName: String,
    val title: String,
    val thumbnailURL: String,
    val watchedAt: Date?,
    val watchCount: Long,
) {
    companion object {
        fun from(doc: DocumentSnapshot): WatchHistoryEntry = WatchHistoryEntry(
            videoID = doc.getString("videoID")?.takeIf { it.isNotBlank() } ?: doc.id,
            threadID = doc.getString("threadID") ?: "",
            creatorID = doc.getString("creatorID") ?: "",
            creatorName = doc.getString("creatorName") ?: "",
            title = doc.getString("title") ?: "",
            thumbnailURL = doc.getString("thumbnailURL") ?: "",
            watchedAt = doc.getTimestamp("watchedAt")?.toDate(),
            watchCount = doc.getLong("watchCount") ?: 1L,
        )
    }
}

/** One page of history plus the cursor for the next. [last] is null when the page was empty. */
data class WatchHistoryPage(
    val entries: List<WatchHistoryEntry>,
    val last: DocumentSnapshot?,
    val hasMore: Boolean,
)

object WatchHistory {

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Batched deletes stay well under Firestore's 500-write batch cap. */
    private const val CLEAR_BATCH = 400

    // Whose sets these are. A different account signing in without a reset
    // (an account switch) must not inherit the last one's history.
    @Volatile private var ownerUid: String? = null
    /** From the last recentlyWatchedIDs() read. */
    private val loaded: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Recorded this session — known instantly, before any read comes back. */
    private val session: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    private fun collection(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("watch_history")

    /** Clears both sets if they belong to someone other than [uid]. */
    private fun claim(uid: String) {
        if (ownerUid != uid) {
            loaded.clear()
            session.clear()
            ownerUid = uid
        }
    }

    /** Everything Discover should treat as watched: the last read plus this session. */
    val watchedIDs: Set<String>
        get() {
            val uid = currentUid() ?: return emptySet()
            if (ownerUid != uid) return emptySet()
            return loaded + session
        }

    /**
     * Remember that the signed-in user watched [video]. Called where a view is
     * counted (5 seconds on screen). Signed-out users and the feed's pseudo
     * cards (sponsored and collection placeholders have no videoURL, and
     * sponsored ones no creatorID) are skipped.
     */
    fun record(video: CoreVideoMetadata) {
        val uid = currentUid() ?: return
        if (video.id.isBlank() || video.videoURL.isBlank() || video.creatorID.isBlank()) return
        claim(uid)
        session.add(video.id)

        val data = hashMapOf<String, Any>(
            "videoID" to video.id,
            "threadID" to (video.threadID?.takeIf { it.isNotBlank() } ?: video.id),
            "creatorID" to video.creatorID,
            "creatorName" to video.creatorName,
            "title" to video.title,
            "thumbnailURL" to video.thumbnailURL,
            "watchedAt" to FieldValue.serverTimestamp(),
            "watchCount" to FieldValue.increment(1),
        )
        scope.launch {
            try {
                collection(uid).document(video.id).set(data, SetOptions.merge()).await()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) println("⚠️ WATCH HISTORY: record failed for ${video.id} — ${e.message}")
            }
        }
    }

    /** One page, newest first. Pass the previous page's [WatchHistoryPage.last] to continue. */
    suspend fun page(after: DocumentSnapshot? = null, limit: Int = 30): WatchHistoryPage {
        val uid = currentUid() ?: return WatchHistoryPage(emptyList(), null, false)
        var query = collection(uid)
            .orderBy("watchedAt", Query.Direction.DESCENDING)
            .limit(limit.toLong())
        if (after != null) query = query.startAfter(after)
        val snap = query.get().await()
        return WatchHistoryPage(
            entries = snap.documents.map { WatchHistoryEntry.from(it) },
            last = snap.documents.lastOrNull(),
            hasMore = snap.size() >= limit,
        )
    }

    /** Forget one video. It goes back to being unwatched for Discover straight away. */
    suspend fun remove(videoID: String) {
        val uid = currentUid() ?: return
        collection(uid).document(videoID).delete().await()
        if (ownerUid == uid) {
            loaded.remove(videoID)
            session.remove(videoID)
        }
    }

    /** Delete every entry, in batches, until a read comes back empty. */
    suspend fun clearAll() {
        val uid = currentUid() ?: return
        val ref = collection(uid)
        while (true) {
            val snap = ref.limit(CLEAR_BATCH.toLong()).get().await()
            if (snap.isEmpty) break
            val batch = db.batch()
            snap.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
            if (snap.size() < CLEAR_BATCH) break
        }
        if (ownerUid == uid) {
            loaded.clear()
            session.clear()
        }
    }

    /**
     * IDs watched in the last [days] days, newest first, capped at [limit].
     * Replaces the loaded set. Never throws — an empty set just means Discover
     * orders as if nothing had been watched.
     */
    suspend fun recentlyWatchedIDs(days: Int = 14, limit: Int = 1000): Set<String> {
        val uid = currentUid() ?: return emptySet()
        return try {
            val cutoff = Timestamp(Date(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000))
            val snap = collection(uid)
                .whereGreaterThanOrEqualTo("watchedAt", cutoff)
                .orderBy("watchedAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get().await()
            val ids = snap.documents.map { it.id }.toSet()
            claim(uid)
            loaded.clear()
            loaded.addAll(ids)
            if (BuildConfig.DEBUG) println("👁 WATCH HISTORY: ${ids.size} watched in the last $days days")
            ids
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) println("⚠️ WATCH HISTORY: recent read failed — ${e.message}")
            emptySet()
        }
    }

    /** Sign-out: nothing of the last account's history carries over. */
    fun reset() {
        ownerUid = null
        loaded.clear()
        session.clear()
    }
}
