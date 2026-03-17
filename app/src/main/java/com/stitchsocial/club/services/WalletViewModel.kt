/*
 * WalletViewModel.kt - WALLET VIEW MODEL
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Coordination — ViewModel for WalletScreen
 * Dependencies: HypeCoinCoordinator, HypeCoinService, HypeCoinBillingManager,
 *               HypeCoinModels, UserTier
 *
 * EXACT PORT: WalletView.swift (iOS) — business logic extracted into ViewModel
 *
 * CACHING:
 *   - transactions cached in-memory after first fetch (immutable — no TTL needed)
 *   - balance driven by HypeCoinCoordinator's real-time Firestore listener
 *   - productDetails cached by HypeCoinBillingManager for session lifetime
 *
 * BATCHING:
 *   - No batching here — tips go through HypeCoinCoordinator which batches them
 */

package com.stitchsocial.club.services

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    // MARK: - Dependencies

    private val coordinator = HypeCoinCoordinator.getInstance(application)
    private val coinService = HypeCoinService.shared
    private val billingManager = HypeCoinBillingManager.getInstance(application)

    // MARK: - UI State

    private val _selectedTab = MutableStateFlow(WalletTab.BALANCE)
    val selectedTab: StateFlow<WalletTab> = _selectedTab.asStateFlow()

    private val _transactions = MutableStateFlow<List<CoinTransaction>>(emptyList())
    val transactions: StateFlow<List<CoinTransaction>> = _transactions.asStateFlow()

    private val _showCashOut = MutableStateFlow(false)
    val showCashOut: StateFlow<Boolean> = _showCashOut.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _cashOutAmount = MutableStateFlow("")
    val cashOutAmount: StateFlow<String> = _cashOutAmount.asStateFlow()

    private val _cashOutSuccess = MutableStateFlow(false)
    val cashOutSuccess: StateFlow<Boolean> = _cashOutSuccess.asStateFlow()

    private val _cashOutProcessing = MutableStateFlow(false)
    val cashOutProcessing: StateFlow<Boolean> = _cashOutProcessing.asStateFlow()

    private val _transactionsLoaded = MutableStateFlow(false)
    val transactionsLoaded: StateFlow<Boolean> = _transactionsLoaded.asStateFlow()

    // MARK: - Pass-through from Coordinator / Service

    /** Live balance from Coordinator's Firestore listener */
    val balance: StateFlow<HypeCoinBalance?> = coordinator.balance

    val isLoading: StateFlow<Boolean>    = coordinator.isLoading
    val billingState                     = coordinator.billingState
    val purchaseState                    = coordinator.purchaseState
    val showPurchaseSuccess              = coordinator.showPurchaseSuccess
    val lastPurchaseAmount               = coordinator.lastPurchaseAmount

    // MARK: - Derived State

    /** All available packages with Play-formatted price strings */
    val packageDetails: List<Pair<HypeCoinPackage, String>>
        get() = HypeCoinPackage.entries.map { pkg ->
            val details = billingManager.getProductDetails(pkg)
            val priceStr = details?.oneTimePurchaseOfferDetails?.formattedPrice
                ?: "$${String.format("%.2f", pkg.price)}"  // fallback to model price
            Pair(pkg, priceStr)
        }

    // MARK: - Cash Out Derived

    fun coinAmountInt(userInput: String): Int = userInput.toIntOrNull() ?: 0

    fun isValidCashOut(userInput: String, availableCoins: Int): Boolean {
        val amount = coinAmountInt(userInput)
        return amount >= CashOutLimits.MINIMUM_COINS && amount <= availableCoins
    }

    fun cashOutBreakdown(userInput: String, tier: UserTier): Pair<Double, Double> {
        val amount = coinAmountInt(userInput)
        return SubscriptionRevenueShare.calculateCashOut(amount, tier)
    }

    // MARK: - Load

    /**
     * Initial load — balance driven by listener, transactions fetched once.
     * Matches iOS WalletView.task { await loadData() }
     */
    fun loadData(userID: String) {
        viewModelScope.launch {
            try {
                if (!_transactionsLoaded.value) {
                    val txList = coinService.fetchTransactions(userID)
                    _transactions.value    = txList
                    _transactionsLoaded.value = true
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    fun syncBalance() {
        viewModelScope.launch {
            try {
                coordinator.syncBalance()
                // Re-fetch transactions on manual refresh
                val userID = getCurrentUserID() ?: return@launch
                val txList = coinService.fetchTransactions(userID)
                _transactions.value = txList
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    // MARK: - Tab Navigation

    fun selectTab(tab: WalletTab) {
        _selectedTab.value = tab
    }

    // MARK: - Purchase (Play Billing)
    // Android-only — iOS uses web/Stripe

    fun launchPurchase(activity: Activity, pkg: HypeCoinPackage) {
        val success = coordinator.launchPurchase(activity, pkg)
        if (!success) {
            _errorMessage.value = "Purchase unavailable. Please try again."
        }
    }

    // MARK: - Cash Out

    fun updateCashOutAmount(input: String) {
        // Only allow numeric input
        if (input.all { it.isDigit() }) {
            _cashOutAmount.value = input
        }
    }

    fun showCashOutSheet() { _showCashOut.value = true }
    fun hideCashOutSheet() {
        _showCashOut.value    = false
        _cashOutAmount.value  = ""
        _cashOutSuccess.value = false
    }

    fun processCashOut(tier: UserTier) {
        val amount = coinAmountInt(_cashOutAmount.value)
        _cashOutProcessing.value = true

        viewModelScope.launch {
            try {
                coordinator.requestCashOut(
                    amount = amount,
                    tier   = tier,
                    method = PayoutMethod.BANK_TRANSFER
                )
                _cashOutSuccess.value  = true
                _cashOutProcessing.value = false
            } catch (e: Exception) {
                _errorMessage.value      = e.message
                _cashOutProcessing.value = false
            }
        }
    }

    // MARK: - Error Handling

    fun clearError() { _errorMessage.value = null }

    // MARK: - Private Helpers

    private fun getCurrentUserID(): String? {
        return balance.value?.userID
    }
}

// MARK: - Wallet Tab

enum class WalletTab(val label: String) {
    BALANCE("Balance"),
    BUY("Buy Coins"),
    HISTORY("History")
}