/*
 * SettingsView.kt - COMPLETE SETTINGS WITH SIGN OUT
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Settings Interface matching Swift SettingsView.swift
 * Dependencies: AuthService (Layer 4), UserService (Layer 4)
 * Features: Account management, sign-out, app info, preferences
 *
 * ✅ EXACT PORT: SettingsView.swift with all sections
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.ui.theme.StitchColors
import kotlinx.coroutines.launch

/**
 * SettingsView - Complete settings interface matching Swift version
 * MATCHES: SettingsView.swift with all sections and dialogs
 */
@Composable
fun SettingsView(
    currentUser: BasicUserInfo,
    authService: AuthService,
    onDismiss: () -> Unit,
    onSignOutSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // MARK: - State (MATCHES SWIFT)
    var showingSignOutConfirmation by remember { mutableStateOf(false) }
    var isSigningOut by remember { mutableStateOf(false) }
    var showingError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var signOutComplete by remember { mutableStateOf(false) }

    // Handle sign out completion with delay
    LaunchedEffect(signOutComplete) {
        if (signOutComplete) {
            kotlinx.coroutines.delay(100)
            onSignOutSuccess()
        }
    }

    // MARK: - Body (MATCHES SWIFT STRUCTURE)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Bar
            TopBar(onDismiss = onDismiss)

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Account Section
                AccountSection(
                    user = currentUser,
                    isSigningOut = isSigningOut,
                    onSignOut = { showingSignOutConfirmation = true }
                )

                Divider(
                    color = Color.Gray.copy(alpha = 0.3f),
                    thickness = 1.dp
                )

                // Social Section (NEW FROM SWIFT)
                SocialSection()

                Divider(
                    color = Color.Gray.copy(alpha = 0.3f),
                    thickness = 1.dp
                )

                // Preferences Section
                PreferencesSection()

                Divider(
                    color = Color.Gray.copy(alpha = 0.3f),
                    thickness = 1.dp
                )

                // Support Section
                SupportSection()

                Divider(
                    color = Color.Gray.copy(alpha = 0.3f),
                    thickness = 1.dp
                )

                // About Section
                AboutSection()

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // MARK: - Dialogs (MATCHES SWIFT ALERTS)

    // Sign Out Confirmation Dialog
    if (showingSignOutConfirmation) {
        AlertDialog(
            onDismissRequest = { showingSignOutConfirmation = false },
            title = {
                Text(
                    text = "Sign Out",
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to sign out?",
                    color = StitchColors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showingSignOutConfirmation = false
                        scope.launch {
                            isSigningOut = true
                            try {
                                authService.signOut()
                                onSignOutSuccess()
                            } catch (e: Exception) {
                                errorMessage = "Failed to sign out: ${e.message}"
                                showingError = true
                            } finally {
                                isSigningOut = false
                            }
                        }
                    }
                ) {
                    Text("Sign Out", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showingSignOutConfirmation = false }
                ) {
                    Text("Cancel", color = StitchColors.textSecondary)
                }
            },
            containerColor = StitchColors.surface,
            tonalElevation = 6.dp
        )
    }

    // Error Dialog
    if (showingError) {
        AlertDialog(
            onDismissRequest = { showingError = false },
            title = {
                Text(
                    text = "Error",
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = errorMessage,
                    color = StitchColors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showingError = false }
                ) {
                    Text("OK", color = StitchColors.primary)
                }
            },
            containerColor = StitchColors.surface,
            tonalElevation = 6.dp
        )
    }
}

// MARK: - Top Bar

@Composable
private fun TopBar(
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Settings",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        TextButton(onClick = onDismiss) {
            Text(
                text = "Done",
                color = StitchColors.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// MARK: - Account Section (MATCHES SWIFT)

@Composable
private fun AccountSection(
    user: BasicUserInfo,
    isSigningOut: Boolean,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
    ) {
        Text(
            text = "Account",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        // Account Info Rows
        AccountInfoRow(title = "Display Name", value = user.displayName)
        AccountInfoRow(title = "Username", value = "@${user.username}")
        AccountInfoRow(title = "Tier", value = user.tier.displayName)

        // Sign Out Button
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isSigningOut, onClick = onSignOut),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = "Sign Out",
                        tint = if (isSigningOut) Color.Gray else Color.Red,
                        modifier = Modifier.size(20.dp)
                    )

                    Text(
                        text = "Sign Out",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isSigningOut) Color.Gray else Color.Red
                    )
                }

                if (isSigningOut) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountInfoRow(
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            color = Color.Gray
        )
        Text(
            text = value,
            fontSize = 16.sp,
            color = Color.White
        )
    }
}

// MARK: - Social Section (NEW FROM SWIFT)

@Composable
private fun SocialSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
    ) {
        Text(
            text = "Social",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        SettingsRow(
            icon = Icons.Default.People,
            title = "Friend Suggestions",
            subtitle = "Discover people you might know",
            onClick = { /* Future: Navigate to friend suggestions */ }
        )

        SettingsRow(
            icon = Icons.Default.Favorite,
            title = "Engagement Settings",
            subtitle = "Customize hype and interaction preferences",
            onClick = { /* Future: Navigate to engagement settings */ }
        )
    }
}

// MARK: - Preferences Section (MATCHES SWIFT)

@Composable
private fun PreferencesSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
    ) {
        Text(
            text = "Preferences",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        SettingsRow(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            subtitle = "Push notifications and alerts",
            onClick = { /* Future: Navigate to notification settings */ }
        )

        SettingsRow(
            icon = Icons.Default.Lock,
            title = "Privacy",
            subtitle = "Account visibility and data",
            onClick = { /* Future: Navigate to privacy settings */ }
        )
    }
}

// MARK: - Support Section (MATCHES SWIFT)

@Composable
private fun SupportSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
    ) {
        Text(
            text = "Support",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        SettingsRow(
            icon = Icons.Default.Help,
            title = "Help & Support",
            subtitle = "Get help with your account",
            onClick = { /* Future: Open support */ }
        )

        SettingsRow(
            icon = Icons.Default.Description,
            title = "Privacy Policy",
            subtitle = "How we handle your data",
            onClick = { /* Future: Open privacy policy */ }
        )

        SettingsRow(
            icon = Icons.Default.Description,
            title = "Terms of Service",
            subtitle = "Terms and conditions",
            onClick = { /* Future: Open terms */ }
        )
    }
}

// MARK: - About Section (MATCHES SWIFT)

@Composable
private fun AboutSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
    ) {
        Text(
            text = "About",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        // Version
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Version",
                fontSize = 16.sp,
                color = Color.Gray
            )
            Text(
                text = "1.0.0", // TODO: Get from BuildConfig
                fontSize = 16.sp,
                color = Color.White
            )
        }

        // Build Number
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Build",
                fontSize = 16.sp,
                color = Color.Gray
            )
            Text(
                text = "1", // TODO: Get from BuildConfig
                fontSize = 16.sp,
                color = Color.White
            )
        }
    }
}

// MARK: - Helper Components

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = StitchColors.primary,
                modifier = Modifier.size(24.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Navigate",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}