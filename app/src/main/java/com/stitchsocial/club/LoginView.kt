/*
 * LoginView.kt - REDESIGNED TO MATCH iOS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Authentication interface
 * Features: Animated particles, glassmorphism, logo, entrance animations
 * Matches: LoginView.swift (iOS)
 *
 * USES: StitchColors from com.stitchsocial.club.ui.theme (no redefinition)
 */

package com.stitchsocial.club

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
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.ui.theme.StitchColors
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random

// MARK: - Auth Mode Enum

enum class AuthMode(
    val title: String,
    val subtitle: String,
    val buttonText: String
) {
    SIGN_IN(
        title = "Welcome Back",
        subtitle = "Sign in to continue your creative journey",
        buttonText = "Sign In"
    ),
    SIGN_UP(
        title = "Join Stitch Social",
        subtitle = "Create your conversation account",
        buttonText = "Create Account"
    )
}

// MARK: - Floating Particle

@Composable
fun FloatingParticle(
    index: Int,
    screenWidth: Float,
    screenHeight: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "particle_$index")

    val initialX = remember { Random.nextFloat() * screenWidth }
    val initialY = remember { Random.nextFloat() * screenHeight }
    val size = remember { Random.nextFloat() * 6f + 2f }
    val duration = remember { Random.nextInt(3000, 6000) }

    val offsetX by infiniteTransition.animateFloat(
        initialValue = initialX,
        targetValue = initialX + Random.nextFloat() * 100f - 50f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "particleX_$index"
    )

    val offsetY by infiniteTransition.animateFloat(
        initialValue = initialY,
        targetValue = initialY + Random.nextFloat() * 100f - 50f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "particleY_$index"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration / 2, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "particleAlpha_$index"
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
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()

    // Form State
    var currentMode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    // UI State
    var isLoading by remember { mutableStateOf(false) }
    var showingSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var forgotPasswordEmail by remember { mutableStateOf("") }
    var forgotPasswordSent by remember { mutableStateOf(false) }
    var forgotPasswordError by remember { mutableStateOf<String?>(null) }

    // Animation State
    var animateWelcome by remember { mutableStateOf(false) }

    // Focus Requesters
    val emailFocusRequester = remember { FocusRequester() }
    val usernameFocusRequester = remember { FocusRequester() }
    val displayNameFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val confirmPasswordFocusRequester = remember { FocusRequester() }

    // Watch AuthService states
    val authServiceLoading by authService.isLoading.collectAsState()
    val authServiceError by authService.lastError.collectAsState()

    LaunchedEffect(authServiceLoading) {
        isLoading = authServiceLoading
    }

    LaunchedEffect(authServiceError) {
        errorMessage = authServiceError?.message
    }

    // Start entrance animation
    LaunchedEffect(Unit) {
        delay(100)
        animateWelcome = true
    }

    // Form Validation
    val isValidEmail = email.contains("@") && email.contains(".")
    val isValidPassword = password.length >= 8
    val passwordsMatch = password == confirmPassword

    val isFormValid = when (currentMode) {
        AuthMode.SIGN_IN -> isValidEmail && isValidPassword
        AuthMode.SIGN_UP -> isValidEmail && isValidPassword && passwordsMatch &&
                username.length >= 3 && displayName.isNotBlank()
    }

    // Authentication Handler
    fun handleAuthentication() {
        keyboardController?.hide()

        if (!isFormValid) {
            errorMessage = when {
                !isValidEmail -> "Please enter a valid email address"
                !isValidPassword -> "Password must be at least 8 characters"
                currentMode == AuthMode.SIGN_UP && !passwordsMatch -> "Passwords do not match"
                currentMode == AuthMode.SIGN_UP && username.length < 3 -> "Username must be at least 3 characters"
                currentMode == AuthMode.SIGN_UP && displayName.isBlank() -> "Please enter your display name"
                else -> "Please fill in all fields"
            }
            return
        }

        GlobalScope.launch(Dispatchers.Main) {
            try {
                isLoading = true
                errorMessage = null

                if (currentMode == AuthMode.SIGN_IN) {
                    val result = authService.signIn(email.trim(), password)
                    if (result.success) {
                        showingSuccess = true
                        delay(1500)
                        showingSuccess = false
                        onLoginSuccess()
                    } else {
                        errorMessage = "Sign in failed. Please check your credentials."
                    }
                } else {
                    val result = authService.signUp(
                        email.trim(),
                        password,
                        displayName.trim(),
                        username.trim().lowercase()
                    )
                    if (result.success) {
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
                    e.message?.contains("Username") == true ->
                        e.message
                    else -> "Authentication error. Please try again."
                }
            } finally {
                isLoading = false
            }
        }
    }

    // Clear form when switching modes
    fun switchMode(newMode: AuthMode) {
        currentMode = newMode
        email = ""
        password = ""
        confirmPassword = ""
        username = ""
        displayName = ""
        errorMessage = null
    }

    // Forgot Password Handler
    fun handleForgotPassword() {
        val emailToReset = forgotPasswordEmail.trim()

        if (emailToReset.isBlank() || !emailToReset.contains("@") || !emailToReset.contains(".")) {
            forgotPasswordError = "Please enter a valid email address"
            return
        }

        GlobalScope.launch(Dispatchers.Main) {
            try {
                isLoading = true
                forgotPasswordError = null

                // Use Firebase Auth directly for password reset
                FirebaseAuth.getInstance()
                    .sendPasswordResetEmail(emailToReset)
                    .addOnCompleteListener { task ->
                        isLoading = false
                        if (task.isSuccessful) {
                            forgotPasswordSent = true
                        } else {
                            forgotPasswordError = when {
                                task.exception?.message?.contains("no user") == true ->
                                    "No account found with this email"
                                task.exception?.message?.contains("network") == true ->
                                    "Network error. Please check your connection."
                                else -> "Failed to send reset email. Please try again."
                            }
                        }
                    }
            } catch (e: Exception) {
                isLoading = false
                forgotPasswordError = "Failed to send reset email. Please try again."
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black,
                        Color(0xFF0A0A1A),
                        Color.Black
                    )
                )
            )
    ) {
        // Floating Particles Background
        Box(modifier = Modifier.fillMaxSize()) {
            repeat(20) { index ->
                FloatingParticle(
                    index = index,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )
            }
        }

        // Main Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            // MARK: - Logo Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .scale(if (animateWelcome) 1f else 0.8f)
                    .alpha(if (animateWelcome) 1f else 0f)
            ) {
                // App Logo
                Image(
                    painter = painterResource(id = R.drawable.stitchsociallogo),
                    contentDescription = "Stitch Social Logo",
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.height(16.dp))

                // App Name
                Text(
                    text = "Stitch Social",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // MARK: - Welcome Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .alpha(if (animateWelcome) 1f else 0f)
            ) {
                Text(
                    text = currentMode.title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = currentMode.subtitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = StitchColors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // MARK: - Glassmorphism Form Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (animateWelcome) 1f else 0f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.6f)
                ),
                border = BorderStroke(1.dp, StitchColors.glassBorder)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Email Field
                    LoginTextField(
                        value = email,
                        onValueChange = {
                            email = it.trim()
                            errorMessage = null
                        },
                        label = "Email",
                        placeholder = "Enter your email",
                        keyboardType = KeyboardType.Email,
                        imeAction = if (currentMode == AuthMode.SIGN_UP) ImeAction.Next else ImeAction.Next,
                        onImeAction = {
                            if (currentMode == AuthMode.SIGN_UP) {
                                usernameFocusRequester.requestFocus()
                            } else {
                                passwordFocusRequester.requestFocus()
                            }
                        },
                        focusRequester = emailFocusRequester
                    )

                    // Username Field (Sign Up only)
                    if (currentMode == AuthMode.SIGN_UP) {
                        LoginTextField(
                            value = username,
                            onValueChange = {
                                username = it.filter { c -> c.isLetterOrDigit() || c == '_' }
                                errorMessage = null
                            },
                            label = "Username",
                            placeholder = "Choose a username",
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next,
                            onImeAction = { displayNameFocusRequester.requestFocus() },
                            focusRequester = usernameFocusRequester
                        )

                        LoginTextField(
                            value = displayName,
                            onValueChange = {
                                displayName = it
                                errorMessage = null
                            },
                            label = "Display Name",
                            placeholder = "Your display name",
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next,
                            onImeAction = { passwordFocusRequester.requestFocus() },
                            focusRequester = displayNameFocusRequester
                        )
                    }

                    // Password Field
                    LoginTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        label = "Password",
                        placeholder = "Enter your password",
                        isPassword = true,
                        passwordVisible = passwordVisible,
                        onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
                        keyboardType = KeyboardType.Password,
                        imeAction = if (currentMode == AuthMode.SIGN_UP) ImeAction.Next else ImeAction.Done,
                        onImeAction = {
                            if (currentMode == AuthMode.SIGN_UP) {
                                confirmPasswordFocusRequester.requestFocus()
                            } else {
                                handleAuthentication()
                            }
                        },
                        focusRequester = passwordFocusRequester
                    )

                    // Forgot Password (Sign In only)
                    if (currentMode == AuthMode.SIGN_IN) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    forgotPasswordEmail = email
                                    forgotPasswordSent = false
                                    forgotPasswordError = null
                                    showForgotPasswordDialog = true
                                }
                            ) {
                                Text(
                                    text = "Forgot Password?",
                                    fontSize = 14.sp,
                                    color = StitchColors.primary
                                )
                            }
                        }
                    }

                    // Password Requirements (Sign Up only)
                    if (currentMode == AuthMode.SIGN_UP && password.isNotEmpty()) {
                        PasswordRequirementRow(
                            text = "At least 8 characters",
                            isMet = password.length >= 8
                        )
                    }

                    // Confirm Password Field (Sign Up only)
                    if (currentMode == AuthMode.SIGN_UP) {
                        LoginTextField(
                            value = confirmPassword,
                            onValueChange = {
                                confirmPassword = it
                                errorMessage = null
                            },
                            label = "Confirm Password",
                            placeholder = "Confirm your password",
                            isPassword = true,
                            passwordVisible = confirmPasswordVisible,
                            onPasswordVisibilityToggle = { confirmPasswordVisible = !confirmPasswordVisible },
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            onImeAction = { handleAuthentication() },
                            focusRequester = confirmPasswordFocusRequester
                        )

                        // Password match warning
                        if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Warning",
                                    tint = StitchColors.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Passwords do not match",
                                    color = StitchColors.error,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // Error Message
                    if (errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = StitchColors.error.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = "Error",
                                    tint = StitchColors.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = errorMessage ?: "",
                                    color = StitchColors.error,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Primary Action Button
                    Button(
                        onClick = { handleAuthentication() },
                        enabled = isFormValid && !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StitchColors.primary,
                            disabledContainerColor = StitchColors.buttonDisabled
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = currentMode.buttonText,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // MARK: - Mode Switcher
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(if (animateWelcome) 1f else 0f)
            ) {
                Text(
                    text = if (currentMode == AuthMode.SIGN_IN)
                        "Don't have an account?" else "Already have an account?",
                    fontSize = 14.sp,
                    color = StitchColors.textSecondary
                )

                Spacer(modifier = Modifier.width(4.dp))

                TextButton(
                    onClick = {
                        switchMode(
                            if (currentMode == AuthMode.SIGN_IN) AuthMode.SIGN_UP
                            else AuthMode.SIGN_IN
                        )
                    }
                ) {
                    Text(
                        text = if (currentMode == AuthMode.SIGN_IN) "Sign Up" else "Sign In",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StitchColors.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }

        // MARK: - Success Overlay
        if (showingSuccess) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(StitchColors.modalOverlay),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Logo with checkmark
                    Box {
                        Image(
                            painter = painterResource(id = R.drawable.stitchsociallogo),
                            contentDescription = "Stitch Social Logo",
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(20.dp)),
                            contentScale = ContentScale.Fit
                        )

                        // Success badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 8.dp, y = 8.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(StitchColors.success),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Success",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Text(
                        text = "Welcome to Stitch!",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        text = "Your account is ready to go",
                        fontSize = 16.sp,
                        color = StitchColors.textSecondary
                    )
                }
            }
        }

        // MARK: - Loading Overlay
        if (isLoading && !showingSuccess) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.8f)
                    ),
                    border = BorderStroke(1.dp, StitchColors.inputBorder)
                ) {
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

                        Text(
                            text = "Authenticating...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // MARK: - Forgot Password Dialog
        if (showForgotPasswordDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (!isLoading) {
                        showForgotPasswordDialog = false
                    }
                },
                containerColor = Color(0xFF1A1A1A),
                titleContentColor = Color.White,
                textContentColor = StitchColors.textSecondary,
                title = {
                    Text(
                        text = if (forgotPasswordSent) "Email Sent!" else "Reset Password",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (forgotPasswordSent) {
                            // Success message
                            Text(
                                text = "We've sent a password reset link to:",
                                color = StitchColors.textSecondary
                            )
                            Text(
                                text = forgotPasswordEmail,
                                color = StitchColors.primary,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Please check your inbox and follow the instructions to reset your password.",
                                color = StitchColors.textSecondary,
                                fontSize = 14.sp
                            )
                        } else {
                            // Email input
                            Text(
                                text = "Enter your email address and we'll send you a link to reset your password.",
                                color = StitchColors.textSecondary
                            )

                            OutlinedTextField(
                                value = forgotPasswordEmail,
                                onValueChange = {
                                    forgotPasswordEmail = it.trim()
                                    forgotPasswordError = null
                                },
                                placeholder = {
                                    Text(
                                        text = "Email address",
                                        color = StitchColors.placeholder
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = StitchColors.primary,
                                    unfocusedBorderColor = StitchColors.glassBorder,
                                    focusedContainerColor = StitchColors.inputBackground,
                                    unfocusedContainerColor = StitchColors.inputBackground,
                                    cursorColor = StitchColors.primary
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { handleForgotPassword() }
                                )
                            )

                            // Error message
                            if (forgotPasswordError != null) {
                                Text(
                                    text = forgotPasswordError ?: "",
                                    color = StitchColors.error,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (forgotPasswordSent) {
                        TextButton(
                            onClick = { showForgotPasswordDialog = false }
                        ) {
                            Text(
                                text = "Done",
                                color = StitchColors.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        TextButton(
                            onClick = { handleForgotPassword() },
                            enabled = !isLoading && forgotPasswordEmail.isNotBlank()
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = StitchColors.primary,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = "Send Reset Link",
                                    color = if (forgotPasswordEmail.isNotBlank())
                                        StitchColors.primary else StitchColors.textSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    if (!forgotPasswordSent) {
                        TextButton(
                            onClick = { showForgotPasswordDialog = false },
                            enabled = !isLoading
                        ) {
                            Text(
                                text = "Cancel",
                                color = StitchColors.textSecondary
                            )
                        }
                    }
                }
            )
        }
    }
}

// MARK: - Login Text Field (Renamed to avoid conflicts)

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
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = StitchColors.textSecondary
        )

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    color = StitchColors.placeholder
                )
            },
            visualTransformation = if (isPassword && !passwordVisible)
                PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = { onPasswordVisibilityToggle?.invoke() }) {
                        Icon(
                            imageVector = if (passwordVisible)
                                Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible)
                                "Hide password" else "Show password",
                            tint = StitchColors.textSecondary
                        )
                    }
                }
            } else null,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onNext = { onImeAction() },
                onDone = { onImeAction() }
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = StitchColors.primary,
                unfocusedBorderColor = StitchColors.glassBorder,
                focusedContainerColor = StitchColors.inputBackground,
                unfocusedContainerColor = StitchColors.inputBackground,
                cursorColor = StitchColors.primary
            )
        )
    }
}

// MARK: - Password Requirement Row (Renamed to avoid conflicts)

@Composable
fun PasswordRequirementRow(
    text: String,
    isMet: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (isMet) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isMet) StitchColors.success else StitchColors.textSecondary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (isMet) StitchColors.success else StitchColors.textSecondary
        )
    }
}