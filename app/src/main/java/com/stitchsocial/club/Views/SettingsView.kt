/*
 * SettingsView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Full settings matching iOS SettingsView.swift
 * UPDATED: Added businessSection (Analytics, Campaigns rows)
 * UPDATED: isCreator now mirrors iOS AdRevenueShare.canAccessAdMarketplace()
 * UPDATED: SocialSection includes ReferralButton (Ambassador Program / Invite Friends)
 * UPDATED: isBusiness gating mirrors iOS (business vs personal sections)
 *
 * CACHING NOTES (add to CachingOptimization file):
 * - coinBalance: already cached in HypeCoinService. SettingsView reads from
 *   published state — zero extra reads. No change needed.
 * - subscriptionCount: already cached in SubscriptionService. Same pattern.
 * - ReferralStats in ReferralDashboardView: session-scoped @State, loaded once
 *   on appear. Already noted in ReferralButton.swift caching comment.
 */

package com.stitchsocial.club.views

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.AccountType
import com.stitchsocial.club.foundation.BusinessProfile
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.ReferralService
import com.stitchsocial.club.ui.theme.StitchColors
import kotlinx.coroutines.launch

// MARK: - AdRevenueShare helper (mirrors iOS AdRevenueShare.canAccessAdMarketplace)
// Influencer+ personal accounts can access creator tools
private fun canAccessAdMarketplace(tier: UserTier): Boolean {
    return tier in setOf(
        UserTier.INFLUENCER, UserTier.AMBASSADOR, UserTier.ELITE,
        UserTier.PARTNER, UserTier.LEGENDARY, UserTier.TOP_CREATOR,
        UserTier.FOUNDER, UserTier.CO_FOUNDER
    )
}

// Ambassador tiers get the full referral dashboard (mirrors iOS ReferralButton.isAmbassador)
private fun isAmbassadorTier(tier: UserTier): Boolean {
    return tier in setOf(
        UserTier.INFLUENCER, UserTier.AMBASSADOR, UserTier.ELITE,
        UserTier.PARTNER, UserTier.LEGENDARY, UserTier.TOP_CREATOR,
        UserTier.FOUNDER, UserTier.CO_FOUNDER
    )
}

// MARK: - SettingsView

@Composable
fun SettingsView(
    currentUser: BasicUserInfo,
    authService: AuthService,
    onDismiss: () -> Unit,
    onSignOutSuccess: () -> Unit,
    onShowWallet: (() -> Unit)? = null,
    onShowMySubscriptions: (() -> Unit)? = null,
    onShowAdOpportunities: (() -> Unit)? = null,
    onShowManageAccount: (() -> Unit)? = null,
    onShowReferralDashboard: (() -> Unit)? = null,
    coinBalance: Int = 0,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("stitch_settings", Context.MODE_PRIVATE) }

    var showingSignOutConfirmation by remember { mutableStateOf(false) }
    var isSigningOut by remember { mutableStateOf(false) }
    var showingError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showingReferralDashboard by remember { mutableStateOf(false) }
    var showingWallet by remember { mutableStateOf(false) }

    // Inline wallet — mirrors iOS .sheet(isPresented: $showingWallet)
    if (showingWallet) {
        WalletView(
            userID = currentUser.id,
            userTier = currentUser.tier,
            onDismiss = { showingWallet = false }
        )
        return
    }

    // Show referral dashboard inline if no external handler provided
    if (showingReferralDashboard) {
        ReferralDashboardView(
            userID = currentUser.id,
            onDismiss = { showingReferralDashboard = false }
        )
        return
    }

    var isHapticEnabled by remember { mutableStateOf(prefs.getBoolean("hapticFeedbackEnabled", true)) }
    var isNotificationsEnabled by remember { mutableStateOf(prefs.getBoolean("notificationsEnabled", true)) }

    // Mirrors iOS: isBusiness = currentUser.isBusiness
    val isBusiness = remember(currentUser) { currentUser.isBusiness }

    // Mirrors iOS: isCreator = AdRevenueShare.canAccessAdMarketplace(tier) || isDeveloper
    val isCreator = remember(currentUser) {
        canAccessAdMarketplace(currentUser.tier)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(onDismiss = onDismiss)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                ProfileHeader(user = currentUser)

                WalletSection(
                    user = currentUser,
                    isCreator = isCreator,
                    coinBalance = coinBalance,
                    onShowWallet = onShowWallet ?: { showingWallet = true },
                    onShowManageAccount = onShowManageAccount
                )

                // Business-only section
                if (isBusiness) {
                    BusinessSection(user = currentUser)
                }

                // Creator section (Influencer+ personal only, mirrors iOS)
                if (!isBusiness && isCreator) {
                    CreatorSection(user = currentUser, onShowAdOpportunities = onShowAdOpportunities)
                }

                // Subscriptions (personal only)
                if (!isBusiness) {
                    SubscriptionsSection(onShowMySubscriptions = onShowMySubscriptions)
                }

                // Social (personal only)
                if (!isBusiness) {
                    SocialSection(
                        user = currentUser,
                        onShowReferralDashboard = onShowReferralDashboard ?: { showingReferralDashboard = true }
                    )
                }

                PreferencesSection(
                    user = currentUser,
                    isHapticEnabled = isHapticEnabled,
                    onHapticChanged = { isHapticEnabled = it; prefs.edit().putBoolean("hapticFeedbackEnabled", it).apply() },
                    isNotificationsEnabled = isNotificationsEnabled,
                    onNotificationsChanged = { isNotificationsEnabled = it; prefs.edit().putBoolean("notificationsEnabled", it).apply() }
                )

                SupportSection(context = context)

                AboutSection(context = context)

                SignOutButton(isSigningOut = isSigningOut, onSignOut = { showingSignOutConfirmation = true })

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    if (showingSignOutConfirmation) {
        AlertDialog(
            onDismissRequest = { showingSignOutConfirmation = false },
            title = { Text("Sign Out", color = Color.White) },
            text = { Text("Are you sure you want to sign out?", color = StitchColors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showingSignOutConfirmation = false
                    scope.launch {
                        isSigningOut = true
                        try { authService.signOut(); onSignOutSuccess() }
                        catch (e: Exception) { errorMessage = "Failed to sign out: ${e.message}"; showingError = true }
                        finally { isSigningOut = false }
                    }
                }) { Text("Sign Out", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showingSignOutConfirmation = false }) { Text("Cancel", color = StitchColors.textSecondary) }
            },
            containerColor = StitchColors.surface
        )
    }

    if (showingError) {
        AlertDialog(
            onDismissRequest = { showingError = false },
            title = { Text("Error", color = Color.White) },
            text = { Text(errorMessage, color = StitchColors.textSecondary) },
            confirmButton = { TextButton(onClick = { showingError = false }) { Text("OK", color = StitchColors.primary) } },
            containerColor = StitchColors.surface
        )
    }
}

// MARK: - Top Bar

@Composable
private fun SettingsTopBar(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.clickable { onDismiss() }
        ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Back", tint = StitchColors.primary, modifier = Modifier.size(20.dp))
            Text("Back", color = StitchColors.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.width(60.dp))
    }
}

// MARK: - Profile Header

@Composable
private fun ProfileHeader(user: BasicUserInfo) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(16.dp)).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
            val imageUrl = user.profileImageURL
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(model = imageUrl, contentDescription = "Profile", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Icon(
                    imageVector = if (user.isBusiness) Icons.Default.Business else Icons.Default.Person,
                    contentDescription = "Profile", tint = Color.Gray, modifier = Modifier.size(36.dp)
                )
            }
        }

        // Verified business badge
        if (user.isBusiness) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(user.displayName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Icon(Icons.Default.Verified, contentDescription = "Verified", tint = StitchColors.primary, modifier = Modifier.size(18.dp))
            }
        } else {
            Text(user.displayName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Text("@${user.username}", fontSize = 14.sp, color = Color.Gray)

        // Business: category chip + clickable website. Personal: tier badge.
        if (user.isBusiness) {
            val biz = user.businessProfile
            if (biz != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Category chip
                    Text(
                        text = biz.categoryDisplay,
                        fontSize = 12.sp,
                        color = StitchColors.tierBusiness,
                        modifier = Modifier
                            .background(StitchColors.tierBusiness.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    // Website link
                    val context = LocalContext.current
                    val website = biz.websiteURL
                    if (!website.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(website)))
                            }
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFF007AFF), modifier = Modifier.size(12.dp))
                            Text(
                                text = website.removePrefix("https://").removePrefix("http://"),
                                fontSize = 12.sp,
                                color = Color(0xFF007AFF)
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.background(Color.Yellow.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Star, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(14.dp))
                Text(user.tier.displayName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Yellow)
            }
        }
    }
}

// MARK: - Wallet Section

@Composable
private fun WalletSection(user: BasicUserInfo, isCreator: Boolean, coinBalance: Int, onShowWallet: (() -> Unit)?, onShowManageAccount: (() -> Unit)?) {
    SettingsSectionContainer(title = "WALLET", icon = Icons.Default.CreditCard, iconColor = Color.Yellow) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { onShowWallet?.invoke() },
            shape = RoundedCornerShape(12.dp),
            color = Color.Gray.copy(alpha = 0.15f)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier.size(44.dp).background(Brush.linearGradient(listOf(Color.Yellow, Color(0xFFFF8C00))), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("🔥", fontSize = 20.sp) }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hype Coins", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("$coinBalance coins", fontSize = 14.sp, color = Color.Yellow)
                }
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
        }

        StyledSettingsRow(icon = Icons.Default.Language, title = "Manage Account", subtitle = "Profile, billing & security", iconColor = StitchColors.primary, onClick = { onShowManageAccount?.invoke() })

        if (isCreator) {
            StyledSettingsRow(icon = Icons.Default.AccountBalance, title = "Cash Out", subtitle = "Withdraw your earnings", iconColor = Color(0xFF34C759), onClick = { /* CashOutSheet */ })
        }
    }
}

// MARK: - Business Section

@Composable
private fun BusinessSection(user: BasicUserInfo) {
    SettingsSectionContainer(title = "BUSINESS", icon = Icons.Default.Business, iconColor = StitchColors.tierBusiness) {
        StyledSettingsRow(
            icon = Icons.Default.BarChart,
            title = "Analytics",
            subtitle = "Impressions, reach & performance",
            iconColor = StitchColors.primary,
            onClick = { /* Future: BusinessAnalyticsView */ }
        )
        StyledSettingsRow(
            icon = Icons.Default.Campaign,
            title = "My Campaigns",
            subtitle = "Create & manage ad campaigns",
            iconColor = Color(0xFFFF9500),
            onClick = { /* Future: BusinessCampaignsView */ }
        )
        StyledSettingsRow(
            icon = Icons.Default.CreditCard,
            title = "Ad Spend",
            subtitle = "Budget & billing overview",
            iconColor = Color.Yellow,
            onClick = { /* Future: BusinessAnalyticsView */ }
        )
        StyledSettingsRow(
            icon = Icons.Default.Edit,
            title = "Edit Business Profile",
            subtitle = "Brand name, category & website",
            iconColor = Color(0xFF9C27B0),
            onClick = { /* Future: AccountWebView */ }
        )

        // Quick Stats 2x2 Card
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BusinessStatCard("Campaigns", "—", Color(0xFFFF9500), Modifier.weight(1f))
                BusinessStatCard("Impressions", "—", StitchColors.primary, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BusinessStatCard("Total Spend", "—", Color.Yellow, Modifier.weight(1f))
                BusinessStatCard("Avg CPM", "—", Color(0xFF34C759), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BusinessStatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

// MARK: - Creator Section

@Composable
private fun CreatorSection(user: BasicUserInfo, onShowAdOpportunities: (() -> Unit)?) {
    SettingsSectionContainer(title = "CREATOR", icon = Icons.Default.Star, iconColor = Color(0xFF9C27B0)) {
        StyledSettingsRow(icon = Icons.Default.AttachMoney, title = "Ad Opportunities", subtitle = "Brand partnerships", iconColor = Color(0xFF34C759), onClick = { onShowAdOpportunities?.invoke() })
        StyledSettingsRow(icon = Icons.Default.People, title = "My Subscribers", subtitle = "People subscribed to you", iconColor = Color(0xFFFF2D55), onClick = { /* MySubscribersView */ })
        StyledSettingsRow(icon = Icons.Default.Settings, title = "Subscription Settings", subtitle = "Set price & manage subscribers", iconColor = Color(0xFFFF9500), onClick = { /* CreatorPricingSettingsView */ })
        StyledSettingsRow(icon = Icons.Default.Groups, title = "My Community", subtitle = "Create & manage your community", iconColor = Color(0xFF007AFF), onClick = { /* CreatorCommunitySettingsView */ })

        Row(
            modifier = Modifier.fillMaxWidth().background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Sub Revenue", fontSize = 12.sp, color = Color.Gray)
                Text("${getSubRevenuePercent(user.tier)}%", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34C759))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Ad Revenue", fontSize = 12.sp, color = Color.Gray)
                Text("${getAdRevenuePercent(user.tier)}%", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = StitchColors.primary)
            }
        }
    }
}

// MARK: - Subscriptions Section

@Composable
private fun SubscriptionsSection(onShowMySubscriptions: (() -> Unit)?) {
    SettingsSectionContainer(title = "SUBSCRIPTIONS", icon = Icons.Default.Favorite, iconColor = Color(0xFFFF2D55)) {
        StyledSettingsRow(icon = Icons.Default.Star, title = "My Subscriptions", subtitle = "Creators you support", iconColor = Color.Yellow, onClick = { onShowMySubscriptions?.invoke() })
    }
}

// MARK: - Social Section

@Composable
private fun SocialSection(
    user: BasicUserInfo,
    onShowReferralDashboard: (() -> Unit)?
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val referralService = remember { ReferralService() }
    var isGeneratingLink by remember { mutableStateOf(false) }

    fun handleSimpleShare() {
        scope.launch {
            isGeneratingLink = true
            try {
                val link = referralService.generateReferralLink(user.id)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link.shareText)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share your invite code"))
            } catch (e: Exception) {
                println("⚠️ REFERRAL: Share failed — ${e.message}")
            } finally {
                isGeneratingLink = false
            }
        }
    }

    SettingsSectionContainer(title = "SOCIAL", icon = Icons.Default.People, iconColor = Color(0xFF007AFF)) {

        // ReferralButton FIRST — mirrors iOS socialSection order
        if (isAmbassadorTier(user.tier)) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onShowReferralDashboard?.invoke() },
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.05f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).background(
                            Brush.linearGradient(listOf(Color(0xFF9C27B0).copy(alpha = 0.4f), StitchColors.primary.copy(alpha = 0.2f))),
                            CircleShape
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Campaign, contentDescription = null, tint = Color(0xFF9C27B0), modifier = Modifier.size(18.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Ambassador Program", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Track referrals & earn rewards", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }
        } else {
            // Simple invite — mirrors iOS ReferralButton simple share path
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !isGeneratingLink) { handleSimpleShare() },
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.05f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).background(StitchColors.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isGeneratingLink) {
                            CircularProgressIndicator(
                                color = StitchColors.primary,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.PersonAddAlt1, contentDescription = null, tint = StitchColors.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Invite Friends", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Share Stitch Social with friends", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                    }
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }
        }

        // People You May Know — subtitle matches iOS
        StyledSettingsRow(
            icon = Icons.Default.PersonAdd,
            title = "People You May Know",
            subtitle = "Based on mutual connections",
            iconColor = StitchColors.primary,
            onClick = { /* Future: PeopleYouMayKnowView */ }
        )

        StyledSettingsRow(
            icon = Icons.Default.Link,
            title = "Connected Accounts",
            subtitle = "Link social media",
            iconColor = Color(0xFF9C27B0),
            onClick = { /* Future */ }
        )
    }
}

// MARK: - Preferences Section

@Composable
private fun PreferencesSection(
    user: BasicUserInfo,
    isHapticEnabled: Boolean, onHapticChanged: (Boolean) -> Unit,
    isNotificationsEnabled: Boolean, onNotificationsChanged: (Boolean) -> Unit
) {
    var showingPrivacySettings by remember { mutableStateOf(false) }

    SettingsSectionContainer(title = "PREFERENCES", icon = Icons.Default.Tune, iconColor = Color.Gray) {
        SettingsToggleRow(icon = Icons.Default.Vibration, title = "Haptic Feedback", subtitle = "Vibrations", iconColor = StitchColors.primary, isOn = isHapticEnabled, onChanged = onHapticChanged)
        SettingsToggleRow(icon = Icons.Default.Notifications, title = "Notifications", subtitle = "Push alerts", iconColor = Color.Red, isOn = isNotificationsEnabled, onChanged = onNotificationsChanged)
        StyledSettingsRow(
            icon = Icons.Default.Visibility, title = "Privacy", subtitle = "Account visibility",
            iconColor = Color(0xFF007AFF),
            onClick = { showingPrivacySettings = true }
        )
    }

    if (showingPrivacySettings) {
        // Future: PrivacySettingsView sheet
        // ModalBottomSheet(onDismissRequest = { showingPrivacySettings = false }) {
        //     PrivacySettingsView(userID = user.id)
        // }
    }
}

// MARK: - Support Section (with URL handling)

@Composable
private fun SupportSection(context: Context) {
    SettingsSectionContainer(title = "SUPPORT", icon = Icons.Default.HelpOutline, iconColor = Color(0xFF007AFF)) {
        StyledSettingsRow(icon = Icons.Default.HelpOutline, title = "Help & Support", subtitle = "Get help", iconColor = Color(0xFF007AFF), onClick = { /* Future */ })
        StyledSettingsRow(
            icon = Icons.Default.Description, title = "Privacy Policy", subtitle = "How we use data", iconColor = Color.Gray,
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://stitchsocial.me/privacy"))) }
        )
        StyledSettingsRow(
            icon = Icons.Default.Description, title = "Terms of Service", subtitle = "Terms & conditions", iconColor = Color.Gray,
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://stitchsocial.me/privacy"))) }
        )
    }
}

// MARK: - About Section

@Composable
private fun AboutSection(context: Context) {
    val version = remember { try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0" } catch (e: Exception) { "1.0" } }
    val buildNumber = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode.toString()
            else @Suppress("DEPRECATION") info.versionCode.toString()
        } catch (e: Exception) { "1" }
    }

    SettingsSectionContainer(title = "ABOUT", icon = Icons.Default.Info, iconColor = Color.Gray) {
        Row(modifier = Modifier.fillMaxWidth().background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Version", fontSize = 16.sp, color = Color.Gray)
            Text(version, fontSize = 16.sp, color = Color.White)
        }
        Row(modifier = Modifier.fillMaxWidth().background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Build", fontSize = 16.sp, color = Color.Gray)
            Text(buildNumber, fontSize = 16.sp, color = Color.White)
        }
    }
}

// MARK: - Sign Out Button

@Composable
private fun SignOutButton(isSigningOut: Boolean, onSignOut: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(enabled = !isSigningOut) { onSignOut() }, shape = RoundedCornerShape(12.dp), color = Color.Red.copy(alpha = 0.15f)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign Out", tint = if (isSigningOut) Color.Gray else Color.Red, modifier = Modifier.size(18.dp))
            Text("Sign Out", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (isSigningOut) Color.Gray else Color.Red)
            Spacer(modifier = Modifier.weight(1f))
            if (isSigningOut) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) }
        }
    }
}

// MARK: - Reusable Components

@Composable
private fun SettingsSectionContainer(title: String, icon: ImageVector, iconColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(14.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray, letterSpacing = 1.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun StyledSettingsRow(icon: ImageVector, title: String, subtitle: String, iconColor: Color, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp), color = Color.Gray.copy(alpha = 0.1f)) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, color = Color.White)
                Text(subtitle, fontSize = 13.sp, color = Color.Gray)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SettingsToggleRow(icon: ImageVector, title: String, subtitle: String, iconColor: Color, isOn: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Color.White)
            Text(subtitle, fontSize = 13.sp, color = Color.Gray)
        }
        Switch(
            checked = isOn, onCheckedChange = onChanged,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = StitchColors.primary,
                uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
            )
        )
    }
}

// MARK: - Revenue Helpers

private fun getSubRevenuePercent(tier: UserTier): Int = when (tier) {
    UserTier.TOP_CREATOR, UserTier.FOUNDER, UserTier.CO_FOUNDER -> 100
    UserTier.LEGENDARY -> 95
    UserTier.PARTNER -> 90
    UserTier.ELITE -> 85
    UserTier.AMBASSADOR, UserTier.INFLUENCER -> 80
    else -> 70
}

private fun getAdRevenuePercent(tier: UserTier): Int = when (tier) {
    UserTier.TOP_CREATOR, UserTier.FOUNDER, UserTier.CO_FOUNDER, UserTier.LEGENDARY -> 50
    UserTier.PARTNER -> 45
    UserTier.ELITE, UserTier.AMBASSADOR -> 40
    UserTier.INFLUENCER -> 35
    else -> 25
}