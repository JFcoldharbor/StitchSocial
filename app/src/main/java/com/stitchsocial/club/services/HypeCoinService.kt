/*
 * HypeCoinService.kt - HYPE COIN FIRESTORE SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services — Coin balance, transfers, cash out, transactions
 * Dependencies: HypeCoinModel.kt, FirebaseSchema.kt, UserTier.kt
 *
 * EXACT PORT: HypeCoinService.swift (iOS)
 *
 * ANDROID DELTA vs iOS:
 *   - creditPurchase() replaces creditWebPurchase() — called by HypeCoinBillingManager
 *     after Google Play purchase is verified, not by web webhook
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
 * BATCHING:
 *   - transferCoins() uses two sequential updateData() calls (debit + credit).
 *     For high-frequency tips, HypeCoinCoordinator batches by recipient before
 *     calling transferCoins() once per recipient — reduces writes.
 *   - creditPurchase() uses a duplicate-check query then two writes — safe for
 *     Play Billing since purchase tokens are unique per transaction.
 *   - All balance mutations use FieldValue.increment — no read-then-write.
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.firebase.FirebaseSchema
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

class HypeCoinService private constructor() {

    companion object {
        val shared = HypeCoinService()
    }

    // MARK: - Firestore

    private val db = FirebaseFirestore.getInstance(FirebaseSchema.DATABASE_NAME)

    private object Collections {
        val BALANCES     = FirebaseSchema.Collections.COIN_BALANCES
        val TRANSACTIONS = FirebaseSchema.Collections.COIN_TRANSACTIONS
        val CASH_OUTS    = FirebaseSchema.Collections.CASH_OUT_REQUESTS
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

        // Create new balance document if missing — matches iOS behavior
        val newBalance = HypeCoinBalance.empty(userID)
        docRef.set(newBalance.toMap()).await()
        _balance.value = newBalance
        return newBalance
    }

    // ─────────────────────────────────────────────
    // MARK: - Credit Purchase (Android: Play Billing)
    // ─────────────────────────────────────────────

    /**
     * Credit coins after a verified Google Play purchase.
     * Called by HypeCoinBillingManager once Play Billing reports SUCCESS
     * and the purchase token has been acknowledged.
     *
     * iOS equivalent: creditWebPurchase() — triggered by Stripe webhook.
     * Android: triggered in-app by Play Billing purchase callback.
     *
     * SAFETY: Duplicate check on purchaseToken prevents double-crediting
     * if the callback fires more than once (Play Billing can replay).
     *
     * BATCHING: Two Firestore writes (balance increment + transaction record).
     * FieldValue.increment avoids a read-then-write on balance.
     */
    suspend fun creditPurchase(
        userID: String,
        coins: Int,
        purchaseToken: String,      // Google Play purchase token (unique per transaction)
        packageRawValue: String     // HypeCoinPackage.rawValue for description
    ) {
        _isLoading.value = true
        try {
            // Duplicate check — purchaseToken used as transaction ID
            val existing = db.collection(Collections.TRANSACTIONS)
                .whereEqualTo(FirebaseSchema.CoinTransactionDocument.ID, purchaseToken)
                .get().await()

            if (!existing.isEmpty) {
                println("⚠️ COINS: Purchase token already credited: $purchaseToken")
                return
            }

            val balanceRef = db.collection(Collections.BALANCES).document(userID)

            // Increment balance — FieldValue.increment = no read needed
            balanceRef.update(
                mapOf(
                    FirebaseSchema.CoinBalanceDocument.AVAILABLE_COINS to FieldValue.increment(coins.toLong()),
                    FirebaseSchema.CoinBalanceDocument.LIFETIME_EARNED  to FieldValue.increment(coins.toLong()),
                    FirebaseSchema.CoinBalanceDocument.LAST_UPDATED     to Timestamp.now()
                )
            ).await()

            // Record transaction
            val transaction = buildTransactionMap(
                id          = purchaseToken,
                userID      = userID,
                type        = CoinTransactionType.PURCHASE,
                amount      = coins,
                balanceAfter = (_balance.value?.availableCoins ?: 0) + coins,
                description = "Purchased $coins Hype Coins ($packageRawValue)"
            )
            db.collection(Collections.TRANSACTIONS)
                .document(purchaseToken)
                .set(transaction).await()

            // Refresh published state
            fetchBalance(userID)
            println("💰 COINS: Credited $coins coins from Play purchase $purchaseToken")

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
        println("🔄 COINS: Balance synced")
    }

    // ─────────────────────────────────────────────
    // MARK: - Transfer Coins (Subscription / Tip)
    // ─────────────────────────────────────────────

    /**
     * Debit sender, credit receiver (as pending).
     * EXACT MATCH: iOS HypeCoinService.transferCoins()
     *
     * BATCHING: HypeCoinCoordinator batches rapid tips by recipient before
     * calling this — so this function runs once per recipient per batch window.
     *
     * IMPORTANT: Two sequential writes (no Firestore transaction) matches iOS.
     * For true atomicity a Firestore transaction or Cloud Function would be
     * needed — acceptable for tip/sub flows where eventual consistency is fine.
     */
    suspend fun transferCoins(
        fromUserID: String,
        toUserID: String,
        amount: Int,
        type: CoinTransactionType,
        subscriptionID: String? = null
    ) {
        // Validate balance first — 1 read
        val senderBalance = fetchBalance(fromUserID)
        if (senderBalance.availableCoins < amount) {
            throw CoinError.InsufficientBalance
        }

        val senderRef   = db.collection(Collections.BALANCES).document(fromUserID)
        val receiverRef = db.collection(Collections.BALANCES).document(toUserID)

        // Deduct from sender
        senderRef.update(
            mapOf(
                FirebaseSchema.CoinBalanceDocument.AVAILABLE_COINS to FieldValue.increment(-amount.toLong()),
                FirebaseSchema.CoinBalanceDocument.LIFETIME_SPENT  to FieldValue.increment(amount.toLong()),
                FirebaseSchema.CoinBalanceDocument.LAST_UPDATED    to Timestamp.now()
            )
        ).await()

        // Credit receiver as pending — matches iOS behavior
        receiverRef.update(
            mapOf(
                FirebaseSchema.CoinBalanceDocument.PENDING_COINS   to FieldValue.increment(amount.toLong()),
                FirebaseSchema.CoinBalanceDocument.LIFETIME_EARNED to FieldValue.increment(amount.toLong()),
                FirebaseSchema.CoinBalanceDocument.LAST_UPDATED    to Timestamp.now()
            )
        ).await()

        // Sender transaction record
        val senderTxType = if (type == CoinTransactionType.SUBSCRIPTION_RECEIVED)
            CoinTransactionType.SUBSCRIPTION_SENT else CoinTransactionType.TIP_SENT

        val senderTxMap = buildTransactionMap(
            id           = UUID.randomUUID().toString(),
            userID       = fromUserID,
            type         = senderTxType,
            amount       = -amount,
            balanceAfter = senderBalance.availableCoins - amount,
            relatedUserID = toUserID,
            relatedSubscriptionID = subscriptionID,
            description  = if (type == CoinTransactionType.SUBSCRIPTION_RECEIVED) "Subscription payment" else "Tip sent"
        )
        db.collection(Collections.TRANSACTIONS)
            .document(senderTxMap[FirebaseSchema.CoinTransactionDocument.ID] as String)
            .set(senderTxMap).await()

        // Receiver transaction record
        val receiverTxMap = buildTransactionMap(
            id           = UUID.randomUUID().toString(),
            userID       = toUserID,
            type         = type,
            amount       = amount,
            balanceAfter = 0, // Will resolve on next fetchBalance
            relatedUserID = fromUserID,
            relatedSubscriptionID = subscriptionID,
            description  = if (type == CoinTransactionType.SUBSCRIPTION_RECEIVED) "Subscription received" else "Tip received"
        )
        db.collection(Collections.TRANSACTIONS)
            .document(receiverTxMap[FirebaseSchema.CoinTransactionDocument.ID] as String)
            .set(receiverTxMap).await()

        fetchBalance(fromUserID)
        println("💸 COINS: Transferred $amount coins from $fromUserID to $toUserID")
    }

    // ─────────────────────────────────────────────
    // MARK: - Release Pending Coins
    // ─────────────────────────────────────────────

    /**
     * Move pending coins to available.
     * EXACT MATCH: iOS HypeCoinService.releasePendingCoins()
     * Called by Cloud Function / admin on payout cycle.
     */
    suspend fun releasePendingCoins(userID: String) {
        val balanceRef = db.collection(Collections.BALANCES).document(userID)
        val doc        = balanceRef.get().await()
        val data       = doc.data ?: return
        val pending    = (data[FirebaseSchema.CoinBalanceDocument.PENDING_COINS] as? Number)?.toInt() ?: 0

        if (pending <= 0) return

        balanceRef.update(
            mapOf(
                FirebaseSchema.CoinBalanceDocument.AVAILABLE_COINS to FieldValue.increment(pending.toLong()),
                FirebaseSchema.CoinBalanceDocument.PENDING_COINS   to 0,
                FirebaseSchema.CoinBalanceDocument.LAST_UPDATED    to Timestamp.now()
            )
        ).await()

        fetchBalance(userID)
        println("✅ COINS: Released $pending pending coins for $userID")
    }

    // ─────────────────────────────────────────────
    // MARK: - Cash Out
    // ─────────────────────────────────────────────

    /**
     * Submit a cash-out request.
     * EXACT MATCH: iOS HypeCoinService.requestCashOut()
     *
     * Auto-fetches custom revenue share fields from user doc if not provided
     * (1 extra read) — matches iOS behavior to avoid requiring caller to pass them.
     *
     * CACHING: No caching on cash-out requests — low frequency, always fresh.
     */
    suspend fun requestCashOut(
        userID: String,
        amount: Int,
        tier: UserTier,
        payoutMethod: PayoutMethod,
        customSubShare: Double? = null,
        customSubShareExpiresAt: Date? = null,
        customSubSharePermanent: Boolean = false,
        referralCount: Int = 0,
        referralGoal: Int? = null
    ): CashOutRequest {
        if (amount < CashOutLimits.MINIMUM_COINS) throw CoinError.BelowMinimumCashOut

        val balance = fetchBalance(userID)
        if (balance.availableCoins < amount) throw CoinError.InsufficientBalance

        // Auto-fetch custom share fields if not provided — 1 read, saves caller
        var resolvedShare     = customSubShare
        var resolvedExpiresAt = customSubShareExpiresAt
        var resolvedPermanent = customSubSharePermanent
        var resolvedRefCount  = referralCount
        var resolvedRefGoal   = referralGoal

        if (customSubShare == null) {
            val userDoc = db.collection(FirebaseSchema.Collections.USERS).document(userID).get().await()
            val data    = userDoc.data
            if (data != null) {
                resolvedShare     = data[FirebaseSchema.UserDocument.CUSTOM_SUB_SHARE] as? Double
                resolvedExpiresAt = (data[FirebaseSchema.UserDocument.CUSTOM_SUB_SHARE_EXPIRES_AT] as? Timestamp)?.toDate()
                resolvedPermanent = data[FirebaseSchema.UserDocument.CUSTOM_SUB_SHARE_PERMANENT] as? Boolean ?: false
                resolvedRefCount  = (data[FirebaseSchema.UserDocument.REFERRAL_COUNT] as? Number)?.toInt() ?: 0
                resolvedRefGoal   = (data[FirebaseSchema.UserDocument.REFERRAL_GOAL] as? Number)?.toInt()
            }
        }

        val effectiveShare = SubscriptionRevenueShare.effectiveCreatorShare(
            tier                    = tier,
            customSubShare          = resolvedShare,
            customSubShareExpiresAt = resolvedExpiresAt,
            customSubSharePermanent = resolvedPermanent,
            referralCount           = resolvedRefCount,
            referralGoal            = resolvedRefGoal
        )

        val totalValue      = HypeCoinValue.toDollars(amount)
        val creatorAmount   = totalValue * effectiveShare
        val platformAmount  = totalValue * (1.0 - effectiveShare)

        val requestID = UUID.randomUUID().toString()
        val request   = CashOutRequest(
            id                = requestID,
            userID            = userID,
            coinAmount        = amount,
            userTier          = tier,
            creatorPercentage = effectiveShare,
            creatorAmount     = creatorAmount,
            platformAmount    = platformAmount,
            status            = CashOutStatus.PENDING,
            payoutMethod      = payoutMethod,
            createdAt         = Date()
        )

        // Save request
        db.collection(Collections.CASH_OUTS)
            .document(requestID)
            .set(request.toMap()).await()

        // Deduct from balance
        db.collection(Collections.BALANCES).document(userID).update(
            mapOf(
                FirebaseSchema.CoinBalanceDocument.AVAILABLE_COINS to FieldValue.increment(-amount.toLong()),
                FirebaseSchema.CoinBalanceDocument.LAST_UPDATED    to Timestamp.now()
            )
        ).await()

        // Record transaction
        val txMap = buildTransactionMap(
            id           = UUID.randomUUID().toString(),
            userID       = userID,
            type         = CoinTransactionType.CASH_OUT,
            amount       = -amount,
            balanceAfter = balance.availableCoins - amount,
            description  = "Cash out: \$${String.format("%.2f", creatorAmount)} (${(effectiveShare * 100).toInt()}%)"
        )
        db.collection(Collections.TRANSACTIONS)
            .document(txMap[FirebaseSchema.CoinTransactionDocument.ID] as String)
            .set(txMap).await()

        fetchBalance(userID)
        println("💵 CASH OUT: $amount coins → \$${String.format("%.2f", creatorAmount)} for ${tier.displayName}")
        return request
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

    private fun buildTransactionMap(
        id: String,
        userID: String,
        type: CoinTransactionType,
        amount: Int,
        balanceAfter: Int,
        relatedUserID: String? = null,
        relatedSubscriptionID: String? = null,
        description: String
    ): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            FirebaseSchema.CoinTransactionDocument.ID           to id,
            FirebaseSchema.CoinTransactionDocument.USER_ID      to userID,
            FirebaseSchema.CoinTransactionDocument.TYPE         to type.rawValue,
            FirebaseSchema.CoinTransactionDocument.AMOUNT       to amount,
            FirebaseSchema.CoinTransactionDocument.BALANCE_AFTER to balanceAfter,
            FirebaseSchema.CoinTransactionDocument.DESCRIPTION  to description,
            FirebaseSchema.CoinTransactionDocument.CREATED_AT   to Timestamp.now()
        )
        relatedUserID?.let         { map[FirebaseSchema.CoinTransactionDocument.RELATED_USER_ID]          = it }
        relatedSubscriptionID?.let { map[FirebaseSchema.CoinTransactionDocument.RELATED_SUBSCRIPTION_ID]  = it }
        return map
    }
}

// ─────────────────────────────────────────────
// MARK: - HypeCoinBalance toMap extension
// ─────────────────────────────────────────────

private fun HypeCoinBalance.toMap(): Map<String, Any> = mapOf(
    FirebaseSchema.CoinBalanceDocument.USER_ID         to userID,
    FirebaseSchema.CoinBalanceDocument.AVAILABLE_COINS to availableCoins,
    FirebaseSchema.CoinBalanceDocument.PENDING_COINS   to pendingCoins,
    FirebaseSchema.CoinBalanceDocument.LIFETIME_EARNED to lifetimeEarned,
    FirebaseSchema.CoinBalanceDocument.LIFETIME_SPENT  to lifetimeSpent,
    FirebaseSchema.CoinBalanceDocument.LAST_UPDATED    to Timestamp(lastUpdated)
)