/*
 * DiscoverySignal.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 6: Coordination — Passive Discovery Engagement Tracking
 * Full mirror of Swift DiscoverySignal.swift + DiscoveryEngagementTracker.swift
 *
 * Tracks watch time, session intent, rewatch caps, and creator preference scoring
 * from discovery swipe cards WITHOUT any explicit user buttons.
 *
 * Signal Logic:
 *   Watch time hype weights: 3s=1, 5s=2, 8s=3, fullscreen tap=4 (max)
 *   Cool-down: <3s + manual swipe away = negative signal
 *   Rewatch cap: max 2x per video (manual swipe-back only, not loops)
 *   Session intent: signals only count if user made at least 1 manual interaction
 *   Auto-progress only sessions generate ZERO algorithm data
 *
 * Creator Preference Tiers:
 *   1 cool = mildSuppress
 *   2-4 cools = escalating suppression, decay over 14 days
 *   4th cool = delay enforced before 5th registers
 *   5+ cools across 2+ videos = blocked (never show in discovery)
 *   Following a creator overrides all cool signals in home feed
 *
 * Persistence:
 *   users/{userID}/discoveryPreferences/{creatorID}
 *   Batch-written every 5s — no per-swipe writes
 *
 * CACHING: All state in-memory per session. Loaded from Firestore once on app start.
 * BATCHING: flushPendingPersists() writes all dirty creators in a single batch commit.
 */

package com.stitchsocial.club.coordination

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import com.stitchsocial.club.BuildConfig

// ─────────────────────────────────────────────
// MARK: - DiscoverySignal
// ─────────────────────────────────────────────

data class DiscoverySignal(
    val videoID: String,
    val creatorID: String,
    val watchTimeSeconds: Double,
    val didTapFullscreen: Boolean,
    val wasManualSwipe: Boolean,   // true = user swiped, false = auto-advanced
    val wasSwipeBack: Boolean,     // true = user swiped back to rewatch
    val timestamp: Date = Date()
) {
    /** Hype weight from watch time — mirrors Swift exactly */
    val hypeWeight: Int get() = when {
        didTapFullscreen       -> 4
        watchTimeSeconds >= 8  -> 3
        watchTimeSeconds >= 5  -> 2
        watchTimeSeconds >= 3  -> 1
        else                   -> 0
    }

    /** Cool signal: quick swipe away with no fullscreen */
    val isCoolSignal: Boolean get() =
        watchTimeSeconds < 3 && wasManualSwipe && !didTapFullscreen
}

// ─────────────────────────────────────────────
// MARK: - CreatorPreference
// ─────────────────────────────────────────────

enum class CreatorPreference(val rawValue: Int) {
    NEUTRAL(0),
    MILD_BOOST(1),
    STRONG_BOOST(2),
    SUPER_FAN(3),
    MILD_SUPPRESS(-1),
    MODERATE_SUPPRESS(-2),
    STRONG_SUPPRESS(-3),
    BLOCKED(-4);

    val shouldShowInDiscovery: Boolean get() = this != BLOCKED

    val displayName: String get() = when (this) {
        NEUTRAL           -> "Neutral"
        MILD_BOOST        -> "Mild Boost"
        STRONG_BOOST      -> "Strong Boost"
        SUPER_FAN         -> "Super Fan"
        MILD_SUPPRESS     -> "Mild Suppress"
        MODERATE_SUPPRESS -> "Moderate Suppress"
        STRONG_SUPPRESS   -> "Strong Suppress"
        BLOCKED           -> "Blocked"
    }

    companion object {
        fun from(raw: Int): CreatorPreference =
            values().firstOrNull { it.rawValue == raw } ?: NEUTRAL
    }
}

// ─────────────────────────────────────────────
// MARK: - VideoWatchRecord
// ─────────────────────────────────────────────

data class VideoWatchRecord(
    val videoID: String,
    val creatorID: String,
    var watchCount: Int = 0,
    var totalHypeWeight: Int = 0,
    var coolSignalCount: Int = 0,
    var didTapFullscreen: Boolean = false,
    var lastWatchedAt: Date = Date()
) {
    val maxHypeWeight: Int get() = 8   // 4 max per watch × 2 rewatches
    val isRewatchCapped: Boolean get() = watchCount >= 2
}

// ─────────────────────────────────────────────
// MARK: - CreatorSignalAccumulator
// ─────────────────────────────────────────────

data class CreatorSignalAccumulator(
    val creatorID: String,
    var totalHypeWeight: Int = 0,
    var totalCoolSignals: Int = 0,
    var videosWithCoolSignals: Int = 0,
    var videosWithHypeSignals: Int = 0,
    var lastCoolSignalAt: Date? = null,
    var lastHypeSignalAt: Date? = null,
    var coolDelayActive: Boolean = false,
    var coolDelayStartedAt: Date? = null,
    var preference: CreatorPreference = CreatorPreference.NEUTRAL
) {
    companion object {
        const val COOL_DELAY_DURATION_MS = 3_000L      // 3s delay before 5th cool registers
        const val COOL_DECAY_WINDOW_MS = 14L * 24 * 60 * 60 * 1000  // 14 days
    }

    val canRegisterBlockingCool: Boolean get() {
        if (!coolDelayActive) return false
        val started = coolDelayStartedAt ?: return false
        return System.currentTimeMillis() - started.time >= COOL_DELAY_DURATION_MS
    }

    fun recalculatePreference() {
        preference = when {
            totalCoolSignals >= 5 && videosWithCoolSignals >= 2 -> CreatorPreference.BLOCKED
            totalCoolSignals >= 4 -> {
                if (!coolDelayActive) {
                    coolDelayActive = true
                    coolDelayStartedAt = Date()
                }
                CreatorPreference.STRONG_SUPPRESS
            }
            totalCoolSignals >= 2 -> CreatorPreference.MODERATE_SUPPRESS
            totalCoolSignals == 1 -> CreatorPreference.MILD_SUPPRESS
            totalHypeWeight >= 20 && videosWithHypeSignals >= 3 -> CreatorPreference.SUPER_FAN
            totalHypeWeight >= 10 && videosWithHypeSignals >= 2 -> CreatorPreference.STRONG_BOOST
            totalHypeWeight >= 3  -> CreatorPreference.MILD_BOOST
            else                  -> CreatorPreference.NEUTRAL
        }
    }

    fun applyDecay() {
        val lastCool = lastCoolSignalAt ?: return
        val elapsed = System.currentTimeMillis() - lastCool.time
        if (elapsed > COOL_DECAY_WINDOW_MS && totalCoolSignals < 5) {
            val periods = (elapsed / COOL_DECAY_WINDOW_MS).toInt()
            totalCoolSignals = maxOf(0, totalCoolSignals - minOf(periods, totalCoolSignals))
            if (totalCoolSignals < 4) {
                coolDelayActive = false
                coolDelayStartedAt = null
            }
            recalculatePreference()
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - DiscoveryEngagementTracker
// ─────────────────────────────────────────────

/**
 * Singleton. Call from DiscoverySwipeCards exactly as Swift does:
 *   startNewSession()        — on discovery view appear
 *   cardBecameActive()       — when new top card appears
 *   cardSwipedAway()         — manual swipe left/right
 *   cardAutoAdvanced()       — loop-count auto-advance
 *   cardTappedFullscreen()   — tap to open fullscreen
 *   loadPreferences()        — once on app start
 *   flushOnExit()            — on app background/exit
 */
object DiscoveryEngagementTracker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val db = FirebaseFirestore.getInstance("stitchfin")

    // Published-style state — read by DiscoveryViewModel.applyFilterAndShuffle
    @Volatile var sessionHasIntent: Boolean = false
        private set

    val creatorPreferences = ConcurrentHashMap<String, CreatorPreference>()

    // Internal state
    private var manualInteractionCount = 0
    private val videoRecords = HashMap<String, VideoWatchRecord>()
    private val creatorAccumulators = HashMap<String, CreatorSignalAccumulator>()
    private val creatorCoolVideoSets = HashMap<String, MutableSet<String>>()
    private val creatorHypeVideoSets = HashMap<String, MutableSet<String>>()

    private var currentCardStartTime: Long? = null
    private var currentCardVideoID: String? = null
    private var currentCardCreatorID: String? = null

    // Batch persist state
    private val pendingPersists = mutableSetOf<String>()
    private var persistJob: Job? = null

    // ── Session ──────────────────────────────────────────────────

    fun startNewSession() {
        manualInteractionCount = 0
        sessionHasIntent = false
        videoRecords.clear()
        currentCardStartTime = null
        currentCardVideoID = null
        currentCardCreatorID = null
        if (BuildConfig.DEBUG) { println("📊 DISCOVERY TRACKER: New session started") }
    }

    fun registerManualInteraction() {
        manualInteractionCount++
        if (!sessionHasIntent) {
            sessionHasIntent = true
            if (BuildConfig.DEBUG) { println("📊 DISCOVERY TRACKER: Session intent confirmed") }
        }
    }

    // ── Card Lifecycle (mirrors Swift exactly) ───────────────────

    fun cardBecameActive(videoID: String, creatorID: String) {
        finalizeCurrentCard(wasManualSwipe = false, wasAutoAdvance = true)
        // GUARD: pseudo feed entries (sponsored ad slots) have empty creatorID.
        // Never start tracking them — the persist path is .document(creatorID)
        // and Firestore throws on .document("") (this crashed iOS).
        if (creatorID.isBlank()) return
        currentCardStartTime = System.currentTimeMillis()
        currentCardVideoID = videoID
        currentCardCreatorID = creatorID
    }

    fun cardSwipedAway(wasSwipeBack: Boolean = false) {
        registerManualInteraction()
        finalizeCurrentCard(wasManualSwipe = true, wasAutoAdvance = false, wasSwipeBack = wasSwipeBack)
    }

    fun cardAutoAdvanced() {
        finalizeCurrentCard(wasManualSwipe = false, wasAutoAdvance = true)
    }

    fun cardTappedFullscreen(videoID: String, creatorID: String) {
        registerManualInteraction()
        // GUARD: sponsored/pseudo cards (empty creatorID) generate no signals —
        // persisting them would build users/{uid}/discoveryPreferences/"" and crash.
        if (creatorID.isBlank()) return
        if (!sessionHasIntent) return

        var record = videoRecords[videoID] ?: VideoWatchRecord(videoID, creatorID)
        record = record.copy(didTapFullscreen = true)
        videoRecords[videoID] = record

        val signal = DiscoverySignal(
            videoID = videoID,
            creatorID = creatorID,
            watchTimeSeconds = 99.0, // fullscreen = max watch
            didTapFullscreen = true,
            wasManualSwipe = true,
            wasSwipeBack = false
        )
        processSignal(signal)
        if (BuildConfig.DEBUG) { println("📊 DISCOVERY TRACKER: Fullscreen tap — ${videoID.take(8)}, hypeWeight=4") }
    }

    // ── Signal Processing ─────────────────────────────────────────

    private fun finalizeCurrentCard(
        wasManualSwipe: Boolean,
        wasAutoAdvance: Boolean,
        wasSwipeBack: Boolean = false
    ) {
        val videoID = currentCardVideoID ?: return
        val creatorID = currentCardCreatorID ?: return
        val startTime = currentCardStartTime ?: return

        val watchTimeMs = System.currentTimeMillis() - startTime
        val watchTimeSecs = watchTimeMs / 1000.0

        currentCardStartTime = null
        currentCardVideoID = null
        currentCardCreatorID = null

        if (!sessionHasIntent) return
        if (wasAutoAdvance && !wasManualSwipe) return

        var record = videoRecords[videoID] ?: VideoWatchRecord(videoID, creatorID)

        if (wasSwipeBack && record.isRewatchCapped) {
            if (BuildConfig.DEBUG) { println("📊 DISCOVERY TRACKER: Rewatch capped for ${videoID.take(8)}") }
            return
        }

        record = record.copy(
            watchCount = record.watchCount + 1,
            lastWatchedAt = Date()
        )

        val signal = DiscoverySignal(
            videoID = videoID,
            creatorID = creatorID,
            watchTimeSeconds = watchTimeSecs,
            didTapFullscreen = false,
            wasManualSwipe = wasManualSwipe,
            wasSwipeBack = wasSwipeBack
        )

        val newTotal = record.totalHypeWeight + signal.hypeWeight
        record = record.copy(
            totalHypeWeight = minOf(newTotal, record.maxHypeWeight),
            coolSignalCount = if (signal.isCoolSignal) record.coolSignalCount + 1 else record.coolSignalCount
        )

        videoRecords[videoID] = record
        processSignal(signal)
    }

    private fun processSignal(signal: DiscoverySignal) {
        val creatorID = signal.creatorID
        // GUARD: empty creatorID must never reach the accumulator/persist path
        if (creatorID.isBlank()) return
        var acc = creatorAccumulators[creatorID] ?: CreatorSignalAccumulator(creatorID)

        acc.applyDecay()

        if (signal.isCoolSignal) {
            if (acc.totalCoolSignals >= 4 && acc.coolDelayActive) {
                if (!acc.canRegisterBlockingCool) {
                    if (BuildConfig.DEBUG) { println("📊 DISCOVERY TRACKER: Cool delay active — 5th signal blocked") }
                    return
                }
            }
            acc.totalCoolSignals++
            acc.lastCoolSignalAt = Date()
            val coolSet = creatorCoolVideoSets.getOrPut(creatorID) { mutableSetOf() }
            coolSet.add(signal.videoID)
            acc.videosWithCoolSignals = coolSet.size
            if (BuildConfig.DEBUG) { println("📊 DISCOVERY TRACKER: Cool — creator=${creatorID.take(8)}, total=${acc.totalCoolSignals}") }

        } else if (signal.hypeWeight > 0) {
            acc.totalHypeWeight += signal.hypeWeight
            acc.lastHypeSignalAt = Date()
            val hypeSet = creatorHypeVideoSets.getOrPut(creatorID) { mutableSetOf() }
            hypeSet.add(signal.videoID)
            acc.videosWithHypeSignals = hypeSet.size
            if (BuildConfig.DEBUG) { println("📊 DISCOVERY TRACKER: Hype — creator=${creatorID.take(8)}, weight=${signal.hypeWeight}") }
        }

        acc.recalculatePreference()
        creatorAccumulators[creatorID] = acc
        creatorPreferences[creatorID] = acc.preference

        persistIfNeeded(creatorID, acc)
    }

    // ── Query Helpers ─────────────────────────────────────────────

    fun shouldShowCreator(creatorID: String): Boolean =
        creatorPreferences[creatorID]?.shouldShowInDiscovery ?: true

    fun discoveryWeight(creatorID: String): Double =
        when (creatorPreferences[creatorID] ?: CreatorPreference.NEUTRAL) {
            CreatorPreference.SUPER_FAN         -> 3.0
            CreatorPreference.STRONG_BOOST      -> 2.0
            CreatorPreference.MILD_BOOST        -> 1.5
            CreatorPreference.NEUTRAL           -> 1.0
            CreatorPreference.MILD_SUPPRESS     -> 0.5
            CreatorPreference.MODERATE_SUPPRESS -> 0.2
            CreatorPreference.STRONG_SUPPRESS   -> 0.1
            CreatorPreference.BLOCKED           -> 0.0
        }

    fun blockedCreatorIDs(): Set<String> =
        creatorPreferences.entries.filter { it.value == CreatorPreference.BLOCKED }.map { it.key }.toSet()

    fun boostedCreatorIDs(): List<Pair<String, Double>> =
        creatorPreferences
            .filter { it.value.rawValue > 0 }
            .map { it.key to discoveryWeight(it.key) }
            .sortedByDescending { it.second }

    fun resetPreference(creatorID: String) {
        creatorAccumulators.remove(creatorID)
        creatorCoolVideoSets.remove(creatorID)
        creatorHypeVideoSets.remove(creatorID)
        creatorPreferences.remove(creatorID)
        if (BuildConfig.DEBUG) { println("🔓 DISCOVERY TRACKER: Cleared preference for ${creatorID.take(8)}") }
    }

    // ── Firebase Persistence ──────────────────────────────────────

    private fun persistIfNeeded(creatorID: String, accumulator: CreatorSignalAccumulator) {
        pendingPersists.add(creatorID)
        persistJob?.cancel()
        // Batch write after 5s — matches Swift exactly
        persistJob = scope.launch {
            delay(5_000)
            flushPendingPersists()
        }
    }

    private suspend fun flushPendingPersists() {
        val userID = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val toFlush = pendingPersists.toSet()
        pendingPersists.clear()

        val batch = db.batch()
        for (creatorID in toFlush) {
            // FINAL GUARD: .document("") throws IllegalArgumentException — the exact
            // crash sponsored pseudo-cards caused on iOS. Never build an empty path.
            if (creatorID.isBlank()) continue
            val acc = creatorAccumulators[creatorID] ?: continue
            val ref = db.collection("users")
                .document(userID)
                .collection("discoveryPreferences")
                .document(creatorID)
            val data = mapOf(
                "creatorID"              to creatorID,
                "totalHypeWeight"        to acc.totalHypeWeight,
                "totalCoolSignals"       to acc.totalCoolSignals,
                "videosWithCoolSignals"  to acc.videosWithCoolSignals,
                "videosWithHypeSignals"  to acc.videosWithHypeSignals,
                "preference"             to acc.preference.rawValue,
                "coolDelayActive"        to acc.coolDelayActive,
                "updatedAt"              to Timestamp.now()
            )
            batch.set(ref, data, com.google.firebase.firestore.SetOptions.merge())
        }

        try {
            batch.commit().await()
            if (BuildConfig.DEBUG) { println("📊 DISCOVERY TRACKER: Persisted ${toFlush.size} creator preferences") }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("⚠️ DISCOVERY TRACKER: Persist failed — ${e.message}") }
            pendingPersists.addAll(toFlush) // re-queue
        }
    }

    suspend fun loadPreferences() {
        val userID = FirebaseAuth.getInstance().currentUser?.uid ?: return
        try {
            val snapshot = db.collection("users")
                .document(userID)
                .collection("discoveryPreferences")
                .get().await()
            for (doc in snapshot.documents) {
                val data = doc.data ?: continue
                val creatorID = data["creatorID"] as? String ?: doc.id
                val prefRaw = (data["preference"] as? Long)?.toInt() ?: 0
                val pref = CreatorPreference.from(prefRaw)
                creatorPreferences[creatorID] = pref

                val acc = CreatorSignalAccumulator(
                    creatorID                = creatorID,
                    totalHypeWeight          = (data["totalHypeWeight"] as? Long)?.toInt() ?: 0,
                    totalCoolSignals         = (data["totalCoolSignals"] as? Long)?.toInt() ?: 0,
                    videosWithCoolSignals    = (data["videosWithCoolSignals"] as? Long)?.toInt() ?: 0,
                    videosWithHypeSignals    = (data["videosWithHypeSignals"] as? Long)?.toInt() ?: 0,
                    coolDelayActive          = data["coolDelayActive"] as? Boolean ?: false,
                    preference               = pref
                )
                creatorAccumulators[creatorID] = acc
            }
            if (BuildConfig.DEBUG) { println("📊 DISCOVERY TRACKER: Loaded ${creatorPreferences.size} creator preferences") }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("⚠️ DISCOVERY TRACKER: Failed to load preferences — ${e.message}") }
        }
    }

    fun flushOnExit() {
        scope.launch { flushPendingPersists() }
    }
}