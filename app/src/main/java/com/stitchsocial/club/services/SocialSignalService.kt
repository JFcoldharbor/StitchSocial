/*
 * SocialSignalService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Social Signal / Megaphone System
 * MATCHES iOS SocialSignalService.swift
 * Features:
 *   - Records notable engagements from Partner+ tier users
 *   - Fetches active social signals for feed injection
 *   - 2-strike impression dismissal
 *   - Calls Cloud Function fan-out
 */

package com.stitchsocial.club.services

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.stitchsocial.club.foundation.NotableEngagement
import com.stitchsocial.club.foundation.SocialSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

class SocialSignalService private constructor() {

    companion object {
        val shared = SocialSignalService()
    }

    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()

    private val _activeSignals = MutableStateFlow<List<SocialSignal>>(emptyList())
    val activeSignals: StateFlow<List<SocialSignal>> = _activeSignals.asStateFlow()

    private val dismissedSignalIDs = mutableSetOf<String>()
    private val engagedSignalIDs = mutableSetOf<String>()

    private val maxImpressionsBeforeDismiss = 2
    private val maxSignalsPerFeedLoad = 5
    private val signalExpirationHours = 72.0

    init {
        println("📢 SOCIAL SIGNAL SERVICE: Initialized")
    }

    // MARK: - Record Notable Engagement

    suspend fun recordNotableEngagement(
        engagerID: String,
        engagerName: String,
        engagerTier: String,
        engagerProfileImageURL: String?,
        videoID: String,
        videoCreatorID: String,
        hypeWeight: Int,
        cloutAwarded: Int
    ) {
        if (!NotableEngagement.isMegaphoneTier(engagerTier)) return
        if (engagerID == videoCreatorID) return

        println("📢 MEGAPHONE: $engagerName ($engagerTier) hyped video $videoID with weight $hypeWeight")

        val docID = "${engagerID}_${videoID}"

        val data = mapOf(
            "id" to docID,
            "engagerID" to engagerID,
            "engagerName" to engagerName,
            "engagerTier" to engagerTier,
            "engagerProfileImageURL" to (engagerProfileImageURL ?: ""),
            "videoID" to videoID,
            "videoCreatorID" to videoCreatorID,
            "hypeWeight" to hypeWeight,
            "cloutAwarded" to cloutAwarded,
            "createdAt" to FieldValue.serverTimestamp()
        )

        try {
            db.collection("videos")
                .document(videoID)
                .collection("notableEngagements")
                .document(docID)
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .await()

            println("📢 MEGAPHONE: Notable engagement recorded")

            triggerFanOut(
                engagerID, engagerName, engagerTier,
                engagerProfileImageURL, videoID, videoCreatorID, hypeWeight
            )
        } catch (e: Exception) {
            println("⚠️ MEGAPHONE: Failed to record — ${e.message}")
        }
    }

    // MARK: - Cloud Function Fan-Out

    private suspend fun triggerFanOut(
        engagerID: String,
        engagerName: String,
        engagerTier: String,
        engagerProfileImageURL: String?,
        videoID: String,
        videoCreatorID: String,
        hypeWeight: Int
    ) {
        val payload = mapOf(
            "engagerID" to engagerID,
            "engagerName" to engagerName,
            "engagerTier" to engagerTier,
            "engagerProfileImageURL" to (engagerProfileImageURL ?: ""),
            "videoID" to videoID,
            "videoCreatorID" to videoCreatorID,
            "hypeWeight" to hypeWeight
        )

        try {
            val result = functions.getHttpsCallable("stitchnoti_fanOutSocialSignal")
                .call(payload).await()

            @Suppress("UNCHECKED_CAST")
            val data = result.data as? Map<String, Any>
            val followersNotified = data?.get("followersNotified") as? Int ?: 0
            println("📢 MEGAPHONE: Fan-out complete — $followersNotified followers will see this")
        } catch (e: Exception) {
            println("⚠️ MEGAPHONE: Fan-out failed — ${e.message}")
        }
    }

    // MARK: - Load Active Signals for Feed

    suspend fun loadActiveSignals(userID: String): List<SocialSignal> {
        val cutoffMs = System.currentTimeMillis() - (signalExpirationHours * 3600000).toLong()
        val cutoffDate = Date(cutoffMs)

        try {
            val snapshot = db.collection("users")
                .document(userID)
                .collection("socialSignals")
                .whereEqualTo("dismissed", false)
                .whereEqualTo("engagedWith", false)
                .whereGreaterThan("createdAt", com.google.firebase.Timestamp(cutoffDate))
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(maxSignalsPerFeedLoad.toLong())
                .get().await()

            val signals = snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                if (dismissedSignalIDs.contains(doc.id)) return@mapNotNull null

                SocialSignal(
                    id = doc.id,
                    videoID = data["videoID"] as? String ?: "",
                    videoCreatorID = data["videoCreatorID"] as? String ?: "",
                    videoCreatorName = data["videoCreatorName"] as? String ?: "",
                    videoTitle = data["videoTitle"] as? String ?: "",
                    videoThumbnailURL = data["videoThumbnailURL"] as? String,
                    engagerID = data["engagerID"] as? String ?: "",
                    engagerName = data["engagerName"] as? String ?: "",
                    engagerTier = data["engagerTier"] as? String ?: "",
                    engagerProfileImageURL = data["engagerProfileImageURL"] as? String,
                    hypeWeight = (data["hypeWeight"] as? Long)?.toInt() ?: 0,
                    createdAt = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date(),
                    impressionCount = (data["impressionCount"] as? Long)?.toInt() ?: 0
                )
            }

            _activeSignals.value = signals
            println("📢 SOCIAL SIGNALS: Loaded ${signals.size} active signals for feed")
            return signals
        } catch (e: Exception) {
            println("⚠️ SOCIAL SIGNALS: Failed to load — ${e.message}")
            return emptyList()
        }
    }

    // MARK: - Track Impression (2-Strike)

    suspend fun recordImpression(signalID: String, userID: String) {
        if (dismissedSignalIDs.contains(signalID) || engagedSignalIDs.contains(signalID)) return

        val docRef = db.collection("users")
            .document(userID)
            .collection("socialSignals")
            .document(signalID)

        try {
            docRef.update(
                mapOf(
                    "impressionCount" to FieldValue.increment(1),
                    "lastImpressionAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val doc = docRef.get().await()
            val impressions = (doc.data?.get("impressionCount") as? Long)?.toInt() ?: 0

            if (impressions >= maxImpressionsBeforeDismiss) {
                docRef.update("dismissed", true).await()
                dismissedSignalIDs.add(signalID)
                _activeSignals.value = _activeSignals.value.filter { it.id != signalID }
                println("📢 SIGNAL DISMISSED: $signalID after $impressions impressions")
            }
        } catch (e: Exception) {
            println("⚠️ SIGNAL IMPRESSION: Failed — ${e.message}")
        }
    }

    // MARK: - Record Engagement

    suspend fun recordEngagement(signalID: String, userID: String) {
        if (engagedSignalIDs.contains(signalID)) return
        engagedSignalIDs.add(signalID)

        try {
            db.collection("users")
                .document(userID)
                .collection("socialSignals")
                .document(signalID)
                .update("engagedWith", true)
                .await()
            println("📢 SIGNAL ENGAGED: $signalID — counts as real view")
        } catch (e: Exception) {
            println("⚠️ SIGNAL ENGAGEMENT: Failed — ${e.message}")
        }
    }

    // MARK: - Get Notable Engagers for Video

    suspend fun getNotableEngagers(videoID: String): List<NotableEngagement> {
        return try {
            val snapshot = db.collection("videos")
                .document(videoID)
                .collection("notableEngagements")
                .orderBy("hypeWeight", Query.Direction.DESCENDING)
                .limit(5)
                .get().await()

            snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                NotableEngagement(
                    id = doc.id,
                    engagerID = data["engagerID"] as? String ?: "",
                    engagerName = data["engagerName"] as? String ?: "",
                    engagerTier = data["engagerTier"] as? String ?: "",
                    engagerProfileImageURL = data["engagerProfileImageURL"] as? String,
                    videoID = data["videoID"] as? String ?: "",
                    videoCreatorID = data["videoCreatorID"] as? String ?: "",
                    hypeWeight = (data["hypeWeight"] as? Long)?.toInt() ?: 0,
                    cloutAwarded = (data["cloutAwarded"] as? Long)?.toInt() ?: 0,
                    createdAt = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()
                )
            }
        } catch (e: Exception) {
            println("⚠️ Notable engagers fetch failed — ${e.message}")
            emptyList()
        }
    }

    fun clearSessionCache() {
        dismissedSignalIDs.clear()
        engagedSignalIDs.clear()
        _activeSignals.value = emptyList()
    }
}