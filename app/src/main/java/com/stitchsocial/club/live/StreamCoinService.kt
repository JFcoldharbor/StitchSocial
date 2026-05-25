package com.stitchsocial.club.live

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.stitchsocial.club.foundation.CoinError
import com.stitchsocial.club.foundation.CoinTransactionType
import com.stitchsocial.club.services.HypeCoinService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Stream-side coin + hype manager. Mirrors iOS `StreamCoinService`.
 *
 * Responsibilities:
 *  - `sendHype` debits coins via [HypeCoinService.transferCoins] (which
 *    already handles balance validation + sender-debit + receiver-credit),
 *    writes the `hypeEvent` doc, increments the stream doc's hype/coin
 *    counters, and applies a local XP multiplier.
 *  - `listenForHypes` subscribes to the hypeEvents subcollection so the
 *    viewer + creator both surface storm alerts when others send hypes.
 *  - `onStreamEnd` clears local state.
 *
 * Not yet ported (post-launch):
 *  - PendingHypeBuffer (30s flush window — iOS batches writes for cost)
 *  - StreamCollectiveGoal (creator-set coin target with progress bar)
 *  - XP rollups (community XP per coin spent)
 */
class StreamCoinService private constructor() {

    companion object {
        @Volatile private var INSTANCE: StreamCoinService? = null
        fun getInstance(): StreamCoinService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StreamCoinService().also { INSTANCE = it }
            }

        private const val TAG = "StreamCoin"
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val coinService = HypeCoinService.shared

    // ── State ───────────────────────────────────────────────────────────────

    /// Last hype event observed in this stream — drives the floating storm
    /// alert overlay. Cleared after each alert renders + auto-hides.
    private val _lastHypeAlert = MutableStateFlow<StreamHypeEvent?>(null)
    val lastHypeAlert: StateFlow<StreamHypeEvent?> = _lastHypeAlert.asStateFlow()

    /// Local-only XP multiplier state. Mirrors iOS `activeMultiplier`.
    private val _activeMultiplier = MutableStateFlow(ActiveXPMultiplier())
    val activeMultiplier: StateFlow<ActiveXPMultiplier> = _activeMultiplier.asStateFlow()

    private var hypesListener: ListenerRegistration? = null
    private var listenStartMs: Long = 0L

    // ── Paths (must match iOS) ──────────────────────────────────────────────

    private fun hypeEventsPath(communityID: String, streamID: String) =
        "communities/$communityID/streams/$streamID/hypeEvents"

    private fun streamDoc(communityID: String, streamID: String) =
        "communities/$communityID/streams/$streamID"

    // ─────────────────────────────────────────────────────────────────────────
    // Viewer-side send. Throws CoinError.InsufficientBalance — caller can
    // catch and prompt to buy more coins.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun sendHype(
        hypeType: StreamHypeType,
        streamID: String,
        communityID: String,
        senderID: String,
        senderUsername: String,
        senderLevel: Int,
    ): StreamHypeEvent {
        Log.d(TAG, "🔥 send ${hypeType.emoji} ${hypeType.displayName} (${hypeType.coinCost} coins)")

        // 1. Coin transfer — throws if sender doesn't have enough. Receiver
        //    (communityID == creatorID) gets pending credit; the cash-out
        //    flow settles this later.
        coinService.transferCoins(
            fromUserID = senderID,
            toUserID = communityID,
            amount = hypeType.coinCost,
            type = CoinTransactionType.TIP_SENT,
        )

        // 2. Build event + write to hypeEvents subcollection
        val event = StreamHypeEvent.create(
            streamID = streamID,
            communityID = communityID,
            senderID = senderID,
            senderUsername = senderUsername,
            senderLevel = senderLevel,
            hypeType = hypeType,
        )
        runCatching {
            db.collection(hypeEventsPath(communityID, streamID))
                .document(event.id)
                .set(event.toFirestore())
                .await()
        }.onFailure { Log.w(TAG, "hypeEvent write failed: ${it.localizedMessage}") }

        // 3. Increment stream doc counters atomically
        runCatching {
            db.document(streamDoc(communityID, streamID)).update(
                mapOf(
                    "hypeCount" to FieldValue.increment(1L),
                    "totalCoinsSpent" to FieldValue.increment(hypeType.coinCost.toLong()),
                )
            ).await()
        }.onFailure { Log.w(TAG, "stream counter update failed: ${it.localizedMessage}") }

        // 4. Local effects: multiplier + alert
        _activeMultiplier.value = _activeMultiplier.value.applied(hypeType)
        _lastHypeAlert.value = event

        Log.d(TAG, "✅ ${hypeType.displayName} sent — XP multiplier now ${_activeMultiplier.value.multiplier}x")
        return event
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Subscribe to all hype events on this stream so viewers + creator see
    // alerts when ANYONE hypes. Filters out events fired before this listener
    // attached so a late-joining viewer doesn't get a flood of stale storms.
    // ─────────────────────────────────────────────────────────────────────────

    fun listenForHypes(communityID: String, streamID: String) {
        removeHypesListener()
        listenStartMs = System.currentTimeMillis()

        hypesListener = db.collection(hypeEventsPath(communityID, streamID))
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "hypes listener error: ${err.localizedMessage}")
                    return@addSnapshotListener
                }
                val docs = snap?.documents.orEmpty()
                if (docs.isEmpty()) return@addSnapshotListener
                val doc = docs.first()
                val event = StreamHypeEvent.fromDoc(doc.id, doc.data ?: emptyMap()) ?: return@addSnapshotListener
                val ts = event.createdAt.toDate().time
                // Drop events that arrived before we started listening.
                if (ts < listenStartMs - 5_000) return@addSnapshotListener
                _lastHypeAlert.value = event
            }
    }

    fun removeHypesListener() {
        hypesListener?.remove()
        hypesListener = null
    }

    fun clearLastAlert() {
        _lastHypeAlert.value = null
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    fun onStreamEnd() {
        removeHypesListener()
        _activeMultiplier.value = ActiveXPMultiplier()
        _lastHypeAlert.value = null
    }
}

/// Type alias surface to make catch blocks read naturally at the call site.
typealias InsufficientCoinsError = CoinError.InsufficientBalance
