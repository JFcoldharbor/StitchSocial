/*
 * AccountSwitcherView.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Compose mirror of iOS AccountSwitcherView. Lists linked accounts (personal
 * + business), lets the user toggle between them without re-entering creds,
 * and surfaces an "Add the other type" CTA when only one is linked.
 *
 * Constraints:
 *   • Max 2 linked accounts (1 personal + 1 business).
 *   • Different emails required (Firebase Auth enforces unique emails).
 *   • Local-only — adding on this device doesn't sync to other devices.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.foundation.AccountType
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.LinkedAccount
import com.stitchsocial.club.services.LinkedAccountError
import com.stitchsocial.club.services.LinkedAccountManager
import com.stitchsocial.club.services.LinkedAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

@Composable
fun AccountSwitcherView(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { LinkedAccountManager.getInstance(context) }
    val accounts by manager.accounts.collectAsState()
    val activeUID by manager.activeUID.collectAsState()
    val scope = rememberCoroutineScope()

    var error by remember { mutableStateOf<String?>(null) }
    var isSwitching by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<LinkedAccount?>(null) }
    var addSheetTargetType by remember { mutableStateOf<AccountType?>(null) }

    val missingType: AccountType? = remember(accounts) {
        when {
            accounts.size >= 2 -> null
            accounts.any { it.accountType == AccountType.PERSONAL } -> AccountType.BUSINESS
            accounts.any { it.accountType == AccountType.BUSINESS } -> AccountType.PERSONAL
            else -> AccountType.BUSINESS
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 50.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.Cyan)
            }
            Text(
                "Accounts", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = Color.White, modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(48.dp))
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (accounts.isEmpty()) {
                EmptyHint()
            } else {
                accounts.forEach { account ->
                    AccountRow(
                        account = account,
                        isActive = account.uid == activeUID,
                        isSwitching = isSwitching,
                        onSwitchTap = {
                            scope.launch {
                                isSwitching = true
                                error = null
                                try {
                                    manager.switchTo(account.uid)
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    isSwitching = false
                                }
                            }
                        },
                        onRemoveTap = { pendingRemove = account }
                    )
                }
            }

            missingType?.let { type ->
                AddAccountCTA(type = type) { addSheetTargetType = type }
            }

            error?.let {
                Text(
                    it, color = Color.Red, fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // Remove confirmation
    pendingRemove?.let { acc ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove linked account?", color = Color.White) },
            text = {
                Text(
                    "${acc.displayName} will no longer appear here. The account itself isn't deleted.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    manager.removeAccount(acc.uid)
                    pendingRemove = null
                }) { Text("Remove", color = Color(0xFFFF453A)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel", color = Color.Gray) }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }

    // Add-account sheet
    addSheetTargetType?.let { type ->
        AddLinkedAccountSheet(
            targetType = type,
            onDismiss = { addSheetTargetType = null },
            onResult = { msg ->
                error = msg
                addSheetTargetType = null
            }
        )
    }
}

// ─────────────────────────────────────────────
// Rows
// ─────────────────────────────────────────────

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Your current account isn't linked yet.",
            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.7f)
        )
        Text(
            "Linking lets you toggle between this account and another type without signing in each time.",
            fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun AccountRow(
    account: LinkedAccount,
    isActive: Boolean,
    isSwitching: Boolean,
    onSwitchTap: () -> Unit,
    onRemoveTap: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (isActive) Color.Cyan.copy(alpha = 0.4f) else Color.Transparent,
                RoundedCornerShape(14.dp)
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(account)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(account.displayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AccountTypePill(type = account.accountType)
                Text(account.email, fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f), maxLines = 1)
            }
        }

        when {
            isActive -> Icon(Icons.Default.CheckCircle, "Active", tint = Color.Cyan, modifier = Modifier.size(20.dp))
            isSwitching -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Cyan)
            else -> Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Cyan.copy(alpha = 0.12f))
                    .clickable { onSwitchTap() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Switch", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Cyan)
            }
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, "More", tint = Color.White.copy(alpha = 0.5f))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Remove from this device", color = Color(0xFFFF453A)) },
                    onClick = {
                        menuOpen = false
                        onRemoveTap()
                    }
                )
            }
        }
    }
}

@Composable
private fun Avatar(account: LinkedAccount) {
    if (account.profileImageURL != null) {
        AsyncImage(
            model = account.profileImageURL,
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f))
        )
    } else {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                account.displayName.take(1).uppercase(),
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
        }
    }
}

@Composable
private fun AccountTypePill(type: AccountType) {
    val color = if (type == AccountType.BUSINESS) Color(0xFFFF9500) else Color.Cyan
    Box(
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            type.displayName.uppercase(),
            fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = color
        )
    }
}

@Composable
private fun AddAccountCTA(type: AccountType, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.Cyan.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.Cyan.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            if (type == AccountType.BUSINESS) Icons.Default.Business else Icons.Default.Person,
            null, tint = Color.Cyan
        )
        Text(
            "Add ${type.displayName} Account",
            modifier = Modifier.weight(1f),
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White
        )
        Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(alpha = 0.4f))
    }
}

// ─────────────────────────────────────────────
// Add-account sheet
// Single form. Mode toggle: "Sign In" (link existing) or "Sign Up" (new).
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLinkedAccountSheet(
    targetType: AccountType,
    onDismiss: () -> Unit,
    onResult: (String?) -> Unit
) {
    val context = LocalContext.current
    val authService = remember { AuthService() }
    val manager = remember { LinkedAccountManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(SheetMode.LINK) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var brandName by remember { mutableStateOf("") }
    var websiteURL by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val canSubmit = email.isNotBlank() && password.length >= 8 && when (mode) {
        SheetMode.LINK -> true
        SheetMode.CREATE -> if (targetType == AccountType.BUSINESS) brandName.isNotBlank() else displayName.isNotBlank()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E0E12)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Add ${targetType.displayName} Account",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
            Text(
                if (targetType == AccountType.BUSINESS)
                    "Business accounts post brand content and run ads. They can't hype, follow communities, or earn clout."
                else
                    "Personal accounts post videos, follow others, and earn clout.",
                fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f)
            )
            Text(
                "⚠️ Must use a different email than your current account.",
                fontSize = 12.sp, color = Color(0xFFFF9500).copy(alpha = 0.85f)
            )

            // Mode toggle
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Link existing", mode == SheetMode.LINK) { mode = SheetMode.LINK }
                ModeChip("Create new", mode == SheetMode.CREATE) { mode = SheetMode.CREATE }
            }

            FormField("Email", email, { email = it }, keyboard = KeyboardType.Email)
            FormField("Password (8+ chars)", password, { password = it }, isPassword = true)

            if (mode == SheetMode.CREATE) {
                if (targetType == AccountType.BUSINESS) {
                    FormField("Brand name", brandName, onChange = { brandName = it })
                    FormField("Website (optional)", websiteURL, onChange = { websiteURL = it }, keyboard = KeyboardType.Uri)
                } else {
                    FormField("Display name", displayName, onChange = { displayName = it })
                }
            }

            localError?.let {
                Text(it, fontSize = 12.sp, color = Color.Red, textAlign = TextAlign.Center)
            }

            Button(
                onClick = {
                    scope.launch {
                        isWorking = true
                        localError = null
                        try {
                            performAdd(
                                mode = mode,
                                targetType = targetType,
                                email = email,
                                password = password,
                                displayName = displayName.ifBlank { brandName },
                                brandName = brandName,
                                websiteURL = websiteURL,
                                authService = authService,
                                manager = manager
                            )
                            onResult(null)
                        } catch (e: Exception) {
                            localError = e.message
                        } finally {
                            isWorking = false
                        }
                    }
                },
                enabled = canSubmit && !isWorking,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan, contentColor = Color.Black)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when (mode) {
                        SheetMode.LINK -> "Link Account"
                        SheetMode.CREATE -> "Create Account"
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private enum class SheetMode { LINK, CREATE }

@Composable
private fun ModeChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Color.White else Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (active) Color.Black else Color.White.copy(alpha = 0.6f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    isPassword: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(label, color = Color.White.copy(alpha = 0.4f)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color.Cyan,
            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
            cursorColor = Color.Cyan
        )
    )
}

// ─────────────────────────────────────────────
// Add flow logic
// ─────────────────────────────────────────────

private suspend fun performAdd(
    mode: SheetMode,
    targetType: AccountType,
    email: String,
    password: String,
    displayName: String,
    brandName: String,
    websiteURL: String,
    authService: AuthService,
    manager: LinkedAccountManager
) {
    val previousActiveUID = FirebaseAuth.getInstance().currentUser?.uid
    val db = FirebaseFirestore.getInstance("stitchfin")

    try {
        when (mode) {
            SheetMode.CREATE -> {
                // signUp captures email/password; signs in as the new user
                authService.signUp(
                    email = email,
                    password = password,
                    displayName = if (targetType == AccountType.BUSINESS) brandName else displayName,
                    username = if (targetType == AccountType.BUSINESS) "" else displayName.replace(" ", "_").lowercase(),
                    accountType = targetType,
                    brandName = if (targetType == AccountType.BUSINESS) brandName else null,
                    websiteURL = websiteURL.takeIf { it.isNotBlank() }
                )
            }
            SheetMode.LINK -> {
                // Sign in to capture creds, then verify accountType matches
                FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await()
            }
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: throw LinkedAccountError.CredentialsMissing
        val data = db.collection("users").document(uid).get().await().data ?: emptyMap()
        val typeRaw = data["accountType"] as? String
        val actualType = if (typeRaw == "business") AccountType.BUSINESS else AccountType.PERSONAL

        if (mode == SheetMode.LINK && actualType != targetType) {
            // Wrong type — restore previous session and bail.
            previousActiveUID?.let {
                if (manager.accounts.value.any { acc -> acc.uid == it }) {
                    manager.switchTo(it)
                } else {
                    FirebaseAuth.getInstance().signOut()
                }
            } ?: FirebaseAuth.getInstance().signOut()
            throw LinkedAccountError.WrongAccountType
        }

        val resolvedDisplayName = (data["displayName"] as? String) ?: email
        val profileImageURL = data["profileImageURL"] as? String
        val account = LinkedAccount(
            uid = uid,
            email = email,
            accountType = actualType,
            displayName = resolvedDisplayName,
            profileImageURL = profileImageURL,
            provider = LinkedAuthProvider.EMAIL_PASSWORD,
            addedAt = Date()
        )
        manager.addEmailPasswordAccount(account, email, password)

        // Restore previous session so the user stays where they were.
        previousActiveUID?.takeIf { it != uid }?.let { manager.switchTo(it) }
    } catch (e: Exception) {
        // Best-effort restore previous session on failure.
        previousActiveUID?.let {
            if (FirebaseAuth.getInstance().currentUser?.uid != it &&
                manager.accounts.value.any { acc -> acc.uid == it }) {
                try { manager.switchTo(it) } catch (_: Exception) {}
            }
        }
        throw e
    }
}
