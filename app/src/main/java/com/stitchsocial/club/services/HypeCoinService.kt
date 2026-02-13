/*
 * HypeCoinService.kt - HYPE COIN SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Coin web purchases, transfers, cash out
 * Dependencies: HypeCoinPackage models, SubscriptionRevenueShare
 * NOTE: Coin purchases happen on stitchsocial.com (web) to avoid Google's 30% cut
 *
 * EXACT PORT: HypeCoinService.swift
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.*

class HypeCoinService private constructor() {

    companion object {
        val shared = HypeCoinService()
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")

    private object Collections {
        const val BALANCES = "coin_balances"
        const val TRANSACTIONS = "coin_transactions"
        const val CASH_OUTS = "cash_out_requests"
    }

    private val _balance = MutableStateFlow<HypeCoinBalance?>(null)
    val balance: StateFlow<HypeCoinBalance?> = _balance.asStateFlow()

    private val _transactions = MutableStateFlow<List<CoinTransaction>>(emptyList())
    val transactions: StateFlow<List<CoinTransaction>> = _transactions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // MARK: - Fetch Balance

    suspend fun fetchBalance(userID: String): HypeCoinBalance {
        val docRef = db.collection(Collections.BALANCES).document(userID)
        val doc = docRef.get().await()
        val data = doc.data

        if (data != null) {
            val bal = HypeCoinBalance(
                userID = userID,
                availableCoins = (data["availableCoins"] as? Number)?.toInt() ?: 0,
                pendingCoins = (data["pendingCoins"] as? Number)?.toInt() ?: 0,
                lifetimeEarned = (data["lifetimeEarned"] as? Number)?.toInt() ?: 0,
                lifetimeSpent = (data["lifetimeSpent"] as? Number)?.toInt() ?: 0,
                lastUpdated = (data["lastUpdated"] as? Timestamp)?.toDate() ?: Date()
            )
            _balance.value = bal
            return bal
        }

        // Create new balance
        val newBalance = HypeCoinBalance(userID = userID)
        val balData = hashMapOf<String, Any>(
            "userID" to userID,
            "availableCoins" to 0,
            "pendingCoins" to 0,
            "lifetimeEarned" to 0,
            "lifetimeSpent" to 0,
            "lastUpdated" to Timestamp.now()
        )
        docRef.set(balData).await()
        _balance.value = newBalance
        return newBalance
    }

    // MARK: - Web Purchase Verification (triggered by Cloud Function)

    suspend fun creditWebPurchase(userID: String, coins: Int, transactionID: String) {
        _isLoading.value = true
        try {
            // Verify not already credited
            val existing = db.collection(Collections.TRANSACTIONS)
                .whereEqualTo("id", transactionID)
                .get().await()
            if (!existing.isEmpty) {
                println("⚠️ COINS: Transaction already credited")
                return
            }

            val balanceRef = db.collection(Collections.BALANCES).document(userID)
            balanceRef.update(
                mapOf(
                    "availableCoins" to FieldValue.increment(coins.toLong()),
                    "lifetimeEarned" to FieldValue.increment(coins.toLong()),
                    "lastUpdated" to Timestamp.now()
                )
            ).await()

            // Record transaction
            val txData = hashMapOf<String, Any>(
                "id" to transactionID,
                "userID" to userID,
                "type" to CoinTransactionType.PURCHASE.value,
                "amount" to coins,
                "balanceAfter" to ((_balance.value?.availableCoins ?: 0) + coins),
                "description" to "Purchased $coins Hype Coins",
                "createdAt" to Timestamp.now()
            )
            db.collection(Collections.TRANSACTIONS).document(transactionID).set(txData).await()

            fetchBalance(userID)
            println("💰 COINS: Credited $coins coins from web purchase")
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Sync Balance

    suspend fun syncBalance(userID: String) {
        fetchBalance(userID)
        fetchTransactions(userID)
        println("🔄 COINS: Balance synced")
    }

    // MARK: - Transfer Coins

    suspend fun transferCoins(
        fromUserID: String,
        toUserID: String,
        amount: Int,
        type: CoinTransactionType,
        subscriptionID: String? = null
    ) {
        val senderBalance = fetchBalance(fromUserID)
        if (senderBalance.availableCoins < amount) throw CoinError.InsufficientBalance

        val senderRef = db.collection(Collections.BALANCES).document(fromUserID)
        val receiverRef = db.collection(Collections.BALANCES).document(toUserID)

        // Deduct from sender
        senderRef.update(
            mapOf(
                "availableCoins" to FieldValue.increment(-amount.toLong()),
                "lifetimeSpent" to FieldValue.increment(amount.toLong()),
                "lastUpdated" to Timestamp.now()
            )
        ).await()

        // Credit to receiver (pending)
        receiverRef.update(
            mapOf(
                "pendingCoins" to FieldValue.increment(amount.toLong()),
                "lifetimeEarned" to FieldValue.increment(amount.toLong()),
                "lastUpdated" to Timestamp.now()
            )
        ).await()

        // Record sender transaction
        val senderType = if (type == CoinTransactionType.SUBSCRIPTION_RECEIVED)
            CoinTransactionType.SUBSCRIPTION_SENT else CoinTransactionType.TIP_SENT
        val senderDesc = if (type == CoinTransactionType.SUBSCRIPTION_RECEIVED)
            "Subscription payment" else "Tip sent"

        val senderTxID = UUID.randomUUID().toString()
        val senderTxData = hashMapOf<String, Any>(
            "id" to senderTxID,
            "userID" to fromUserID,
            "type" to senderType.value,
            "amount" to -amount,
            "balanceAfter" to (senderBalance.availableCoins - amount),
            "relatedUserID" to toUserID,
            "description" to senderDesc,
            "createdAt" to Timestamp.now()
        )
        subscriptionID?.let { senderTxData["relatedSubscriptionID"] = it }
        db.collection(Collections.TRANSACTIONS).document(senderTxID).set(senderTxData).await()

        // Record receiver transaction
        val receiverDesc = if (type == CoinTransactionType.SUBSCRIPTION_RECEIVED)
            "Subscription received" else "Tip received"

        val receiverTxID = UUID.randomUUID().toString()
        val receiverTxData = hashMapOf<String, Any>(
            "id" to receiverTxID,
            "userID" to toUserID,
            "type" to type.value,
            "amount" to amount,
            "balanceAfter" to 0,
            "relatedUserID" to fromUserID,
            "description" to receiverDesc,
            "createdAt" to Timestamp.now()
        )
        subscriptionID?.let { receiverTxData["relatedSubscriptionID"] = it }
        db.collection(Collections.TRANSACTIONS).document(receiverTxID).set(receiverTxData).await()

        fetchBalance(fromUserID)
        println("💸 COINS: Transferred $amount coins from $fromUserID to $toUserID")
    }

    // MARK: - Release Pending Coins

    suspend fun releasePendingCoins(userID: String) {
        val balanceRef = db.collection(Collections.BALANCES).document(userID)
        val doc = balanceRef.get().await()
        val data = doc.data ?: return
        val pending = (data["pendingCoins"] as? Number)?.toInt() ?: 0
        if (pending <= 0) return

        balanceRef.update(
            mapOf(
                "availableCoins" to FieldValue.increment(pending.toLong()),
                "pendingCoins" to 0,
                "lastUpdated" to Timestamp.now()
            )
        ).await()

        fetchBalance(userID)
        println("✅ COINS: Released $pending pending coins")
    }

    // MARK: - Cash Out

    suspend fun requestCashOut(
        userID: String,
        amount: Int,
        tier: UserTier,
        payoutMethod: PayoutMethod
    ): CashOutRequest {
        if (amount < CashOutLimits.MINIMUM_COINS) throw CoinError.BelowMinimumCashOut

        val bal = fetchBalance(userID)
        if (bal.availableCoins < amount) throw CoinError.InsufficientBalance

        val (creatorAmount, platformAmount) = SubscriptionRevenueShare.calculateCashOut(amount, tier)

        val request = CashOutRequest(
            id = UUID.randomUUID().toString(),
            userID = userID,
            coinAmount = amount,
            userTier = tier,
            creatorPercentage = SubscriptionRevenueShare.creatorShare(tier),
            creatorAmount = creatorAmount,
            platformAmount = platformAmount,
            status = CashOutStatus.PENDING,
            payoutMethod = payoutMethod,
            createdAt = Date()
        )

        val requestData = hashMapOf<String, Any>(
            "id" to request.id,
            "userID" to userID,
            "coinAmount" to amount,
            "userTier" to tier.name,
            "creatorPercentage" to request.creatorPercentage,
            "creatorAmount" to creatorAmount,
            "platformAmount" to platformAmount,
            "status" to CashOutStatus.PENDING.value,
            "payoutMethod" to payoutMethod.value,
            "createdAt" to Timestamp.now()
        )
        db.collection(Collections.CASH_OUTS).document(request.id).set(requestData).await()

        // Deduct from balance
        db.collection(Collections.BALANCES).document(userID).update(
            mapOf(
                "availableCoins" to FieldValue.increment(-amount.toLong()),
                "lastUpdated" to Timestamp.now()
            )
        ).await()

        // Record transaction
        val sharePercent = (SubscriptionRevenueShare.creatorShare(tier) * 100).toInt()
        val txID = UUID.randomUUID().toString()
        val txData = hashMapOf<String, Any>(
            "id" to txID,
            "userID" to userID,
            "type" to CoinTransactionType.CASH_OUT.value,
            "amount" to -amount,
            "balanceAfter" to (bal.availableCoins - amount),
            "description" to "Cash out: $${String.format("%.2f", creatorAmount)} ($sharePercent%)",
            "createdAt" to Timestamp.now()
        )
        db.collection(Collections.TRANSACTIONS).document(txID).set(txData).await()

        fetchBalance(userID)
        println("💵 CASH OUT: $amount coins → $${String.format("%.2f", creatorAmount)} for ${tier.displayName}")
        return request
    }

    // MARK: - Fetch Transactions

    suspend fun fetchTransactions(userID: String, limit: Int = 50): List<CoinTransaction> {
        val snapshot = db.collection(Collections.TRANSACTIONS)
            .whereEqualTo("userID", userID)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get().await()

        val txList = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            CoinTransaction(
                id = doc.id,
                userID = data["userID"] as? String ?: userID,
                type = CoinTransactionType.fromRawValue(data["type"] as? String ?: "") ?: CoinTransactionType.PURCHASE,
                amount = (data["amount"] as? Number)?.toInt() ?: 0,
                balanceAfter = (data["balanceAfter"] as? Number)?.toInt() ?: 0,
                relatedUserID = data["relatedUserID"] as? String,
                relatedSubscriptionID = data["relatedSubscriptionID"] as? String,
                description = data["description"] as? String ?: "",
                createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date()
            )
        }

        _transactions.value = txList
        return txList
    }

    // MARK: - Check Can Afford

    suspend fun canAfford(userID: String, amount: Int): Boolean {
        val bal = fetchBalance(userID)
        return bal.availableCoins >= amount
    }
}

// MARK: - Errors

sealed class CoinError(message: String) : Exception(message) {
    object InsufficientBalance : CoinError("Not enough Hype Coins")
    object BelowMinimumCashOut : CoinError("Minimum cash out is ${CashOutLimits.MINIMUM_COINS} coins ($${CashOutLimits.MINIMUM_COINS / 100})")
    object PurchaseFailed : CoinError("Purchase failed. Please try again.")
    object TransferFailed : CoinError("Transfer failed. Please try again.")
}