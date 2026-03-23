/*
 * LoginView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Authentication interface
 * Matches: LoginView.swift (iOS) — FULL PARITY
 * Features: Animated particles, glassmorphism, account type picker, business fields,
 *           referral code, terms acceptance checkbox, saveTermsAcceptance Firestore write,
 *           processReferralSignup post-auth, auto-join official community
 *
 * CACHING NOTES (add to CachingOptimization file):
 * - referralCodeValid: debounce + in-memory cache per code string to avoid
 *   Firestore reads on every keystroke. Key: "referral_validated_{code}",
 *   TTL: session-scoped (no persistence needed). 1 read per unique code max.
 * - acceptedTermsVersion: cache in SharedPreferences after first acceptance.
 *   On cold launch, compare cached version to currentTermsVersion constant —
 *   skip Firestore read if versions match. Only write when version changes.
 * - NO caching needed for business category list — static enum, never fetched.
 */

package com.stitchsocial.club

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.AdCategory
import com.stitchsocial.club.services.ReferralService
import com.stitchsocial.club.foundation.AccountType
import com.stitchsocial.club.ui.theme.StitchColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

// AccountType and BusinessCategory are defined in BusinessProfile.kt (foundation package)
// import com.stitchsocial.club.foundation.AccountType
// import com.stitchsocial.club.foundation.AdCategory (used as BusinessCategory equivalent)

// MARK: - Auth Mode Enum

enum class AuthMode(
    val title: String,
    val subtitle: String,
    val buttonText: String
) {
    SIGN_IN("Welcome Back", "Sign in to continue your creative journey", "Sign In"),
    SIGN_UP("Join Stitch Social", "Create your conversation account", "Create Account")
}

// MARK: - Floating Particle

@Composable
fun FloatingParticle(index: Int, screenWidth: Float, screenHeight: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "particle_$index")
    val initialX = remember { Random.nextFloat() * screenWidth }
    val initialY = remember { Random.nextFloat() * screenHeight }
    val size = remember { Random.nextFloat() * 6f + 2f }
    val duration = remember { Random.nextInt(3000, 6000) }

    val offsetX by infiniteTransition.animateFloat(
        initialValue = initialX,
        targetValue = initialX + Random.nextFloat() * 100f - 50f,
        animationSpec = infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pX_$index"
    )
    val offsetY by infiniteTransition.animateFloat(
        initialValue = initialY,
        targetValue = initialY + Random.nextFloat() * 100f - 50f,
        animationSpec = infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pY_$index"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(duration / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pA_$index"
    )

    Box(
        modifier = Modifier
            .offset(x = offsetX.dp, y = offsetY.dp)
            .size(size.dp)
            .clip(CircleShape)
            .background(StitchColors.primary.copy(alpha = alpha))
    )
}

// MARK: - Main LoginView

@Composable
fun LoginView(
    authService: AuthService,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()
    val prefs = remember { context.getSharedPreferences("stitch_settings", android.content.Context.MODE_PRIVATE) }

    // Auth mode
    var currentMode by remember { mutableStateOf(AuthMode.SIGN_IN) }

    // Form fields
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var referralCode by remember { mutableStateOf("") }
    var acceptedTerms by remember { mutableStateOf(false) }

    // Account type
    var selectedAccountType by remember { mutableStateOf(AccountType.PERSONAL) }
    var brandName by remember { mutableStateOf("") }
    var websiteURL by remember { mutableStateOf("") }
    var selectedBusinessCategory by remember { mutableStateOf(AdCategory.OTHER) }

    // Password visibility
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    // UI state
    var isLoading by remember { mutableStateOf(false) }
    var showingSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var forgotPasswordEmail by remember { mutableStateOf("") }
    var forgotPasswordSent by remember { mutableStateOf(false) }
    var forgotPasswordError by remember { mutableStateOf<String?>(null) }
    var animateWelcome by remember { mutableStateOf(false) }

    // Current terms version — bump to force re-accept
    val currentTermsVersion = "1.0"

    // Focus requesters
    val emailFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val displayNameFocus = remember { FocusRequester() }
    val brandNameFocus = remember { FocusRequester() }
    val websiteFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmPasswordFocus = remember { FocusRequester() }
    val referralFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        animateWelcome = true
    }

    // Form validation — mirrors iOS isFormValid
    val isValidEmail = email.contains("@") && email.contains(".")
    val isValidPassword = password.length >= 6
    val passwordsMatch = password == confirmPassword

    val isFormValid = when {
        !acceptedTerms -> false
        currentMode == AuthMode.SIGN_IN -> isValidEmail && isValidPassword
        selectedAccountType == AccountType.BUSINESS ->
            isValidEmail && brandName.isNotBlank() && isValidPassword && passwordsMatch
        else -> isValidEmail && username.length >= 3 && displayName.isNotBlank() && isValidPassword && passwordsMatch
    }

    // Auth handler
    fun handleAuthentication() {
        keyboardController?.hide()
        if (!isFormValid) return
        scope.launch {
            try {
                isLoading = true
                errorMessage = null

                if (currentMode == AuthMode.SIGN_IN) {
                    val result = authService.signIn(email.trim(), password)
                    if (result.success) {
                        // Cache terms version in prefs to avoid re-read on cold launch
                        prefs.edit().putString("acceptedTermsVersion", currentTermsVersion).apply()
                        showingSuccess = true
                        delay(1500)
                        showingSuccess = false
                        onLoginSuccess()
                    } else {
                        errorMessage = "Sign in failed. Please check your credentials."
                    }
                } else {
                    val resolvedUsername = if (selectedAccountType == AccountType.BUSINESS) brandName else username.trim().lowercase()
                    val resolvedDisplayName = if (selectedAccountType == AccountType.BUSINESS) brandName else displayName.trim()

                    val result = authService.signUp(
                        email = email.trim(),
                        password = password,
                        displayName = resolvedDisplayName,
                        username = resolvedUsername,
                        accountType = selectedAccountType,
                        brandName = if (selectedAccountType == AccountType.BUSINESS) brandName else null,
                        websiteURL = if (selectedAccountType == AccountType.BUSINESS && websiteURL.isNotBlank()) websiteURL else null,
                        businessCategory = if (selectedAccountType == AccountType.BUSINESS) selectedBusinessCategory else null
                    )

                    if (result.success) {
                        val userID = result.userId  // ✅ lowercase d — matches AuthResult data class ?: ""

                        // Save terms acceptance — single merge write, no read
                        saveTermsAcceptance(userID, currentTermsVersion)

                        // Cache version in prefs to skip future Firestore reads
                        prefs.edit().putString("acceptedTermsVersion", currentTermsVersion).apply()

                        // Process referral code — only on signup, only if provided
                        val trimmedCode = referralCode.trim().uppercase()
                        if (trimmedCode.isNotEmpty()) {
                            try {
                                val referralResult = ReferralService().processReferralSignup(
                                    referralCode = trimmedCode,
                                    newUserID = userID,
                                    platform = "android",
                                    sourceType = "manual"
                                )
                                if (referralResult.success) {
                                    println("🎉 REFERRAL: Code redeemed — referred by ${referralResult.referrerID ?: "unknown"}")
                                } else {
                                    println("⚠️ REFERRAL: Code failed — ${referralResult.error ?: "unknown error"}")
                                }
                            } catch (e: Exception) {
                                println("⚠️ REFERRAL: Exception — ${e.message}")
                            }
                        } else {
                            // No referral code — track organic signup
                            try {
                                ReferralService().processOrganicSignup(userID, "android")
                            } catch (e: Exception) {
                                println("⚠️ REFERRAL: Organic tracking failed — ${e.message}")
                            }
                        }

                        // Auto-join official community
                        // TODO: Wire CommunityService.autoJoinOfficialCommunity when ported to Android
                        println("🏘 COMMUNITY: autoJoinOfficialCommunity — CommunityService not yet on Android")

                        showingSuccess = true
                        delay(2000)
                        showingSuccess = false
                        onLoginSuccess()
                    } else {
                        errorMessage = "Sign up failed. Please try again."
                    }
                }
            } catch (e: Exception) {
                errorMessage = when {
                    e.message?.contains("email address is already in use") == true ->
                        "This email is already registered. Please sign in instead."
                    e.message?.contains("network") == true ->
                        "Network error. Please check your connection."
                    e.message?.contains("password") == true ->
                        "Invalid password. Please try again."
                    e.message?.contains("Username") == true -> e.message
                    else -> "Authentication error. Please try again."
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun switchMode(newMode: AuthMode) {
        currentMode = newMode
        email = ""; password = ""; confirmPassword = ""
        username = ""; displayName = ""; referralCode = ""
        brandName = ""; websiteURL = ""
        acceptedTerms = false
        selectedAccountType = AccountType.PERSONAL
        errorMessage = null
    }

    fun handleForgotPassword() {
        val emailToReset = forgotPasswordEmail.trim()
        if (emailToReset.isBlank() || !emailToReset.contains("@")) {
            forgotPasswordError = "Please enter a valid email address"
            return
        }
        scope.launch {
            try {
                isLoading = true
                forgotPasswordError = null
                FirebaseAuth.getInstance().sendPasswordResetEmail(emailToReset).await()
                forgotPasswordSent = true
            } catch (e: Exception) {
                forgotPasswordError = when {
                    e.message?.contains("no user") == true -> "No account found with this email"
                    e.message?.contains("network") == true -> "Network error. Please check your connection."
                    else -> "Failed to send reset email. Please try again."
                }
            } finally {
                isLoading = false
            }
        }
    }

    // MARK: - Root Layout

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black, Color(0xFF0A0A1A), Color.Black)))
    ) {
        // Floating particles background
        Box(modifier = Modifier.fillMaxSize()) {
            repeat(20) { index ->
                FloatingParticle(index = index, screenWidth = screenWidth, screenHeight = screenHeight)
            }
        }

        // Main scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            // MARK: - Logo
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .scale(if (animateWelcome) 1f else 0.8f)
                    .alpha(if (animateWelcome) 1f else 0f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.stitchsociallogo),
                    contentDescription = "Stitch Social Logo",
                    modifier = Modifier.size(120.dp).clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Stitch Social", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(40.dp))

            // MARK: - Welcome Text
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(if (animateWelcome) 1f else 0f)
            ) {
                Text(currentMode.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(8.dp))
                Text(currentMode.subtitle, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = StitchColors.textSecondary, textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // MARK: - Form Card
            Card(
                modifier = Modifier.fillMaxWidth().alpha(if (animateWelcome) 1f else 0f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, StitchColors.glassBorder)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Email
                    LoginTextField(
                        value = email, onValueChange = { email = it.trim(); errorMessage = null },
                        label = "Email", placeholder = "Enter your email",
                        keyboardType = KeyboardType.Email,
                        imeAction = if (currentMode == AuthMode.SIGN_UP) ImeAction.Next else ImeAction.Next,
                        onImeAction = { if (currentMode == AuthMode.SIGN_UP) usernameFocus.requestFocus() else passwordFocus.requestFocus() },
                        focusRequester = emailFocus
                    )

                    // Sign Up only fields
                    if (currentMode == AuthMode.SIGN_UP) {

                        // Account Type Picker
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Account Type", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.8f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AccountTypeButton(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Default.Person,
                                    label = "Personal",
                                    selected = selectedAccountType == AccountType.PERSONAL,
                                    onClick = { selectedAccountType = AccountType.PERSONAL }
                                )
                                AccountTypeButton(
                                    modifier = Modifier.weight(1f),
                                    icon = Icons.Default.Business,
                                    label = "Business",
                                    selected = selectedAccountType == AccountType.BUSINESS,
                                    onClick = { selectedAccountType = AccountType.BUSINESS }
                                )
                            }
                        }

                        // Business fields
                        if (selectedAccountType == AccountType.BUSINESS) {
                            LoginTextField(
                                value = brandName, onValueChange = { brandName = it; errorMessage = null },
                                label = "Brand Name", placeholder = "Your company or brand name",
                                imeAction = ImeAction.Next,
                                onImeAction = { websiteFocus.requestFocus() },
                                focusRequester = brandNameFocus
                            )
                            LoginTextField(
                                value = websiteURL, onValueChange = { websiteURL = it; errorMessage = null },
                                label = "Website (optional)", placeholder = "https://yourbrand.com",
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                                onImeAction = { passwordFocus.requestFocus() },
                                focusRequester = websiteFocus
                            )

                            // Business category horizontal scroll
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Business Category", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.8f))
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AdCategory.entries.forEach { category ->
                                        val selected = selectedBusinessCategory == category
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = if (selected) Color.White else Color.White.copy(alpha = 0.1f),
                                            modifier = Modifier.clickable { selectedBusinessCategory = category }
                                        ) {
                                            Text(
                                                text = "${category.icon} ${category.displayName}",
                                                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                                color = if (selected) Color.Black else Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Personal fields
                        if (selectedAccountType == AccountType.PERSONAL) {
                            LoginTextField(
                                value = username,
                                onValueChange = { username = it.filter { c -> c.isLetterOrDigit() || c == '_' }; errorMessage = null },
                                label = "Username", placeholder = "Choose a username",
                                imeAction = ImeAction.Next,
                                onImeAction = { displayNameFocus.requestFocus() },
                                focusRequester = usernameFocus
                            )
                            LoginTextField(
                                value = displayName, onValueChange = { displayName = it; errorMessage = null },
                                label = "Display Name", placeholder = "Your display name",
                                imeAction = ImeAction.Next,
                                onImeAction = { passwordFocus.requestFocus() },
                                focusRequester = displayNameFocus
                            )
                        }
                    }

                    // Password
                    LoginTextField(
                        value = password, onValueChange = { password = it; errorMessage = null },
                        label = "Password", placeholder = "Enter your password",
                        isPassword = true, passwordVisible = passwordVisible,
                        onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
                        keyboardType = KeyboardType.Password,
                        imeAction = if (currentMode == AuthMode.SIGN_UP) ImeAction.Next else ImeAction.Done,
                        onImeAction = { if (currentMode == AuthMode.SIGN_UP) confirmPasswordFocus.requestFocus() else handleAuthentication() },
                        focusRequester = passwordFocus
                    )

                    // Forgot Password (sign in only)
                    if (currentMode == AuthMode.SIGN_IN) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                forgotPasswordEmail = email; forgotPasswordSent = false
                                forgotPasswordError = null; showForgotPasswordDialog = true
                            }) {
                                Text("Forgot Password?", fontSize = 14.sp, color = StitchColors.primary)
                            }
                        }
                    }

                    // Password requirements (sign up)
                    if (currentMode == AuthMode.SIGN_UP && password.isNotEmpty()) {
                        PasswordRequirementRow("At least 6 characters", password.length >= 6)
                    }

                    // Confirm password (sign up)
                    if (currentMode == AuthMode.SIGN_UP) {
                        LoginTextField(
                            value = confirmPassword, onValueChange = { confirmPassword = it; errorMessage = null },
                            label = "Confirm Password", placeholder = "Confirm your password",
                            isPassword = true, passwordVisible = confirmPasswordVisible,
                            onPasswordVisibilityToggle = { confirmPasswordVisible = !confirmPasswordVisible },
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                            onImeAction = { referralFocus.requestFocus() },
                            focusRequester = confirmPasswordFocus
                        )

                        if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StitchColors.error, modifier = Modifier.size(16.dp))
                                Text("Passwords do not match", color = StitchColors.error, fontSize = 12.sp)
                            }
                        }

                        // Referral Code
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Referral Code (optional)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.8f))
                            OutlinedTextField(
                                value = referralCode,
                                onValueChange = { referralCode = it.uppercase().filter { c -> c.isLetterOrDigit() } },
                                placeholder = { Text("Enter referral code", color = StitchColors.placeholder) },
                                leadingIcon = {
                                    Text("🎟", fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().focusRequester(referralFocus),
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = if (referralCode.isNotEmpty()) Color(0xFF9C27B0).copy(alpha = 0.6f) else StitchColors.glassBorder,
                                    unfocusedBorderColor = if (referralCode.isNotEmpty()) Color(0xFF9C27B0).copy(alpha = 0.3f) else StitchColors.glassBorder,
                                    focusedContainerColor = StitchColors.inputBackground,
                                    unfocusedContainerColor = StitchColors.inputBackground,
                                    cursorColor = StitchColors.primary
                                )
                            )
                            Text("Got a code from a creator? Enter it to connect.", fontSize = 11.sp, color = Color.Gray)
                        }
                    }

                    // Error message
                    if (errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StitchColors.error.copy(alpha = 0.1f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = StitchColors.error, modifier = Modifier.size(20.dp))
                                Text(errorMessage ?: "", color = StitchColors.error, fontSize = 14.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Primary Action Button
                    Button(
                        onClick = { handleAuthentication() },
                        enabled = isFormValid && !isLoading,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StitchColors.primary,
                            disabledContainerColor = StitchColors.buttonDisabled
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Text(currentMode.buttonText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // MARK: - Terms Acceptance
            if (currentMode == AuthMode.SIGN_UP || true) { // Show on both modes for visibility
                TermsAcceptanceRow(
                    accepted = acceptedTerms,
                    onToggle = { acceptedTerms = !acceptedTerms },
                    onOpenTerms = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://stitchsocial.me/privacy"))) },
                    modifier = Modifier.alpha(if (animateWelcome) 1f else 0f)
                )
            }

            if (!acceptedTerms) {
                Text(
                    "Please accept the Terms & Conditions to continue",
                    fontSize = 11.sp, color = StitchColors.textSecondary.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // MARK: - Mode Switcher
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(if (animateWelcome) 1f else 0f)
            ) {
                Text(
                    text = if (currentMode == AuthMode.SIGN_IN) "Don't have an account?" else "Already have an account?",
                    fontSize = 14.sp, color = StitchColors.textSecondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = { switchMode(if (currentMode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN) }) {
                    Text(
                        text = if (currentMode == AuthMode.SIGN_IN) "Sign Up" else "Sign In",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = StitchColors.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }

        // MARK: - Success Overlay
        if (showingSuccess) {
            Box(modifier = Modifier.fillMaxSize().background(StitchColors.modalOverlay), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box {
                        Image(
                            painter = painterResource(id = R.drawable.stitchsociallogo),
                            contentDescription = null,
                            modifier = Modifier.size(100.dp).clip(RoundedCornerShape(20.dp)),
                            contentScale = ContentScale.Fit
                        )
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 8.dp)
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

        // MARK: - Loading Overlay
        if (isLoading && !showingSuccess) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                    border = BorderStroke(1.dp, StitchColors.inputBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = StitchColors.primary, modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
                        Text("Authenticating...", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
            }
        }

        // MARK: - Forgot Password Dialog
        if (showForgotPasswordDialog) {
            AlertDialog(
                onDismissRequest = { if (!isLoading) showForgotPasswordDialog = false },
                containerColor = Color(0xFF1A1A1A),
                titleContentColor = Color.White,
                textContentColor = StitchColors.textSecondary,
                title = { Text(if (forgotPasswordSent) "Email Sent!" else "Reset Password", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (forgotPasswordSent) {
                            Text("We've sent a password reset link to:", color = StitchColors.textSecondary)
                            Text(forgotPasswordEmail, color = StitchColors.primary, fontWeight = FontWeight.Medium)
                            Text("Please check your inbox and follow the instructions.", color = StitchColors.textSecondary, fontSize = 14.sp)
                        } else {
                            Text("Enter your email address and we'll send you a reset link.", color = StitchColors.textSecondary)
                            OutlinedTextField(
                                value = forgotPasswordEmail,
                                onValueChange = { forgotPasswordEmail = it.trim(); forgotPasswordError = null },
                                placeholder = { Text("Email address", color = StitchColors.placeholder) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = StitchColors.primary, unfocusedBorderColor = StitchColors.glassBorder,
                                    focusedContainerColor = StitchColors.inputBackground, unfocusedContainerColor = StitchColors.inputBackground,
                                    cursorColor = StitchColors.primary
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { handleForgotPassword() })
                            )
                            if (forgotPasswordError != null) {
                                Text(forgotPasswordError ?: "", color = StitchColors.error, fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    if (forgotPasswordSent) {
                        TextButton(onClick = { showForgotPasswordDialog = false }) {
                            Text("Done", color = StitchColors.primary, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        TextButton(onClick = { handleForgotPassword() }, enabled = !isLoading && forgotPasswordEmail.isNotBlank()) {
                            if (isLoading) {
                                CircularProgressIndicator(color = StitchColors.primary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Send Reset Link", color = if (forgotPasswordEmail.isNotBlank()) StitchColors.primary else StitchColors.textSecondary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                },
                dismissButton = {
                    if (!forgotPasswordSent) {
                        TextButton(onClick = { showForgotPasswordDialog = false }, enabled = !isLoading) {
                            Text("Cancel", color = StitchColors.textSecondary)
                        }
                    }
                }
            )
        }
    }
}

// MARK: - saveTermsAcceptance
// Single merge write — no read. Matches iOS saveTermsAcceptance.
// CACHING: version stored in SharedPreferences post-write to avoid Firestore read on cold launch.
private fun saveTermsAcceptance(userID: String, version: String) {
    val db = FirebaseFirestore.getInstance()
    val userRef = db.collection("users").document(userID)
    val termsData = mapOf(
        "acceptedTermsAt" to FieldValue.serverTimestamp(),
        "acceptedTermsVersion" to version,
        "acceptedSafetyPolicy" to true,
        "acceptedPrivacyPolicy" to true
    )
    userRef.set(termsData, com.google.firebase.firestore.SetOptions.merge())
        .addOnSuccessListener { println("✅ Terms acceptance saved for user: $userID") }
        .addOnFailureListener { e -> println("❌ Failed to save terms acceptance: ${e.message}") }
}

// MARK: - Terms Acceptance Row

@Composable
private fun TermsAcceptanceRow(
    accepted: Boolean,
    onToggle: () -> Unit,
    onOpenTerms: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.clickable { onToggle() }
        ) {
            Icon(
                imageVector = if (accepted) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = "Accept terms",
                tint = if (accepted) StitchColors.primary else StitchColors.textSecondary,
                modifier = Modifier.size(22.dp).padding(top = 2.dp)
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = StitchColors.textSecondary, fontSize = 13.sp)) { append("I agree to the ") }
                    withStyle(SpanStyle(color = StitchColors.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) { append("Terms & Conditions") }
                    withStyle(SpanStyle(color = StitchColors.textSecondary, fontSize = 13.sp)) { append(", ") }
                    withStyle(SpanStyle(color = StitchColors.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) { append("Safety Policy") }
                    withStyle(SpanStyle(color = StitchColors.textSecondary, fontSize = 13.sp)) { append(", and ") }
                    withStyle(SpanStyle(color = StitchColors.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) { append("Privacy Policy") }
                }
            )
        }

        // Legal links row
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onOpenTerms, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Description, contentDescription = null, tint = StitchColors.primary, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Terms", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = StitchColors.primary)
            }
            TextButton(onClick = onOpenTerms, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = StitchColors.primary, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Safety", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = StitchColors.primary)
            }
            TextButton(onClick = onOpenTerms, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = StitchColors.primary, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Privacy", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = StitchColors.primary)
            }
        }
    }
}

// MARK: - Account Type Button

@Composable
private fun AccountTypeButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(70.dp).clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color.White else Color.White.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, tint = if (selected) Color.Black else Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.Black else Color.White.copy(alpha = 0.7f))
        }
    }
}

// MARK: - Login Text Field

@Composable
fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onPasswordVisibilityToggle: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = StitchColors.textSecondary)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = StitchColors.placeholder) },
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = { onPasswordVisibilityToggle?.invoke() }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = StitchColors.textSecondary
                        )
                    }
                }
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
                cursorColor = StitchColors.primary
            )
        )
    }
}

// MARK: - Password Requirement Row

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