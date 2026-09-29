package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.ui.theme.DismissX
import com.stitchsocial.club.ui.theme.Spacing

/**
 * Settings — one permanent place for everything. iOS parity
 * (Views/SettingsRootView.swift), built from the same design handoff.
 *
 * The principle, and the reason the structure is worth more than the pixels:
 * every setting lives in the same place for every account, forever. Nothing
 * is shown or hidden by role. The only thing that ever appears or disappears
 * is the To-finish card, and only for tasks that BLOCK something.
 *
 * What this replaces had seven sections of full-width cards, four of them
 * gated by role, so a setting sat at a different height — or nowhere —
 * depending on who was looking.
 *
 * Rows for things this app has not built yet say "Coming soon" rather than
 * being left out. A place that exists before the setting does is the whole
 * point: nothing moves when it arrives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootView(
    userID: String,
    displayName: String,
    username: String,
    email: String,
    isEmailVerified: Boolean,
    userTier: UserTier,
    isBusiness: Boolean,
    isPrivateAccount: Boolean,
    notificationsEnabled: Boolean,
    subscriptionCount: Int,
    onDismiss: () -> Unit,
    onSignOut: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenCreatorStudio: () -> Unit,
    onOpenEarnings: () -> Unit,
    onOpenReferral: () -> Unit,
    onOpenAccountsCenter: () -> Unit,
    onOpenAccountSwitcher: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenHelp: () -> Unit,
    onTaskAction: (SettingsTask) -> Unit,
    isAmbassador: Boolean = false,
) {
    val context = LocalContext.current
    var showSignOut by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var comingSoon by remember { mutableStateOf<String?>(null) }

    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "Stitch ${info.versionName} (${@Suppress("DEPRECATION") info.versionCode})"
        }.getOrDefault("Stitch")
    }

    // The only conditional content on the screen. Two kinds can be produced;
    // see SettingsTask for why the other three are declared and never are.
    val tasks = remember(isEmailVerified, userTier, isBusiness) {
        buildList {
            if (email.isNotBlank() && !isEmailVerified) add(SettingsTask.emailUnverified(email))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.bg),
    ) {
        // Chrome. A full-screen surface closes with an X, top-start — the
        // rule from DismissControls, which this screen was the reason for on
        // iOS: it was the only one in the app that said "Back".
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DismissX(onClick = onDismiss, showsScrim = false)
        }
        Text(
            text = "Settings",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.textPrimary,
            modifier = Modifier.padding(horizontal = Spacing.md).padding(bottom = Spacing.xs),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {

            if (tasks.isNotEmpty()) {
                item { ToFinishCard(tasks = tasks, onAct = onTaskAction) }
            }

            // Profile row → Accounts Center.
            item {
                Spacer(Modifier.height(Spacing.md))
                SettingsGroupCard {
                    AccountsCenterRow(
                        displayName = displayName,
                        badgeCount = tasks.count { it.destination == SettingsDestination.ACCOUNTS_CENTER },
                        onClick = onOpenAccountsCenter,
                    )
                }
            }

            // ---- YOU ----
            item {
                SettingsSectionHeader("You")
                SettingsGroupCard {
                    SettingsRow(
                        title = "Notifications",
                        value = if (notificationsEnabled) "On" else "Off",
                        icon = Icons.Default.Notifications,
                        iconTint = SettingsTokens.tileNotifications,
                        onClick = onOpenNotificationSettings,
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = "Privacy & safety",
                        value = if (isPrivateAccount) "Private" else "Public",
                        icon = Icons.Default.Shield,
                        iconTint = SettingsTokens.tilePrivacy,
                        onClick = onOpenPrivacy,
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = "My subscriptions",
                        value = if (subscriptionCount > 0) subscriptionCount.toString() else null,
                        icon = Icons.Default.Star,
                        iconTint = SettingsTokens.tileSubscriptions,
                        onClick = onOpenSubscriptions,
                    )
                }
            }

            // ---- CREATOR ----
            item {
                SettingsSectionHeader("Creator")
                SettingsGroupCard {
                    // Hype Coins sits here rather than under YOU. iOS's commit
                    // message argues for YOU — "the balance is an account
                    // fact" — and its code puts the row in Creator. Matching
                    // the code, because that is what a user of either app
                    // actually sees.
                    SettingsRow(
                        title = "Hype Coins",
                        icon = Icons.Default.LocalFireDepartment,
                        iconTint = Color(0xFFFF9500),
                        onClick = onOpenWallet,
                    )
                    SettingsDivider()
                    // Shown to viewers too. For a non-creator this opens an
                    // invitation rather than a locked door, which is also how
                    // a viewer finds out it exists.
                    SettingsRow(
                        title = "Creator Studio",
                        icon = Icons.Default.PlayCircle,
                        iconTint = SettingsTokens.tileCreatorStudio,
                        onClick = onOpenCreatorStudio,
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = "Earnings & payouts",
                        badgeCount = tasks.count { it.destination == SettingsDestination.EARNINGS },
                        icon = Icons.Default.AttachMoney,
                        iconTint = SettingsTokens.tileEarnings,
                        onClick = onOpenEarnings,
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = if (isAmbassador) "Ambassador Program" else "Invite Friends",
                        subtitle = if (isAmbassador) "Track referrals & earn rewards"
                                   else "Share Stitch Social with friends",
                        icon = Icons.Default.Campaign,
                        iconTint = SettingsTokens.tileAmbassador,
                        onClick = onOpenReferral,
                    )
                }
            }

            // ---- APP ----
            item {
                SettingsSectionHeader("App")
                SettingsGroupCard {
                    SettingsRow(
                        title = "Appearance & haptics",
                        value = com.stitchsocial.club.ui.theme.ThemeState.mode.name
                            .lowercase().replaceFirstChar { it.uppercase() },
                        icon = Icons.Default.Contrast,
                        iconTint = SettingsTokens.tileAppearance,
                        onClick = { showAppearance = true },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = "Playback & data",
                        value = "Coming soon",
                        icon = Icons.Default.PlayArrow,
                        iconTint = SettingsTokens.tilePlayback,
                        onClick = onOpenPlayback,
                    )
                }
            }

            // ---- HELP ----
            item {
                SettingsSectionHeader("Help")
                SettingsGroupCard {
                    SettingsRow(
                        title = "Help & report a problem",
                        icon = Icons.Default.HelpOutline,
                        iconTint = SettingsTokens.tileHelp,
                        onClick = onOpenHelp,
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = "Terms & policies",
                        icon = Icons.Default.Info,
                        iconTint = SettingsTokens.tileHelp,
                        onClick = onOpenTerms,
                    )
                }
            }

            // ---- LOGIN ----
            item {
                SettingsGroupGap()
                SettingsGroupCard {
                    SettingsRow(
                        title = "Add or switch account",
                        titleColor = SettingsTokens.link,
                        showChevron = false,
                        onClick = onOpenAccountSwitcher,
                    )
                    SettingsDivider(inset = Spacing.md)
                    SettingsRow(
                        title = "Sign out",
                        titleColor = SettingsTokens.destructive,
                        showChevron = false,
                        onClick = { showSignOut = true },
                    )
                }
            }

            // Version as a footer, not two rows of its own.
            item {
                Text(
                    text = version,
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 40.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    if (showSignOut) {
        SignOutSheet(
            displayName = displayName,
            onConfirm = { showSignOut = false; onSignOut() },
            onDismiss = { showSignOut = false },
        )
    }

    if (showAppearance) {
        AppearanceSheet(onDismiss = { showAppearance = false })
    }

    comingSoon?.let { what ->
        AlertDialog(
            onDismissRequest = { comingSoon = null },
            title = { Text("$what is coming") },
            text = {
                Text("This is where it will live. The row is here now so nothing moves when it arrives.")
            },
            confirmButton = { TextButton(onClick = { comingSoon = null }) { Text("OK") } },
        )
    }
}

/** The profile row at the top — 56dp gradient avatar, name, and what it holds. */
@Composable
private fun AccountsCenterRow(displayName: String, badgeCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Color(0xFFF0245F), Color(0xFFA855F7)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = displayName.take(1).uppercase(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(displayName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary, maxLines = 1)
            Text(
                "Accounts Center · login, security, personal info",
                fontSize = 13.sp,
                color = AppTheme.colors.textSecondary,
                maxLines = 2,
            )
        }
        if (badgeCount > 0) {
            Box(
                modifier = Modifier.size(20.dp).clip(CircleShape).background(SettingsTokens.destructive),
                contentAlignment = Alignment.Center,
            ) {
                Text("$badgeCount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Icon(Icons.Default.ChevronRight, null, tint = AppTheme.colors.textTertiary, modifier = Modifier.size(20.dp))
    }
}

/**
 * The one card allowed to appear and disappear. Pinned directly below the
 * title; the list beneath it never moves.
 */
@Composable
private fun ToFinishCard(tasks: List<SettingsTask>, onAct: (SettingsTask) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .padding(top = Spacing.sm)
            .clip(RoundedCornerShape(14.dp))
            .background(AppTheme.colors.surface)
            .border(1.5.dp, SettingsTokens.destructive.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                "${tasks.size} TO FINISH",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = SettingsTokens.destructive,
            )
            Text("Disappears when done", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
        }
        tasks.forEach { task ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary)
                    Text(task.reason, fontSize = 13.sp, color = AppTheme.colors.textSecondary)
                }
                Button(
                    onClick = { onAct(task) },
                    colors = ButtonDefaults.buttonColors(containerColor = SettingsTokens.brand),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(task.actionLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignOutSheet(displayName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppTheme.colors.bg) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text("Sign out of $displayName?", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
            Text(
                "Anything still uploading will be lost.",
                fontSize = 13.sp,
                color = AppTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(Spacing.xs))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SettingsTokens.destructive),
                shape = RoundedCornerShape(13.dp),
            ) { Text("Sign out", fontWeight = FontWeight.Bold, color = Color.White) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = AppTheme.colors.textSecondary)
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppTheme.colors.bg) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
            Text("Appearance", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
            Spacer(Modifier.height(Spacing.sm))
            com.stitchsocial.club.ui.theme.AppThemeMode.entries.forEach { mode ->
                val selected = com.stitchsocial.club.ui.theme.ThemeState.mode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { com.stitchsocial.club.ui.theme.ThemeState.setMode(context, mode) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        mode.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontSize = 17.sp,
                        color = AppTheme.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Icons.Default.Check, null, tint = SettingsTokens.brand, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}
