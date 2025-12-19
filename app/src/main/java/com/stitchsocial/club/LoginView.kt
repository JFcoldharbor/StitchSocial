/*
 * LoginView.kt - FIXED WITH STABLE COROUTINE SCOPE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Authentication interface
 * CRITICAL FIX: Uses rememberCoroutineScope instead of LaunchedEffect
 * This prevents scope cancellation during Firebase auth calls
 * ✅ PACKAGE FIXED: com.stitchsocial.club.views
 */

package com.stitchsocial.club

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.services.AuthService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers

/**
 * LoginView with FIXED stable coroutine scope
 * CRITICAL FIX: Uses rememberCoroutineScope to prevent cancellation
 */
@Composable
fun LoginView(
    authService: AuthService,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Remove rememberCoroutineScope - not needed with GlobalScope
    val keyboardController = LocalSoftwareKeyboardController.current

    // Form State
    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // UI State
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Focus Management
    val emailFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val usernameFocusRequester = remember { FocusRequester() }
    val displayNameFocusRequester = remember { FocusRequester() }

    // Watch AuthService states
    val authServiceLoading by authService.isLoading.collectAsState()
    val authServiceError by authService.lastError.collectAsState()

    // Sync with AuthService states
    LaunchedEffect(authServiceLoading) {
        isLoading = authServiceLoading
    }

    LaunchedEffect(authServiceError) {
        errorMessage = authServiceError?.message
    }

    // CRITICAL FIX: Use GlobalScope to prevent cancellation during composition
    fun handleAuthentication() {
        keyboardController?.hide()

        // Basic validation
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Please fill in all fields"
            return
        }

        if (!isLoginMode && (username.isBlank() || displayName.isBlank())) {
            errorMessage = "Please fill in all fields"
            return
        }

        // Use GlobalScope - won't be cancelled by Compose recomposition
        GlobalScope.launch(Dispatchers.Main) {
            try {
                isLoading = true
                errorMessage = null

                println("🔐 AUTH: Starting ${if (isLoginMode) "LOGIN" else "SIGNUP"}")

                if (isLoginMode) {
                    // LOGIN FLOW
                    println("🔐 AUTH: Calling authService.signIn('$email', '***')")
                    val result = authService.signIn(email, password)

                    if (result.success) {
                        println("✅ AUTH: Login successful - User ID: ${result.userId}")
                        delay(100) // Small delay for state stabilization
                        onLoginSuccess()
                    } else {
                        println("❌ AUTH: Login failed")
                        errorMessage = "Login failed. Please check your credentials."
                    }
                } else {
                    // SIGNUP FLOW
                    println("🔐 AUTH: Calling authService.signUp('$email', '***', '$displayName', '$username')")
                    val result = authService.signUp(email, password, displayName, username)

                    if (result.success) {
                        println("✅ AUTH: Signup successful - User ID: ${result.userId}")
                        // INCREASED: Longer delay for Firestore propagation after signup
                        delay(2000)
                        onLoginSuccess()
                    } else {
                        println("❌ AUTH: Signup failed")
                        errorMessage = "Signup failed. Please try again."
                    }
                }

            } catch (e: Exception) {
                println("🔐 AUTH: Exception - ${e.message}")

                // Better error messages
                errorMessage = when {
                    e.message?.contains("email address is already in use") == true ->
                        "This email is already registered. Please sign in instead."
                    e.message?.contains("network error") == true ->
                        "Network error. Please check your connection."
                    else -> "Authentication error: ${e.message}"
                }
            } finally {
                isLoading = false
            }
        }
    }

    // Guest login handler - also using GlobalScope
    fun handleGuestLogin() {
        GlobalScope.launch(Dispatchers.Main) {
            try {
                isLoading = true
                println("🔐 AUTH: Signing in anonymously as guest...")
                val result = authService.signInAnonymously()

                if (result.success) {
                    println("✅ AUTH: Anonymous login successful")
                    delay(100)
                    onLoginSuccess()
                } else {
                    println("❌ AUTH: Anonymous login failed")
                    errorMessage = "Guest login failed"
                }
            } catch (e: Exception) {
                println("🔐 AUTH: Anonymous login exception - ${e.message}")
                errorMessage = "Guest login error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Colors
    val primaryColor = Color(0xFF00BFFF)
    val backgroundColor = Color.Black
    val surfaceColor = Color(0xFF1A1A1A)
    val goldColor = Color(0xFFFFD700)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // Logo/Title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 48.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            primaryColor.copy(alpha = 0.2f),
                            RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = "Stitch Social",
                        tint = primaryColor,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Stitch Social",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (isLoginMode) "Welcome back" else "Join the community",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Form Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = surfaceColor.copy(alpha = 0.8f)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {

                    // Mode Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(
                            onClick = {
                                isLoginMode = true
                                errorMessage = null
                            }
                        ) {
                            Text(
                                text = "Login",
                                color = if (isLoginMode) primaryColor else Color.Gray,
                                fontWeight = if (isLoginMode) FontWeight.Bold else FontWeight.Normal
                            )
                        }

                        Spacer(modifier = Modifier.width(24.dp))

                        TextButton(
                            onClick = {
                                isLoginMode = false
                                errorMessage = null
                            }
                        ) {
                            Text(
                                text = "Sign Up",
                                color = if (!isLoginMode) primaryColor else Color.Gray,
                                fontWeight = if (!isLoginMode) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    // Email Field
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it.trim()
                            errorMessage = null
                        },
                        label = { Text("Email") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = "Email"
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { passwordFocusRequester.requestFocus() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(emailFocusRequester),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor,
                            focusedLabelColor = primaryColor,
                            cursorColor = primaryColor
                        )
                    )

                    // Password Field
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        label = { Text("Password") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Password"
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible }
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (isLoginMode) ImeAction.Done else ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = if (!isLoginMode) {
                                { usernameFocusRequester.requestFocus() }
                            } else null,
                            onDone = if (isLoginMode) {
                                { handleAuthentication() }
                            } else null
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocusRequester),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor,
                            focusedLabelColor = primaryColor,
                            cursorColor = primaryColor
                        )
                    )

                    // Signup Fields
                    if (!isLoginMode) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                username = it.trim()
                                errorMessage = null
                            },
                            label = { Text("Username") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.AlternateEmail,
                                    contentDescription = "Username"
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { displayNameFocusRequester.requestFocus() }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(usernameFocusRequester),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                focusedLabelColor = primaryColor,
                                cursorColor = primaryColor
                            )
                        )

                        OutlinedTextField(
                            value = displayName,
                            onValueChange = {
                                displayName = it
                                errorMessage = null
                            },
                            label = { Text("Display Name") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Display Name"
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { handleAuthentication() }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(displayNameFocusRequester),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                focusedLabelColor = primaryColor,
                                cursorColor = primaryColor
                            )
                        )
                    }

                    // Error Message
                    if (errorMessage != null) {
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
                                    imageVector = Icons.Default.Error,
                                    contentDescription = "Error",
                                    tint = Color.Red,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = errorMessage ?: "",
                                    color = Color.Red,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // Submit Button
                    Button(
                        onClick = { handleAuthentication() },
                        enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            disabledContainerColor = primaryColor.copy(alpha = 0.5f)
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isLoginMode) Icons.Default.Login else Icons.Default.PersonAdd,
                                    contentDescription = if (isLoginMode) "Login" else "Sign Up",
                                    tint = Color.White
                                )
                                Text(
                                    text = if (isLoginMode) "Sign In" else "Create Account",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Quick Login for Testing
                    Divider(
                        color = Color.Gray.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    Text(
                        text = "Quick Login (Testing)",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                email = "founder@stitchsocial.me"
                                password = "StitchSocial2024!"
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = goldColor.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Founder", fontSize = 12.sp, color = Color.White)
                        }

                        Button(
                            onClick = {
                                email = "test@example.com"
                                password = "Test123456!"
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Gray.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Test User", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Guest Login Option
            TextButton(
                onClick = { handleGuestLogin() }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Guest",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Continue as Guest",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}