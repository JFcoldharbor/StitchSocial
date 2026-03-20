package com.stitchsocial.club.services

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.CashOutLimits
import com.stitchsocial.club.foundation.CoinTransaction
import com.stitchsocial.club.foundation.HypeCoinBalance
import com.stitchsocial.club.foundation.HypeCoinPackage
import com.stitchsocial.club.foundation.PayoutMethod
import com.stitchsocial.club.foundation.SubscriptionRevenueShare
import com.stitchsocial.club.foundation.UserTier
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/*
 * WalletViewModel.kt — FIXED
 * Explicit import for HypeCoinCoordinator resolves "Unresolved reference".
 * Same package (com.stitchsocial.club.services) but AGP sometimes needs
 * explicit imports within the same package across source sets.
 */

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val coordinator    = HypeCoinCoordinator.getInstance(application)
    private val coinService    = HypeCoinService.shared
    private val billingManager = HypeCoinBillingManager.getInstance(application)

    private val _selectedTab       = MutableStateFlow(WalletTab.BALANCE)
    val selectedTab: StateFlow<WalletTab> = _selectedTab.asStateFlow()

    private val _transactions      = MutableStateFlow<List<CoinTransaction>>(emptyList())
    val transactions: StateFlow<List<CoinTransaction>> = _transactions.asStateFlow()

    private val _showCashOut       = MutableStateFlow(false)
    val showCashOut: StateFlow<Boolean> = _showCashOut.asStateFlow()

    private val _errorMessage      = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _cashOutAmount     = MutableStateFlow("")
    val cashOutAmount: StateFlow<String> = _cashOutAmount.asStateFlow()

    private val _cashOutSuccess    = MutableStateFlow(false)
    val cashOutSuccess: StateFlow<Boolean> = _cashOutSuccess.asStateFlow()

    private val _cashOutProcessing = MutableStateFlow(false)
    val cashOutProcessing: StateFlow<Boolean> = _cashOutProcessing.asStateFlow()

    private val _transactionsLoaded = MutableStateFlow(false)
    val transactionsLoaded: StateFlow<Boolean> = _transactionsLoaded.asStateFlow()

    val balance: StateFlow<HypeCoinBalance?> = coordinator.balance
    val isLoading: StateFlow<Boolean>        = coordinator.isLoading
    val billingState                         = coordinator.billingState
    val purchaseState                        = coordinator.purchaseState
    val showPurchaseSuccess                  = coordinator.showPurchaseSuccess
    val lastPurchaseAmount                   = coordinator.lastPurchaseAmount

    val packageDetails: List<Pair<HypeCoinPackage, String>>
        get() = HypeCoinPackage.entries.map { pkg ->
            val priceStr = billingManager.getProductDetails(pkg)
                ?.oneTimePurchaseOfferDetails?.formattedPrice
                ?: "$${String.format("%.2f", pkg.price)}"
            Pair(pkg, priceStr)
        }

    fun coinAmountInt(input: String): Int = input.toIntOrNull() ?: 0

    fun isValidCashOut(input: String, availableCoins: Int): Boolean {
        val amount = coinAmountInt(input)
        return amount >= CashOutLimits.MINIMUM_COINS && amount <= availableCoins
    }

    fun cashOutBreakdown(input: String, tier: UserTier): Pair<Double, Double> =
        SubscriptionRevenueShare.calculateCashOut(coinAmountInt(input), tier)

    fun loadData(userID: String) {
        viewModelScope.launch {
            try {
                if (!_transactionsLoaded.value) {
                    _transactions.value       = coinService.fetchTransactions(userID)
                    _transactionsLoaded.value = true
                }
            } catch (e: Exception) { _errorMessage.value = e.message }
        }
    }

    fun syncBalance() {
        viewModelScope.launch {
            try {
                coordinator.syncBalance()
                balance.value?.userID?.let { uid ->
                    _transactions.value = coinService.fetchTransactions(uid)
                }
            } catch (e: Exception) { _errorMessage.value = e.message }
        }
    }

    fun selectTab(tab: WalletTab)   { _selectedTab.value = tab }
    fun showCashOutSheet()          { _showCashOut.value = true }
    fun hideCashOutSheet()          { _showCashOut.value = false; _cashOutAmount.value = ""; _cashOutSuccess.value = false }
    fun updateCashOutAmount(input: String) { if (input.all { it.isDigit() }) _cashOutAmount.value = input }
    fun clearError()                { _errorMessage.value = null }

    fun launchPurchase(activity: Activity, pkg: HypeCoinPackage) {
        if (!coordinator.launchPurchase(activity, pkg))
            _errorMessage.value = "Purchase unavailable. Please try again."
    }

    fun processCashOut(tier: UserTier) {
        _cashOutProcessing.value = true
        viewModelScope.launch {
            try {
                coordinator.requestCashOut(coinAmountInt(_cashOutAmount.value), tier, PayoutMethod.BANK_TRANSFER)
                _cashOutSuccess.value  = true
            } catch (e: Exception) { _errorMessage.value = e.message }
            finally { _cashOutProcessing.value = false }
        }
    }
}

enum class WalletTab(val label: String) {
    BALANCE("Balance"), BUY("Buy Coins"), HISTORY("History")
}