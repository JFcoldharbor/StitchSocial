/*
 * SubscriptionViews.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views — Subscription screens matching iOS exactly
 *
 * MySubscriptionsView          → iOS MySubscriptionsView     (fan's active subs)
 * CreatorSubscriptionSettingsView → iOS CreatorPricingSettingsView (creator config)
 * MySubscribersView            → iOS MySubscribersView       (creator's subscriber list)
 *
 * CACHING: All reads go through SubscriptionService.shared which owns 5/10min TTL caches.
 *   - fetchMySubscriptions: 5min TTL, served from cache on re-open
 *   - fetchCreatorPlan: 10min TTL, invalidated on save
 *   - fetchMySubscribers: 5min TTL
 * BATCHING: createOrUpdatePlan does a single Firestore set+merge. No per-perk writes.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.services.AdRevenueShare
import com.stitchsocial.club.services.SubscriptionService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────
// MARK: - MySubscriptionsView
// Fan sees active subscriptions. Matches iOS MySubscriptionsView.
// ─────────────────────────────────────────────

@Composable
fun MySubscriptionsView(
    userID: String,
    onDismiss: () -> Unit
) {
    val service = remember { SubscriptionService.shared }
    val scope = rememberCoroutineScope()

    var subscriptions by remember { mutableStateOf<List<ActiveSubscription>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var cancelingID by remember { mutableStateOf<String?>(null) }
    var showCancelConfirm by remember { mutableStateOf<String?>(null) } // creatorID to cancel

    LaunchedEffect(userID) {
        try {
            subscriptions = service.fetchMySubscriptions(userID)
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 52.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.Cyan)
            }
            Text(
                "My Subscriptions", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = Color.White, modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(48.dp))
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = Color.Red, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
            subscriptions.isEmpty() -> EmptySubscriptionsView()
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(subscriptions, key = { it.id }) { sub ->
                    ActiveSubscriptionCard(
                        subscription = sub,
                        isCanceling = cancelingID == sub.creatorID,
                        onCancelTap = { showCancelConfirm = sub.creatorID }
                    )
                }
            }
        }
    }

    // Cancel confirmation dialog
    showCancelConfirm?.let { creatorID ->
        AlertDialog(
            onDismissRequest = { showCancelConfirm = null },
            title = { Text("Cancel Subscription", color = Color.White) },
            text = { Text("You'll keep access until the end of your current period.", color = Color.Gray) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = null
                    cancelingID = creatorID
                    scope.launch {
                        try {
                            service.cancelSubscription(userID, creatorID)
                            subscriptions = subscriptions.filter { it.creatorID != creatorID }
                        } catch (_: Exception) {}
                        cancelingID = null
                    }
                }) { Text("Cancel Sub", color = Color(0xFFFF453A)) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = null }) { Text("Keep", color = Color.Gray) }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}

@Composable
private fun ActiveSubscriptionCard(
    subscription: ActiveSubscription,
    isCanceling: Boolean,
    onCancelTap: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val tierColor = when (subscription.coinTier) {
        CoinPriceTier.STARTER -> Color.Gray
        CoinPriceTier.BASIC   -> Color.Cyan
        CoinPriceTier.PLUS    -> Color(0xFF30D158)
        CoinPriceTier.PRO     -> Color(0xFFBF5AF2)
        CoinPriceTier.MAX     -> Color(0xFFFFD60A)
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Tier badge + price
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.background(tierColor.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    subscription.coinTier.displayName,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tierColor
                )
            }
            Text(
                "${subscription.coinTier.rawValue} coins/mo",
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
        }

        // Perks list
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            subscription.perks.forEach { perk ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(perk.emoji, fontSize = 14.sp)
                    Text(perk.displayName, fontSize = 14.sp, color = Color.Gray)
                }
            }
        }

        Divider(color = Color.White.copy(alpha = 0.1f))

        // Period info
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Renews", fontSize = 12.sp, color = Color.Gray)
                Text(dateFormat.format(subscription.currentPeriodEnd), fontSize = 14.sp, color = Color.White)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Days left", fontSize = 12.sp, color = Color.Gray)
                Text("${subscription.daysRemaining}", fontSize = 14.sp, color = Color.Cyan)
            }
        }

        // Cancel button
        if (isCanceling) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Red, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            TextButton(
                onClick = onCancelTap,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel Subscription", color = Color(0xFFFF453A), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun EmptySubscriptionsView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⭐", fontSize = 48.sp)
            Text("No Active Subscriptions", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("Support your favourite creators with Hype Coins", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - CreatorSubscriptionSettingsView
// Matches iOS CreatorPricingSettingsView.
// Creator enables sub plan, sets custom perks per tier, 60-day cooldown enforced by service.
// ─────────────────────────────────────────────

@Composable
fun CreatorSubscriptionSettingsView(
    creatorID: String,
    creatorTier: UserTier,
    onDismiss: () -> Unit
) {
    val service = remember { SubscriptionService.shared }
    val scope = rememberCoroutineScope()

    var plan by remember { mutableStateOf<CreatorSubscriptionPlan?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var isEnabled by remember { mutableStateOf(false) }
    var welcomeMessage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(creatorID) {
        try {
            val fetched = service.fetchCreatorPlan(creatorID)
            plan = fetched
            isEnabled = fetched?.isEnabled ?: false
            welcomeMessage = fetched?.customWelcomeMessage ?: ""
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 52.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.Cyan)
            }
            Text(
                "Subscription Settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = Color.White, modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            TextButton(
                onClick = {
                    isSaving = true
                    scope.launch {
                        try {
                            service.createOrUpdatePlan(
                                creatorID = creatorID,
                                isEnabled = isEnabled,
                                tierPricing = plan?.tierPricing ?: TierPricing(),
                                welcomeMessage = welcomeMessage.ifBlank { null }
                            )
                            successMessage = "Saved!"
                        } catch (e: SubscriptionError.PriceCooldown) {
                            error = "Perk changes locked for ${e.daysLeft} more days"
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving
            ) {
                if (isSaving) CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Save", color = Color.Cyan, fontWeight = FontWeight.SemiBold)
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan)
            }
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Enable toggle
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Enable Subscriptions", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Text("Let fans subscribe with Hype Coins", fontSize = 13.sp, color = Color.Gray)
                }
                Switch(
                    checked = isEnabled, onCheckedChange = { isEnabled = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.Cyan)
                )
            }

            if (isEnabled) {
                // Subscriber count if plan exists
                plan?.let { p ->
                    if (p.subscriberCount > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatPill("Subscribers", "${p.subscriberCount}", Color.Cyan)
                            StatPill("Total Earned", "${p.totalEarned} coins", Color(0xFFFFD60A))
                        }
                    }

                    // Cooldown notice
                    if (!p.canChangePrice) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(Color(0xFFFF9F0A).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(18.dp))
                            Text(
                                "Perk changes locked for ${p.daysUntilPriceChange} more days",
                                fontSize = 13.sp, color = Color(0xFFFF9F0A)
                            )
                        }
                    }
                }

                // Tier cards — show all 5 tiers
                Text("Subscription Tiers", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Prices are fixed by platform. You can customize perks on each tier (Influencer+ only).",
                    fontSize = 13.sp, color = Color.Gray
                )

                CoinPriceTier.entries.forEach { tier ->
                    TierCard(
                        tier = tier,
                        tierPricing = plan?.tierPricing ?: TierPricing(),
                        canCustomize = AdRevenueShare.canAccessAds(creatorTier)
                    )
                }

                // Welcome message
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Welcome Message", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    OutlinedTextField(
                        value = welcomeMessage,
                        onValueChange = { if (it.length <= 200) welcomeMessage = it },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        placeholder = { Text("Welcome new subscribers...", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Cyan, unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            cursorColor = Color.Cyan
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Text("${welcomeMessage.length}/200", fontSize = 12.sp, color = Color.Gray,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                }
            }

            // Error / success
            error?.let { Text(it, color = Color.Red, fontSize = 13.sp) }
            successMessage?.let { Text(it, color = Color(0xFF30D158), fontSize = 13.sp) }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun TierCard(tier: CoinPriceTier, tierPricing: TierPricing, canCustomize: Boolean) {
    val tierColor = when (tier) {
        CoinPriceTier.STARTER -> Color.Gray
        CoinPriceTier.BASIC   -> Color.Cyan
        CoinPriceTier.PLUS    -> Color(0xFF30D158)
        CoinPriceTier.PRO     -> Color(0xFFBF5AF2)
        CoinPriceTier.MAX     -> Color(0xFFFFD60A)
    }
    val perks = tierPricing.perks(tier)

    Column(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, tierColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .background(tierColor.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(tier.displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tierColor)
            Text(tier.coinsDisplay, fontSize = 14.sp, color = Color.White)
        }
        perks.forEach { perk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(perk.emoji, fontSize = 14.sp)
                Text(perk.displayName, fontSize = 14.sp, color = Color.Gray)
            }
        }
        if (canCustomize) {
            Text("Tap to customize perks (coming soon)", fontSize = 11.sp, color = Color.Gray.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 12.sp, color = Color.Gray)
    }
}

// ─────────────────────────────────────────────
// MARK: - MySubscribersView
// Creator's subscriber list. Matches iOS MySubscribersView.
// ─────────────────────────────────────────────

@Composable
fun MySubscribersView(
    creatorID: String,
    onDismiss: () -> Unit
) {
    val service = remember { SubscriptionService.shared }
    var subscribers by remember { mutableStateOf<List<SubscriberInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(creatorID) {
        try {
            subscribers = service.fetchMySubscribers(creatorID)
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 52.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.Cyan)
            }
            Text(
                "My Subscribers", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = Color.White, modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(48.dp))
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = Color.Red, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
            subscribers.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🏅", fontSize = 48.sp)
                    Text("No Subscribers Yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("Enable subscriptions to start earning", fontSize = 14.sp, color = Color.Gray)
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Stats header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatPill("Total", "${subscribers.size}", Color.Cyan)
                        StatPill("Total Coins", "${subscribers.sumOf { it.totalPaid }}", Color(0xFFFFD60A))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                items(subscribers, key = { it.id }) { sub ->
                    SubscriberRow(sub)
                }
            }
        }
    }
}

@Composable
private fun SubscriberRow(sub: SubscriberInfo) {
    val tierColor = when (sub.coinTier) {
        CoinPriceTier.STARTER -> Color.Gray
        CoinPriceTier.BASIC   -> Color.Cyan
        CoinPriceTier.PLUS    -> Color(0xFF30D158)
        CoinPriceTier.PRO     -> Color(0xFFBF5AF2)
        CoinPriceTier.MAX     -> Color(0xFFFFD60A)
    }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar placeholder
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            if (!sub.profileImageURL.isNullOrEmpty()) {
                AsyncImage(model = sub.profileImageURL, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Icon(Icons.Default.Person, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
            }
        }

        Column(Modifier.weight(1f)) {
            val name = sub.displayName.ifEmpty { sub.subscriberID.take(8) }
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text("Since ${dateFormat.format(sub.subscribedAt)}", fontSize = 12.sp, color = Color.Gray)
        }

        Column(horizontalAlignment = Alignment.End) {
            Box(Modifier.background(tierColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(sub.coinTier.displayName, fontSize = 11.sp, color = tierColor, fontWeight = FontWeight.SemiBold)
            }
            Text("${sub.totalPaid} coins", fontSize = 12.sp, color = Color.Gray)
        }
    }
}