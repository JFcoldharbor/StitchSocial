package com.stitchsocial.club.views

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.ui.theme.DismissX
import com.stitchsocial.club.ui.theme.Spacing

/**
 * The Settings sub-pages — iOS parity (Views/SettingsPages.swift).
 *
 * The handoff's sub-page pattern: a back control and an inline title, an
 * optional summary card, then grouped text-only rows with the most important
 * group first. No icon tiles below the root — the tiles are how the root
 * distinguishes twelve destinations at a glance, and a page with six rows in
 * one subject does not need them.
 */

@Composable
private fun SettingsSubPage(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.bg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            DismissX(onClick = onDismiss, showsScrim = false)
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textPrimary,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

// ============================================================
// Accounts Center
// ============================================================

@Composable
fun AccountsCenterPage(
    displayName: String,
    isEmailVerified: Boolean,
    linkedAccountCount: Int,
    onDismiss: () -> Unit,
    onPersonalDetails: () -> Unit,
    onResendVerification: () -> Unit,
    onLinkedAccounts: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    var resent by remember { mutableStateOf(false) }

    SettingsSubPage(title = "Accounts Center", onDismiss = onDismiss) {
        SettingsSectionHeader("Personal")
        SettingsGroupCard {
            SettingsRow(
                title = "Personal details",
                subtitle = "Name, handle, bio and photo",
                onClick = onPersonalDetails,
            )
        }

        SettingsSectionHeader("Login & security")
        SettingsGroupCard {
            // Shows verification state, not the address. The address is on the
            // account, not a setting, and printing it here only creates a
            // shoulder-surfing surface.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Email", fontSize = 17.sp, color = AppTheme.colors.textPrimary)
                    Text(
                        text = if (isEmailVerified) "Verified"
                               else "Not verified — we need this to recover your account",
                        fontSize = 13.sp,
                        color = if (isEmailVerified) AppTheme.colors.textSecondary
                                else SettingsTokens.destructive,
                    )
                }
                if (!isEmailVerified) {
                    TextButton(onClick = { resent = true; onResendVerification() }) {
                        Text(
                            if (resent) "Sent" else "Resend",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SettingsTokens.link,
                        )
                    }
                }
            }
            SettingsDivider(inset = Spacing.md)
            SettingsRow(
                title = "Two-factor authentication",
                subtitle = "Coming soon",
                value = "Off",
                showChevron = false,
                titleColor = AppTheme.colors.textSecondary,
            )
        }

        SettingsSectionHeader("Linked accounts")
        SettingsGroupCard {
            SettingsRow(
                title = "Linked Stitch accounts",
                subtitle = "Switch between a personal and a business account",
                value = if (linkedAccountCount > 0) linkedAccountCount.toString() else null,
                onClick = onLinkedAccounts,
            )
        }

        SettingsSectionHeader("Ownership & control")
        SettingsGroupCard {
            SettingsRow(
                // The literal words, deliberately. App Review rejected an iOS
                // build once for burying this behind a vaguer label.
                title = "Delete Account",
                subtitle = "Permanently delete your profile, videos, and data",
                titleColor = SettingsTokens.destructive,
                showChevron = false,
                onClick = onDeleteAccount,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ============================================================
// Creator Studio
// ============================================================

@Composable
fun CreatorStudioPage(
    isCreator: Boolean,
    onDismiss: () -> Unit,
    onCommunity: () -> Unit,
    onSubscribers: () -> Unit,
    onAdOpportunities: () -> Unit,
    onBrandMarketplace: () -> Unit,
    onSubscriptionSettings: () -> Unit,
) {
    SettingsSubPage(title = "Creator Studio", onDismiss = onDismiss) {
        // A viewer gets an invitation, not a locked door. This page is also
        // how they find out any of it exists.
        if (!isCreator) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .padding(top = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text("Start creating", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                Text(
                    "Post, build a thread, and grow a following. Subscriptions, brand work and payouts unlock as your clout grows.",
                    fontSize = 14.sp,
                    color = AppTheme.colors.textSecondary,
                )
            }
        }

        SettingsSectionHeader("Audience")
        SettingsGroupCard {
            SettingsRow(title = "My community", subtitle = "Create and manage your community", onClick = onCommunity)
            SettingsDivider(inset = Spacing.md)
            SettingsRow(title = "My subscribers", subtitle = "People subscribed to you", onClick = onSubscribers)
        }

        SettingsSectionHeader("Brand work")
        SettingsGroupCard {
            SettingsRow(title = "Ad opportunities", subtitle = "Brand partnerships", onClick = onAdOpportunities)
            SettingsDivider(inset = Spacing.md)
            SettingsRow(title = "Brand marketplace", subtitle = "Apply to paid creator briefs", onClick = onBrandMarketplace)
        }

        SettingsSectionHeader("Pricing")
        SettingsGroupCard {
            SettingsRow(
                title = "Subscription settings",
                subtitle = "Set your price and manage subscribers",
                onClick = onSubscriptionSettings,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ============================================================
// Earnings & payouts
// ============================================================

@Composable
fun EarningsPayoutsPage(
    payoutsEnabled: Boolean,
    onDismiss: () -> Unit,
    onSubscriptionTiers: () -> Unit,
) {
    SettingsSubPage(title = "Earnings & payouts", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .padding(top = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text("Payouts", fontSize = 13.sp, color = AppTheme.colors.textSecondary)
            Text(
                if (payoutsEnabled) "Ready" else "Not set up",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.textPrimary,
            )
            if (!payoutsEnabled) {
                Text(
                    "Earnings are held until a payout method is connected.",
                    fontSize = 13.sp,
                    color = SettingsTokens.destructive,
                )
            }
        }

        SettingsSectionHeader("Payouts")
        SettingsGroupCard {
            SettingsRow(
                title = "Payout method",
                subtitle = if (payoutsEnabled) "Connected" else "Coming soon",
                badgeCount = if (payoutsEnabled) 0 else 1,
                showChevron = false,
                titleColor = AppTheme.colors.textSecondary,
            )
            SettingsDivider(inset = Spacing.md)
            SettingsRow(
                title = "Schedule",
                subtitle = "Monthly, once you clear the minimum",
                showChevron = false,
                titleColor = AppTheme.colors.textSecondary,
            )
            SettingsDivider(inset = Spacing.md)
            SettingsRow(
                title = "Tax information",
                subtitle = "Handled by Stripe during payout setup",
                showChevron = false,
                titleColor = AppTheme.colors.textSecondary,
            )
        }

        SettingsSectionHeader("Pricing")
        SettingsGroupCard {
            SettingsRow(
                title = "Subscription tiers",
                subtitle = "What supporters pay, in coins",
                onClick = onSubscriptionTiers,
            )
        }

        SettingsSectionHeader("History")
        SettingsGroupCard {
            SettingsRow(title = "Payout history", subtitle = "Coming soon", showChevron = false, titleColor = AppTheme.colors.textSecondary)
            SettingsDivider(inset = Spacing.md)
            SettingsRow(title = "Statements & receipts", subtitle = "Coming soon", showChevron = false, titleColor = AppTheme.colors.textSecondary)
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ============================================================
// Notifications
// ============================================================

/**
 * Status and one action — no toggle.
 *
 * The switch this replaces wrote a SharedPreferences key nothing read, never
 * asked Android for the runtime permission and never touched the FCM token.
 * It did nothing in either position, which is worse than absent: it told you
 * notifications were off while they kept arriving.
 */
@Composable
fun NotificationSettingsPage(
    isGranted: Boolean,
    canRequest: Boolean,
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    SettingsSubPage(title = "Notifications", onDismiss = onDismiss) {
        SettingsGroupCard {
            Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    when {
                        isGranted -> "Notifications are on"
                        !canRequest -> "Notifications are off"
                        else -> "Notifications are not set up"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary,
                )
                Text(
                    when {
                        isGranted -> "You'll hear about hype, replies, and events you're going to."
                        !canRequest -> "Android is blocking them. You can turn them back on in system settings."
                        else -> "Turn them on to hear when someone hypes, replies, or goes live."
                    },
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary,
                )
            }
            SettingsDivider(inset = Spacing.md)
            SettingsRow(
                title = if (canRequest && !isGranted) "Turn on notifications" else "Open system settings",
                titleColor = SettingsTokens.link,
                showChevron = false,
                onClick = if (canRequest && !isGranted) onRequestPermission else onOpenSystemSettings,
            )
        }

        Text(
            "Per-type controls — hype, replies, events — are coming. Today it is all of them or none.",
            fontSize = 13.sp,
            color = AppTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = Spacing.md).padding(top = Spacing.sm),
        )
        Spacer(Modifier.height(40.dp))
    }
}

// ============================================================
// Playback & data
// ============================================================

@Composable
fun PlaybackPage(onDismiss: () -> Unit) {
    SettingsSubPage(title = "Playback & data", onDismiss = onDismiss) {
        Text(
            "Playback and data controls are coming. Videos currently stream at the best quality your connection allows.",
            fontSize = 13.sp,
            color = AppTheme.colors.textSecondary,
            modifier = Modifier.padding(Spacing.md),
        )
    }
}

// ============================================================
// Terms & policies
// ============================================================

@Composable
fun TermsAndPoliciesPage(onDismiss: () -> Unit, onReplayOnboarding: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    SettingsSubPage(title = "Terms & policies", onDismiss = onDismiss) {
        SettingsGroupCard {
            SettingsRow(
                title = "Privacy Policy",
                titleColor = SettingsTokens.link,
                showChevron = false,
                onClick = { open("https://stitchsocial.me/privacy") },
            )
            SettingsDivider(inset = Spacing.md)
            SettingsRow(
                // These were the same URL before. Two links to one document is
                // a Play review risk and, more simply, a lie about one of them.
                title = "Terms of Service",
                titleColor = SettingsTokens.link,
                showChevron = false,
                onClick = { open("https://stitchsocial.me/terms") },
            )
        }

        SettingsGroupGap()
        SettingsGroupCard {
            SettingsRow(
                title = "Replay the welcome walkthrough",
                titleColor = SettingsTokens.link,
                showChevron = false,
                onClick = onReplayOnboarding,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
