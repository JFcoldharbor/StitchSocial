package com.stitchsocial.club.challenge

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.BuildConfig
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

        db.collection("videos").document(headVideoID)
            .update(mapOf("isChallenge" to true, "challenge" to map))
            .await()

        if (BuildConfig.DEBUG) {
            println("🎯 CHALLENGE: attached to $headVideoID (#${draft.normalizedHashtag}, ${draft.winnerCount} winner(s), scope ${draft.scope.raw})")
        }
    }
}
