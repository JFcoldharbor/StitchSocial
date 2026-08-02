/*
 * WalletView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Hype Coins Wallet
 * Mirrors: WalletView.swift (iOS) — 3 tabs: Balance, Buy Coins, History
 * Dependencies: WalletViewModel, HypeCoinCoordinator, HypeCoinBillingManager
 *
 * ANDROID DELTA vs iOS:
 *   - Buy tab uses Google Play Billing (not web/Stripe)
 *   - WalletViewModel drives all state via StateFlow
 *   - Pull-to-refresh via SwipeRefresh
 *
 * CACHING (see CachingOptimization file):
 *   - Balance: HypeCoinCoordinator real-time listener — zero polling
 *   - Transactions: fetched once on open, cached in WalletViewModel
 *   - Package prices: loaded from Play Billing on connect, cached in BillingManager
 */

package com.stitchsocial.club.views

import com.stitchsocial.club.ui.theme.AppTheme
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stitchsocial.club.foundation.CoinTransaction
import com.stitchsocial.club.foundation.CoinTransactionType
import com.stitchsocial.club.foundation.HypeCoinPackage
import com.stitchsocial.club.foundation.SubscriptionRevenueShare
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.services.BillingState
import com.stitchsocial.club.services.PurchaseState
import com.stitchsocial.club.services.WalletTab
import com.stitchsocial.club.services.WalletViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun WalletView(
    userID: String,
    userTier: UserTier,
    onDismiss: () -> Unit,
    vm: WalletViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val selectedTab by vm.selectedTab.collectAsState()
    val balance by vm.balance.collectAsState()
    val transactions by vm.transactions.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val billingState by vm.billingState.collectAsState()
    val purchaseState by vm.purchaseState.collectAsState()
    val showPurchaseSuccess by vm.showPurchaseSuccess.collectAsState()
    val lastPurchaseAmount by vm.lastPurchaseAmount.collectAsState()

    // Load data on open — mirrors iOS .task { await loadData() }
    LaunchedEffect(userID) {
        vm.loadData(userID)
    }

    // Purchase state error handling
    val purchaseError = when (val ps = purchaseState) {
        is PurchaseState.Failed -> ps.message
        else -> null
    }

    Box(modifier = Modifier.fillMaxSize().background(AppTheme.colors.bg)) {

        Column(modifier = Modifier.fillMaxSize()) {

            // Top bar — mirrors iOS navigationTitle + refresh button
            WalletTopBar(
                isLoading = isLoading,
                onDismiss = onDismiss,
                onRefresh = { vm.syncBalance() }
            )

            // Balance header — always visible, mirrors iOS balanceHeader
            balance?.let { bal ->
                WalletBalanceHeader(
                    availableCoins = bal.availableCoins,
                    pendingCoins = bal.pendingCoins
                )
            } ?: run {
                WalletBalanceHeader(availableCoins = 0, pendingCoins = 0)
            }

            // Tab bar — mirrors iOS tabBar
            WalletTabBar(
                selectedTab = selectedTab,
                onTabSelected = { vm.selectTab(it) }
            )

            // Tab content
            when (selectedTab) {
                WalletTab.BALANCE -> BalanceTab(
                    balance = balance,
                    userTier = userTier,
                    onBuyTap = { vm.selectTab(WalletTab.BUY) }
                )
                WalletTab.BUY -> BuyCoinsTab(
                    packageDetails = vm.packageDetails,
                    billingState = billingState,
                    purchaseState = purchaseState,
                    onPurchase = { pkg ->
                        if (activity != null) vm.launchPurchase(activity, pkg)
                        else vm.clearError()
                    }
                )
                WalletTab.HISTORY -> HistoryTab(transactions = transactions)
            }
        }

        // Purchase success overlay — mirrors iOS coordinator showPurchaseSuccess
        if (showPurchaseSuccess) {
            PurchaseSuccessBanner(coinAmount = lastPurchaseAmount)
        }
    }

    // Error dialogs
    val errorToShow = errorMessage ?: purchaseError
    if (errorToShow != null) {
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title = { Text("Error", color = AppTheme.colors.textPrimary) },
            text = { Text(errorToShow, color = AppTheme.colors.textSecondary) },
            confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("OK", color = Color.Cyan) } },
            containerColor = Color(0xFF1A1A1A)
        )
    }

}

// MARK: - Top Bar

@Composable
private fun WalletTopBar(isLoading: Boolean, onDismiss: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(AppTheme.colors.bg).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Refresh — mirrors iOS leading navigationBarItem
        IconButton(onClick = onRefresh) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.Cyan)
            }
        }

        Text("Hype Coins", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary)

        TextButton(onClick = onDismiss) {
            Text("Done", color = Color.Cyan, fontWeight = FontWeight.SemiBold)
        }
    }
}

// MARK: - Balance Header

@Composable
private fun WalletBalanceHeader(availableCoins: Int, pendingCoins: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Coin icon — mirrors iOS HypeCoinView(size: 90)
        Box(
            modifier = Modifier.size(90.dp).background(
                Brush.radialGradient(listOf(Color(0xFFFFD700), Color(0xFFFF8C00))),
                CircleShape
            ),
            contentAlignment = Alignment.Center
        ) {
            Text("🔥", fontSize = 44.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "$availableCoins",
            fontSize = 40.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary,
            fontFamily = FontFamily.Default
        )

        Text("Hype Coins", fontSize = 15.sp, color = AppTheme.colors.textSecondary)

        if (pendingCoins > 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(13.dp))
                // "pending" alone reads as "briefly delayed". These are earned
                // coins held for payout, and cash-out isn't shipped yet — say
                // so rather than implying they'll land in the spendable balance.
                Text("$pendingCoins earned · pending payout", fontSize = 13.sp, color = Color.Yellow)
            }
        }
    }
}

// MARK: - Tab Bar

@Composable
private fun WalletTabBar(selectedTab: WalletTab, onTabSelected: (WalletTab) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(AppTheme.colors.bg)) {
        WalletTab.values().forEach { tab ->
            val isSelected = tab == selectedTab
            Column(
                modifier = Modifier.weight(1f).clickable { onTabSelected(tab) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    tab.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) Color.White else AppTheme.colors.textSecondary,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                Box(
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                        .background(if (isSelected) Color.Yellow else Color.Transparent)
                )
            }
        }
    }
}

// MARK: - Balance Tab

@Composable
private fun BalanceTab(
    balance: com.stitchsocial.club.foundation.HypeCoinBalance?,
    userTier: UserTier,
    onBuyTap: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Quick actions — mirrors iOS quickActionButton row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickActionButton("Buy", Icons.Default.AddCircle, Color.Green, Modifier.weight(1f), onBuyTap)
        }

        // Stats card — mirrors iOS statRow section
        if (balance != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AppTheme.colors.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatRow("Lifetime Earned", "${balance.lifetimeEarned}")
                    Divider(color = AppTheme.colors.textSecondary.copy(alpha = 0.2f))
                    StatRow("Lifetime Spent", "${balance.lifetimeSpent}")
                    Divider(color = AppTheme.colors.textSecondary.copy(alpha = 0.2f))
                    StatRow("Your Tier", userTier.displayName, valueColor = Color(0xFF9C27B0))
                    Divider(color = AppTheme.colors.textSecondary.copy(alpha = 0.2f))
                    StatRow(
                        "Cash Out Rate",
                        "${(SubscriptionRevenueShare.creatorShare(userTier) * 100).toInt()}%",
                        valueColor = Color.Green
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = AppTheme.colors.surface
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Text(title, fontSize = 13.sp, color = AppTheme.colors.textPrimary)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 15.sp, color = AppTheme.colors.textSecondary)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

// MARK: - Buy Coins Tab

@Composable
private fun BuyCoinsTab(
    packageDetails: List<Pair<HypeCoinPackage, String>>,
    billingState: BillingState,
    purchaseState: PurchaseState,
    onPurchase: (HypeCoinPackage) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Billing status banner
        when (billingState) {
            is BillingState.Disconnected ->
                BillingStatusBanner("Connecting to Play Store...", Color.Yellow)
            is BillingState.Error ->
                BillingStatusBanner("Store unavailable: ${billingState.message}", Color.Red)
            else -> {}
        }

        // Purchase state banner
        when (purchaseState) {
            is PurchaseState.Purchasing ->
                BillingStatusBanner("Purchasing ${purchaseState.pkg.displayName}...", Color.Cyan)
            is PurchaseState.Pending ->
                BillingStatusBanner("Purchase pending — check Play Store", Color.Yellow)
            is PurchaseState.Success ->
                BillingStatusBanner("✅ Purchase complete!", Color.Green)
            is PurchaseState.Failed ->
                BillingStatusBanner("❌ ${purchaseState.message}", Color.Red)
            else -> {}
        }

        // Packages — mirrors iOS CoinPackageDisplayCard with buy button
        Text(
            "Available Packages",
            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary
        )

        packageDetails.forEach { (pkg, priceStr) ->
            CoinPackageCard(
                pkg = pkg,
                priceStr = priceStr,
                isPurchasing = purchaseState is PurchaseState.Purchasing &&
                        (purchaseState as PurchaseState.Purchasing).pkg == pkg,
                billingReady = billingState is BillingState.Connected,
                onBuy = { onPurchase(pkg) }
            )
        }

        Text(
            "Purchases are processed securely via Google Play. Your balance updates automatically.",
            fontSize = 12.sp, color = AppTheme.colors.textSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun CoinPackageCard(
    pkg: HypeCoinPackage,
    priceStr: String,
    isPurchasing: Boolean,
    billingReady: Boolean,
    onBuy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AppTheme.colors.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Coin icon
            Box(
                modifier = Modifier.size(44.dp).background(
                    Brush.radialGradient(listOf(Color(0xFFFFD700), Color(0xFFFF8C00))),
                    CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Text("🔥", fontSize = 20.sp)
            }

            // Coin info
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(pkg.displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                Text("${pkg.coins} coins", fontSize = 13.sp, color = AppTheme.colors.textSecondary)
            }

            // Buy button
            Button(
                onClick = onBuy,
                enabled = billingReady && !isPurchasing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFD700),
                    disabledContainerColor = AppTheme.colors.textSecondary.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (isPurchasing) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(priceStr, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun BillingStatusBanner(message: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(message, fontSize = 13.sp, color = color, modifier = Modifier.weight(1f))
    }
}

// MARK: - History Tab

@Composable
private fun HistoryTab(transactions: List<CoinTransaction>) {
    if (transactions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.History, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(48.dp))
                Text("No transactions yet", fontSize = 16.sp, color = AppTheme.colors.textSecondary)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(transactions) { transaction ->
                TransactionRow(transaction = transaction)
            }
        }
    }
}

@Composable
private fun TransactionRow(transaction: CoinTransaction) {
    val isCredit = transaction.amount > 0
    val color = if (isCredit) Color.Green else Color.Red
    val icon = if (isCredit) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward
    val sdf = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(color.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(transaction.description, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppTheme.colors.textPrimary)
            Text(sdf.format(transaction.createdAt), fontSize = 12.sp, color = AppTheme.colors.textSecondary)
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${if (isCredit) "+" else ""}${transaction.amount}",
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color,
                fontFamily = FontFamily.Monospace
            )
            Text("${transaction.balanceAfter}", fontSize = 11.sp, color = AppTheme.colors.textSecondary.copy(alpha = 0.7f))
        }
    }

    Divider(color = AppTheme.colors.textSecondary.copy(alpha = 0.1f))
}

// MARK: - Purchase Success Banner

@Composable
private fun PurchaseSuccessBanner(coinAmount: Int) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier.padding(top = 80.dp, start = 24.dp, end = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.Green.copy(alpha = 0.15f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🎉", fontSize = 24.sp)
                Column {
                    Text("Purchase Complete!", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Green)
                    Text("+$coinAmount Hype Coins added", fontSize = 13.sp, color = Color.Green.copy(alpha = 0.8f))
                }
            }
        }
    }
}

