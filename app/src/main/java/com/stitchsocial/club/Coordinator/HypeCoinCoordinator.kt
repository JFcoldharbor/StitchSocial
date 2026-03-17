/*
 * HypeCoinCoordinator.kt - HYPE COIN COORDINATOR
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Coordination — Orchestrates HypeCoinService + HypeCoinBillingManager
 * Dependencies: HypeCoinService, HypeCoinBillingManager, SubscriptionService,
 *               HypeCoinModels, FirebaseSchema
 *
 * EXACT PORT: HypeCoinCoordinator.swift (iOS)
 *
 * ANDROID DELTA vs iOS:
 *   - ProcessLifecycleOwner replaces UIApplication foreground notifications
 *   - HypeCoinBillingManager replaces web deep link purchase detection
 *   - StateFlow replaces @Published
 *   - Coroutine scope replaces Task {}
 *   - Firestore addSnapshotListener replaces iOS equivalent (same API)
 *
 * CACHING:
 *   - Balance cached 60s TTL (balanceCacheTTL) — real-time listener keeps fresh
 *   - Cache key: "coin_balance_{userID}" (see FirebaseSchema.CacheConfiguration)
 *   - Tips batched by recipient within 2s window before single transferCoins() call
 *   - Add COIN_BALANCE to CacheType enum in CoreTypes.kt
 *
 * BATCHING:
 *   - pendingTips grouped by toUserID, flushed after tipCooldownMs
 *   - One transferCoins() call per recipient per batch — minimises Firestore writes
 */

package com.stitchsocial.club.Coordinator

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.stitchsocial.club.firebase.FirebaseSchema
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date

class HypeCoinCoordinator private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: HypeCoinCoordinator? = null

        fun getInstance(context: Context): HypeCoinCoordinator =
            instance ?: synchronized(this) {
                instance ?: HypeCoinCoordinator(context.applicationContext).also { instance = it }
            }
    }

    // MARK: - Dependencies

    private val coinService       = HypeCoinService.shared
    private val subscriptionService = SubscriptionService.shared
    private val billingManager    = HypeCoinBillingManager.getInstance(context)
    private val db                = FirebaseFirestore.getInstance(FirebaseSchema.DATABASE_NAME)
    private val scope             = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // MARK: - Published State

    private val _balance           = MutableStateFlow<HypeCoinBalance?>(null)
    val balance: StateFlow<HypeCoinBalance?> = _balance.asStateFlow()

    private val _isLoading         = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError         = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _showPurchaseSuccess = MutableStateFlow(false)
    val showPurchaseSuccess: StateFlow<Boolean> = _showPurchaseSuccess.asStateFlow()

    private val _lastPurchaseAmount  = MutableStateFlow(0)
    val lastPurchaseAmount: StateFlow<Int> = _lastPurchaseAmount.asStateFlow()

    // MARK: - Cache
    // Matches iOS HypeCoinCoordinator balanceCacheTTL = 60s

    private var cachedBalance: HypeCoinBalance? = null
    private var balanceLastFetched: Date?        = null
    private val balanceCacheTtlMs: Long          = 60_000L  // 1 minute

    // MARK: - Tip Batching
    // Matches iOS HypeCoinCoordinator pendingTips + tipCooldown

    private data class PendingTip(
        val toUserID: String,
        val amount: Int,
        val completion: ((Boolean) -> Unit)?
    )

    private val pendingTips    = mutableListOf<PendingTip>()
    private var tipBatchJob: Job? = null
    private var lastTipTime: Long  = 0L
    private val tipCooldownMs: Long = 2_000L  // 2 seconds — matches iOS

    // MARK: - Firestore Listener

    private var balanceListener: ListenerRegistration? = null
    private var currentUserID: String?                 = null

    // MARK: - Init

    init {
        setupLifecycleObserver()
    }

    // MARK: - Configure / Disconnect

    /**
     * Call after user signs in. Matches iOS HypeCoinCoordinator.configure().
     */
    fun configure(userID: String) {
        if (currentUserID == userID) return

        balanceListener?.remove()
        currentUserID = userID

        // Wire userID into billing manager so purchases can be credited
        billingManager.currentUserID = userID
        billingManager.connect()

        startBalanceListener(userID)

        scope.launch { syncBalance() }
    }

    /**
     * Call on logout. Matches iOS HypeCoinCoordinator.disconnect().
     */
    fun disconnect() {
        balanceListener?.remove()
        balanceListener  = null
        currentUserID    = null
        cachedBalance    = null
        _balance.value   = null
        tipBatchJob?.cancel()
        pendingTips.clear()
        billingManager.disconnect()
        println("🔌 COORDINATOR: Disconnected")
    }

    // MARK: - App Lifecycle (Sync on Foreground)
    // Matches iOS setupAppLifecycleObservers() / willEnterForeground

    private fun setupLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // App came to foreground
                scope.launch { syncBalance() }
            }
        })
    }

    // MARK: - Real-Time Balance Listener
    // Matches iOS HypeCoinCoordinator.startBalanceListener()
    // Detects Play Billing credits written by HypeCoinBillingManager

    private fun startBalanceListener(userID: String) {
        val docRef = db.collection(FirebaseSchema.Collections.COIN_BALANCES).document(userID)

        balanceListener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                println("❌ COINS: Listener error - ${error.message}")
                return@addSnapshotListener
            }

            val data       = snapshot?.data ?: return@addSnapshotListener
            val newBalance = HypeCoinBalance.fromMap(userID, data)

            // Detect purchase (balance increased) — matches iOS listener logic
            val cached = cachedBalance
            if (cached != null && newBalance.availableCoins > cached.availableCoins) {
                val purchased = newBalance.availableCoins - cached.availableCoins
                scope.launch {
                    _lastPurchaseAmount.value  = purchased
                    _showPurchaseSuccess.value = true
                    delay(3_000)
                    _showPurchaseSuccess.value = false
                }
                println("💰 COINS: Detected purchase of $purchased coins")
            }

            cachedBalance        = newBalance
            _balance.value       = newBalance
            balanceLastFetched   = Date()
        }
    }

    // MARK: - Balance Operations

    /**
     * Force-refresh from Firestore. Matches iOS syncBalance().
     */
    suspend fun syncBalance() {
        val userID = currentUserID ?: return
        try {
            val fresh        = coinService.fetchBalance(userID)
            cachedBalance    = fresh
            _balance.value   = fresh
            balanceLastFetched = Date()
            println("🔄 COINS: Balance synced — ${fresh.availableCoins} available")
        } catch (e: Exception) {
            _lastError.value = e.message
            println("❌ COINS: Sync failed - ${e.message}")
        }
    }

    /**
     * Return cached balance if fresh, otherwise fetch.
     * Matches iOS HypeCoinCoordinator.getBalance().
     */
    suspend fun getBalance(): Int {
        val cached     = cachedBalance
        val lastFetch  = balanceLastFetched
        if (cached != null && lastFetch != null &&
            (Date().time - lastFetch.time) < balanceCacheTtlMs) {
            return cached.availableCoins
        }
        syncBalance()
        return cachedBalance?.availableCoins ?: 0
    }

    suspend fun canAfford(amount: Int): Boolean = getBalance() >= amount

    // MARK: - Purchase (Play Billing)
    // Android-only. iOS uses web/Stripe deep link instead.

    /**
     * Launch Play Billing purchase sheet.
     * Activity reference required — call from a ViewModel or Composable with context.
     */
    fun launchPurchase(activity: android.app.Activity, pkg: HypeCoinPackage): Boolean {
        return billingManager.launchPurchase(activity, pkg)
    }

    /** Expose billing state for UI observation */
    val billingState  get() = billingManager.billingState
    val purchaseState get() = billingManager.purchaseState

    // MARK: - Tipping
    // Matches iOS HypeCoinCoordinator.sendTip() + tip batching

    fun sendTip(
        toUserID: String,
        amount: Int,
        completion: ((Boolean) -> Unit)? = null
    ) {
        val fromUserID = currentUserID
        if (fromUserID == null) {
            completion?.invoke(false)
            return
        }

        val now = System.currentTimeMillis()

        // Within cooldown window — queue for batch
        if (now - lastTipTime < tipCooldownMs) {
            pendingTips.add(PendingTip(toUserID, amount, completion))
            scheduleTipBatch()
            return
        }

        // Send immediately
        lastTipTime = now
        scope.launch {
            try {
                coinService.transferCoins(
                    fromUserID = fromUserID,
                    toUserID   = toUserID,
                    amount     = amount,
                    type       = CoinTransactionType.TIP_RECEIVED
                )
                // Don't syncBalance here — real-time listener handles UI update
                completion?.invoke(true)
                println("💸 COINS: Tipped $amount to $toUserID")
            } catch (e: Exception) {
                _lastError.value = e.message
                completion?.invoke(false)
                println("❌ COINS: Tip failed - ${e.message}")
            }
        }
    }

    fun sendQuickTip(preset: TipPreset, toUserID: String, completion: ((Boolean) -> Unit)? = null) {
        sendTip(toUserID, preset.amount, completion)
    }

    // MARK: - Tip Batching
    // Matches iOS scheduleTipBatch() + processPendingTips()

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

        val tips = pendingTips.toList()
        pendingTips.clear()

        // Group by recipient — one transferCoins() per recipient
        val grouped = mutableMapOf<String, MutableList<PendingTip>>()
        for (tip in tips) {
            grouped.getOrPut(tip.toUserID) { mutableListOf() }.add(tip)
        }

        for ((toUserID, recipientTips) in grouped) {
            val total = recipientTips.sumOf { it.amount }
            try {
                coinService.transferCoins(
                    fromUserID = fromUserID,
                    toUserID   = toUserID,
                    amount     = total,
                    type       = CoinTransactionType.TIP_RECEIVED
                )
                recipientTips.forEach { it.completion?.invoke(true) }
                println("💸 COINS: Batched tip of $total to $toUserID")
            } catch (e: Exception) {
                recipientTips.forEach { it.completion?.invoke(false) }
                println("❌ COINS: Batched tip failed - ${e.message}")
            }
        }

        syncBalance()
        lastTipTime = System.currentTimeMillis()
    }

    // MARK: - Subscriptions
    // Matches iOS HypeCoinCoordinator.subscribe() / cancelSubscription()

    suspend fun subscribe(toCreatorID: String, tier: SubscriptionTier) {
        val userID = currentUserID ?: throw CoinError.TransferFailed
        _isLoading.value = true
        try {
            subscriptionService.subscribe(
                subscriberID = userID,
                creatorID    = toCreatorID,
                tier         = tier
            )
            syncBalance()
            println("🎉 COINS: Subscribed to $toCreatorID at ${tier.displayName}")
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun cancelSubscription(creatorID: String) {
        val userID = currentUserID ?: return
        subscriptionService.cancelSubscription(
            subscriberID = userID,
            creatorID    = creatorID
        )
    }

    // MARK: - Cash Out
    // Matches iOS HypeCoinCoordinator.requestCashOut()

    suspend fun requestCashOut(
        amount: Int,
        tier: UserTier,
        method: PayoutMethod
    ): CashOutRequest {
        val userID = currentUserID ?: throw CoinError.TransferFailed
        _isLoading.value = true
        try {
            val request = coinService.requestCashOut(
                userID       = userID,
                amount       = amount,
                tier         = tier,
                payoutMethod = method
            )
            syncBalance()
            return request
        } finally {
            _isLoading.value = false
        }
    }
}