/*
 * WalletScreen.kt - WALLET COMPOSE UI
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 6: UI — Compose screen for HypeCoin wallet
 * Dependencies: WalletViewModel, HypeCoinModels, UserTier
 *
 * EXACT PORT: WalletView.swift (iOS)
 *
 * ANDROID DELTA vs iOS:
 *   - Buy Coins tab shows real Play Billing purchase buttons (not web redirect)
 *   - Activity reference needed for launchPurchase() — passed via LocalContext
 *   - ViewModel-driven state instead of @State/@ObservedObject
 *   - Compose navigation instead of SwiftUI NavigationView
 */

package com.stitchsocial.club.Views

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.services.*
import java.text.SimpleDateFormat
import java.util.*

// MARK: - Colors (matches iOS dark theme)
private val Black       = Color(0xFF000000)
private val StitchCyan  = Color(0xFF00BCD4)
private val StitchGold  = Color(0xFFFFD700)
private val CardBg      = Color(0xFF1A1A1A)
private val DividerGray = Color(0xFF2A2A2A)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextMuted   = Color(0xFF888888)
private val Green       = Color(0xFF4CAF50)
private val Red         = Color(0xFFE53935)
private val Purple      = Color(0xFFAB47BC)

// MARK: - WalletScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    userID: String,
    userTier: UserTier,
    onDismiss: () -> Unit,
    viewModel: WalletViewModel = viewModel()
) {
    val context        = LocalContext.current
    val activity       = context as? Activity

    val selectedTab    by viewModel.selectedTab.collectAsState()
    val balance        by viewModel.balance.collectAsState()
    val isLoading      by viewModel.isLoading.collectAsState()
    val errorMessage   by viewModel.errorMessage.collectAsState()
    val showPurchaseSuccess by viewModel.showPurchaseSuccess.collectAsState()
    val lastPurchaseAmt     by viewModel.lastPurchaseAmount.collectAsState()
    val showCashOut    by viewModel.showCashOut.collectAsState()

    // Load on first composition
    LaunchedEffect(userID) {
        viewModel.loadData(userID)
    }

    // Purchase success banner
    if (showPurchaseSuccess) {
        PurchaseSuccessBanner(coins = lastPurchaseAmt)
    }

    // Cash out bottom sheet
    if (showCashOut) {
        CashOutSheet(
            userTier       = userTier,
            availableCoins = balance?.availableCoins ?: 0,
            viewModel      = viewModel,
            onDismiss      = { viewModel.hideCashOutSheet() }
        )
    }

    // Error snackbar
    errorMessage?.let { msg ->
        LaunchedEffect(msg) {
            // Auto-clear after show
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Hype Coins",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.syncBalance() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = StitchCyan)
                    }
                },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = StitchCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Black)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Black)
        ) {
            // Balance header
            BalanceHeader(balance = balance)

            // Tab bar
            WalletTabBar(
                selectedTab = selectedTab,
                onTabSelect = { viewModel.selectTab(it) }
            )

            // Tab content
            when (selectedTab) {
                WalletTab.BALANCE -> BalanceTab(
                    balance  = balance,
                    userTier = userTier,
                    onBuyTap = { viewModel.selectTab(WalletTab.BUY) },
                    onCashOutTap = { viewModel.showCashOutSheet() }
                )
                WalletTab.BUY -> BuyCoinsTab(
                    packageDetails = viewModel.packageDetails,
                    onPurchase     = { pkg ->
                        activity?.let { viewModel.launchPurchase(it, pkg) }
                    }
                )
                WalletTab.HISTORY -> HistoryTab(viewModel = viewModel)
            }
        }
    }
}

// MARK: - Balance Header
// Matches iOS WalletView.balanceHeader

@Composable
private fun BalanceHeader(balance: HypeCoinBalance?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
    ) {
        // Coin icon
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(listOf(StitchGold, Color(0xFFFF8F00)))
                )
        ) {
            Text("🪙", fontSize = 36.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Available balance
        Text(
            text = "${balance?.availableCoins ?: 0}",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Text(
            text = "Hype Coins",
            fontSize = 14.sp,
            color = TextMuted
        )

        // Pending coins
        val pending = balance?.pendingCoins ?: 0
        if (pending > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("⏱", fontSize = 12.sp)
                Text(
                    text = "$pending pending",
                    fontSize = 12.sp,
                    color = StitchGold
                )
            }
        }
    }
}

// MARK: - Tab Bar
// Matches iOS WalletView.tabBar

@Composable
private fun WalletTabBar(
    selectedTab: WalletTab,
    onTabSelect: (WalletTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Black)
    ) {
        WalletTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            ) {
                TextButton(onClick = { onTabSelect(tab) }) {
                    Text(
                        text       = tab.label,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = if (isSelected) TextPrimary else TextMuted
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (isSelected) StitchGold else Color.Transparent)
                )
            }
        }
    }
}

// MARK: - Balance Tab
// Matches iOS WalletView.balanceTab

@Composable
private fun BalanceTab(
    balance: HypeCoinBalance?,
    userTier: UserTier,
    onBuyTap: () -> Unit,
    onCashOutTap: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Quick actions
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                QuickActionButton(
                    emoji  = "💳",
                    label  = "Buy",
                    color  = Green,
                    onClick = onBuyTap,
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    emoji  = "💵",
                    label  = "Cash Out",
                    color  = StitchGold,
                    onClick = onCashOutTap,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Stats card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape  = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatRow("Lifetime Earned", "${balance?.lifetimeEarned ?: 0}")
                    Spacer(modifier = Modifier.height(8.dp))
                    StatRow("Lifetime Spent",  "${balance?.lifetimeSpent ?: 0}")

                    if (balance != null) {
                        Divider(color = DividerGray, modifier = Modifier.padding(vertical = 12.dp))
                        StatRow("Your Tier",      userTier.displayName,        valueColor = Purple)
                        Spacer(modifier = Modifier.height(8.dp))
                        StatRow(
                            "Cash Out Rate",
                            "${(SubscriptionRevenueShare.creatorShare(userTier) * 100).toInt()}%",
                            valueColor = Green
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Buy Coins Tab
// Android: real Play Billing purchase buttons (iOS shows web redirect)

@Composable
private fun BuyCoinsTab(
    packageDetails: List<Pair<HypeCoinPackage, String>>,
    onPurchase: (HypeCoinPackage) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text       = "Buy Hype Coins",
                fontSize   = 20.sp,
                fontWeight = FontWeight.Bold,
                color      = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text     = "Coins are used for subscriptions, tips, and community perks.",
                fontSize = 13.sp,
                color    = TextMuted
            )
        }

        items(packageDetails) { (pkg, priceStr) ->
            CoinPackageCard(
                pkg      = pkg,
                priceStr = priceStr,
                onBuy    = { onPurchase(pkg) }
            )
        }
    }
}

// MARK: - Coin Package Card
// Matches iOS CoinPackageDisplayCard — Android adds Buy button

@Composable
private fun CoinPackageCard(
    pkg: HypeCoinPackage,
    priceStr: String,
    onBuy: () -> Unit
) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = CardBg),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            // Coin icon + amount
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("🪙", fontSize = 24.sp)
                Column {
                    Text(
                        text       = "${pkg.coins} coins",
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color      = TextPrimary
                    )
                    Text(
                        text     = pkg.displayName,
                        fontSize = 12.sp,
                        color    = TextMuted
                    )
                }
            }

            // Price + buy button
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text       = priceStr,
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color      = StitchGold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onBuy,
                    colors  = ButtonDefaults.buttonColors(containerColor = StitchCyan),
                    shape   = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("Buy", fontSize = 13.sp, color = Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// MARK: - History Tab
// Matches iOS WalletView.historyTab

@Composable
private fun HistoryTab(viewModel: WalletViewModel) {
    val transactions      by viewModel.transactions.collectAsState()
    val transactionsLoaded by viewModel.transactionsLoaded.collectAsState()

    if (!transactionsLoaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = StitchCyan)
        }
        return
    }

    if (transactions.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(32.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🕐", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("No transactions yet", color = TextMuted, fontSize = 16.sp)
            }
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(transactions, key = { it.id }) { tx ->
            TransactionRow(tx)
            Divider(color = DividerGray)
        }
    }
}

// MARK: - Transaction Row
// Matches iOS TransactionRow

@Composable
private fun TransactionRow(tx: CoinTransaction) {
    val isCredit = tx.amount > 0
    val amountColor = if (isCredit) Green else Red
    val iconBg      = if (isCredit) Green.copy(alpha = 0.15f) else Red.copy(alpha = 0.15f)
    val arrow       = if (isCredit) "↓" else "↑"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        // Icon circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBg)
        ) {
            Text(arrow, color = amountColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Description + date
        Column(modifier = Modifier.weight(1f)) {
            Text(tx.description, color = TextPrimary, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text     = formatRelativeDate(tx.createdAt),
                fontSize = 12.sp,
                color    = TextMuted
            )
        }

        // Amount
        Text(
            text       = "${if (isCredit) "+" else ""}${tx.amount}",
            fontSize   = 15.sp,
            fontWeight = FontWeight.Bold,
            color      = amountColor
        )
    }
}

// MARK: - Cash Out Sheet
// Matches iOS CashOutSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashOutSheet(
    userTier: UserTier,
    availableCoins: Int,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    val cashOutAmount   by viewModel.cashOutAmount.collectAsState()
    val isProcessing    by viewModel.cashOutProcessing.collectAsState()
    val isSuccess       by viewModel.cashOutSuccess.collectAsState()
    val errorMessage    by viewModel.errorMessage.collectAsState()

    val amount      = viewModel.coinAmountInt(cashOutAmount)
    val isValid     = viewModel.isValidCashOut(cashOutAmount, availableCoins)
    val (creatorAmt, _) = viewModel.cashOutBreakdown(cashOutAmount, userTier)

    if (isSuccess) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111111)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Cash Out",
                fontSize   = 20.sp,
                fontWeight = FontWeight.Bold,
                color      = TextPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Available
            Text("Available: $availableCoins coins", color = TextMuted, fontSize = 14.sp)

            Spacer(modifier = Modifier.height(16.dp))

            // Amount input
            OutlinedTextField(
                value         = cashOutAmount,
                onValueChange = { viewModel.updateCashOutAmount(it) },
                placeholder   = { Text("Enter amount", color = TextMuted) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine    = true,
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = StitchCyan,
                    unfocusedBorderColor = DividerGray,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "Minimum: ${CashOutLimits.MINIMUM_COINS} coins",
                fontSize = 12.sp,
                color    = TextMuted,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(top = 4.dp)
            )

            // Breakdown
            if (amount > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors   = CardDefaults.cardColors(containerColor = CardBg),
                    shape    = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        StatRow("Your tier",   userTier.displayName, valueColor = Purple)
                        Spacer(Modifier.height(8.dp))
                        StatRow(
                            "Your share",
                            "${(SubscriptionRevenueShare.creatorShare(userTier) * 100).toInt()}%",
                            valueColor = Green
                        )
                        Divider(color = DividerGray, modifier = Modifier.padding(vertical = 10.dp))
                        StatRow(
                            "You'll receive",
                            "$${String.format("%.2f", creatorAmt)}",
                            valueColor = Green,
                            boldValue  = true
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Cash Out button
            Button(
                onClick  = { viewModel.processCashOut(userTier) },
                enabled  = isValid && !isProcessing,
                colors   = ButtonDefaults.buttonColors(
                    containerColor = if (isValid) Green else Color.Gray
                ),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = Black, modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        "Cash Out",
                        fontWeight = FontWeight.Bold,
                        color      = Black,
                        fontSize   = 16.sp
                    )
                }
            }

            errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Red, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

// MARK: - Purchase Success Banner

@Composable
private fun PurchaseSuccessBanner(coins: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(Green)
            .padding(12.dp)
    ) {
        Text(
            "🎉 +$coins Hype Coins added!",
            color      = Black,
            fontWeight = FontWeight.Bold,
            fontSize   = 15.sp
        )
    }
}

// MARK: - Shared Components

@Composable
private fun QuickActionButton(
    emoji: String,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors  = ButtonDefaults.buttonColors(containerColor = CardBg),
        shape   = RoundedCornerShape(12.dp),
        modifier = modifier.height(64.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 20.sp)
            Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    boldValue: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, color = TextMuted, fontSize = 14.sp)
        Text(
            text       = value,
            color      = valueColor,
            fontSize   = 14.sp,
            fontWeight = if (boldValue) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// MARK: - Date Formatting

private fun formatRelativeDate(date: Date): String {
    val now     = System.currentTimeMillis()
    val diff    = now - date.time
    val minutes = diff / 60_000
    val hours   = diff / 3_600_000
    val days    = diff / 86_400_000

    return when {
        minutes < 1  -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24   -> "${hours}h ago"
        days < 7     -> "${days}d ago"
        else         -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
    }
}