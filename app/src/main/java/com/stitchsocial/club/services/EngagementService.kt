package com.stitchsocial.club.services

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.tasks.await

/**
 * A hype or a cool, processed by the server — iOS parity (EngagementManager).
 *
 * Android did all of this from the phone: it wrote hypeCount and coolCount
 * straight onto the video document and paid the creator's clout itself, while
 * iOS had already moved both to stitchnoti_processEngagement.
 *
 * Neither half of that was working.
 *
 * The counter write has been refused by firestore.rules for months — the rule
 * has never allowed a client to touch hypeCount or coolCount — and nothing
 * noticed, because the write result was thrown away. The clout write did land,
 * which was worse: the amount came from a tier the client chose and went to a
 * creatorID the client named, so a phone could pay anyone anything. Closing
 * that in the rules is what made this file necessary rather than merely
 * correct.
 *
 * The server reads the sender's tier from their own user document and the
 * recipient from the video's, computes the clout, writes the counters, splits
 * the share with any accepted collaborators, and keeps the per-viewer
 * engagement state that stops a video being hyped forever. All this does is
 * ask it.
 */
object EngagementService {

    private val functions = FirebaseFunctions.getInstance("us-central1")
    private val auth = FirebaseAuth.getInstance()

    /** What the server decided, for the UI to show. */
    data class Result(
        val visualIncrement: Int,
        val cloutAwarded: Int,
    )

    /**
     * Returns null when the engagement did not happen — not signed in, the
     * call failed, or the server refused it (already engaged, over the
     * per-video limit). The caller rolls its tap state back on null.
     */
    suspend fun process(
        videoID: String,
        engagementType: String,
        isBurst: Boolean = false,
    ): Result? {
        auth.currentUser ?: return null

        return try {
            val payload = mapOf(
                "videoID" to videoID,
                "engagementType" to engagementType,
                "isBurst" to isBurst,
            )
            val response = functions
                .getHttpsCallable("stitchnoti_processEngagement")
                .call(payload)
                .await()

            @Suppress("UNCHECKED_CAST")
            val data = response.data as? Map<String, Any> ?: return null
            if (data["success"] != true) return null

            Result(
                visualIncrement = (data["visualIncrement"] as? Number)?.toInt() ?: 0,
                cloutAwarded = (data["cloutAwarded"] as? Number)?.toInt() ?: 0,
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("ENGAGEMENT: $engagementType on $videoID failed — ${e.message}") }
            null
        }
    }
}
