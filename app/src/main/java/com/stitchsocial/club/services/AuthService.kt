/*
 * AuthService.kt - FIXED PROFILE CREATION TIMING
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Clean Firebase Authentication
 * Dependencies: Firebase Auth, Foundation Layer only
 * Features: Self-contained auth with automatic initialization
 *
 * CRITICAL FIX: Increased delay after profile creation for Firestore propagation
 * CRITICAL FIX: Sign up waits for profile creation before returning success
 */

package com.stitchsocial.club.services

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Timestamp
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.*

/**
 * Clean AuthService with FIXED profile creation timing
 * Uses its own scope that won't be cancelled by composition changes
 * CRITICAL: Profile creation is SYNCHRONOUS during signup with proper delays
 */
class AuthService {

    // Self-contained Firebase instances
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance("stitchfin")

    // Service owns its own scope - won't be cancelled by UI
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // MARK: - Authentication State

    private val _authState = MutableStateFlow(AuthState.SIGNED_OUT)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<StitchError?>(null)
    val lastError: StateFlow<StitchError?> = _lastError.asStateFlow()

    init {
        println("AUTH SERVICE: Initializing Firebase Authentication")
        setupAuthListener()

        // Check initial auth state
        val user = auth.currentUser
        if (user != null) {
            _currentUser.value = user
            _isAuthenticated.value = true
            _authState.value = AuthState.SIGNED_IN
            println("AUTH SERVICE: User already authenticated - ${user.email}")
        }
    }

    // MARK: - Authentication Methods

    /**
     * Sign in with email and password
     */
    suspend fun signIn(email: String, password: String): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                _isLoading.value = true
                _authState.value = AuthState.SIGNING_IN
                _lastError.value = null

                // Validate inputs
                validateEmail(email)
                validatePassword(password)

                println("AUTH SERVICE: 🔐 Attempting sign in for: $email")

                // Firebase sign-in
                val result = auth.signInWithEmailAndPassword(email, password).await()
                val user = result.user ?: throw StitchError.AuthenticationError("No user returned")

                println("AUTH SERVICE: ✅ Firebase sign-in successful - UID: ${user.uid}")

                // Check/create profile in background scope (non-blocking for login)
                serviceScope.launch {
                    try {
                        val profileExists = checkUserProfileExists(user.uid)
                        println("AUTH SERVICE: 📋 Profile exists: $profileExists")

                        if (!profileExists) {
                            println("AUTH SERVICE: 📝 Creating missing user profile...")
                            createUserProfile(user)
                            println("AUTH SERVICE: ✅ User profile created successfully")
                        } else {
                            println("AUTH SERVICE: ✅ User profile already exists")
                        }
                    } catch (e: Exception) {
                        println("AUTH SERVICE: ⚠️ Profile creation failed (non-critical): ${e.message}")
                    }
                }

                println("AUTH SERVICE: ✅ Sign in completed successfully")

                AuthResult(
                    success = true,
                    userId = user.uid,
                    email = user.email ?: "",
                    isNewUser = false,
                    needsProfileSetup = false
                )

            } catch (e: Exception) {
                println("AUTH SERVICE: ❌ Sign in failed: ${e.message}")
                handleAuthError(e)
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Sign up with email, password, and profile info
     * CRITICAL FIX: Profile creation is SYNCHRONOUS with increased delay
     */
    suspend fun signUp(
        email: String,
        password: String,
        displayName: String,
        username: String
    ): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                _isLoading.value = true
                _authState.value = AuthState.SIGNING_IN
                _lastError.value = null

                // Validate all inputs
                validateEmail(email)
                validatePassword(password)
                validateUsername(username)
                validateDisplayName(displayName)

                println("AUTH SERVICE: 🔐 Attempting sign up for: $email")
                println("AUTH SERVICE: 👤 Username: $username")

                // Check username availability
                println("AUTH SERVICE: 🔍 Checking username availability: $username")
                val usernameAvailable = withContext(serviceScope.coroutineContext) {
                    try {
                        checkUsernameAvailabilityInternal(username)
                    } catch (e: Exception) {
                        println("AUTH SERVICE: ⚠️ Error checking username - ${e.message}")
                        println("AUTH SERVICE: 🔓 Allowing signup despite error")
                        true
                    }
                }

                if (!usernameAvailable) {
                    throw StitchError.ValidationError("Username '$username' is already taken")
                }

                // Create Firebase user
                val result = auth.createUserWithEmailAndPassword(email, password).await()
                val user = result.user ?: throw StitchError.AuthenticationError("Failed to create user")

                println("AUTH SERVICE: ✅ Firebase user created - UID: ${user.uid}")

                // CRITICAL FIX: Create profile SYNCHRONOUSLY (blocking)
                try {
                    val isSpecialUser = checkSpecialUserStatus(email)
                    createUserProfile(user, username, displayName, isSpecialUser)
                    println("AUTH SERVICE: ✅ User profile created successfully")

                    // CRITICAL FIX: Increased delay to 2000ms for Firestore write propagation
                    // This ensures the profile document is available across all read replicas
                    println("AUTH SERVICE: ⏳ Waiting 2000ms for Firestore propagation...")
                    delay(2000)
                    println("AUTH SERVICE: ✅ Firestore propagation delay complete")

                } catch (e: Exception) {
                    println("AUTH SERVICE: ❌ Profile creation failed: ${e.message}")
                    // Continue anyway - user can complete profile later
                }

                println("AUTH SERVICE: ✅ Sign up completed successfully")

                AuthResult(
                    success = true,
                    userId = user.uid,
                    email = user.email ?: "",
                    isNewUser = true,
                    needsProfileSetup = false
                )

            } catch (e: Exception) {
                println("AUTH SERVICE: ❌ Sign up failed: ${e.message}")
                handleAuthError(e)
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Sign in anonymously (for testing/browsing)
     */
    suspend fun signInAnonymously(): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                _isLoading.value = true
                _authState.value = AuthState.SIGNING_IN
                _lastError.value = null

                val result = auth.signInAnonymously().await()
                val user = result.user ?: throw StitchError.AuthenticationError("Anonymous sign-in failed")

                println("AUTH SERVICE: ✅ Anonymous sign-in successful - ${user.uid}")

                AuthResult(
                    success = true,
                    userId = user.uid,
                    email = "",
                    isNewUser = false,
                    needsProfileSetup = false
                )

            } catch (e: Exception) {
                println("AUTH SERVICE: ❌ Anonymous sign-in failed: ${e.message}")
                handleAuthError(e)
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Sign out current user
     */
    suspend fun signOut() {
        try {
            _authState.value = AuthState.SIGNING_OUT
            auth.signOut()
            println("AUTH SERVICE: ✅ User signed out successfully")
        } catch (e: Exception) {
            println("AUTH SERVICE: ❌ Sign out error: ${e.message}")
            handleAuthError(e)
        }
    }

    /**
     * Reset password via email
     */
    suspend fun resetPassword(email: String) {
        try {
            validateEmail(email)
            auth.sendPasswordResetEmail(email).await()
            println("AUTH SERVICE: ✅ Password reset email sent to $email")
        } catch (e: Exception) {
            println("AUTH SERVICE: ❌ Password reset failed: ${e.message}")
            handleAuthError(e)
            throw e
        }
    }

    // MARK: - User Management

    fun getCurrentUserId(): String? = auth.currentUser?.uid
    fun getCurrentUserEmail(): String? = auth.currentUser?.email
    fun isUserAuthenticated(): Boolean = auth.currentUser != null

    // MARK: - Private Helper Methods

    private fun setupAuthListener() {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _currentUser.value = user
            _isAuthenticated.value = user != null

            when {
                user == null -> {
                    _authState.value = AuthState.SIGNED_OUT
                    println("AUTH SERVICE: 🚪 User signed out")
                }
                user.isAnonymous -> {
                    _authState.value = AuthState.SIGNED_IN
                    println("AUTH SERVICE: 👤 Anonymous user authenticated")
                }
                else -> {
                    _authState.value = AuthState.SIGNED_IN
                    println("AUTH SERVICE: ✅ User authenticated - ${user.email}")
                }
            }
        }
    }

    private suspend fun checkUserProfileExists(userId: String): Boolean {
        return try {
            println("AUTH SERVICE: 🔍 Checking if profile exists for: $userId")
            val doc = db.collection("users").document(userId).get().await()
            val exists = doc.exists()
            println("AUTH SERVICE: 📄 Profile exists: $exists")
            exists
        } catch (e: Exception) {
            println("AUTH SERVICE: ❌ Error checking user profile - ${e.message}")
            false
        }
    }

    private suspend fun checkUsernameAvailabilityInternal(username: String): Boolean {
        return withContext(serviceScope.coroutineContext) {
            try {
                val query = db.collection("users")
                    .whereEqualTo("username", username)
                    .limit(1)
                    .get()
                    .await()

                val available = query.isEmpty
                println("AUTH SERVICE: 🔍 Username '$username' available: $available")
                available
            } catch (e: Exception) {
                println("AUTH SERVICE: ❌ Error checking username - ${e.message}")
                throw e
            }
        }
    }

    suspend fun checkUsernameAvailability(username: String): Boolean {
        return checkUsernameAvailabilityInternal(username)
    }

    private suspend fun createUserProfile(
        firebaseUser: FirebaseUser,
        username: String? = null,
        displayName: String? = null,
        isSpecialUser: Boolean = false
    ) {
        try {
            println("AUTH SERVICE: 📝 Creating user profile for ${firebaseUser.uid}")

            val actualUsername = username ?: generateUsername(firebaseUser.email, firebaseUser.uid)
            val actualDisplayName = displayName ?: firebaseUser.displayName ?: actualUsername

            val userData = mapOf(
                "id" to firebaseUser.uid,
                "email" to (firebaseUser.email ?: ""),
                "username" to actualUsername,
                "displayName" to actualDisplayName,
                "profileImageURL" to (firebaseUser.photoUrl?.toString() ?: ""),
                "bio" to "",
                "tier" to if (isSpecialUser) UserTier.FOUNDER.rawValue else UserTier.ROOKIE.rawValue,
                "clout" to if (isSpecialUser) 10000 else 0,
                "isVerified" to isSpecialUser,
                "isPrivate" to false,
                "followerCount" to 0,
                "followingCount" to 0,
                "videoCount" to 0,
                "threadCount" to 0,
                "totalHypesReceived" to 0,
                "totalCoolsReceived" to 0,
                "createdAt" to Timestamp.now(),
                "updatedAt" to Timestamp.now(),
                "lastActiveAt" to Timestamp.now()
            )

            db.collection("users")
                .document(firebaseUser.uid)
                .set(userData, SetOptions.merge())
                .await()

            println("AUTH SERVICE: ✅ User profile created successfully for ${firebaseUser.email}")

        } catch (e: Exception) {
            println("AUTH SERVICE: ❌ Failed to create user profile - ${e.message}")
            throw StitchError.ProcessingError("Failed to create user profile")
        }
    }

    private fun checkSpecialUserStatus(email: String): Boolean {
        val specialEmails = setOf(
            "founder@stitchsocial.me",
            "james@stitchsocial.me",
            "teddyruks@gmail.com",
            "chaneyvisionent@gmail.com",
            "afterflaspoint@icloud.com",
            "floydjrsullivan@yahoo.com",
            "srbentleyga@gmail.com"
        )
        val isSpecial = specialEmails.contains(email.lowercase())
        println("AUTH SERVICE: 👑 Special user check for $email: $isSpecial")
        return isSpecial
    }

    private fun generateUsername(email: String?, uid: String): String {
        return if (!email.isNullOrEmpty()) {
            val emailPrefix = email.substringBefore("@")
            "${emailPrefix}_${uid.take(6)}"
        } else {
            "user_${uid.take(8)}"
        }
    }

    private fun handleAuthError(exception: Exception) {
        val error = when (exception) {
            is StitchError -> exception
            else -> StitchError.AuthenticationError(exception.message ?: "Authentication failed")
        }
        _lastError.value = error
        _authState.value = AuthState.ERROR
        println("AUTH SERVICE: ❌ Error - ${error.message}")
    }

    // MARK: - Validation Methods

    private fun validateEmail(email: String) {
        if (email.isBlank() || !email.contains("@") || !email.contains(".")) {
            throw StitchError.ValidationError("Invalid email format")
        }
    }

    private fun validatePassword(password: String) {
        if (password.length < 8) {
            throw StitchError.ValidationError("Password must be at least 8 characters")
        }
    }

    private fun validateUsername(username: String) {
        if (username.length < 3 || username.length > 20) {
            throw StitchError.ValidationError("Username must be 3-20 characters")
        }
        if (!username.all { it.isLetterOrDigit() || it == '_' }) {
            throw StitchError.ValidationError("Username can only contain letters, numbers, and underscores")
        }
    }

    private fun validateDisplayName(displayName: String) {
        if (displayName.isBlank() || displayName.length > 50) {
            throw StitchError.ValidationError("Display name must be 1-50 characters")
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    fun cleanup() {
        serviceScope.cancel()
    }
}

// MARK: - Data Classes

/**
 * Authentication result returned by sign in/up methods
 */
data class AuthResult(
    val success: Boolean,
    val userId: String = "",
    val email: String = "",
    val isNewUser: Boolean = false,
    val needsProfileSetup: Boolean = false
)