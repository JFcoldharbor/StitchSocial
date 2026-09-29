package com.stitchsocial.club.views

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.services.AdRevenueShare
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.LinkedAccountManager
import com.stitchsocial.club.services.SubscriptionService
import kotlinx.coroutines.launch

/**
 * Everything Settings can open, hosted once.
 *
 * SettingsRootView and the pages take plain callbacks so they stay testable
 * and free of service lookups. This is where the wiring lives: the state each
 * row reads, and the destination each one opens.
 *
 * It keeps the old SettingsView's entry signature so ProfileView changes by
 * one line.
 */
@Composable
fun SettingsHost(
    currentUser: BasicUserInfo,
    authService: AuthService,
    onDismiss: () -> Unit,
    onSignOutSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Which page is on top. One nullable enum rather than a boolean per
    // destination — the screen this replaces had eleven of those, two of
    // which nothing ever consumed, so three rows were silent dead taps.
    var page by remember { mutableStateOf<SettingsPage?>(null) }

    var subscriptionCount by remember { mutableIntStateOf(0) }
    var notificationsGranted by remember { mutableStateOf(false) }
    var emailVerified by remember { mutableStateOf(true) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val linkedAccounts by LinkedAccountManager.getInstance(context).accounts.collectAsState()
    val isCreator = AdRevenueShare.canAccessAds(currentUser.tier)
    val isAmbassador = currentUser.tier in listOf(
        UserTier.INFLUENCER, UserTier.AMBASSADOR, UserTier.ELITE, UserTier.PARTNER,
        UserTier.LEGENDARY, UserTier.TOP_CREATOR, UserTier.FOUNDER, UserTier.CO_FOUNDER,
    )

    // Real permission state, read on open — not a stored boolean. Below
    // Android 13 there is no runtime permission and notifications are on.
    fun readNotificationState() {
        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    LaunchedEffect(currentUser.id) {
        readNotificationState()
        val auth = FirebaseAuth.getInstance().currentUser
        runCatching { auth?.reload() }
        emailVerified = FirebaseAuth.getInstance().currentUser?.isEmailVerified ?: true
        runCatching {
            subscriptionCount = SubscriptionService.shared.fetchMySubscriptions(currentUser.id).size
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsRootView(
            userID = currentUser.id,
            displayName = currentUser.displayName,
            username = currentUser.username,
            email = FirebaseAuth.getInstance().currentUser?.email ?: "",
            isEmailVerified = emailVerified,
            userTier = currentUser.tier,
            isBusiness = currentUser.isBusiness,
            isPrivateAccount = currentUser.isPrivate,
            notificationsEnabled = notificationsGranted,
            subscriptionCount = subscriptionCount,
            isAmbassador = isAmbassador,
            onDismiss = onDismiss,
            onSignOut = {
                scope.launch {
                    runCatching { authService.signOut() }
                        .onSuccess { onSignOutSuccess() }
                        .onFailure { errorText = "Couldn't sign out: ${it.message}" }
                }
            },
            onOpenSubscriptions = { page = SettingsPage.SUBSCRIPTIONS },
            onOpenPrivacy = { page = SettingsPage.PRIVACY },
            onOpenWallet = { page = SettingsPage.WALLET },
            onOpenNotificationSettings = { page = SettingsPage.NOTIFICATIONS },
            onOpenCreatorStudio = { page = SettingsPage.CREATOR_STUDIO },
            onOpenEarnings = { page = SettingsPage.EARNINGS },
            onOpenReferral = { page = SettingsPage.REFERRAL },
            onOpenAccountsCenter = { page = SettingsPage.ACCOUNTS_CENTER },
            onOpenAccountSwitcher = { page = SettingsPage.ACCOUNT_SWITCHER },
            onOpenTerms = { page = SettingsPage.TERMS },
            onOpenPlayback = { page = SettingsPage.PLAYBACK },
            onOpenHelp = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:support@stitchsocial.me?subject=Stitch%20Social%20support")
                        }
                    )
                }
            },
            onTaskAction = { task ->
                // Only the email task can be acted on today; the rest deep-link
                // to the row that fixes them when they exist.
                when (task.destination) {
                    SettingsDestination.ACCOUNTS_CENTER -> page = SettingsPage.ACCOUNTS_CENTER
                    SettingsDestination.EARNINGS -> page = SettingsPage.EARNINGS
                    else -> Unit
                }
            },
        )

        page?.let { current ->
            Box(modifier = Modifier.fillMaxSize().zIndex(10f)) {
                val close = { page = null }
                when (current) {
                    SettingsPage.ACCOUNTS_CENTER -> AccountsCenterPage(
                        displayName = currentUser.displayName,
                        isEmailVerified = emailVerified,
                        linkedAccountCount = linkedAccounts.size,
                        onDismiss = close,
                        onPersonalDetails = { page = SettingsPage.EDIT_PROFILE },
                        onResendVerification = {
                            scope.launch {
                                runCatching { FirebaseAuth.getInstance().currentUser?.sendEmailVerification() }
                            }
                        },
                        onLinkedAccounts = { page = SettingsPage.ACCOUNT_SWITCHER },
                        onDeleteAccount = { deleteConfirm = true },
                    )

                    SettingsPage.CREATOR_STUDIO -> CreatorStudioPage(
                        isCreator = isCreator,
                        onDismiss = close,
                        onCommunity = { page = SettingsPage.COMMUNITY },
                        onSubscribers = { page = SettingsPage.SUBSCRIBERS },
                        onAdOpportunities = { page = SettingsPage.AD_OPPORTUNITIES },
                        onBrandMarketplace = { page = SettingsPage.BRAND_MARKETPLACE },
                        onSubscriptionSettings = { page = SettingsPage.SUBSCRIPTION_SETTINGS },
                    )

                    SettingsPage.EARNINGS -> EarningsPayoutsPage(
                        payoutsEnabled = false,
                        onDismiss = close,
                        onSubscriptionTiers = { page = SettingsPage.SUBSCRIPTION_SETTINGS },
                    )

                    SettingsPage.NOTIFICATIONS -> NotificationSettingsPage(
                        isGranted = notificationsGranted,
                        canRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        onDismiss = close,
                        onRequestPermission = { openAppNotificationSettings(context) },
                        onOpenSystemSettings = { openAppNotificationSettings(context) },
                    )

                    SettingsPage.PLAYBACK -> PlaybackPage(onDismiss = close)

                    SettingsPage.TERMS -> TermsAndPoliciesPage(
                        onDismiss = close,
                        onReplayOnboarding = { close(); onDismiss() },
                    )

                    SettingsPage.PRIVACY -> PrivacySettingsView(userID = currentUser.id, onDismiss = close)
                    SettingsPage.WALLET -> WalletView(
                        userID = currentUser.id, userTier = currentUser.tier, onDismiss = close
                    )
                    SettingsPage.SUBSCRIPTIONS -> MySubscriptionsView(userID = currentUser.id, onDismiss = close)
                    SettingsPage.SUBSCRIBERS -> MySubscribersView(creatorID = currentUser.id, onDismiss = close)
                    SettingsPage.SUBSCRIPTION_SETTINGS -> CreatorSubscriptionSettingsView(
                        creatorID = currentUser.id, creatorTier = currentUser.tier, onDismiss = close
                    )
                    SettingsPage.COMMUNITY -> CreatorCommunitySettingsView(
                        creatorID = currentUser.id,
                        creatorUsername = currentUser.username,
                        creatorDisplayName = currentUser.displayName,
                        creatorTier = currentUser.tier,
                        onDismiss = close,
                    )
                    SettingsPage.AD_OPPORTUNITIES -> AdOpportunitiesView(user = currentUser, onDismiss = close)
                    SettingsPage.BRAND_MARKETPLACE -> CreatorCampaignsHubView(
                        currentUserID = currentUser.id,
                        isBrandAccount = currentUser.isBusiness,
                        brandName = currentUser.displayName,
                        brandLogoURL = currentUser.profileImageURL,
                        onDismiss = close,
                    )
                    SettingsPage.REFERRAL -> ReferralDashboardView(userID = currentUser.id, onDismiss = close)
                    SettingsPage.ACCOUNT_SWITCHER -> AccountSwitcherView(onDismiss = close)
                    SettingsPage.EDIT_PROFILE -> EditProfileView(
                        userID = currentUser.id,
                        currentUserName = currentUser.displayName,
                        currentUsername = currentUser.username,
                        currentUserImage = currentUser.profileImageURL,
                        currentBio = currentUser.bio,
                        currentIsPrivate = currentUser.isPrivate,
                        onCancel = close,
                    )
                }
            }
        }
    }

    if (deleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { androidx.compose.material3.Text("Delete account") },
            text = {
                androidx.compose.material3.Text(
                    "This deletes your account and everything in it — videos, collections, coins and community history. It cannot be undone."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleteConfirm = false
                    scope.launch {
                        runCatching { authService.deleteAccount() }
                            .onSuccess { onSignOutSuccess() }
                            .onFailure { errorText = "Couldn't delete the account: ${it.message}" }
                    }
                }) {
                    androidx.compose.material3.Text("Delete", color = SettingsTokens.destructive)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirm = false }) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }

    errorText?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { errorText = null },
            title = { androidx.compose.material3.Text("Something went wrong") },
            text = { androidx.compose.material3.Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { errorText = null }) {
                    androidx.compose.material3.Text("OK")
                }
            },
        )
    }
}

enum class SettingsPage {
    ACCOUNTS_CENTER, CREATOR_STUDIO, EARNINGS, NOTIFICATIONS, PLAYBACK, TERMS,
    PRIVACY, WALLET, SUBSCRIPTIONS, SUBSCRIBERS, SUBSCRIPTION_SETTINGS,
    COMMUNITY, AD_OPPORTUNITIES, BRAND_MARKETPLACE, REFERRAL, ACCOUNT_SWITCHER,
    EDIT_PROFILE,
}

/**
 * Android has no in-app way to re-ask once the permission has been refused,
 * so both the "turn on" and the "already answered" paths land in the same
 * place: the app's own notification settings.
 */
private fun openAppNotificationSettings(context: android.content.Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        }
        context.startActivity(intent)
    }
}
