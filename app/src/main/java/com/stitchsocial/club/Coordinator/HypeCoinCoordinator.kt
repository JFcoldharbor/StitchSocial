package com.stitchsocial.club.services

import android.app.Activity
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.stitchsocial.club.firebase.FirebaseSchema
import com.stitchsocial.club.foundation.CoinError
import com.stitchsocial.club.foundation.CoinTransactionType
import com.stitchsocial.club.foundation.HypeCoinBalance
import com.stitchsocial.club.foundation.HypeCoinPackage
import com.stitchsocial.club.foundation.PayoutMethod
import com.stitchsocial.club.foundation.TipPreset
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.CashOutRequest
import com.stitchsocial.club.foundation.CoinPriceTier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date

/*
 * HypeCoinCoordinator.kt - HYPE COIN COORDINATOR
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * FIXED: Explicit imports for HypeCoinService, SubscriptionService,
 *        HypeCoinBillingManager — all in same package but listed
 *        explicitly to resolve "Unresolved reference" on some AGP versions.
 *
 * CACHING: Balance 60s TTL, real-time listener keeps fresh.
 * BATCHING: Tips batched by recipient within 2s window.
 */

class HypeCoinCoordinator private constructor(context: Context) {

    companion object {
        @Volatile private var instance: HypeCoinCoordinator? = null
        fun getInstance(context: Context): HypeCoinCoordinator =
            instance ?: synchronized(this) {
                instance ?: HypeCoinCoordinator(context.applicationContext).also { instance = it }
            }
    }

    private val coinService         = HypeCoinService.shared
    private val subscriptionService = SubscriptionService.shared
    private val billingManager      = HypeCoinBillingManager.getInstance(context)
    private val db                  = FirebaseFirestore.getInstance(FirebaseSchema.DATABASE_NAME)
    private val scope               = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _balance            = MutableStateFlow<HypeCoinBalance?>(null)
    val balance: StateFlow<HypeCoinBalance?> = _balance.asStateFlow()

    private val _isLoading          = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError          = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _showPurchaseSuccess = MutableStateFlow(false)
    val showPurchaseSuccess: StateFlow<Boolean> = _showPurchaseSuccess.asStateFlow()

    private val _lastPurchaseAmount  = MutableStateFlow(0)
    val lastPurchaseAmount: StateFlow<Int> = _lastPurchaseAmount.asStateFlow()

    private var cachedBalance: HypeCoinBalance? = null
    private var balanceLastFetched: Date?        = null
    private val balanceCacheTtlMs: Long          = 60_000L

    private data class PendingTip(
        val toUserID: String,
        val amount: Int,
        val completion: ((Boolean) -> Unit)?
    )

    private val pendingTips     = mutableListOf<PendingTip>()
    private var tipBatchJob: Job? = null
    private var lastTipTime: Long = 0L
    private val tipCooldownMs: Long = 2_000L

    private var balanceListener: ListenerRegistration? = null
    private var currentUserID: String?                 = null

    init { setupLifecycleObserver() }

    fun configure(userID: String) {
        if (currentUserID == userID) return
        balanceListener?.remove()
        currentUserID = userID
        billingManager.currentUserID = userID
        billingManager.connect()
        startBalanceListener(userID)
        scope.launch { syncBalance() }
    }

    fun disconnect() {
        balanceListener?.remove()
        balanceListener = null
        currentUserID   = null
        cachedBalance   = null
        _balance.value  = null
        tipBatchJob?.cancel()
        pendingTips.clear()
        billingManager.disconnect()
    }

    private fun setupLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch { syncBalance() }
            }
        })
    }

    private fun startBalanceListener(userID: String) {
        val docRef = db.collection(FirebaseSchema.Collections.COIN_BALANCES).document(userID)
        balanceListener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) { println("❌ COINS: Listener - ${error.message}"); return@addSnapshotListener }
            val data       = snapshot?.data ?: return@addSnapshotListener
            val newBalance = HypeCoinBalance.fromMap(userID, data)
            val cached     = cachedBalance
            if (cached != null && newBalance.availableCoins > cached.availableCoins) {
                val purchased = newBalance.availableCoins - cached.availableCoins
                scope.launch {
                    _lastPurchaseAmount.value  = purchased
                    _showPurchaseSuccess.value = true
                    delay(3_000)
                    _showPurchaseSuccess.value = false
                }
            }
            cachedBalance       = newBalance
            _balance.value      = newBalance
            balanceLastFetched  = Date()
        }
    }

    suspend fun syncBalance() {
        val userID = currentUserID ?: return
        try {
            val fresh       = coinService.fetchBalance(userID)
            cachedBalance   = fresh
            _balance.value  = fresh
            balanceLastFetched = Date()
        } catch (e: Exception) { _lastError.value = e.message }
    }

    suspend fun getBalance(): Int {
        val cached    = cachedBalance
        val lastFetch = balanceLastFetched
        if (cached != null && lastFetch != null &&
            (Date().time - lastFetch.time) < balanceCacheTtlMs) return cached.availableCoins
        syncBalance()
        return cachedBalance?.availableCoins ?: 0
    }

    suspend fun canAfford(amount: Int): Boolean = getBalance() >= amount

    fun launchPurchase(activity: Activity, pkg: HypeCoinPackage): Boolean =
        billingManager.launchPurchase(activity, pkg)

    val billingState  get() = billingManager.billingState
    val purchaseState get() = billingManager.purchaseState

    fun sendTip(toUserID: String, amount: Int, completion: ((Boolean) -> Unit)? = null) {
        val fromUserID = currentUserID ?: run { completion?.invoke(false); return }
        val now = System.currentTimeMillis()
        if (now - lastTipTime < tipCooldownMs) {
            pendingTips.add(PendingTip(toUserID, amount, completion))
            scheduleTipBatch()
            return
        }
        lastTipTime = now
        scope.launch {
            try {
                coinService.transferCoins(fromUserID, toUserID, amount, CoinTransactionType.TIP_RECEIVED)
                completion?.invoke(true)
            } catch (e: Exception) { _lastError.value = e.message; completion?.invoke(false) }
        }
    }

    fun sendQuickTip(preset: TipPreset, toUserID: String, completion: ((Boolean) -> Unit)? = null) =
        sendTip(toUserID, preset.amount, completion)

    private fun scheduleTipBatch() {
        tipBatchJob?.cancel()
        tipBatchJob = scope.launch {
            delay(tipCooldownMs)
            processPendingTips()
        }
    }

    private suspend fun processPendingTips() {
        if (pendingTips.isEmpty()) return
        val fromUserID = currentUserID ?: return
        val tips = pendingTips.toList(); pendingTips.clear()
        val grouped = mutableMapOf<String, MutableList<PendingTip>>()
        for (tip in tips) grouped.getOrPut(tip.toUserID) { mutableListOf() }.add(tip)
        for ((toUserID, recipientTips) in grouped) {
            val total = recipientTips.sumOf { it.amount }
            try {
                coinService.transferCoins(fromUserID, toUserID, total, CoinTransactionType.TIP_RECEIVED)
                recipientTips.forEach { it.completion?.invoke(true) }
            } catch (e: Exception) { recipientTips.forEach { it.completion?.invoke(false) } }
        }
        syncBalance()
        lastTipTime = System.currentTimeMillis()
    }

    suspend fun subscribe(toCreatorID: String, tier: CoinPriceTier) {
        val userID = currentUserID ?: throw CoinError.TransferFailed
        _isLoading.value = true
        try {
            subscriptionService.subscribe(userID, toCreatorID, tier)
            syncBalance()
        } finally { _isLoading.value = false }
    }

    suspend fun cancelSubscription(creatorID: String) {
        val userID = currentUserID ?: return
        subscriptionService.cancelSubscription(userID, creatorID)
    }

    suspend fun requestCashOut(amount: Int, tier: UserTier, method: PayoutMethod): CashOutRequest {
        val userID = currentUserID ?: throw CoinError.TransferFailed
        _isLoading.value = true
        try {
            val request = coinService.requestCashOut(userID, amount, tier, method)
            syncBalance()
            return request
        } finally { _isLoading.value = false }
    }
}