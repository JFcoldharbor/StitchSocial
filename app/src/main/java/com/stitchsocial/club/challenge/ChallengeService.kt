package com.stitchsocial.club.challenge

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.isVideoPubliclyVisible
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Writes the creator's Challenge config onto the thread-HEAD video. This is the ONLY
 * client write for the feature — entry detection, qualification, and the winner draw
 * are all server-side (Cloud Functions on the shared stitchfin backend). iOS parity.
 */
object ChallengeService {

    private val db by lazy { FirebaseFirestore.getInstance("stitchfin") }

    /** Attach a Challenge to a freshly-created thread-head video. `state` starts
     *  "active"; the scheduled draw flips it to "completed". */
    suspend fun attachChallenge(headVideoID: String, draft: ChallengeDraft) {
        val map = mutableMapOf<String, Any>(
            "prize" to draft.prize.trim(),
            "hashtag" to draft.normalizedHashtag,
            "scope" to draft.scope.raw,
            "metric" to draft.metric.raw,
            "threshold" to draft.threshold,
            "winnerCount" to draft.winnerCount,
            "deadline" to Timestamp(draft.deadline),
            "createdAt" to Timestamp(Date()),
            "state" to ChallengeState.ACTIVE.raw,
            "entryCount" to 0,
            "qualifierCount" to 0
        )
        if (draft.scope == ChallengeScope.COMMUNITY && !draft.communityID.isNullOrEmpty()) {
            map["communityID"] = draft.communityID!!
        }
        // Anti-farm gates — enforced by the server-side entry/qualification
        // functions (already live); the client just records the creator's config.
        map["eligibility"] = mapOf(
            "verifiedOnly" to draft.verifiedOnly,
            "uniqueHypers" to draft.uniqueHypers,
            "minAccountAgeDays" to draft.minAccountAgeDays
        )

        db.collection("videos").document(headVideoID)
            .update(mapOf("isChallenge" to true, "challenge" to map))
            .await()

        if (BuildConfig.DEBUG) {
            println("🎯 CHALLENGE: attached to $headVideoID (#${draft.normalizedHashtag}, ${draft.winnerCount} winner(s), scope ${draft.scope.raw})")
        }
    }

    /** Winning entries of a completed challenge — videos on the challenge thread the
     *  server draw marked "won". Read-only; drives GiveawayResultsView winner cards. */
    suspend fun fetchWinners(headVideoID: String): List<ChallengeWinner> {
        return try {
            db.collection("videos")
                .whereEqualTo("challengeThreadID", headVideoID)
                .whereEqualTo("challengeStatus", ChallengeEntryStatus.WON.raw)
                .get().await().documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    // Winners are shown to everyone in the challenge, thumbnail
                    // and all — public surface, same gate. Winning doesn't
                    // exempt a video from moderation.
                    if (!isVideoPubliclyVisible(data)) return@mapNotNull null
                    ChallengeWinner(
                        videoID = doc.id,
                        creatorID = data["creatorID"] as? String ?: "",
                        creatorName = data["creatorName"] as? String ?: "someone",
                        thumbnailURL = data["thumbnailURL"] as? String ?: ""
                    )
                }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("🎯 CHALLENGE: winners fetch failed: ${e.message}") }
            emptyList()
        }
    }
}

/** Lightweight winner-card model for GiveawayResultsView. */
data class ChallengeWinner(
    val videoID: String,
    val creatorID: String,
    val creatorName: String,
    val thumbnailURL: String
)
