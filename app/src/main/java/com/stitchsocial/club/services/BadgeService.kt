/*
 * BadgeService.kt — Android port of iOS BadgeService.
 *
 * Read-only badge layer for Android:
 *   • Listens to users/{uid}/badges and exposes earned badges as a flow
 *   • Pin / unpin (max 3 pinned)
 *   • Mark as seen (clears the NEW dot)
 *   • Computes badge progress for the catalog given a user's stats
 *
 * Awards happen server-side via the onUserStatsChanged Cloud Function —
 * Android never writes badges directly. (The iOS app still does for the
 * rookie-on-signup case; Android signups will get their rookie badge via
 * the same server-side trigger as soon as the user doc lands.)
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.stitchsocial.club.foundation.BadgeCatalog
import com.stitchsocial.club.foundation.BadgeDefinition
import com.stitchsocial.club.foundation.BadgeProgress
import com.stitchsocial.club.foundation.EarnedBadge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

/** Snapshot of a user's stats used by the progress evaluator. Mirrors
 *  iOS RealUserStats — fields read from the user doc. */
data class RealUserStats(
    val clout: Int = 0,
    val hypes: Int = 0,
    val cools: Int = 0,
    val posts: Int = 0,
    val followers: Int = 0,
    val coinsGiven: Int = 0,
    val subscriptionsGiven: Int = 0,
    val subscribersEarned: Int = 0
)

class BadgeService private constructor() {

    companion object {
        val shared = BadgeService()
        private val TIER_ORDER = listOf(
            "rookie", "rising", "veteran", "influencer", "ambassador",
            "elite", "partner", "legendary", "top_creator", "founder", "co_founder"
        )
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")

    // ── In-memory state ──────────────────────────────────────────
    private val _earnedByUser = MutableStateFlow<Map<String, List<EarnedBadge>>>(emptyMap())
    val earnedByUser: StateFlow<Map<String, List<EarnedBadge>>> = _earnedByUser.asStateFlow()

    private val listeners = mutableMapOf<String, ListenerRegistration>()

    // ── Listener lifecycle ───────────────────────────────────────

    /** Start listening for a user's badges. Idempotent — re-attaches if
     *  there's no active listener for that uid. */
    fun listenForBadges(userID: String) {
        if (userID.isEmpty() || listeners.containsKey(userID)) return
        val ref = db.collection("users").document(userID).collection("badges")
        val reg = ref.addSnapshotListener { snap, err ->
            if (err != null) {
                println("🎖 BADGES: listen failed for $userID — ${err.message}")
                return@addSnapshotListener
            }
            val badges = snap?.documents.orEmpty().mapNotNull { d ->
                val id = d.getString("id") ?: d.id
                val earnedAt = (d.get("earnedAt") as? Timestamp)?.toDate() ?: Date()
                val isPinned = d.getBoolean("isPinned") ?: false
                val isNew = d.getBoolean("isNew") ?: false
                EarnedBadge(id = id, earnedAt = earnedAt, isPinned = isPinned, isNew = isNew)
            }
            _earnedByUser.value = _earnedByUser.value + (userID to badges)
        }
        listeners[userID] = reg
    }

    fun stopListening(userID: String) {
        listeners[userID]?.remove()
        listeners.remove(userID)
    }

    fun earnedBadges(userID: String): List<EarnedBadge> =
        _earnedByUser.value[userID].orEmpty()

    // ── Pin / unpin (max 3 pinned) ───────────────────────────────

    suspend fun togglePin(userID: String, badgeID: String) {
        val list = earnedBadges(userID)
        val cur = list.firstOrNull { it.id == badgeID } ?: return
        val pinnedCount = list.count { it.isPinned }
        if (!cur.isPinned && pinnedCount >= 3) return

        val newPinned = !cur.isPinned
        // Optimistic local update
        _earnedByUser.value = _earnedByUser.value + (userID to list.map {
            if (it.id == badgeID) it.copy(isPinned = newPinned) else it
        })
        try {
            db.collection("users").document(userID)
                .collection("badges").document(badgeID)
                .update("isPinned", newPinned)
                .await()
        } catch (e: Exception) {
            // Rollback on failure
            _earnedByUser.value = _earnedByUser.value + (userID to list)
            println("🎖 BADGES: pin toggle failed — ${e.message}")
        }
    }

    suspend fun markSeen(userID: String, badgeID: String) {
        val list = earnedBadges(userID)
        val cur = list.firstOrNull { it.id == badgeID } ?: return
        if (!cur.isNew) return
        _earnedByUser.value = _earnedByUser.value + (userID to list.map {
            if (it.id == badgeID) it.copy(isNew = false) else it
        })
        try {
            db.collection("users").document(userID)
                .collection("badges").document(badgeID)
                .update("isNew", false)
                .await()
        } catch (e: Exception) {
            println("🎖 BADGES: markSeen failed — ${e.message}")
        }
    }

    // ── Progress evaluator (catalog vs current stats) ────────────

    /** Per-badge progress fraction for the catalog, ignoring already-earned
     *  and manually-awarded entries. Used by the "In Progress" section of
     *  BadgePageView. */
    fun badgeProgress(
        userID: String,
        stats: RealUserStats,
        xp: Int,
        currentTier: String
    ): List<BadgeProgress> {
        val earned = earnedBadges(userID).map { it.id }.toSet()
        val results = mutableListOf<BadgeProgress>()
        for (def in BadgeCatalog.all) {
            if (def.id in earned) continue
            if (def.requirements.isManuallyAwarded) continue
            // Skip seasonal — Android doesn't track active season yet.
            if (def.requirements.seasonRequired != null) continue
            val p = computeProgress(def, stats, xp, currentTier) ?: continue
            results.add(p)
        }
        return results.sortedByDescending { it.progressFraction }
    }

    private fun computeProgress(
        def: BadgeDefinition,
        stats: RealUserStats,
        xp: Int,
        currentTier: String
    ): BadgeProgress? {
        val req = def.requirements
        val pairs = mutableListOf<Pair<Int, Int>>()
        if (req.minHypesGiven > 0)         pairs.add(stats.hypes to req.minHypesGiven)
        if (req.minCoolsGiven > 0)         pairs.add(stats.cools to req.minCoolsGiven)
        if (req.minPosts > 0)              pairs.add(stats.posts to req.minPosts)
        if (req.minFollowers > 0)          pairs.add(stats.followers to req.minFollowers)
        if (req.minClout > 0)              pairs.add(stats.clout to req.minClout)
        if (req.minCoinsGiven > 0)         pairs.add(stats.coinsGiven to req.minCoinsGiven)
        if (req.minSubscriptionsGiven > 0) pairs.add(stats.subscriptionsGiven to req.minSubscriptionsGiven)
        if (req.minSubscribersEarned > 0)  pairs.add(stats.subscribersEarned to req.minSubscribersEarned)
        if (req.minXP > 0)                 pairs.add(xp to req.minXP)

        // Tier requirement gates the badge entirely — show 0 progress
        // until they hit the tier.
        if (req.requiredTier != null) {
            val ci = TIER_ORDER.indexOf(currentTier)
            val ri = TIER_ORDER.indexOf(req.requiredTier)
            val tierMet = ci >= 0 && ri >= 0 && ci >= ri
            if (!tierMet) {
                return BadgeProgress(
                    id = def.id, definition = def,
                    progressFraction = 0f,
                    currentValue = ci.coerceAtLeast(0),
                    targetValue = ri.coerceAtLeast(0)
                )
            }
            // Tier is met but no other reqs → already qualifies; CF will
            // award it on next stats update. Don't show in "in progress".
            if (pairs.isEmpty()) return null
        }

        if (pairs.isEmpty()) return null
        val bottleneck = pairs.minByOrNull { it.first.toFloat() / it.second.toFloat() } ?: return null
        val frac = (bottleneck.first.toFloat() / bottleneck.second.toFloat()).coerceIn(0f, 1f)
        return BadgeProgress(
            id = def.id, definition = def,
            progressFraction = frac,
            currentValue = bottleneck.first,
            targetValue = bottleneck.second
        )
    }
}
