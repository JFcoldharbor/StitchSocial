/*
 * CommunityXPService.kt - COMMUNITY XP, LEVEL-UPS, BADGE UNLOCKS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Services - Award XP, level-up detection, badge eligibility, XP buffering
 * Port of: CommunityXPService.swift (iOS)
 *
 * CACHING: xpLookupTable (static, built once at init), pendingXP buffer (flushes every 30s)
 * BATCHING: XP awards accumulate in buffer, NOT written per action.
 *   Flush writes membership update + XP log entry in single Firestore batch.
 *   Badge checks run locally against cached level — zero Firestore reads.
 *   Daily login: one read per community per day.
 * Add to OptimizationConfig under "Community XP Batching"
 */

package com.stitchsocial.club.community

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.*

class CommunityXPService private constructor() {

    companion object {
        val shared = CommunityXPService()
        private const val TAG = "CommunityXPService"
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val communityService = CommunityService.shared

    // Published state
    private val _lastLevelUp = MutableStateFlow<LevelUpEvent?>(null)
    val lastLevelUp: StateFlow<LevelUpEvent?> = _lastLevelUp.asStateFlow()

    private val _lastBadgeUnlock = MutableStateFlow<CommunityBadgeDefinition?>(null)
    val lastBadgeUnlock: StateFlow<CommunityBadgeDefinition?> = _lastBadgeUnlock.asStateFlow()

    // MARK: - XP Lookup Table (Built Once, Never Refetch)
    // Cumulative XP for levels 0-1000, binary search for O(log n) level lookup
    private val xpLookupTable: IntArray

    // MARK: - XP Buffer (Batch Writes — NOT per action)
    // Key: "userID_communityID", flushed every 30 seconds
    private val pendingXP = mutableMapOf<String, PendingXPBuffer>()
    private var flushJob: Job? = null
    private val flushInterval = 30_000L // 30 seconds
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class PendingXPBuffer(
        val userID: String,
        val communityID: String,
        var totalPending: Int,
        val sources: MutableList<CommunityXPSource>,
        var lastAddedAt: Long
    )

    private object Col {
        const val COMMUNITIES = "communities"
        const val MEMBERS = "members"
        const val XP_LOG = "xpLog"
    }

    init {
        // Build lookup table once — 1001 entries, pure math, zero Firestore reads
        val table = IntArray(1001)
        for (level in 2..1000) {
            table[level] = table[level - 1] + CommunityXPCurve.xpRequired(level)
        }
        xpLookupTable = table
        startFlushTimer()
    }

    // MARK: - Award XP (Buffered — does NOT write immediately)

    fun awardXP(
        userID: String,
        communityID: String,
        source: CommunityXPSource,
        multiplier: Double = 1.0
    ) {
        val amount = (source.xpAmount * multiplier).toInt()
        if (amount <= 0) return

        val key = "${userID}_${communityID}"
        val existing = pendingXP[key]
        if (existing != null) {
            existing.totalPending += amount
            existing.sources.add(source)
            existing.lastAddedAt = System.currentTimeMillis()
        } else {
            pendingXP[key] = PendingXPBuffer(
                userID = userID, communityID = communityID,
                totalPending = amount,
                sources = mutableListOf(source),
                lastAddedAt = System.currentTimeMillis()
            )
        }
        Log.d(TAG, "XP BUFFER: +$amount (${source.displayName}) for $userID in $communityID — pending: ${pendingXP[key]?.totalPending}")
    }

    // Award XP immediately — use sparingly for critical events
    suspend fun awardXPImmediate(
        userID: String, communityID: String,
        source: CommunityXPSource, multiplier: Double = 1.0
    ): XPAwardResult {
        val amount = (source.xpAmount * multiplier).toInt()
        return writeXP(userID, communityID, amount, source)
    }

    // MARK: - Flush Pending XP (Batched Write)

    suspend fun flushPendingXP() {
        if (pendingXP.isEmpty()) return

        val toFlush = pendingXP.toMap()
        pendingXP.clear()

        for ((key, buffer) in toFlush) {
            try {
                val primarySource = buffer.sources.lastOrNull() ?: CommunityXPSource.TEXT_POST
                val result = writeXP(buffer.userID, buffer.communityID, buffer.totalPending, primarySource)
                if (result.leveledUp) {
                    Log.d(TAG, "XP FLUSH: ${buffer.userID} leveled up to ${result.newLevel} in ${buffer.communityID}")
                }
            } catch (e: Exception) {
                // Re-queue failed flushes
                pendingXP[key] = buffer
                Log.w(TAG, "XP FLUSH FAILED: ${e.message} — re-queued")
            }
        }
    }

    // MARK: - Core XP Write (Single Batched Operation)
    // BATCHED: membership update + XP log entry in ONE Firestore batch

    private suspend fun writeXP(
        userID: String, communityID: String, amount: Int, source: CommunityXPSource
    ): XPAwardResult {
        val memberRef = db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.MEMBERS).document(userID)

        val doc = memberRef.get().await()
        val data = doc.data ?: throw CommunityError.NotMember
        val membership = CommunityMembership.fromFirestore(data)

        val oldLevel = membership.level
        val newXP = membership.localXP + amount
        val newLevel = levelFromXP(newXP)
        val didLevelUp = newLevel > oldLevel

        // Check badge unlocks (LOCAL — no Firestore reads)
        val oldBadges = membership.earnedBadgeIDs.toSet()
        val eligibleBadges = CommunityBadgeDefinition.badgesEarned(newLevel)
        val newBadgeIDs = eligibleBadges.map { it.id }.filter { it !in oldBadges }

        // Build updated membership
        val updatedMembership = membership.copy(
            localXP = newXP, level = newLevel,
            earnedBadgeIDs = membership.earnedBadgeIDs + newBadgeIDs,
            lastActiveAt = Date()
        )

        val logEntry = CommunityXPTransaction(
            communityID = communityID, userID = userID,
            source = source.key, amount = amount,
            newTotalXP = newXP, newLevel = newLevel,
            leveledUp = didLevelUp, badgeUnlocked = newBadgeIDs.firstOrNull()
        )

        // BATCHED WRITE: membership + xp log in one operation
        val batch = db.batch()
        batch.set(memberRef, updatedMembership.toFirestore())
        val logRef = memberRef.collection(Col.XP_LOG).document(logEntry.id)
        batch.set(logRef, logEntry.toFirestore())
        batch.commit().await()

        // Invalidate membership cache
        communityService.invalidateMembershipCache(userID, communityID)

        // Publish events
        if (didLevelUp) {
            _lastLevelUp.value = LevelUpEvent(
                userID = userID, communityID = communityID,
                oldLevel = oldLevel, newLevel = newLevel
            )
        }
        if (newBadgeIDs.isNotEmpty()) {
            _lastBadgeUnlock.value = eligibleBadges.firstOrNull { it.id == newBadgeIDs.first() }
        }

        val globalContribution = GlobalCommunityXP.globalContribution(amount)

        return XPAwardResult(
            xpAwarded = amount, newTotalXP = newXP,
            oldLevel = oldLevel, newLevel = newLevel,
            leveledUp = didLevelUp,
            newBadges = eligibleBadges.filter { it.id in newBadgeIDs },
            globalXPContribution = globalContribution
        )
    }

    // MARK: - Level Lookup (Binary search on precomputed table — O(log n))

    fun levelFromXP(xp: Int): Int {
        var low = 1; var high = 1000
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (xpLookupTable[mid] <= xp) low = mid else high = mid - 1
        }
        return low
    }

    fun progressToNext(currentXP: Int): Double {
        val level = levelFromXP(currentXP)
        if (level >= 1000) return 1.0
        val currentLevelXP = xpLookupTable[level]
        val nextLevelXP = xpLookupTable[level + 1]
        val range = nextLevelXP - currentLevelXP
        if (range <= 0) return 0.0
        return (currentXP - currentLevelXP).toDouble() / range
    }

    fun xpToNextLevel(currentXP: Int): Int {
        val level = levelFromXP(currentXP)
        if (level >= 1000) return 0
        return xpLookupTable[level + 1] - currentXP
    }

    // MARK: - Daily Login XP

    suspend fun claimDailyLogin(userID: String, communityID: String): DailyLoginResult {
        val memberRef = db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.MEMBERS).document(userID)
        val doc = memberRef.get().await()
        val data = doc.data ?: throw CommunityError.NotMember
        val membership = CommunityMembership.fromFirestore(data)

        val calendar = Calendar.getInstance()
        val today = calendar.get(Calendar.DAY_OF_YEAR)

        membership.lastDailyLoginAt?.let { lastLogin ->
            val lastCal = Calendar.getInstance().apply { time = lastLogin }
            if (lastCal.get(Calendar.DAY_OF_YEAR) == today && lastCal.get(Calendar.YEAR) == calendar.get(Calendar.YEAR)) {
                return DailyLoginResult(false, 0, membership.dailyLoginStreak, "Already claimed today")
            }
        }

        // Calculate streak
        var newStreak = 1
        membership.lastDailyLoginAt?.let { lastLogin ->
            val lastCal = Calendar.getInstance().apply { time = lastLogin }
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            if (lastCal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) &&
                lastCal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR)) {
                newStreak = membership.dailyLoginStreak + 1
            }
        }

        val streakBonus = minOf(newStreak, 15)
        val totalXP = CommunityXPSource.DAILY_LOGIN.xpAmount + streakBonus

        memberRef.update(mapOf(
            "lastDailyLoginAt" to Timestamp(Date()),
            "dailyLoginStreak" to newStreak
        )).await()

        // Award XP through buffer
        awardXP(userID, communityID, CommunityXPSource.DAILY_LOGIN,
            totalXP.toDouble() / CommunityXPSource.DAILY_LOGIN.xpAmount)
        communityService.invalidateMembershipCache(userID, communityID)

        return DailyLoginResult(
            awarded = true, xpAmount = totalXP, streak = newStreak,
            message = if (newStreak > 1) "\uD83D\uDD25 $newStreak day streak! +$streakBonus bonus XP" else "Welcome back!"
        )
    }

    // MARK: - Badge Check (LOCAL — no Firestore reads)

    fun checkBadgeEligibility(currentLevel: Int, earnedBadgeIDs: List<String>): BadgeCheckResult {
        val earned = earnedBadgeIDs.toSet()
        val eligible = CommunityBadgeDefinition.allBadges.filter { it.level <= currentLevel }
        val unearned = eligible.filter { it.id !in earned }
        val nextBadge = CommunityBadgeDefinition.nextBadge(currentLevel)
        return BadgeCheckResult(
            totalEarned = eligible.size,
            totalAvailable = CommunityBadgeDefinition.allBadges.size,
            newlyEligible = unearned, nextBadge = nextBadge,
            levelsToNextBadge = nextBadge?.let { it.level - currentLevel } ?: 0
        )
    }

    // MARK: - Feature Gate (No Firestore)

    fun canAccessFeature(feature: CommunityFeatureGate, atLevel: Int): Boolean = atLevel >= feature.requiredLevel
    fun unlockedFeatures(atLevel: Int): List<CommunityFeatureGate> = CommunityFeatureGate.entries.filter { it.requiredLevel <= atLevel }
    fun nextFeatureUnlock(atLevel: Int): CommunityFeatureGate? = CommunityFeatureGate.entries.sortedBy { it.requiredLevel }.firstOrNull { it.requiredLevel > atLevel }

    // MARK: - XP from Coin Spending

    fun awardCoinSpendXP(userID: String, communityID: String, coinsSpent: Int) {
        val totalXP = coinsSpent * CommunityXPSource.SPENT_HYPE_COIN.xpAmount
        val key = "${userID}_${communityID}"
        val existing = pendingXP[key]
        if (existing != null) {
            existing.totalPending += totalXP
            repeat(coinsSpent) { existing.sources.add(CommunityXPSource.SPENT_HYPE_COIN) }
            existing.lastAddedAt = System.currentTimeMillis()
        } else {
            pendingXP[key] = PendingXPBuffer(
                userID = userID, communityID = communityID,
                totalPending = totalXP,
                sources = MutableList(coinsSpent) { CommunityXPSource.SPENT_HYPE_COIN },
                lastAddedAt = System.currentTimeMillis()
            )
        }
    }

    // MARK: - Flush Timer

    private fun startFlushTimer() {
        flushJob = serviceScope.launch {
            while (isActive) {
                delay(flushInterval)
                flushPendingXP()
            }
        }
    }

    suspend fun onAppBackground() { flushPendingXP() }

    suspend fun onLogout() {
        flushPendingXP()
        pendingXP.clear()
        flushJob?.cancel()
        _lastLevelUp.value = null
        _lastBadgeUnlock.value = null
    }

    fun destroy() {
        flushJob?.cancel()
        serviceScope.cancel()
    }
}