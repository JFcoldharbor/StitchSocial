/*
 * LoginView.kt - FULL iOS PARITY
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Matches LoginView.swift exactly:
 *   - Personal / Business account type picker
 *   - Business: brandName, websiteURL, AdCategory picker
 *   - Personal: username, displayName
 *   - Referral code (optional, auto-uppercase)
 *   - Terms + Safety + Privacy acceptance checkbox (required)
 *   - saveTermsAcceptance — single merge write post-auth
 *   - processReferralSignup / processOrganicSignup
 *   - No authService state observers (fixes race condition)
 *
 * CACHING: acceptedTermsVersion — add to CachingOptimization if force
 *   re-acceptance is needed; cache version in SharedPreferences to avoid
 *   Firestore read on cold launch.
 */

package com.stitchsocial.club

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.stitchsocial.club.foundation.AccountType
import com.stitchsocial.club.services.AdCategory
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.ReferralService
import com.stitchsocial.club.ui.theme.StitchColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.random.Random
import com.stitchsocial.club.BuildConfig

private const val TERMS_VERSION = "1.0"
private const val TERMS_URL = "https://stitchsocial.me/privacy"

// MARK: - AuthMode

enum class AuthMode(val title: String, val subtitle: String, val buttonText: String) {
    SIGN_IN("Welcome Back", "Sign in to continue your creative journey", "Sign In"),
    SIGN_UP("Join Stitch Social", "Create your conversation account", "Create Account")
}

// MARK: - FloatingParticle

@Composable
fun FloatingParticle(index: Int, screenWidth: Float, screenHeight: Float) {
    val t = rememberInfiniteTransition(label = "p$index")
    val ix = remember { Random.nextFloat() * screenWidth }
    val iy = remember { Random.nextFloat() * screenHeight }
    val dur = remember { Random.nextInt(3000, 6000) }
    val sz = remember { Random.nextFloat() * 6f + 2f }
    val ox by t.animateFloat(ix, ix + Random.nextFloat() * 100f - 50f,
        infiniteRepeatable(tween(dur, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ox$index")
    val oy by t.animateFloat(iy, iy + Random.nextFloat() * 100f - 50f,
        infiniteRepeatable(tween(dur, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "oy$index")
    val al by t.animateFloat(0.1f, 0.3f,
        infiniteRepeatable(tween(dur / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "al$index")
    Box(Modifier.offset(ox.dp, oy.dp).size(sz.dp).clip(CircleShape).background(StitchColors.primary.copy(alpha = al)))
}

// MARK: - LoginView

@Composable
fun LoginView(
    authService: AuthService,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = lifecycleOwner.lifecycleScope
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Form
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var pwVisible by remember { mutableStateOf(false) }
    var cpwVisible by remember { mutableStateOf(false) }

    // Account type + business
    var accountType by remember { mutableStateOf(AccountType.PERSONAL) }
    var brandName by remember { mutableStateOf("") }
    var websiteURL by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(AdCategory.OTHER) }
    var referralCode by remember { mutableStateOf("") }

    // Terms
    var acceptedTerms by remember { mutableStateOf(false) }

    // UI
    var isLoading by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var animated by remember { mutableStateOf(false) }
    var showForgot by remember { mutableStateOf(false) }
    var forgotEmail by remember { mutableStateOf("") }
    var forgotSent by remember { mutableStateOf(false) }
    var forgotError by remember { mutableStateOf<String?>(null) }

    // Focus
    val fEmail = remember { FocusRequester() }
    val fUser = remember { FocusRequester() }
    val fDN = remember { FocusRequester() }
    val fBrand = remember { FocusRequester() }
    val fPw = remember { FocusRequester() }
    val fCpw = remember { FocusRequester() }

    LaunchedEffect(Unit) { delay(100); animated = true }

    val validEmail = email.contains("@") && email.contains(".")
    val validPw = password.length >= 6
    val pwMatch = password == confirmPassword
    val formValid = acceptedTerms && when (mode) {
        AuthMode.SIGN_IN -> validEmail && validPw
        AuthMode.SIGN_UP -> when (accountType) {
            AccountType.BUSINESS -> validEmail && brandName.isNotBlank() && validPw && pwMatch
            else -> validEmail && username.length >= 3 && displayName.isNotBlank() && validPw && pwMatch
        }
    }

    fun doAuth() {
        keyboard?.hide(); focusManager.clearFocus()
        if (!formValid) {
            errorMsg = when {
                !acceptedTerms -> "Please accept the Terms & Conditions to continue"
                !validEmail -> "Please enter a valid email address"
                !validPw -> "Password must be at least 6 characters"
                mode == AuthMode.SIGN_UP && !pwMatch -> "Passwords do not match"
                mode == AuthMode.SIGN_UP && accountType == AccountType.PERSONAL && username.length < 3 ->
                    "Username must be at least 3 characters"
                mode == AuthMode.SIGN_UP && accountType == AccountType.PERSONAL && displayName.isBlank() ->
                    "Please enter your display name"
                mode == AuthMode.SIGN_UP && accountType == AccountType.BUSINESS && brandName.isBlank() ->
                    "Please enter your brand name"
                else -> "Please fill in all fields"
            }
            return
        }
        scope.launch {
            try {
                isLoading = true; errorMsg = null
                if (mode == AuthMode.SIGN_IN) {
                    val r = authService.signIn(email.trim(), password)
                    if (r.success) { showSuccess = true; delay(1500); showSuccess = false; onLoginSuccess() }
                } else {
                    val resolvedUsername = if (accountType == AccountType.BUSINESS) brandName else username.trim().lowercase()
                    val resolvedDN = if (accountType == AccountType.BUSINESS) brandName else displayName.trim()
                    val r = authService.signUp(
                        email = email.trim(), password = password,
                        displayName = resolvedDN, username = resolvedUsername,
                        accountType = accountType,
                        brandName = if (accountType == AccountType.BUSINESS) brandName else null,
                        websiteURL = if (accountType == AccountType.BUSINESS && websiteURL.isNotBlank()) websiteURL else null,
                        businessCategory = if (accountType == AccountType.BUSINESS) category else null
                    )
                    if (r.success) {
                        // Save terms acceptance — mirrors iOS saveTermsAcceptance()
                        try {
                            FirebaseFirestore.getInstance("stitchfin").collection("users").document(r.userId)
                                .set(mapOf(
                                    "acceptedTermsAt" to FieldValue.serverTimestamp(),
                                    "acceptedTermsVersion" to TERMS_VERSION,
                                    "acceptedSafetyPolicy" to true,
                                    "acceptedPrivacyPolicy" to true
                                ), SetOptions.merge()).await()
                        } catch (e: Exception) { println("⚠️ TERMS: ${e.message}") }

                        // Process referral — mirrors iOS processReferralSignup / processOrganicSignup
                        val code = referralCode.trim().uppercase()
                        try {
                            val rs = ReferralService()
                            if (code.isNotEmpty()) {
                                val ref = rs.processReferralSignup(code, r.userId, "android", "manual")
                                if (BuildConfig.DEBUG) { println(if (ref.success) "🎉 REFERRAL: Redeemed by ${ref.referrerID}" else "⚠️ REFERRAL: ${ref.error}") }
                            } else {
                                rs.processOrganicSignup(newUserID = r.userId, platform = "android")
                            }
                        } catch (e: Exception) { println("⚠️ REFERRAL: ${e.message}") }

                        showSuccess = true; delay(2000); showSuccess = false; onLoginSuccess()
                    }
                }
            } catch (e: Exception) {
                errorMsg = when {
                    e.message?.contains("email address is already in use") == true ->
                        "This email is already registered. Please sign in instead."
                    e.message?.contains("network") == true -> "Network error. Please check your connection."
                    e.message?.contains("Username") == true -> e.message
                    else -> "Authentication error: ${e.message}"
                }
            } finally { isLoading = false }
        }
    }

    fun switchMode(m: AuthMode) {
        mode = m; email = ""; password = ""; confirmPassword = ""
        username = ""; displayName = ""; brandName = ""; websiteURL = ""
        referralCode = ""; acceptedTerms = false; errorMsg = null
        accountType = AccountType.PERSONAL
    }

    Box(modifier = modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color.Black, Color(0xFF0A0A1A), Color.Black))
    )) {
        repeat(20) { i -> FloatingParticle(i, 400f, 800f) }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))

            // Logo
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.scale(if (animated) 1f else 0.8f).alpha(if (animated) 1f else 0f)) {
                Image(painterResource(R.drawable.stitchsociallogo), "Stitch Social",
                    Modifier.size(120.dp).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Fit)
                Spacer(Modifier.height(16.dp))
                Text("Stitch Social", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(Modifier.height(40.dp))

            // Welcome
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(if (animated) 1f else 0f)) {
                Text(mode.title, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(mode.subtitle, fontSize = 16.sp, color = StitchColors.textSecondary, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(32.dp))

            // Form card
            Card(Modifier.fillMaxWidth().alpha(if (animated) 1f else 0f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, StitchColors.glassBorder)
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    // Email
                    LTextField(email, { email = it.trim(); errorMsg = null }, "Email", "Enter your email",
                        KeyboardType.Email, ImeAction.Next,
                        { if (mode == AuthMode.SIGN_UP) fUser.requestFocus() else fPw.requestFocus() }, fEmail)

                    if (mode == AuthMode.SIGN_UP) {
                        // Account Type Picker
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Account Type", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(0.8f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AccTypeBtn(Icons.Default.Person, "Personal",
                                    accountType == AccountType.PERSONAL, Modifier.weight(1f)) {
                                    accountType = AccountType.PERSONAL
                                }
                                AccTypeBtn(Icons.Default.Business, "Business",
                                    accountType == AccountType.BUSINESS, Modifier.weight(1f)) {
                                    accountType = AccountType.BUSINESS
                                }
                            }
                        }

                        // Business fields
                        if (accountType == AccountType.BUSINESS) {
                            LTextField(brandName, { brandName = it; errorMsg = null },
                                "Brand Name", "Your company or brand name",
                                KeyboardType.Text, ImeAction.Next, { fPw.requestFocus() }, fBrand)

                            LTextField(websiteURL, { websiteURL = it },
                                "Website (optional)", "https://yourbrand.com",
                                KeyboardType.Uri, ImeAction.Next, { fPw.requestFocus() })

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Business Category", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(0.8f))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(AdCategory.values().toList()) { cat ->
                                        val sel = category == cat
                                        Surface(
                                            onClick = { category = cat },
                                            shape = RoundedCornerShape(20.dp),
                                            color = if (sel) Color.White else Color.White.copy(0.1f)
                                        ) {
                                            Text("${cat.icon} ${cat.displayName}", fontSize = 13.sp,
                                                color = if (sel) Color.Black else Color.White.copy(0.7f),
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Personal fields
                        if (accountType == AccountType.PERSONAL) {
                            LTextField(username,
                                { username = it.filter { c -> c.isLetterOrDigit() || c == '_' }; errorMsg = null },
                                "Username", "Choose a username",
                                KeyboardType.Text, ImeAction.Next, { fDN.requestFocus() }, fUser)
                            LTextField(displayName, { displayName = it; errorMsg = null },
                                "Display Name", "Your display name",
                                KeyboardType.Text, ImeAction.Next, { fPw.requestFocus() }, fDN)
                        }
                    }

                    // Password
                    LTextField(password, { password = it; errorMsg = null }, "Password", "Enter your password",
                        KeyboardType.Password,
                        if (mode == AuthMode.SIGN_UP) ImeAction.Next else ImeAction.Done,
                        { if (mode == AuthMode.SIGN_UP) fCpw.requestFocus() else doAuth() }, fPw,
                        isPassword = true, pwVisible, { pwVisible = !pwVisible })

                    // Forgot password
                    if (mode == AuthMode.SIGN_IN) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { forgotEmail = email; forgotSent = false; forgotError = null; showForgot = true }) {
                                Text("Forgot Password?", fontSize = 14.sp, color = StitchColors.primary)
                            }
                        }
                    }

                    // Password requirements
                    if (mode == AuthMode.SIGN_UP && password.isNotEmpty()) {
                        PasswordRequirementRow("At least 6 characters", password.length >= 6)
                    }

                    // Confirm password + referral
                    if (mode == AuthMode.SIGN_UP) {
                        LTextField(confirmPassword, { confirmPassword = it; errorMsg = null },
                            "Confirm Password", "Confirm your password",
                            KeyboardType.Password, ImeAction.Done, { doAuth() }, fCpw,
                            isPassword = true, cpwVisible, { cpwVisible = !cpwVisible })

                        if (confirmPassword.isNotEmpty() && !pwMatch) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StitchColors.error, modifier = Modifier.size(16.dp))
                                Text("Passwords do not match", fontSize = 12.sp, color = StitchColors.error)
                            }
                        }

                        // Referral code
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Referral Code (optional)", fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, color = Color.White.copy(0.8f))
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(Color.White.copy(0.08f), RoundedCornerShape(12.dp))
                                    .border(1.dp,
                                        if (referralCode.isEmpty()) Color.Transparent else Color(0xFF9C27B0).copy(0.3f),
                                        RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ConfirmationNumber,
                                    contentDescription = null,
                                    tint = Color(0xFF9C27B0).copy(0.6f),
                                    modifier = Modifier.size(16.dp))
                                Box(Modifier.weight(1f)) {
                                    if (referralCode.isEmpty()) Text("Enter referral code", fontSize = 15.sp, color = Color.Gray)
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = referralCode,
                                        onValueChange = { referralCode = it.uppercase().filter { c -> c.isLetterOrDigit() } },
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color.White),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { doAuth() }),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Text("Got a code from a creator? Enter it to connect.", fontSize = 11.sp, color = Color.Gray)
                        }
                    }

                    // Error
                    errorMsg?.let {
                        Card(Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = StitchColors.error.copy(0.1f))) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = StitchColors.error, modifier = Modifier.size(20.dp))
                                Text(it, color = StitchColors.error, fontSize = 14.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Submit button
                    Button(onClick = { doAuth() }, enabled = formValid && !isLoading,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StitchColors.primary,
                            disabledContainerColor = StitchColors.buttonDisabled),
                        shape = RoundedCornerShape(16.dp)) {
                        if (isLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        else Text(mode.buttonText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Terms — always shown, mirrors iOS isFormValid requiring acceptedTerms
            Column(Modifier.fillMaxWidth().alpha(if (animated) 1f else 0f),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().clickable { acceptedTerms = !acceptedTerms },
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        if (acceptedTerms) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (acceptedTerms) StitchColors.primary else StitchColors.textSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text("I agree to the Terms & Conditions, Safety Policy, and Privacy Policy",
                        fontSize = 13.sp, color = StitchColors.textSecondary, lineHeight = 18.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("📄 Terms", "🛡️ Safety", "🔒 Privacy").forEach { label ->
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = StitchColors.primary,
                            modifier = Modifier.clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL)))
                            })
                    }
                }
                if (!acceptedTerms) {
                    Text("Please accept the Terms & Conditions to continue",
                        fontSize = 11.sp, color = StitchColors.textSecondary.copy(0.7f),
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))

            // Mode switcher
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(if (animated) 1f else 0f)) {
                Text(if (mode == AuthMode.SIGN_IN) "Don't have an account?" else "Already have an account?",
                    fontSize = 14.sp, color = StitchColors.textSecondary)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { switchMode(if (mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN) }) {
                    Text(if (mode == AuthMode.SIGN_IN) "Sign Up" else "Sign In",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = StitchColors.primary)
                }
            }

            Spacer(Modifier.height(40.dp))
        }

        // Success overlay
        if (showSuccess) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.8f)), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box {
                        Image(painterResource(R.drawable.stitchsociallogo), null,
                            Modifier.size(100.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Fit)
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd).offset(8.dp, 8.dp)
                                .size(32.dp).clip(CircleShape).background(StitchColors.success),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    Text("Welcome to Stitch!", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Your account is ready to go", fontSize = 16.sp, color = StitchColors.textSecondary)
                }
            }
        }

        // Loading overlay
        if (isLoading && !showSuccess) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(0.8f)),
                    border = BorderStroke(1.dp, StitchColors.inputBorder)) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = StitchColors.primary,
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 3.dp
                        )
                        Text("Authenticating...", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
            }
        }

        // Forgot password dialog
        if (showForgot) {
            AlertDialog(
                onDismissRequest = { if (!isLoading) showForgot = false },
                containerColor = Color(0xFF1A1A1A),
                titleContentColor = Color.White,
                textContentColor = StitchColors.textSecondary,
                title = { Text(if (forgotSent) "Email Sent!" else "Reset Password", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (forgotSent) {
                            Text("We've sent a reset link to:", color = StitchColors.textSecondary)
                            Text(forgotEmail, color = StitchColors.primary, fontWeight = FontWeight.Medium)
                            Text("Check your inbox to reset your password.", color = StitchColors.textSecondary, fontSize = 14.sp)
                        } else {
                            Text("Enter your email and we'll send a reset link.", color = StitchColors.textSecondary)
                            OutlinedTextField(forgotEmail, { forgotEmail = it.trim(); forgotError = null },
                                placeholder = { Text("Email address", color = StitchColors.placeholder) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = StitchColors.primary, unfocusedBorderColor = StitchColors.glassBorder,
                                    focusedContainerColor = StitchColors.inputBackground, unfocusedContainerColor = StitchColors.inputBackground,
                                    cursorColor = StitchColors.primary),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done))
                            forgotError?.let { Text(it, color = StitchColors.error, fontSize = 14.sp) }
                        }
                    }
                },
                confirmButton = {
                    if (forgotSent) {
                        TextButton(onClick = { showForgot = false }) {
                            Text("Done", color = StitchColors.primary, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    isLoading = true
                                    authService.resetPassword(forgotEmail)
                                    forgotSent = true
                                } catch (e: Exception) {
                                    forgotError = if (e.message?.contains("no user") == true)
                                        "No account found with this email"
                                    else "Failed to send reset email. Please try again."
                                } finally { isLoading = false }
                            }
                        }, enabled = !isLoading && forgotEmail.isNotBlank()) {
                            if (isLoading) CircularProgressIndicator(color = StitchColors.primary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text("Send Reset Link",
                                color = if (forgotEmail.isNotBlank()) StitchColors.primary else StitchColors.textSecondary,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                dismissButton = {
                    if (!forgotSent) {
                        TextButton(onClick = { showForgot = false }, enabled = !isLoading) {
                            Text("Cancel", color = StitchColors.textSecondary)
                        }
                    }
                }
            )
        }
    }
}

// MARK: - Account Type Button

@Composable
private fun AccTypeBtn(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color.White else Color.White.copy(0.08f),
        border = BorderStroke(1.dp, if (selected) Color.White else Color.White.copy(0.2f)),
        modifier = modifier.height(70.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) Color.Black else Color.White.copy(0.7f), modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (selected) Color.Black else Color.White.copy(0.7f))
        }
    }
}

// MARK: - LoginTextField (public — used by other views)

@Composable
fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onPasswordVisibilityToggle: (() -> Unit)? = null
) = LTextField(value, onValueChange, label, placeholder, keyboardType, imeAction, onImeAction,
    focusRequester, isPassword, passwordVisible, onPasswordVisibilityToggle)

@Composable
private fun LTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onPasswordToggle: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = StitchColors.textSecondary)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = StitchColors.placeholder) },
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (isPassword) {
                { IconButton(onClick = { onPasswordToggle?.invoke() }) {
                    Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null, tint = StitchColors.textSecondary)
                }}
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = StitchColors.primary, unfocusedBorderColor = StitchColors.glassBorder,
                focusedContainerColor = StitchColors.inputBackground, unfocusedContainerColor = StitchColors.inputBackground,
                cursorColor = StitchColors.primary)
        )
    }
}

// MARK: - PasswordRequirementRow (public)

@Composable
fun PasswordRequirementRow(text: String, isMet: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (isMet) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isMet) StitchColors.success else StitchColors.textSecondary,
            modifier = Modifier.size(16.dp)
        )
        Text(text, fontSize = 12.sp, color = if (isMet) StitchColors.success else StitchColors.textSecondary)
    }
}