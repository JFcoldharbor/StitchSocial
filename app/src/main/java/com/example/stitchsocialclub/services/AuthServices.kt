/*
 * AuthService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Clean Firebase Authentication
 * Dependencies: Firebase Auth, Foundation Layer only
 * Features: Self-contained auth with automatic initialization
 */

package com.example.stitchsocialclub.services

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.example.stitchsocialclub.foundation.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * Clean AuthService with self-contained dependencies
 */
class AuthService {

    // Self-contained Firebase instances
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance("stitchfin")

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
        return try {
            _isLoading.value = true
            _authState.value = AuthState.SIGNING_IN
            _lastError.value = null

            // Validate inputs
            validateEmail(email)
            validatePassword(password)

            // Firebase sign-in
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user ?: throw StitchError.AuthenticationError("No user returned")

            // Check if user profile exists
            val profileExists = checkUserProfileExists(user.uid)
            if (!profileExists) {
                createUserProfile(user)
            }

            println("AUTH SERVICE: Successfully signed in - ${user.email}")

            AuthResult(
                success = true,
                userId = user.uid,
                email = user.email ?: "",
                isNewUser = false,
                needsProfileSetup = !profileExists
            )

        } catch (e: Exception) {
            handleAuthError(e)
            throw e
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Sign up with email, password, and profile info
     */
    suspend fun signUp(
        email: String,
        password: String,
        displayName: String,
        username: String
    ): AuthResult {
        return try {
            _isLoading.value = true
            _authState.value = AuthState.SIGNING_IN
            _lastError.value = null

            // Validate all inputs
            validateEmail(email)
            validatePassword(password)
            validateUsername(username)
            validateDisplayName(displayName)

            // Check username availability
            val usernameAvailable = checkUsernameAvailability(username)
            if (!usernameAvailable) {
                throw StitchError.ValidationError("Username '$username' is already taken")
            }

            // Create Firebase user
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user ?: throw StitchError.AuthenticationError("Failed to create user")

            // Create user profile in Firestore
            val isSpecialUser = checkSpecialUserStatus(email)
            createUserProfile(user, username, displayName, isSpecialUser)

            println("AUTH SERVICE: Successfully created user - ${user.email}")

            AuthResult(
                success = true,
                userId = user.uid,
                email = user.email ?: "",
                isNewUser = true,
                needsProfileSetup = false
            )

        } catch (e: Exception) {
            handleAuthError(e)
            throw e
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Sign in anonymously (for testing/browsing)
     */
    suspend fun signInAnonymously(): AuthResult {
        return try {
            _isLoading.value = true
            _authState.value = AuthState.SIGNING_IN
            _lastError.value = null

            val result = auth.signInAnonymously().await()
            val user = result.user ?: throw StitchError.AuthenticationError("Anonymous sign-in failed")

            println("AUTH SERVICE: Signed in anonymously - ${user.uid}")

            AuthResult(
                success = true,
                userId = user.uid,
                email = "",
                isNewUser = false,
                needsProfileSetup = false
            )

        } catch (e: Exception) {
            handleAuthError(e)
            throw e
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Sign out current user
     */
    suspend fun signOut() {
        try {
            _authState.value = AuthState.SIGNING_OUT
            auth.signOut()
            println("AUTH SERVICE: User signed out successfully")
        } catch (e: Exception) {
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
            println("AUTH SERVICE: Password reset email sent to $email")
        } catch (e: Exception) {
            handleAuthError(e)
            throw e
        }
    }

    // MARK: - User Management

    /**
     * Get current user ID (null if not authenticated)
     */
    fun getCurrentUserId(): String? = auth.currentUser?.uid

    /**
     * Get current user email
     */
    fun getCurrentUserEmail(): String? = auth.currentUser?.email

    /**
     * Check if user is currently authenticated
     */
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
                    println("AUTH SERVICE: User signed out")
                }
                user.isAnonymous -> {
                    _authState.value = AuthState.SIGNED_IN
                    println("AUTH SERVICE: Anonymous user authenticated")
                }
                else -> {
                    _authState.value = AuthState.SIGNED_IN
                    println("AUTH SERVICE: User authenticated - ${user.email}")
                }
            }
        }
    }

    private suspend fun checkUserProfileExists(userId: String): Boolean {
        return try {
            val doc = db.collection("users").document(userId).get().await()
            doc.exists()
        } catch (e: Exception) {
            println("AUTH SERVICE: Error checking user profile - ${e.message}")
            false
        }
    }

    private suspend fun checkUsernameAvailability(username: String): Boolean {
        return try {
            val query = db.collection("users")
                .whereEqualTo("username", username)
                .limit(1)
                .get()
                .await()
            query.isEmpty
        } catch (e: Exception) {
            println("AUTH SERVICE: Error checking username - ${e.message}")
            false
        }
    }

    private suspend fun createUserProfile(
        firebaseUser: FirebaseUser,
        username: String? = null,
        displayName: String? = null,
        isSpecialUser: Boolean = false
    ) {
        try {
            val userData = mapOf(
                "id" to firebaseUser.uid,
                "email" to (firebaseUser.email ?: ""),
                "username" to (username ?: generateUsername(firebaseUser.email)),
                "displayName" to (displayName ?: firebaseUser.displayName ?: "User"),
                "profileImageURL" to (firebaseUser.photoUrl?.toString() ?: ""),
                "bio" to "",
                "tier" to if (isSpecialUser) UserTier.FOUNDER.rawValue else UserTier.ROOKIE.rawValue,
                "clout" to if (isSpecialUser) 10000 else 0,
                "isVerified" to isSpecialUser,
                "isPrivate" to false,
                "createdAt" to Date(),
                "updatedAt" to Date(),

                // Engagement metrics
                "totalVideos" to 0,
                "totalViews" to 0,
                "totalLikes" to 0,
                "followersCount" to 0,
                "followingCount" to 0
            )

            db.collection("users")
                .document(firebaseUser.uid)
                .set(userData, SetOptions.merge())
                .await()

            println("AUTH SERVICE: Created user profile for ${firebaseUser.email}")

        } catch (e: Exception) {
            println("AUTH SERVICE: Failed to create user profile - ${e.message}")
            throw StitchError.ProcessingError("Failed to create user profile")
        }
    }

    private fun checkSpecialUserStatus(email: String): Boolean {
        val specialEmails = setOf(
            "founder@stitchsocial.me",
            "teddyruks@gmail.com",
            "chaneyvisionent@gmail.com",
            "afterflaspoint@icloud.com",
            "floydjrsullivan@yahoo.com",
            "srbentleyga@gmail.com"
        )
        return specialEmails.contains(email.lowercase())
    }

    private fun generateUsername(email: String?): String {
        return email?.substringBefore("@") ?: "user${System.currentTimeMillis()}"
    }

    private fun handleAuthError(exception: Exception) {
        val error = when (exception) {
            is StitchError -> exception
            else -> StitchError.AuthenticationError(exception.message ?: "Authentication failed")
        }
        _lastError.value = error
        _authState.value = AuthState.ERROR
        println("AUTH SERVICE: Error - ${error.message}")
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

    /**
     * Clear error state
     */
    fun clearError() {
        _lastError.value = null
    }
}

// MARK: - Data Classes

/**
 * Authentication result
 */
data class AuthResult(
    val success: Boolean,
    val userId: String = "",
    val email: String = "",
    val isNewUser: Boolean = false,
    val needsProfileSetup: Boolean = false
)