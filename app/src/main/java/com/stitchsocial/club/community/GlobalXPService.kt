/*
 * GlobalXPService.kt - GLOBAL COMMUNITY XP AGGREGATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Services - Global XP at 25%, clout milestones, per-community daily tap multipliers
 * Port of: GlobalXPService.swift (iOS)
 *
 * CACHING:
 * - globalXPCache: Single GlobalCommunityXP per user, cached on login, 15-min TTL
 * - tapUsageCache: Dictionary [communityID: CommunityTapUsage], LOCAL ONLY, reset at midnight
 * - awardedMilestones: Tracks which clout milestones were awarded, prevents double-payouts
 * Add to OptimizationConfig under "Global XP Cache"
 *
 * BATCHING:
 * - recalculateGlobalXP: Single query for all memberships, single write to global doc
 * - Tap multiplier usage: LOCAL ONLY — never reads Firestore per tap, syncs on session end
 * - Clout bonus: batched with global XP update
 */

package com.stitchsocial.club.community

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class GlobalXPService private constructor() {

    companion object {
        val shared = GlobalXPService()
        private const val TAG = "GlobalXPService"
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val communityService = CommunityService.shared

    // Published state
    private val _globalXP = MutableStateFlow<GlobalCommunityXP?>(null)
    val globalXP: StateFlow<GlobalCommunityXP?> = _globalXP.asStateFlow()

    private val _globalLevel = MutableStateFlow(1)
    val globalLevel: StateFlow<Int> = _globalLevel.asStateFlow()

    private val _tapMultiplier = MutableStateFlow(0)
    val tapMultiplier: StateFlow<Int> = _tapMultiplier.asStateFlow()

    private val _cloutBonus = MutableStateFlow(0)
    val cloutBonus: StateFlow<Int> = _cloutBonus.asStateFlow()

    // Cache
    private data class CachedItem<T>(val value: T, val cachedAt: Long, val ttlMs: Long) {
        val isExpired: Boolean get() = System.currentTimeMillis() - cachedAt > ttlMs
    }

    private var globalXPCache: CachedItem<GlobalCommunityXP>? = null
    private val tapUsageCache = mutableMapOf<String, CommunityTapUsage>()  // LOCAL ONLY
    private val awardedMilestones = mutableSetOf<Int>()

    private val globalTTL = 900_000L // 15 min

    private object Col {
        const val GLOBAL_XP = "global_community_xp"
        const val COMMUNITIES = "communities"
        const val MEMBERS = "members"
        const val CLOUT_LOG = "clout_bonus_log"
    }

    // MARK: - Load Global XP (Cached, Called on Login)

    suspend fun loadGlobalXP(userID: String): GlobalCommunityXP {
        globalXPCache?.let { if (!it.isExpired) { applyToPublished(it.value); return it.value } }

        val docRef = db.collection(Col.GLOBAL_XP).document(userID)
        val doc = docRef.get().await()

        val global: GlobalCommunityXP
        if (doc.exists() && doc.data != null) {
            global = GlobalCommunityXP.fromFirestore(doc.data!!)
        } else {
            global = GlobalCommunityXP(userID = userID)
            docRef.set(global.toFirestore()).await()
        }

        loadAwardedMilestones(userID)

        globalXPCache = CachedItem(global, System.currentTimeMillis(), globalTTL)
        applyToPublished(global)
        resetTapUsageIfNewDay()

        Log.d(TAG, "Loaded for $userID — Lv ${global.globalLevel}, +${global.tapMultiplierBonus} taps, +${global.permanentCloutBonus} clout")
        return global
    }

    // MARK: - Recalculate Global XP (Single Query + Single Write)

    suspend fun recalculateGlobalXP(userID: String): GlobalCommunityXP {
        val communities = communityService.fetchMyCommunities(userID)
        if (communities.isEmpty()) return loadGlobalXP(userID)

        var totalGlobalXP = 0
        var activeCount = 0
        for (community in communities) {
            totalGlobalXP += GlobalCommunityXP.globalContribution(community.userXP)
            if (community.userXP > 0) activeCount++
        }

        val newGlobalLevel = CommunityXPCurve.levelFromXP(totalGlobalXP)
        val tapBonus = GlobalCommunityXP.tapMultiplierForLevel(newGlobalLevel)
        val newCloutBonus = calculateTotalCloutBonus(newGlobalLevel)
        val previousClout = _globalXP.value?.permanentCloutBonus ?: 0
        val cloutDelta = maxOf(0, newCloutBonus - previousClout)

        val updated = GlobalCommunityXP(
            userID = userID, totalGlobalXP = totalGlobalXP,
            globalLevel = newGlobalLevel, permanentCloutBonus = newCloutBonus,
            tapMultiplierBonus = tapBonus, communitiesActive = activeCount,
            lastCalculatedAt = Date()
        )

        // SINGLE WRITE
        db.collection(Col.GLOBAL_XP).document(userID).set(updated.toFirestore()).await()

        if (cloutDelta > 0) awardCloutBonus(userID, cloutDelta, newGlobalLevel)

        globalXPCache = CachedItem(updated, System.currentTimeMillis(), globalTTL)
        applyToPublished(updated)

        Log.d(TAG, "Recalculated — $totalGlobalXP XP, Lv $newGlobalLevel, $activeCount communities")
        return updated
    }

    // MARK: - Clout Bonus

    private fun calculateTotalCloutBonus(forLevel: Int): Int {
        val milestones = listOf(10, 25, 50, 75, 100, 150, 200)
        return milestones.filter { forLevel >= it }.sumOf { GlobalCommunityXP.cloutBonusForLevel(it) }
    }

    private suspend fun awardCloutBonus(userID: String, amount: Int, atLevel: Int) {
        if (amount <= 0 || awardedMilestones.contains(atLevel)) return

        val batch = db.batch()
        val logRef = db.collection(Col.CLOUT_LOG).document("${userID}_global_${atLevel}")
        batch.set(logRef, mapOf(
            "userID" to userID, "source" to "global_community_xp",
            "amount" to amount, "globalLevel" to atLevel,
            "awardedAt" to Timestamp(Date())
        ))
        batch.commit().await()
        awardedMilestones.add(atLevel)
        Log.d(TAG, "CLOUT: +$amount to $userID at global Lv $atLevel")
    }

    private suspend fun loadAwardedMilestones(userID: String) {
        try {
            val snapshot = db.collection(Col.CLOUT_LOG)
                .whereEqualTo("userID", userID)
                .whereEqualTo("source", "global_community_xp")
                .get().await()
            for (doc in snapshot.documents) {
                (doc.data?.get("globalLevel") as? Number)?.toInt()?.let { awardedMilestones.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load milestones: ${e.message}")
        }
    }

    // MARK: - Tap Multiplier (LOCAL ONLY — No Firestore Per Tap)

    fun remainingBonusTaps(communityID: String): Int {
        resetTapUsageIfNewDay()
        val usage = tapUsageCache[communityID] ?: return _tapMultiplier.value
        return usage.remainingBonusTaps
    }

    /** Use a bonus tap — LOCAL ONLY, returns 2 (boosted) or 1 (normal) */
    fun useBonusTap(communityID: String): Int {
        resetTapUsageIfNewDay()
        if (_tapMultiplier.value <= 0) return 1

        val existing = tapUsageCache[communityID]
        if (existing != null) {
            if (!existing.hasRemainingBonusTaps) return 1
            existing.bonusTapsUsed++
            return 2
        } else {
            tapUsageCache[communityID] = CommunityTapUsage(
                communityID = communityID, date = todayString(),
                bonusTapsUsed = 1, bonusTapsAllowed = _tapMultiplier.value
            )
            return 2
        }
    }

    private fun resetTapUsageIfNewDay() {
        val today = todayString()
        if (tapUsageCache.values.firstOrNull()?.date != today && tapUsageCache.isNotEmpty()) {
            tapUsageCache.clear()
        }
    }

    private fun todayString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // MARK: - Sync Tap Usage (Call on Session End — NOT per tap)

    suspend fun syncTapUsage(userID: String) {
        if (tapUsageCache.isEmpty()) return
        try {
            val batch = db.batch()
            val date = todayString()
            for ((communityID, usage) in tapUsageCache) {
                val docRef = db.collection(Col.GLOBAL_XP).document(userID)
                    .collection("tapUsage").document("${date}_${communityID}")
                batch.set(docRef, mapOf(
                    "communityID" to communityID, "date" to date,
                    "bonusTapsUsed" to usage.bonusTapsUsed,
                    "bonusTapsAllowed" to usage.bonusTapsAllowed,
                    "syncedAt" to Timestamp(Date())
                ))
            }
            batch.commit().await()
            Log.d(TAG, "Synced tap usage for ${tapUsageCache.size} communities")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync tap usage: ${e.message}")
        }
    }

    // MARK: - Progress Info (For UI)

    fun progressToNextLevel(): Double {
        val global = _globalXP.value ?: return 0.0
        return CommunityXPCurve.progressToNextLevel(global.totalGlobalXP)
    }

    fun xpToNextLevel(): Int {
        val global = _globalXP.value ?: return 0
        if (global.globalLevel >= 1000) return 0
        return CommunityXPCurve.totalXPForLevel(global.globalLevel + 1) - global.totalGlobalXP
    }

    fun nextCloutMilestone(): Pair<Int, Int>? {
        val currentLevel = _globalXP.value?.globalLevel ?: 1
        val milestones = listOf(10, 25, 50, 75, 100, 150, 200)
        val next = milestones.firstOrNull { it > currentLevel } ?: return null
        return Pair(next, GlobalCommunityXP.cloutBonusForLevel(next))
    }

    fun nextTapUpgrade(): Pair<Int, Int>? {
        val thresholds = listOf(10 to 1, 25 to 2, 50 to 3, 75 to 4, 100 to 5)
        val currentLevel = _globalXP.value?.globalLevel ?: 1
        return thresholds.firstOrNull { it.first > currentLevel }
    }

    fun globalSummary(): GlobalXPSummary {
        val global = _globalXP.value ?: GlobalCommunityXP(userID = "")
        return GlobalXPSummary(
            totalXP = global.totalGlobalXP, level = global.globalLevel,
            cloutBonus = global.permanentCloutBonus, tapMultiplier = global.tapMultiplierBonus,
            communitiesActive = global.communitiesActive,
            progress = progressToNextLevel(), xpToNext = xpToNextLevel(),
            nextCloutMilestone = nextCloutMilestone(), nextTapUpgrade = nextTapUpgrade()
        )
    }

    private fun applyToPublished(global: GlobalCommunityXP) {
        _globalXP.value = global; _globalLevel.value = global.globalLevel
        _tapMultiplier.value = global.tapMultiplierBonus; _cloutBonus.value = global.permanentCloutBonus
    }

    // MARK: - Lifecycle

    suspend fun onAppBackground(userID: String) {
        syncTapUsage(userID)
        if (globalXPCache?.isExpired == true) {
            try { recalculateGlobalXP(userID) } catch (_: Exception) {}
        }
    }

    suspend fun onLogout(userID: String) {
        syncTapUsage(userID)
        clearAllCaches()
    }

    fun clearAllCaches() {
        globalXPCache = null; tapUsageCache.clear(); awardedMilestones.clear()
        _globalXP.value = null; _globalLevel.value = 1
        _tapMultiplier.value = 0; _cloutBonus.value = 0
    }

    fun invalidateGlobalCache() { globalXPCache = null }
}