/*
 * SettingsView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 6: View — Full Settings Screen
 * Mirror of Swift SettingsView exactly.
 * Dependencies: AuthService, HypeCoinCoordinator, WalletView, SearchView, CommunitySettings
 *
 * Sections:
 *   Profile Header  (avatar, name, tier/business badge, follower counts)
 *   Wallet          (HypeCoins balance, Manage Account, Cash Out)
 *   Business        (Analytics, Campaigns, Ad Spend, Quick Stats — business only)
 *   Creator         (Ad Opportunities, Subscribers, Sub Settings, Community, Revenue % — creator tier+)
 *   Subscriptions   (My Subscriptions — personal only)
 *   Social          (PYMK, Connected Accounts)
 *   Preferences     (Haptic, Notifications, Privacy)
 *   Support         (Help, Privacy Policy, Terms)
 *   About           (Version, Build)
 *   Sign Out
 *
 * FIXED vs previous version:
 *   - Revenue % now dynamic via SubscriptionRevenueShare + AdRevenueShare (was hardcoded 70/60)
 *   - All navigation destinations wired: WalletView, SearchView (PYMK), CreatorCommunitySettingsView
 *   - AdRevenueShare added (mirrors iOS — pure computation, zero reads)
 *
 * CACHING: No Firestore reads here — user data from currentUser param.
 *          HypeCoin balance: HypeCoinCoordinator real-time listener (60s TTL).
 */

package com.stitchsocial.club.views

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.stitchsocial.club.FollowManager
import com.stitchsocial.club.SearchView
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.SubscriptionRevenueShare
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.AdRevenueShare
import com.stitchsocial.club.services.HypeCoinCoordinator
import com.stitchsocial.club.coordination.NavigationCoordinator
import kotlinx.coroutines.launch


// ─────────────────────────────────────────────
// MARK: - SettingsView
// ─────────────────────────────────────────────

@Composable
fun SettingsView(
    currentUser: BasicUserInfo,
    authService: AuthService,
    navigationCoordinator: NavigationCoordinator? = null,
    onDismiss: () -> Unit,
    onSignOutSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("stitch_prefs", Context.MODE_PRIVATE) }

    // HypeCoinCoordinator owns balance (60s TTL + real-time listener)
    val coinCoordinator = remember { HypeCoinCoordinator.getInstance(context) }
    val coinBalanceObj by coinCoordinator.balance.collectAsState()
    val coinBalance = coinBalanceObj?.availableCoins ?: 0

    // Self-healing user state: starts from the prop, but re-fetches from
    // Firestore whenever Firebase Auth's active uid changes (e.g. after a
    // linked-account toggle). Without this, switching personal ↔ business
    // would leave Settings rendering the previous account's profile until
    // the parent ProfileView gets re-mounted.
    val activeUID by com.stitchsocial.club.services.LinkedAccountManager.getInstance(context).activeUID.collectAsState()
    var liveUser by remember(currentUser.id) { mutableStateOf(currentUser) }
    val userService = remember { com.stitchsocial.club.services.UserService(context) }
    LaunchedEffect(activeUID) {
        val uid = activeUID ?: return@LaunchedEffect
        if (uid != liveUser.id) {
            try {
                userService.getUserProfile(uid)?.let { liveUser = it }
            } catch (_: Exception) { /* keep last value on error */ }
        }
    }

    val isBusiness = liveUser.isBusiness ?: false
    // Matches iOS: AdRevenueShare.canAccessAds(tier:)
    val isCreator = !isBusiness && AdRevenueShare.canAccessAds(liveUser.tier)

    // Dynamic revenue shares — matches iOS computed vars
    val subRevenuePercent = (SubscriptionRevenueShare.creatorShare(liveUser.tier) * 100).toInt()
    val adRevenuePercent  = (AdRevenueShare.creatorShare(liveUser.tier) * 100).toInt()

    LaunchedEffect(liveUser.id) { coinCoordinator.configure(liveUser.id) }

    var hapticEnabled by remember { mutableStateOf(prefs.getBoolean("hapticFeedbackEnabled", true)) }
    var notificationsEnabled by remember { mutableStateOf(prefs.getBoolean("notificationsEnabled", true)) }

    var showSignOutDialog by remember { mutableStateOf(false) }
    var isSigningOut by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Navigation states — match iOS @State vars
    var showWallet by remember { mutableStateOf(false) }
    var showManageAccount by remember { mutableStateOf(false) }
    var showMySubscriptions by remember { mutableStateOf(false) }
    var showMySubscribers by remember { mutableStateOf(false) }
    var showAdOpportunities by remember { mutableStateOf(false) }
    var showCashOut by remember { mutableStateOf(false) }
    var showSubscriptionSettings by remember { mutableStateOf(false) }
    var showCommunitySettings by remember { mutableStateOf(false) }
    var showPrivacySettings by remember { mutableStateOf(false) }
    var showFriendSuggestions by remember { mutableStateOf(false) }
    var showBusinessAnalytics by remember { mutableStateOf(false) }
    var showBusinessCampaigns by remember { mutableStateOf(false) }
    var showMarketplace by remember { mutableStateOf(false) }
    var showAccountSwitcher by remember { mutableStateOf(false) }
    var showBadgePage by remember { mutableStateOf(false) }
    var showReferralDashboard by remember { mutableStateOf(false) }

    // Influencer+ tiers see the Refer Friends entry (iOS ReferralButton.isAmbassador)
    val isAmbassador = liveUser.tier in setOf(
        UserTier.INFLUENCER, UserTier.AMBASSADOR, UserTier.ELITE,
        UserTier.PARTNER, UserTier.LEGENDARY, UserTier.TOP_CREATOR,
        UserTier.FOUNDER, UserTier.CO_FOUNDER
    )

    val appVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0" }
        catch (_: Exception) { "1.0" }
    }
    val buildNumber = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString() }
        catch (_: Exception) { "1" }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── Main content ──────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {

            // Top Bar (matches iOS navigationBarItems back button)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 40.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.Cyan, modifier = Modifier.size(22.dp))
                }
                Text(
                    "Settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(40.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {

                // ── Profile Header ────────────────────────────────
                item { ProfileHeaderCard(liveUser) }

                // ── Wallet ────────────────────────────────────────
                item {
                    SSection("WALLET", Icons.Default.CreditCard, Color(0xFFFFD60A)) {
                        // Coin balance tap → WalletView
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { showWallet = true }
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Color(0xFFFFD60A), Color(0xFFFF9F0A)))),
                                contentAlignment = Alignment.Center
                            ) { Text("🔥", fontSize = 16.sp) }
                            Column(Modifier.weight(1f)) {
                                Text("Hype Coins", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text("$coinBalance coins", fontSize = 12.sp, color = Color(0xFFFFD60A))
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                        }
                        SNavRow(Icons.Default.Language, Color.Cyan, "Manage Account", "Profile, billing & security") { showManageAccount = true }
                        if (isCreator) SNavRow(Icons.Default.AttachMoney, Color(0xFF30D158), "Cash Out", "Withdraw your earnings") { showCashOut = true }
                    }
                }

                // ── Business ──────────────────────────────────────
                if (isBusiness) {
                    item {
                        SSection("BUSINESS", Icons.Default.Business, Color(0xFF64D2FF)) {
                            SNavRow(Icons.Default.BarChart, Color.Cyan, "Analytics", "Impressions, reach & performance") { showBusinessAnalytics = true }
                            SNavRow(Icons.Default.Campaign, Color(0xFFFF9F0A), "My Campaigns", "Create & manage ad campaigns") { showBusinessCampaigns = true }
                            SNavRow(Icons.Default.Groups, Color(0xFFFF2D87), "Creator Marketplace", "Post briefs, review applicants, pay creators") { showMarketplace = true }
                            SNavRow(Icons.Default.CreditCard, Color(0xFFFFD60A), "Ad Spend", "Budget & billing overview") { showBusinessAnalytics = true }
                            SNavRow(Icons.Default.Edit, Color(0xFFBF5AF2), "Edit Business Profile", "Brand name, category & website") { showManageAccount = true }
                            // Quick Stats 2×2 grid (matches iOS businessSection)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BizStatCard("Campaigns", "—", Color(0xFFFF9F0A), Modifier.weight(1f))
                                BizStatCard("Impressions", "—", Color.Cyan, Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BizStatCard("Total Spend", "—", Color(0xFFFFD60A), Modifier.weight(1f))
                                BizStatCard("Avg CPM", "—", Color(0xFF30D158), Modifier.weight(1f))
                            }
                        }
                    }
                }

                // ── Creator ───────────────────────────────────────
                if (!isBusiness && isCreator) {
                    item {
                        SSection("CREATOR", Icons.Default.Star, Color(0xFFBF5AF2)) {
                            SNavRow(Icons.Default.AttachMoney, Color(0xFF30D158), "Ad Opportunities", "Brand partnerships") { showAdOpportunities = true }
                            SNavRow(Icons.Default.Groups, Color(0xFFFF2D87), "Brand Marketplace", "Apply to paid creator briefs") { showMarketplace = true }
                            SNavRow(Icons.Default.Group, Color(0xFFFF2D55), "My Subscribers", "People subscribed to you") { showMySubscribers = true }
                            SNavRow(Icons.Default.Settings, Color(0xFFFF9F0A), "Subscription Settings", "Set price & manage subscribers") { showSubscriptionSettings = true }
                            SNavRow(Icons.Default.Forum, Color.Cyan, "My Community", "Create & manage your community") { showCommunitySettings = true }

                            // Revenue Share Card — DYNAMIC (matches iOS creatorSection revenue card)
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Sub Revenue", fontSize = 11.sp, color = Color.Gray)
                                    Text("$subRevenuePercent%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF30D158))
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Ad Revenue", fontSize = 11.sp, color = Color.Gray)
                                    Text("$adRevenuePercent%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Cyan)
                                }
                            }
                        }
                    }
                }

                // ── Subscriptions ─────────────────────────────────
                if (!isBusiness) {
                    item {
                        SSection("SUBSCRIPTIONS", Icons.Default.Favorite, Color(0xFFFF2D55)) {
                            SNavRow(Icons.Default.Star, Color(0xFFFFD60A), "My Subscriptions", "Creators you support") { showMySubscriptions = true }
                        }
                    }
                }

                // ── Social ────────────────────────────────────────
                if (!isBusiness) {
                    item {
                        SSection("SOCIAL", Icons.Default.People, Color(0xFF0A84FF)) {
                            // Refer Friends — Influencer+ only (iOS ReferralButton)
                            if (isAmbassador) {
                                SNavRow(
                                    Icons.Default.Campaign,
                                    Color(0xFFBF5AF2),
                                    "Refer Friends",
                                    "Share your link and earn rewards"
                                ) { showReferralDashboard = true }
                            }
                            SNavRow(Icons.Default.PersonAdd, Color.Cyan, "People You May Know", "Based on mutual connections") { showFriendSuggestions = true }
                            SNavRow(Icons.Default.Link, Color(0xFFBF5AF2), "Connected Accounts", "Link social media") { }
                        }
                    }
                }

                // ── Preferences ───────────────────────────────────
                item {
                    SSection("PREFERENCES", Icons.Default.Tune, Color.Gray) {
                        SToggleRow(Icons.Default.Vibration, Color.Cyan, "Haptic Feedback", "Vibrations", hapticEnabled) {
                            hapticEnabled = it; prefs.edit().putBoolean("hapticFeedbackEnabled", it).apply()
                        }
                        SToggleRow(Icons.Default.Notifications, Color(0xFFFF453A), "Notifications", "Push alerts", notificationsEnabled) {
                            notificationsEnabled = it; prefs.edit().putBoolean("notificationsEnabled", it).apply()
                        }
                        SNavRow(Icons.Default.Visibility, Color(0xFF0A84FF), "Privacy", "Account visibility") { showPrivacySettings = true }
                    }
                }

                // ── Support ───────────────────────────────────────
                item {
                    SSection("SUPPORT", Icons.Default.HelpOutline, Color(0xFF0A84FF)) {
                        SNavRow(Icons.Default.HelpOutline, Color(0xFF0A84FF), "Help & Support", "Get help") { }
                        SNavRow(Icons.Default.Description, Color.Gray, "Privacy Policy", "How we use data") { }
                        SNavRow(Icons.Default.Description, Color.Gray, "Terms of Service", "Terms & conditions") { }
                    }
                }

                // ── About ─────────────────────────────────────────
                item {
                    SSection("ABOUT", Icons.Default.Info, Color.Gray) {
                        SAboutRow("Version", appVersion)
                        SAboutRow("Build", buildNumber)
                    }
                }

                // ── Linked Accounts ───────────────────────────────
                item {
                    SSection("ACCOUNTS", Icons.Default.SupervisorAccount, Color.Cyan) {
                        // One-tap toggle row when both accounts are linked.
                        QuickAccountToggleRow()
                        SNavRow(
                            Icons.Default.SwapHoriz,
                            Color.Cyan,
                            "Manage linked accounts",
                            "Add or remove personal/business"
                        ) { showAccountSwitcher = true }
                    }
                }

                // ── Badges ────────────────────────────────────────
                item {
                    SSection("BADGES", Icons.Default.EmojiEvents, Color(0xFFFBBF24)) {
                        SNavRow(
                            Icons.Default.EmojiEvents,
                            Color(0xFFFBBF24),
                            "View badges",
                            "Earned and in-progress"
                        ) { showBadgePage = true }
                    }
                }

                // ── Sign Out ──────────────────────────────────────
                item {
                    Button(
                        onClick = { showSignOutDialog = true },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A).copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSigningOut
                    ) {
                        if (isSigningOut) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Logout, null, tint = Color(0xFFFF453A), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sign Out", color = Color(0xFFFF453A), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }

        // ── Navigation Destinations (matches iOS .fullScreenCover / .sheet) ──

        if (showWallet) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                WalletView(
                    userID = liveUser.id,
                    userTier = liveUser.tier,
                    onDismiss = { showWallet = false }
                )
            }
        }

        if (showManageAccount) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                AccountWebView(onDismiss = {
                    showManageAccount = false
                    // Mirror iOS: re-sync coin balance on return from web portal.
                    scope.launch {
                        try { com.stitchsocial.club.services.HypeCoinService.shared.syncBalance(liveUser.id) }
                        catch (_: Exception) { }
                    }
                })
            }
        }

        if (showCashOut) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                CashOutSheet(
                    userID = liveUser.id,
                    userTier = liveUser.tier,
                    availableCoins = coinBalance,
                    onDismiss = { showCashOut = false }
                )
            }
        }

        if (showMySubscribers) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                MySubscribersView(creatorID = liveUser.id, onDismiss = { showMySubscribers = false })
            }
        }

        if (showPrivacySettings) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                PrivacySettingsView(userID = liveUser.id, onDismiss = { showPrivacySettings = false })
            }
        }

        if (showAdOpportunities) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                AdOpportunitiesView(user = liveUser, onDismiss = { showAdOpportunities = false })
            }
        }

        if (showMarketplace) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                CreatorCampaignsHubView(
                    currentUserID = liveUser.id,
                    isBrandAccount = isBusiness,
                    brandName = liveUser.displayName.ifBlank { liveUser.username },
                    brandLogoURL = null,
                    onDismiss = { showMarketplace = false }
                )
            }
        }

        if (showAccountSwitcher) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                AccountSwitcherView(onDismiss = { showAccountSwitcher = false })
            }
        }

        if (showBadgePage) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                BadgePageView(
                    userID = liveUser.id,
                    isOwner = true,
                    stats = com.stitchsocial.club.services.RealUserStats(
                        clout = liveUser.clout,
                        // Other stats not yet plumbed into BasicUserInfo
                        // — server-side awards still fire from the user
                        // doc, this just powers the in-progress %.
                    ),
                    xp = liveUser.clout,  // fallback until xp field added
                    tierRaw = liveUser.tier.rawValue,
                    onDismiss = { showBadgePage = false }
                )
            }
        }

        if (showFriendSuggestions) {
            Box(Modifier.fillMaxSize().background(Color.Black).zIndex(10f)) {
                val followManager = remember { FollowManager(context) }
                SearchView(
                    followManager = followManager,
                    onUserTapped = { showFriendSuggestions = false },
                    onDismiss = { showFriendSuggestions = false }
                )
            }
        }

        if (showReferralDashboard) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                ReferralDashboardView(
                    userID = liveUser.id,
                    onDismiss = { showReferralDashboard = false }
                )
            }
        }

        if (showCommunitySettings) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                CreatorCommunitySettingsView(
                    creatorID = liveUser.id,
                    creatorUsername = liveUser.username,
                    creatorDisplayName = liveUser.displayName,
                    creatorTier = liveUser.tier,
                    onDismiss = { showCommunitySettings = false }
                )
            }
        }

        if (showSubscriptionSettings) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                CreatorSubscriptionSettingsView(
                    creatorID = liveUser.id,
                    creatorTier = liveUser.tier,
                    onDismiss = { showSubscriptionSettings = false }
                )
            }
        } // defined in SubscriptionViews.kt — add that file to the project

        if (showMySubscriptions) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                MySubscriptionsView(
                    userID = liveUser.id,
                    onDismiss = { showMySubscriptions = false }
                )
            }
        } // defined in SubscriptionViews.kt — add that file to the project

        // Error snackbar
        if (showError) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, tint = Color.Red, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(errorMessage, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showError = false }) {
                            Icon(Icons.Default.Close, null, tint = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    // ── Sign Out Dialog (matches iOS .alert "Sign Out") ───────────
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign Out", color = Color.White) },
            text = { Text("Are you sure you want to sign out?", color = Color.Gray) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    isSigningOut = true
                    scope.launch {
                        try {
                            authService.signOut()
                            onSignOutSuccess()
                        } catch (e: Exception) {
                            errorMessage = "Failed to sign out: ${e.message}"
                            showError = true
                            isSigningOut = false
                        }
                    }
                }) { Text("Sign Out", color = Color(0xFFFF453A)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel", color = Color.Gray) }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}

// ─────────────────────────────────────────────
// MARK: - Profile Header Card
// Matches iOS profileHeader: avatar + business/personal icon, name, tier badge, stats
// ─────────────────────────────────────────────

@Composable
private fun ProfileHeaderCard(user: BasicUserInfo) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Avatar (with verified-business badge overlay — iOS parity)
        Box(contentAlignment = Alignment.BottomEnd) {
            if (!user.profileImageURL.isNullOrEmpty()) {
                AsyncImage(
                    model = user.profileImageURL,
                    contentDescription = user.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(Color.Gray.copy(alpha = 0.3f), CircleShape)
                )
            } else {
                Box(
                    Modifier.size(64.dp).clip(CircleShape)
                        .background(Color.Gray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (user.isBusiness == true) Icons.Default.Business else Icons.Default.Person,
                        null, tint = Color.Gray, modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Verified business seal — iOS profileHeader line 261-268
            if (user.isBusiness == true && user.businessProfile?.isVerifiedBusiness == true) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = "Verified Business",
                        tint = Color(0xFF64D2FF),
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                // Empty placeholder so the avatar Box still aligns the same way
                Box(Modifier.size(0.dp))
            }
        }

        // Name + username
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(user.displayName, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("@${user.username}", fontSize = 13.sp, color = Color.Gray)
        }

        // Business: category chip + website link.  Personal: tier badge.  (iOS parity, line 292-333)
        val biz = user.businessProfile
        if (user.isBusiness == true && biz != null) {
            Text(
                biz.categoryDisplay,
                fontSize = 12.sp,
                color = Color(0xFF64D2FF),
                modifier = Modifier
                    .background(Color(0xFF64D2FF).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            val site = biz.websiteURL
            if (!site.isNullOrEmpty()) {
                val uriHandler = LocalUriHandler.current
                Row(
                    modifier = Modifier.clickable {
                        val href = if (site.startsWith("http")) site else "https://$site"
                        runCatching { uriHandler.openUri(href) }
                    },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Link, null, tint = Color(0xFF0A84FF), modifier = Modifier.size(12.dp))
                    Text(
                        site.removePrefix("https://").removePrefix("http://"),
                        fontSize = 12.sp,
                        color = Color(0xFF0A84FF)
                    )
                }
            }
        } else {
            TierBadge(user.tier)
        }

        // Stats row: followers · following · videos
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(label = "Stitchers", value = formatCount(user.followerCount ?: 0))
            StatDivider()
            StatItem(label = "Following", value = formatCount(user.followingCount ?: 0))
            StatDivider()
            StatItem(label = "Threads", value = formatCount(user.videoCount ?: 0))
        }
    }
}

@Composable
private fun TierBadge(tier: UserTier) {
    val (bg, label) = when (tier) {
        UserTier.FOUNDER, UserTier.CO_FOUNDER ->
            Brush.linearGradient(listOf(Color(0xFFFFD700), Color(0xFFFF6B35))) to tier.displayName
        UserTier.TOP_CREATOR ->
            Brush.linearGradient(listOf(Color(0xFFBF5AF2), Color(0xFFFF2D55))) to tier.displayName
        UserTier.ELITE, UserTier.PARTNER, UserTier.LEGENDARY ->
            Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0xFF30D158))) to tier.displayName
        UserTier.INFLUENCER, UserTier.AMBASSADOR ->
            Brush.linearGradient(listOf(Color.Cyan, Color(0xFF0A84FF))) to tier.displayName
        UserTier.BUSINESS ->
            Brush.linearGradient(listOf(Color(0xFF64D2FF), Color(0xFF0A84FF))) to "Business"
        else ->
            Brush.linearGradient(listOf(Color.Gray, Color.DarkGray)) to tier.displayName
    }
    Box(
        modifier = Modifier.background(bg, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.1f)))
}

// ─────────────────────────────────────────────
// MARK: - Reusable Row Components
// ─────────────────────────────────────────────

@Composable
private fun SSection(title: String, icon: ImageVector, iconTint: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(12.dp))
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray,
                letterSpacing = 0.8.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun SNavRow(icon: ImageVector, iconTint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Color.White)
            Text(subtitle, fontSize = 12.sp, color = Color.Gray)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SToggleRow(icon: ImageVector, iconTint: Color, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Color.White)
            Text(subtitle, fontSize = 12.sp, color = Color.Gray)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Color.Cyan,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF3A3A3C)
            )
        )
    }
}

@Composable
private fun SAboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = Color.Gray)
        Text(value, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
private fun BizStatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

// ─────────────────────────────────────────────
// MARK: - Helpers
// ─────────────────────────────────────────────

private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> "${"%.1f".format(count / 1_000_000.0)}M"
    count >= 1_000     -> "${"%.1f".format(count / 1_000.0)}K"
    else               -> count.toString()
}