/*
 * HypeCoinService.kt - HYPE COIN FIRESTORE SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services — Coin balance, transfers, cash out, transactions
 * Dependencies: HypeCoinModel.kt, FirebaseSchema.kt, UserTier.kt
 *
 * EXACT PORT: HypeCoinService.swift (iOS)
 *
 * SERVER-OWNED LEDGER:
 *   The app never writes coin_balances or coin_transactions. Every coin
 *   movement goes through a callable that validates, moves and records it
 *   in one server transaction:
 *     - coins_transfer           — tips, subscriptions, stream gifts
 *     - coins_spend              — coin spends with no recipient (post boosts;
 *                                  Android has no boost UI yet)
 *     - verifyGooglePlayPurchase — called by HypeCoinBillingManager; the
 *                                  server checks the token with Google Play
 *                                  and credits. Client crediting is gone.
 *   The app only reads: balance (snapshot listener in HypeCoinCoordinator)
 *   and transaction history.
 *
 * ANDROID DELTA vs iOS:
 *   - No Stripe / web flow — Play Billing handles all purchases natively
 *   - Coroutine-based (suspend funs) instead of async/await
 *   - StateFlow instead of @Published
 *
 * CACHING:
 *   - Balance: DO NOT cache here. HypeCoinCoordinator owns the 60s TTL cache
 *     and real-time Firestore listener. Calling fetchBalance() directly always
 *     hits Firestore — callers should go through Coordinator.
 *   - Transactions: Cached in WalletViewModel after first fetch (immutable).
 *   - Add COIN_BALANCE to CacheType enum in CoreTypes.kt.
 *
 * BATCHING / IDEMPOTENCY:
 *   - For high-frequency tips, HypeCoinCoordinator batches by recipient before
 *     calling transferCoins() once per recipient — one callable per batch.
 *   - Every transfer/spend carries a clientTxnID, generated once per logical
 *     movement. Transient failures are retried here with the SAME id, so the
 *     server applies it at most once (it answers alreadyProcessed on a replay).
 */

package com.stitchsocial.club.services

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.stitchsocial.club.firebase.FirebaseSchema
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import com.stitchsocial.club.BuildConfig

/** What a coins_transfer is for. The server books each kind differently. */
enum class CoinTransferKind(val raw: String) {
    TIP("tip"),
    SUBSCRIPTION("subscription"),
    STREAM_GIFT("stream_gift")
}

/** Server reply to a transfer or spend. */
data class CoinMoveResult(
    val balanceAfter: Int,
    val txnID: String,
    val alreadyProcessed: Boolean
)

/** Server reply to verifyGooglePlayPurchase. */
data class PurchaseVerifyResult(
    val coins: Int,
    val balanceAfter: Int,
    val alreadyCredited: Boolean
)

class HypeCoinService private constructor() {

    companion object {
        val shared = HypeCoinService()
    }

    // MARK: - Firestore

    private val db = FirebaseFirestore.getInstance(FirebaseSchema.DATABASE_NAME)
    private val functions = FirebaseFunctions.getInstance("us-central1")

    private object Collections {
        val BALANCES     = FirebaseSchema.Collections.COIN_BALANCES
        val TRANSACTIONS = FirebaseSchema.Collections.COIN_TRANSACTIONS
    }

    // MARK: - State

    private val _balance      = MutableStateFlow<HypeCoinBalance?>(null)
    val balance: StateFlow<HypeCoinBalance?> = _balance.asStateFlow()

    private val _transactions = MutableStateFlow<List<CoinTransaction>>(emptyList())
    val transactions: StateFlow<List<CoinTransaction>> = _transactions.asStateFlow()

    private val _isLoading    = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ─────────────────────────────────────────────
    // MARK: - Fetch Balance
    // ─────────────────────────────────────────────

    /**
     * Fetch or create balance document for user.
     * EXACT MATCH: iOS HypeCoinService.fetchBalance()
     *
     * NOTE: Callers should prefer HypeCoinCoordinator.getBalance() which
     * returns cached value within TTL to avoid unnecessary Firestore reads.
     */
    suspend fun fetchBalance(userID: String): HypeCoinBalance {
        val docRef = db.collection(Collections.BALANCES).document(userID)
        val doc    = docRef.get().await()

        if (doc.exists()) {
            val data    = doc.data ?: emptyMap()
            val balance = HypeCoinBalance.fromMap(userID, data)
            _balance.value = balance
            return balance
        }

        // No document yet — the server creates it on the first credit or
        // transfer. Writing an empty one from here would be a client write
        // to a server-owned collection, so just report zero.
        val newBalance = HypeCoinBalance.empty(userID)
        _balance.value = newBalance
        return newBalance
    }

    // ─────────────────────────────────────────────
    // MARK: - Credit Purchase (Android: Play Billing)
    // ─────────────────────────────────────────────

    /**
     * Have the server verify a Google Play purchase and credit it.
     * Called by HypeCoinBillingManager BEFORE it consumes the purchase — a
     * purchase is only consumed once this returns, so a failed verify stays
     * in Play's unconsumed list and is retried on the next connect.
     *
     * SAFETY: the server keys the credit on purchaseToken and answers
     * alreadyCredited on a replay, so re-verifying is always safe.
     */
    suspend fun verifyPlayPurchase(
        userID: String,
        productId: String,
        purchaseToken: String,
        orderId: String?
    ): PurchaseVerifyResult {
        _isLoading.value = true
        try {
            val payload = mutableMapOf<String, Any>(
                "productId"     to productId,
                "purchaseToken" to purchaseToken
            )
            orderId?.takeIf { it.isNotEmpty() }?.let { payload["orderId"] = it }

            val data = callWithRetry("verifyGooglePlayPurchase", payload)
            val result = PurchaseVerifyResult(
                coins           = (data["coins"] as? Number)?.toInt() ?: 0,
                balanceAfter    = (data["balanceAfter"] as? Number)?.toInt() ?: 0,
                alreadyCredited = data["alreadyCredited"] as? Boolean ?: false
            )
            applyBalanceAfter(userID, result.balanceAfter)
            if (BuildConfig.DEBUG) { println("💰 COINS: Verified Play purchase $productId (+${result.coins}, alreadyCredited=${result.alreadyCredited})") }
            return result
        } finally {
            _isLoading.value = false
        }
    }

    // ─────────────────────────────────────────────
    // MARK: - Sync Balance
    // ─────────────────────────────────────────────

    /**
     * Force-refresh balance + transactions.
     * EXACT MATCH: iOS HypeCoinService.syncBalance()
     * Called by HypeCoinCoordinator on foreground resume.
     */
    suspend fun syncBalance(userID: String) {
        fetchBalance(userID)
        fetchTransactions(userID)
        if (BuildConfig.DEBUG) { println("🔄 COINS: Balance synced") }
    }

    // ─────────────────────────────────────────────
    // MARK: - Transfer Coins (Subscription / Tip)
    // ─────────────────────────────────────────────

    /**
     * Move coins from the signed-in user to another user via coins_transfer.
     * The server debits the sender, credits the receiver's pendingCoins (the
     * earnings bucket — not re-spendable) and writes both ledger rows.
     *
     * For kind TIP with a videoID the server also bumps videos.coinTotal and
     * sends the tip notification (with its 60s cooldown). For SUBSCRIPTION
     * and STREAM_GIFT it only moves coins — callers keep writing their own
     * subscription/stream docs and notifications.
     *
     * [clientTxnID] identifies the logical transfer. Generate it once; a
     * caller that retries the same transfer must pass the same id.
     *
     * Throws CoinError.InsufficientBalance, CoinError.CoinsFrozen or
     * CoinError.TransferFailed.
     */
    suspend fun transferCoins(
        fromUserID: String,
        toUserID: String,
        amount: Int,
        kind: CoinTransferKind,
        clientTxnID: String = UUID.randomUUID().toString(),
        videoID: String? = null,
        streamID: String? = null,
        subscriptionTier: String? = null
    ): CoinMoveResult {
        val payload = mutableMapOf<String, Any>(
            "toUserID"    to toUserID,
            "amount"      to amount,
            "kind"        to kind.raw,
            "clientTxnID" to clientTxnID
        )
        videoID?.let          { payload["videoID"] = it }
        streamID?.let         { payload["streamID"] = it }
        subscriptionTier?.let { payload["subscriptionTier"] = it }

        val data   = callWithRetry("coins_transfer", payload)
        val result = parseMoveResult(data)
        applyBalanceAfter(fromUserID, result.balanceAfter)
        if (BuildConfig.DEBUG) { println("💸 COINS: ${kind.raw} $amount → $toUserID (txn ${result.txnID}, replay=${result.alreadyProcessed})") }
        return result
    }

    // ─────────────────────────────────────────────
    // MARK: - Spend Coins (no recipient)
    // ─────────────────────────────────────────────

    /**
     * Spend coins on something that isn't another user, via coins_spend.
     * Only reason the server accepts today is "post_boost". Android has no
     * boost purchase UI yet — this is here so one can be added without a
     * client write.
     */
    suspend fun spendCoins(
        userID: String,
        amount: Int,
        reason: String = "post_boost",
        clientTxnID: String = UUID.randomUUID().toString(),
        videoID: String? = null
    ): CoinMoveResult {
        val payload = mutableMapOf<String, Any>(
            "amount"      to amount,
            "reason"      to reason,
            "clientTxnID" to clientTxnID
        )
        videoID?.let { payload["videoID"] = it }

        val data   = callWithRetry("coins_spend", payload)
        val result = parseMoveResult(data)
        applyBalanceAfter(userID, result.balanceAfter)
        return result
    }

    // ─────────────────────────────────────────────
    // MARK: - Fetch Transactions
    // ─────────────────────────────────────────────

    /**
     * Fetch last 50 transactions for user.
     * EXACT MATCH: iOS HypeCoinService.fetchTransactions()
     *
     * CACHING: WalletViewModel caches these after first fetch.
     * Transactions are immutable — no TTL needed.
     */
    suspend fun fetchTransactions(userID: String, limit: Int = 50): List<CoinTransaction> {
        val snapshot = db.collection(Collections.TRANSACTIONS)
            .whereEqualTo(FirebaseSchema.CoinTransactionDocument.USER_ID, userID)
            .orderBy(FirebaseSchema.CoinTransactionDocument.CREATED_AT, com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get().await()

        val txList = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            CoinTransaction.fromMap(doc.id, data)
        }

        _transactions.value = txList
        return txList
    }

    // ─────────────────────────────────────────────
    // MARK: - Can Afford
    // ─────────────────────────────────────────────

    /**
     * Check if user can afford an amount.
     * EXACT MATCH: iOS HypeCoinService.canAfford()
     * Prefer HypeCoinCoordinator.canAfford() which uses cached balance.
     */
    suspend fun canAfford(userID: String, amount: Int): Boolean {
        val balance = fetchBalance(userID)
        return balance.availableCoins >= amount
    }

    // ─────────────────────────────────────────────
    // MARK: - Private Helpers
    // ─────────────────────────────────────────────

    /**
     * Call a coin callable, retrying transient failures (network, timeout)
     * with the identical payload — the clientTxnID / purchaseToken inside it
     * makes the replay a no-op on the server if the first call landed.
     * Server refusals are mapped to CoinError and never retried.
     */
    private suspend fun callWithRetry(name: String, payload: Map<String, Any>): Map<String, Any?> {
        var attempt = 0
        while (true) {
            try {
                val result = functions.getHttpsCallable(name).call(payload).await()
                @Suppress("UNCHECKED_CAST")
                val data = result.data as? Map<String, Any?> ?: emptyMap()
                if (data["ok"] != true) throw CoinError.TransferFailed
                return data
            } catch (e: FirebaseFunctionsException) {
                val transient = e.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                    e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED
                if (transient && attempt < 2) {
                    attempt++
                    delay(500L * attempt)
                    continue
                }
                if (BuildConfig.DEBUG) { println("❌ COINS: $name failed — ${e.code} ${e.message}") }
                throw mapCallableError(e)
            }
        }
    }

    private fun mapCallableError(e: FirebaseFunctionsException): CoinError {
        val message = e.message.orEmpty()
        return when {
            message.contains("insufficient_coins") -> CoinError.InsufficientBalance
            message.contains("coins_frozen")       -> CoinError.CoinsFrozen
            e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION -> CoinError.InsufficientBalance
            else -> CoinError.TransferFailed
        }
    }

    private fun parseMoveResult(data: Map<String, Any?>) = CoinMoveResult(
        balanceAfter     = (data["balanceAfter"] as? Number)?.toInt() ?: 0,
        txnID            = data["txnID"] as? String ?: "",
        alreadyProcessed = data["alreadyProcessed"] as? Boolean ?: false
    )

    /** Reflect the server's post-move balance locally until the listener catches up. */
    private fun applyBalanceAfter(userID: String, balanceAfter: Int) {
        val current = _balance.value
        _balance.value = if (current != null && current.userID == userID)
            current.copy(availableCoins = balanceAfter)
        else
            HypeCoinBalance(userID = userID, availableCoins = balanceAfter)
    }
}
