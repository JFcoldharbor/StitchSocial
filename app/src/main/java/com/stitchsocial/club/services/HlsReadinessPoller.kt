package com.stitchsocial.club.services

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HEAD-polls a video's HLS master and, on the first 200, flips the doc status
 * processing -> published so every playback resolver upgrades from the MP4 fallback
 * to ABR HLS. Part of the TikTok/IG optimistic upload pattern (iOS parity —
 * see project_stitch_cdn_integration).
 *
 * Best-effort: if the poster's app is backgrounded/killed before the master is ready
 * the doc stays `processing`, but CoreVideoMetadata.playbackURL self-heals to HLS once
 * the doc is >45s old, and the server-side flip (CDN_STATUS_FLIP_SPEC) is the durable
 * source of truth. No data is lost.
 */
object HlsReadinessPoller {

    private val db by lazy { FirebaseFirestore.getInstance("stitchfin") }
    private val client = OkHttpClient()

    /** Emits the Firestore doc id when its HLS master goes live. Feed players observe
     *  this to swap an optimistic local file over to HLS. */
    private val _published = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val published: SharedFlow<String> = _published

    /**
     * Poll `hlsURL` (HEAD) up to [attempts] times spaced [intervalMs] apart
     * (default ~2 min at 15s). On the first 200, set status -> published and emit [docId].
     */
    suspend fun pollUntilReady(
        docId: String,
        hlsURL: String,
        attempts: Int = 8,
        intervalMs: Long = 15_000L
    ) = withContext(Dispatchers.IO) {
        repeat(attempts) {
            delay(intervalMs)
            val live = try {
                val req = Request.Builder().url(hlsURL).head().build()
                client.newCall(req).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
            if (live) {
                try {
                    db.collection("videos").document(docId).update("status", "published").await()
                } catch (e: Exception) {
                    // Non-fatal — server-side flip / 45s self-heal covers this.
                }
                _published.tryEmit(docId)
                return@withContext
            }
        }
    }
}
