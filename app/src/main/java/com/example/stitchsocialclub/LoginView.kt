/*
 * LoginView.kt
 * StitchSocial Android
 *
 * Layer 8: Views - Authentication Interface WITH REAL AUTH INTEGRATION
 * Dependencies: AuthService (Layer 4), Validation (Layer 1)
 * Features: Email/password login, signup, validation, error handling
 *
 * BLUEPRINT: LoginView.swift exact Compose translation with real AuthService calls
 */

package com.example.stitchsocialclub.views

import com.example.stitchsocialclub.services.AuthService

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stitchsocialclub.foundation.*
import com.example.stitchsocial.foundation.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main login/signup screen with glassmorphism design and REAL AUTHENTICATION
 * BLUEPRINT: LoginView.swift with Compose architecture
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginView(
    authService: AuthService = AuthService(),
    onLoginSuccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Authentication state
    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }

    // UI state
    var isLoading by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var showSuccessAnimation by remember { mutableStateOf(false) }

    // Validation state
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var displayNameError by remember { mutableStateOf<String?>(null) }

    // Focus and keyboard
    val emailFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val confirmPasswordFocusRequester = remember { FocusRequester() }
    val usernameFocusRequester = remember { FocusRequester() }
    val displayNameFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // Observe AuthService state for error handling
    val authServiceError by authService.lastError.collectAsState()
    val authServiceLoading by authService.isLoading.collectAsState()

    // Update local state when AuthService state changes
    LaunchedEffect(authServiceError) {
        authServiceError?.let { error ->
            authError = error.message
            isLoading = false
        }
    }

    LaunchedEffect(authServiceLoading) {
        isLoading = authServiceLoading
    }

    // Animated background
    val infiniteTransition = rememberInfiniteTransition(label = "background")
    val backgroundOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "background_offset"
    )

    // Validation functions
    fun validateEmail() {
        val result = if (email.isBlank()) {
            ValidationResult.failure("Email is required")
        } else if (!email.contains("@") || !email.contains(".")) {
            ValidationResult.failure("Please enter a valid email address")
        } else {
            ValidationResult.success()
        }
        emailError = if (result.isValid) null else result.errors.firstOrNull()
    }

    fun validatePassword() {
        val result = if (password.length < 8) {
            ValidationResult.failure("Password must be at least 8 characters long")
        } else if (!password.any { it.isUpperCase() }) {
            ValidationResult.failure("Password must contain at least one uppercase letter")
        } else if (!password.any { it.isLowerCase() }) {
            ValidationResult.failure("Password must contain at least one lowercase letter")
        } else if (!password.any { it.isDigit() }) {
            ValidationResult.failure("Password must contain at least one number")
        } else {
            ValidationResult.success()
        }
        passwordError = if (result.isValid) null else result.errors.firstOrNull()
    }

    fun validateUsername() {
        if (!isLoginMode) {
            val result = if (username.isBlank()) {
                ValidationResult.failure("Username is required")
            } else if (username.length < 3) {
                ValidationResult.failure("Username must be at least 3 characters long")
            } else if (username.length > 20) {
                ValidationResult.failure("Username cannot exceed 20 characters")
            } else if (!username.matches(Regex("^[a-zA-Z0-9_]+$"))) {
                ValidationResult.failure("Username can only contain letters, numbers, and underscores")
            } else {
                ValidationResult.success()
            }
            usernameError = if (result.isValid) null else result.errors.firstOrNull()
        }
    }

    fun validateDisplayName() {
        if (!isLoginMode) {
            val result = if (displayName.isBlank()) {
                ValidationResult.failure("Display name is required")
            } else if (displayName.length > 50) {
                ValidationResult.failure("Display name cannot exceed 50 characters")
            } else {
                ValidationResult.success()
            }
            displayNameError = if (result.isValid) null else result.errors.firstOrNull()
        }
    }

    // REAL AUTHENTICATION HANDLER - FIXED!
    fun handleAuthentication() {
        validateEmail()
        validatePassword()
        if (!isLoginMode) {
            validateUsername()
            validateDisplayName()
        }

        val hasErrors = emailError != null || passwordError != null ||
                (!isLoginMode && (usernameError != null || displayNameError != null))

        if (hasErrors) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            return
        }

        coroutineScope.launch {
            try {
                isLoading = true
                authError = null
                keyboardController?.hide()

                if (isLoginMode) {
                    // REAL LOGIN - Using actual AuthService
                    println("🔐 LOGIN: Attempting real login for $email")
                    val result = authService.signIn(email, password)

                    if (result.success) {
                        println("✅ LOGIN: Success! UserID = ${result.userId}")
                        showSuccessAnimation = true
                        delay(1500)
                        onLoginSuccess()
                    } else {
                        authError = "Login failed. Please check your credentials."
                    }

                } else {
                    // REAL SIGNUP - Using actual AuthService
                    println("🔐 SIGNUP: Attempting real signup for $email")
                    val result = authService.signUp(email, password, displayName, username)

                    if (result.success) {
                        println("✅ SIGNUP: Success! UserID = ${result.userId}")
                        showSuccessAnimation = true
                        delay(1500)
                        onLoginSuccess()
                    } else {
                        authError = "Signup failed. Please try again."
                    }
                }

            } catch (error: Exception) {
                authError = error.message ?: "Authentication failed"
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                println("❌ AUTH: Error - ${error.message}")
            } finally {
                isLoading = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black,
                        Color(0xFF001122),
                        Color.Black
                    ),
                    center = androidx.compose.ui.geometry.Offset(
                        backgroundOffset,
                        backgroundOffset
                    )
                )
            )
    ) {
        // Animated background elements
        AnimatedBackgroundElements(backgroundOffset)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(0.5f))

            // Logo and title
            LoginHeader(isLoginMode = isLoginMode)

            Spacer(modifier = Modifier.height(48.dp))

            // Main login card
            LoginCard(
                isLoginMode = isLoginMode,
                email = email,
                password = password,
                confirmPassword = confirmPassword,
                username = username,
                displayName = displayName,
                showPassword = showPassword,
                showConfirmPassword = showConfirmPassword,
                rememberMe = rememberMe,
                isLoading = isLoading,
                emailError = emailError,
                passwordError = passwordError,
                usernameError = usernameError,
                displayNameError = displayNameError,
                authError = authError,
                emailFocusRequester = emailFocusRequester,
                passwordFocusRequester = passwordFocusRequester,
                confirmPasswordFocusRequester = confirmPasswordFocusRequester,
                usernameFocusRequester = usernameFocusRequester,
                displayNameFocusRequester = displayNameFocusRequester,
                onEmailChange = {
                    email = it
                    if (emailError != null) validateEmail()
                },
                onPasswordChange = {
                    password = it
                    if (passwordError != null) validatePassword()
                },
                onConfirmPasswordChange = { confirmPassword = it },
                onUsernameChange = {
                    username = it
                    if (usernameError != null) validateUsername()
                },
                onDisplayNameChange = {
                    displayName = it
                    if (displayNameError != null) validateDisplayName()
                },
                onShowPasswordToggle = { showPassword = !showPassword },
                onShowConfirmPasswordToggle = { showConfirmPassword = !showConfirmPassword },
                onRememberMeToggle = { rememberMe = !rememberMe },
                onSubmit = { handleAuthentication() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Mode toggle
            LoginModeToggle(
                isLoginMode = isLoginMode,
                onToggle = {
                    isLoginMode = !isLoginMode
                    authError = null
                    emailError = null
                    passwordError = null
                    usernameError = null
                    displayNameError = null
                    authService.clearError() // Clear AuthService errors
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            // Forgot password (login mode only)
            if (isLoginMode) {
                ForgotPasswordLink(authService)
            }
        }

        // Success animation overlay
        if (showSuccessAnimation) {
            SuccessAnimationOverlay()
        }
    }
}

@Composable
private fun AnimatedBackgroundElements(offset: Float) {
    Box(modifier = Modifier.fillMaxSize()) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .offset(
                        x = (50 + index * 100).dp,
                        y = (100 + index * 150).dp
                    )
                    .scale(0.8f + index * 0.1f)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                StitchColors.primary.copy(alpha = 0.1f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
                    .blur(radius = 50.dp)
            )
        }
    }
}

@Composable
private fun LoginHeader(isLoginMode: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App logo/icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            StitchColors.primary,
                            StitchColors.secondary
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "S",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "STITCH",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            letterSpacing = 4.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        AnimatedContent(
            targetState = isLoginMode,
            transitionSpec = {
                slideInVertically { -it } + fadeIn() togetherWith
                        slideOutVertically { it } + fadeOut()
            },
            label = "mode_text"
        ) { isLogin ->
            Text(
                text = if (isLogin) "Welcome back" else "Join the community",
                fontSize = 16.sp,
                color = StitchColors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoginCard(
    isLoginMode: Boolean,
    email: String,
    password: String,
    confirmPassword: String,
    username: String,
    displayName: String,
    showPassword: Boolean,
    showConfirmPassword: Boolean,
    rememberMe: Boolean,
    isLoading: Boolean,
    emailError: String?,
    passwordError: String?,
    usernameError: String?,
    displayNameError: String?,
    authError: String?,
    emailFocusRequester: FocusRequester,
    passwordFocusRequester: FocusRequester,
    confirmPasswordFocusRequester: FocusRequester,
    usernameFocusRequester: FocusRequester,
    displayNameFocusRequester: FocusRequester,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onShowPasswordToggle: () -> Unit,
    onShowConfirmPasswordToggle: () -> Unit,
    onRememberMeToggle: () -> Unit,
    onSubmit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Error message
            authError?.let { error ->
                ErrorMessage(error)
            }

            // Signup fields (username, display name)
            AnimatedVisibility(
                visible = !isLoginMode,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StitchTextField(
                        value = username,
                        onValueChange = onUsernameChange,
                        label = "Username",
                        icon = Icons.Outlined.Person,
                        error = usernameError,
                        focusRequester = usernameFocusRequester,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { displayNameFocusRequester.requestFocus() }
                        )
                    )

                    StitchTextField(
                        value = displayName,
                        onValueChange = onDisplayNameChange,
                        label = "Display Name",
                        icon = Icons.Outlined.Badge,
                        error = displayNameError,
                        focusRequester = displayNameFocusRequester,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { emailFocusRequester.requestFocus() }
                        )
                    )
                }
            }

            // Email field
            StitchTextField(
                value = email,
                onValueChange = onEmailChange,
                label = "Email",
                icon = Icons.Outlined.Email,
                error = emailError,
                focusRequester = emailFocusRequester,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { passwordFocusRequester.requestFocus() }
                )
            )

            // Password field
            StitchTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "Password",
                icon = Icons.Outlined.Lock,
                error = passwordError,
                focusRequester = passwordFocusRequester,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onShowPasswordToggle) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            tint = StitchColors.textSecondary
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (isLoginMode) ImeAction.Done else ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = if (!isLoginMode) { { confirmPasswordFocusRequester.requestFocus() } } else null,
                    onDone = if (isLoginMode) { { onSubmit() } } else null
                )
            )

            // Confirm password (signup only)
            AnimatedVisibility(
                visible = !isLoginMode,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                StitchTextField(
                    value = confirmPassword,
                    onValueChange = onConfirmPasswordChange,
                    label = "Confirm Password",
                    icon = Icons.Outlined.Lock,
                    error = if (confirmPassword.isNotEmpty() && password != confirmPassword) "Passwords don't match" else null,
                    focusRequester = confirmPasswordFocusRequester,
                    visualTransformation = if (showConfirmPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onShowConfirmPasswordToggle) {
                            Icon(
                                imageVector = if (showConfirmPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showConfirmPassword) "Hide password" else "Show password",
                                tint = StitchColors.textSecondary
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onSubmit() }
                    )
                )
            }

            // Remember me (login only)
            if (isLoginMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = rememberMe,
                        onCheckedChange = { onRememberMeToggle() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = StitchColors.primary,
                            uncheckedColor = StitchColors.textSecondary
                        )
                    )

                    Text(
                        text = "Remember me",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { onRememberMeToggle() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Submit button
            StitchButton(
                onClick = onSubmit,
                text = if (isLoginMode) "Sign In" else "Create Account",
                isLoading = isLoading,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StitchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    error: String? = null,
    focusRequester: FocusRequester = FocusRequester(),
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, color = StitchColors.textSecondary) },
            leadingIcon = {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (error != null) Color.Red else StitchColors.textSecondary
                )
            },
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            isError = error != null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = StitchColors.primary,
                unfocusedBorderColor = StitchColors.textSecondary,
                errorBorderColor = Color.Red,
                cursorColor = StitchColors.primary
            ),
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
        )

        error?.let { errorText ->
            Text(
                text = errorText,
                color = Color.Red,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun StitchButton(
    onClick: () -> Unit,
    text: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current

    Button(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StitchColors.primary,
            disabledContainerColor = StitchColors.primary.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        enabled = !isLoading
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Text(
                text = text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun LoginModeToggle(
    isLoginMode: Boolean,
    onToggle: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isLoginMode) "Don't have an account?" else "Already have an account?",
            color = StitchColors.textSecondary,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = if (isLoginMode) "Sign Up" else "Sign In",
            color = StitchColors.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onToggle() }
        )
    }
}

@Composable
private fun ForgotPasswordLink(authService: AuthService) {
    Text(
        text = "Forgot Password?",
        color = StitchColors.primary,
        fontSize = 14.sp,
        modifier = Modifier.clickable {
            // TODO: Implement forgot password flow with real AuthService
            println("🔑 FORGOT PASSWORD: Navigate to reset")
        }
    )
}

@Composable
private fun ErrorMessage(error: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.Red.copy(alpha = 0.1f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "Error",
                tint = Color.Red,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = error,
                color = Color.Red,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun SuccessAnimationOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.1f)
            ),
            modifier = Modifier.size(200.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Success",
                    tint = Color.Green,
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Welcome!",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// MARK: - Stitch Colors
object StitchColors {
    val primary = Color(0xFF00BFFF)
    val secondary = Color(0xFF1E90FF)
    val textSecondary = Color(0xFF9CA3AF)
}