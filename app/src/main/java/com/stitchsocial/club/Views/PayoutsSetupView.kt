package com.stitchsocial.club.views

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stitchsocial.club.services.CreatorCampaignService
import com.stitchsocial.club.services.StripeAccountStatus
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Stripe Connect payout onboarding for creators (iOS parity with
 * Marketplace/PayoutsSetupView.swift).
 *
 * createCreatorStripeOnboarding returns a Stripe-hosted link, which opens in a
 * Custom Tab. Stripe collects KYC, bank and tax details — Stitch never sees
 * them — then redirects to stitchsocial.me/app/payouts/complete. When the user
 * comes back to the app (the Activity resumes) refreshCreatorStripeStatus
 * re-syncs the account, same as iOS does on SafariView dismiss.
 *
 * Once active, held payouts (deliverables accepted before the account was
 * ready) can be swept with retryHeldPayouts.
 */
@Composable
fun PayoutsSetupView(onDismiss: () -> Unit, onStatusChanged: (StripeAccountStatus) -> Unit = {}) {
    val service = remember { CreatorCampaignService.getInstance() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<StripeAccountStatus?>(null) }
    var isLoadingStatus by remember { mutableStateOf(true) }
    var isStartingOnboarding by remember { mutableStateOf(false) }
    var isRetrying by remember { mutableStateOf(false) }
    var retryResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Set while the Custom Tab is up, so only a return from Stripe re-syncs —
    // not every resume of the app.
    var awaitingReturn by remember { mutableStateOf(false) }

    suspend fun loadStatus() {
        isLoadingStatus = true
        try {
            val s = service.refreshStripeStatus()
            status = s
            onStatusChanged(s)
        } catch (e: Exception) {
            errorMessage = "Couldn't load status: ${e.message}"
        }
        isLoadingStatus = false
    }

    LaunchedEffect(Unit) { loadStatus() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingReturn) {
                awaitingReturn = false
                scope.launch { loadStatus() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isActive = status?.status == "active"

    SettingsSubPage(title = "Payouts", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // Header
            PayoutCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CreditCard, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                    Text("Receive campaign earnings", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                Text(
                    "Connect a bank account through Stripe to receive payouts from accepted campaign deliverables. Stripe handles KYC and tax forms — Stitch never sees your bank details.",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 13.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Shield, null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(11.dp))
                    Text("Powered by Stripe", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                }
            }

            // Status + primary action
            PayoutCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Account status", color = AppTheme.colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    if (isLoadingStatus) {
                        CircularProgressIndicator(color = AppTheme.colors.textPrimary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        StripeStatusBadge(status?.status)
                    }
                }
                if (isActive) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF1E8E3E), modifier = Modifier.size(16.dp))
                        Text("Payouts are active — earnings will deposit automatically.", color = Color(0xFF1E8E3E), fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                isStartingOnboarding = true
                                errorMessage = null
                                try {
                                    val url = service.createStripeOnboarding()
                                    awaitingReturn = true
                                    openInCustomTab(context, url)
                                } catch (e: Exception) {
                                    awaitingReturn = false
                                    errorMessage = e.message ?: "Couldn't start payout setup"
                                }
                                isStartingOnboarding = false
                            }
                        },
                        enabled = !isStartingOnboarding && !isLoadingStatus,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isStartingOnboarding) {
                            CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (status == null || status?.status == "none") "Set up payouts" else "Continue setup",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                val reqs = status?.requirements.orEmpty()
                if (reqs.isNotEmpty() && !isActive) {
                    Text("Still needed by Stripe:", color = Color(0xFFFFA000), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    reqs.forEach { Text("• $it", color = AppTheme.colors.textSecondary, fontSize = 12.sp) }
                }
            }

            // Held payouts
            if (isActive) {
                PayoutCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Inbox, null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
                        Text("Sweep held payouts", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    Text(
                        "If any deliverables were accepted before you connected payouts, sweep them now to release the funds.",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 13.sp
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                isRetrying = true
                                try {
                                    retryResult = service.retryHeldPayouts()
                                } catch (e: Exception) {
                                    errorMessage = e.message
                                }
                                isRetrying = false
                            }
                        },
                        enabled = !isRetrying,
                        modifier = Modifier.background(Color.Cyan.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    ) {
                        Text(if (isRetrying) "Sweeping…" else "Sweep now", color = Color.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    retryResult?.let { (ok, failed) ->
                        Text("Released: $ok. Still failing: $failed.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }

            errorMessage?.let { Text(it, color = Color.Red, fontSize = 12.sp) }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PayoutCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun StripeStatusBadge(status: String?) {
    val (label, color) = when (status) {
        "active" -> "Active" to Color(0xFF1E8E3E)
        "pending_verification" -> "Pending verification" to Color(0xFFFFA000)
        "onboarding" -> "Onboarding in progress" to Color.Yellow
        else -> "Not set up" to AppTheme.colors.textSecondary
    }
    Text(
        label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** Custom Tab, falling back to the browser when no Custom Tabs provider exists. */
private fun openInCustomTab(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (_: Exception) {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
    }
}
