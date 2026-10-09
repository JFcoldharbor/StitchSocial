/*
 * ShowDeletionService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 3: Services — the one way the app deletes a show, season or episode.
 *
 * Shows, episodes and their parts are stored flat and linked by id, and
 * Firestore never cascades a delete. So when the app deleted them itself it
 * only ever removed the container — a show flagged "removed", a collection
 * flagged "deleted", a season document deleted with its episodes stranded —
 * and every part stayed live: playable, scanned, counted as a video, and
 * stored on the CDN. The deleteShowContent Cloud Function
 * (StitchSocialFunctions/showDeletion.js) does the whole cascade instead, and
 * checks the caller owns what they are deleting. Mirrors iOS ShowDeletionService.
 */

package com.stitchsocial.club.services

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

object ShowDeletionService {

    sealed class Scope {
        data class Show(val showId: String) : Scope()
        data class Season(val showId: String, val seasonId: String) : Scope()
        /** Also right for a standalone collection: it's the same document. */
        data class Episode(val episodeId: String) : Scope()

        val payload: Map<String, Any>
            get() = when (this) {
                is Show -> mapOf("scope" to "show", "showId" to showId)
                is Season -> mapOf("scope" to "season", "showId" to showId, "seasonId" to seasonId)
                is Episode -> mapOf("scope" to "episode", "episodeId" to episodeId)
            }
    }

    data class Result(val episodesDeleted: Int, val partsDeleted: Int)

    suspend fun delete(scope: Scope): Result {
        val response = FirebaseFunctions.getInstance("us-central1")
            .getHttpsCallable("deleteShowContent")
            // A big show is a lot of parts to delete one by one; the function
            // allows itself 540s, so don't give up on it at the SDK's 70.
            .apply { setTimeout(540, TimeUnit.SECONDS) }
            .call(scope.payload)
            .await()
        val data = response.getData() as? Map<*, *> ?: emptyMap<String, Any>()
        return Result(
            episodesDeleted = (data["episodesDeleted"] as? Number)?.toInt() ?: 0,
            partsDeleted = (data["partsDeleted"] as? Number)?.toInt() ?: 0,
        )
    }
}
