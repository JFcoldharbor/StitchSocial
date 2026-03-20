package com.stitchsocial.club.views

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.stitchsocial.club.foundation.CashOutLimits
import com.stitchsocial.club.foundation.CoinTransaction
import com.stitchsocial.club.foundation.HypeCoinBalance
import com.stitchsocial.club.foundation.HypeCoinPackage
import com.stitchsocial.club.foundation.SubscriptionRevenueShare
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.services.WalletTab
import com.stitchsocial.club.services.WalletViewModel
import java.text.SimpleDateFormat
import java.util.*

/*
 * WalletScreen.kt — FIXED
 * Explicit imports for collectAsState and getValue resolve
 * "Cannot infer type / Property delegate must have getValue" errors.
 * Wildcard imports from compose.runtime don't always pull these in.
 */

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    userID: String,
    userTier: UserTier,
    onDismiss: () -> Unit,
    viewModel: WalletViewModel = viewModel()
) {
    val context      = LocalContext.current
    val activity     = context as? Activity

    val selectedTab          by viewModel.selectedTab.collectAsState()
    val balance              by viewModel.balance.collectAsState()
    val isLoading            by viewModel.isLoading.collectAsState()
    val showPurchaseSuccess  by viewModel.showPurchaseSuccess.collectAsState()
    val lastPurchaseAmt      by viewModel.lastPurchaseAmount.collectAsState()
    val showCashOut          by viewModel.showCashOut.collectAsState()
    val errorMessage         by viewModel.errorMessage.collectAsState()

    LaunchedEffect(userID) { viewModel.loadData(userID) }

    if (showPurchaseSuccess) PurchaseSuccessBanner(lastPurchaseAmt)

    if (showCashOut) {
        CashOutSheet(
            userTier       = userTier,
            availableCoins = balance?.availableCoins ?: 0,
            viewModel      = viewModel,
            onDismiss      = { viewModel.hideCashOutSheet() }
        )
    }

    errorMessage?.let { LaunchedEffect(it) { viewModel.clearError() } }

    Scaffold(
        containerColor = Black,
        topBar = {
            TopAppBar(
                title           = { Text("Hype Coins", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon  = { IconButton(onClick = { viewModel.syncBalance() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = StitchCyan)
                }},
                actions         = { IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = StitchCyan)
                }},
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
            BalanceHeader(balance)
            WalletTabBar(selectedTab) { viewModel.selectTab(it) }
            when (selectedTab) {
                WalletTab.BALANCE -> BalanceTab(
                    balance      = balance,
                    userTier     = userTier,
                    onBuyTap     = { viewModel.selectTab(WalletTab.BUY) },
                    onCashOutTap = { viewModel.showCashOutSheet() }
                )
                WalletTab.BUY -> BuyCoinsTab(
                    packageDetails = viewModel.packageDetails,
                    onPurchase     = { pkg -> activity?.let { viewModel.launchPurchase(it, pkg) } }
                )
                WalletTab.HISTORY -> HistoryTab(viewModel)
            }
        }
    }
}

@Composable
private fun BalanceHeader(balance: HypeCoinBalance?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(80.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(StitchGold, Color(0xFFFF8F00))))
        ) { Text("🪙", fontSize = 36.sp) }

        Spacer(Modifier.height(12.dp))
        Text("${balance?.availableCoins ?: 0}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("Hype Coins", fontSize = 14.sp, color = TextMuted)

        val pending = balance?.pendingCoins ?: 0
        if (pending > 0) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("⏱", fontSize = 12.sp)
                Text("$pending pending", fontSize = 12.sp, color = StitchGold)
            }
        }
    }
}

@Composable
private fun WalletTabBar(selectedTab: WalletTab, onTabSelect: (WalletTab) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(Black)) {
        WalletTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
            ) {
                TextButton(onClick = { onTabSelect(tab) }) {
                    Text(tab.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) TextPrimary else TextMuted)
                }
                Box(modifier = Modifier.fillMaxWidth().height(2.dp)
                    .background(if (isSelected) StitchGold else Color.Transparent))
            }
        }
    }
}

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
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                QuickActionButton("💳", "Buy",      Green,      onBuyTap,     Modifier.weight(1f))
                QuickActionButton("💵", "Cash Out", StitchGold, onCashOutTap, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatRow("Lifetime Earned", "${balance?.lifetimeEarned ?: 0}")
                    Spacer(Modifier.height(8.dp))
                    StatRow("Lifetime Spent",  "${balance?.lifetimeSpent  ?: 0}")
                    if (balance != null) {
                        Divider(color = DividerGray, modifier = Modifier.padding(vertical = 12.dp))
                        StatRow("Your Tier",   userTier.displayName, valueColor = Purple)
                        Spacer(Modifier.height(8.dp))
                        StatRow("Cash Out Rate",
                            "${(SubscriptionRevenueShare.creatorShare(userTier) * 100).toInt()}%",
                            valueColor = Green)
                    }
                }
            }
        }
    }
}

@Composable
private fun BuyCoinsTab(
    packageDetails: List<Pair<HypeCoinPackage, String>>,
    onPurchase: (HypeCoinPackage) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Buy Hype Coins", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Used for subscriptions, tips, and community perks.", fontSize = 13.sp, color = TextMuted)
        }
        items(packageDetails) { (pkg, priceStr) ->
            CoinPackageCard(pkg, priceStr) { onPurchase(pkg) }
        }
    }
}

@Composable
private fun CoinPackageCard(pkg: HypeCoinPackage, priceStr: String, onBuy: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                Text("🪙", fontSize = 24.sp)
                Column {
                    Text("${pkg.coins} coins", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(pkg.displayName, fontSize = 12.sp, color = TextMuted)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(priceStr, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = StitchGold)
                Spacer(Modifier.height(6.dp))
                Button(onClick = onBuy,
                    colors = ButtonDefaults.buttonColors(containerColor = StitchCyan),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("Buy", fontSize = 13.sp, color = Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(viewModel: WalletViewModel) {
    val transactions       by viewModel.transactions.collectAsState()
    val transactionsLoaded by viewModel.transactionsLoaded.collectAsState()

    if (!transactionsLoaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = StitchCyan)
        }
        return
    }
    if (transactions.isEmpty()) {
        Box(contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(32.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🕐", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
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

@Composable
private fun TransactionRow(tx: CoinTransaction) {
    val isCredit    = tx.amount > 0
    val amountColor = if (isCredit) Green else Red
    val iconBg      = if (isCredit) Green.copy(alpha = 0.15f) else Red.copy(alpha = 0.15f)
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(iconBg)) {
            Text(if (isCredit) "↓" else "↑", color = amountColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(tx.description, color = TextPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(formatRelativeDate(tx.createdAt), fontSize = 12.sp, color = TextMuted)
        }
        Text("${if (isCredit) "+" else ""}${tx.amount}",
            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = amountColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashOutSheet(
    userTier: UserTier,
    availableCoins: Int,
    viewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    val cashOutAmount  by viewModel.cashOutAmount.collectAsState()
    val isProcessing   by viewModel.cashOutProcessing.collectAsState()
    val isSuccess      by viewModel.cashOutSuccess.collectAsState()
    val errorMessage   by viewModel.errorMessage.collectAsState()

    val amount     = viewModel.coinAmountInt(cashOutAmount)
    val isValid    = viewModel.isValidCashOut(cashOutAmount, availableCoins)
    val (creatorAmt, _) = viewModel.cashOutBreakdown(cashOutAmount, userTier)

    if (isSuccess) { LaunchedEffect(Unit) { onDismiss() } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF111111)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Cash Out", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(16.dp))
            Text("Available: $availableCoins coins", color = TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value         = cashOutAmount,
                onValueChange = { viewModel.updateCashOutAmount(it) },
                placeholder   = { Text("Enter amount", color = TextMuted) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine    = true,
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = StitchCyan, unfocusedBorderColor = DividerGray,
                    focusedTextColor     = TextPrimary, unfocusedTextColor   = TextPrimary),
                modifier = Modifier.fillMaxWidth()
            )
            Text("Minimum: ${CashOutLimits.MINIMUM_COINS} coins", fontSize = 12.sp, color = TextMuted,
                modifier = Modifier.align(Alignment.Start).padding(top = 4.dp))
            if (amount > 0) {
                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        StatRow("Your tier",  userTier.displayName, valueColor = Purple)
                        Spacer(Modifier.height(8.dp))
                        StatRow("Your share", "${(SubscriptionRevenueShare.creatorShare(userTier)*100).toInt()}%", valueColor = Green)
                        Divider(color = DividerGray, modifier = Modifier.padding(vertical = 10.dp))
                        StatRow("You'll receive", "$${String.format("%.2f", creatorAmt)}", valueColor = Green, boldValue = true)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { viewModel.processCashOut(userTier) }, enabled = isValid && !isProcessing,
                colors = ButtonDefaults.buttonColors(containerColor = if (isValid) Green else Color.Gray),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (isProcessing) CircularProgressIndicator(color = Black, modifier = Modifier.size(20.dp))
                else Text("Cash Out", fontWeight = FontWeight.Bold, color = Black, fontSize = 16.sp)
            }
            errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Red, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PurchaseSuccessBanner(coins: Int) {
    Box(contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().background(Green).padding(12.dp)) {
        Text("🎉 +$coins Hype Coins added!", color = Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun QuickActionButton(emoji: String, label: String, color: Color, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp), modifier = modifier.height(64.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 20.sp)
            Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = TextPrimary, boldValue: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMuted, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp,
            fontWeight = if (boldValue) FontWeight.Bold else FontWeight.Medium)
    }
}

private fun formatRelativeDate(date: Date): String {
    val diff    = System.currentTimeMillis() - date.time
    val minutes = diff / 60_000
    val hours   = diff / 3_600_000
    val days    = diff / 86_400_000
    return when {
        minutes < 1  -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours   < 24 -> "${hours}h ago"
        days    < 7  -> "${days}d ago"
        else         -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
    }
}