/*
 * HypeRatingService.kt - HYPE RATING REGENERATION SYSTEM
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * MATCHES iOS HypeRatingService.swift:
 * - Activity-based regeneration (posting, stitching, receiving engagement)
 * - Passive time regen (30% over 7 days)
 * - Diminishing returns per source per day
 * - Daily caps per source
 * - Firebase persistence under users/{uid}/hypeRating/state
 * - Pending regen queue for offline creators
 */

package com.stitchsocial.club.services

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// MARK: - Hype Regen Source (matches iOS HypeRegenSource)

enum class HypeRegenSource(
    val key: String,
    val baseRegenAmount: Double,
    val diminishingFactor: Double,
    val dailyCap: Int
) {
    RECEIVED_HYPE("receivedHype", 1.5, 0.95, 50),
    RECEIVED_STITCH("receivedStitch", 3.0, 0.90, 20),
    RECEIVED_REPLY("receivedReply", 2.0, 0.90, 30),
    POSTED_ORIGINAL("postedOriginal", 4.0, 0.60, 5),
    POSTED_CAMERA_ROLL("postedCameraRoll", 1.5, 0.40, 3),
    STITCHED_OTHER("stitchedOther", 2.0, 0.70, 10),
    REPLIED_TO_OTHER("repliedToOther", 1.5, 0.70, 15),
    PASSIVE_TIME("passiveTime", 0.0, 1.0, 1),
    REFERRAL_BONUS("referralBonus", 2.0, 1.0, 10);
}

// MARK: - Hype Rating State (matches iOS HypeRatingState)

data class HypeRatingStateData(
    var currentRating: Double = 25.0,
    var lastUpdatedAt: Date = Date(),
    var lastPassiveRegenAt: Date = Date(),
    var dailyEventCounts: MutableMap<String, Int> = mutableMapOf(),
    var dailyCountsResetAt: Date = Date(),
    var lastFullAt: Date? = null,
    var pendingRegenFromEngagement: Double = 0.0
) {
    // 30% over 168 hours (7 days) = ~0.1786% per hour
    fun calculatePassiveRegen(): Double {
        val now = Date()
        val hoursSinceLastRegen = (now.time - lastPassiveRegenAt.time) / 3600000.0
        if (hoursSinceLastRegen < 1.0) return 0.0

        val passiveRatePerHour = 30.0 / 168.0
        val passiveRegen = hoursSinceLastRegen * passiveRatePerHour
        val capped = min(passiveRegen, 30.0)

        lastPassiveRegenAt = now
        return capped
    }

    fun resetDailyCountsIfNeeded() {
        val cal = Calendar.getInstance()
        val nowDay = cal.get(Calendar.DAY_OF_YEAR)
        cal.time = dailyCountsResetAt
        val resetDay = cal.get(Calendar.DAY_OF_YEAR)

        if (nowDay != resetDay) {
            dailyEventCounts.clear()
            dailyCountsResetAt = Date()
        }
    }

    fun canRegisterEvent(source: HypeRegenSource): Boolean {
        resetDailyCountsIfNeeded()
        val count = dailyEventCounts[source.key] ?: 0
        return count < source.dailyCap
    }

    fun registerEvent(source: HypeRegenSource) {
        resetDailyCountsIfNeeded()
        dailyEventCounts[source.key] = (dailyEventCounts[source.key] ?: 0) + 1
    }

    fun diminishedReward(source: HypeRegenSource): Double {
        val count = dailyEventCounts[source.key] ?: 0
        return source.baseRegenAmount * source.diminishingFactor.pow(count.toDouble())
    }
}

// MARK: - Hype Rating Service (matches iOS HypeRatingService)

class HypeRatingService private constructor() {

    companion object {
        val shared = HypeRatingService()
    }

    private val _currentRating = MutableStateFlow(25.0)
    val currentRating: StateFlow<Double> = _currentRating.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private var state = HypeRatingStateData()
    private val db = FirebaseFirestore.getInstance()
    private val maxRating = 100.0
    private val minRating = 0.0
    private var saveJob: Job? = null

    init {
        println("⚡ HYPE RATING SERVICE: Initialized")
    }

    // MARK: - Load (matches iOS loadRating)

    suspend fun loadRating() {
        val userID = FirebaseAuth.getInstance().currentUser?.uid ?: return

        try {
            val doc = db.collection("users")
                .document(userID)
                .collection("hypeRating")
                .document("state")
                .get().await()

            if (doc.exists()) {
                val data = doc.data ?: return
                state.currentRating = (data["currentRating"] as? Double) ?: 25.0
                (data["lastUpdatedAt"] as? com.google.firebase.Timestamp)?.let { state.lastUpdatedAt = it.toDate() }
                (data["lastPassiveRegenAt"] as? com.google.firebase.Timestamp)?.let { state.lastPassiveRegenAt = it.toDate() }
                @Suppress("UNCHECKED_CAST")
                (data["dailyEventCounts"] as? Map<String, Long>)?.let { map ->
                    state.dailyEventCounts = map.mapValues { it.value.toInt() }.toMutableMap()
                }
                (data["dailyCountsResetAt"] as? com.google.firebase.Timestamp)?.let { state.dailyCountsResetAt = it.toDate() }
                (data["pendingRegenFromEngagement"] as? Double)?.let { state.pendingRegenFromEngagement = it }

                // Apply passive regen since last load
                val passiveRegen = state.calculatePassiveRegen()
                if (passiveRegen > 0) {
                    state.currentRating = min(maxRating, state.currentRating + passiveRegen)
                    println("⚡ HYPE RATING: +${"%.1f".format(passiveRegen)}% passive regen")
                }

                // Apply pending engagement regen
                if (state.pendingRegenFromEngagement > 0) {
                    state.currentRating = min(maxRating, state.currentRating + state.pendingRegenFromEngagement)
                    println("⚡ HYPE RATING: +${"%.1f".format(state.pendingRegenFromEngagement)}% pending engagement regen")
                    state.pendingRegenFromEngagement = 0.0
                }

                _currentRating.value = state.currentRating
                _isLoaded.value = true
                scheduleSave()
                println("⚡ HYPE RATING: Loaded ${"%.1f".format(state.currentRating)}%")
            } else {
                // First time
                state = HypeRatingStateData()
                _currentRating.value = state.currentRating
                _isLoaded.value = true
                saveToFirebase()
                println("⚡ HYPE RATING: Initialized at ${"%.1f".format(state.currentRating)}%")
            }
        } catch (e: Exception) {
            println("⚠️ HYPE RATING: Failed to load — ${e.message}")
            _currentRating.value = state.currentRating
            _isLoaded.value = true
        }
    }

    // MARK: - Deduct (matches iOS)

    fun deductRating(cost: Double) {
        state.currentRating = max(minRating, state.currentRating - cost)
        state.lastUpdatedAt = Date()
        _currentRating.value = state.currentRating
        scheduleSave()
    }

    fun canAfford(cost: Double): Boolean = state.currentRating >= cost

    // MARK: - Activity Regen Triggers (matches iOS)

    fun receivedHypeOnContent()               = applyRegen(HypeRegenSource.RECEIVED_HYPE)
    fun receivedStitchOnContent()             = applyRegen(HypeRegenSource.RECEIVED_STITCH)
    fun receivedReplyOnContent()              = applyRegen(HypeRegenSource.RECEIVED_REPLY)
    fun didPostOriginalContent(isInApp: Boolean) = applyRegen(if (isInApp) HypeRegenSource.POSTED_ORIGINAL else HypeRegenSource.POSTED_CAMERA_ROLL)
    fun didStitchContent()                    = applyRegen(HypeRegenSource.STITCHED_OTHER)
    fun didReplyToContent()                   = applyRegen(HypeRegenSource.REPLIED_TO_OTHER)
    fun applyReferralBonus()                  = applyRegen(HypeRegenSource.REFERRAL_BONUS)

    // MARK: - Core Regen (matches iOS)

    private fun applyRegen(source: HypeRegenSource) {
        if (!state.canRegisterEvent(source)) {
            println("⚡ HYPE RATING: Daily cap reached for ${source.key}")
            return
        }

        val reward = state.diminishedReward(source)
        state.registerEvent(source)
        if (reward <= 0.01) return

        val oldRating = state.currentRating
        state.currentRating = min(maxRating, state.currentRating + reward)
        state.lastUpdatedAt = Date()
        _currentRating.value = state.currentRating

        if (state.currentRating >= maxRating) {
            state.lastFullAt = Date()
        }

        println("⚡ HYPE RATING: +${"%.2f".format(reward)}% from ${source.key} → ${"%.1f".format(oldRating)}% → ${"%.1f".format(state.currentRating)}%")
        scheduleSave()
    }

    fun refreshPassiveRegen() {
        val passiveRegen = state.calculatePassiveRegen()
        if (passiveRegen <= 0.1) return

        state.currentRating = min(maxRating, state.currentRating + passiveRegen)
        state.lastUpdatedAt = Date()
        _currentRating.value = state.currentRating
        println("⚡ HYPE RATING: Passive +${"%.1f".format(passiveRegen)}% → ${"%.1f".format(state.currentRating)}%")
        scheduleSave()
    }

    // MARK: - Persistence (matches iOS)

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = CoroutineScope(Dispatchers.IO).launch {
            delay(3000L) // 3s debounce
            saveToFirebase()
        }
    }

    private suspend fun saveToFirebase() {
        val userID = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val data = mapOf(
            "currentRating" to state.currentRating,
            "lastUpdatedAt" to com.google.firebase.Timestamp(state.lastUpdatedAt),
            "lastPassiveRegenAt" to com.google.firebase.Timestamp(state.lastPassiveRegenAt),
            "dailyEventCounts" to state.dailyEventCounts,
            "dailyCountsResetAt" to com.google.firebase.Timestamp(state.dailyCountsResetAt),
            "pendingRegenFromEngagement" to state.pendingRegenFromEngagement
        )

        try {
            db.collection("users")
                .document(userID)
                .collection("hypeRating")
                .document("state")
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .await()
        } catch (e: Exception) {
            println("⚠️ HYPE RATING: Save failed — ${e.message}")
        }
    }

    fun flushOnExit() {
        CoroutineScope(Dispatchers.IO).launch { saveToFirebase() }
    }

    // MARK: - Queue Regen for Offline Creators (matches iOS)

    suspend fun queueEngagementRegen(source: HypeRegenSource, amount: Double) {
        val userID = FirebaseAuth.getInstance().currentUser?.uid ?: return

        try {
            db.collection("users")
                .document(userID)
                .collection("hypeRating")
                .document("state")
                .set(
                    mapOf("pendingRegenFromEngagement" to FieldValue.increment(amount)),
                    com.google.firebase.firestore.SetOptions.merge()
                ).await()
        } catch (e: Exception) {
            println("⚠️ HYPE RATING: Failed to queue regen — ${e.message}")
        }
    }
}