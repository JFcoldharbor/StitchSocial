package com.stitchsocial.club.services

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.CoinTransaction
import com.stitchsocial.club.foundation.HypeCoinBalance
import com.stitchsocial.club.foundation.HypeCoinPackage
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

    private val _errorMessage      = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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
    fun clearError()                { _errorMessage.value = null }

    fun launchPurchase(activity: Activity, pkg: HypeCoinPackage) {
        if (!coordinator.launchPurchase(activity, pkg))
            _errorMessage.value = "Purchase unavailable. Please try again."
    }
}

enum class WalletTab(val label: String) {
    BALANCE("Balance"), BUY("Buy Coins"), HISTORY("History")
}