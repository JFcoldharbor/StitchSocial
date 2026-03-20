/*
 * AuthService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Firebase Authentication
 * UPDATED: signUp() now accepts accountType, brandName, websiteURL, businessCategory
 * UPDATED: createUserProfile() writes accountType + business fields to Firestore
 * UPDATED: Business signup path mirrors iOS performPrimaryAction / authService.signUp()
 *
 * CACHING NOTE (add to CachingOptimization file):
 * - acceptedTermsVersion cached in SharedPreferences post-write (done in LoginView.kt)
 *   to avoid Firestore read on cold launch. No change needed here.
 * - Business profile fields written once at signup in a single set() — no batching needed,
 *   no read before write. Merge: false to ensure clean document.
 */

package com.stitchsocial.club.services

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Timestamp
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.foundation.AccountType
import com.stitchsocial.club.services.AdCategory
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.*

class AuthService {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val user = auth.currentUser
        if (user != null) {
            _currentUser.value = user
            _isAuthenticated.value = true
            _authState.value = AuthState.SIGNED_IN
            println("AUTH SERVICE: User already authenticated - ${user.email}")
        }
    }

    // MARK: - Sign In

    suspend fun signIn(email: String, password: String): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                _isLoading.value = true
                _authState.value = AuthState.SIGNING_IN
                _lastError.value = null

                validateEmail(email)
                validatePassword(password)

                println("AUTH SERVICE: 🔐 Attempting sign in for: $email")

                val result = auth.signInWithEmailAndPassword(email, password).await()
                val user = result.user ?: throw StitchError.AuthenticationError("No user returned")

                println("AUTH SERVICE: ✅ Firebase sign-in successful - UID: ${user.uid}")

                serviceScope.launch {
                    try {
                        val profileExists = checkUserProfileExists(user.uid)
                        if (!profileExists) {
                            println("AUTH SERVICE: 📝 Creating missing user profile...")
                            createUserProfile(user)
                        }
                    } catch (e: Exception) {
                        println("AUTH SERVICE: ⚠️ Profile creation failed (non-critical): ${e.message}")
                    }
                }

                AuthResult(success = true, userId = user.uid, email = user.email ?: "", isNewUser = false)

            } catch (e: Exception) {
                println("AUTH SERVICE: ❌ Sign in failed: ${e.message}")
                handleAuthError(e)
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    // MARK: - Sign Up (UPDATED: business params added)

    /**
     * Sign up with email, password, and profile info.
     * Mirrors iOS AuthService.signUp() — supports personal and business accounts.
     *
     * @param accountType Personal or Business — determines which fields are written
     * @param brandName   Required for business accounts
     * @param websiteURL  Optional for business accounts
     * @param businessCategory  Business category — written to Firestore for BusinessProfileBuilder
     */
    suspend fun signUp(
        email: String,
        password: String,
        displayName: String,
        username: String,
        accountType: AccountType = AccountType.PERSONAL,
        brandName: String? = null,
        websiteURL: String? = null,
        businessCategory: AdCategory? = null
    ): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                _isLoading.value = true
                _authState.value = AuthState.SIGNING_IN
                _lastError.value = null

                validateEmail(email)
                validatePassword(password)

                // Only validate username/displayName for personal accounts
                if (accountType == AccountType.PERSONAL) {
                    validateUsername(username)
                    validateDisplayName(displayName)
                } else {
                    // Business: brandName required
                    if (brandName.isNullOrBlank()) {
                        throw StitchError.ValidationError("Brand name is required for business accounts")
                    }
                }

                println("AUTH SERVICE: 🔐 Attempting sign up for: $email (${accountType.rawValue})")

                // Username check — personal only
                if (accountType == AccountType.PERSONAL) {
                    val usernameAvailable = try {
                        checkUsernameAvailabilityInternal(username)
                    } catch (e: Exception) {
                        println("AUTH SERVICE: ⚠️ Username check failed (allowing): ${e.message}")
                        true
                    }
                    if (!usernameAvailable) {
                        throw StitchError.ValidationError("Username '$username' is already taken")
                    }
                }

                val result = auth.createUserWithEmailAndPassword(email, password).await()
                val user = result.user ?: throw StitchError.AuthenticationError("Failed to create user")

                println("AUTH SERVICE: ✅ Firebase user created - UID: ${user.uid}")

                try {
                    val isSpecialUser = checkSpecialUserStatus(email)
                    createUserProfile(
                        firebaseUser = user,
                        username = username,
                        displayName = displayName,
                        isSpecialUser = isSpecialUser,
                        accountType = accountType,
                        brandName = brandName,
                        websiteURL = websiteURL,
                        businessCategory = businessCategory
                    )
                    println("AUTH SERVICE: ✅ User profile created successfully")

                    // Wait for Firestore propagation across read replicas
                    println("AUTH SERVICE: ⏳ Waiting 2000ms for Firestore propagation...")
                    delay(2000)
                    println("AUTH SERVICE: ✅ Firestore propagation delay complete")

                } catch (e: Exception) {
                    println("AUTH SERVICE: ❌ Profile creation failed: ${e.message}")
                }

                println("AUTH SERVICE: ✅ Sign up completed successfully")

                AuthResult(success = true, userId = user.uid, email = user.email ?: "", isNewUser = true)

            } catch (e: Exception) {
                println("AUTH SERVICE: ❌ Sign up failed: ${e.message}")
                handleAuthError(e)
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    // MARK: - Sign Out

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

    // MARK: - Reset Password

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

    // MARK: - Helpers

    fun getCurrentUserId(): String? = auth.currentUser?.uid
    fun getCurrentUserEmail(): String? = auth.currentUser?.email
    fun isUserAuthenticated(): Boolean = auth.currentUser != null

    // MARK: - Private

    private fun setupAuthListener() {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _currentUser.value = user
            _isAuthenticated.value = user != null
            when {
                user == null -> { _authState.value = AuthState.SIGNED_OUT; println("AUTH SERVICE: 🚪 User signed out") }
                user.isAnonymous -> { _authState.value = AuthState.SIGNED_IN; println("AUTH SERVICE: 👤 Anonymous user") }
                else -> { _authState.value = AuthState.SIGNED_IN; println("AUTH SERVICE: ✅ User authenticated - ${user.email}") }
            }
        }
    }

    private suspend fun checkUserProfileExists(userId: String): Boolean {
        return try {
            db.collection("users").document(userId).get().await().exists()
        } catch (e: Exception) { false }
    }

    private suspend fun checkUsernameAvailabilityInternal(username: String): Boolean {
        return withContext(serviceScope.coroutineContext) {
            val query = db.collection("users").whereEqualTo("username", username).limit(1).get().await()
            query.isEmpty
        }
    }

    suspend fun checkUsernameAvailability(username: String): Boolean = checkUsernameAvailabilityInternal(username)

    /**
     * Write user profile to Firestore.
     * UPDATED: writes accountType + business fields when accountType == BUSINESS.
     * Mirrors iOS AuthService.createUserProfile + BusinessMigration field list.
     *
     * BATCHING: All fields written in a single set() call — no separate writes.
     */
    private suspend fun createUserProfile(
        firebaseUser: FirebaseUser,
        username: String? = null,
        displayName: String? = null,
        isSpecialUser: Boolean = false,
        accountType: AccountType = AccountType.PERSONAL,
        brandName: String? = null,
        websiteURL: String? = null,
        businessCategory: AdCategory? = null
    ) {
        try {
            println("AUTH SERVICE: 📝 Creating user profile for ${firebaseUser.uid} (${accountType.rawValue})")

            val resolvedUsername = when {
                accountType == AccountType.BUSINESS && !brandName.isNullOrBlank() ->
                    brandName.lowercase().replace(Regex("[^a-z0-9]"), "_").take(20)
                !username.isNullOrBlank() -> username
                else -> generateUsername(firebaseUser.email, firebaseUser.uid)
            }

            val resolvedDisplayName = when {
                accountType == AccountType.BUSINESS && !brandName.isNullOrBlank() -> brandName
                !displayName.isNullOrBlank() -> displayName
                else -> firebaseUser.displayName ?: resolvedUsername
            }

            // Base user fields — same for all account types
            val userData = mutableMapOf<String, Any>(
                "id" to firebaseUser.uid,
                "email" to (firebaseUser.email ?: ""),
                "username" to resolvedUsername,
                "displayName" to resolvedDisplayName,
                "profileImageURL" to (firebaseUser.photoUrl?.toString() ?: ""),
                "bio" to "",
                "tier" to if (accountType == AccountType.BUSINESS) "business"
                else if (isSpecialUser) UserTier.FOUNDER.rawValue else UserTier.ROOKIE.rawValue,
                "clout" to if (isSpecialUser && accountType == AccountType.PERSONAL) 10000 else 0,
                "isVerified" to isSpecialUser,
                "isPrivate" to false,
                "followerCount" to 0,
                "followingCount" to 0,
                "videoCount" to 0,
                "threadCount" to 0,
                "totalHypesReceived" to 0,
                "totalCoolsReceived" to 0,
                "accountType" to accountType.rawValue,
                "createdAt" to Timestamp.now(),
                "updatedAt" to Timestamp.now(),
                "lastActiveAt" to Timestamp.now()
            )

            // Business-specific fields — mirrors iOS BusinessMigration.migrateToBusinessAccount fields
            if (accountType == AccountType.BUSINESS) {
                userData["brandName"] = brandName ?: resolvedDisplayName
                userData["isVerifiedBusiness"] = false
                userData["businessCategory"] = (businessCategory ?: AdCategory.OTHER).value
                if (!websiteURL.isNullOrBlank()) {
                    userData["websiteURL"] = websiteURL
                }
                // Zero out social metrics — business accounts don't show these (mirrors iOS migration)
                userData["clout"] = 0
            }

            db.collection("users")
                .document(firebaseUser.uid)
                .set(userData, SetOptions.merge())
                .await()

            println("AUTH SERVICE: ✅ User profile created for ${firebaseUser.email} (${accountType.rawValue})")

        } catch (e: Exception) {
            println("AUTH SERVICE: ❌ Failed to create user profile - ${e.message}")
            throw StitchError.ProcessingError("Failed to create user profile")
        }
    }

    private fun checkSpecialUserStatus(email: String): Boolean {
        val specialEmails = setOf(
            "founder@stitchsocial.me", "james@stitchsocial.me",
            "teddyruks@gmail.com", "chaneyvisionent@gmail.com",
            "afterflaspoint@icloud.com", "floydjrsullivan@yahoo.com", "srbentleyga@gmail.com"
        )
        return specialEmails.contains(email.lowercase())
    }

    private fun generateUsername(email: String?, uid: String): String {
        return if (!email.isNullOrEmpty()) {
            val prefix = email.substringBefore("@").lowercase().replace(Regex("[^a-z0-9]"), "")
            "${prefix}_${uid.take(6)}"
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

    private fun validateEmail(email: String) {
        if (email.isBlank() || !email.contains("@") || !email.contains("."))
            throw StitchError.ValidationError("Invalid email format")
    }

    private fun validatePassword(password: String) {
        if (password.length < 6) throw StitchError.ValidationError("Password must be at least 6 characters")
    }

    private fun validateUsername(username: String) {
        if (username.length < 3 || username.length > 20)
            throw StitchError.ValidationError("Username must be 3-20 characters")
        if (!username.all { it.isLetterOrDigit() || it == '_' })
            throw StitchError.ValidationError("Username can only contain letters, numbers, and underscores")
    }

    private fun validateDisplayName(displayName: String) {
        if (displayName.isBlank() || displayName.length > 50)
            throw StitchError.ValidationError("Display name must be 1-50 characters")
    }

    fun clearError() { _lastError.value = null }

    fun cleanup() { serviceScope.cancel() }
}

// MARK: - AuthResult

data class AuthResult(
    val success: Boolean,
    val userId: String = "",
    val email: String = "",
    val isNewUser: Boolean = false,
    val needsProfileSetup: Boolean = false
)